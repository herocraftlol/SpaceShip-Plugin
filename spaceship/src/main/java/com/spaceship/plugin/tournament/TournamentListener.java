package com.spaceship.plugin.tournament;

import com.spaceship.plugin.SpaceShipPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Gère le départ d'un joueur inscrit à un tournoi (retrait propre de son inscription
 * pendant la phase REGISTRATION). Les forfaits en plein match sont déjà gérés par le
 * flux normal SpaceShip (PlayerConnectionListener + GameManager#checkForfeit), puisque
 * les joueurs d'un match de tournoi sont de vrais joueurs de l'arène SpaceShip réservée.
 */
public class TournamentListener implements Listener {

    private final SpaceShipPlugin plugin;

    public TournamentListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getTournamentManager().handlePlayerQuit(player);
    }
}
