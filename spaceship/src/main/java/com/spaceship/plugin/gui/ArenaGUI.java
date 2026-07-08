package com.spaceship.plugin.gui;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * GUI d'inventaire permettant au joueur de voir toutes les arènes disponibles
 * et d'en rejoindre une (ou une aléatoire via un bouton dédié).
 *
 * Structure :
 *   - Lignes 1 à 5 : une icône par arène (max 45 arènes affichées)
 *   - Ligne 6 entière : bouton "Rejoindre une arène aléatoire" (9 slots fusionnés visuellement)
 *
 * Taille du GUI = 54 slots (6 rangées × 9 colonnes).
 */
public class ArenaGUI {

    /** Titre affiché dans la barre du coffre. */
    public static final String GUI_TITLE = ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "⚔ Arènes disponibles";

    /** Nombre total de slots du GUI (6 rangées). */
    private static final int GUI_SIZE = 54;

    /** Index du premier slot de la dernière ligne (ligne 6 = index 45 à 53). */
    private static final int RANDOM_ROW_START = 45;

    private final SpaceShipPlugin plugin;

    public ArenaGUI(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Ouvre le GUI pour le joueur donné.
     */
    public void open(Player player) {
        Inventory inv = buildInventory();
        player.openInventory(inv);
    }

    /**
     * Construit et retourne l'inventaire rempli.
     */
    public Inventory buildInventory() {
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);

        Collection<GameManager> allArenas = plugin.getArenaManager().getAll();

        int slot = 0;
        for (GameManager gm : allArenas) {
            if (slot >= RANDOM_ROW_START) break; // Max 45 arènes

            ItemStack item = buildArenaItem(gm, gm.getMaxPlayers());
            inv.setItem(slot, item);
            slot++;
        }

        // Remplir les slots vides des lignes 1-5 avec du verre noir
        ItemStack filler = buildFiller();
        for (int i = slot; i < RANDOM_ROW_START; i++) {
            inv.setItem(i, filler);
        }

        // Ligne 6 entière : bouton arène aléatoire
        ItemStack randomBtn = buildRandomButton(allArenas);
        for (int i = RANDOM_ROW_START; i < GUI_SIZE; i++) {
            inv.setItem(i, randomBtn);
        }

        return inv;
    }

    /**
     * Crée l'icône représentant une arène.
     * - Arène jouable (WAITING/COUNTDOWN) → émeraude verte
     * - Arène en cours (PLAYING) → tête de joueur rouge (barrière)
     * - Arène non configurée → caillou gris
     */
    private ItemStack buildArenaItem(GameManager gm, int maxPlayers) {
        String name = gm.getName();
        GameState state = gm.getState();
        int current = gm.getPlayerCount();

        Material mat;
        String displayName;
        String statusLine;
        ChatColor statusColor;

        if (!gm.getArena().isFullyConfigured() || state == GameState.NOT_CONFIGURED) {
            mat = Material.GRAY_STAINED_GLASS_PANE;
            displayName = ChatColor.GRAY + "" + ChatColor.BOLD + "✖ " + capitalize(name);
            statusLine = ChatColor.GRAY + "Non configurée";
            statusColor = ChatColor.GRAY;
        } else if (state == GameState.PLAYING || state == GameState.ROUND_RESET) {
            mat = Material.RED_STAINED_GLASS_PANE;
            displayName = ChatColor.RED + "" + ChatColor.BOLD + "⚔ " + capitalize(name);
            statusLine = ChatColor.RED + "Partie en cours";
            statusColor = ChatColor.RED;
        } else if (current >= maxPlayers) {
            mat = Material.ORANGE_STAINED_GLASS_PANE;
            displayName = ChatColor.GOLD + "" + ChatColor.BOLD + "⚠ " + capitalize(name);
            statusLine = ChatColor.GOLD + "Pleine";
            statusColor = ChatColor.GOLD;
        } else {
            mat = Material.LIME_STAINED_GLASS_PANE;
            displayName = ChatColor.GREEN + "" + ChatColor.BOLD + "✔ " + capitalize(name);
            statusLine = ChatColor.GREEN + "Disponible";
            statusColor = ChatColor.GREEN;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(displayName);

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.GRAY + "Joueurs : " + statusColor + current + ChatColor.DARK_GRAY + "/" + ChatColor.GRAY + maxPlayers);
        lore.add(ChatColor.GRAY + "Statut  : " + statusLine);
        lore.add("");

        boolean joinable = gm.getArena().isFullyConfigured()
                && state != GameState.PLAYING
                && state != GameState.ROUND_RESET
                && state != GameState.ENDING
                && state != GameState.NOT_CONFIGURED
                && current < maxPlayers;

        if (joinable) {
            lore.add(ChatColor.YELLOW + "▶ Cliquez pour rejoindre !");
        } else {
            lore.add(ChatColor.RED + "✖ Indisponible");
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Bouton de la dernière ligne : rejoindre une arène aléatoire (priorité aux arènes avec joueurs).
     */
    private ItemStack buildRandomButton(Collection<GameManager> allArenas) {
        long joinableCount = allArenas.stream()
                .filter(gm -> gm.getArena().isFullyConfigured()
                        && gm.getState() != GameState.PLAYING
                        && gm.getState() != GameState.ROUND_RESET
                        && gm.getState() != GameState.ENDING
                        && gm.getState() != GameState.NOT_CONFIGURED
                        && gm.getPlayerCount() < gm.getMaxPlayers())
                .count();

        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "✦ Rejoindre une arène aléatoire");

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.GRAY + "Vous serez envoyé dans une arène");
        lore.add(ChatColor.GRAY + "disponible, en priorité celles");
        lore.add(ChatColor.GRAY + "qui ont déjà des joueurs.");
        lore.add("");
        if (joinableCount > 0) {
            lore.add(ChatColor.GREEN + "" + joinableCount + " arène(s) disponible(s)");
            lore.add("");
            lore.add(ChatColor.YELLOW + "▶ Cliquez pour jouer !");
        } else {
            lore.add(ChatColor.RED + "Aucune arène disponible pour le moment.");
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Case de remplissage pour les slots vides (lignes 1-5).
     */
    private ItemStack buildFiller() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Met la première lettre en majuscule.
     */
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Retourne le nom de l'arène à partir de son slot dans le GUI.
     * Retourne null si le slot est en dehors de la zone des arènes.
     */
    public String getArenaNameAt(int slot) {
        if (slot < 0 || slot >= RANDOM_ROW_START) return null;
        List<GameManager> list = new ArrayList<>(plugin.getArenaManager().getAll());
        if (slot >= list.size()) return null;
        return list.get(slot).getName();
    }

    /**
     * Retourne true si le slot cliqué correspond au bouton "aléatoire" (ligne 6).
     */
    public static boolean isRandomButton(int slot) {
        return slot >= RANDOM_ROW_START && slot < GUI_SIZE;
    }
}
