package com.spaceship.plugin.game;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stocke toute la configuration spatiale d'une arène SpaceShip.
 *
 * LAYOUT (exemple zoneCount=5, 2 bases par équipe) :
 *
 *   [base2black] [base1black] [mid] [base1white] [base2white]
 *
 * Chaque SALLE est une pièce fermée distincte.  Elle possède :
 *   - un spawn par équipe (où chaque équipe apparaît quand tout le monde
 *     est téléporté dans cette salle).
 *   - un GOAL PAR ÉQUIPE (deux zones de capture distinctes dans la même salle) :
 *     chaque équipe a son propre point à toucher pour marquer depuis cette salle.
 *     Toucher SON goal fait avancer la ligne de front si on est déjà en tête (ou à
 *     égalité au Mid), ou repousse l'adversaire d'une salle si on est en train de
 *     défendre (voir GameManager#handleZoneCapture).
 *
 * Identifiants de salles (roomId) : "mid", "base1black", "base1white", "base2black", ...
 * Chaque salle a donc deux goals, un par Team (BLACK/WHITE).
 *
 * Relation frontier → salle (voir GameManager) :
 *   frontier =  0  →  "mid"
 *   frontier = +k  →  "base{k}white"   (noir avance en territoire blanc)
 *   frontier = -k  →  "base{k}black"   (blanc avance en territoire noir)
 */
public class Arena {

    private Location lobbySpawn;
    private CuboidRegion gameZone;
    private int zoneCount = 5;
    private int maxPlayers = -1;

    /**
     * Spawns par salle, puis par équipe.
     * clé : roomId ("mid", "base1black", "base1white", ...)
     */
    private final Map<String, Map<Team, List<Location>>> roomSpawns = new LinkedHashMap<>();

    /**
     * Zones de capture (goals), par salle puis par équipe.
     * clé : roomId ("mid", "base1black", "base1white", ...)
     * Chaque salle contient un goal pour BLACK et un goal pour WHITE.
     */
    private final Map<String, Map<Team, CuboidRegion>> roomGoals = new LinkedHashMap<>();

    // ================= FRONTIER → ROOM =================

    /**
     * Convertit la valeur de la ligne de front en identifiant de salle :
     *   0  → "mid"
     *  +k  → "base{k}white"
     *  -k  → "base{k}black"
     */
    public static String frontierToRoom(int frontier) {
        if (frontier == 0) return "mid";
        if (frontier > 0) return "base" + frontier + "white";
        return "base" + (-frontier) + "black";
    }

    // ================= ZONE COUNT =================

    public int getZoneCount() { return zoneCount; }

    /** N'accepte que 5, 7 ou 9. Renvoie false si invalide. */
    public boolean setZoneCount(int zoneCount) {
        if (zoneCount != 5 && zoneCount != 7 && zoneCount != 9) return false;
        this.zoneCount = zoneCount;
        return true;
    }

    /** Nombre de salles de base par équipe : 2, 3 ou 4 selon zoneCount 5/7/9. */
    public int getBasesPerSide() { return (zoneCount - 1) / 2; }

    // ================= DIVERS =================

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int v) { this.maxPlayers = v <= 0 ? -1 : v; }
    public CuboidRegion getGameZone() { return gameZone; }
    public void setGameZone(CuboidRegion region) { this.gameZone = region; }
    public boolean isInGameZone(Location loc) { return gameZone != null && gameZone.contains(loc); }
    public Location getLobbySpawn() { return lobbySpawn; }
    public void setLobbySpawn(Location loc) { this.lobbySpawn = loc; }

    // ================= SPAWNS =================

    private List<Location> spawnList(String roomId, Team team) {
        return roomSpawns
            .computeIfAbsent(roomId, k -> {
                Map<Team, List<Location>> m = new EnumMap<>(Team.class);
                m.put(Team.BLACK, new ArrayList<>());
                m.put(Team.WHITE, new ArrayList<>());
                return m;
            })
            .computeIfAbsent(team, k -> new ArrayList<>());
    }

    /** Renvoie un spawn aléatoire pour la salle et l'équipe données. Null si non configuré. */
    public Location getRoomSpawn(String roomId, Team team) {
        Map<Team, List<Location>> room = roomSpawns.get(roomId);
        if (room == null) return null;
        List<Location> list = room.get(team);
        if (list == null || list.isEmpty()) return null;
        return list.size() == 1 ? list.get(0)
                                : list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    public int getRoomSpawnCount(String roomId, Team team) {
        Map<Team, List<Location>> room = roomSpawns.get(roomId);
        if (room == null) return 0;
        List<Location> list = room.get(team);
        return list == null ? 0 : list.size();
    }

    /** Définit (ou remplace) le spawn numéro {@code index} (1-based). */
    public boolean setRoomSpawn(String roomId, Team team, int index, Location loc) {
        return setInList(spawnList(roomId, team), index, loc);
    }

    public boolean removeRoomSpawn(String roomId, Team team, int index) {
        Map<Team, List<Location>> room = roomSpawns.get(roomId);
        if (room == null) return false;
        List<Location> list = room.get(team);
        return list != null && removeFromList(list, index);
    }

    // ================= GOALS =================

    /** Renvoie le goal de {@code team} dans la salle {@code roomId}, ou null si non configuré. */
    public CuboidRegion getRoomGoal(String roomId, Team team) {
        Map<Team, CuboidRegion> room = roomGoals.get(roomId);
        return room == null ? null : room.get(team);
    }

    /** Définit (ou remplace) le goal de {@code team} dans la salle {@code roomId}. */
    public void setRoomGoal(String roomId, Team team, CuboidRegion region) {
        roomGoals.computeIfAbsent(roomId, k -> new EnumMap<>(Team.class)).put(team, region);
    }

    /** True si {@code loc} se trouve dans le goal de {@code team} pour la salle {@code roomId}. */
    public boolean isInRoomGoal(String roomId, Team team, Location loc) {
        CuboidRegion r = getRoomGoal(roomId, team);
        return r != null && r.contains(loc);
    }

    // ================= CONVENIENCE METHODS FOR BLOCK PLACE LISTENER =================

    /** Check if location is in base goal for team at base index k (1-based). */
    public boolean isInBaseGoal(Team team, int k, Location loc) {
        if (k < 1 || k > getBasesPerSide()) return false;
        String roomId = "base" + k + team.name().toLowerCase(Locale.ROOT);
        return isInRoomGoal(roomId, team, loc);
    }

    /** Get all mid spawns for team. */
    public List<Location> getMidSpawns(Team team) {
        List<Location> result = new ArrayList<>();
        Map<Team, List<Location>> mid = roomSpawns.get("mid");
        if (mid != null) {
            List<Location> spawns = mid.get(team);
            if (spawns != null) result.addAll(spawns);
        }
        return result;
    }

    /** Get all spawns for team at base index k (1-based). */
    public List<Location> getBaseSpawns(Team team, int k) {
        List<Location> result = new ArrayList<>();
        if (k < 1 || k > getBasesPerSide()) return result;
        String roomId = "base" + k + team.name().toLowerCase(Locale.ROOT);
        Map<Team, List<Location>> base = roomSpawns.get(roomId);
        if (base != null) {
            List<Location> spawns = base.get(team);
            if (spawns != null) result.addAll(spawns);
        }
        return result;
    }

    // ================= VALIDATION =================

    /**
     * Arène considérée comme entièrement configurée quand :
     * - lobby défini
     * - salle "mid" a un spawn ET un goal pour chaque équipe
     * - pour chaque salle de base (base1black, base1white, …) :
     *     • spawn pour black ET pour white
     *     • goal pour black ET pour white
     */
    public boolean isFullyConfigured() {
        if (lobbySpawn == null) return false;
        if (!roomFullyConfigured("mid")) return false;

        int n = getBasesPerSide();
        for (int k = 1; k <= n; k++) {
            for (Team t : Team.values()) {
                String roomId = "base" + k + t.name().toLowerCase(Locale.ROOT);
                if (!roomFullyConfigured(roomId)) return false;
            }
        }
        return true;
    }

    private boolean roomFullyConfigured(String roomId) {
        for (Team t : Team.values()) {
            if (getRoomSpawnCount(roomId, t) == 0) return false;
            if (getRoomGoal(roomId, t) == null) return false;
        }
        return true;
    }

    // ================= SAVE / LOAD =================

    public void saveToConfig(FileConfiguration config) {
        saveLocation(config, "arena.lobby", lobbySpawn);
        config.set("arena.zonecount", zoneCount);
        if (maxPlayers > 0) config.set("arena.max-players", maxPlayers);
        else config.set("arena.max-players", null);

        config.set("arena.spawns", null);
        for (Map.Entry<String, Map<Team, List<Location>>> re : roomSpawns.entrySet()) {
            for (Map.Entry<Team, List<Location>> te : re.getValue().entrySet()) {
                String base = "arena.spawns." + re.getKey() + "." + te.getKey().name().toLowerCase(Locale.ROOT);
                saveSpawnList(config, base, te.getValue());
            }
        }

        config.set("arena.goals", null);
        for (Map.Entry<String, Map<Team, CuboidRegion>> re : roomGoals.entrySet()) {
            for (Map.Entry<Team, CuboidRegion> te : re.getValue().entrySet()) {
                String base = "arena.goals." + re.getKey() + "." + te.getKey().name().toLowerCase(Locale.ROOT);
                saveRegion(config, base, te.getValue());
            }
        }

        saveRegion(config, "arena.gamezone", gameZone);
    }

    public void loadFromConfig(FileConfiguration config) {
        this.lobbySpawn = loadLocation(config, "arena.lobby");
        this.zoneCount = config.isSet("arena.zonecount") ? config.getInt("arena.zonecount") : 5;
        if (zoneCount != 5 && zoneCount != 7 && zoneCount != 9) zoneCount = 5;
        this.maxPlayers = config.isSet("arena.max-players") ? config.getInt("arena.max-players") : -1;

        roomSpawns.clear();
        ConfigurationSection spawnsSection = config.getConfigurationSection("arena.spawns");
        if (spawnsSection != null) {
            for (String roomId : spawnsSection.getKeys(false)) {
                ConfigurationSection roomSection = spawnsSection.getConfigurationSection(roomId);
                if (roomSection == null) continue;
                for (String teamKey : roomSection.getKeys(false)) {
                    Team team = teamFromKey(teamKey);
                    if (team == null) continue;
                    List<Location> locs = loadSpawnList(config,
                            "arena.spawns." + roomId + "." + teamKey);
                    if (!locs.isEmpty()) spawnList(roomId, team).addAll(locs);
                }
            }
        }

        roomGoals.clear();
        ConfigurationSection goalsSection = config.getConfigurationSection("arena.goals");
        if (goalsSection != null) {
            for (String key : goalsSection.getKeys(false)) {
                ConfigurationSection keySection = goalsSection.getConfigurationSection(key);
                if (keySection == null) continue;
                if (keySection.getConfigurationSection("corner1") != null) {
                    // Ancien format (une génération avant l'ajout des goals par équipe) :
                    // "arena.goals.<goalId>.corner1/corner2" où goalId était soit
                    // "midblack"/"midwhite", soit le roomId d'une salle de base
                    // (goal unique partagé par les deux équipes). On migre vers le
                    // nouveau format afin de ne pas perdre les zones déjà configurées.
                    CuboidRegion r = loadRegion(config, "arena.goals." + key);
                    if (r == null) continue;
                    migrateLegacyGoal(key, r);
                } else {
                    // Nouveau format : "arena.goals.<roomId>.<team>.corner1/corner2"
                    for (String teamKey : keySection.getKeys(false)) {
                        Team team = teamFromKey(teamKey);
                        if (team == null) continue;
                        CuboidRegion r = loadRegion(config, "arena.goals." + key + "." + teamKey);
                        if (r != null) setRoomGoal(key, team, r);
                    }
                }
            }
        }

        this.gameZone = loadRegion(config, "arena.gamezone");
    }

    // ================= UTILITAIRES LISTE (index 1-based, sans trou) =================

    private boolean setInList(List<Location> list, int index, Location loc) {
        if (index < 1) return false;
        if (index <= list.size()) { list.set(index - 1, loc); return true; }
        if (index == list.size() + 1) { list.add(loc); return true; }
        return false;
    }

    private boolean removeFromList(List<Location> list, int index) {
        if (index < 1 || index > list.size()) return false;
        list.remove(index - 1);
        return true;
    }

    /**
     * Migre un ancien goalId à plat ("midblack", "midwhite", "base1black", ...) vers le
     * nouveau modèle (un goal par équipe et par salle), en conservant la même zone.
     * <ul>
     *   <li>"midblack" était le goal que BLANC touchait pour marquer → devient le goal
     *       de WHITE dans la salle "mid".</li>
     *   <li>"midwhite" était le goal que NOIR touchait pour marquer → devient le goal
     *       de BLACK dans la salle "mid".</li>
     *   <li>Une salle de base ("base1black", "base1white", ...) avait un seul goal
     *       partagé par les deux équipes → on l'assigne aux DEUX équipes dans cette
     *       salle (même zone physique tant que l'admin n'en définit pas une nouvelle
     *       par équipe).</li>
     * </ul>
     */
    private void migrateLegacyGoal(String legacyGoalId, CuboidRegion region) {
        if (legacyGoalId.equals("midblack")) {
            setRoomGoal("mid", Team.WHITE, region);
        } else if (legacyGoalId.equals("midwhite")) {
            setRoomGoal("mid", Team.BLACK, region);
        } else if (legacyGoalId.matches("base[1-4](black|white)")) {
            setRoomGoal(legacyGoalId, Team.BLACK, region);
            setRoomGoal(legacyGoalId, Team.WHITE, region);
        }
    }

    private static Team teamFromKey(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "black" -> Team.BLACK;
            case "white" -> Team.WHITE;
            default -> null;
        };
    }

    // ---- Sérialisation ----

    private void saveSpawnList(FileConfiguration config, String path, List<Location> list) {
        for (int i = 0; i < list.size(); i++)
            saveLocation(config, path + "." + (i + 1), list.get(i));
    }

    private List<Location> loadSpawnList(FileConfiguration config, String path) {
        List<Location> result = new ArrayList<>();
        int i = 1;
        while (config.isSet(path + "." + i + ".world")) {
            Location loc = loadLocation(config, path + "." + i);
            if (loc != null) result.add(loc);
            i++;
        }
        return result;
    }

    private void saveLocation(FileConfiguration config, String path, Location loc) {
        if (loc == null) return;
        config.set(path + ".world", loc.getWorld().getName());
        config.set(path + ".x", loc.getX());
        config.set(path + ".y", loc.getY());
        config.set(path + ".z", loc.getZ());
        config.set(path + ".yaw",   (double) loc.getYaw());
        config.set(path + ".pitch", (double) loc.getPitch());
    }

    private Location loadLocation(FileConfiguration config, String path) {
        if (!config.isSet(path + ".world")) return null;
        World world = org.bukkit.Bukkit.getWorld(config.getString(path + ".world"));
        if (world == null) return null;
        return new Location(world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw"),
                (float) config.getDouble(path + ".pitch"));
    }

    private void saveRegion(FileConfiguration config, String path, CuboidRegion r) {
        if (r == null) return;
        saveLocation(config, path + ".corner1", r.getCorner1());
        saveLocation(config, path + ".corner2", r.getCorner2());
    }

    private CuboidRegion loadRegion(FileConfiguration config, String path) {
        if (config.getConfigurationSection(path) == null) return null;
        Location c1 = loadLocation(config, path + ".corner1");
        Location c2 = loadLocation(config, path + ".corner2");
        if (c1 == null || c2 == null) return null;
        return new CuboidRegion(c1, c2);
    }
}
