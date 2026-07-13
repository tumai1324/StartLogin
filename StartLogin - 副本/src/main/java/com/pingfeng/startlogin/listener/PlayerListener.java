package com.pingfeng.startlogin.listener;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.account.AccountManager;
import com.pingfeng.startlogin.account.AccountManager.AccountData;
import com.pingfeng.startlogin.config.ConfigManager;
import com.pingfeng.startlogin.config.MessageManager;
import com.pingfeng.startlogin.ui.FormDialogManager;
import com.pingfeng.startlogin.ui.UILock;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final StartLogin plugin;
    private final AccountManager accountManager;
    private final FormDialogManager formDialogManager;
    private final MessageManager messageManager;
    private final UILock uiLock;

    public PlayerListener(StartLogin plugin, AccountManager accountManager,
                          FormDialogManager formDialogManager,
                          MessageManager messageManager, UILock uiLock) {
        this.plugin = plugin;
        this.accountManager = accountManager;
        this.formDialogManager = formDialogManager;
        this.messageManager = messageManager;
        this.uiLock = uiLock;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        // 记录加入时间，用于踢出超时检测
        accountManager.setJoinTime(uuid);

        // 单端在线检测
        if (configManager().isSingleSession()) {
            UUID existingUuid = accountManager.getOnlineSession(username);
            if (existingUuid != null && !existingUuid.equals(uuid)) {
                Player existingPlayer = Bukkit.getPlayer(existingUuid);
                if (existingPlayer != null && existingPlayer.isOnline()) {
                    existingPlayer.kick(Component.text("你的账号在其他地方登录了"));
                }
            }
        }

        accountManager.addOnlineSession(uuid, username);
        accountManager.setLoggedIn(uuid, false, false);

        // 查询账号数据
        accountManager.loadAccount(uuid, username, data -> {
            if (!player.isOnline()) {
                return;
            }

            if (data == null) {
                // 新玩家：注册流程
                if (configManager().isVerboseLogging()) {
                    plugin.getLogger().info("新玩家进入: " + username + " (UUID: " + uuid + ")");
                }
                doRegister(player);
            } else {
                // 老玩家：登录流程
                if (configManager().isVerboseLogging()) {
                    plugin.getLogger().info("老玩家进入: " + username + " (UUID: " + uuid + ")");
                }
                accountManager.setAgreedRuleLocal(uuid, data.hasAgreedRule);

                // 检查会话缓存，有效期内自动登录
                if (accountManager.isSessionCached(uuid)) {
                    if (configManager().isVerboseLogging()) {
                        plugin.getLogger().info("玩家 " + username + " 在会话缓存有效期内，自动登录");
                    }
                    accountManager.setLoggedIn(uuid, true, false);
                    accountManager.cacheSession(uuid); // 刷新缓存时间

                    // 自动登录后也需要检查强制改密码和密码过期
                    String ip = getPlayerIp(player);
                    accountManager.recordLoginAttempt(uuid, username, ip, true);
                    accountManager.updateLoginInfo(uuid, ip, null);
                    handlePostLogin(player, data);
                } else {
                    doLogin(player);
                }
            }
        });
    }

    /**
     * 登录成功后的通用处理：检查强制改密码、密码过期、异地登录提醒、欢迎消息
     */
    private void handlePostLogin(Player player, AccountManager.AccountData data) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        // 检查强制改密码
        accountManager.checkForceChangePassword(uuid, needChange -> {
            if (!player.isOnline()) return;
            if (needChange) {
                formDialogManager.openMessageDialog(player,
                        messageManager.getPlainMessage("security.force-change-password"),
                        () -> doForceChangePassword(player));
                return;
            }
            // 检查密码过期
            accountManager.checkPasswordExpired(uuid, expired -> {
                if (!player.isOnline()) return;
                if (expired) {
                    formDialogManager.openMessageDialog(player,
                            messageManager.getPlainMessage("security.password-expired"),
                            () -> doForceChangePassword(player));
                    return;
                }
                // 检查异地登录
                String ip = getPlayerIp(player);
                accountManager.checkRemoteLogin(uuid, ip, isRemote -> {
                    if (!player.isOnline()) return;
                    String msg = configManager().getWelcomeMessage();
                    if (msg == null || msg.isEmpty()) {
                        msg = "<green>登录成功！欢迎回来";
                    }
                    if (isRemote) {
                        msg = messageManager.getPlainMessage("security.remote-login") + "\n" + msg;
                    }
                    final String welcomeMsg = msg;
                    if (accountManager.hasAgreedRule(uuid)) {
                        formDialogManager.openMessageDialog(player, welcomeMsg, null);
                    } else {
                        doRule(player, () -> formDialogManager.openMessageDialog(player, welcomeMsg, null));
                    }
                });
            });
        });
    }

    /**
     * 强制修改密码流程（管理员强制或密码过期）
     */
    private void doForceChangePassword(Player player) {
        UUID uuid = player.getUniqueId();

        formDialogManager.openRegisterForm(player, () -> {
            if (!player.isOnline()) return;

            java.util.Map<String, String> inputs = formDialogManager.getInputs(uuid);
            if (inputs == null) {
                formDialogManager.openMessageDialog(player, "<red>获取输入失败，请重试", () -> doForceChangePassword(player));
                return;
            }

            String password = inputs.getOrDefault("password", "");
            String confirm = inputs.getOrDefault("confirm", "");

            if (password.isEmpty()) {
                formDialogManager.openMessageDialog(player, "<red>密码不能为空", () -> doForceChangePassword(player));
                return;
            }
            if (!password.equals(confirm)) {
                formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("register.password-mismatch"), () -> doForceChangePassword(player));
                return;
            }
            // 密码长度检查
            int minLength = configManager().getPasswordMinLength();
            int maxLength = configManager().getPasswordMaxLength();
            if (password.length() < minLength || password.length() > maxLength) {
                String msg = messageManager.getPlainMessage("register.password-length-error",
                        "min", String.valueOf(minLength),
                        "max", String.valueOf(maxLength));
                formDialogManager.openMessageDialog(player, msg, () -> doForceChangePassword(player));
                return;
            }
            // 密码黑名单
            if (configManager().isPasswordBlacklisted(password)) {
                formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("register.password-blacklisted"), () -> doForceChangePassword(player));
                return;
            }
            // 密码强度
            String strengthError = accountManager.checkPasswordStrength(password);
            if (strengthError != null) {
                formDialogManager.openMessageDialog(player, messageManager.getPlainMessage(strengthError), () -> doForceChangePassword(player));
                return;
            }

            // 重置密码（不需要旧密码）
            accountManager.resetPassword(uuid, password, success -> {
                if (!player.isOnline()) return;
                if (success) {
                    formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("changepwd.success"), null);
                } else {
                    formDialogManager.openMessageDialog(player, "<red>密码修改失败，请重试", () -> doForceChangePassword(player));
                }
            });
        }, () -> {
            // 不允许切换到登录，必须改密码
            formDialogManager.openMessageDialog(player, "<red>必须修改密码才能继续游戏", () -> doForceChangePassword(player));
        });
    }

    private String getPlayerIp(Player player) {
        if (player.getAddress() != null) {
            return player.getAddress().getHostString();
        }
        return "unknown";
    }

    /**
     * 注册流程：打开注册对话框 → 提交后验证密码 → 注册账号 → 显示规则
     */
    private void doRegister(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String ip = getPlayerIp(player);

        formDialogManager.openRegisterForm(player, () -> {
            if (!player.isOnline()) {
                return;
            }

            // 获取对话框输入的密码
            java.util.Map<String, String> inputs = formDialogManager.getInputs(uuid);
            if (inputs == null) {
                formDialogManager.openMessageDialog(player, "<red>获取输入失败，请重试", () -> doRegister(player));
                return;
            }

            String password = inputs.getOrDefault("password", "");
            String confirm = inputs.getOrDefault("confirm", "");

            // 验证密码不为空
            if (password.isEmpty()) {
                formDialogManager.openMessageDialog(player, "<red>密码不能为空", () -> doRegister(player));
                return;
            }

            // 验证两次密码是否一致
            if (!password.equals(confirm)) {
                formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("register.password-mismatch"), () -> doRegister(player));
                return;
            }

            // 验证密码长度
            int minLength = configManager().getPasswordMinLength();
            int maxLength = configManager().getPasswordMaxLength();
            if (password.length() < minLength || password.length() > maxLength) {
                String msg = messageManager.getPlainMessage("register.password-length-error",
                        "min", String.valueOf(minLength),
                        "max", String.valueOf(maxLength));
                formDialogManager.openMessageDialog(player, msg, () -> doRegister(player));
                return;
            }

            // 检查密码是否在黑名单中
            if (configManager().isPasswordBlacklisted(password)) {
                formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("register.password-blacklisted"), () -> doRegister(player));
                return;
            }

            // 密码强度检测
            String strengthError = accountManager.checkPasswordStrength(password);
            if (strengthError != null) {
                formDialogManager.openMessageDialog(player, messageManager.getPlainMessage(strengthError), () -> doRegister(player));
                return;
            }

            // 检查IP注册限制
            accountManager.checkIpRegistrationLimit(ip, (allowed, current, limit) -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!allowed) {
                    formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("security.ip-limit"), () -> doRegister(player));
                    return;
                }

                // 检查昵称是否被占用
                accountManager.checkNameConflict(username, conflict -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (conflict) {
                        formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("register.name-taken"), () -> doRegister(player));
                        return;
                    }

                    // 注册账号
                    accountManager.registerAccount(uuid, username, password, ip, success -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (success) {
                            // 注册成功后设置已登录状态并缓存会话
                            accountManager.setLoggedIn(uuid, true, false);
                            accountManager.cacheSession(uuid);
                            // 启用首次登录保护（无敌效果）
                            accountManager.enableFirstLoginProtection(uuid);
                            // 显示规则
                            doRule(player, () -> {
                                formDialogManager.openMessageDialog(player, "<green>注册成功！欢迎加入服务器", null);
                            });
                        } else {
                            formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("register.failed"), () -> doRegister(player));
                        }
                    });
                });
            });
        }, () -> {
            // 切换到登录（保留切换按钮功能）
            doLogin(player);
        });
    }

    /**
     * 登录流程：打开登录对话框 → 提交后验证密码 → 登录成功 → 检查规则
     */
    private void doLogin(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String ip = getPlayerIp(player);

        formDialogManager.openLoginForm(player, () -> {
            if (!player.isOnline()) {
                return;
            }

            // 获取对话框输入的密码
            java.util.Map<String, String> inputs = formDialogManager.getInputs(uuid);
            if (inputs == null) {
                formDialogManager.openMessageDialog(player, "<red>获取输入失败，请重试", () -> doLogin(player));
                return;
            }

            String password = inputs.getOrDefault("password", "");

            // 验证密码不为空
            if (password.isEmpty()) {
                formDialogManager.openMessageDialog(player, "<red>密码不能为空", () -> doLogin(player));
                return;
            }

            // 验证密码
            accountManager.verifyPassword(uuid, password, success -> {
                if (!player.isOnline()) {
                    return;
                }
                if (success) {
                    accountManager.resetFailedAttempts(uuid);
                    accountManager.setLoggedIn(uuid, true, false);
                    accountManager.cacheSession(uuid); // 缓存会话

                    // 记录登录尝试和更新登录信息
                    accountManager.recordLoginAttempt(uuid, username, ip, true);
                    accountManager.updateLoginInfo(uuid, ip, null);

                    // 加载账号数据进行后续检查
                    accountManager.loadAccount(uuid, username, data -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        handlePostLogin(player, data);
                    });
                } else {
                    // 记录失败的登录尝试
                    accountManager.recordLoginAttempt(uuid, username, ip, false);
                    accountManager.incrementFailedAttempts(uuid);
                    if (accountManager.isAccountLocked(uuid)) {
                        player.kick(messageManager.getMessage("login.account-locked"));
                    } else {
                        int remaining = accountManager.getRemainingAttempts(uuid);
                        String msg = messageManager.getPlainMessage("login.wrong-password",
                                "count", String.valueOf(remaining));
                        formDialogManager.openMessageDialog(player, msg, () -> doLogin(player));
                    }
                }
            });
        }, () -> {
            // 切换到注册
            doRegister(player);
        });
    }

    /**
     * 规则流程：显示规则对话框 → 同意则标记
     */
    private void doRule(Player player, Runnable onAgree) {
        formDialogManager.openRuleForm(player,
                () -> accountManager.setAgreedRule(player.getUniqueId(), onAgree),
                () -> {
                    // 拒绝规则，由 FormDialogManager 踢出
                });
    }

    // ==================== 未登录玩家限制 ====================

    private boolean isRestricted(Player player) {
        return player != null && !accountManager.isLoggedIn(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isRestricted(player)) {
            // 只允许转身，不允许移动位置
            if (event.getFrom().getX() != event.getTo().getX()
                    || event.getFrom().getY() != event.getTo().getY()
                    || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            // 未登录玩家或首次登录保护期玩家不受伤害
            if (isRestricted(player) || accountManager.isFirstLoginProtected(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
            // 不显示提示，聊天已取消
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isRestricted(player)) {
            String msg = event.getMessage().toLowerCase();
            // 只允许 /sl 开头的指令
            if (!msg.startsWith("/sl ") && !msg.equals("/sl")) {
                event.setCancelled(true);
                // 不显示提示，指令已取消
            }
        }
    }

    // ==================== 离开处理 ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        handlePlayerLeave(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerKick(PlayerKickEvent event) {
        handlePlayerLeave(event.getPlayer());
    }

    private void handlePlayerLeave(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        accountManager.removeOnlineSession(uuid, username);
        accountManager.removeJoinTime(uuid);
        accountManager.removeFirstLoginProtection(uuid);
        formDialogManager.clearPlayerState(uuid);
    }

    private ConfigManager configManager() {
        return plugin.getConfigManager();
    }
}
