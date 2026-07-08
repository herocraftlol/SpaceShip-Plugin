package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.KitManager;
import com.spaceship.plugin.game.Team;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Dans un minijeu comme SpaceShip, mourir (peu importe la cause : coup d'épée, chute,
 * lave, vide, noyade...) ne doit pas sortir le joueur de la partie : on le fait
 * respawn directement au spawn de son équipe, comme sur les serveurs type Hypixel.
 * On lui redonne aussi une pomme dorée fraîche à chaque mort.
 */
public class PlayerDamageListener implements Listener {

    private final SpaceShipPlugin plugin;

    public PlayerDamageListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isActiveGameState(GameState state) {
        return state == GameState.PLAYING || state == GameState.ROUND_RESET;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GameManager gameManager = plugin.getArenaManager().findArenaOf(player);

        if (gameManager == null || !isActiveGameState(gameManager.getState())) {
            return;
        }

        // On vide les drops pour ne pas perdre l'inventaire entre les morts (comportement minijeu).
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    /**
     * Se déclenche au moment précis où le joueur respawn (après l'écran de mort, ou
     * immédiatement si doImmediateRespawn est activé). On y fixe directement la
     * position de respawn pour éviter un aller-retour visible au spawn du monde,
     * et on redonne une pomme dorée fraîche.
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        GameManager gameManager = plugin.getArenaManager().findArenaOf(player);

        if (gameManager == null || !isActiveGameState(gameManager.getState())) {
            return;
        }

        Team team = gameManager.getTeam(player);
        Location spawn = gameManager.getCurrentSpawnFor(team);
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }

        // setHealth/setFoodLevel/inventaire doivent être appliqués au tick suivant : juste après
        // le respawn, certaines valeurs (santé, faim, inventaire) ne sont pas encore stabilisées.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.setHealth(20);
                player.setFoodLevel(20);
                KitManager.regiveGoldenApple(player);
            }
        });
    }
}
