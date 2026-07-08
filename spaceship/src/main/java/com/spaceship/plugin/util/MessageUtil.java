package com.spaceship.plugin.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * Petite classe utilitaire pour traduire les codes couleurs (&) et envoyer des messages formatés.
 */
public final class MessageUtil {

    private MessageUtil() {
    }

    public static String format(String raw) {
        if (raw == null) return "";
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public static void send(CommandSender target, String raw) {
        if (raw == null || raw.isEmpty()) return;
        target.sendMessage(format(raw));
    }
}
