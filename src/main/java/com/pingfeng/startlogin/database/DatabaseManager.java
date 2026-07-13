package com.pingfeng.startlogin.database;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.config.ConfigManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DatabaseManager {

    private final StartLogin plugin;
    private final ConfigManager configManager;
    private final BlockingQueue<Connection> connectionPool;
    private final AtomicInteger activeConnections;
    private String dbUrl;
    private File dbFile;
    private volatile boolean initialized;
    private ScheduledFuture<?> idleReclaimerFuture;
    private ScheduledFuture<?> autoBackupFuture;

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS accounts (
                uuid TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                password TEXT,
                has_agreed_rule BOOLEAN NOT NULL DEFAULT false,
                register_ip TEXT,
                register_time INTEGER,
                last_login_ip TEXT,
                last_login_time INTEGER,
                password_changed_time INTEGER,
                force_change_password BOOLEAN NOT NULL DEFAULT false,
                is_premium BOOLEAN NOT NULL DEFAULT false,
                premium_uuid TEXT,
                microsoft_refresh_token TEXT,
                microsoft_access_token TEXT,
                microsoft_access_token_expires INTEGER
            )
            """;
    private static final String CREATE_LOGIN_RECORDS_SQL = """
            CREATE TABLE IF NOT EXISTS login_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                username TEXT,
                ip TEXT,
                login_time INTEGER,
                success BOOLEAN
            )
            """;

    public DatabaseManager(StartLogin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.connectionPool = new LinkedBlockingQueue<>(5);
        this.activeConnections = new AtomicInteger(0);
        this.initialized = false;
    }

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("SQLite JDBC驱动加载失败: " + e.getMessage());
            return;
        }

        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        String filename = configManager.getDatabaseFilename();
        dbFile = new File(dataFolder, filename);
        dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        try {
            try (Connection conn = DriverManager.getConnection(dbUrl);
                 Statement stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
                stmt.execute(CREATE_LOGIN_RECORDS_SQL);
                // 迁移：为旧数据库添加新列
                migrateDatabase(conn);
            }
            initialized = true;
            plugin.getLogger().info("数据库初始化完成: " + dbFile.getName());

            startIdleConnectionReclaimer();
            startAutoBackup();
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库初始化失败: " + e.getMessage());
        }
    }

    /**
     * 数据库迁移：为旧版数据库添加新列
     */
    private void migrateDatabase(Connection conn) throws SQLException {
        String[] newColumns = {
                "register_ip TEXT", "register_time INTEGER",
                "last_login_ip TEXT", "last_login_time INTEGER",
                "password_changed_time INTEGER", "force_change_password BOOLEAN NOT NULL DEFAULT false",
                "is_premium BOOLEAN NOT NULL DEFAULT false",
                "premium_uuid TEXT",
                "microsoft_refresh_token TEXT",
                "microsoft_access_token TEXT",
                "microsoft_access_token_expires INTEGER"
        };
        for (String colDef : newColumns) {
            String colName = colDef.split(" ")[0];
            if (!columnExists(conn, "accounts", colName)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE accounts ADD COLUMN " + colDef);
                    plugin.getLogger().info("数据库迁移: 已添加列 " + colName);
                }
            }
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("PRAGMA table_info(" + table + ")")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(rs.getString("name"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 启动自动备份定时任务
     */
    private void startAutoBackup() {
        int intervalHours = configManager.getAutoBackupInterval();
        if (intervalHours <= 0) {
            return;
        }
        long intervalSeconds = intervalHours * 3600L;
        autoBackupFuture = plugin.getThreadPoolManager().scheduleAtFixedRate(() -> {
            try {
                File backup = backupDatabaseToBackupDir();
                if (backup != null) {
                    cleanOldBackups();
                    plugin.getLogger().info("自动备份完成: " + backup.getName());
                }
            } catch (Exception e) {
                plugin.getLogger().severe("自动备份失败: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        plugin.getLogger().info("自动备份已启动，间隔: " + intervalHours + " 小时");
    }

    /**
     * 备份数据库到 backup 目录
     */
    public File backupDatabaseToBackupDir() {
        if (dbFile == null || !dbFile.exists()) {
            return null;
        }
        File backupDir = new File(plugin.getDataFolder(), "backup");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String backupName = "startlogin_backup_" + sdf.format(new Date()) + ".db";
        File backupFile = new File(backupDir, backupName);
        try {
            Files.copy(dbFile.toPath(), backupFile.toPath());
            return backupFile;
        } catch (IOException e) {
            plugin.getLogger().severe("数据库备份失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 清理过多的旧备份
     */
    private void cleanOldBackups() {
        int maxCount = configManager.getAutoBackupMaxCount();
        if (maxCount <= 0) {
            return;
        }
        File backupDir = new File(plugin.getDataFolder(), "backup");
        if (!backupDir.exists()) {
            return;
        }
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("startlogin_backup_") && name.endsWith(".db"));
        if (backups == null || backups.length <= maxCount) {
            return;
        }
        // 按修改时间排序，最旧的在前
        java.util.Arrays.sort(backups, java.util.Comparator.comparingLong(File::lastModified));
        int toDelete = backups.length - maxCount;
        for (int i = 0; i < toDelete; i++) {
            if (backups[i].delete()) {
                plugin.getLogger().info("已清理旧备份: " + backups[i].getName());
            }
        }
    }

    private void startIdleConnectionReclaimer() {
        int timeoutSeconds = configManager.getIdleConnectionTimeout();
        if (timeoutSeconds <= 0) {
            return;
        }

        idleReclaimerFuture = plugin.getThreadPoolManager().scheduleAtFixedRate(() -> {
            int reclaimed = 0;
            while (connectionPool.size() > 1) {
                Connection conn = connectionPool.poll();
                if (conn != null) {
                    try {
                        conn.close();
                        reclaimed++;
                    } catch (SQLException e) {
                        plugin.getLogger().warning("关闭空闲连接失败: " + e.getMessage());
                    }
                }
            }
            if (reclaimed > 0) {
                plugin.getLogger().fine("回收了 " + reclaimed + " 个空闲数据库连接");
            }
        }, timeoutSeconds, timeoutSeconds, TimeUnit.SECONDS);
    }

    public Connection getConnection() throws SQLException {
        if (!initialized) {
            throw new SQLException("数据库未初始化");
        }

        Connection conn = connectionPool.poll();
        if (conn != null && !conn.isClosed()) {
            activeConnections.incrementAndGet();
            return conn;
        }

        conn = DriverManager.getConnection(dbUrl);
        activeConnections.incrementAndGet();
        return conn;
    }

    public void releaseConnection(Connection conn) {
        if (conn == null) {
            return;
        }
        activeConnections.decrementAndGet();
        try {
            if (!conn.isClosed()) {
                connectionPool.offer(conn);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("连接池归还连接失败: " + e.getMessage());
        }
    }

    public void closeAllConnections() {
        if (idleReclaimerFuture != null) {
            idleReclaimerFuture.cancel(false);
        }
        if (autoBackupFuture != null) {
            autoBackupFuture.cancel(false);
        }

        Connection conn;
        int count = 0;
        while ((conn = connectionPool.poll()) != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                    count++;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("关闭连接失败: " + e.getMessage());
            }
        }
        plugin.getLogger().info("已关闭 " + count + " 个数据库连接");
    }

    public File backupDatabase() {
        if (dbFile == null || !dbFile.exists()) {
            return null;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String backupName = "startlogin_backup_" + sdf.format(new Date()) + ".db";
        File backupFile = new File(plugin.getDataFolder(), backupName);

        try {
            Files.copy(dbFile.toPath(), backupFile.toPath());
            plugin.getLogger().info("数据库已备份: " + backupFile.getName());
            return backupFile;
        } catch (IOException e) {
            plugin.getLogger().severe("数据库备份失败: " + e.getMessage());
            return null;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public File getDbFile() {
        return dbFile;
    }
}
