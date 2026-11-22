package mentor.focus;

import java.util.ArrayList;
import java.util.List;

final class FocusSchedule {
    static final String HEADER = "MENTOR_FOCUS_1";

    static final class Window {
        final long start;
        final long end;
        final boolean strict;

        Window(long start, long end, boolean strict) {
            this.start = start;
            this.end = end;
            this.strict = strict;
        }
    }

    static List<Window> parse(String text) {
        String[] lines = text.trim().split("\\R");
        if (lines.length > 101 || !HEADER.equals(lines[0])) throw new IllegalArgumentException();
        List<Window> windows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split(",", -1);
            if (fields.length != 3 || !(fields[2].equals("true") || fields[2].equals("false"))) {
                throw new IllegalArgumentException();
            }
            long start = Long.parseLong(fields[0]);
            long end = Long.parseLong(fields[1]);
            if (start < 0 || end <= start || end - start > 8_100_000L) throw new IllegalArgumentException();
            windows.add(new Window(start, end, Boolean.parseBoolean(fields[2])));
        }
        return windows;
    }

    static Window active(String text, long now, boolean strictOnly) {
        for (Window window : parse(text)) {
            if (window.start <= now && now < window.end && (!strictOnly || window.strict)) return window;
        }
        return null;
    }
}
