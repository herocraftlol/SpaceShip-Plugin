package com.spaceship.plugin.game;

import org.bukkit.ChatColor;
import org.bukkit.Color;

/**
 * Les deux équipes possibles dans une partie de SpaceShip : Noir contre Blanc.
 *
 * Note : le texte de chat "noir" pur (ChatColor.BLACK) est quasiment invisible sur le
 * fond noir du chat Minecraft. On utilise donc ChatColor.DARK_GRAY pour l'affichage
 * textuel (nom coloré, tab list, scoreboard) de l'équipe BLACK, tout en gardant du vrai
 * noir pour l'armure/les blocs/l'item de sélection d'équipe.
 */
public enum Team {
    BLACK(ChatColor.DARK_GRAY, "Noir", Color.BLACK),
    WHITE(ChatColor.WHITE, "Blanc", Color.WHITE);

    private final ChatColor color;
    private final String displayName;
    private final Color armorColor;

    Team(ChatColor color, String displayName, Color armorColor) {
        this.color = color;
        this.displayName = displayName;
        this.armorColor = armorColor;
    }

    public ChatColor getColor() {
        return color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Color getArmorColor() {
        return armorColor;
    }

    public String getColoredName() {
        return color + displayName + ChatColor.RESET;
    }

    /**
     * Renvoie l'équipe opposée.
     */
    public Team opponent() {
        return this == BLACK ? WHITE : BLACK;
    }
}
