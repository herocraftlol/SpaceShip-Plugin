package com.spaceship.plugin.game;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Color;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;
import java.util.Collections;

/**
 * Gère l'intégralité du cycle de vie d'une partie SpaceShip pour UNE arène nommée :
 * lobby d'attente -> compte à rebours -> partie -> fin -> reset.
 *
 * Contrairement à HikaBrain (score simple à atteindre), SpaceShip est un jeu de "poussée"
 * le long d'un vaisseau à plusieurs zones : chaque équipe essaie de percer, une zone à la
 * fois, jusqu'au bout du vaisseau adverse (la dernière Base) pour gagner directement.
 *
 * L'état de la partie est représenté par un seul entier "frontier" :
 * -  0        = les deux équipes sont au Mid (neutre), c'est le début de partie ou une
 *               situation totalement remise à zéro.
 * -  +k (k>0) = l'équipe NOIRE a percé k zones dans le territoire BLANC (elle est
 *               actuellement stationnée à Base<k>_Blanc, en attente de percer Base<k+1>_Blanc).
 * -  -k (k>0) = l'équipe BLANCHE a percé k zones dans le territoire NOIR, symétriquement.
 *
 * Règles de percée (voir handleZoneCapture) :
 * - Si l'équipe qui marque est déjà en avantage (ou à égalité au Mid) et avance ENCORE plus
 *   loin dans le territoire adverse : la frontière avance d'un cran vers cette équipe. Si
 *   cela correspond à la dernière Base du vaisseau adverse, c'est la victoire immédiate.
 * - Si l'équipe qui marque était en train de DÉFENDRE (l'adversaire avait l'avantage) et
 *   reprend sa zone : la frontière recule d'UN SEUL cran (l'adversaire est repoussé d'une
 *   Base, pas éliminé d'un coup). Si l'adversaire n'était avancé que d'une zone (Base1),
 *   ce recul d'un cran ramène naturellement tout le monde à 0 (Mid) ; s'il était plus
 *   profond, il reste avec son avantage réduit d'une zone (ex : Base2 -> Base1) au lieu
 *   d'être renvoyé jusqu'au centre.
 *
 * Plusieurs instances de GameManager peuvent coexister (une par arène), gérées par
 * ArenaManager, ce qui permet plusieurs parties SpaceShip simultanées et indépendantes.
 */
public class GameManager {

    private final SpaceShipPlugin plugin;
    private final String arenaName;
    private final Arena arena;
    private final ArenaSnapshot arenaSnapshot;
    private final File snapshotFile;
    private final File arenaConfigFile;
    private final org.bukkit.configuration.file.YamlConfiguration arenaConfig;

    private GameState state = GameState.NOT_CONFIGURED;

    // Joueurs en lobby/en partie, et leur équipe assignée
    private final Map<UUID, Team> playerTeams = new HashMap<>();

    // Positions des joueurs avant qu'ils rejoignent le lobby (pour restauration à la fin)
    private final Map<UUID, Location> preLobbyLocations = new HashMap<>();

    // Position actuelle de la "ligne de front" le long du vaisseau (voir doc de classe).
    private int frontier = 0;

    private BukkitTask countdownTask;
    private BukkitTask roundResetTask;
    private BukkitTask offhandReplenishTask;
    private int countdownSecondsLeft;
    private int roundResetSecondsLeft;

    // Joueurs actuellement gelés (après une percée)
    private final Set<UUID> frozenPlayers = new HashSet<>();

    public GameManager(SpaceShipPlugin plugin, String arenaName) {
        this.plugin = plugin;
        this.arenaName = arenaName;
        this.arena = new Arena();
        this.arenaSnapshot = new ArenaSnapshot(plugin.getLogger());

        File arenasDir = new File(plugin.getDataFolder(), "arenas");
        if (!arenasDir.exists()) {
            arenasDir.mkdirs();
        }
        this.snapshotFile = new File(arenasDir, arenaName + ".snapshot");
        this.arenaConfigFile = new File(arenasDir, arenaName + ".yml");
        this.arenaConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(arenaConfigFile);

        resetFrontier();
    }

