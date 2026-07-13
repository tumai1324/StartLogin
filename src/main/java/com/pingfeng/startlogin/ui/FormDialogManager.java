package com.pingfeng.startlogin.ui;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.config.ConfigManager;
import com.pingfeng.startlogin.config.MessageManager;
import com.pingfeng.startlogin.thread.ThreadPoolManager;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class FormDialogManager {

    private final StartLogin plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final ThreadPoolManager threadPoolManager;
    private final UILock uiLock;
    private final MiniMessage miniMessage;

    private final Map<UUID, DialogSession> dialogSessions;
    private final Map<UUID, Map<String, String>> inputCache;

    public static class DialogSession {
        public String type;
        public String title;
        public String content;
        public String primaryButton;
        public String secondaryButton;
        public List<String> inputKeys;
        public Runnable onPrimary;
        public Runnable onSecondary;
        public final Map<String, String> inputs;
        public String errorMessage;

        public DialogSession() {
            this.inputs = new HashMap<>();
            this.inputKeys = new ArrayList<>();
        }

        public DialogSession type(String type) {
            this.type = type;
            return this;
        }

        public DialogSession title(String title) {
            this.title = title;
            return this;
        }

        public DialogSession content(String content) {
            this.content = content;
            return this;
        }

        public DialogSession primaryButton(String text) {
            this.primaryButton = text;
            return this;
        }

        public DialogSession secondaryButton(String text) {
            this.secondaryButton = text;
            return this;
        }

        public DialogSession input(String key) {
            this.inputKeys.add(key);
            return this;
        }

        public DialogSession onPrimary(Runnable runnable) {
            this.onPrimary = runnable;
            return this;
        }

        public DialogSession onSecondary(Runnable runnable) {
            this.onSecondary = runnable;
            return this;
        }

        public DialogSession error(String error) {
            this.errorMessage = error;
            return this;
        }

        public DialogSession clearError() {
            this.errorMessage = null;
            return this;
        }
    }

    public FormDialogManager(StartLogin plugin, ConfigManager configManager,
                             MessageManager messageManager, ThreadPoolManager threadPoolManager,
                             UILock uiLock) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.threadPoolManager = threadPoolManager;
        this.uiLock = uiLock;
        this.miniMessage = MiniMessage.miniMessage();
        this.dialogSessions = new ConcurrentHashMap<>();
        this.inputCache = new ConcurrentHashMap<>();
    }

    /**
     * 创建并显示一个通用对话框
     */
    private void showDialog(Player player, DialogSession session) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        try {
            String displayTitle = session.title;
            String displayContent = session.content;

            if (session.errorMessage != null && !session.errorMessage.isEmpty()) {
                displayContent = session.errorMessage + "\n\n" + displayContent;
            }

            DialogBase.Builder baseBuilder = DialogBase.builder(text(displayTitle))
                    .canCloseWithEscape(session.type.equals("message"))
                    .body(List.of(DialogBody.plainMessage(text(displayContent))));

            if (!session.inputKeys.isEmpty()) {
                List<DialogInput> inputs = new ArrayList<>();
                for (String key : session.inputKeys) {
                    String label = switch (key) {
                        case "password" -> "密码";
                        case "confirm" -> "确认密码";
                        default -> key;
                    };
                    inputs.add(DialogInput.text(key, text(label)).build());
                }
                baseBuilder.inputs(inputs);
            }

            ActionButton primaryBtn = ActionButton.builder(text(session.primaryButton))
                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                            Key.key("startlogin", "submit"), null))
                    .build();

            ActionButton secondaryBtn = ActionButton.builder(text(session.secondaryButton))
                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                            Key.key("startlogin", "switch"), null))
                    .build();

            Dialog dialog = Dialog.create(factory -> {
                io.papermc.paper.registry.RegistryBuilder<io.papermc.paper.dialog.Dialog> builder = factory.empty();
                io.papermc.paper.registry.data.dialog.DialogRegistryEntry.Builder entryBuilder = 
                        (io.papermc.paper.registry.data.dialog.DialogRegistryEntry.Builder) builder;
                entryBuilder.base(baseBuilder.build());
                entryBuilder.type(DialogType.confirmation(primaryBtn, secondaryBtn));
            });

            player.showDialog(dialog);

            logVerbose("对话框已发送 [" + session.type + "] 给 " + player.getName());

        } catch (Exception e) {
            plugin.getLogger().severe("显示对话框失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 更新对话框内容（保持会话状态，重新显示）
     */
    public void updateDialog(Player player) {
        UUID uuid = player.getUniqueId();
        DialogSession session = dialogSessions.get(uuid);
        if (session == null) {
            plugin.getLogger().warning("尝试更新不存在的对话框会话: " + player.getName());
            return;
        }
        showDialog(player, session);
    }

    /**
     * 更新对话框内容并显示错误消息
     */
    public void updateDialogWithError(Player player, String errorMessage) {
        UUID uuid = player.getUniqueId();
        DialogSession session = dialogSessions.get(uuid);
        if (session == null) {
            plugin.getLogger().warning("尝试更新不存在的对话框会话: " + player.getName());
            return;
        }
        session.error(errorMessage);
        showDialog(player, session);
    }

    /**
     * 清除错误消息并刷新对话框
     */
    public void clearDialogError(Player player) {
        UUID uuid = player.getUniqueId();
        DialogSession session = dialogSessions.get(uuid);
        if (session != null) {
            session.clearError();
            showDialog(player, session);
        }
    }

    /**
     * 打开登录对话框
     */
    public void openLoginForm(Player player, Runnable onSuccess, Runnable onRegisterSwitch) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        if (!uiLock.tryLock(uuid)) {
            plugin.getLogger().warning("打开登录窗体失败: UI锁被占用 (玩家: " + player.getName() + ")");
            return;
        }

        DialogSession session = new DialogSession()
                .type("login")
                .title(getStr("login.title", "登录"))
                .content(getStr("login.password-label", "请输入密码"))
                .input("password")
                .primaryButton(getStr("login.submit-button", "登录"))
                .secondaryButton(getStr("login.switch-button", "没有账号？点此注册"))
                .onPrimary(onSuccess)
                .onSecondary(onRegisterSwitch);

        dialogSessions.put(uuid, session);
        showDialog(player, session);
    }

    /**
     * 打开注册对话框
     */
    public void openRegisterForm(Player player, Runnable onSuccess, Runnable onLoginSwitch) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        if (!uiLock.tryLock(uuid)) {
            plugin.getLogger().warning("打开注册窗体失败: UI锁被占用 (玩家: " + player.getName() + ")");
            return;
        }

        DialogSession session = new DialogSession()
                .type("register")
                .title(getStr("register.title", "注册"))
                .content(getStr("register.password-label", "请设置密码"))
                .input("password")
                .input("confirm")
                .primaryButton(getStr("register.submit-button", "注册"))
                .secondaryButton(getStr("register.switch-button", "已有账号？点此登录"))
                .onPrimary(onSuccess)
                .onSecondary(onLoginSwitch);

        dialogSessions.put(uuid, session);
        showDialog(player, session);
    }

    /**
     * 打开规则对话框
     */
    public void openRuleForm(Player player, Runnable onAgree, Runnable onRefuse) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        if (!uiLock.tryLock(uuid)) {
            plugin.getLogger().warning("打开规则窗体失败: UI锁被占用 (玩家: " + player.getName() + ")");
            return;
        }

        DialogSession session = new DialogSession()
                .type("rule")
                .title(getStr("rule.title", "服务器规则"))
                .content(getStr("rule.content", "请阅读并同意服务器规则"))
                .primaryButton(getStr("rule.agree-button", "同意"))
                .secondaryButton(getStr("rule.refuse-button", "拒绝"))
                .onPrimary(onAgree)
                .onSecondary(onRefuse);

        dialogSessions.put(uuid, session);
        showDialog(player, session);
    }

    /**
     * 打开消息对话框（用于错误提示、成功消息等）
     */
    public void openMessageDialog(Player player, String message, Runnable onClose) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        DialogSession existingSession = dialogSessions.get(uuid);
        boolean hadLock = existingSession != null;

        DialogSession session = new DialogSession()
                .type("message")
                .title("提示")
                .content(message)
                .primaryButton("确定")
                .secondaryButton("关闭")
                .onPrimary(onClose)
                .onSecondary(onClose);

        dialogSessions.put(uuid, session);
        showDialog(player, session);
    }

    /**
     * 打开强制改密码对话框
     */
    public void openForceChangePasswordForm(Player player, Runnable onSuccess, Runnable onCancel) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        if (!uiLock.tryLock(uuid)) {
            plugin.getLogger().warning("打开强制改密码窗体失败: UI锁被占用 (玩家: " + player.getName() + ")");
            return;
        }

        DialogSession session = new DialogSession()
                .type("forcechangepwd")
                .title("强制修改密码")
                .content("管理员要求你修改密码，请设置新密码")
                .input("password")
                .input("confirm")
                .primaryButton("确认修改")
                .secondaryButton("取消")
                .onPrimary(onSuccess)
                .onSecondary(onCancel);

        dialogSessions.put(uuid, session);
        showDialog(player, session);
    }

    /**
     * 切换对话框类型（保留输入缓存）
     */
    public void switchDialogType(Player player, String newType) {
        UUID uuid = player.getUniqueId();
        DialogSession currentSession = dialogSessions.get(uuid);
        if (currentSession == null) {
            plugin.getLogger().warning("尝试切换不存在的对话框会话: " + player.getName());
            return;
        }

        Map<String, String> savedInputs = new HashMap<>(currentSession.inputs);

        DialogSession newSession = new DialogSession();

        switch (newType) {
            case "login" -> newSession
                    .type("login")
                    .title(getStr("login.title", "登录"))
                    .content(getStr("login.password-label", "请输入密码"))
                    .input("password")
                    .primaryButton(getStr("login.submit-button", "登录"))
                    .secondaryButton(getStr("login.switch-button", "没有账号？点此注册"));
            case "register" -> newSession
                    .type("register")
                    .title(getStr("register.title", "注册"))
                    .content(getStr("register.password-label", "请设置密码"))
                    .input("password")
                    .input("confirm")
                    .primaryButton(getStr("register.submit-button", "注册"))
                    .secondaryButton(getStr("register.switch-button", "已有账号？点此登录"));
        }

        newSession.inputs.putAll(savedInputs);
        dialogSessions.put(uuid, newSession);
        showDialog(player, newSession);
    }

    /**
     * 处理对话框点击事件
     */
    public void handleDialogClick(Player player, Key key, io.papermc.paper.dialog.DialogResponseView responseView) {
        UUID uuid = player.getUniqueId();
        DialogSession session = dialogSessions.get(uuid);
        if (session == null) {
            plugin.getLogger().warning("收到对话框点击但没有session: " + player.getName());
            return;
        }

        // 从响应中提取输入
        if (responseView != null) {
            try {
                for (String keyName : session.inputKeys) {
                    String value = responseView.getText(keyName);
                    if (value != null) {
                        session.inputs.put(keyName, value);
                        logVerbose("对话框输入 [" + keyName + "] = " + value);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("读取对话框输入失败: " + e.getMessage());
            }
        }

        String keyStr = key.value();
        logVerbose("对话框点击: " + keyStr + " (玩家: " + player.getName() + ")");

        // 保存输入到缓存
        inputCache.put(uuid, new HashMap<>(session.inputs));

        if ("submit".equals(keyStr)) {
            session.clearError();
            if (session.onPrimary != null) {
                try {
                    session.onPrimary.run();
                } catch (Exception e) {
                    plugin.getLogger().severe("对话框主按钮回调异常: " + e);
                    e.printStackTrace();
                }
            }
        } else if ("switch".equals(keyStr)) {
            if (session.onSecondary != null) {
                try {
                    session.onSecondary.run();
                } catch (Exception e) {
                    plugin.getLogger().severe("对话框切换按钮回调异常: " + e);
                    e.printStackTrace();
                    uiLock.forceUnlock(uuid);
                }
            }
        } else if ("agree".equals(keyStr)) {
            cleanupDialog(uuid);
            if (session.onPrimary != null) {
                try {
                    session.onPrimary.run();
                } catch (Exception e) {
                    plugin.getLogger().severe("对话框同意按钮回调异常: " + e);
                    e.printStackTrace();
                }
            }
        } else if ("refuse".equals(keyStr)) {
            cleanupDialog(uuid);
            player.kick(messageManager.getMessage("rule.refused"));
        } else if ("close".equals(keyStr)) {
            cleanupDialog(uuid);
            if (session.onPrimary != null) {
                try {
                    session.onPrimary.run();
                } catch (Exception e) {
                    plugin.getLogger().severe("对话框关闭回调异常: " + e);
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 清理对话框会话
     */
    public void cleanupDialog(UUID uuid) {
        dialogSessions.remove(uuid);
        uiLock.forceUnlock(uuid);
        threadPoolManager.schedule(() -> inputCache.remove(uuid), 3, TimeUnit.SECONDS);
    }

    /**
     * 清理对话框会话（不释放锁，用于切换）
     */
    public void cleanupDialogNoUnlock(UUID uuid) {
        dialogSessions.remove(uuid);
        threadPoolManager.schedule(() -> inputCache.remove(uuid), 3, TimeUnit.SECONDS);
    }

    /**
     * 获取当前对话框输入
     */
    public Map<String, String> getInputs(UUID uuid) {
        DialogSession session = dialogSessions.get(uuid);
        if (session != null) {
            return new HashMap<>(session.inputs);
        }
        return inputCache.get(uuid);
    }

    /**
     * 获取当前会话类型
     */
    public String getSessionType(UUID uuid) {
        DialogSession session = dialogSessions.get(uuid);
        return session != null ? session.type : null;
    }

    /**
     * 是否有活动的对话框会话
     */
    public boolean hasDialogSession(UUID uuid) {
        return dialogSessions.containsKey(uuid);
    }

    /**
     * 获取当前会话
     */
    public DialogSession getDialogSession(UUID uuid) {
        return dialogSessions.get(uuid);
    }

    private String getStr(String path, String defaultVal) {
        String val = messageManager.getString(path);
        if (val == null || val.isEmpty()) return defaultVal;
        return val;
    }

    private Component text(String str) {
        if (str == null || str.isEmpty()) return Component.empty();
        try {
            return miniMessage.deserialize(str);
        } catch (Exception e) {
            return Component.text(str);
        }
    }

    public UILock getUiLock() {
        return uiLock;
    }

    public void clearPlayerState(UUID uuid) {
        dialogSessions.remove(uuid);
        inputCache.remove(uuid);
        uiLock.forceUnlock(uuid);
    }

    private void logVerbose(String msg) {
        if (configManager.isVerboseLogging()) {
            plugin.getLogger().info(msg);
        }
    }
}