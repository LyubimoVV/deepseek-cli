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
    private static final ThreadLocal<Boolean> inTransaction = new ThreadLocal<>();

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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS session_heartbeats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL UNIQUE,
                    last_heartbeat DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_session_heartbeats_session_id ON session_heartbeats(session_id)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS session_mcp_servers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    server_name TEXT NOT NULL,
                    enabled INTEGER DEFAULT 1,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(session_id, server_name),
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_session_mcp_servers_session_id ON session_mcp_servers(session_id)");

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

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE task_messages ADD COLUMN step_index INTEGER");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки step_index: " + e.getMessage());
            }
        }

        try (Statement stmt = DriverManager.getConnection(getJdbcUrl()).createStatement()) {
            stmt.execute("ALTER TABLE task_context ADD COLUMN previous_state TEXT");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column name")) {
                log.warn("Ошибка при добавлении колонки previous_state: " + e.getMessage());
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
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
            connectionHolder.set(conn);
        }
        Boolean inTx = inTransaction.get();
        if (inTx != null && inTx) {
            return new NonClosingConnectionWrapper(conn);
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
            inTransaction.remove();
        }
    }

    public static void beginTransaction() throws SQLException {
        Connection conn = connectionHolder.get();
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection(getJdbcUrl());
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
            connectionHolder.set(conn);
        }
        conn.setAutoCommit(false);
        inTransaction.set(true);
    }

    public static void commitTransaction() throws SQLException {
        Connection conn = connectionHolder.get();
        if (conn != null && !conn.isClosed()) {
            conn.commit();
            conn.setAutoCommit(true);
        }
        inTransaction.remove();
    }

    public static void rollbackTransaction() {
        Connection conn = connectionHolder.get();
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                log.error("Ошибка при откате транзакции: " + e.getMessage());
            }
        }
        inTransaction.remove();
    }

    private static class NonClosingConnectionWrapper implements Connection {
        private final Connection delegate;

        NonClosingConnectionWrapper(Connection delegate) {
            this.delegate = delegate;
        }

        public void close() {
            // Не закрываем соединение во время транзакции
        }

        public Connection getDelegate() {
            return delegate;
        }

        // Делегируем все методы Connection
        public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        public java.sql.CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        public void setAutoCommit(boolean autoCommit) throws SQLException { delegate.setAutoCommit(autoCommit); }
        public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        public void commit() throws SQLException { delegate.commit(); }
        public void rollback() throws SQLException { delegate.rollback(); }
        public boolean isClosed() throws SQLException { return delegate.isClosed(); }
        public java.sql.DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        public java.sql.SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency); }
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency); }
        public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        public java.sql.Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        public java.sql.Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        public void rollback(java.sql.Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException { return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException { return delegate.prepareStatement(sql, autoGeneratedKeys); }
        public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException { return delegate.prepareStatement(sql, columnIndexes); }
        public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return delegate.prepareStatement(sql, columnNames); }
        public java.sql.Clob createClob() throws SQLException { return delegate.createClob(); }
        public java.sql.Blob createBlob() throws SQLException { return delegate.createBlob(); }
        public java.sql.NClob createNClob() throws SQLException { return delegate.createNClob(); }
        public java.sql.SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException { delegate.setClientInfo(name, value); }
        public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException { delegate.setClientInfo(properties); }
        public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        public java.util.Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException { return delegate.createArrayOf(typeName, elements); }
        public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException { return delegate.createStruct(typeName, attributes); }
        public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        public String getSchema() throws SQLException { return delegate.getSchema(); }
        public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException { delegate.setNetworkTimeout(executor, milliseconds); }
        public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}