    public String getName() {
        return arenaName;
    }

    public Arena getArena() {
        return arena;
    }

    public ArenaSnapshot getArenaSnapshot() {
        return arenaSnapshot;
    }

    public void captureGameZone() {
        CuboidRegion zone = arena.getGameZone();
        if (zone == null) return;
        arenaSnapshot.capture(zone);
        arenaSnapshot.saveToFile(snapshotFile);
    }

    public void loadGameZoneSnapshot() {
        arenaSnapshot.loadFromFile(snapshotFile);
    }

    public void saveArenaConfig() {
        arena.saveToConfig(arenaConfig);
        try {
            arenaConfig.save(arenaConfigFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder l'arène '" + arenaName + "' : " + e.getMessage());
        }
    }

    public void loadArenaConfig() {
        arena.loadFromConfig(arenaConfig);
    }

    public GameState getState() {
        return state;
    }

    private void resetFrontier() {
        frontier = 0;
    }

    /**
     * Position actuelle de la ligne de front (voir doc de classe). 0 = Mid.
     */
    public int getFrontier() {
        return frontier;
    }

    public int getBasesPerSide() {
        return arena.getBasesPerSide();
    }

    /**
     * Compatibilité / affichage simple : renvoie le nombre de zones actuellement gagnées
     * "en faveur" de cette équipe (0 si elle est en train de défendre ou à égalité).
     */
    public int getScore(Team team) {
        if (team == Team.BLACK) {
            return Math.max(frontier, 0);
        } else {
            return Math.max(-frontier, 0);
        }
    }

    // ================= GESTION DES JOUEURS =================

    public boolean isPlaying(Player player) {
        return playerTeams.containsKey(player.getUniqueId());
    }

    public Team getTeam(Player player) {
        return playerTeams.get(player.getUniqueId());
    }

    public int getPlayerCount() {
        return playerTeams.size();
    }

    public Map<UUID, Team> getPlayerTeams() {
        return Collections.unmodifiableMap(playerTeams);
    }

    public int getPlayerCountForTeam(Team team) {
        return (int) playerTeams.values().stream().filter(t -> t == team).count();
    }

    public int getMaxPlayers() {
        int specific = arena.getMaxPlayers();
        if (specific > 0) {
            return specific;
        }
        return plugin.getConfig().getInt("max-players", 16);
    }

    public boolean addPlayer(Player player) {
        if (!arena.isFullyConfigured()) {
            MessageUtil.send(player, "&cLa map n'est pas encore configurée. Contacte un admin.");
            return false;
        }
        if (state == GameState.PLAYING || state == GameState.ROUND_RESET || state == GameState.ENDING) {
            MessageUtil.send(player, "&cUne partie est déjà en cours, réessaie plus tard.");
            return false;
        }
        int max = getMaxPlayers();
        if (playerTeams.size() >= max) {
            MessageUtil.send(player, "&cLe lobby est complet.");
            return false;
        }

        Team team = pickBalancedTeam();
        playerTeams.put(player.getUniqueId(), team);

        preLobbyLocations.put(player.getUniqueId(), player.getLocation().clone());

        player.teleport(arena.getLobbySpawn());
        preparePlayerForLobby(player);

        plugin.getScoreboardManager().showScoreboard(player, this);

        broadcast(MessageUtil.format(plugin.getConfig().getString("messages.join", ""))
                .replace("%current%", String.valueOf(playerTeams.size()))
                .replace("%max%", String.valueOf(max)));
        MessageUtil.send(player, plugin.getConfig().getString("messages.team-assigned", "")
                .replace("%team%", team.getColoredName()));

        checkLobbyStart();
        return true;
    }

    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (!playerTeams.containsKey(uuid)) {
            return;
        }
        playerTeams.remove(uuid);

        if (frozenPlayers.remove(uuid)) {
            player.setWalkSpeed(0.2f);
            player.setFlySpeed(0.1f);
        }
        MessageUtil.send(player, plugin.getConfig().getString("messages.leave", ""));

        Location preLobbyLocation = preLobbyLocations.remove(uuid);
        if (preLobbyLocation != null) {
            restorePlayer(player);
            player.teleport(preLobbyLocation);
        } else {
            restorePlayer(player);
        }

        plugin.getScoreboardManager().removeScoreboard(player);

        if (state == GameState.COUNTDOWN
                && (playerTeams.size() < plugin.getConfig().getInt("min-players", 2) || !bothTeamsHavePlayers())) {
            cancelCountdown();
        }

        if (state == GameState.PLAYING || state == GameState.ROUND_RESET) {
            checkForfeit();
        }
    }

