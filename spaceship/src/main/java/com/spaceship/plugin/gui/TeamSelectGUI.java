package com.spaceship.plugin.gui;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI ouvert au clic sur l'item de sélection d'équipe du lobby, qui permet à un joueur
 * de choisir explicitement l'équipe qu'il veut rejoindre (Noir ou Blanc), en tenant
 * compte des places encore disponibles dans chaque équipe.
 *
 * La limite de joueurs par équipe est calculée à partir du maximum de joueurs de
 * l'arène, réparti en 2 : une arène configurée en max 8 donne des équipes de 4 (4v4),
 * max 16 donne du 8v8, etc.
 */
public class TeamSelectGUI {

    /** Titre affiché dans la barre du coffre. */
    public static final String GUI_TITLE = ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Choisis ton équipe";

    private static final int GUI_SIZE = 27;

    /** Slot de l'icône permettant de rejoindre l'équipe Noir. */
    public static final int BLACK_SLOT = 11;

    /** Slot de l'icône permettant de rejoindre l'équipe Blanc. */
    public static final int WHITE_SLOT = 15;

    private final SpaceShipPlugin plugin;

    public TeamSelectGUI(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    /** Ouvre le GUI de sélection d'équipe pour le joueur donné. */
    public void open(Player player, GameManager gm) {
        player.openInventory(build(gm));
    }

    /** Construit l'inventaire à jour (effectifs/places disponibles) pour l'arène donnée. */
    public Inventory build(GameManager gm) {
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);

        ItemStack filler = buildFiller();
        for (int i = 0; i < GUI_SIZE; i++) {
            inv.setItem(i, filler);
        }

        int perTeamLimit = teamLimit(gm);
        inv.setItem(BLACK_SLOT, buildTeamItem(gm, Team.BLACK, perTeamLimit));
        inv.setItem(WHITE_SLOT, buildTeamItem(gm, Team.WHITE, perTeamLimit));

        return inv;
    }

    /**
     * Limite de joueurs par équipe pour l'arène donnée : la moitié du maximum de joueurs
     * de l'arène, avec un minimum de 1.
     */
    public static int teamLimit(GameManager gm) {
        return Math.max(1, gm.getMaxPlayers() / 2);
    }

    /**
     * Construit l'icône permettant de rejoindre une équipe donnée : laine colorée si des
     * places sont disponibles, vitre teintée (grisée visuellement) si l'équipe est complète.
     */
    private ItemStack buildTeamItem(GameManager gm, Team team, int limit) {
        int current = gm.getPlayerCountForTeam(team);
        boolean full = current >= limit;

        Material mat = team == Team.BLACK
                ? (full ? Material.BLACK_STAINED_GLASS_PANE : Material.BLACK_WOOL)
                : (full ? Material.WHITE_STAINED_GLASS_PANE : Material.WHITE_WOOL);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(team.getColoredName() + ChatColor.WHITE + " - Équipe " + team.getDisplayName());

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.GRAY + "Joueurs : " + (full ? ChatColor.RED : ChatColor.GREEN) + current
                + ChatColor.DARK_GRAY + "/" + ChatColor.GRAY + limit);
        lore.add("");
        if (full) {
            lore.add(ChatColor.RED + "✖ Équipe complète");
        } else {
            lore.add(ChatColor.YELLOW + "▶ Clique pour rejoindre cette équipe !");
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Case de remplissage pour les slots vides. */
    private ItemStack buildFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }
}
