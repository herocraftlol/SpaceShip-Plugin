package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.Arena;
import com.spaceship.plugin.game.ArenaSnapshot;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Protège la zone de jeu façon WorldGuard maison, pour chaque arène configurée :
 * - Les blocs déjà présents lors de la configuration (capturés dans le snapshot) ne peuvent
 *   jamais être cassés tant qu'ils sont dans leur état d'origine.
 * - Les joueurs peuvent poser des blocs librement dans la zone de jeu (aucune restriction
 *   n'est appliquée à BlockPlaceEvent).
 * - Les blocs posés par un joueur peuvent être cassés normalement (ils ne correspondent
 *   plus à l'état d'origine du snapshot).
 * - En dehors de toute zone de jeu (ou si aucune zone n'est configurée), aucune restriction.
 */
public class ArenaProtectionListener implements Listener {

    private final SpaceShipPlugin plugin;

    public ArenaProtectionListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // On cherche, parmi toutes les arènes actives, celle dont la zone de jeu contient ce bloc.
        // On se base sur la position du bloc plutôt que sur le statut "en partie" du joueur,
        // pour rester correct même dans des cas limites (spectateur, tiers proche de la zone).
        for (GameManager gameManager : plugin.getArenaManager().getAll()) {
            GameState state = gameManager.getState();
            if (state != GameState.PLAYING && state != GameState.ROUND_RESET) {
                continue;
            }

            Arena arena = gameManager.getArena();
            if (!arena.isInGameZone(event.getBlock().getLocation())) {
                continue;
            }

            ArenaSnapshot snapshot = gameManager.getArenaSnapshot();
            if (snapshot.isUnmodifiedOriginalBlock(event.getBlock())) {
                event.setCancelled(true);
            }
            return;
        }
    }
}
