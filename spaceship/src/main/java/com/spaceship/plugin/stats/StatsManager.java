package com.spaceship.plugin.stats;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.Team;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les statistiques d'SpaceShip :
 *  - Statistiques globales par équipe, ventilées par mode de jeu (1v1/2v2/3v3/4v4)
 *  - Statistiques INDIVIDUELLES par joueur (kills, deaths, K/D, parties, victoires)
 *    elles-mêmes ventilées par mode de jeu
 *
 * Les statistiques sont sauvegardées dans stats.yml.
 */
public class StatsManager {

    // ── Modes de jeu ──────────────────────────────────────────────────────────

    public enum GameMode {
        V1("1v1"), V2("2v2"), V3("3v3"), V4("4v4");

        private final String label;
        GameMode(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static GameMode fromTeamSize(int size) {
            return switch (size) {
                case 1  -> V1;
                case 2  -> V2;
                case 3  -> V3;
                default -> V4;
            };
        }
    }

    // ── Stats équipes par mode ─────────────────────────────────────────────────

    private static class ModeStats {
        int blackWins, whiteWins, blackKills, blackDeaths, whiteKills, whiteDeaths;
        void reset() { blackWins = whiteWins = blackKills = blackDeaths = whiteKills = whiteDeaths = 0; }
    }

    // ── Stats individuelles par joueur ─────────────────────────────────────────

    public static class PlayerStats {
        public String name;
        // Stats globales (toutes catégories confondues)
        public int kills;
        public int deaths;
        public int gamesPlayed;
        public int gamesWon;
        // Stats par mode
        public final Map<GameMode, PlayerModeStats> byMode = new HashMap<>();

        public PlayerStats(String name) {
            this.name = name;
            for (GameMode m : GameMode.values()) byMode.put(m, new PlayerModeStats());
        }

        public double getKD() {
            if (deaths == 0) return kills > 0 ? kills : 0.0;
            return Math.round((double) kills / deaths * 100.0) / 100.0;
        }

        public double getKD(GameMode m) {
            PlayerModeStats s = byMode.get(m);
            if (s.deaths == 0) return s.kills > 0 ? s.kills : 0.0;
            return Math.round((double) s.kills / s.deaths * 100.0) / 100.0;
        }

        public int getWins(GameMode m)       { return byMode.get(m).gamesWon; }
        public int getKills(GameMode m)      { return byMode.get(m).kills; }
        public int getGamesPlayed(GameMode m){ return byMode.get(m).gamesPlayed; }
    }

    public static class PlayerModeStats {
        public int kills, deaths, gamesPlayed, gamesWon;
    }

    // ── État ───────────────────────────────────────────────────────────────────

    private final SpaceShipPlugin plugin;
    private final File             statsFile;
    private FileConfiguration      statsConfig;

    private final Map<GameMode, ModeStats> modes = new HashMap<>();
    private int totalGames;
    private int totalCaptures;

    private final Map<UUID, PlayerStats> playerStats = new HashMap<>();

    // ── Constructeur ───────────────────────────────────────────────────────────

    public StatsManager(SpaceShipPlugin plugin) {
        this.plugin    = plugin;
        this.statsFile = new File(plugin.getDataFolder(), "stats.yml");
        for (GameMode m : GameMode.values()) modes.put(m, new ModeStats());
        loadStats();
    }

    // ── Persistance ────────────────────────────────────────────────────────────

