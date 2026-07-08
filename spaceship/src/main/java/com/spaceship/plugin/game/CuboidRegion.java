package com.spaceship.plugin.game;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Représente une zone cuboïde simple définie par deux coins.
 * Utilisée pour les zones de capture.
 */
public class CuboidRegion {

    private final Location corner1;
    private final Location corner2;

    public CuboidRegion(Location corner1, Location corner2) {
        this.corner1 = corner1;
        this.corner2 = corner2;
    }

    public Location getCorner1() {
        return corner1;
    }

    public Location getCorner2() {
        return corner2;
    }

    /**
     * Vérifie si une localisation se trouve dans la zone (même monde + dans les bornes XYZ).
     *
     * Important : la comparaison se fait en coordonnées de BLOC entières (getBlockX/Y/Z),
     * pas en coordonnées décimales exactes. Sans ça, si pos1 et pos2 sont posés à des endroits
     * légèrement différents dans un même bloc (ex: x=120.7 puis x=120.3), la zone calculée en
     * décimales devient plus étroite qu'un bloc complet et exclut une partie du bloc visé —
     * ce qui rend la zone de capture peu fiable, surtout pour les petites zones (1 bloc).
     */
    public boolean contains(Location loc) {
        World world = corner1.getWorld();
        if (world == null || loc.getWorld() == null || !world.equals(loc.getWorld())) {
            return false;
        }

        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int minY = Math.min(corner1.getBlockY(), corner2.getBlockY());
        int maxY = Math.max(corner1.getBlockY(), corner2.getBlockY());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());

        int blockX = loc.getBlockX();
        int blockY = loc.getBlockY();
        int blockZ = loc.getBlockZ();

        return blockX >= minX && blockX <= maxX
                && blockY >= minY && blockY <= maxY
                && blockZ >= minZ && blockZ <= maxZ;
    }
}
