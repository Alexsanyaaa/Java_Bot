package mentor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

record TelegramText(String text, List<TelegramText.Entity> entities) {
    record Entity(String type, int offset, int length) {}

    // Only the constructs used by mentor replies. Code takes precedence over emphasis.
    private static final Pattern MARKUP = Pattern.compile(
            "(?m)^ {0,3}```[\\w+.-]*[ \\t]*\\r?\\n([\\s\\S]*?)(?:^ {0,3}```[ \\t]*$|\\z)"
            + "|`([^`\\r\\n]+)`|\\*\\*([^*\\r\\n]+)\\*\\*|^#{1,6}[ \\t]+([^\\r\\n]+)");

    static TelegramText format(String input) {
        var result = new StringBuilder();
        var entities = new ArrayList<Entity>();
        var match = MARKUP.matcher(input);
        int cursor = 0;
        while (match.find()) {
            result.append(input, cursor, match.start());
            String content;
            String type;
            if (match.group(1) != null) {
                content = match.group(1);
                type = "pre";
            } else if (match.group(2) != null) {
                content = match.group(2);
                type = "code";
            } else {
                content = match.group(3) != null ? match.group(3) : match.group(4);
                type = "bold";
            }
            if (!content.isBlank()) entities.add(new Entity(type, result.length(), content.length()));
            result.append(content);
            cursor = match.end();
        }
        result.append(input, cursor, input.length());
        return result.toString().isBlank() ? new TelegramText(input, List.of())
                : new TelegramText(result.toString(), entities);
    }

    List<Entity> entitiesIn(int start, int end) {
        var part = new ArrayList<Entity>();
        for (Entity entity : entities) {
            int from = Math.max(start, entity.offset());
            int to = Math.min(end, entity.offset() + entity.length());
            if (from < to && !text.substring(from, to).isBlank()) {
                part.add(new Entity(entity.type(), from - start, to - from));
            }
        }
        return part;
    }
}
