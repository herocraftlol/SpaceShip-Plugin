package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.KitManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Gère les restrictions sur les items du kit (lobby et jeu).
 * - Empêche de lâcher l'item de sélection d'équipe
 * - Empêche de déplacer l'item de sélection d'équipe dans l'inventaire
 * - Empêche de swapper les items (touche F)
 */
public class PlayerItemListener implements Listener {

    private final SpaceShipPlugin plugin;

    public PlayerItemListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        
        if (gm == null) return;
        
        ItemStack item = event.getItemDrop().getItemStack();
        
        // En lobby : empêcher de lâcher l'item de sélection d'équipe et l'item quitter
        if (gm.getState() == GameState.WAITING) {
            if (KitManager.isTeamSelectorItem(item) || KitManager.isForceStartItem(item) || KitManager.isLeaveItem(item)) {
                event.setCancelled(true);
            }
        }
        
        // En jeu : empêcher de lâcher les items du kit
        if (gm.getState() == GameState.PLAYING || gm.getState() == GameState.ROUND_RESET || 
            gm.getState() == GameState.COUNTDOWN) {
            if (isKitItem(item)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        
        if (gm == null) return;
        
        ItemStack item = event.getCurrentItem();
        if (item == null) return;
        
        // En lobby : empêcher de déplacer l'item de sélection d'équipe et l'item quitter
        if (gm.getState() == GameState.WAITING) {
            if (KitManager.isTeamSelectorItem(item) || KitManager.isForceStartItem(item) || KitManager.isLeaveItem(item)) {
                event.setCancelled(true);
            }
        }
        
        // En jeu : empêcher de déplacer les items du kit
        if (gm.getState() == GameState.PLAYING || gm.getState() == GameState.ROUND_RESET || 
            gm.getState() == GameState.COUNTDOWN) {
            if (isKitItem(item)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        
        if (gm == null) return;
        
        if (gm.getState() == GameState.WAITING || gm.getState() == GameState.PLAYING || 
            gm.getState() == GameState.ROUND_RESET || gm.getState() == GameState.COUNTDOWN) {
            event.setCancelled(true);
        }
    }

    private boolean isKitItem(ItemStack item) {
        if (item == null) return false;
        Material mat = item.getType();
        return mat == Material.IRON_SWORD || 
               mat == Material.IRON_PICKAXE || 
               mat == Material.GOLDEN_APPLE ||
               mat == Material.SMOOTH_SANDSTONE;
    }
}
