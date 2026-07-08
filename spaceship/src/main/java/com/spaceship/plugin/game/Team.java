package com.spaceship.plugin.game;

import org.bukkit.ChatColor;
import org.bukkit.Color;

/**
 * Les deux équipes possibles dans une partie de SpaceShip.
 */
public enum Team {
    RED(ChatColor.RED, "Rouge", Color.RED),
    BLUE(ChatColor.BLUE, "Bleu", Color.BLUE);

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
        return this == RED ? BLUE : RED;
    }
}
