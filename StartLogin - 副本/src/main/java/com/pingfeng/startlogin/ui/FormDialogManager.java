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
    private final Set<UUID> switchingPlayers;

    public static class DialogSession {
        public final String type;
        public final Runnable onPrimary;
        public final Runnable onSecondary;
        public final Map<String, String> inputs;

        public DialogSession(String type, Runnable onPrimary, Runnable onSecondary) {
            this.type = type;
            this.onPrimary = onPrimary;
            this.onSecondary = onSecondary;
            this.inputs = new HashMap<>();
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
        this.dialogSessions = new HashMap<>();
        this.inputCache = new HashMap<>();
        this.switchingPlayers = ConcurrentHashMap.newKeySet();
    }

    /**
     * 显示消息对话框（用于错误提示、成功消息等）
     */
    public void openMessageDialog(Player player, String message, Runnable onClose) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        // 消息对话框不需要锁，但需要确保不会与登录/注册对话框冲突
        // 如果已有 session，先清理
        DialogSession existingSession = dialogSessions.get(uuid);
        if (existingSession != null && !"message".equals(existingSession.type)) {
            plugin.getLogger().warning("尝试在已有对话框时打开消息对话框: " + player.getName());
        }

        try {
            dialogSessions.put(uuid, new DialogSession("message", onClose, onClose));

            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(text("提示"))
                            .canCloseWithEscape(true)
                            .body(List.of(DialogBody.plainMessage(text(message))))
                            .build())
                    .type(DialogType.confirmation(
                            ActionButton.builder(text("确定"))
                                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                            Key.key("startlogin", "close"), null))
                                    .build(),
                            ActionButton.builder(text("关闭"))
                                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                            Key.key("startlogin", "close"), null))
                                    .build()
                    )));

            player.showDialog(dialog);

        } catch (Exception e) {
            plugin.getLogger().severe("打开消息对话框失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            dialogSessions.remove(uuid);
            if (onClose != null) {
                onClose.run();
            }
        }
    }

    public void openLoginForm(Player player, Runnable onSuccess, Runnable onRegisterSwitch) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        boolean isSwitching = switchingPlayers.remove(uuid);
        if (!isSwitching && !uiLock.tryLock(uuid)) {
            plugin.getLogger().warning("打开登录窗体失败: UI锁被占用 (玩家: " + player.getName() + ")");
            return;
        }

        try {
            String title = getStr("login.title", "登录");
            String content = getStr("login.password-label", "请输入密码");
            String submitText = getStr("login.submit-button", "登录");
            String switchText = getStr("login.switch-button", "没有账号？点此注册");

            dialogSessions.put(uuid, new DialogSession("login", onSuccess, onRegisterSwitch));

            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(text(title))
                            .canCloseWithEscape(false)
                            .body(List.of(DialogBody.plainMessage(text(content))))
                            .inputs(List.of(DialogInput.text("password", text("密码")).build()))
                            .build())
                    .type(DialogType.confirmation(
                            ActionButton.builder(text(submitText))
                                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                            Key.key("startlogin", "submit"), null))
                                    .build(),
                            ActionButton.builder(text(switchText))
                                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                            Key.key("startlogin", "switch"), null))
                                    .build()
                    )));

            player.showDialog(dialog);
            logVerbose("登录对话框已发送给 " + player.getName());

        } catch (Exception e) {
            plugin.getLogger().severe("打开登录窗体失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            if (!isSwitching) uiLock.unlock(uuid);
            dialogSessions.remove(uuid);
        }
    }

    public void openRegisterForm(Player player, Runnable onSuccess, Runnable onLoginSwitch) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        boolean isSwitching = switchingPlayers.remove(uuid);
        if (!isSwitching && !uiLock.tryLock(uuid)) {
            plugin.getLogger().warning("打开注册窗体失败: UI锁被占用 (玩家: " + player.getName() + ")");
            return;
        }

        try {
            String title = getStr("register.title", "注册");
            String content = getStr("register.password-label", "请设置密码");
            String submitText = getStr("register.submit-button", "注册");
            String switchText = getStr("register.switch-button", "已有账号？点此登录");

            dialogSessions.put(uuid, new DialogSession("register", onSuccess, onLoginSwitch));

            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(text(title))
                            .canCloseWithEscape(false)
                            .body(List.of(DialogBody.plainMessage(text(content))))
                            .inputs(List.of(
                                    DialogInput.text("password", text("密码")).build(),
                                    DialogInput.text("confirm", text("确认密码")).build()
                            ))
                            .build())
                    .type(DialogType.confirmation(
                            ActionButton.builder(text(submitText))
                                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                            Key.key("startlogin", "submit"), null))
                                    .build(),
                            ActionButton.builder(text(switchText))
                                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                            Key.key("startlogin", "switch"), null))
                                    .build()
                    )));

            player.showDialog(dialog);
            logVerbose("注册对话框已发送给 " + player.getName());

        } catch (Exception e) {
            plugin.getLogger().severe("打开注册窗体失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            if (!isSwitching) uiLock.unlock(uuid);
            dialogSessions.remove(uuid);
        }
    }

    public void openRuleForm(Player player, Runnable onAgree, Runnable onRefuse) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;
        if (!uiLock.tryLock(uuid)) {
            plugin.getLogger().warning("打开规则窗体失败: UI锁被占用 (玩家: " + player.getName() + ")");
            return;
        }

        try {
            String title = getStr("rule.title", "服务器规则");
            String content = getStr("rule.content", "请阅读并同意服务器规则");
            String agreeText = getStr("rule.agree-button", "同意");
            String refuseText = getStr("rule.refuse-button", "拒绝");

            dialogSessions.put(uuid, new DialogSession("rule", onAgree, onRefuse));

            Dialog dialog = Dialog.create(builder -> builder.empty()
                    .base(DialogBase.builder(text(title))
                            .canCloseWithEscape(false)
                            .body(List.of(DialogBody.plainMessage(text(content))))
                            .build())
                    .type(DialogType.confirmation(
                            ActionButton.builder(text(agreeText))
                                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                            Key.key("startlogin", "agree"), null))
                                    .build(),
                            ActionButton.builder(text(refuseText))
                                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                            Key.key("startlogin", "refuse"), null))
                                    .build()
                    )));

            player.showDialog(dialog);
            logVerbose("规则对话框已发送给 " + player.getName());

        } catch (Exception e) {
            plugin.getLogger().severe("打开规则窗体失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            uiLock.unlock(uuid);
            dialogSessions.remove(uuid);
        }
    }

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
                String password = responseView.getText("password");
                if (password != null) {
                    session.inputs.put("password", password);
                    logVerbose("对话框输入 [password] = " + password);
                }
                String confirm = responseView.getText("confirm");
                if (confirm != null) {
                    session.inputs.put("confirm", confirm);
                    logVerbose("对话框输入 [confirm] = " + confirm);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("读取对话框输入失败: " + e.getMessage());
            }
        }

        String keyStr = key.value();
        logVerbose("对话框点击: " + keyStr + " (玩家: " + player.getName() + ")");

        // 保存输入到缓存
        inputCache.put(uuid, new HashMap<>(session.inputs));

        if ("submit".equals(keyStr) || "agree".equals(keyStr)) {
            cleanupDialog(uuid);
            if (session.onPrimary != null) {
                try {
                    session.onPrimary.run();
                } catch (Exception e) {
                    plugin.getLogger().severe("对话框主按钮回调异常: " + e);
                    e.printStackTrace();
                }
            }
        } else if ("switch".equals(keyStr)) {
            // 切换模式：不释放锁，保留输入缓存，直接执行回调
            dialogSessions.remove(uuid);
            inputCache.put(uuid, new HashMap<>(session.inputs));

            switchingPlayers.add(uuid);
            if (session.onSecondary != null) {
                try {
                    session.onSecondary.run();
                } catch (Exception e) {
                    plugin.getLogger().severe("对话框切换按钮回调异常: " + e);
                    e.printStackTrace();
                    switchingPlayers.remove(uuid);
                    uiLock.forceUnlock(uuid);
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

    private void cleanupDialog(UUID uuid) {
        dialogSessions.remove(uuid);
        uiLock.forceUnlock(uuid);
        threadPoolManager.schedule(() -> inputCache.remove(uuid), 3, TimeUnit.SECONDS);
    }

    public Map<String, String> getInputs(UUID uuid) {
        return inputCache.get(uuid);
    }

    public boolean hasDialogSession(UUID uuid) {
        return dialogSessions.containsKey(uuid);
    }

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
        switchingPlayers.remove(uuid);
        uiLock.forceUnlock(uuid);
    }

    private void logVerbose(String msg) {
        if (configManager.isVerboseLogging()) {
            plugin.getLogger().info(msg);
        }
    }
}