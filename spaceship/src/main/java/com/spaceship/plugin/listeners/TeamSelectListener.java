package com.spaceship.plugin.listeners;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.KitManager;
import com.spaceship.plugin.game.Team;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Gère les clics sur l'item de sélection d'équipe (terracotta coloré) au lobby.
 * Permet aux joueurs de changer d'équipe en cliquant sur le terracotta.
 */
public class TeamSelectListener implements Listener {

    private static final int MAX_PLAYERS_PER_TEAM = 8;
    private final SpaceShipPlugin plugin;

    public TeamSelectListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Vérifier si l'item cliqué est l'item de sélection d'équipe
        if (!KitManager.isTeamSelectorItem(item)) {
            return;
        }

        // Vérifier si le joueur est dans une arène
        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        if (gm == null) {
            return;
        }

        // Empêcher le clic droit de consommer l'item
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
        }

        // Ne permettre le changement d'équipe que pendant l'état WAITING (lobby)
        if (gm.getState() != GameState.WAITING) {
            player.sendMessage(ChatColor.RED + "Vous ne pouvez pas changer d'équipe pendant la partie !");
            return;
        }

        // Récupérer l'équipe sélectionnée par l'item
        Team targetTeam = KitManager.getTeamFromSelectorItem(item);
        if (targetTeam == null) {
            return;
        }

        // Vérifier si le joueur est déjà dans cette équipe
        Team currentTeam = gm.getTeam(player);
        if (currentTeam == targetTeam) {
            // Déjà dans cette équipe, informer le joueur
            player.sendMessage(ChatColor.YELLOW + "Vous êtes déjà dans l'équipe " + targetTeam.getColoredName() + ChatColor.YELLOW + " !");
            return;
        }

        // Vérifier si l'équipe cible n'est pas pleine (max 8 par équipe)
        int targetTeamCount = gm.getPlayerCountForTeam(targetTeam);
        if (targetTeamCount >= MAX_PLAYERS_PER_TEAM) {
            player.sendMessage(ChatColor.RED + "L'équipe " + targetTeam.getColoredName() + ChatColor.RED + " est pleine (8/8) !");
            return;
        }

        // Vérifier si l'équipe actuelle ne va pas être vide (si c'est la dernière personne)
        int currentTeamCount = gm.getPlayerCountForTeam(currentTeam);
        if (currentTeamCount <= 1) {
            // Vérifier si l'autre équipe n'est pas aussi pleine
            int otherTeamCount = gm.getPlayerCountForTeam(targetTeam);
            if (otherTeamCount >= MAX_PLAYERS_PER_TEAM) {
                player.sendMessage(ChatColor.RED + "Impossible de quitter votre équipe : l'autre équipe est pleine et vous êtes le dernier !");
                return;
            }
            // Si l'autre équipe n'est pas pleine, on peut quand même changer
        }

        // Changer l'équipe
        if (gm.changePlayerTeam(player, targetTeam)) {
            player.sendMessage(ChatColor.GREEN + "Vous avez rejoint l'équipe " + targetTeam.getColoredName() + ChatColor.GREEN + " !");
        } else {
            player.sendMessage(ChatColor.RED + "Impossible de changer d'équipe !");
        }
    }
}
