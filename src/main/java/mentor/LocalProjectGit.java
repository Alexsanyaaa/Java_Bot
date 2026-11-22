package mentor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class LocalProjectGit {
    private LocalProjectGit() {}

    static String snapshot(Path repo, String previous) throws IOException, InterruptedException {
        if (!Files.isDirectory(repo.resolve(".git"))) {
            throw new IOException("Нужна локальная рабочая копия Git");
        }
        String head = git(repo, "rev-parse", "HEAD").strip();
        if (!head.matches("[a-f0-9]{40,64}")) {
            throw new IOException("Git не вернул идентификатор коммита");
        }
        String commits = previous.matches("[a-f0-9]{40,64}")
                ? git(repo, "log", "-8", "--oneline", "--name-status", previous + ".." + head)
                : git(repo, "log", "-4", "--oneline", "--name-status", head);
        return head + "\nКоммиты и файлы:\n" + (commits.isBlank() ? "Новых коммитов нет.\n" : commits)
                + "\nРабочая копия:\n" + git(repo, "status", "--short", "--untracked-files=normal")
                + "\nИзменения отслеживаемых файлов:\n"
                + git(repo, "diff", "--stat", "--no-ext-diff", "--no-textconv", "HEAD");
    }

    private static String git(Path repo, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git", "--no-pager", "-C", repo.toString(), "-c", "core.fsmonitor=false",
                "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(args));
        Path output = Files.createTempFile("mentor-git-", ".txt");
        try {
            var builder = new ProcessBuilder(command).redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            var process = builder.start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw new IOException("Git не вернул результат");
                }
                try (var stream = Files.newInputStream(output)) {
                    byte[] data = stream.readNBytes(2500);
                    return new String(data, StandardCharsets.UTF_8) + (Files.size(output) > data.length ? "\n…\n" : "");
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
