package mentor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One JDBC connection for the single state row. StateStore already serializes every read and write, so a
 * connection pool (Hikari under Spring) bought nothing but memory. Reconnects after a failure.
 */
final class Database implements AutoCloseable {
    private final String url;
    private final String user;
    private final String password;
    private Connection connection;

    Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        if (url.startsWith("jdbc:h2:")) org.h2.Driver.load(); // no ServiceLoader lookup (also simpler for native image)
    }

    synchronized void execute(String sql) {
        run(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    synchronized int update(String sql, Object... args) {
        return run(connection -> {
            try (PreparedStatement statement = prepare(connection, sql, args)) {
                return statement.executeUpdate();
            }
        });
    }

    synchronized int queryInt(String sql) {
        return run(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) throw new IllegalStateException("Пустой ответ базы: " + sql);
                return rows.getInt(1);
            }
        });
    }

    synchronized String queryString(String sql) {
        return run(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) throw new IllegalStateException("Пустой ответ базы: " + sql);
                return rows.getString(1);
            }
        });
    }

    @Override
    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // closing anyway
        }
        connection = null;
    }

    private interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    private <T> T run(Work<T> work) {
        try {
            return work.apply(connection());
        } catch (SQLException failure) {
            close(); // the next call opens a fresh connection
            throw new IllegalStateException("Ошибка базы: " + failure.getMessage(), failure);
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(url, user, password);
        }
        return connection;
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
        return statement;
    }
}
