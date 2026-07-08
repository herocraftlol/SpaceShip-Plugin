package com.spaceship.plugin.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.*;
import java.util.logging.Logger;

/**
 * Capture l'état complet d'une zone cuboïde (type + données de chaque bloc) et permet
 * de la restaurer à l'identique. Utilisé pour remettre la map dans son état d'origine
 * à chaque round reset et au début de chaque partie, façon WorldEdit/WorldGuard maison.
 *
 * Le snapshot est stocké dans un fichier binaire dédié (et non dans le config.yml) car
 * une map peut contenir des dizaines de milliers de blocs : un format YAML serait beaucoup
 * trop lent à écrire/lire et gonflerait démesurément le fichier de configuration.
 */
public class ArenaSnapshot {

    private final Logger logger;

    // Coin minimal du cuboïde et dimensions, utilisés pour retrouver chaque bloc à la restauration.
    private World world;
    private int minX, minY, minZ;
    private int sizeX, sizeY, sizeZ;

    // Une entrée par bloc, dans l'ordre x -> y -> z. Stocke le nom du matériau + sa blockdata brute.
    private String[] blockDataStrings;

    public ArenaSnapshot(Logger logger) {
        this.logger = logger;
    }

    public boolean isCaptured() {
        return blockDataStrings != null;
    }

    /**
     * Parcourt toute la zone donnée et enregistre l'état exact de chaque bloc en mémoire.
     * Cette opération est synchrone (l'API Bukkit de lecture de blocs doit être appelée
     * sur le thread principal) mais reste rapide : on ne lit que des données déjà chargées.
     */
    public void capture(CuboidRegion region) {
        Location c1 = region.getCorner1();
        Location c2 = region.getCorner2();
        this.world = c1.getWorld();

        this.minX = Math.min(c1.getBlockX(), c2.getBlockX());
        this.minY = Math.min(c1.getBlockY(), c2.getBlockY());
        this.minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());

        this.sizeX = maxX - minX + 1;
        this.sizeY = maxY - minY + 1;
        this.sizeZ = maxZ - minZ + 1;

        long total = (long) sizeX * sizeY * sizeZ;
        if (total > 2_000_000) {
            logger.warning("La zone de jeu SpaceShip est très grande (" + total
                    + " blocs) : la capture/restauration peut prendre plusieurs secondes.");
        }

        blockDataStrings = new String[sizeX * sizeY * sizeZ];

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    Block block = world.getBlockAt(minX + x, minY + y, minZ + z);
                    blockDataStrings[index(x, y, z)] = block.getBlockData().getAsString();
                }
            }
        }

        logger.info("Snapshot de la zone de jeu SpaceShip capturé (" + blockDataStrings.length + " blocs).");
    }

    /**
     * Restaure chaque bloc de la zone à l'état exact enregistré lors de la capture.
     * Utilise physics=false pour éviter les mises à jour en cascade (chute de sable,
     * propagation de redstone, etc.) pendant la restauration en masse.
     */
    public void restore() {
        if (!isCaptured()) {
            return;
        }
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    String dataString = blockDataStrings[index(x, y, z)];
                    Block block = world.getBlockAt(minX + x, minY + y, minZ + z);
                    BlockData data = Bukkit.createBlockData(dataString);
                    block.setBlockData(data, false);
                }
            }
        }
    }

    /**
     * Détermine si le bloc à la position donnée est encore exactement dans son état d'origine
     * (même type + même blockdata que lors de la capture). Si c'est le cas, il s'agit d'un bloc
     * "carte" qu'on ne doit pas laisser casser. Si le bloc a été modifié (posé par un joueur,
     * ou un bloc d'origine déjà cassé puis remplacé), il n'est plus protégé.
     */
    public boolean isUnmodifiedOriginalBlock(Block block) {
        if (!isCaptured() || world == null || !world.equals(block.getWorld())) return false;
        int x = block.getX() - minX;
        int y = block.getY() - minY;
        int z = block.getZ() - minZ;
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return false;
        }
        String original = blockDataStrings[index(x, y, z)];
        return original != null && original.equals(block.getBlockData().getAsString());
    }

    private int index(int x, int y, int z) {
        return (x * sizeY * sizeZ) + (y * sizeZ) + z;
    }

    // ---- Sauvegarde / chargement sur disque (fichier binaire dédié) ----

    public void saveToFile(File file) {
        if (!isCaptured()) return;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeUTF(world.getName());
            out.writeInt(minX);
            out.writeInt(minY);
            out.writeInt(minZ);
            out.writeInt(sizeX);
            out.writeInt(sizeY);
            out.writeInt(sizeZ);
            out.writeInt(blockDataStrings.length);
            for (String s : blockDataStrings) {
                out.writeUTF(s);
            }
        } catch (IOException e) {
            logger.severe("Impossible de sauvegarder le snapshot de la zone de jeu : " + e.getMessage());
        }
    }

    public boolean loadFromFile(File file) {
        if (!file.exists()) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            String worldName = in.readUTF();
            World loadedWorld = Bukkit.getWorld(worldName);
            if (loadedWorld == null) {
                logger.warning("Le monde '" + worldName + "' du snapshot SpaceShip n'existe pas (encore).");
                return false;
            }
            this.world = loadedWorld;
            this.minX = in.readInt();
            this.minY = in.readInt();
            this.minZ = in.readInt();
            this.sizeX = in.readInt();
            this.sizeY = in.readInt();
            this.sizeZ = in.readInt();
            int count = in.readInt();
            this.blockDataStrings = new String[count];
            for (int i = 0; i < count; i++) {
                blockDataStrings[i] = in.readUTF();
            }
            return true;
        } catch (IOException e) {
            logger.severe("Impossible de charger le snapshot de la zone de jeu : " + e.getMessage());
            return false;
        }
    }
}