    public void loadStats() {
        if (!statsFile.exists()) {
            try { statsFile.getParentFile().mkdirs(); statsFile.createNewFile(); }
            catch (IOException e) { plugin.getLogger().severe("Impossible de créer stats.yml: " + e.getMessage()); }
        }
        statsConfig   = YamlConfiguration.loadConfiguration(statsFile);
        totalGames    = statsConfig.getInt("total-games",    0);
        totalCaptures = statsConfig.getInt("total-captures", 0);

        for (GameMode m : GameMode.values()) {
            ModeStats s  = modes.get(m);
            s.blackWins    = statsConfig.getInt(mKey(m, "wins.black"),    0);
            s.whiteWins   = statsConfig.getInt(mKey(m, "wins.white"),   0);
            s.blackKills   = statsConfig.getInt(mKey(m, "kills.black"),   0);
            s.blackDeaths  = statsConfig.getInt(mKey(m, "deaths.black"),  0);
            s.whiteKills  = statsConfig.getInt(mKey(m, "kills.white"),  0);
            s.whiteDeaths = statsConfig.getInt(mKey(m, "deaths.white"), 0);
        }

        playerStats.clear();
        ConfigurationSection playersSection = statsConfig.getConfigurationSection("players");
        if (playersSection != null) {
            for (String uuidStr : playersSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    ConfigurationSection section = playersSection.getConfigurationSection(uuidStr);
                    if (section == null) continue;

                    PlayerStats ps = new PlayerStats(section.getString("name", "?"));
                    ps.kills       = section.getInt("kills", 0);
                    ps.deaths      = section.getInt("deaths", 0);
                    ps.gamesPlayed = section.getInt("games-played", 0);
                    ps.gamesWon    = section.getInt("games-won", 0);

                    // Stats par mode
                    for (GameMode m : GameMode.values()) {
                        String mp = "mode." + m.getLabel() + ".";
                        PlayerModeStats pms = ps.byMode.get(m);
                        pms.kills       = section.getInt(mp + "kills", 0);
                        pms.deaths      = section.getInt(mp + "deaths", 0);
                        pms.gamesPlayed = section.getInt(mp + "games-played", 0);
                        pms.gamesWon    = section.getInt(mp + "games-won", 0);
                    }
                    playerStats.put(uuid, ps);
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public void saveStats() {
        statsConfig.set("total-games",    totalGames);
        statsConfig.set("total-captures", totalCaptures);

        for (GameMode m : GameMode.values()) {
            ModeStats s = modes.get(m);
            statsConfig.set(mKey(m, "wins.black"),    s.blackWins);
            statsConfig.set(mKey(m, "wins.white"),   s.whiteWins);
            statsConfig.set(mKey(m, "kills.black"),   s.blackKills);
            statsConfig.set(mKey(m, "deaths.black"),  s.blackDeaths);
            statsConfig.set(mKey(m, "kills.white"),  s.whiteKills);
            statsConfig.set(mKey(m, "deaths.white"), s.whiteDeaths);
        }

        statsConfig.set("players", null);
        for (Map.Entry<UUID, PlayerStats> entry : playerStats.entrySet()) {
            String path = "players." + entry.getKey();
            PlayerStats ps = entry.getValue();
            statsConfig.set(path + ".name",         ps.name);
            statsConfig.set(path + ".kills",        ps.kills);
            statsConfig.set(path + ".deaths",       ps.deaths);
            statsConfig.set(path + ".games-played", ps.gamesPlayed);
            statsConfig.set(path + ".games-won",    ps.gamesWon);
            for (GameMode m : GameMode.values()) {
                String mp = path + ".mode." + m.getLabel() + ".";
                PlayerModeStats pms = ps.byMode.get(m);
                statsConfig.set(mp + "kills",        pms.kills);
                statsConfig.set(mp + "deaths",       pms.deaths);
                statsConfig.set(mp + "games-played", pms.gamesPlayed);
                statsConfig.set(mp + "games-won",    pms.gamesWon);
            }
        }

        try { statsConfig.save(statsFile); }
        catch (IOException e) { plugin.getLogger().severe("Impossible de sauvegarder stats.yml: " + e.getMessage()); }
    }

    private static String mKey(GameMode m, String stat) { return m.getLabel() + "." + stat; }

    private PlayerStats getOrCreate(UUID uuid, String name) {
        PlayerStats ps = playerStats.get(uuid);
        if (ps == null) { ps = new PlayerStats(name); playerStats.put(uuid, ps); }
        else if (name != null) ps.name = name;
        return ps;
    }

    // ── Mutateurs équipes ──────────────────────────────────────────────────────

    public void addWin(Team winner, int playersPerTeam) {
        GameMode m  = GameMode.fromTeamSize(playersPerTeam);
        ModeStats s = modes.get(m);
        if (winner == Team.BLACK) s.blackWins++; else s.whiteWins++;
        totalGames++;
        saveStats();
    }
    public void addWin(Team winner) { addWin(winner, 1); }

    public void addKill(Team team, int playersPerTeam) {
        ModeStats s = modes.get(GameMode.fromTeamSize(playersPerTeam));
        if (team == Team.BLACK) s.blackKills++; else s.whiteKills++;
        saveStats();
    }
    public void addKill(Team team) { addKill(team, 1); }

    public void addDeath(Team team, int playersPerTeam) {
        ModeStats s = modes.get(GameMode.fromTeamSize(playersPerTeam));
        if (team == Team.BLACK) s.blackDeaths++; else s.whiteDeaths++;
        saveStats();
    }
    public void addDeath(Team team) { addDeath(team, 1); }

    public void addCapture() { totalCaptures++; saveStats(); }

    // ── Mutateurs joueurs ──────────────────────────────────────────────────────

    public void addPlayerKill(UUID uuid, String name, int playersPerTeam) {
        GameMode m = GameMode.fromTeamSize(playersPerTeam);
        PlayerStats ps = getOrCreate(uuid, name);
        ps.kills++;
        ps.byMode.get(m).kills++;
        saveStats();
    }
    public void addPlayerKill(UUID uuid, String name) { addPlayerKill(uuid, name, 1); }

    public void addPlayerDeath(UUID uuid, String name, int playersPerTeam) {
        GameMode m = GameMode.fromTeamSize(playersPerTeam);
        PlayerStats ps = getOrCreate(uuid, name);
        ps.deaths++;
        ps.byMode.get(m).deaths++;
        saveStats();
    }
    public void addPlayerDeath(UUID uuid, String name) { addPlayerDeath(uuid, name, 1); }

    public void addPlayerGameResult(UUID uuid, String name, boolean won, int playersPerTeam) {
        GameMode m = GameMode.fromTeamSize(playersPerTeam);
        PlayerStats ps = getOrCreate(uuid, name);
        ps.gamesPlayed++;
        ps.byMode.get(m).gamesPlayed++;
        if (won) { ps.gamesWon++; ps.byMode.get(m).gamesWon++; }
        saveStats();
    }
    public void addPlayerGameResult(UUID uuid, String name, boolean won) { addPlayerGameResult(uuid, name, won, 1); }

    // ── Accesseurs équipes globaux ─────────────────────────────────────────────

    public int getBlackWins()    { return modes.values().stream().mapToInt(s -> s.blackWins).sum(); }
    public int getWhiteWins()   { return modes.values().stream().mapToInt(s -> s.whiteWins).sum(); }
    public int getBlackKills()   { return modes.values().stream().mapToInt(s -> s.blackKills).sum(); }
    public int getWhiteKills()  { return modes.values().stream().mapToInt(s -> s.whiteKills).sum(); }
    public int getBlackDeaths()  { return modes.values().stream().mapToInt(s -> s.blackDeaths).sum(); }
    public int getWhiteDeaths() { return modes.values().stream().mapToInt(s -> s.whiteDeaths).sum(); }
    public int getTotalGames()    { return totalGames; }
    public int getTotalCaptures() { return totalCaptures; }

    public double getBlackKD()  { int d=getBlackDeaths(), k=getBlackKills();   return d==0?k:Math.round((double)k/d*100)/100.0; }
    public double getWhiteKD() { int d=getWhiteDeaths(), k=getWhiteKills(); return d==0?k:Math.round((double)k/d*100)/100.0; }

    // ── Accesseurs équipes par mode ────────────────────────────────────────────

    public int getBlackWins(GameMode m)    { return modes.get(m).blackWins; }
    public int getWhiteWins(GameMode m)   { return modes.get(m).whiteWins; }
    public int getBlackKills(GameMode m)   { return modes.get(m).blackKills; }
    public int getWhiteKills(GameMode m)  { return modes.get(m).whiteKills; }
    public int getBlackDeaths(GameMode m)  { return modes.get(m).blackDeaths; }
    public int getWhiteDeaths(GameMode m) { return modes.get(m).whiteDeaths; }

    public double getBlackKD(GameMode m) {
        int d=modes.get(m).blackDeaths, k=modes.get(m).blackKills;
        return d==0?k:Math.round((double)k/d*100)/100.0;
    }
    public double getWhiteKD(GameMode m) {
        int d=modes.get(m).whiteDeaths, k=modes.get(m).whiteKills;
        return d==0?k:Math.round((double)k/d*100)/100.0;
    }

    // ── Accesseurs joueurs ────────────────────────────────────────────────────

    public PlayerStats getPlayerStats(UUID uuid, String fallbackName) {
        PlayerStats ps = playerStats.get(uuid);
        return ps != null ? ps : new PlayerStats(fallbackName);
    }

    /**
     * Top joueurs global (toutes catégories).
     */
    public List<Map.Entry<UUID, PlayerStats>> getTopPlayers(int limit, Comparator<PlayerStats> comparator) {
        List<Map.Entry<UUID, PlayerStats>> entries = new ArrayList<>();
        for (Map.Entry<UUID, PlayerStats> entry : playerStats.entrySet()) {
            if (entry.getValue().gamesPlayed > 0) entries.add(entry);
        }
        entries.sort((a, b) -> comparator.compare(b.getValue(), a.getValue()));
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    /**
     * Top joueurs pour un mode précis.
     */
    public List<Map.Entry<UUID, PlayerStats>> getTopPlayersByMode(int limit, GameMode mode, Comparator<PlayerStats> comparator) {
        List<Map.Entry<UUID, PlayerStats>> entries = new ArrayList<>();
        for (Map.Entry<UUID, PlayerStats> entry : playerStats.entrySet()) {
            if (entry.getValue().byMode.get(mode).gamesPlayed > 0) entries.add(entry);
        }
        entries.sort((a, b) -> comparator.compare(b.getValue(), a.getValue()));
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    // ── Reset ──────────────────────────────────────────────────────────────────

    public void resetStats() {
        modes.values().forEach(ModeStats::reset);
        totalGames = totalCaptures = 0;
        playerStats.clear();
        saveStats();
    }

    public Map<String, Object> getAllStats() {
        Map<String, Object> s = new HashMap<>();
        s.put("black_wins",  getBlackWins());  s.put("white_wins",  getWhiteWins());
        s.put("black_kills", getBlackKills()); s.put("black_deaths", getBlackDeaths());
        s.put("white_kills",getWhiteKills());s.put("white_deaths",getWhiteDeaths());
        s.put("black_kd",    getBlackKD());    s.put("white_kd",    getWhiteKD());
        s.put("total_games", totalGames);  s.put("total_captures", totalCaptures);
        return s;
    }
}
