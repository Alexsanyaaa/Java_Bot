package mentor.focus;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;

public final class FocusService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        SharedPreferences prefs = getSharedPreferences("focus", MODE_PRIVATE);
        String app = event.getPackageName().toString();
        if (!prefs.getBoolean("enabled", false) || app.equals(getPackageName())
                || !prefs.getStringSet("apps", Collections.emptySet()).contains(app)) return;
        var window = FocusSchedule.active(prefs.getString("schedule", FocusSchedule.HEADER),
                System.currentTimeMillis(), true);
        if (window != null && performGlobalAction(GLOBAL_ACTION_HOME)) {
            prefs.edit().putInt("blocked", prefs.getInt("blocked", 0) + 1).apply();
        }
    }

    @Override public void onInterrupt() {}
}
