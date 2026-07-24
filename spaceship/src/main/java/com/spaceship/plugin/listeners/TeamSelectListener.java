package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.KitManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Gère les clics sur l'item de sélection d'équipe (terracotta coloré) au lobby.
 * Ouvre le GUI de sélection d'équipe (voir {@link com.spaceship.plugin.gui.TeamSelectGUI}),
 * qui permet de choisir explicitement Noir ou Blanc en tenant compte des places
 * encore disponibles dans chaque équipe.
 */
public class TeamSelectListener implements Listener {

    private final SpaceShipPlugin plugin;

    public TeamSelectListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!KitManager.isTeamSelectorItem(item)) {
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK
                || event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
        }

        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        if (gm == null) {
            return;
        }

        if (gm.getState() != GameState.WAITING) {
            player.sendMessage(ChatColor.RED + "Tu ne peux pas changer d'équipe pendant la partie !");
            return;
        }

        plugin.getTeamSelectGUI().open(player, gm);
    }
}
