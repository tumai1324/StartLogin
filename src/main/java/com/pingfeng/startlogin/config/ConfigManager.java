package com.pingfeng.startlogin.config;

import com.pingfeng.startlogin.StartLogin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ConfigManager {

    private final StartLogin plugin;
    private FileConfiguration config;
    private File configFile;

    public ConfigManager(StartLogin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        setDefaults();
    }

    private void setDefaults() {
        InputStream defaultStream = plugin.getResource("config.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
            config.options().copyDefaults(true);
            saveConfig();
        }
    }

    public void reloadConfig() {
        if (configFile == null) {
            configFile = new File(plugin.getDataFolder(), "config.yml");
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        setDefaults();
    }

    public void saveConfig() {
        if (config == null || configFile == null) {
            return;
        }
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("保存配置文件失败: " + e.getMessage());
        }
    }

    public FileConfiguration getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }

    public String getDatabaseFilename() {
        return getConfig().getString("database.filename", "startlogin.db");
    }

    public int getIdleConnectionTimeout() {
        return getConfig().getInt("database.idle-connection-timeout", 300);
    }

    public int getThreadPoolCoreSize() {
        return getConfig().getInt("thread-pool.core-size", 4);
    }

    public int getThreadPoolMaxSize() {
        return getConfig().getInt("thread-pool.max-size", 8);
    }

    public int getThreadPoolQueueCapacity() {
        return getConfig().getInt("thread-pool.queue-capacity", 100);
    }

    public long getThreadPoolKeepAlive() {
        return getConfig().getLong("thread-pool.keep-alive", 60);
    }

    public boolean isPremiumEnabled() {
        return getConfig().getBoolean("premium.enabled", true);
    }

    public String getPremiumClientId() {
        return getConfig().getString("premium.client-id", "");
    }

    public String getPremiumRedirectUri() {
        return getConfig().getString("premium.redirect-uri", "");
    }

    public int getPremiumPollInterval() {
        return getConfig().getInt("premium.poll-interval", 5);
    }

    public boolean isPremiumAutoLogin() {
        return getConfig().getBoolean("premium.auto-login", true);
    }

    public int getPremiumTimeout() {
        return getConfig().getInt("premium.timeout", 3000);
    }

    public int getPremiumMaxRetries() {
        return getConfig().getInt("premium.max-retries", 3);
    }

    public int getPremiumCacheDuration() {
        return getConfig().getInt("premium.cache-duration", 5);
    }

    public int getMaxLoginAttempts() {
        return getConfig().getInt("login.max-attempts", 5);
    }

    public int getPasswordMinLength() {
        return getConfig().getInt("login.password-min-length", 4);
    }

    public int getPasswordMaxLength() {
        return getConfig().getInt("login.password-max-length", 32);
    }

    public int getLockDuration() {
        return getConfig().getInt("login.lock-duration", 30);
    }

    public int getSessionCacheTimeout() {
        return getConfig().getInt("login.session-cache-timeout", 30);
    }

    public boolean isSingleSession() {
        return getConfig().getBoolean("login.single-session", true);
    }

    public int getUiCooldown() {
        return getConfig().getInt("ui.cooldown", 3);
    }

    public boolean isVerboseLogging() {
        return getConfig().getBoolean("debug.verbose-logging", false);
    }

    public String getLoginTitle() {
        return getConfig().getString("ui.login-title", "登录系统");
    }

    public String getRegisterTitle() {
        return getConfig().getString("ui.register-title", "注册账号");
    }

    public String getRuleTitle() {
        return getConfig().getString("ui.rule-title", "服务器规则");
    }

    public boolean isCheckPremiumName() {
        return getConfig().getBoolean("nickname.check-premium-name", true);
    }

    public List<String> getPasswordBlacklist() {
        return getConfig().getStringList("login.password-blacklist");
    }

    public boolean isPasswordBlacklisted(String password) {
        List<String> blacklist = getPasswordBlacklist();
        String lowerPassword = password.toLowerCase();
        for (String blacklisted : blacklist) {
            if (lowerPassword.equals(blacklisted.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // ==================== V2.2 新增配置 ====================

    // 密码强度检测
    public boolean isPasswordStrengthCheckEnabled() {
        return getConfig().getBoolean("security.password-strength-check", false);
    }

    public boolean passwordRequiresUppercase() {
        return getConfig().getBoolean("security.require-uppercase", true);
    }

    public boolean passwordRequiresLowercase() {
        return getConfig().getBoolean("security.require-lowercase", true);
    }

    public boolean passwordRequiresDigit() {
        return getConfig().getBoolean("security.require-digit", true);
    }

    public boolean passwordRequiresSpecial() {
        return getConfig().getBoolean("security.require-special", false);
    }

    // IP 注册限制
    public int getIpRegistrationLimit() {
        return getConfig().getInt("nickname.ip-registration-limit", 3);
    }

    // 异地登录提醒
    public boolean isRemoteLoginAlertEnabled() {
        return getConfig().getBoolean("security.remote-login-alert", true);
    }

    // 未登录踢出
    public int getKickTimeout() {
        return getConfig().getInt("security.kick-timeout", 300);
    }

    // 首次登录保护
    public int getFirstLoginProtectionDuration() {
        return getConfig().getInt("security.first-login-protection", 30);
    }

    // 密码过期
    public int getPasswordExpiryDays() {
        return getConfig().getInt("security.password-expiry-days", 0);
    }

    // 最小账号时长
    public int getMinAccountAgeHours() {
        return getConfig().getInt("security.min-account-age-hours", 0);
    }

    // 自动备份
    public int getAutoBackupInterval() {
        return getConfig().getInt("database.auto-backup-interval", 24);
    }

    public int getAutoBackupMaxCount() {
        return getConfig().getInt("database.auto-backup-max-count", 7);
    }

    // 登录欢迎语
    public String getWelcomeMessage() {
        return getConfig().getString("ui.welcome-message", "");
    }
}
