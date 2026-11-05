package com.daliar26.androidassistant;

import android.app.Activity;
import android.os.Bundle;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.widget.*;
import android.graphics.Color;
import android.view.Gravity;
import java.util.List;

public class MainActivity extends Activity {

    private MediaSessionManager manager;
    private MediaController controller;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        manager = (MediaSessionManager)
            getSystemService(MEDIA_SESSION_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 40, 28, 28);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.rgb(18,18,24));

        TextView title = new TextView(this);
        title.setText("ANDROID ASSISTANT");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0,30,0,30);
        layout.addView(status);

        addButton(layout, "РАЗРЕШИТЬ ДОСТУП", v -> connect());
        addButton(layout, "НАЙТИ ПЛЕЕР", v -> refresh());
        addButton(layout, "PLAY / PAUSE", v -> control(0));
        addButton(layout, "ПРЕДЫДУЩИЙ ТРЕК", v -> control(1));
        addButton(layout, "СЛЕДУЮЩИЙ ТРЕК", v -> control(2));
        addButton(layout, "ОСТАНОВИТЬ", v -> control(3));

        setContentView(layout);
        refresh();
    }

    private void addButton(LinearLayout layout, String text,
            android.view.View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(text);
        layout.addView(button);
        button.setOnClickListener(action);
    }

    private void connect() {
        startActivity(new Intent(
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private void refresh() {
        try {
            ComponentName component =
                new ComponentName(this, MediaListener.class);

            List<MediaController> sessions =
                manager.getActiveSessions(component);

            if (sessions == null || sessions.isEmpty()) {
                controller = null;
                status.setText("Активный плеер не найден");
                return;
            }

            controller = sessions.get(0);
            status.setText("Подключен: " +
                controller.getPackageName());

        } catch (SecurityException e) {
            status.setText("Разреши доступ к уведомлениям");
        } catch (Exception e) {
            status.setText("Ошибка поиска плеера");
        }
    }

    private void control(int action) {
        if (controller == null) {
            refresh();
            return;
        }

        MediaController.TransportControls controls =
            controller.getTransportControls();

        switch (action) {
            case 0:
                if (controller.getPlaybackState() != null &&
                    controller.getPlaybackState().getState() ==
                    android.media.session.PlaybackState.STATE_PLAYING) {
                    controls.pause();
                } else {
                    controls.play();
                }
                break;
            case 1:
                controls.skipToPrevious();
                break;
            case 2:
                controls.skipToNext();
                break;
            case 3:
                controls.stop();
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
