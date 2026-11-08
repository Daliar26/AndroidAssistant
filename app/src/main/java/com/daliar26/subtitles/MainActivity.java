package com.daliar26.subtitles;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION = 101;
    private static final int OVERLAY_PERMISSION = 102;
    private static final int PROJECTION_REQUEST = 103;
    private Spinner speechLanguage, targetLanguage;
    private CheckBox showOriginal;
    private TextView status;
    private boolean pendingSystem;
    private boolean receiverRegistered;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (status != null) status.setText(intent.getStringExtra("message"));
        }
    };
    private final String[] targetNames = {
        "Русский", "English", "Français", "Deutsch", "Español",
        "Italiano", "Português", "中文", "日本語", "한국어"
    };
    private final String[] targetCodes = {
        "ru", "en", "fr", "de", "es", "it", "pt", "zh", "ja", "ko"
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(SubtitleForegroundService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(statusReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    @Override protected void onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 28, 24, 24);

        TextView title = new TextView(this);
        title.setText("SubtitleApp 3.1");
        title.setTextSize(27);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("Готово. Выберите языки и режим.");
        status.setTextSize(15);
        status.setPadding(0, 18, 0, 18);
        root.addView(status);

        addLabel(root, "Язык оригинальной речи");
        speechLanguage = new Spinner(this);
        speechLanguage.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Русский", "English"}));
        root.addView(speechLanguage);

        addLabel(root, "Язык перевода");
        targetLanguage = new Spinner(this);
        targetLanguage.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, targetNames));
        root.addView(targetLanguage);

        showOriginal = new CheckBox(this);
        showOriginal.setText("Показывать оригинальный текст");
        root.addView(showOriginal);

        android.content.SharedPreferences p =
            getSharedPreferences("subtitle_settings", MODE_PRIVATE);
        speechLanguage.setSelection(p.getInt("speech", 0));
        targetLanguage.setSelection(p.getInt("target", 0));
        showOriginal.setChecked(p.getBoolean("original", true));

        speechLanguage.setOnItemSelectedListener(new SimpleSelectListener(
            () -> saveSettings()));
        targetLanguage.setOnItemSelectedListener(new SimpleSelectListener(
            () -> saveSettings()));
        showOriginal.setOnCheckedChangeListener((v, checked) -> saveSettings());

        addButton(root, "Запустить — системный звук", () -> begin(true));
        addButton(root, "Запустить — микрофон", () -> begin(false));
        addButton(root, "Остановить", this::stopNow);
        addButton(root, "Управление языковыми моделями", this::manageModels);

        TextView note = new TextView(this);
        note.setText("\nРаспознавание речи сейчас поддерживает русский и английский. "
            + "Перевод выполняется на устройстве после загрузки модели языка. "
            + "Интернет нужен для первой загрузки моделей.");
        note.setTextSize(13);
        root.addView(note);
        setContentView(root);
    }

    private void addLabel(LinearLayout root, String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(16);
        v.setPadding(0, 18, 0, 5);
        root.addView(v);
    }

    private void addButton(LinearLayout root, String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        root.addView(b, new LinearLayout.LayoutParams(-1, -2));
        b.setOnClickListener(v -> action.run());
    }

    private void saveSettings() {
        if (speechLanguage == null) return;
        getSharedPreferences("subtitle_settings", MODE_PRIVATE).edit()
            .putInt("speech", speechLanguage.getSelectedItemPosition())
            .putInt("target", targetLanguage.getSelectedItemPosition())
            .putBoolean("original", showOriginal.isChecked()).apply();
    }

    private void begin(boolean system) {
        pendingSystem = system;
        if (system && Build.VERSION.SDK_INT < 29) {
            status.setText("Системный звук доступен начиная с Android 10.");
            return;
        }
        if (ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())), OVERLAY_PERMISSION);
            status.setText("Разрешите SubtitleApp показывать субтитры поверх других приложений.");
            return;
        }
        continueStart(system);
    }

    private void continueStart(boolean system) {
        saveSettings();
        if (system) {
            MediaProjectionManager m = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), PROJECTION_REQUEST);
        } else launchService(false, -1, null);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) continueStart(pendingSystem);
            else status.setText("Разрешение на отображение субтитров не выдано.");
        } else if (requestCode == PROJECTION_REQUEST) {
            if (resultCode == RESULT_OK && data != null) launchService(true, resultCode, data);
            else status.setText("Захват системного звука отменён.");
        }
    }

    private void launchService(boolean system, int resultCode, Intent data) {
        int speechIndex = speechLanguage.getSelectedItemPosition();
        int targetIndex = targetLanguage.getSelectedItemPosition();
        Intent i = new Intent(this, SubtitleForegroundService.class);
        i.putExtra("system", system);
        i.putExtra("speech", speechIndex == 0 ? "ru" : "en");
        i.putExtra("target", targetCodes[targetIndex]);
        i.putExtra("showOriginal", showOriginal.isChecked());
        if (system) {
            i.putExtra("resultCode", resultCode);
            i.putExtra("projectionData", data);
        }
        ContextCompat.startForegroundService(this, i);
        status.setText("Запуск. Подготовка моделей и аудио...");
    }

    private void stopNow() {
        stopService(new Intent(this, SubtitleForegroundService.class));
        status.setText("Остановка запрошена.");
    }

    private void manageModels() {
        String[] options = {
            "Удалить русскую модель распознавания",
            "Удалить английскую модель распознавания",
            "Удалить модель выбранного языка перевода"
        };
        new AlertDialog.Builder(this).setTitle("Управление моделями")
            .setItems(options, (d, which) -> {
                Intent i = new Intent(this, SubtitleForegroundService.class);
                i.setAction(SubtitleForegroundService.ACTION_DELETE_MODEL);
                i.putExtra("deleteType", which);
                i.putExtra("target", targetCodes[targetLanguage.getSelectedItemPosition()]);
                startService(i);
                status.setText("Запрос на удаление модели отправлен.");
            }).setNegativeButton("Закрыть", null).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode,
        String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_PERMISSION && results.length > 0
            && results[0] == PackageManager.PERMISSION_GRANTED) {
            continueStart(pendingSystem);
        } else if (requestCode == AUDIO_PERMISSION) {
            status.setText("Для распознавания необходимо разрешение на микрофон.");
        }
    }

    private static class SimpleSelectListener implements AdapterView.OnItemSelectedListener {
        private final Runnable action;
        SimpleSelectListener(Runnable action) { this.action = action; }
        public void onItemSelected(AdapterView<?> p, android.view.View v, int pos, long id) {
            action.run();
        }
        public void onNothingSelected(AdapterView<?> p) {}
    }
}
