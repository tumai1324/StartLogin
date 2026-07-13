package com.pingfeng.startlogin;

import com.pingfeng.startlogin.account.AccountManager;
import com.pingfeng.startlogin.auth.PremiumAuthManager;
import com.pingfeng.startlogin.command.CommandHandler;
import com.pingfeng.startlogin.config.ConfigManager;
import com.pingfeng.startlogin.config.MessageManager;
import com.pingfeng.startlogin.database.DatabaseManager;
import com.pingfeng.startlogin.database.SQLTaskQueue;
import com.pingfeng.startlogin.listener.DialogListener;
import com.pingfeng.startlogin.listener.PlayerListener;
import com.pingfeng.startlogin.thread.ThreadPoolManager;
import com.pingfeng.startlogin.ui.FormDialogManager;
import com.pingfeng.startlogin.ui.UILock;
import com.pingfeng.startlogin.util.VersionChecker;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class StartLogin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private ThreadPoolManager threadPoolManager;
    private DatabaseManager databaseManager;
    private SQLTaskQueue sqlTaskQueue;
    private AccountManager accountManager;
    private PremiumAuthManager premiumAuthManager;
    private UILock uiLock;
    private FormDialogManager formDialogManager;
    private PlayerListener playerListener;
    private DialogListener dialogListener;
    private CommandHandler commandHandler;
    private ScheduledFuture<?> kickTimeoutFuture;

    @Override
    public void onEnable() {
        getLogger().info("=====================================");
        getLogger().info("  StartLogin V2.3");
        getLogger().info("  作者：屏风");
        getLogger().info("=====================================");

        if (!VersionChecker.isSupported()) {
            getLogger().severe("不支持的服务端版本: " + VersionChecker.getVersionString());
            getLogger().severe("支持版本: 1.21.7 ~ 26.x");
            getLogger().severe("插件已停止加载");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("服务端版本校验通过: " + VersionChecker.getVersionString());

        initDataFolder();
        initConfig();
        initThreadPool();
        initDatabase();
        initManagers();
        registerListeners();
        registerCommands();
        startKickTimeoutTask();

        getLogger().info("插件加载完成！");
    }

    /**
     * 启动踢出超时定时任务
     * 定期检查未登录玩家是否超过踢出超时时间
     */
    private void startKickTimeoutTask() {
        int kickTimeout = configManager.getKickTimeout();
        if (kickTimeout <= 0) {
            getLogger().info("未登录踢出功能已禁用");
            return;
        }
        // 每5秒检查一次
        kickTimeoutFuture = threadPoolManager.scheduleAtFixedRate(() -> {
            try {
                for (UUID uuid : accountManager.getOnlinePlayerUuids()) {
                    if (accountManager.isLoginTimedOut(uuid)) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            player.kick(messageManager.getMessage("security.kick-timeout"));
                            getLogger().info("玩家 " + player.getName() + " 因登录超时被踢出");
                        }
                    }
                }
            } catch (Exception e) {
                getLogger().warning("踢出超时检查异常: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
        getLogger().info("未登录踢出任务已启动，超时时间: " + kickTimeout + " 秒");
    }

    private void initDataFolder() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
            getLogger().info("数据文件夹已创建: " + dataFolder.getPath());
        }
    }

    private void initConfig() {
        configManager = new ConfigManager(this);
        configManager.loadConfig();

        messageManager = new MessageManager(this);
        messageManager.loadMessages();

        getLogger().info("配置文件加载完成");
    }

    private void initThreadPool() {
        threadPoolManager = new ThreadPoolManager(this, configManager);
        threadPoolManager.init();
    }

    private void initDatabase() {
        databaseManager = new DatabaseManager(this, configManager);
        databaseManager.init();

        sqlTaskQueue = new SQLTaskQueue(this, databaseManager);
        sqlTaskQueue.start();
    }

    private void initManagers() {
        uiLock = new UILock(this, configManager);

        formDialogManager = new FormDialogManager(this, configManager, messageManager,
                threadPoolManager, uiLock);

        accountManager = new AccountManager(this, configManager, messageManager,
                databaseManager, sqlTaskQueue);

        premiumAuthManager = new PremiumAuthManager(this, configManager, threadPoolManager);
    }

    private void registerListeners() {
        playerListener = new PlayerListener(this, accountManager,
                formDialogManager, messageManager, uiLock,
                premiumAuthManager, threadPoolManager);
        Bukkit.getPluginManager().registerEvents(playerListener, this);

        dialogListener = new DialogListener(this, formDialogManager);
        Bukkit.getPluginManager().registerEvents(dialogListener, this);

        getLogger().info("事件监听器已注册");
    }

    private void registerCommands() {
        commandHandler = new CommandHandler(this, accountManager, configManager,
                messageManager, databaseManager, formDialogManager);
        Objects.requireNonNull(getCommand("sl")).setExecutor(commandHandler);
        Objects.requireNonNull(getCommand("sl")).setTabCompleter(commandHandler);
        getLogger().info("指令已注册");
    }

    @Override
    public void onDisable() {
        getLogger().info("正在关闭插件...");

        if (kickTimeoutFuture != null) {
            kickTimeoutFuture.cancel(false);
        }

        if (sqlTaskQueue != null) {
            sqlTaskQueue.stop();
            getLogger().info("SQL任务队列已停止");
        }

        if (databaseManager != null) {
            databaseManager.closeAllConnections();
            getLogger().info("数据库连接已关闭");
        }

        if (databaseManager != null && databaseManager.isInitialized()) {
            databaseManager.backupDatabase();
            getLogger().info("数据库已备份");
        }

        if (threadPoolManager != null) {
            threadPoolManager.shutdown();
            getLogger().info("线程池已关闭");
        }

        if (uiLock != null) {
            uiLock.clearAll();
        }

        getLogger().info("StartLogin 插件已关闭");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public ThreadPoolManager getThreadPoolManager() {
        return threadPoolManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public SQLTaskQueue getSqlTaskQueue() {
        return sqlTaskQueue;
    }

    public AccountManager getAccountManager() {
        return accountManager;
    }

    public UILock getUiLock() {
        return uiLock;
    }

    public FormDialogManager getFormDialogManager() {
        return formDialogManager;
    }

    public PremiumAuthManager getPremiumAuthManager() {
        return premiumAuthManager;
    }
}
