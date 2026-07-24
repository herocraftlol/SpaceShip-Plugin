package com.spaceship.plugin.gui;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.Team;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Écoute les clics dans le GUI de sélection d'équipe et applique le changement
 * d'équipe demandé, en revérifiant à chaque clic que la partie est toujours en
 * lobby d'attente (ou en compte à rebours) et que l'équipe visée a encore de la place.
 */
public class TeamSelectGUIListener implements Listener {

    private final SpaceShipPlugin plugin;

    public TeamSelectGUIListener(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!TeamSelectGUI.GUI_TITLE.equals(event.getView().getTitle())) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        Team targetTeam;
        if (slot == TeamSelectGUI.BLACK_SLOT) {
            targetTeam = Team.BLACK;
        } else if (slot == TeamSelectGUI.WHITE_SLOT) {
            targetTeam = Team.WHITE;
        } else {
            return;
        }

        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        if (gm == null) {
            player.closeInventory();
            return;
        }

        // La partie a pu démarrer entre l'ouverture du GUI et ce clic : on revérifie.
        // Le changement d'équipe reste autorisé pendant WAITING uniquement.
        if (gm.getState() != GameState.WAITING) {
            MessageUtil.send(player, "&cTu ne peux plus changer d'équipe maintenant.");
            player.closeInventory();
            return;
        }

        Team currentTeam = gm.getTeam(player);
        if (currentTeam == targetTeam) {
            MessageUtil.send(player, "&eTu es déjà dans l'équipe " + targetTeam.getColoredName() + "&e !");
            return;
        }

        int limit = TeamSelectGUI.teamLimit(gm);
        int targetCount = gm.getPlayerCountForTeam(targetTeam);
        if (targetCount >= limit) {
            MessageUtil.send(player, "&cL'équipe " + targetTeam.getColoredName() + "&c est déjà complète ("
                    + targetCount + "/" + limit + ") !");
            player.openInventory(plugin.getTeamSelectGUI().build(gm));
            return;
        }

        if (gm.changePlayerTeam(player, targetTeam)) {
            MessageUtil.send(player, "&aTu as rejoint l'équipe " + targetTeam.getColoredName() + "&a !");
            player.closeInventory();
        } else {
            MessageUtil.send(player, "&cImpossible de changer d'équipe !");
            player.closeInventory();
        }
    }
}
