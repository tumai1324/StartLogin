package com.pingfeng.startlogin.command;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.account.AccountManager;
import com.pingfeng.startlogin.config.ConfigManager;
import com.pingfeng.startlogin.config.MessageManager;
import com.pingfeng.startlogin.database.DatabaseManager;
import com.pingfeng.startlogin.ui.FormDialogManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CommandHandler implements TabExecutor {

    private final StartLogin plugin;
    private final AccountManager accountManager;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final DatabaseManager databaseManager;
    private final FormDialogManager formDialogManager;

    public CommandHandler(StartLogin plugin, AccountManager accountManager,
                          ConfigManager configManager, MessageManager messageManager,
                          DatabaseManager databaseManager, FormDialogManager formDialogManager) {
        this.plugin = plugin;
        this.accountManager = accountManager;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.databaseManager = databaseManager;
        this.formDialogManager = formDialogManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCmd = args[0].toLowerCase();

        return switch (subCmd) {
            case "login" -> handleLogin(sender, args);
            case "register" -> handleRegister(sender, args);
            case "changepwd" -> handleChangePassword(sender, args);
            case "unregister" -> handleUnregister(sender, args);
            case "reset" -> handleReset(sender, args);
            case "migrate" -> handleMigrate(sender, args);
            case "reload" -> handleReload(sender, args);
            case "backup" -> handleBackup(sender, args);
            case "clean" -> handleClean(sender, args);
            case "info" -> handleInfo(sender, args);
            case "stats" -> handleStats(sender, args);
            case "forcechangepwd" -> handleForceChangePwd(sender, args);
            case "premium" -> handlePremium(sender, args);
            case "unpremium" -> handleUnpremium(sender, args);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleLogin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageManager.getMessage("general.player-only"));
            return true;
        }

        if (accountManager.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(messageManager.getMessage("login.already-logged"));
            return true;
        }

        if (accountManager.isPremiumAccount(player.getUniqueId())) {
            player.sendMessage(messageManager.getMessage("login.already-logged"));
            return true;
        }

        formDialogManager.openLoginForm(player,
                () -> {
                },
                () -> {
                }
        );
        return true;
    }

    private boolean handleRegister(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageManager.getMessage("general.player-only"));
            return true;
        }

        if (accountManager.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(messageManager.getMessage("register.already-registered"));
            return true;
        }

        if (accountManager.isPremiumAccount(player.getUniqueId())) {
            player.sendMessage(messageManager.getMessage("register.already-registered"));
            return true;
        }

        formDialogManager.openRegisterForm(player,
                () -> {
                },
                () -> {
                }
        );
        return true;
    }

    private boolean handleChangePassword(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageManager.getMessage("general.player-only"));
            return true;
        }

        if (!accountManager.isLoggedIn(player.getUniqueId())) {
            player.sendMessage(messageManager.getMessage("login.not-registered"));
            return true;
        }

        if (args.length < 3) {
            player.sendMessage(Component.text("用法: /sl changepwd <旧密码> <新密码>"));
            return true;
        }

        String oldPassword = args[1];
        String newPassword = args[2];

        if (newPassword.length() < 4) {
            player.sendMessage(messageManager.getMessage("register.password-too-short"));
            return true;
        }

        // 密码强度检测
        String strengthError = accountManager.checkPasswordStrength(newPassword);
        if (strengthError != null) {
            player.sendMessage(messageManager.getMessage(strengthError));
            return true;
        }

        accountManager.changePassword(player.getUniqueId(), oldPassword, newPassword, success -> {
            if (success) {
                player.sendMessage(messageManager.getMessage("changepwd.success"));
            } else {
                player.sendMessage(messageManager.getMessage("changepwd.wrong-old-password"));
            }
        });
        return true;
    }

    private boolean handleUnregister(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messageManager.getMessage("general.player-only"));
            return true;
        }

        if (accountManager.isPremiumAccount(player.getUniqueId())) {
            player.sendMessage(Component.text("正版账号无法注销"));
            return true;
        }

        UUID uuid = player.getUniqueId();

        // 检查账号是否太新不能注销
        accountManager.checkAccountTooNew(uuid, tooNew -> {
            if (tooNew) {
                player.sendMessage(messageManager.getMessage("security.account-too-new"));
                return;
            }
            accountManager.unregisterAccount(uuid, success -> {
                if (success) {
                    player.sendMessage(messageManager.getMessage("unregister.success"));
                } else {
                    player.sendMessage(messageManager.getMessage("login.not-registered"));
                }
            });
        });
        return true;
    }

    private boolean handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /sl reset <玩家名> <新密码>"));
            return true;
        }

        String targetName = args[1];
        String newPassword = args.length > 2 ? args[2] : "123456";

        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            target = Bukkit.getOfflinePlayer(targetName);
        }

        UUID targetUuid = target.getUniqueId();

        if (accountManager.isPremiumAccount(targetUuid)) {
            sender.sendMessage(messageManager.getMessage("admin.reset-premium"));
            return true;
        }

        accountManager.resetPassword(targetUuid, newPassword, success -> {
            if (success) {
                sender.sendMessage(messageManager.getMessage("admin.reset-success"));
                logAudit(sender.getName(), "reset", targetName);
            } else {
                sender.sendMessage(Component.text("重置失败，玩家不存在"));
            }
        });
        return true;
    }

    private boolean handleMigrate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        sender.sendMessage(messageManager.getMessage("admin.migrate-start"));

        File backupFile = databaseManager.backupDatabase();
        if (backupFile == null) {
            sender.sendMessage(Component.text("备份失败，已取消迁移"));
            return true;
        }

        sender.sendMessage(messageManager.getMessage("admin.migrate-success"));
        logAudit(sender.getName(), "migrate", "");
        return true;
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        configManager.reloadConfig();
        messageManager.reloadMessages();
        sender.sendMessage(messageManager.getMessage("general.config-reloaded"));
        return true;
    }

    private boolean handleBackup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        File backupFile = databaseManager.backupDatabase();
        if (backupFile != null) {
            sender.sendMessage(messageManager.getMessage("general.database-backed-up"));
            logAudit(sender.getName(), "backup", backupFile.getName());
        } else {
            sender.sendMessage(Component.text("备份失败"));
        }
        return true;
    }

    private boolean handleClean(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        accountManager.cleanInvalidData(count -> {
            sender.sendMessage(messageManager.getMessage("admin.clean-success", "count", String.valueOf(count)));
            logAudit(sender.getName(), "clean", String.valueOf(count));
        });
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /sl info <玩家名>"));
            return true;
        }

        String targetName = args[1];
        accountManager.getAccountInfo(targetName, data -> {
            if (data == null) {
                sender.sendMessage(messageManager.getMessage("admin.info-not-found"));
                return;
            }
            sender.sendMessage(messageManager.getMessage("admin.info-header"));
            sender.sendMessage(messageManager.getMessage("admin.info-username", "name", data.username != null ? data.username : "未知"));
            sender.sendMessage(messageManager.getMessage("admin.info-registered", "time", accountManager.formatTime(data.registerTime)));
            sender.sendMessage(messageManager.getMessage("admin.info-register-ip", "ip", data.registerIp != null ? data.registerIp : "无记录"));
            sender.sendMessage(messageManager.getMessage("admin.info-last-login", "time", accountManager.formatTime(data.lastLoginTime)));
            sender.sendMessage(messageManager.getMessage("admin.info-last-ip", "ip", data.lastLoginIp != null ? data.lastLoginIp : "无记录"));
            sender.sendMessage(messageManager.getMessage("info.is-premium", "status", data.isPremium ? "是" : "否"));
            sender.sendMessage(messageManager.getMessage("info.premium-uuid", "uuid", data.premiumUuid != null ? data.premiumUuid : "无"));
        });
        return true;
    }

    private boolean handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        int[] stats = accountManager.getOnlineStats();
        sender.sendMessage(messageManager.getMessage("admin.stats-header"));
        sender.sendMessage(messageManager.getMessage("admin.stats-total", "total", String.valueOf(stats[0])));
        sender.sendMessage(messageManager.getMessage("admin.stats-logged-in", "logged", String.valueOf(stats[1])));
        sender.sendMessage(messageManager.getMessage("admin.stats-not-logged", "notlogged", String.valueOf(stats[2])));
        return true;
    }

    private boolean handleForceChangePwd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /sl forcechangepwd <玩家名>"));
            return true;
        }

        String targetName = args[1];
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            target = Bukkit.getOfflinePlayer(targetName);
        }

        UUID targetUuid = target.getUniqueId();
        accountManager.forceChangePassword(targetUuid, () -> {
            sender.sendMessage(messageManager.getMessage("admin.force-change-password", "player", targetName));
            logAudit(sender.getName(), "forcechangepwd", targetName);
        });
        return true;
    }

    private boolean handlePremium(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messageManager.getMessage("general.player-only"));
                return true;
            }
            if (!accountManager.isLoggedIn(player.getUniqueId())) {
                sender.sendMessage(messageManager.getMessage("login.not-logged"));
                return true;
            }
            accountManager.isPremiumAccountDb(player.getUniqueId(), (isPremium, data) -> {
                if (isPremium) {
                    player.sendMessage(messageManager.getMessage("premium.already-premium"));
                    return;
                }
                startPlayerPremiumLogin(player);
            });
            return true;
        }

        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        String targetName = args[1];
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            target = Bukkit.getOfflinePlayer(targetName);
        }

        UUID targetUuid = target.getUniqueId();
        accountManager.isPremiumAccountDb(targetUuid, (isPremium, data) -> {
            if (data == null) {
                sender.sendMessage(messageManager.getMessage("premium.account-not-found"));
                return;
            }
            if (isPremium) {
                sender.sendMessage(messageManager.getMessage("premium.already-premium"));
                return;
            }
            accountManager.setPremium(targetUuid, null, null, null, 0, success -> {
                if (success) {
                    sender.sendMessage(messageManager.getMessage("premium.set-success", "player", targetName));
                    logAudit(sender.getName(), "premium", targetName);
                } else {
                    sender.sendMessage(messageManager.getMessage("premium.account-not-found"));
                }
            });
        });
        return true;
    }

    private void startPlayerPremiumLogin(Player player) {
        UUID uuid = player.getUniqueId();
        formDialogManager.cleanupDialogNoUnlock(uuid);

        final boolean[] cancelled = {false};
        final String[] currentUserCode = {null};

        plugin.getPremiumAuthManager().startDeviceLogin(new com.pingfeng.startlogin.auth.PremiumAuthManager.DeviceLoginStartCallback() {
            @Override
            public void onDeviceCode(String userCode, String verificationUrl) {
                if (!player.isOnline() || cancelled[0]) return;
                currentUserCode[0] = userCode;
                formDialogManager.openPremiumVerifyForm(player, userCode, verificationUrl,
                        () -> {
                            cancelled[0] = true;
                            formDialogManager.cleanupDialog(uuid);
                            player.sendMessage(messageManager.getMessage("premium.cancelled"));
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
                formDialogManager.updatePremiumVerifyStatus(player, "验证成功，正在绑定...");
                handlePlayerPremiumSuccess(player, premiumUuid, username, mcToken, refreshToken, accessTokenExpires);
            }

            @Override
            public void onFailure(String error) {
                if (!player.isOnline() || cancelled[0]) return;
                formDialogManager.cleanupDialog(uuid);
                formDialogManager.openMessageDialog(player, "<red>正版验证失败：" + error,
                        () -> {});
            }
        });
    }

    private void handlePlayerPremiumSuccess(Player player, UUID premiumUuid, String username, String mcToken, String refreshToken, long accessTokenExpires) {
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress() != null ? player.getAddress().getHostString() : "unknown";

        accountManager.checkPremiumAccount(premiumUuid.toString(), (exists, existingData) -> {
            if (!player.isOnline()) return;

            if (exists) {
                if (!existingData.uuid.equals(uuid)) {
                    formDialogManager.cleanupDialog(uuid);
                    player.sendMessage(messageManager.getMessage("premium.already-bound"));
                    return;
                }
                accountManager.setPremium(uuid, premiumUuid.toString(), refreshToken, mcToken, accessTokenExpires, success -> {
                    if (!player.isOnline()) return;
                    if (success) {
                        formDialogManager.cleanupDialog(uuid);
                        player.sendMessage(messageManager.getMessage("premium.bound-success"));
                    }
                });
            } else {
                accountManager.setPremium(uuid, premiumUuid.toString(), refreshToken, mcToken, accessTokenExpires, success -> {
                    if (!player.isOnline()) return;
                    if (success) {
                        formDialogManager.cleanupDialog(uuid);
                        player.sendMessage(messageManager.getMessage("premium.bound-success"));
                    }
                });
            }
        });
    }

    private boolean handleUnpremium(CommandSender sender, String[] args) {
        if (!sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(messageManager.getMessage("general.no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /sl unpremium <玩家名>"));
            return true;
        }

        String targetName = args[1];
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            target = Bukkit.getOfflinePlayer(targetName);
        }

        UUID targetUuid = target.getUniqueId();
        accountManager.isPremiumAccountDb(targetUuid, (isPremium, data) -> {
            if (data == null) {
                sender.sendMessage(messageManager.getMessage("premium.account-not-found"));
                return;
            }
            if (!isPremium) {
                sender.sendMessage(messageManager.getMessage("premium.not-premium"));
                return;
            }
            accountManager.unsetPremium(targetUuid, success -> {
                if (success) {
                    sender.sendMessage(messageManager.getMessage("premium.unset-success", "player", targetName));
                    logAudit(sender.getName(), "unpremium", targetName);
                } else {
                    sender.sendMessage(messageManager.getMessage("premium.account-not-found"));
                }
            });
        });
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(messageManager.getMessage("help.header"));
        sender.sendMessage(messageManager.getMessage("help.player-title"));
        sender.sendMessage(messageManager.getMessage("help.login"));
        sender.sendMessage(messageManager.getMessage("help.register"));
        sender.sendMessage(messageManager.getMessage("help.changepwd"));
        sender.sendMessage(messageManager.getMessage("help.unregister"));
        sender.sendMessage(messageManager.getMessage("help.premium-self"));

        if (sender.hasPermission("startlogin.admin")) {
            sender.sendMessage(Component.empty());
            sender.sendMessage(messageManager.getMessage("help.admin-title"));
            sender.sendMessage(messageManager.getMessage("help.reset"));
            sender.sendMessage(messageManager.getMessage("help.forcechangepwd"));
            sender.sendMessage(messageManager.getMessage("help.premium"));
            sender.sendMessage(messageManager.getMessage("help.unpremium"));
            sender.sendMessage(messageManager.getMessage("help.info"));
            sender.sendMessage(messageManager.getMessage("help.stats"));
            sender.sendMessage(messageManager.getMessage("help.backup"));
            sender.sendMessage(messageManager.getMessage("help.clean"));
            sender.sendMessage(messageManager.getMessage("help.reload"));
            sender.sendMessage(messageManager.getMessage("help.migrate"));
        }

        sender.sendMessage(Component.empty());
        sender.sendMessage(messageManager.getMessage("help.footer"));
    }

    private void logAudit(String admin, String action, String target) {
        String message = switch (action) {
            case "reset" -> messageManager.getString("admin.audit-reset")
                    .replace("{admin}", admin)
                    .replace("{player}", target);
            case "clean" -> messageManager.getString("admin.audit-clean")
                    .replace("{admin}", admin);
            case "forcechangepwd" -> "[审计] 管理员 " + admin + " 强制玩家 " + target + " 修改密码";
            default -> "[审计] " + admin + " 执行了 " + action + " 操作";
        };
        plugin.getLogger().info(message);
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.add("login");
            completions.add("register");
            completions.add("changepwd");
            completions.add("unregister");
            if (sender.hasPermission("startlogin.admin")) {
                completions.add("reset");
                completions.add("migrate");
                completions.add("reload");
                completions.add("backup");
                completions.add("clean");
                completions.add("info");
                completions.add("stats");
                completions.add("forcechangepwd");
                completions.add("premium");
                completions.add("unpremium");
            }
        }

        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .toList();
    }
}
