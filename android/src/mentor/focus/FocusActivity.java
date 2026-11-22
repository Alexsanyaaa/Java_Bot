package mentor.focus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class FocusActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView status;
    private Button toggle;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("focus", MODE_PRIVATE);
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad * 2, pad, pad);
        layout.setBackgroundColor(Color.rgb(244, 248, 245));
        var title = new TextView(this);
        title.setText("Mentor Focus");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(19, 52, 42));
        layout.addView(title);
        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, pad, 0, pad);
        layout.addView(status);
        button(layout, "Импортировать учебные окна", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, 1);
        });
        button(layout, "Выбрать отвлекающие приложения", this::chooseApps);
        button(layout, "Настроить доступ на Android", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        toggle = button(layout, "", () -> {
            prefs.edit().putBoolean("enabled", !prefs.getBoolean("enabled", false)).apply();
            renderStatus();
        });
        setContentView(layout);
    }

    private Button button(LinearLayout layout, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        layout.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    @Override protected void onResume() {
        super.onResume();
        refresh.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void renderStatus() {
        String schedule = prefs.getString("schedule", FocusSchedule.HEADER);
        long now = System.currentTimeMillis();
        var window = FocusSchedule.active(schedule, now, false);
        String time = "Нет ближайших окон";
        if (window != null) {
            long seconds = Math.max(0, (window.end - now + 999) / 1000);
            time = (window.strict ? "Строгое окно" : "Учебное окно")
                    + String.format(Locale.ROOT, " · %02d:%02d до конца", seconds / 60, seconds % 60);
        } else {
            var next = FocusSchedule.parse(schedule).stream().filter(item -> item.start > now)
                    .min(java.util.Comparator.comparingLong(item -> item.start));
            if (next.isPresent()) time = "Следующее окно · " + DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(new Date(next.get().start));
        }
        boolean enabled = prefs.getBoolean("enabled", false);
        status.setText(time + "\nПриложений выбрано: " + prefs.getStringSet("apps", Collections.emptySet()).size()
                + "\nВозвратов на главный экран: " + prefs.getInt("blocked", 0));
        toggle.setText(enabled ? "Приостановить ограничения" : "Включить ограничения");
    }

    private void chooseApps() {
        var manager = getPackageManager();
        var apps = manager.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        var dialers = new LinkedHashSet<String>();
        for (ResolveInfo item : manager.queryIntentActivities(
                new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:")), 0)) dialers.add(item.activityInfo.packageName);
        var selected = new LinkedHashSet<>(prefs.getStringSet("apps", Collections.emptySet()));
        var ids = new ArrayList<String>();
        var labels = new ArrayList<String>();
        for (ResolveInfo item : apps) {
            String id = item.activityInfo.packageName;
            if (id.equals(getPackageName()) || id.equals("com.android.settings") || dialers.contains(id) || ids.contains(id)) continue;
            ids.add(id);
            labels.add(item.loadLabel(manager).toString());
        }
        boolean[] checked = new boolean[ids.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = selected.contains(ids.get(i));
        new AlertDialog.Builder(this).setTitle("Возвращать на главный экран в строгие окна")
                .setMultiChoiceItems(labels.toArray(new String[0]), checked, (dialog, index, on) -> {
                    if (on) selected.add(ids.get(index)); else selected.remove(ids.get(index));
                })
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    prefs.edit().putStringSet("apps", selected).apply();
                    renderStatus();
                }).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (var stream = getContentResolver().openInputStream(data.getData())) {
            if (stream == null) throw new IOException();
            var bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int count; (count = stream.read(buffer)) != -1;) {
                if (bytes.size() + count > 8192) throw new IOException();
                bytes.write(buffer, 0, count);
            }
            String schedule = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            int count = FocusSchedule.parse(schedule).size();
            prefs.edit().putString("schedule", schedule).apply();
            Toast.makeText(this, "Импортировано окон: " + count, Toast.LENGTH_SHORT).show();
            renderStatus();
        } catch (IOException | IllegalArgumentException error) {
            Toast.makeText(this, "Выбери файл mentor-focus.txt из Telegram", Toast.LENGTH_LONG).show();
        }
    }
}
