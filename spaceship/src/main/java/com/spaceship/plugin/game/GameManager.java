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

    /**
     * Vrai quand une équipe a touché le goal de Base1 adverse et que tout le monde a été
     * téléporté dans la salle Base2 adverse, en attente d'un score final ou d'un recul.
     * Le frontier ne change PAS encore — il ne change qu'au moment du score en Base2.
     * L'équipe qui attaque est celle dont le signe du frontier va dans sa direction d'avance.
     */
    private boolean inTransitToBase2 = false;
    /** Équipe qui est en phase de transit vers la Base2 adverse (null si inTransitToBase2 == false). */
    private Team transitAttackingTeam = null;

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
        inTransitToBase2 = false;
        transitAttackingTeam = null;
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
     * Renvoie le spawn à utiliser pour {@code team} au début d'un round.
     * <p>
     * Tout le monde se téléporte dans la MÊME salle (déterminée par {@code frontier}).
     * Dans cette salle, chaque équipe a ses propres points de spawn.
     * <pre>
     *   frontier =  0  →  salle "mid"
     *   frontier = +k  →  salle "base{k}white"  (noir avance en territoire blanc)
     *   frontier = -k  →  salle "base{k}black"  (blanc avance en territoire noir)
     * </pre>
     */
    public Location getCurrentSpawnFor(Team team) {
        if (inTransitToBase2) {
            // En phase de transit, les joueurs sont physiquement en Base2 adverse
            String base2Room = getBase2RoomFor(transitAttackingTeam);
            Location loc = arena.getRoomSpawn(base2Room, team);
            if (loc != null) return loc;
        }
        return resolveSpawnForRound(team);
    }

    private Location resolveSpawnForRound(Team team) {
        String roomId = Arena.frontierToRoom(frontier);
        return arena.getRoomSpawn(roomId, team);
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
     * Vérifie chaque tick si un joueur se trouve dans le goal actif.
     * <p>
     * Deux phases possibles :
     * <ul>
     *   <li><b>Phase normale</b> ({@code inTransitToBase2 == false}) : le goal actif est celui
     *       de la salle courante ({@code frontier}). Si c'est une salle Base1 adverse et que
     *       l'équipe attaquante touche le goal, on entre en transit vers Base2 (sans scorer).</li>
     *   <li><b>Phase transit</b> ({@code inTransitToBase2 == true}) : les joueurs sont en Base2
     *       adverse. L'équipe attaquante peut scorer (→ victoire), l'adversaire peut aussi scorer
     *       (→ recul en Base1, fin du transit).</li>
     * </ul>
     */
    public void checkCaptureZone() {
        if (state != GameState.PLAYING) return;

        int n = getBasesPerSide();

        if (inTransitToBase2) {
            // ── Phase transit : on est physiquement dans la salle Base2 adverse ──
            // La salle Base2 adverse par rapport à transitAttackingTeam
            String base2Room = getBase2RoomFor(transitAttackingTeam);

            for (UUID playerId : playerTeams.keySet()) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) continue;

                Team playerTeam = playerTeams.get(playerId);
                Team targetGoalTeam = playerTeam.opponent();

                if (arena.isInRoomGoal(base2Room, targetGoalTeam, player.getLocation())) {
                    handleBase2Capture(playerTeam);
                    break;
                }
            }
        } else {
            // ── Phase normale : salle courante selon frontier ──
            String currentRoom = Arena.frontierToRoom(frontier);

            for (UUID playerId : playerTeams.keySet()) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) continue;

                Team playerTeam = playerTeams.get(playerId);
                Team targetGoalTeam = playerTeam.opponent();

                if (arena.isInRoomGoal(currentRoom, targetGoalTeam, player.getLocation())) {
                    // Vérifie si c'est un goal de Base1 adverse (avant-dernière salle)
                    int currentDepth = Math.abs(frontier); // 0=mid, 1=base1, 2=base2...
                    boolean isBase1 = (currentDepth == n - 1);

                    if (isBase1) {
                        // L'équipe attaquante est celle qui vient de toucher le goal adverse
                        // mais seulement si elle est bien l'équipe qui avance (ou neutre)
                        boolean attackerIsScoring =
                                (playerTeam == Team.BLACK && frontier >= 0) ||
                                (playerTeam == Team.WHITE && frontier <= 0);

                        if (attackerIsScoring) {
                            // Transit vers Base2 sans changer le frontier
                            handleTransitToBase2(playerTeam);
                        } else {
                            // L'adversaire repousse depuis Base1 → comportement normal
                            handleZoneCapture(playerTeam);
                        }
                    } else {
                        handleZoneCapture(playerTeam);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Retourne l'identifiant de la salle Base2 adverse pour {@code attackingTeam}.
     * Noir attaque en territoire Blanc → "base2white" ; Blanc attaque → "base2black".
     */
    private String getBase2RoomFor(Team attackingTeam) {
        int n = getBasesPerSide();
        if (attackingTeam == Team.BLACK) {
            return "base" + n + "white";
        } else {
            return "base" + n + "black";
        }
    }

    /**
     * Déclenché quand {@code attackingTeam} touche le goal de Base1 adverse alors qu'elle avance.
     * On téléporte tout le monde dans la salle Base2 adverse sans changer le frontier.
     * La phase de transit commence.
     */
    private void handleTransitToBase2(Team attackingTeam) {
        inTransitToBase2 = true;
        transitAttackingTeam = attackingTeam;

        String base2Room = getBase2RoomFor(attackingTeam);
        String base2Label = (attackingTeam == Team.BLACK) ? "Base2 Blanc" : "Base2 Noir";

        String title = attackingTeam.getColoredName() + " \u00a7lPERCÉE !";
        String subtitle = "\u00a77Assaut final : " + base2Label;
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendTitle(title, subtitle, 5, 40, 10);
            }
        }

        startRoundResetToRoom(base2Room);
    }

    /**
     * Déclenché quand un joueur touche un goal en phase de transit (Base2 adverse).
     * <ul>
     *   <li>L'équipe attaquante marque → victoire immédiate.</li>
     *   <li>L'adversaire marque → fin du transit, retour en Base1 (frontier inchangé).</li>
     * </ul>
     */
    private void handleBase2Capture(Team scoringTeam) {
        if (scoringTeam == transitAttackingTeam) {
            // L'attaquant marque en Base2 → victoire
            // On pousse le frontier pour déclencher endGame proprement
            if (scoringTeam == Team.BLACK) {
                frontier++;
            } else {
                frontier--;
            }
            endGame(scoringTeam);
        } else {
            // L'adversaire repousse depuis Base2 → retour en Base1
            inTransitToBase2 = false;
            Team attacker = transitAttackingTeam;
            transitAttackingTeam = null;

            // La salle courante redevient la Base1 (frontier inchangé)
            String base1Room = Arena.frontierToRoom(frontier);
            String base1Label = (attacker == Team.BLACK) ? "Base1 Blanc" : "Base1 Noir";

            String title = scoringTeam.getColoredName() + " \u00a7lREPOUSSÉ !";
            String subtitle = "\u00a77Retour en " + base1Label;
            for (UUID uuid : playerTeams.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.sendTitle(title, subtitle, 5, 40, 10);
                }
            }

            startRoundResetToRoom(base1Room);
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
     * Gère un but marqué par scoringTeam (un joueur a touché le goal adverse).
     * <p>
     * Logique de domination (5 salles, N=2 bases par équipe) :
     * <pre>
     *   Base2_Noir | Base1_Noir | Mid | Base1_Blanc | Base2_Blanc
     *   frontier :    -2           -1     0             +1           +2
     * </pre>
     * - Si NOIR marque et frontier >= 0 (neutre ou avantage Noir) → frontier++
     *   → Si frontier atteint +basesPerSide : VICTOIRE de Noir
     * - Si NOIR marque et frontier < 0 (Blanc était en avance) → frontier++ (recul d'un cran)
     *   → Blanc perd une zone, on peut revenir au Mid si frontier revient à 0
     * - Symétrique pour BLANC (frontier--)
     * <p>
     * La map est restaurée (snapshot) et tout le monde est téléporté dans
     * la salle correspondant au nouveau frontier.
     */
    private void handleZoneCapture(Team scoringTeam) {
        int n = getBasesPerSide();

        if (scoringTeam == Team.BLACK) {
            frontier++;
        } else {
            frontier--;
        }

        // Vérification victoire
        if (frontier >= n) {
            // NOIR a percé toutes les bases blanches
            endGame(Team.BLACK);
            return;
        }
        if (frontier <= -n) {
            // BLANC a percé toutes les bases noires
            endGame(Team.WHITE);
            return;
        }

        // Annonce du but et de la nouvelle salle
        int depth = Math.abs(frontier); // 0 = Mid, 1 = Base1, 2 = Base2...
        String roomLabel;
        if (frontier == 0) {
            roomLabel = "Mid";
        } else if (frontier > 0) {
            roomLabel = "Base" + frontier + " Blanc";
        } else {
            roomLabel = "Base" + (-frontier) + " Noir";
        }

        broadcast(plugin.getConfig().getString("messages.point-scored", "")
                .replace("%team%", scoringTeam.getColoredName())
                .replace("%depth%", String.valueOf(depth))
                .replace("%total%", String.valueOf(n)));

        announceGoalTitle(scoringTeam, roomLabel);
        startRoundReset();
    }

    private void announceGoalTitle(Team scoringTeam, String roomLabel) {
        String title = scoringTeam.getColoredName() + " \u00a7lBUT !";
        String subtitle = "\u00a77Repositionnement : " + roomLabel;
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendTitle(title, subtitle, 5, 40, 10);
            }
        }
    }

    private void startRoundReset() {
        state = GameState.ROUND_RESET;
        // Restaurer la map (clear les blocs posés) AVANT de téléporter les joueurs
        arenaSnapshot.restore();
        teleportAllForRoundReset();

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

    /**
     * Identique à {@link #startRoundReset()} mais téléporte tout le monde dans {@code targetRoom}
     * au lieu de la salle déduite du frontier. Utilisé pour le transit Base1→Base2 et le retour
     * Base2→Base1 sans changer le frontier.
     */
    private void startRoundResetToRoom(String targetRoom) {
        state = GameState.ROUND_RESET;
        arenaSnapshot.restore();
        teleportAllToRoom(targetRoom);

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

    /** Téléporte tous les joueurs dans la salle {@code roomId} avec restauration HP/kit. */
    private void teleportAllToRoom(String roomId) {
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            Team team = playerTeams.get(uuid);
            Location spawn = arena.getRoomSpawn(roomId, team);
            if (spawn != null) {
                player.teleport(spawn);
            }
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(20);
            player.setFoodLevel(20);
            KitManager.regiveGoldenApple(player);
        }
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
