package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.KitManager;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Détecte l'utilisation de l'item "quitter la partie" (barrier block) donné à tous
 * les joueurs dans le slot 8 du lobby d'attente.
 * Un clic déclenche directement /ss leave, comme raccourci visuel.
 */
public class LeaveItemListener implements Listener {

    private final SpaceShipPlugin plugin;

    public LeaveItemListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!KitManager.isLeaveItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        if (gm == null) {
            MessageUtil.send(player, "&cTu n'es dans aucune arène en ce moment.");
            return;
        }

        // L'item n'est disponible qu'en lobby ; on vérifie l'état par sécurité
        if (gm.getState() != GameState.WAITING && gm.getState() != GameState.COUNTDOWN) {
            MessageUtil.send(player, "&cTu ne peux pas quitter la partie en ce moment.");
            return;
        }

        gm.removePlayer(player);
    }
}
