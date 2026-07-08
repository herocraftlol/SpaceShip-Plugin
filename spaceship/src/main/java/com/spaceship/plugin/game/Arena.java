package com.spaceship.plugin.game;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stocke tous les points importants de la map SpaceShip configurés par un admin.
 *
 * Contrairement à HikaBrain (une seule zone de capture par équipe), SpaceShip a
 * plusieurs zones alignées le long d'un "vaisseau" : une zone Mid neutre au centre,
 * puis Base1, Base2 (et Base3/Base4 selon la taille choisie) de chaque côté, de plus
 * en plus profondes dans le territoire de chaque équipe.
 *
 * Exemple à 5 zones (zoneCount=5, donc 2 bases par équipe) :
 *   Base2_Rouge | Base1_Rouge | Mid | Base1_Bleu | Base2_Bleu
 *
 * Chaque zone Base<k> d'une équipe possède :
 * - une ou plusieurs positions de spawn (utilisées par l'équipe ADVERSE quand elle a
 *   percé jusqu'à cette zone, voir GameManager)
 * - une zone de capture ("but") : l'endroit à atteindre pour percer cette zone.
 *
 * La zone Mid possède uniquement des positions de spawn (une liste par équipe, pas de
 * but : le centre du vaisseau est neutre, on n'y marque jamais directement).
 */
public class Arena {

    private Location lobbySpawn;

    private CuboidRegion gameZone;

    /**
     * Nombre total de zones du vaisseau : 5, 7 ou 9. Détermine le nombre de bases par
     * équipe (basesPerSide = (zoneCount - 1) / 2).
     */
    private int zoneCount = 5;

    // Spawns du centre neutre, par équipe (utilisés au tout début de la partie, et
    // par l'équipe qui défend tant qu'elle n'a pas été percée).
    private final Map<Team, List<Location>> midSpawns = new EnumMap<>(Team.class);

    // Spawns des bases, par équipe puis par index de base (1..4).
    private final Map<Team, Map<Integer, List<Location>>> baseSpawns = new EnumMap<>(Team.class);

    // Buts (zones de capture) des bases, par équipe puis par index de base (1..4).
    private final Map<Team, Map<Integer, CuboidRegion>> baseGoals = new EnumMap<>(Team.class);

    private int maxPlayers = -1;

    public Arena() {
        midSpawns.put(Team.RED, new ArrayList<>());
        midSpawns.put(Team.BLUE, new ArrayList<>());
        baseSpawns.put(Team.RED, new HashMap<>());
        baseSpawns.put(Team.BLUE, new HashMap<>());
        baseGoals.put(Team.RED, new HashMap<>());
        baseGoals.put(Team.BLUE, new HashMap<>());
    }

    // ================= ZONE COUNT / N =================

    public int getZoneCount() {
        return zoneCount;
    }

    /**
     * Définit le nombre total de zones du vaisseau. N'accepte que 5, 7 ou 9.
     * Renvoie false si la valeur est invalide.
     */
    public boolean setZoneCount(int zoneCount) {
        if (zoneCount != 5 && zoneCount != 7 && zoneCount != 9) {
            return false;
        }
        this.zoneCount = zoneCount;
        return true;
    }

    /**
     * Nombre de bases par équipe (2, 3 ou 4 selon zoneCount = 5, 7 ou 9).
     */
    public int getBasesPerSide() {
        return (zoneCount - 1) / 2;
    }

    // ================= DIVERS =================

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers <= 0 ? -1 : maxPlayers;
    }

    public CuboidRegion getGameZone() {
        return gameZone;
    }

    public void setGameZone(CuboidRegion gameZone) {
        this.gameZone = gameZone;
    }

    public boolean isInGameZone(Location loc) {
        return gameZone != null && gameZone.contains(loc);
    }

    public Location getLobbySpawn() {
        return lobbySpawn;
    }

    public void setLobbySpawn(Location lobbySpawn) {
        this.lobbySpawn = lobbySpawn;
    }

    /**
     * Une arène SpaceShip est complètement configurée quand :
     * - le lobby est défini
     * - chaque équipe a au moins un spawn au Mid
     * - chaque équipe a, pour CHAQUE base de 1 à basesPerSide, au moins un spawn ET un but défini
     *
     * (La gameZone reste optionnelle, comme pour HikaBrain : juste pour la protection/restauration.)
     */
    public boolean isFullyConfigured() {
        if (lobbySpawn == null) return false;
        if (midSpawns.get(Team.RED).isEmpty() || midSpawns.get(Team.BLUE).isEmpty()) return false;

        int n = getBasesPerSide();
        for (Team team : Team.values()) {
            for (int k = 1; k <= n; k++) {
                if (getBaseSpawnCount(team, k) == 0) return false;
                if (getBaseGoal(team, k) == null) return false;
            }
        }
        return true;
    }

    // ================= SPAWNS : MID =================

    public Location getMidSpawn(Team team) {
        List<Location> spawns = midSpawns.get(team);
        if (spawns == null || spawns.isEmpty()) return null;
        if (spawns.size() == 1) return spawns.get(0);
        return spawns.get(ThreadLocalRandom.current().nextInt(spawns.size()));
    }

    public List<Location> getMidSpawns(Team team) {
        return Collections.unmodifiableList(midSpawns.get(team));
    }

    public int getMidSpawnCount(Team team) {
        return midSpawns.get(team).size();
    }

    public boolean setMidSpawn(Team team, int index, Location loc) {
        return setInList(midSpawns.get(team), index, loc);
    }

    public boolean removeMidSpawn(Team team, int index) {
        return removeFromList(midSpawns.get(team), index);
    }

    // ================= SPAWNS : BASES =================

    public Location getBaseSpawn(Team team, int baseIndex) {
        List<Location> spawns = baseSpawns.get(team).get(baseIndex);
        if (spawns == null || spawns.isEmpty()) return null;
        if (spawns.size() == 1) return spawns.get(0);
        return spawns.get(ThreadLocalRandom.current().nextInt(spawns.size()));
    }

    public List<Location> getBaseSpawns(Team team, int baseIndex) {
        List<Location> spawns = baseSpawns.get(team).get(baseIndex);
        return spawns == null ? Collections.emptyList() : Collections.unmodifiableList(spawns);
    }

    public int getBaseSpawnCount(Team team, int baseIndex) {
        List<Location> spawns = baseSpawns.get(team).get(baseIndex);
        return spawns == null ? 0 : spawns.size();
    }

    public boolean setBaseSpawn(Team team, int baseIndex, int index, Location loc) {
        Map<Integer, List<Location>> perTeam = baseSpawns.get(team);
        List<Location> spawns = perTeam.computeIfAbsent(baseIndex, k -> new ArrayList<>());
        return setInList(spawns, index, loc);
    }

    public boolean removeBaseSpawn(Team team, int baseIndex, int index) {
        List<Location> spawns = baseSpawns.get(team).get(baseIndex);
        if (spawns == null) return false;
        return removeFromList(spawns, index);
    }

    // ================= BUTS (ZONES DE CAPTURE) : BASES =================

    public CuboidRegion getBaseGoal(Team team, int baseIndex) {
        return baseGoals.get(team).get(baseIndex);
    }

    public void setBaseGoal(Team team, int baseIndex, CuboidRegion region) {
        baseGoals.get(team).put(baseIndex, region);
    }

    public boolean isInBaseGoal(Team team, int baseIndex, Location loc) {
        CuboidRegion region = getBaseGoal(team, baseIndex);
        return region != null && region.contains(loc);
    }

    // ================= ACCESSEURS GÉNÉRIQUES PAR ZONEROLE =================

    /**
     * Renvoie un spawn (aléatoire parmi ceux configurés) pour la zone/équipe donnée.
     * Pour MID, l'index de base est ignoré.
     */
    public Location getZoneSpawn(Team team, ZoneRole role) {
        return role.isMid() ? getMidSpawn(team) : getBaseSpawn(team, role.getBaseIndex());
    }

    public int getZoneSpawnCount(Team team, ZoneRole role) {
        return role.isMid() ? getMidSpawnCount(team) : getBaseSpawnCount(team, role.getBaseIndex());
    }

    public boolean setZoneSpawn(Team team, ZoneRole role, int index, Location loc) {
        return role.isMid() ? setMidSpawn(team, index, loc) : setBaseSpawn(team, role.getBaseIndex(), index, loc);
    }

    public boolean removeZoneSpawn(Team team, ZoneRole role, int index) {
        return role.isMid() ? removeMidSpawn(team, index) : removeBaseSpawn(team, role.getBaseIndex(), index);
    }

    // ================= UTILITAIRES LISTES (spawns 1-based, sans trous) =================

    private boolean setInList(List<Location> list, int index, Location loc) {
        if (index < 1) return false;
        if (index <= list.size()) {
            list.set(index - 1, loc);
            return true;
        }
        if (index == list.size() + 1) {
            list.add(loc);
            return true;
        }
        return false;
    }

    private boolean removeFromList(List<Location> list, int index) {
        if (index < 1 || index > list.size()) return false;
        list.remove(index - 1);
        return true;
    }

    // ---- Sauvegarde / chargement dans un fichier de config ----

    public void saveToConfig(FileConfiguration config) {
        saveLocation(config, "arena.lobby", lobbySpawn);
        config.set("arena.zonecount", zoneCount);

        config.set("arena.mid", null);
        saveSpawnList(config, "arena.mid.red", midSpawns.get(Team.RED));
        saveSpawnList(config, "arena.mid.blue", midSpawns.get(Team.BLUE));

        config.set("arena.bases", null);
        for (Team team : Team.values()) {
            String teamKey = team.name().toLowerCase(java.util.Locale.ROOT);
            for (int k = 1; k <= 4; k++) {
                List<Location> spawns = baseSpawns.get(team).get(k);
                if (spawns != null && !spawns.isEmpty()) {
                    saveSpawnList(config, "arena.bases." + teamKey + ".base" + k + ".spawns", spawns);
                }
                CuboidRegion goal = baseGoals.get(team).get(k);
                if (goal != null) {
                    saveRegion(config, "arena.bases." + teamKey + ".base" + k + ".goal", goal);
                }
            }
        }

        saveRegion(config, "arena.gamezone", gameZone);
        if (maxPlayers > 0) {
            config.set("arena.max-players", maxPlayers);
        } else {
            config.set("arena.max-players", null);
        }
    }

    public void loadFromConfig(FileConfiguration config) {
        this.lobbySpawn = loadLocation(config, "arena.lobby");
        this.zoneCount = config.isSet("arena.zonecount") ? config.getInt("arena.zonecount") : 5;
        if (zoneCount != 5 && zoneCount != 7 && zoneCount != 9) {
            zoneCount = 5;
        }

        midSpawns.get(Team.RED).clear();
        midSpawns.get(Team.RED).addAll(loadSpawnList(config, "arena.mid.red"));
        midSpawns.get(Team.BLUE).clear();
        midSpawns.get(Team.BLUE).addAll(loadSpawnList(config, "arena.mid.blue"));

        for (Team team : Team.values()) {
            baseSpawns.get(team).clear();
            baseGoals.get(team).clear();
            String teamKey = team.name().toLowerCase(java.util.Locale.ROOT);
            for (int k = 1; k <= 4; k++) {
                List<Location> spawns = loadSpawnList(config, "arena.bases." + teamKey + ".base" + k + ".spawns");
                if (!spawns.isEmpty()) {
                    baseSpawns.get(team).put(k, spawns);
                }
                CuboidRegion goal = loadRegion(config, "arena.bases." + teamKey + ".base" + k + ".goal");
                if (goal != null) {
                    baseGoals.get(team).put(k, goal);
                }
            }
        }

        this.gameZone = loadRegion(config, "arena.gamezone");
        this.maxPlayers = config.isSet("arena.max-players") ? config.getInt("arena.max-players") : -1;
    }

    private void saveSpawnList(FileConfiguration config, String path, List<Location> spawns) {
        for (int i = 0; i < spawns.size(); i++) {
            saveLocation(config, path + "." + (i + 1), spawns.get(i));
        }
    }

    private List<Location> loadSpawnList(FileConfiguration config, String path) {
        List<Location> result = new ArrayList<>();
        int index = 1;
        while (config.isSet(path + "." + index + ".world")) {
            Location loc = loadLocation(config, path + "." + index);
            if (loc != null) {
                result.add(loc);
            }
            index++;
        }
        return result;
    }

    private void saveLocation(FileConfiguration config, String path, Location loc) {
        if (loc == null) return;
        config.set(path + ".world", loc.getWorld().getName());
        config.set(path + ".x", loc.getX());
        config.set(path + ".y", loc.getY());
        config.set(path + ".z", loc.getZ());
        config.set(path + ".yaw", loc.getYaw());
        config.set(path + ".pitch", loc.getPitch());
    }

    private Location loadLocation(FileConfiguration config, String path) {
        if (!config.isSet(path + ".world")) return null;
        World world = org.bukkit.Bukkit.getWorld(config.getString(path + ".world"));
        if (world == null) return null;
        return new Location(
                world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw"),
                (float) config.getDouble(path + ".pitch")
        );
    }

    private void saveRegion(FileConfiguration config, String path, CuboidRegion region) {
        if (region == null) return;
        saveLocation(config, path + ".corner1", region.getCorner1());
        saveLocation(config, path + ".corner2", region.getCorner2());
    }

    private CuboidRegion loadRegion(FileConfiguration config, String path) {
        if (config.getConfigurationSection(path) == null) return null;
        Location c1 = loadLocation(config, path + ".corner1");
        Location c2 = loadLocation(config, path + ".corner2");
        if (c1 == null || c2 == null) return null;
        return new CuboidRegion(c1, c2);
    }
}
