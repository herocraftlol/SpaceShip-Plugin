package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

/**
 * Gère les déplacements des joueurs :
 * - Immobilisation propre quand le joueur est gelé (après un point marqué)
 *   Le joueur peut toujours regarder autour de lui (yaw/pitch conservés).
 * - Mort automatique si le joueur sort de la zone de jeu
 */
public class PlayerMoveListener implements Listener {

    private final SpaceShipPlugin plugin;

    public PlayerMoveListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaManager().findArenaOf(player);

        if (gm == null) {
            return;
        }

        // FREEZE propre : bloquer tout déplacement du corps, autoriser la tête
        if (gm.isFrozen(player)) {
            Location from = event.getFrom();
            Location to = event.getTo();

            if (from.getBlockX() != to.getBlockX()
                    || from.getBlockY() != to.getBlockY()
                    || from.getBlockZ() != to.getBlockZ()) {
                // Conserver yaw/pitch du "to" pour que le joueur puisse regarder
                Location fixed = from.clone();
                fixed.setYaw(to.getYaw());
                fixed.setPitch(to.getPitch());
                event.setTo(fixed);
                // Annuler aussi la vélocité résiduelle (saut, knockback, etc.)
                player.setVelocity(new Vector(0, 0, 0));
            }
            return;
        }

        GameState state = gm.getState();

        // Ignorer les micro-mouvements (juste la tête)
        if (isSameBlock(event.getFrom(), event.getTo())) {
            return;
        }

        // Pendant PLAYING : vérifier si le joueur sort de la zone
        if (state == GameState.PLAYING) {
            if (!gm.getArena().isInGameZone(player.getLocation())) {
                player.setHealth(0);
            }
        }
    }

    private boolean isSameBlock(Location from, Location to) {
        return from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
