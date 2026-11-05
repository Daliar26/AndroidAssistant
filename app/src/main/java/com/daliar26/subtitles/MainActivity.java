package com.daliar26.subtitles;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final int AUDIO_PERMISSION = 101;
    private static final int OVERLAY_PERMISSION = 102;
    private static final int PROJECTION_REQUEST = 103;

    private Spinner language;
    private TextView status;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 35, 28, 28);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("SubtitleApp 2.0");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        status = new TextView(this);
        status.setText("Готово к запуску");
        status.setTextSize(16);
        status.setPadding(0, 25, 0, 25);
        layout.addView(status);

        language = new Spinner(this);
        String[] langs = {"Русский", "English"};
        language.setAdapter(new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, langs));
        layout.addView(language);

        addButton(layout, "Системный звук", () -> begin(true));
        addButton(layout, "Микрофон", () -> begin(false));
        addButton(layout, "Остановить субтитры", this::stopServiceNow);

        TextView info = new TextView(this);
        info.setText(
            "Системный звук: Android 10+\n" +
            "Микрофон: Android 8+\n" +
            "Первый запуск загрузит языковую модель."
        );
        info.setTextSize(14);
        info.setPadding(0, 30, 0, 0);
        layout.addView(info);

        setContentView(layout);
    }

    private void addButton(LinearLayout layout, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        layout.addView(button, new LinearLayout.LayoutParams(-1, -2));
        button.setOnClickListener(v -> action.run());
    }

    private void begin(boolean system) {
        if (system && Build.VERSION.SDK_INT < 29) {
            status.setText("Системный звук требует Android 10+");
            return;
        }

        if (ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{android.Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION);
            return;
        }

        if (Build.VERSION.SDK_INT >= 23 &&
            !Settings.canDrawOverlays(this)) {
            Intent i = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, OVERLAY_PERMISSION);
            status.setText("Разреши показ поверх приложений");
            return;
        }

        if (system) {
            MediaProjectionManager manager =
                (MediaProjectionManager)getSystemService(
                    MEDIA_PROJECTION_SERVICE);
            startActivityForResult(
                manager.createScreenCaptureIntent(),
                PROJECTION_REQUEST);
        } else {
            launchService(false, -1, null);
        }
    }

    @Override
    protected void onActivityResult(
        int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PROJECTION_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                launchService(true, resultCode, data);
            } else {
                status.setText("Захват системного звука отменён");
            }
        }
    }

    private void launchService(
        boolean system, int resultCode, Intent data) {

        Intent intent = new Intent(this, SubtitleForegroundService.class);
        intent.putExtra("system", system);
        intent.putExtra("language",
            language.getSelectedItemPosition() == 0 ? "ru" : "en");

        if (system) {
            intent.putExtra("resultCode", resultCode);
            intent.putExtra("projectionData", data);
        }

        ContextCompat.startForegroundService(this, intent);
        status.setText("Запуск распознавания...");
    }

    private void stopServiceNow() {
        stopService(new Intent(this, SubtitleForegroundService.class));
        status.setText("Остановлено");
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_PERMISSION &&
            results.length > 0 &&
            results[0] == PackageManager.PERMISSION_GRANTED) {
            status.setText("Разрешение получено. Нажми режим запуска ещё раз.");
        }
    }
}
