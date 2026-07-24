package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.KitManager;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Gère l'item "quitter le mode spectateur" (barrier) donné à tout joueur en mode
 * spectateur : un clic déclenche directement la sortie du mode spectateur, comme
 * raccourci visuel pour /ss unspectate. Empêche aussi de lâcher/déplacer cet item.
 */
public class SpectatorListener implements Listener {

    private final SpaceShipPlugin plugin;

    public SpectatorListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!KitManager.isSpectatorLeaveItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaManager().findSpectatingArenaOf(player);
        if (gm == null) {
            MessageUtil.send(player, "&cTu n'es spectateur d'aucune arène en ce moment.");
            return;
        }
        gm.removeSpectator(player);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (KitManager.isSpectatorLeaveItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (KitManager.isSpectatorLeaveItem(event.getCurrentItem()) || KitManager.isSpectatorLeaveItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }
}