    private Team pickBalancedTeam() {
        long blackCount = playerTeams.values().stream().filter(t -> t == Team.BLACK).count();
        long whiteCount = playerTeams.values().stream().filter(t -> t == Team.WHITE).count();
        return blackCount <= whiteCount ? Team.BLACK : Team.WHITE;
    }

    public boolean changePlayerTeam(Player player, Team newTeam) {
        UUID uuid = player.getUniqueId();
        if (!playerTeams.containsKey(uuid)) {
            return false;
        }

        if (state == GameState.PLAYING || state == GameState.ROUND_RESET || state == GameState.COUNTDOWN) {
            return false;
        }

        Team oldTeam = playerTeams.get(uuid);
        if (oldTeam == newTeam) {
            return false;
        }

        playerTeams.put(uuid, newTeam);

        player.getInventory().setItem(KitManager.TEAM_SELECT_SLOT, KitManager.createTeamSelectorItem(newTeam));
        KitManager.equipArmor(player, newTeam);

        MessageUtil.send(player, plugin.getConfig().getString("messages.team-changed", "")
                .replace("%team%", newTeam.getColoredName()));

        return true;
    }

    private void preparePlayerForLobby(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.setHealth(20);
        player.setFoodLevel(20);

        Team team = playerTeams.get(player.getUniqueId());
        player.getInventory().setItem(KitManager.TEAM_SELECT_SLOT, KitManager.createTeamSelectorItem(team));

        if (player.hasPermission("spaceship.admin")) {
            player.getInventory().setItem(KitManager.FORCESTART_SLOT, KitManager.createForceStartItem());
        }

        player.getInventory().setItem(KitManager.LEAVE_SLOT, KitManager.createLeaveItem());
    }

    private void restorePlayer(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
    }

    // ================= LOBBY / COUNTDOWN =================

    private void checkLobbyStart() {
        if (state != GameState.WAITING && state != GameState.NOT_CONFIGURED) {
            return;
        }
        int min = plugin.getConfig().getInt("min-players", 2);
        if (playerTeams.size() >= min && bothTeamsHavePlayers()) {
            startCountdown();
        } else {
            state = GameState.WAITING;
        }
    }

    private boolean bothTeamsHavePlayers() {
        long blackCount = playerTeams.values().stream().filter(t -> t == Team.BLACK).count();
        long whiteCount = playerTeams.values().stream().filter(t -> t == Team.WHITE).count();
        return blackCount > 0 && whiteCount > 0;
    }

    private void startCountdown() {
        state = GameState.COUNTDOWN;
        int max = plugin.getConfig().getInt("max-players", 16);
        boolean isFull = playerTeams.size() >= max;
        countdownSecondsLeft = isFull
                ? plugin.getConfig().getInt("lobby-countdown-fast", 10)
                : plugin.getConfig().getInt("lobby-countdown-min-reached", 10);

        broadcast(plugin.getConfig().getString("messages.countdown-start", "")
                .replace("%time%", String.valueOf(countdownSecondsLeft)));

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (countdownSecondsLeft <= 0) {
                countdownTask.cancel();
                countdownTask = null;
                startGame();
                return;
            }
            if (countdownSecondsLeft <= 5 || countdownSecondsLeft % 10 == 0) {
                broadcast("&e" + countdownSecondsLeft + "...");
            }
            countdownSecondsLeft--;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (state == GameState.COUNTDOWN) {
            state = GameState.WAITING;
            broadcast("&cPas assez de joueurs, le compte à rebours est annulé.");
        }
    }

