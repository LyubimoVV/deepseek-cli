package com.example.deepseek.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    private static final String DB_PATH_ENV = "APP_DB_PATH";
    private static final String DB_IN_MEMORY_ENV = "APP_DB_IN_MEMORY";
    private static final String DEFAULT_DB_PATH = "./data/chat.db";

    private static String dbPath;
    private static boolean isInMemory = false;
    private static boolean initialized = false;

    private static final ThreadLocal<Connection> connectionHolder = new ThreadLocal<>();

    static {
        initDatabase();
    }

    public static void initDatabase() {
        if (initialized) {
            return;
        }

        String inMemoryEnv = System.getenv(DB_IN_MEMORY_ENV);
        isInMemory = inMemoryEnv != null && inMemoryEnv.equalsIgnoreCase("true");

        if (isInMemory) {
            dbPath = ":memory:?cache=shared";
            log.info("✓ Используется in-memory база данных");
        } else {
            dbPath = System.getenv(DB_PATH_ENV);
            if (dbPath == null || dbPath.isBlank()) {
                dbPath = DEFAULT_DB_PATH;
            }
        }

        try {
            if (!isInMemory) {
                Path dbDir = Path.of(dbPath).getParent();
                if (dbDir != null && !Files.exists(dbDir)) {
                    Files.createDirectories(dbDir);
                }
            }

            createTables();
            initialized = true;
            if (!isInMemory) {
                log.info("✓ База данных инициализирована: " + dbPath);
            }
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
                    total_tokens INTEGER DEFAULT 0,
                    total_cost REAL DEFAULT 0.0,
                    request_count INTEGER DEFAULT 0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    context_strategy TEXT DEFAULT 'NONE',
                    compression_keep_messages INTEGER DEFAULT 3,
                    compression_summary_interval INTEGER DEFAULT 10,
                    sticky_facts_window_size INTEGER DEFAULT 10,
                    sliding_window_size INTEGER DEFAULT 10
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    role TEXT NOT NULL CHECK(role IN ('system', 'user', 'assistant')),
                    content TEXT NOT NULL,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    total_tokens INTEGER DEFAULT 0,
                    cached_tokens INTEGER DEFAULT 0,
                    latency INTEGER DEFAULT 0,
                    cost REAL DEFAULT 0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_session_id ON messages(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS global_summaries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    version INTEGER DEFAULT 1,
                    last_message_id INTEGER,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    total_tokens INTEGER DEFAULT 0,
                    cost REAL DEFAULT 0.0,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_global_summaries_session_id ON global_summaries(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS app_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);

            stmt.execute("PRAGMA foreign_keys = ON");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS facts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(session_id, category, key),
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_facts_session_id ON facts(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS conversation_branches (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    parent_message_id INTEGER,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
                    FOREIGN KEY (parent_message_id) REFERENCES messages(id)
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_conversation_branches_session_id ON conversation_branches(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS profiles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    description TEXT,
                    system_prompt TEXT,
                    personalization TEXT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS working_memory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    priority INTEGER DEFAULT 1,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(session_id, key),
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_working_memory_session_id ON working_memory(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS long_term_memory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    profile_id INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    priority INTEGER DEFAULT 1,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(profile_id, category, key),
                    FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_long_term_memory_profile_id ON long_term_memory(profile_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    state TEXT NOT NULL DEFAULT 'PLANNING',
                    expected_action TEXT,
                    paused INTEGER DEFAULT 0,
                    pause_reason TEXT,
                    context TEXT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tasks_session_id ON tasks(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS task_context (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id INTEGER NOT NULL UNIQUE,
                    task TEXT,
                    state TEXT NOT NULL,
                    step INTEGER DEFAULT 1,
                    total INTEGER DEFAULT 0,
                    plan TEXT,
                    done TEXT,
                    current TEXT,
                    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS task_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id INTEGER NOT NULL,
                    task_state TEXT NOT NULL,
                    prompt TEXT,
                    response TEXT,
                    tokens_used INTEGER DEFAULT 0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_task_messages_task_id ON task_messages(task_id)");

            migrateTables();
        }
    }

    private static void migrateTables() throws SQLException {
        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE global_summaries ADD COLUMN input_tokens INTEGER DEFAULT 0");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки input_tokens: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE global_summaries ADD COLUMN output_tokens INTEGER DEFAULT 0");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки output_tokens: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE global_summaries ADD COLUMN total_tokens INTEGER DEFAULT 0");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки total_tokens: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE global_summaries ADD COLUMN cost REAL DEFAULT 0.0");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки cost: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE sessions ADD COLUMN context_strategy TEXT DEFAULT 'NONE'");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки context_strategy: " + e.getMessage());
            }
        }



        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE sessions ADD COLUMN sticky_facts_window_size INTEGER DEFAULT 10");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки sticky_facts_window_size: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE sessions ADD COLUMN compression_keep_messages INTEGER DEFAULT 3");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки compression_keep_messages: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE sessions ADD COLUMN compression_summary_interval INTEGER DEFAULT 10");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки compression_summary_interval: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE messages ADD COLUMN branch_id INTEGER DEFAULT 1");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки branch_id: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_branch_id ON messages(branch_id)");
        } catch (SQLException e) {
            log.warn("Ошибка при создании индекса idx_messages_branch_id: " + e.getMessage());
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE sessions ADD COLUMN profile_id INTEGER DEFAULT 1");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки profile_id: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("INSERT OR IGNORE INTO profiles (id, name, description, system_prompt, personalization) VALUES (1, 'Default', 'Профиль по умолчанию', NULL, NULL)");
        } catch (SQLException e) {
            log.warn("Ошибка при создании дефолтного профиля: " + e.getMessage());
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE profiles RENAME COLUMN settings TO personalization");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name") && !e.getMessage().contains("no such column")) {
                log.warn("Ошибка при переименовании колонки settings → personalization: " + e.getMessage());
            }
        }

        migrateModesToProfiles();
    }

    private static void migrateModesToProfiles() {
        try (var conn = getConnection();
             var stmt = conn.createStatement()) {

            String[] profiles = {
                "INSERT OR IGNORE INTO profiles (name, description, system_prompt) VALUES ('Тестировщик', 'Режим для тестирования кода', 'Ты — эксперт по тестированию кода на Java. Твоя задача — помогать джуниорам...')",
                "INSERT OR IGNORE INTO profiles (name, description, system_prompt) VALUES ('Помощник', 'Общий помощник', 'Ты — полезный ассистент. Твоя задача — помогать пользователю...')",
                "INSERT OR IGNORE INTO profiles (name, description, system_prompt) VALUES ('Default', 'Профиль по умолчанию', 'Ты — полезный ассистент.')"
            };

            for (String sql : profiles) {
                stmt.execute(sql);
            }

            int updated = stmt.executeUpdate("""
                UPDATE sessions
                SET profile_id = CASE
                    WHEN mode = 1 THEN (SELECT id FROM profiles WHERE name = 'Тестировщик' LIMIT 1)
                    WHEN mode = 2 THEN (SELECT id FROM profiles WHERE name = 'Помощник' LIMIT 1)
                    ELSE (SELECT id FROM profiles WHERE name = 'Default' LIMIT 1)
                END
                WHERE (profile_id IS NULL OR profile_id = 1)
                  AND mode IN (1, 2)
                """);

            log.info("Migrated {} sessions to profiles", updated);

        } catch (SQLException e) {
            if (!e.getMessage().contains("no such table") && !e.getMessage().contains("duplicate column")) {
                log.error("Failed to migrate modes to profiles", e);
            }
        }
    }

    public static String getJdbcUrl() {
        if (isInMemory) {
            return "jdbc:sqlite:file::memory:?cache=shared";
        }
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
            // Включаем поддержку foreign keys для каскадного удаления
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
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
