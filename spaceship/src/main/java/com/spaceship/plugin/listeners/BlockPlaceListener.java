package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.Team;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Empêche de poser des blocs dans les zones de capture et les zones de spawn.
 */
public class BlockPlaceListener implements Listener {

    private final SpaceShipPlugin plugin;

    public BlockPlaceListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        
        if (gm == null) return;
        
        // Vérifier uniquement pendant la partie (PLAYING ou ROUND_RESET)
        if (gm.getState() != GameState.PLAYING && gm.getState() != GameState.ROUND_RESET) {
            return;
        }
        
        Location blockLoc = event.getBlock().getLocation();
        int n = gm.getArena().getBasesPerSide();

        // Vérifier si le bloc est placé dans un des buts (zones de capture) de chaque
        // équipe, pour chaque Base configurée.
        for (Team team : Team.values()) {
            for (int k = 1; k <= n; k++) {
                if (gm.getArena().isInBaseGoal(team, k, blockLoc)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // Vérifier si le bloc est placé dans une zone de spawn (Mid + toutes les Bases,
        // pour les deux équipes, tous leurs spawns).
        // Zone de spawn: X ±0, Y ±1 (donc 2 blocs de hauteur), Z ±0
        for (Team team : Team.values()) {
            for (Location spawn : gm.getArena().getMidSpawns(team)) {
                if (isInSpawnZone(blockLoc, spawn)) {
                    event.setCancelled(true);
                    return;
                }
            }
            for (int k = 1; k <= n; k++) {
                for (Location spawn : gm.getArena().getBaseSpawns(team, k)) {
                    if (isInSpawnZone(blockLoc, spawn)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Vérifie si une location est dans la zone de spawn (X±0, Y±1, Z±0)
     */
    private boolean isInSpawnZone(Location blockLoc, Location spawnLoc) {
        if (spawnLoc == null || blockLoc.getWorld() == null || !blockLoc.getWorld().equals(spawnLoc.getWorld())) {
            return false;
        }
        
        int dx = Math.abs(blockLoc.getBlockX() - spawnLoc.getBlockX());
        int dy = Math.abs(blockLoc.getBlockY() - spawnLoc.getBlockY());
        int dz = Math.abs(blockLoc.getBlockZ() - spawnLoc.getBlockZ());
        
        // X et Z doivent être exactement le même bloc, Y peut être spawnY ou spawnY+1
        return dx <= 0 && dy <= 1 && dz <= 0;
    }
}