    // ================= PARTIE =================

    private void startGame() {
        state = GameState.PLAYING;
        resetFrontier();
        resetStats();
        arenaSnapshot.restore();
        teleportAllToSpawns();
        applyColoredNames();
        startOffhandReplenishTask();
        startCaptureScheduler();

        plugin.getScoreboardManager().onGameStart(this);

        broadcast(plugin.getConfig().getString("messages.game-start", ""));
    }

    private void startOffhandReplenishTask() {
        if (offhandReplenishTask != null) {
            offhandReplenishTask.cancel();
        }
        offhandReplenishTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : playerTeams.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    KitManager.replenishOffhandBlocks(player);
                }
            }
        }, 40L, 40L);
    }

    private void applyColoredNames() {
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            Team team = playerTeams.get(uuid);
            String coloredName = team.getColor() + player.getName();
            player.setPlayerListName(coloredName);
            player.setDisplayName(coloredName);
        }
    }

    private void clearColoredNames(Iterable<UUID> uuids) {
        for (UUID uuid : uuids) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            player.setPlayerListName(player.getName());
            player.setDisplayName(player.getName());
        }
    }

    public boolean isFrozen(Player player) {
        return frozenPlayers.contains(player.getUniqueId());
    }

    private void freezeAllPlayers() {
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                frozenPlayers.add(uuid);
                player.setWalkSpeed(0f);
                player.setFlySpeed(0f);
                player.setVelocity(new Vector(0, 0, 0));
                player.setAllowFlight(false);
            }
        }
    }

    private void unfreezeAllPlayers() {
        for (UUID uuid : frozenPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setWalkSpeed(0.2f);
                player.setFlySpeed(0.1f);
                player.setVelocity(new Vector(0, 0, 0));
            }
        }
        frozenPlayers.clear();
    }

    /**
     * Détermine le spawn à utiliser pour une équipe donnée, en fonction de la position
     * actuelle de la ligne de front :
     * - frontier == 0 : tout le monde spawn au Mid.
     * - frontier > 0 (avantage NOIR) : NOIR spawn en avant, sur Base<frontier>_Blanc
     *   (sa base d'attaque la plus profonde) ; BLANC, repoussé, repart du Mid.
     * - frontier < 0 (avantage BLANC) : symétrique.
     */
    public Location getCurrentSpawnFor(Team team) {
        return resolveSpawnForRound(team);
    }

    private Location resolveSpawnForRound(Team team) {
        if (frontier == 0) {
            return arena.getMidSpawn(team);
        }
        if (frontier > 0) {
            if (team == Team.BLACK) {
                return arena.getBaseSpawn(Team.WHITE, frontier);
            } else {
                return arena.getMidSpawn(Team.WHITE);
            }
        } else {
            int k = -frontier;
            if (team == Team.WHITE) {
                return arena.getBaseSpawn(Team.BLACK, k);
            } else {
                return arena.getMidSpawn(Team.BLACK);
            }
        }
    }

    private void teleportAllToSpawns() {
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            Team team = playerTeams.get(uuid);
            Location spawn = resolveSpawnForRound(team);
            if (spawn != null) {
                player.teleport(spawn);
            }
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(20);
            player.setFoodLevel(20);
            KitManager.giveFullKit(player, team);
        }
    }

    private void teleportAllForRoundReset() {
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            Team team = playerTeams.get(uuid);
            Location spawn = resolveSpawnForRound(team);
            if (spawn != null) {
                player.teleport(spawn);
            }
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(20);
            player.setFoodLevel(20);
            KitManager.regiveGoldenApple(player);
        }
    }

    /**
     * Vérifie, pour chaque joueur en partie, s'il se trouve dans le but "actif" qui le
     * concerne (voir getActiveTargetZone) — c'est-à-dire la seule zone dont la capture
     * a un effet en ce moment, compte tenu de la position actuelle de la ligne de front.
     */
    public void checkCaptureZone() {
        if (state != GameState.PLAYING) return;

        for (UUID playerId : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;

            Team playerTeam = playerTeams.get(playerId);
            int[] target = getActiveTargetZone(playerTeam); // {ownerTeamOrdinal, baseIndex}
            if (target == null) continue;

            Team zoneOwner = Team.values()[target[0]];
            int baseIndex = target[1];

            if (arena.isInZoneGoal(zoneOwner, baseIndex, player.getLocation())) {
                handleZoneCapture(playerTeam);
                break; // Une seule percée à la fois
            }
        }
    }

    /**
     * Calcule la zone (propriétaire + index de base, 0 = Mid) que l'équipe donnée doit
     * atteindre pour marquer, compte tenu de l'état actuel de la ligne de front.
     * Renvoie null si, pour une raison quelconque, aucune cible n'est valide (ne devrait
     * pas arriver en jeu normal tant que basesPerSide >= 1).
     *
     * - Si personne n'a encore d'avantage (frontier == 0, tout le monde est au Mid) :
     *   la cible est le but du Mid situé du côté adverse (percer Mid en premier, ce qui
     *   envoie l'équipe qui perce dans la Base1 adverse).
     * - Si l'équipe est déjà en avance : sa cible est la PROCHAINE base adverse à percer
     *   (une de plus que sa profondeur actuelle).
     * - Si l'équipe est en train de défendre (l'adversaire a l'avantage) : sa cible est
     *   de RE-percer la zone que l'adversaire occupe actuellement, dans son propre
     *   territoire (voir handleZoneCapture pour l'effet, qui recule d'un cran seulement).
     */
    private int[] getActiveTargetZone(Team team) {
        int n = getBasesPerSide();
        int signedAdvantage = (team == Team.BLACK) ? frontier : -frontier;

        if (signedAdvantage >= 0) {
            int nextDepth = signedAdvantage + 1;
            if (nextDepth > n) return null; // ne devrait pas arriver (la partie aurait dû se terminer avant)
            Team enemy = team.opponent();
            if (signedAdvantage == 0) {
                // Personne n'a encore percé : la cible est le but du Mid, côté adverse.
                return new int[]{enemy.ordinal(), 0};
            }
            return new int[]{enemy.ordinal(), nextDepth};
        } else {
            int depthToReclaim = -signedAdvantage;
            return new int[]{team.ordinal(), depthToReclaim};
        }
    }

    private BukkitTask captureSchedulerTask;

    // Stats de la partie en cours (par équipe)
    private int blackKills = 0;
    private int blackDeaths = 0;
    private int whiteKills = 0;
    private int whiteDeaths = 0;

    // Stats de la partie en cours (par joueur)
    private Map<UUID, Integer> playerKills = new HashMap<>();
    private Map<UUID, Integer> playerDeaths = new HashMap<>();

    public void startCaptureScheduler() {
        if (captureSchedulerTask != null) {
            captureSchedulerTask.cancel();
        }
        captureSchedulerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkCaptureZone, 1L, 1L);
    }

    public void stopCaptureScheduler() {
        if (captureSchedulerTask != null) {
            captureSchedulerTask.cancel();
            captureSchedulerTask = null;
        }
    }

    /**
     * Applique l'effet d'une percée réussie par scoringTeam, annonce le résultat, et
     * soit fait gagner la partie (percée de la dernière Base adverse), soit lance le
     * compte à rebours du round suivant avec la nouvelle position de ligne de front.
     */
    private void handleZoneCapture(Team scoringTeam) {
        int n = getBasesPerSide();
        int signedAdvantage = (scoringTeam == Team.BLACK) ? frontier : -frontier;

        if (signedAdvantage >= 0) {
            int newDepth = signedAdvantage + 1;
            if (newDepth >= n) {
                // Percée de la dernière salle du vaisseau adverse : victoire immédiate.
                announceFinalBreachTitle(scoringTeam);
                endGame(scoringTeam);
                return;
            }

            frontier = (scoringTeam == Team.BLACK) ? newDepth : -newDepth;

            broadcast(plugin.getConfig().getString("messages.point-scored", "")
                    .replace("%team%", scoringTeam.getColoredName())
                    .replace("%depth%", String.valueOf(newDepth))
                    .replace("%total%", String.valueOf(n)));

            announceAdvanceTitle(scoringTeam, newDepth, n);
        } else {
            // scoringTeam défend : l'adversaire occupait sa Base<depthToReclaim>.
            // On ne renvoie PAS tout le monde au Mid d'un coup : l'adversaire est
            // simplement repoussé d'UNE zone (comme s'il avait perdu une percée).
            // S'il n'était qu'à Base1, ce recul d'un cran retombe naturellement à 0 (Mid).
            int depthToReclaim = -signedAdvantage;
            int newDepth = depthToReclaim - 1;
            Team enemy = scoringTeam.opponent();

            if (newDepth <= 0) {
                frontier = 0;

                broadcast(plugin.getConfig().getString("messages.point-reset", "")
                        .replace("%team%", scoringTeam.getColoredName()));

                announceResetTitle(scoringTeam);
            } else {
                frontier = (enemy == Team.BLACK) ? newDepth : -newDepth;

                broadcast(plugin.getConfig().getString("messages.point-pushback", "")
                        .replace("%team%", scoringTeam.getColoredName())
                        .replace("%depth%", String.valueOf(newDepth))
                        .replace("%total%", String.valueOf(n)));

                announcePushbackTitle(scoringTeam, newDepth, n);
            }
        }

        startRoundReset();
    }

    private void announceAdvanceTitle(Team scoringTeam, int depth, int total) {
        String title = scoringTeam.getColoredName() + " \u00a7lPERCÉE !";
        String subtitle = "\u00a7fZone " + depth + " \u00a77/ " + total;
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendTitle(title, subtitle, 5, 40, 10);
            }
        }
    }

    private void announceResetTitle(Team scoringTeam) {
        String title = scoringTeam.getColoredName() + " \u00a7lREPOUSSE L'ENNEMI !";
        String subtitle = "\u00a77Retour au centre du vaisseau";
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendTitle(title, subtitle, 5, 40, 10);
            }
        }
    }

    private void announcePushbackTitle(Team scoringTeam, int depth, int total) {
        String title = scoringTeam.getColoredName() + " \u00a7lREPOUSSE L'ENNEMI !";
        String subtitle = "\u00a77L'adversaire recule à Zone " + depth + " \u00a77/ " + total;
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendTitle(title, subtitle, 5, 40, 10);
            }
        }
    }

    private void announceFinalBreachTitle(Team scoringTeam) {
        String title = scoringTeam.getColoredName() + " \u00a7lATTEINT LE CŒUR DU VAISSEAU !";
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendTitle(title, "", 5, 40, 10);
            }
        }
    }

    private void startRoundReset() {
        state = GameState.ROUND_RESET;
        teleportAllForRoundReset();
        arenaSnapshot.restore();

        freezeAllPlayers();

        roundResetSecondsLeft = plugin.getConfig().getInt("round-reset-countdown", 5);

        roundResetTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (roundResetSecondsLeft <= 0) {
                if (roundResetTask != null) {
                    roundResetTask.cancel();
                    roundResetTask = null;
                }
                unfreezeAllPlayers();
                state = GameState.PLAYING;
                broadcast("&a&lÀ vous de jouer !");
                playSoundToAll(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.5f, 1.2f);
                return;
            }

            broadcast("&eProchain round dans &6" + roundResetSecondsLeft + "&e...");

            if (roundResetSecondsLeft <= 3) {
                float pitch = roundResetSecondsLeft == 1 ? 1.4f : 0.9f;
                playSoundToAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
            } else {
                playSoundToAll(Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 0.8f);
            }

            roundResetSecondsLeft--;
        }, 0L, 20L);
    }

    private void checkForfeit() {
        long blackCount = playerTeams.values().stream().filter(t -> t == Team.BLACK).count();
        long whiteCount = playerTeams.values().stream().filter(t -> t == Team.WHITE).count();

        if (blackCount == 0 && whiteCount > 0) {
            endGame(Team.WHITE);
        } else if (whiteCount == 0 && blackCount > 0) {
            endGame(Team.BLACK);
        } else if (blackCount == 0 && whiteCount == 0) {
            forceStopToLobby();
        }
    }

    private void endGame(Team winner) {
        if (state != GameState.PLAYING && state != GameState.ROUND_RESET) return;
        state = GameState.ENDING;

        if (roundResetTask != null) {
            roundResetTask.cancel();
            roundResetTask = null;
        }

        stopCaptureScheduler();

        int teamSize = Math.max(getPlayerCountForTeam(Team.BLACK), getPlayerCountForTeam(Team.WHITE));
        plugin.getStatsManager().addWin(winner, teamSize);

        for (Map.Entry<UUID, Team> entry : playerTeams.entrySet()) {
            UUID uuid = entry.getKey();
            Team team = entry.getValue();
            Player p  = Bukkit.getPlayer(uuid);
            String pName = p != null ? p.getName() : uuid.toString();
            boolean won = (team == winner);
            plugin.getStatsManager().addPlayerGameResult(uuid, pName, won, teamSize);
        }

        plugin.getLeaderboardManager().refreshAll();

        List<String> blackPlayers = new ArrayList<>();
        List<String> whitePlayers = new ArrayList<>();

        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                frozenPlayers.remove(uuid);
                player.setWalkSpeed(0.2f);
                player.setFlySpeed(0.1f);
                player.setGameMode(GameMode.SPECTATOR);

                if (playerTeams.get(uuid) == Team.BLACK) {
                    blackPlayers.add(player.getName());
                } else {
                    whitePlayers.add(player.getName());
                }
            }
        }

        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendTitle(
                    winner.getColoredName() + " \u00a7lVICTOIRE!",
                    "\u00a7f\u00a7oA atteint le bout du vaisseau adverse !",
                    10, 70, 20
                );

                StringBuilder message = new StringBuilder();
                message.append("\n");
                message.append(ChatColor.GOLD).append("------------------------------\n");
                message.append(ChatColor.GOLD).append("  VICTOIRE DES ").append(winner.getColoredName()).append("\n");
                message.append(ChatColor.GOLD).append("------------------------------\n");
                message.append("\n");
                message.append(ChatColor.DARK_GRAY).append("  NOIRS: ").append(blackPlayers.isEmpty() ? "(aucun)" : String.join(", ", blackPlayers)).append("\n");
                message.append(ChatColor.WHITE).append("  BLANCS: ").append(whitePlayers.isEmpty() ? "(aucun)" : String.join(", ", whitePlayers)).append("\n");

                player.sendMessage(message.toString());
            }
        }

        int delay = plugin.getConfig().getInt("restart-delay", 5);

        launchVictoryFireworks(winner, delay);

        Bukkit.getScheduler().runTaskLater(plugin, this::resetToLobby, delay * 20L);
    }

    private void forceStopToLobby() {
        if (roundResetTask != null) {
            roundResetTask.cancel();
            roundResetTask = null;
        }
        unfreezeAllPlayers();
        resetToLobby();
    }

    private void resetToLobby() {
        if (offhandReplenishTask != null) {
            offhandReplenishTask.cancel();
            offhandReplenishTask = null;
        }
        unfreezeAllPlayers();
        clearColoredNames(playerTeams.keySet());
        for (UUID uuid : new ArrayList<>(playerTeams.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                restorePlayer(player);
                plugin.getScoreboardManager().removeScoreboard(player);
                Location preLobbyLocation = preLobbyLocations.remove(uuid);
                if (plugin.getConfig().getBoolean("restore-pre-lobby-location", true) && preLobbyLocation != null) {
                    player.teleport(preLobbyLocation);
                } else if (plugin.getConfig().getBoolean("teleport-to-lobby-on-end", true) && arena.getLobbySpawn() != null) {
                    player.teleport(arena.getLobbySpawn());
                }
            }
        }
        playerTeams.clear();
        preLobbyLocations.clear();
        resetFrontier();
        state = arena.isFullyConfigured() ? GameState.WAITING : GameState.NOT_CONFIGURED;
    }

    public boolean forceStart() {
        if (state == GameState.PLAYING || state == GameState.ROUND_RESET) return false;
        if (!bothTeamsHavePlayers()) return false;
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        startGame();
        return true;
    }

    public void forceStop() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (roundResetTask != null) {
            roundResetTask.cancel();
            roundResetTask = null;
        }
        unfreezeAllPlayers();
        resetToLobby();
    }

    // ================= SONS & EFFETS =================

    private void playSoundToAll(Sound sound, float volume, float pitch) {
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.playSound(player.getLocation(), sound, volume, pitch);
            }
        }
    }

    private void launchVictoryFireworks(Team winner, int durationSeconds) {
        Color primary = (winner == Team.BLACK) ? Color.BLACK : Color.WHITE;
        Color secondary = (winner == Team.BLACK) ? Color.GRAY : Color.SILVER;

        int salves = Math.max(1, durationSeconds - 1);
        for (int i = 0; i < salves; i++) {
            long delayTicks = i * 30L;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (UUID uuid : playerTeams.keySet()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null) continue;
                    spawnFirework(player.getLocation().add(0, 1, 0), primary, secondary);
                }
            }, delayTicks);
        }
    }

    private void spawnFirework(Location location, Color primary, Color secondary) {
        Firework fw = location.getWorld().spawn(location, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.setPower(1);
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BURST)
                .withColor(primary)
                .withFade(secondary)
                .withFlicker()
                .withTrail()
                .build());
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.STAR)
                .withColor(secondary)
                .withFade(primary)
                .build());
        fw.setFireworkMeta(meta);
    }

    // ================= UTILITAIRE =================

    private void broadcast(String rawMessage) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String message = MessageUtil.format(prefix + rawMessage);
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    // ================= STATS PARTIE =================

    public int getBlackKills() { return blackKills; }
    public int getBlackDeaths() { return blackDeaths; }
    public int getWhiteKills() { return whiteKills; }
    public int getWhiteDeaths() { return whiteDeaths; }

    public int getPlayerKills(UUID uuid) { return playerKills.getOrDefault(uuid, 0); }
    public int getPlayerDeaths(UUID uuid) { return playerDeaths.getOrDefault(uuid, 0); }

    public void addKill(Team team) {
        if (team == Team.BLACK) blackKills++;
        else whiteKills++;
    }

    public void addPlayerKill(UUID uuid) {
        playerKills.put(uuid, playerKills.getOrDefault(uuid, 0) + 1);
    }

    public void addDeath(Team team) {
        if (team == Team.BLACK) blackDeaths++;
        else whiteDeaths++;
    }

    public void addPlayerDeath(UUID uuid) {
        playerDeaths.put(uuid, playerDeaths.getOrDefault(uuid, 0) + 1);
    }

    public void resetStats() {
        blackKills = 0;
        blackDeaths = 0;
        whiteKills = 0;
        whiteDeaths = 0;
        playerKills.clear();
        playerDeaths.clear();
    }
}
