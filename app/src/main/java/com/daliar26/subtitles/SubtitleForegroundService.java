package com.daliar26.subtitles;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.projection.*;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import android.util.Log;

import androidx.core.app.ServiceCompat;

import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;

public class SubtitleForegroundService extends Service {
    public static final String ACTION_DELETE_MODEL = "subtitle.DELETE_MODEL";
    public static final String ACTION_STATUS = "subtitle.STATUS";
    private static final String TAG = "SubtitleApp";
    private static final String CHANNEL = "subtitle_channel";
    private static final int NOTIFICATION_ID = 77;

    private volatile boolean running;
    private volatile boolean showOriginal = true;
    private AudioRecord recorder;
    private MediaProjection projection;
    private Model model;
    private Recognizer recognizer;
    private Translator translator;
    private Thread worker;
    private WindowManager windowManager;
    private TextView subtitleView;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl automaticGainControl;
    private AcousticEchoCanceler echoCanceler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService translationQueue = Executors.newSingleThreadExecutor();
    private final AtomicLong displayGeneration = new AtomicLong(0);
    private String targetCode = "ru";

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
            CHANNEL, "SubtitleApp — субтитры", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_DELETE_MODEL.equals(intent.getAction())) {
            deleteModel(intent.getIntExtra("deleteType", -1), intent.getStringExtra("target"));
            return START_NOT_STICKY;
        }

        boolean system = intent.getBooleanExtra("system", false);
        String speech = intent.getStringExtra("speech");
        if (speech == null) speech = "ru";
        targetCode = intent.getStringExtra("target");
        if (targetCode == null) targetCode = "ru";
        showOriginal = intent.getBooleanExtra("showOriginal", true);

        Notification n = new Notification.Builder(this, CHANNEL)
            .setContentTitle("SubtitleApp 3.1")
            .setContentText("Подготовка распознавания и перевода")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build();
        int type = 0;
        if (Build.VERSION.SDK_INT >= 29) type = system
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type);

        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        final String inputLang = speech;
        final boolean captureSystem = system;
        final int resultCode = intent.getIntExtra("resultCode", -1);
        final Intent projectionData = intent.getParcelableExtra("projectionData");
        running = true;
        worker = new Thread(() -> {
            try { runRecognition(captureSystem, inputLang, resultCode, projectionData); }
            catch (Exception e) {
                Log.e(TAG, "Processing error", e);
                status("Ошибка: " + readableError(e));
                showSubtitle("", "Ошибка: " + readableError(e));
                stopSelf();
            }
        }, "subtitle-worker");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runRecognition(boolean system, String lang, int code, Intent data) throws Exception {
        if (system) {
            if (Build.VERSION.SDK_INT < 29 || data == null || code == -1)
                throw new Exception("Не получено разрешение на захват системного звука");
            MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(code, data);
            if (projection == null) throw new Exception("Системный захват недоступен");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { running = false; stopSelf(); }
            }, main);
        }

        status("Проверяем модель распознавания речи...");
        File modelDir = getSpeechModel(lang);
        if (!running) return;
        status("Загружаем модель распознавания в память...");
        model = new Model(modelDir.getAbsolutePath());
        recognizer = new Recognizer(model, 16000.0f);
        recognizer.setWords(true);
        recognizer.setPartialWords(true);

        status("Подготавливаем переводчик...");
        if (!lang.equals(targetCode)) {
            TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(lang).setTargetLanguage(targetCode).build();
            translator = Translation.getClient(options);
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            final Object lock = new Object();
            final boolean[] ready = {false};
            final Exception[] error = {null};
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v -> { synchronized(lock) { ready[0] = true; lock.notifyAll(); }})
                .addOnFailureListener(e -> { synchronized(lock) {
                    error[0] = new Exception("Не удалось загрузить модель перевода: " + e.getMessage());
                    lock.notifyAll();
                }});
            synchronized(lock) {
                long end = System.currentTimeMillis() + 240000;
                while (!ready[0] && error[0] == null && System.currentTimeMillis() < end) lock.wait(500);
            }
            if (!ready[0]) {
                if (error[0] != null) throw error[0];
                throw new Exception("Истекло время ожидания загрузки перевода. Проверьте интернет.");
            }
        } else status("Языки совпадают — перевод не требуется.");

        AudioFormat format = new AudioFormat.Builder().setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new Exception("Телефон не поддерживает выбранный формат аудио");
        int bufferSize = Math.max(min * 2, 16384);
        if (system) {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            recorder = new AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize).setAudioPlaybackCaptureConfig(config).build();
        } else {
            recorder = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format).setBufferSizeInBytes(bufferSize).build();
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
            throw new Exception("Не удалось открыть источник аудио");

        if (!system) enableMicrophoneEnhancements();
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
            throw new Exception("Аудиозапись не запустилась");
        status(system ? "Слушаю системный звук..." : "Слушаю микрофон. Шумоподавление включено, если поддерживается телефоном.");

        byte[] buffer = new byte[8192];
        StringBuilder completed = new StringBuilder();
        long lastPartialUpdate = 0;
        while (running) {
            int count = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
            if (count < 0) throw new Exception("Ошибка чтения аудио: " + count);
            if (count == 0) continue;
            if (recognizer.acceptWaveForm(buffer, count)) {
                JSONObject result = new JSONObject(recognizer.getResult());
                String phrase = result.optString("text", "").trim();
                if (!phrase.isEmpty()) {
                    if (completed.length() > 0) completed.append(". ");
                    completed.append(phrase);
                    String window = lastSentences(completed.toString(), 3);
                    translateAndShow(window);
                    if (completed.length() > 1400) completed.delete(0, completed.length() - 850);
                }
            } else {
                long now = System.currentTimeMillis();
                if (now - lastPartialUpdate > 350) {
                    JSONObject partial = new JSONObject(recognizer.getPartialResult());
                    String p = partial.optString("partial", "").trim();
                    if (!p.isEmpty()) {
                        String provisional = completed.length() == 0 ? p : completed + ". " + p;
                        showSubtitle(provisional, "Распознаю речь…");
                    }
                    lastPartialUpdate = now;
                }
            }
        }
        try {
            String finalText = new JSONObject(recognizer.getFinalResult()).optString("text", "").trim();
            if (!finalText.isEmpty()) translateAndShow(finalText);
        } catch (Exception e) { Log.w(TAG, "Final recognition result", e); }
    }

    private void enableMicrophoneEnhancements() {
        try {
            int session = recorder.getAudioSessionId();
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(session);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(session);
                if (automaticGainControl != null) automaticGainControl.setEnabled(true);
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(session);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (Exception e) { Log.w(TAG, "Audio enhancement unavailable", e); }
    }

    private void translateAndShow(String original) {
        if (original == null || original.trim().isEmpty()) return;
        final long generation = displayGeneration.incrementAndGet();
        if (translator == null) { showSubtitle(original, original); return; }
        translationQueue.execute(() -> {
            if (generation != displayGeneration.get()) return;
            final CountDownLatch latch = new CountDownLatch(1);
            translator.translate(original)
                .addOnSuccessListener(translated -> {
                    if (generation == displayGeneration.get()) showSubtitle(original, translated);
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    if (generation == displayGeneration.get()) {
                        status("Речь распознана, но перевод не выполнен: " + e.getMessage());
                        showSubtitle(original, "Перевод недоступен");
                    }
                    latch.countDown();
                });
            try { latch.await(45, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }

    private String lastSentences(String text, int max) {
        if (text == null || text.trim().isEmpty()) return "";
        String[] parts = text.trim().split("(?<=[.!?])\\s+");
        int start = Math.max(0, parts.length - max);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(parts[i]);
        }
        return out.toString();
    }

    private File getSpeechModel(String lang) throws Exception {
        File base = new File(getFilesDir(), "models");
        if (!base.exists() && !base.mkdirs()) throw new Exception("Не удалось создать папку моделей");
        String name = lang.equals("en") ? "vosk-model-small-en-us-0.15" : "vosk-model-small-ru-0.22";
        File dest = new File(base, name);
        if (new File(dest, "am/final.mdl").exists()) return dest;
        status("Загружаем модель речи " + (lang.equals("ru") ? "русского" : "английского") + " языка...");
        File zipFile = new File(base, name + ".zip");
        HttpURLConnection c = (HttpURLConnection)new URL(
            "https://alphacephei.com/vosk/models/" + name + ".zip").openConnection();
        c.setConnectTimeout(25000); c.setReadTimeout(120000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SubtitleApp/3.1 Android");
        try {
            c.connect();
            int response = c.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK)
                throw new Exception("Сервер модели ответил: HTTP " + response);
            int length = c.getContentLength();
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 FileOutputStream out = new FileOutputStream(zipFile)) {
                byte[] b = new byte[65536]; int n; long total = 0; int last = -1;
                while ((n = in.read(b)) != -1) {
                    if (!running) throw new Exception("Загрузка остановлена");
                    out.write(b, 0, n); total += n;
                    if (length > 0) {
                        int percent = (int)(total * 100 / length);
                        if (percent != last) { last = percent; status("Загрузка модели речи: " + percent + "%"); }
                    } else if (total / (1024*1024) != (total-n) / (1024*1024)) {
                        status("Загружено " + (total/(1024*1024)) + " МБ модели речи");
                    }
                }
            }
        } finally { c.disconnect(); }
        if (!zipFile.exists() || zipFile.length() < 1024*1024)
            throw new Exception("Архив модели загружен не полностью");
        deleteRecursively(dest);
        if (!dest.mkdirs() && !dest.isDirectory()) throw new Exception("Не удалось создать папку модели");
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String nameInZip = entry.getName();
                int slash = nameInZip.indexOf('/');
                String relative = slash >= 0 ? nameInZip.substring(slash + 1) : nameInZip;
                if (relative.isEmpty()) { zip.closeEntry(); continue; }
                File outFile = new File(dest, relative);
                if (!outFile.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator))
                    throw new IOException("Некорректный путь в архиве");
                if (entry.isDirectory()) outFile.mkdirs();
                else {
                    File parent = outFile.getParentFile(); if (parent != null) parent.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] b = new byte[65536]; int n;
                        while ((n = zip.read(b)) != -1) out.write(b, 0, n);
                    }
                }
                zip.closeEntry();
            }
        } finally { zipFile.delete(); }
        if (!new File(dest, "am/final.mdl").exists()) {
            deleteRecursively(dest);
            throw new Exception("Модель не распаковалась полностью. Повторите загрузку.");
        }
        return dest;
    }

    private void deleteModel(int type, String target) {
        new Thread(() -> {
            try {
                if (type == 0 || type == 1) {
                    String name = type == 0 ? "vosk-model-small-ru-0.22" : "vosk-model-small-en-us-0.15";
                    deleteRecursively(new File(new File(getFilesDir(), "models"), name));
                    status("Модель распознавания удалена.");
                } else if (type == 2 && target != null) {
                    TranslateRemoteModel remote = new TranslateRemoteModel.Builder(target).build();
                    RemoteModelManager.getInstance().deleteDownloadedModel(remote)
                        .addOnSuccessListener(v -> status("Модель перевода удалена."))
                        .addOnFailureListener(e -> status("Не удалось удалить модель: " + e.getMessage()));
                }
            } catch (Exception e) { status("Ошибка удаления: " + e.getMessage()); }
        }).start();
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void showSubtitle(String original, String translated) {
        String text = (showOriginal && original != null && !original.isEmpty()
            ? "ОРИГИНАЛ\n" + original + "\n\n" : "")
            + "ПЕРЕВОД\n" + (translated == null ? "" : translated);
        main.post(() -> {
            try {
                if (!Settings.canDrawOverlays(this)) return;
                if (windowManager == null) windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
                if (subtitleView == null) {
                    subtitleView = new TextView(this);
                    subtitleView.setTextColor(Color.WHITE);
                    subtitleView.setTextSize(18);
                    subtitleView.setGravity(Gravity.CENTER);
                    subtitleView.setPadding(18, 14, 18, 14);
                    subtitleView.setMaxLines(8);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xE6111722); bg.setCornerRadius(18);
                    subtitleView.setBackground(bg);
                    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                    p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; p.y = 120;
                    windowManager.addView(subtitleView, p);
                }
                subtitleView.setText(text);
            } catch (Exception e) { Log.e(TAG, "Overlay error", e); }
        });
    }

    private void status(String message) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra("message", message);
        sendBroadcast(i);
        Log.i(TAG, message);
    }

    private String readableError(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? "неизвестная ошибка" : m;
    }

    @Override public void onDestroy() {
        running = false;
        try { if (recorder != null && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        releaseEffect(noiseSuppressor); noiseSuppressor = null;
        releaseEffect(automaticGainControl); automaticGainControl = null;
        releaseEffect(echoCanceler); echoCanceler = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        try { if (translator != null) translator.close(); } catch (Exception ignored) {}
        translator = null;
        try { if (recognizer != null) recognizer.close(); if (model != null) model.close(); } catch (Exception ignored) {}
        recognizer = null; model = null;
        translationQueue.shutdownNow();
        main.post(() -> {
            try { if (subtitleView != null && windowManager != null) windowManager.removeView(subtitleView); }
            catch (Exception ignored) {}
            subtitleView = null;
        });
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void releaseEffect(android.media.audiofx.AudioEffect effect) {
        try { if (effect != null) effect.release(); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
