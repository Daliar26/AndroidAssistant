package com.daliar26.subtitles;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.media.projection.*;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import android.util.Log;

import androidx.core.app.ServiceCompat;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.zip.*;

public class SubtitleForegroundService extends Service {

    private static final String TAG = "SubtitleApp";
    private static final String CHANNEL = "subtitle_channel";
    private static final int NOTIFICATION_ID = 77;

    private volatile boolean running;
    private AudioRecord recorder;
    private MediaProjection projection;
    private Model model;
    private Recognizer recognizer;
    private Thread worker;

    private WindowManager windowManager;
    private TextView subtitleView;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean system = intent.getBooleanExtra("system", false);

        Notification notification = new Notification.Builder(this, CHANNEL)
            .setContentTitle("SubtitleApp")
            .setContentText("Распознавание речи работает")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build();

        int types = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            types = system
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, types);

        if (worker != null && worker.isAlive()) {
            return START_NOT_STICKY;
        }

        String lang = intent.getStringExtra("language");
        if (lang == null) lang = "ru";

        final String selectedLanguage = lang;
        final boolean captureSystem = system;
        final int resultCode = intent.getIntExtra("resultCode", -1);
        final Intent projectionData =
            intent.getParcelableExtra("projectionData");

        running = true;

        worker = new Thread(() -> {
            try {
                startRecognition(
                    captureSystem, selectedLanguage,
                    resultCode, projectionData);
            } catch (Exception e) {
                Log.e(TAG, "Recognition error", e);
                showText("Ошибка: " + e.getMessage());
                stopSelf();
            }
        });

        worker.start();
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Subtitle recognition",
                NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class)
                .createNotificationChannel(channel);
        }
    }

    private void startRecognition(
        boolean system, String lang,
        int resultCode, Intent projectionData) throws Exception {

        if (system) {
            if (Build.VERSION.SDK_INT < 29 ||
                projectionData == null || resultCode == -1) {
                throw new Exception("Нет разрешения MediaProjection");
            }

            MediaProjectionManager manager =
                (MediaProjectionManager)getSystemService(
                    MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(
                resultCode, projectionData);

            if (projection == null) {
                throw new Exception("MediaProjection недоступен");
            }

            projection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        running = false;
                        stopSelf();
                    }
                }, main);
        }

        showText("Загрузка модели распознавания...");

        File modelDir = getModel(lang);
        if (!running) return;

        model = new Model(modelDir.getAbsolutePath());
        recognizer = new Recognizer(model, 16000.0f);

        showText("Модель готова. Слушаю...");

        int min = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);

        int bufferSize = Math.max(min, 8192);

        AudioFormat format = new AudioFormat.Builder()
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build();

        if (system) {
            AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            recorder = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();
        } else {
            recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);
        }

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new Exception("AudioRecord не инициализирован");
        }

        recorder.startRecording();
        showText("Слушаю...");

        byte[] buffer = new byte[4096];
        StringBuilder completed = new StringBuilder();

        while (running) {
            int count = recorder.read(buffer, 0, buffer.length);
            if (count <= 0) continue;

            if (recognizer.acceptWaveForm(buffer, count)) {
                JSONObject result =
                    new JSONObject(recognizer.getResult());

                String text = result.optString("text", "").trim();

                if (!text.isEmpty()) {
                    if (completed.length() > 0) {
                        completed.append(" ");
                    }
                    completed.append(text);

                    if (completed.length() > 350) {
                        completed.delete(0, completed.length() - 250);
                    }
                }

                showText(completed.toString());

            } else {
                JSONObject partial =
                    new JSONObject(recognizer.getPartialResult());

                String text = partial.optString("partial", "").trim();

                if (!text.isEmpty()) {
                    showText(completed.toString() + " " + text);
                }
            }
        }
    }

    private File getModel(String lang) throws Exception {
        File base = new File(getFilesDir(), "models");
        if (!base.exists()) base.mkdirs();

        String modelName = lang.equals("en")
            ? "vosk-model-small-en-us-0.15"
            : "vosk-model-small-ru-0.22";

        File destination = new File(base, modelName);

        if (new File(destination, "am/final.mdl").exists()) {
            return destination;
        }

        String url = "https://alphacephei.com/vosk/models/"
            + modelName + ".zip";

        File archive = new File(base, modelName + ".zip");

        HttpURLConnection connection =
            (HttpURLConnection)new URL(url).openConnection();

        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.connect();

        if (connection.getResponseCode() != 200) {
            throw new Exception("Не удалось загрузить языковую модель");
        }

        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(archive)) {

            byte[] data = new byte[16384];
            int n;

            while ((n = in.read(data)) != -1) {
                if (!running) throw new Exception("Остановлено");
                out.write(data, 0, n);
            }
        } finally {
            connection.disconnect();
        }

        destination.mkdirs();

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {

            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                String relative = slash >= 0
                    ? name.substring(slash + 1) : name;

                if (relative.isEmpty()) continue;

                File output = new File(destination, relative);

                if (!output.getCanonicalPath().startsWith(
                    destination.getCanonicalPath() + File.separator)) {
                    throw new IOException("Недопустимый путь архива");
                }

                if (entry.isDirectory()) {
                    output.mkdirs();
                } else {
                    File parent = output.getParentFile();
                    if (parent != null) parent.mkdirs();

                    try (FileOutputStream out =
                             new FileOutputStream(output)) {
                        byte[] data = new byte[16384];
                        int n;
                        while ((n = zip.read(data)) != -1) {
                            out.write(data, 0, n);
                        }
                    }
                }

                zip.closeEntry();
            }
        }

        archive.delete();

        if (!new File(destination, "am/final.mdl").exists()) {
            throw new Exception("Модель распакована некорректно");
        }

        return destination;
    }

    private void showText(String text) {
        main.post(() -> {
            try {
                if (!Settings.canDrawOverlays(this)) return;

                if (windowManager == null) {
                    windowManager =
                        (WindowManager)getSystemService(WINDOW_SERVICE);
                }

                if (subtitleView == null) {
                    subtitleView = new TextView(this);
                    subtitleView.setTextColor(Color.WHITE);
                    subtitleView.setTextSize(21);
                    subtitleView.setGravity(Gravity.CENTER);
                    subtitleView.setPadding(18, 12, 18, 12);

                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xDD111111);
                    bg.setCornerRadius(16);
                    subtitleView.setBackground(bg);

                    WindowManager.LayoutParams params =
                        new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT);

                    params.gravity =
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    params.y = 130;

                    windowManager.addView(subtitleView, params);
                }

                subtitleView.setText(text);
            } catch (Exception e) {
                Log.e(TAG, "Overlay error", e);
            }
        });
    }

    private void removeOverlay() {
        main.post(() -> {
            try {
                if (subtitleView != null && windowManager != null) {
                    windowManager.removeView(subtitleView);
                }
            } catch (Exception ignored) {}

            subtitleView = null;
        });
    }

    @Override
    public void onDestroy() {
        running = false;

        try {
            if (recorder != null) {
                if (recorder.getRecordingState() ==
                    AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
                recorder.release();
                recorder = null;
            }
        } catch (Exception ignored) {}

        try {
            if (projection != null) {
                projection.stop();
                projection = null;
            }
        } catch (Exception ignored) {}

        try {
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
        } catch (Exception ignored) {}

        removeOverlay();

        ServiceCompat.stopForeground(
            this, ServiceCompat.STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
