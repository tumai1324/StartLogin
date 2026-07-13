package com.pingfeng.startlogin.listener;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.ui.FormDialogManager;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class DialogListener implements Listener {

    private final StartLogin plugin;
    private final FormDialogManager formDialogManager;

    public DialogListener(StartLogin plugin, FormDialogManager formDialogManager) {
        this.plugin = plugin;
        this.formDialogManager = formDialogManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCustomClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection gameConnection)) {
            return;
        }

        Player player = gameConnection.getPlayer();
        if (player == null) {
            return;
        }

        if (!formDialogManager.hasDialogSession(player.getUniqueId())) {
            return;
        }

        Key key = event.getIdentifier();
        if (key != null) {
            DialogResponseView responseView = event.getDialogResponseView();
            formDialogManager.handleDialogClick(player, key, responseView);
        }
    }
}
