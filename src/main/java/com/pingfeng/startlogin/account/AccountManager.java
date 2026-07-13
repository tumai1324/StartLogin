package com.pingfeng.startlogin.account;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.config.ConfigManager;
import com.pingfeng.startlogin.config.MessageManager;
import com.pingfeng.startlogin.database.DatabaseManager;
import com.pingfeng.startlogin.database.SQLTaskQueue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AccountManager {

    private final StartLogin plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final DatabaseManager databaseManager;
    private final SQLTaskQueue sqlTaskQueue;

    private final Map<UUID, LoginSession> loginSessions;
    private final Map<UUID, Integer> failedAttempts;
    private final Map<String, UUID> onlinePlayers;
    private final Map<UUID, Long> sessionCache; // 会话缓存：UUID -> 上次登录时间戳(毫秒)
    private final Map<UUID, Long> joinTimeMap; // 玩家加入时间，用于踢出超时检测

    private static class LoginSession {
        boolean loggedIn;
        boolean agreedRule;
        boolean isPremium;

        LoginSession(boolean loggedIn, boolean agreedRule, boolean isPremium) {
            this.loggedIn = loggedIn;
            this.agreedRule = agreedRule;
            this.isPremium = isPremium;
        }
    }

    public AccountManager(StartLogin plugin, ConfigManager configManager,
                          MessageManager messageManager, DatabaseManager databaseManager,
                          SQLTaskQueue sqlTaskQueue) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.databaseManager = databaseManager;
        this.sqlTaskQueue = sqlTaskQueue;
        this.loginSessions = new ConcurrentHashMap<>();
        this.failedAttempts = new ConcurrentHashMap<>();
        this.onlinePlayers = new ConcurrentHashMap<>();
        this.sessionCache = new ConcurrentHashMap<>();
        this.joinTimeMap = new ConcurrentHashMap<>();
    }

    public void loadAccount(UUID uuid, String username, AccountLoadCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<AccountData>() {
            @Override
            public AccountData execute(Connection conn) throws SQLException {
                return queryAccount(conn, uuid);
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(AccountData data) {
                if (data != null) {
                    if (data.username == null || !data.username.equalsIgnoreCase(username)) {
                        updateUsername(uuid, username, null);
                    }
                }
                callback.onLoaded(data);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("加载账号数据失败: " + e.getMessage());
                callback.onLoaded(null);
            }
        }));
    }

    private AccountData queryAccount(Connection conn, UUID uuid) throws SQLException {
        String sql = "SELECT uuid, username, password, has_agreed_rule, register_ip, register_time, last_login_ip, last_login_time, password_changed_time, force_change_password, is_premium, premium_uuid, microsoft_refresh_token, microsoft_access_token, microsoft_access_token_expires FROM accounts WHERE uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    AccountData data = new AccountData();
                    data.uuid = UUID.fromString(rs.getString("uuid"));
                    data.username = rs.getString("username");
                    data.password = rs.getString("password");
                    data.hasAgreedRule = rs.getBoolean("has_agreed_rule");
                    data.registerIp = rs.getString("register_ip");
                    data.registerTime = rs.getLong("register_time");
                    data.lastLoginIp = rs.getString("last_login_ip");
                    data.lastLoginTime = rs.getLong("last_login_time");
                    data.passwordChangedTime = rs.getLong("password_changed_time");
                    data.forceChangePassword = rs.getBoolean("force_change_password");
                    data.isPremium = rs.getBoolean("is_premium");
                    data.premiumUuid = rs.getString("premium_uuid");
                    data.microsoftRefreshToken = rs.getString("microsoft_refresh_token");
                    data.microsoftAccessToken = rs.getString("microsoft_access_token");
                    data.microsoftAccessTokenExpires = rs.getLong("microsoft_access_token_expires");
                    return data;
                }
            }
        }
        return null;
    }

    public void registerAccount(UUID uuid, String username, String password, String ip, RegisterCallback callback) {
        String hashedPassword = hashPassword(password);
        long currentTime = System.currentTimeMillis();
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData existing = queryAccount(conn, uuid);
                if (existing != null) {
                    return false;
                }
                String sql = "INSERT INTO accounts (uuid, username, password, has_agreed_rule, register_ip, register_time, password_changed_time, force_change_password) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, username);
                    stmt.setString(3, hashedPassword);
                    stmt.setBoolean(4, false);
                    stmt.setString(5, ip);
                    stmt.setLong(6, currentTime);
                    stmt.setLong(7, currentTime);
                    stmt.setBoolean(8, false);
                    stmt.executeUpdate();
                }
                return true;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean success) {
                callback.onResult(success);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("注册账号失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public void verifyPassword(UUID uuid, String password, VerifyCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData data = queryAccount(conn, uuid);
                if (data == null || data.password == null) {
                    return false;
                }
                String hashed = hashPassword(password);
                return hashed.equals(data.password);
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean success) {
                callback.onResult(success);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("密码验证失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public void checkHasAgreedRule(UUID uuid, RuleCheckCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData data = queryAccount(conn, uuid);
                return data != null && data.hasAgreedRule;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean result) {
                callback.onResult(result);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("查询规则同意状态失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public void setAgreedRule(UUID uuid, Runnable onComplete) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Void>() {
            @Override
            public Void execute(Connection conn) throws SQLException {
                String sql = "UPDATE accounts SET has_agreed_rule = ? WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setBoolean(1, true);
                    stmt.setString(2, uuid.toString());
                    stmt.executeUpdate();
                }
                return null;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Void result) {
                LoginSession session = loginSessions.get(uuid);
                if (session != null) {
                    session.agreedRule = true;
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("保存规则同意状态失败: " + e.getMessage());
            }
        }));
    }

    public void changePassword(UUID uuid, String oldPassword, String newPassword, ChangePasswordCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData data = queryAccount(conn, uuid);
                if (data == null || data.password == null) {
                    return false;
                }
                String oldHashed = hashPassword(oldPassword);
                if (!oldHashed.equals(data.password)) {
                    return false;
                }
                String newHashed = hashPassword(newPassword);
                String sql = "UPDATE accounts SET password = ?, password_changed_time = ?, force_change_password = false WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, newHashed);
                    stmt.setLong(2, System.currentTimeMillis());
                    stmt.setString(3, uuid.toString());
                    stmt.executeUpdate();
                }
                return true;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean success) {
                callback.onResult(success);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("修改密码失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public void resetPassword(UUID uuid, String newPassword, ResetCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData data = queryAccount(conn, uuid);
                if (data == null) {
                    return false;
                }
                String newHashed = hashPassword(newPassword);
                String sql = "UPDATE accounts SET password = ?, password_changed_time = ?, force_change_password = false WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, newHashed);
                    stmt.setLong(2, System.currentTimeMillis());
                    stmt.setString(3, uuid.toString());
                    stmt.executeUpdate();
                }
                return true;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean success) {
                callback.onResult(success);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("重置密码失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public void unregisterAccount(UUID uuid, UnregisterCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                String sql = "DELETE FROM accounts WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    int affected = stmt.executeUpdate();
                    return affected > 0;
                }
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean success) {
                loginSessions.remove(uuid);
                failedAttempts.remove(uuid);
                callback.onResult(success);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("注销账号失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    private void updateUsername(UUID uuid, String username, Runnable onComplete) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Void>() {
            @Override
            public Void execute(Connection conn) throws SQLException {
                String sql = "UPDATE accounts SET username = ? WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, username);
                    stmt.setString(2, uuid.toString());
                    stmt.executeUpdate();
                }
                return null;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Void result) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }));
    }

    public void checkNameConflict(String username, NameCheckCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                String sql = "SELECT COUNT(*) FROM accounts WHERE LOWER(username) = LOWER(?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, username);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt(1) > 0;
                        }
                    }
                }
                return false;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean result) {
                callback.onResult(result);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("检查昵称冲突失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public boolean isLoggedIn(UUID uuid) {
        LoginSession session = loginSessions.get(uuid);
        return session != null && session.loggedIn;
    }

    public void setLoggedIn(UUID uuid, boolean loggedIn, boolean isPremium) {
        LoginSession session = loginSessions.computeIfAbsent(uuid,
                k -> new LoginSession(false, false, isPremium));
        session.loggedIn = loggedIn;
        session.isPremium = isPremium;
    }

    public boolean hasAgreedRule(UUID uuid) {
        LoginSession session = loginSessions.get(uuid);
        return session != null && session.agreedRule;
    }

    public void setAgreedRuleLocal(UUID uuid, boolean agreed) {
        LoginSession session = loginSessions.get(uuid);
        if (session != null) {
            session.agreedRule = agreed;
        }
    }

    public boolean isAccountLocked(UUID uuid) {
        Integer attempts = failedAttempts.get(uuid);
        return attempts != null && attempts >= configManager.getMaxLoginAttempts();
    }

    public int getRemainingAttempts(UUID uuid) {
        Integer attempts = failedAttempts.get(uuid);
        int max = configManager.getMaxLoginAttempts();
        if (attempts == null) {
            return max;
        }
        return Math.max(0, max - attempts);
    }

    public void incrementFailedAttempts(UUID uuid) {
        failedAttempts.merge(uuid, 1, Integer::sum);
    }

    public void resetFailedAttempts(UUID uuid) {
        failedAttempts.remove(uuid);
    }

    public UUID getOnlineSession(String username) {
        return onlinePlayers.get(username.toLowerCase());
    }

    public void addOnlineSession(UUID uuid, String username) {
        onlinePlayers.put(username.toLowerCase(), uuid);
    }

    public void removeOnlineSession(UUID uuid, String username) {
        onlinePlayers.remove(username.toLowerCase(), uuid);
        loginSessions.remove(uuid);
        failedAttempts.remove(uuid);
        // 注意：不清理 sessionCache，会话缓存的目的是让玩家退出后在有效期内重新进入无需登录
        // 过期缓存会在 isSessionCached() 检查时自动清理
    }

    public boolean isPremiumAccount(UUID uuid) {
        LoginSession session = loginSessions.get(uuid);
        return session != null && session.isPremium;
    }

    public void registerPremiumAccount(UUID uuid, String username, String premiumUuid, String refreshToken, String accessToken, long accessTokenExpires, String ip, RegisterCallback callback) {
        long currentTime = System.currentTimeMillis();
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData existing = queryAccount(conn, uuid);
                if (existing != null) {
                    return false;
                }
                String sql = "INSERT INTO accounts (uuid, username, password, has_agreed_rule, register_ip, register_time, password_changed_time, force_change_password, is_premium, premium_uuid, microsoft_refresh_token, microsoft_access_token, microsoft_access_token_expires) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, username);
                    stmt.setString(3, null);
                    stmt.setBoolean(4, false);
                    stmt.setString(5, ip);
                    stmt.setLong(6, currentTime);
                    stmt.setLong(7, currentTime);
                    stmt.setBoolean(8, false);
                    stmt.setBoolean(9, true);
                    stmt.setString(10, premiumUuid);
                    stmt.setString(11, refreshToken);
                    stmt.setString(12, accessToken);
                    stmt.setLong(13, accessTokenExpires);
                    stmt.executeUpdate();
                }
                return true;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean success) {
                callback.onResult(success);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("注册正版账号失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public void setPremium(UUID uuid, String premiumUuid, String refreshToken, String accessToken, long accessTokenExpires, PremiumSetCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData existing = queryAccount(conn, uuid);
                if (existing == null) {
                    return false;
                }
                String sql = "UPDATE accounts SET is_premium = ?, premium_uuid = ?, microsoft_refresh_token = ?, microsoft_access_token = ?, microsoft_access_token_expires = ? WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setBoolean(1, true);
                    stmt.setString(2, premiumUuid);
                    stmt.setString(3, refreshToken);
                    stmt.setString(4, accessToken);
                    stmt.setLong(5, accessTokenExpires);
                    stmt.setString(6, uuid.toString());
                    stmt.executeUpdate();
                }
                return true;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean success) {
                if (success) {
                    LoginSession session = loginSessions.get(uuid);
                    if (session != null) {
                        session.isPremium = true;
                    }
                }
                callback.onResult(success);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("设置正版账号失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public void unsetPremium(UUID uuid, PremiumSetCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData existing = queryAccount(conn, uuid);
                if (existing == null) {
                    return false;
                }
                String sql = "UPDATE accounts SET is_premium = ?, premium_uuid = ?, microsoft_refresh_token = ?, microsoft_access_token = ?, microsoft_access_token_expires = ? WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setBoolean(1, false);
                    stmt.setString(2, null);
                    stmt.setString(3, null);
                    stmt.setString(4, null);
                    stmt.setLong(5, 0);
                    stmt.setString(6, uuid.toString());
                    stmt.executeUpdate();
                }
                return true;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean success) {
                if (success) {
                    LoginSession session = loginSessions.get(uuid);
                    if (session != null) {
                        session.isPremium = false;
                    }
                }
                callback.onResult(success);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("取消正版账号失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    public void checkPremiumAccount(String premiumUuid, PremiumCheckCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<AccountData>() {
            @Override
            public AccountData execute(Connection conn) throws SQLException {
                String sql = "SELECT uuid, username, password, has_agreed_rule, register_ip, register_time, last_login_ip, last_login_time, password_changed_time, force_change_password, is_premium, premium_uuid, microsoft_refresh_token, microsoft_access_token, microsoft_access_token_expires FROM accounts WHERE premium_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, premiumUuid);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            AccountData data = new AccountData();
                            data.uuid = UUID.fromString(rs.getString("uuid"));
                            data.username = rs.getString("username");
                            data.password = rs.getString("password");
                            data.hasAgreedRule = rs.getBoolean("has_agreed_rule");
                            data.registerIp = rs.getString("register_ip");
                            data.registerTime = rs.getLong("register_time");
                            data.lastLoginIp = rs.getString("last_login_ip");
                            data.lastLoginTime = rs.getLong("last_login_time");
                            data.passwordChangedTime = rs.getLong("password_changed_time");
                            data.forceChangePassword = rs.getBoolean("force_change_password");
                            data.isPremium = rs.getBoolean("is_premium");
                            data.premiumUuid = rs.getString("premium_uuid");
                            data.microsoftRefreshToken = rs.getString("microsoft_refresh_token");
                            data.microsoftAccessToken = rs.getString("microsoft_access_token");
                            data.microsoftAccessTokenExpires = rs.getLong("microsoft_access_token_expires");
                            return data;
                        }
                    }
                }
                return null;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(AccountData data) {
                callback.onResult(data != null, data);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("检查正版账号失败: " + e.getMessage());
                callback.onResult(false, null);
            }
        }));
    }

    public void updateMicrosoftTokens(UUID uuid, String refreshToken, String accessToken, long accessTokenExpires, Runnable onComplete) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Void>() {
            @Override
            public Void execute(Connection conn) throws SQLException {
                String sql = "UPDATE accounts SET microsoft_refresh_token = ?, microsoft_access_token = ?, microsoft_access_token_expires = ? WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, refreshToken);
                    stmt.setString(2, accessToken);
                    stmt.setLong(3, accessTokenExpires);
                    stmt.setString(4, uuid.toString());
                    stmt.executeUpdate();
                }
                return null;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Void result) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("更新微软令牌失败: " + e.getMessage());
            }
        }));
    }

    public void isPremiumAccountDb(UUID uuid, PremiumCheckCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<AccountData>() {
            @Override
            public AccountData execute(Connection conn) throws SQLException {
                return queryAccount(conn, uuid);
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(AccountData data) {
                callback.onResult(data != null && data.isPremium, data);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("检查正版账号状态失败: " + e.getMessage());
                callback.onResult(false, null);
            }
        }));
    }

    // ==================== 会话缓存 ====================

    public void cacheSession(UUID uuid) {
        sessionCache.put(uuid, System.currentTimeMillis());
    }

    public boolean isSessionCached(UUID uuid) {
        int timeoutMinutes = configManager.getSessionCacheTimeout();
        if (timeoutMinutes <= 0) {
            return false;
        }
        Long lastLogin = sessionCache.get(uuid);
        if (lastLogin == null) {
            return false;
        }
        long timeoutMillis = timeoutMinutes * 60L * 1000L;
        if (System.currentTimeMillis() - lastLogin > timeoutMillis) {
            sessionCache.remove(uuid);
            return false;
        }
        return true;
    }

    public void clearSessionCache(UUID uuid) {
        sessionCache.remove(uuid);
    }

    public void cleanInvalidData(CleanCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Integer>() {
            @Override
            public Integer execute(Connection conn) throws SQLException {
                String sql = "DELETE FROM accounts WHERE username IS NULL OR username = ''";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    return stmt.executeUpdate();
                }
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Integer count) {
                callback.onCleaned(count);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("清理无效数据失败: " + e.getMessage());
                callback.onCleaned(0);
            }
        }));
    }

    // ==================== V2.2 新增功能 ====================

    /**
     * 密码强度检测
     * @return null 表示通过，否则返回失败原因消息 key
     */
    public String checkPasswordStrength(String password) {
        if (!configManager.isPasswordStrengthCheckEnabled()) {
            return null;
        }
        if (configManager.passwordRequiresUppercase() &&
                password.chars().noneMatch(c -> c >= 'A' && c <= 'Z')) {
            return configManager.passwordRequiresSpecial() ? "security.password-weak-special" : "security.password-weak";
        }
        if (configManager.passwordRequiresLowercase() &&
                password.chars().noneMatch(c -> c >= 'a' && c <= 'z')) {
            return configManager.passwordRequiresSpecial() ? "security.password-weak-special" : "security.password-weak";
        }
        if (configManager.passwordRequiresDigit() &&
                password.chars().noneMatch(c -> c >= '0' && c <= '9')) {
            return configManager.passwordRequiresSpecial() ? "security.password-weak-special" : "security.password-weak";
        }
        if (configManager.passwordRequiresSpecial() &&
                password.chars().allMatch(c -> Character.isLetterOrDigit(c))) {
            return "security.password-weak-special";
        }
        return null;
    }

    /**
     * IP 注册数量检查
     */
    public void checkIpRegistrationLimit(String ip, IpLimitCallback callback) {
        int limit = configManager.getIpRegistrationLimit();
        if (limit <= 0) {
            callback.onResult(true, 0, limit);
            return;
        }
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<int[]>() {
            @Override
            public int[] execute(Connection conn) throws SQLException {
                String sql = "SELECT COUNT(*) FROM accounts WHERE register_ip = ?";
                int count = 0;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, ip);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            count = rs.getInt(1);
                        }
                    }
                }
                return new int[]{count, limit};
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(int[] result) {
                callback.onResult(result[0] < result[1], result[0], result[1]);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("IP注册限制检查失败: " + e.getMessage());
                callback.onResult(true, 0, limit);
            }
        }));
    }

    /**
     * 记录登录尝试
     */
    public void recordLoginAttempt(UUID uuid, String username, String ip, boolean success) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Void>() {
            @Override
            public Void execute(Connection conn) throws SQLException {
                String sql = "INSERT INTO login_records (uuid, username, ip, login_time, success) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, username);
                    stmt.setString(3, ip);
                    stmt.setLong(4, System.currentTimeMillis());
                    stmt.setBoolean(5, success);
                    stmt.executeUpdate();
                }
                return null;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Void result) {
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("记录登录尝试失败: " + e.getMessage());
            }
        }));
    }

    /**
     * 更新最后登录IP和时间
     */
    public void updateLoginInfo(UUID uuid, String ip, Runnable onComplete) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Void>() {
            @Override
            public Void execute(Connection conn) throws SQLException {
                String sql = "UPDATE accounts SET last_login_ip = ?, last_login_time = ? WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, ip);
                    stmt.setLong(2, System.currentTimeMillis());
                    stmt.setString(3, uuid.toString());
                    stmt.executeUpdate();
                }
                return null;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Void result) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("更新登录信息失败: " + e.getMessage());
            }
        }));
    }

    /**
     * 检查是否为异地登录（与上次登录IP不同）
     */
    public void checkRemoteLogin(UUID uuid, String currentIp, RemoteLoginCallback callback) {
        if (!configManager.isRemoteLoginAlertEnabled()) {
            callback.onResult(false);
            return;
        }
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData data = queryAccount(conn, uuid);
                if (data == null || data.lastLoginIp == null || data.lastLoginIp.isEmpty()) {
                    return false;
                }
                return !data.lastLoginIp.equals(currentIp);
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean result) {
                callback.onResult(result);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("检查异地登录失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    /**
     * 设置强制改密码标志
     */
    public void forceChangePassword(UUID uuid, Runnable onComplete) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Void>() {
            @Override
            public Void execute(Connection conn) throws SQLException {
                String sql = "UPDATE accounts SET force_change_password = true WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.executeUpdate();
                }
                return null;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Void result) {
                if (onComplete != null) {
                    onComplete.run();
                }
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("设置强制改密码失败: " + e.getMessage());
            }
        }));
    }

    /**
     * 检查是否需要强制改密码
     */
    public void checkForceChangePassword(UUID uuid, ForceChangeCheckCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData data = queryAccount(conn, uuid);
                return data != null && data.forceChangePassword;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean result) {
                callback.onResult(result);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("检查强制改密码状态失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    /**
     * 检查密码是否过期
     */
    public void checkPasswordExpired(UUID uuid, PasswordExpiryCallback callback) {
        int expiryDays = configManager.getPasswordExpiryDays();
        if (expiryDays <= 0) {
            callback.onResult(false);
            return;
        }
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData data = queryAccount(conn, uuid);
                if (data == null || data.passwordChangedTime <= 0) {
                    return false;
                }
                long expiryMillis = expiryDays * 24L * 60L * 60L * 1000L;
                return System.currentTimeMillis() - data.passwordChangedTime > expiryMillis;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean result) {
                callback.onResult(result);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("检查密码过期失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    /**
     * 检查账号是否太新不能注销
     */
    public void checkAccountTooNew(UUID uuid, AccountAgeCallback callback) {
        int minAgeHours = configManager.getMinAccountAgeHours();
        if (minAgeHours <= 0) {
            callback.onResult(false);
            return;
        }
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<Boolean>() {
            @Override
            public Boolean execute(Connection conn) throws SQLException {
                AccountData data = queryAccount(conn, uuid);
                if (data == null || data.registerTime <= 0) {
                    return false;
                }
                long minAgeMillis = minAgeHours * 60L * 60L * 1000L;
                return System.currentTimeMillis() - data.registerTime < minAgeMillis;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(Boolean result) {
                callback.onResult(result);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("检查账号年龄失败: " + e.getMessage());
                callback.onResult(false);
            }
        }));
    }

    /**
     * 获取账号信息（管理员查询）
     */
    public void getAccountInfo(String username, AccountInfoCallback callback) {
        sqlTaskQueue.submit(new SQLTaskQueue.SQLTask<AccountData>() {
            @Override
            public AccountData execute(Connection conn) throws SQLException {
                String sql = "SELECT uuid, username, password, has_agreed_rule, register_ip, register_time, last_login_ip, last_login_time, password_changed_time, force_change_password, is_premium, premium_uuid FROM accounts WHERE LOWER(username) = LOWER(?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, username);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            AccountData data = new AccountData();
                            data.uuid = UUID.fromString(rs.getString("uuid"));
                            data.username = rs.getString("username");
                            data.password = rs.getString("password");
                            data.hasAgreedRule = rs.getBoolean("has_agreed_rule");
                            data.registerIp = rs.getString("register_ip");
                            data.registerTime = rs.getLong("register_time");
                            data.lastLoginIp = rs.getString("last_login_ip");
                            data.lastLoginTime = rs.getLong("last_login_time");
                            data.passwordChangedTime = rs.getLong("password_changed_time");
                            data.forceChangePassword = rs.getBoolean("force_change_password");
                            data.isPremium = rs.getBoolean("is_premium");
                            data.premiumUuid = rs.getString("premium_uuid");
                            return data;
                        }
                    }
                }
                return null;
            }
        }.callback(new SQLTaskQueue.SQLCallback<>() {
            @Override
            public void onSuccess(AccountData data) {
                callback.onResult(data);
            }

            @Override
            public void onFailure(Exception e) {
                plugin.getLogger().severe("获取账号信息失败: " + e.getMessage());
                callback.onResult(null);
            }
        }));
    }

    /**
     * 格式化时间戳为可读字符串
     */
    public String formatTime(long timestamp) {
        if (timestamp <= 0) {
            return "无记录";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(timestamp));
    }

    // ==================== 加入时间管理（用于踢出超时） ====================

    public void setJoinTime(UUID uuid) {
        joinTimeMap.put(uuid, System.currentTimeMillis());
    }

    public long getJoinTime(UUID uuid) {
        Long time = joinTimeMap.get(uuid);
        return time != null ? time : 0;
    }

    public void removeJoinTime(UUID uuid) {
        joinTimeMap.remove(uuid);
    }

    /**
     * 检查玩家是否登录超时（需要踢出）
     */
    public boolean isLoginTimedOut(UUID uuid) {
        int kickTimeout = configManager.getKickTimeout();
        if (kickTimeout <= 0) {
            return false;
        }
        if (isLoggedIn(uuid)) {
            return false;
        }
        long joinTime = getJoinTime(uuid);
        if (joinTime <= 0) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - joinTime;
        return elapsed > kickTimeout * 1000L;
    }

    // ==================== 在线统计 ====================

    /**
     * 获取在线玩家统计
     * @return 数组 [总数, 已登录数, 未登录数]
     */
    public int[] getOnlineStats() {
        int total = onlinePlayers.size();
        int loggedIn = 0;
        for (UUID uuid : onlinePlayers.values()) {
            if (isLoggedIn(uuid)) {
                loggedIn++;
            }
        }
        return new int[]{total, loggedIn, total - loggedIn};
    }

    /**
     * 获取所有在线玩家的UUID集合
     */
    public java.util.Collection<UUID> getOnlinePlayerUuids() {
        return onlinePlayers.values();
    }

    // ==================== 首次登录保护 ====================

    private final Map<UUID, Long> firstLoginProtection = new ConcurrentHashMap<>();

    /**
     * 启用首次登录保护（无敌效果）
     */
    public void enableFirstLoginProtection(UUID uuid) {
        int duration = configManager.getFirstLoginProtectionDuration();
        if (duration <= 0) {
            return;
        }
        firstLoginProtection.put(uuid, System.currentTimeMillis() + duration * 1000L);
    }

    /**
     * 检查玩家是否处于首次登录保护状态
     */
    public boolean isFirstLoginProtected(UUID uuid) {
        Long expiry = firstLoginProtection.get(uuid);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry) {
            firstLoginProtection.remove(uuid);
            return false;
        }
        return true;
    }

    public void removeFirstLoginProtection(UUID uuid) {
        firstLoginProtection.remove(uuid);
    }

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("密码哈希算法不可用: " + e.getMessage());
            return password;
        }
    }

    public static class AccountData {
        public UUID uuid;
        public String username;
        public String password;
        public boolean hasAgreedRule;
        public String registerIp;
        public long registerTime;
        public String lastLoginIp;
        public long lastLoginTime;
        public long passwordChangedTime;
        public boolean forceChangePassword;
        public boolean isPremium;
        public String premiumUuid;
        public String microsoftRefreshToken;
        public String microsoftAccessToken;
        public long microsoftAccessTokenExpires;
    }

    public interface AccountLoadCallback {
        void onLoaded(AccountData data);
    }

    public interface RegisterCallback {
        void onResult(boolean success);
    }

    public interface VerifyCallback {
        void onResult(boolean success);
    }

    public interface RuleCheckCallback {
        void onResult(boolean agreed);
    }

    public interface ChangePasswordCallback {
        void onResult(boolean success);
    }

    public interface ResetCallback {
        void onResult(boolean success);
    }

    public interface UnregisterCallback {
        void onResult(boolean success);
    }

    public interface NameCheckCallback {
        void onResult(boolean conflict);
    }

    public interface CleanCallback {
        void onCleaned(int count);
    }

    public interface IpLimitCallback {
        void onResult(boolean allowed, int current, int limit);
    }

    public interface RemoteLoginCallback {
        void onResult(boolean isRemote);
    }

    public interface ForceChangeCheckCallback {
        void onResult(boolean needChange);
    }

    public interface PasswordExpiryCallback {
        void onResult(boolean expired);
    }

    public interface AccountAgeCallback {
        void onResult(boolean tooNew);
    }

    public interface AccountInfoCallback {
        void onResult(AccountData data);
    }

    public interface PremiumSetCallback {
        void onResult(boolean success);
    }

    public interface PremiumCheckCallback {
        void onResult(boolean exists, AccountData data);
    }
}
