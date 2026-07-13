package com.pingfeng.startlogin.listener;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.account.AccountManager;
import com.pingfeng.startlogin.account.AccountManager.AccountData;
import com.pingfeng.startlogin.auth.PremiumAuthManager;
import com.pingfeng.startlogin.config.ConfigManager;
import com.pingfeng.startlogin.config.MessageManager;
import com.pingfeng.startlogin.thread.ThreadPoolManager;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PlayerListener implements Listener {

    private final StartLogin plugin;
    private final AccountManager accountManager;
    private final FormDialogManager formDialogManager;
    private final MessageManager messageManager;
    private final UILock uiLock;
    private final PremiumAuthManager premiumAuthManager;
    private final ThreadPoolManager threadPoolManager;
    private final Map<UUID, ScheduledFuture<?>> premiumPollTasks;

    public PlayerListener(StartLogin plugin, AccountManager accountManager,
                          FormDialogManager formDialogManager,
                          MessageManager messageManager, UILock uiLock,
                          PremiumAuthManager premiumAuthManager, ThreadPoolManager threadPoolManager) {
        this.plugin = plugin;
        this.accountManager = accountManager;
        this.formDialogManager = formDialogManager;
        this.messageManager = messageManager;
        this.uiLock = uiLock;
        this.premiumAuthManager = premiumAuthManager;
        this.threadPoolManager = threadPoolManager;
        this.premiumPollTasks = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        accountManager.setJoinTime(uuid);

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

        accountManager.loadAccount(uuid, username, data -> {
            if (!player.isOnline()) return;

            if (data == null) {
                if (configManager().isVerboseLogging()) {
                    plugin.getLogger().info("新玩家进入: " + username + " (UUID: " + uuid + ")");
                }
                if (configManager().isPremiumEnabled()) {
                    doModeSelect(player);
                } else {
                    doRegister(player);
                }
            } else {
                if (configManager().isVerboseLogging()) {
                    plugin.getLogger().info("老玩家进入: " + username + " (UUID: " + uuid + ")");
                }
                accountManager.setAgreedRuleLocal(uuid, data.hasAgreedRule);

                if (data.isPremium && configManager().isPremiumEnabled()) {
                    if (accountManager.isSessionCached(uuid)) {
                        if (configManager().isVerboseLogging()) {
                            plugin.getLogger().info("正版玩家 " + username + " 在会话缓存有效期内，自动登录");
                        }
                        accountManager.setLoggedIn(uuid, true, true);
                        accountManager.cacheSession(uuid);
                        String ip = getPlayerIp(player);
                        accountManager.recordLoginAttempt(uuid, username, ip, true);
                        accountManager.updateLoginInfo(uuid, ip, null);
                        handlePostLogin(player, data);
                    } else {
                        doPremiumSessionVerify(player, data);
                    }
                } else {
                    if (accountManager.isSessionCached(uuid)) {
                        if (configManager().isVerboseLogging()) {
                            plugin.getLogger().info("玩家 " + username + " 在会话缓存有效期内，自动登录");
                        }
                        accountManager.setLoggedIn(uuid, true, false);
                        accountManager.cacheSession(uuid);
                        String ip = getPlayerIp(player);
                        accountManager.recordLoginAttempt(uuid, username, ip, true);
                        accountManager.updateLoginInfo(uuid, ip, null);
                        handlePostLogin(player, data);
                    } else {
                        doLogin(player);
                    }
                }
            }
        });
    }

    private void handlePostLogin(Player player, AccountData data) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline()) return;

        accountManager.checkForceChangePassword(uuid, needChange -> {
            if (!player.isOnline()) return;
            if (needChange) {
                formDialogManager.openForceChangePasswordForm(player,
                        () -> doForceChangePasswordSubmit(player),
                        () -> player.kick(messageManager.getMessage("security.force-change-password")));
                return;
            }
            accountManager.checkPasswordExpired(uuid, expired -> {
                if (!player.isOnline()) return;
                if (expired) {
                    formDialogManager.openForceChangePasswordForm(player,
                            () -> doForceChangePasswordSubmit(player),
                            () -> player.kick(messageManager.getMessage("security.password-expired")));
                    return;
                }
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

    private void doForceChangePasswordSubmit(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, String> inputs = formDialogManager.getInputs(uuid);
        if (inputs == null) {
            formDialogManager.updateDialogWithError(player, "<red>获取输入失败，请重试");
            return;
        }

        String password = inputs.getOrDefault("password", "");
        String confirm = inputs.getOrDefault("confirm", "");

        if (password.isEmpty()) {
            formDialogManager.updateDialogWithError(player, "<red>密码不能为空");
            return;
        }
        if (!password.equals(confirm)) {
            formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage("register.password-mismatch"));
            return;
        }

        int minLength = configManager().getPasswordMinLength();
        int maxLength = configManager().getPasswordMaxLength();
        if (password.length() < minLength || password.length() > maxLength) {
            String msg = messageManager.getPlainMessage("register.password-length-error",
                    "min", String.valueOf(minLength), "max", String.valueOf(maxLength));
            formDialogManager.updateDialogWithError(player, msg);
            return;
        }

        if (configManager().isPasswordBlacklisted(password)) {
            formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage("register.password-blacklisted"));
            return;
        }

        String strengthError = accountManager.checkPasswordStrength(password);
        if (strengthError != null) {
            formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage(strengthError));
            return;
        }

        accountManager.resetPassword(uuid, password, success -> {
            if (!player.isOnline()) return;
            if (success) {
                formDialogManager.cleanupDialog(uuid);
                formDialogManager.openMessageDialog(player, messageManager.getPlainMessage("changepwd.success"), null);
            } else {
                formDialogManager.updateDialogWithError(player, "<red>密码修改失败，请重试");
            }
        });
    }

    private String getPlayerIp(Player player) {
        if (player.getAddress() != null) {
            return player.getAddress().getHostString();
        }
        return "unknown";
    }

    private void doRegister(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String ip = getPlayerIp(player);

        formDialogManager.openRegisterForm(player, () -> {
            if (!player.isOnline()) return;

            Map<String, String> inputs = formDialogManager.getInputs(uuid);
            if (inputs == null) {
                formDialogManager.updateDialogWithError(player, "<red>获取输入失败，请重试");
                return;
            }

            String password = inputs.getOrDefault("password", "");
            String confirm = inputs.getOrDefault("confirm", "");

            if (password.isEmpty()) {
                formDialogManager.updateDialogWithError(player, "<red>密码不能为空");
                return;
            }

            if (!password.equals(confirm)) {
                formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage("register.password-mismatch"));
                return;
            }

            int minLength = configManager().getPasswordMinLength();
            int maxLength = configManager().getPasswordMaxLength();
            if (password.length() < minLength || password.length() > maxLength) {
                String msg = messageManager.getPlainMessage("register.password-length-error",
                        "min", String.valueOf(minLength), "max", String.valueOf(maxLength));
                formDialogManager.updateDialogWithError(player, msg);
                return;
            }

            if (configManager().isPasswordBlacklisted(password)) {
                formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage("register.password-blacklisted"));
                return;
            }

            String strengthError = accountManager.checkPasswordStrength(password);
            if (strengthError != null) {
                formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage(strengthError));
                return;
            }

            accountManager.checkIpRegistrationLimit(ip, (allowed, current, limit) -> {
                if (!player.isOnline()) return;
                if (!allowed) {
                    formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage("security.ip-limit"));
                    return;
                }

                accountManager.checkNameConflict(username, conflict -> {
                    if (!player.isOnline()) return;
                    if (conflict) {
                        formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage("register.name-taken"));
                        return;
                    }

                    accountManager.registerAccount(uuid, username, password, ip, success -> {
                        if (!player.isOnline()) return;
                        if (success) {
                            accountManager.setLoggedIn(uuid, true, false);
                            accountManager.cacheSession(uuid);
                            accountManager.enableFirstLoginProtection(uuid);
                            formDialogManager.cleanupDialog(uuid);
                            doRule(player, () -> {
                                formDialogManager.openMessageDialog(player, "<green>注册成功！欢迎加入服务器", null);
                            });
                        } else {
                            formDialogManager.updateDialogWithError(player, messageManager.getPlainMessage("register.failed"));
                        }
                    });
                });
            });
        }, () -> {
            formDialogManager.switchDialogType(player, "login");
        });
    }

    private void doLogin(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String ip = getPlayerIp(player);

        formDialogManager.openLoginForm(player, () -> {
            if (!player.isOnline()) return;

            Map<String, String> inputs = formDialogManager.getInputs(uuid);
            if (inputs == null) {
                formDialogManager.updateDialogWithError(player, "<red>获取输入失败，请重试");
                return;
            }

            String password = inputs.getOrDefault("password", "");

            if (password.isEmpty()) {
                formDialogManager.updateDialogWithError(player, "<red>密码不能为空");
                return;
            }

            accountManager.verifyPassword(uuid, password, success -> {
                if (!player.isOnline()) return;
                if (success) {
                    accountManager.resetFailedAttempts(uuid);
                    accountManager.setLoggedIn(uuid, true, false);
                    accountManager.cacheSession(uuid);
                    accountManager.recordLoginAttempt(uuid, username, ip, true);
                    accountManager.updateLoginInfo(uuid, ip, null);

                    accountManager.loadAccount(uuid, username, data -> {
                        if (!player.isOnline()) return;
                        formDialogManager.cleanupDialog(uuid);
                        handlePostLogin(player, data);
                    });
                } else {
                    accountManager.recordLoginAttempt(uuid, username, ip, false);
                    accountManager.incrementFailedAttempts(uuid);
                    if (accountManager.isAccountLocked(uuid)) {
                        formDialogManager.cleanupDialog(uuid);
                        player.kick(messageManager.getMessage("login.account-locked"));
                    } else {
                        int remaining = accountManager.getRemainingAttempts(uuid);
                        String msg = messageManager.getPlainMessage("login.wrong-password",
                                "count", String.valueOf(remaining));
                        formDialogManager.updateDialogWithError(player, msg);
                    }
                }
            });
        }, () -> {
            formDialogManager.switchDialogType(player, "register");
        });
    }

    private void doRule(Player player, Runnable onAgree) {
        formDialogManager.openRuleForm(player,
                () -> accountManager.setAgreedRule(player.getUniqueId(), onAgree),
                () -> {});
    }

    private void doModeSelect(Player player) {
        formDialogManager.openModeSelectForm(player,
                () -> startPremiumLogin(player),
                () -> {
                    formDialogManager.cleanupDialog(player.getUniqueId());
                    doRegister(player);
                });
    }

    private void startPremiumLogin(Player player) {
        UUID uuid = player.getUniqueId();
        formDialogManager.cleanupDialog(uuid);

        if (configManager().isVerboseLogging()) {
            plugin.getLogger().info("开始正版验证流程: " + player.getName());
        }

        final boolean[] cancelled = {false};
        final String[] currentUserCode = {null};

        premiumAuthManager.startDeviceLogin(new PremiumAuthManager.DeviceLoginStartCallback() {
            @Override
            public void onDeviceCode(String userCode, String verificationUrl) {
                if (!player.isOnline() || cancelled[0]) return;
                currentUserCode[0] = userCode;

                if (configManager().isVerboseLogging()) {
                    plugin.getLogger().info("获取设备码成功: " + userCode + ", " + verificationUrl);
                }

                formDialogManager.openPremiumVerifyForm(player, userCode, verificationUrl,
                        () -> {
                            cancelled[0] = true;
                            doPremiumCancel(player);
                        },
                        () -> {});

                net.kyori.adventure.text.Component clickableLink = net.kyori.adventure.text.Component.text()
                        .append(net.kyori.adventure.text.Component.text("[StartLogin] 请打开浏览器访问以下链接进行正版验证：\n", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                        .append(net.kyori.adventure.text.Component.text(verificationUrl + "\n", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                                .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(verificationUrl)))
                        .append(net.kyori.adventure.text.Component.text("设备码：", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                        .append(net.kyori.adventure.text.Component.text(userCode + "\n", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                                .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(userCode)))
                        .append(net.kyori.adventure.text.Component.text("（点击链接打开验证页面，点击设备码复制）", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                        .build();
                player.sendMessage(clickableLink);
            }

            @Override
            public void onSuccess(UUID premiumUuid, String username, String mcToken, String refreshToken, long accessTokenExpires) {
                if (!player.isOnline() || cancelled[0]) return;
                if (configManager().isVerboseLogging()) {
                    plugin.getLogger().info("正版验证成功: " + username);
                }
                formDialogManager.updatePremiumVerifyStatus(player, "验证成功，正在登录...");
                handlePremiumLoginSuccess(player, premiumUuid, username, mcToken, refreshToken, accessTokenExpires);
            }

            @Override
            public void onFailure(String error) {
                if (!player.isOnline() || cancelled[0]) return;
                plugin.getLogger().warning("正版验证失败: " + player.getName() + ", " + error);
                formDialogManager.cleanupDialog(uuid);
                formDialogManager.openMessageDialog(player, "<red>正版验证失败：" + error + "\n\n请选择离线密码注册",
                        () -> doModeSelect(player));
            }
        });
    }

    private void doPremiumCancel(Player player) {
        UUID uuid = player.getUniqueId();
        formDialogManager.cleanupDialog(uuid);
        doModeSelect(player);
    }

    private void handlePremiumLoginSuccess(Player player, UUID premiumUuid, String username, String mcToken, String refreshToken, long accessTokenExpires) {
        UUID uuid = player.getUniqueId();
        String ip = getPlayerIp(player);

        accountManager.checkPremiumAccount(premiumUuid.toString(), (exists, existingData) -> {
            if (!player.isOnline()) return;

            if (exists) {
                if (!existingData.uuid.equals(uuid)) {
                    formDialogManager.cleanupDialog(uuid);
                    player.kick(Component.text("该正版账号已绑定其他玩家"));
                    return;
                }
                accountManager.setLoggedIn(uuid, true, true);
                accountManager.cacheSession(uuid);
                accountManager.recordLoginAttempt(uuid, username, ip, true);
                accountManager.updateLoginInfo(uuid, ip, null);
                formDialogManager.cleanupDialog(uuid);
                handlePostLogin(player, existingData);
            } else {
                accountManager.checkNameConflict(username, conflict -> {
                    if (!player.isOnline()) return;
                    if (conflict) {
                        formDialogManager.cleanupDialog(uuid);
                        player.kick(Component.text("该玩家名已被注册，请使用离线模式或联系管理员"));
                        return;
                    }
                    accountManager.registerPremiumAccount(uuid, username, premiumUuid.toString(),
                            refreshToken, mcToken, accessTokenExpires, ip, success -> {
                                if (!player.isOnline()) return;
                                if (success) {
                                    accountManager.setLoggedIn(uuid, true, true);
                                    accountManager.cacheSession(uuid);
                                    accountManager.enableFirstLoginProtection(uuid);
                                    formDialogManager.cleanupDialog(uuid);
                                    accountManager.loadAccount(uuid, username, data -> {
                                        if (!player.isOnline()) return;
                                        doRule(player, () -> {
                                            formDialogManager.openMessageDialog(player, "<green>正版验证成功！欢迎加入服务器", null);
                                        });
                                    });
                                } else {
                                    formDialogManager.cleanupDialog(uuid);
                                    formDialogManager.openMessageDialog(player, "<red>注册正版账号失败，请重试",
                                            () -> doModeSelect(player));
                                }
                            });
                });
            }
        });
    }

    private void doPremiumSessionVerify(Player player, AccountData data) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String ip = getPlayerIp(player);

        String serverId = "";
        premiumAuthManager.verifyMinecraftSession(username, serverId, ip, (valid, verifiedUuid, verifiedName) -> {
            if (!player.isOnline()) return;
            if (valid && verifiedUuid != null) {
                if (data.premiumUuid != null && data.premiumUuid.equals(verifiedUuid.toString())) {
                    accountManager.setLoggedIn(uuid, true, true);
                    accountManager.cacheSession(uuid);
                    accountManager.recordLoginAttempt(uuid, username, ip, true);
                    accountManager.updateLoginInfo(uuid, ip, null);
                    handlePostLogin(player, data);
                } else {
                    doLogin(player);
                }
            } else {
                doLogin(player);
            }
        });
    }

    private boolean isRestricted(Player player) {
        return player != null && !accountManager.isLoggedIn(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isRestricted(player)) {
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
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isRestricted(player)) {
            String msg = event.getMessage().toLowerCase();
            if (!msg.startsWith("/sl ") && !msg.equals("/sl")) {
                event.setCancelled(true);
            }
        }
    }

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