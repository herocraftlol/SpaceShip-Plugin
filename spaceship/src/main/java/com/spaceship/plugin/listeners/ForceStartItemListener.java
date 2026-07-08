package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.KitManager;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Détecte l'utilisation du diamant "forcer le démarrage" donné aux admins dans le lobby.
 * Un clic (en main ou un clic dans l'inventaire) déclenche directement forceStart(),
 * comme un raccourci visuel pour /ss start.
 */
public class ForceStartItemListener implements Listener {

    private final SpaceShipPlugin plugin;

    public ForceStartItemListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!KitManager.isForceStartItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        attemptForceStart(event.getPlayer());
    }

    /**
     * Empêche aussi de déplacer/jeter l'item via l'inventaire pour éviter tout contournement
     * ou perte accidentelle de l'item (il n'est pas censé être manipulé, juste cliqué en main).
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (KitManager.isForceStartItem(event.getCurrentItem()) || KitManager.isForceStartItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    private void attemptForceStart(Player player) {
        if (!player.hasPermission("spaceship.admin")) {
            return;
        }
        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        if (gm == null) {
            MessageUtil.send(player, "&cTu n'es dans aucune arène en ce moment.");
            return;
        }
        if (!gm.getArena().isFullyConfigured()) {
            MessageUtil.send(player, "&cLa map n'est pas complètement configurée.");
            return;
        }
        boolean started = gm.forceStart();
        MessageUtil.send(player, started
                ? "&aPartie démarrée de force."
                : "&cImpossible de démarrer : il faut au moins un joueur dans chaque équipe.");
    }
}
