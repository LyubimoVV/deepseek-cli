package com.example.deepseek.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private static final String DB_PATH_ENV = "APP_DB_PATH";
    private static final String DEFAULT_DB_PATH = "./data/chat.db";

    private static String dbPath;
    private static boolean initialized = false;

    private static final ThreadLocal<Connection> connectionHolder = new ThreadLocal<>();

    static {
        initDatabase();
    }

    public static void initDatabase() {
        if (initialized) {
            return;
        }

        dbPath = System.getenv(DB_PATH_ENV);
        if (dbPath == null || dbPath.isBlank()) {
            dbPath = DEFAULT_DB_PATH;
        }

        try {
            Path dbDir = Path.of(dbPath).getParent();
            if (dbDir != null && !Files.exists(dbDir)) {
                Files.createDirectories(dbDir);
            }

            createTables();
            initialized = true;
            log.info("✓ База данных инициализирована: " + dbPath);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось инициализировать базу данных: " + e.getMessage(), e);
        }
    }

    private static void createTables() throws SQLException {
        String url = getJdbcUrl();

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL DEFAULT 'Новая сессия',
                    model TEXT,
                    system_message TEXT,
                    mode INTEGER DEFAULT 2,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    role TEXT NOT NULL CHECK(role IN ('system', 'user', 'assistant')),
                    content TEXT NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS app_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);

            stmt.execute("PRAGMA foreign_keys = ON");
        }
    }

    public static String getJdbcUrl() {
        return "jdbc:sqlite:" + dbPath;
    }

    public static String getDbPath() {
        return dbPath;
    }

    public static Connection getConnection() throws SQLException {
        Connection conn = connectionHolder.get();
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection(getJdbcUrl());
            conn.setAutoCommit(true);
            connectionHolder.set(conn);
        }
        return conn;
    }

    public static void closeConnection() {
        Connection conn = connectionHolder.get();
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                log.error("Ошибка при закрытии соединения: " + e.getMessage());
            }
            connectionHolder.remove();
        }
    }
}
