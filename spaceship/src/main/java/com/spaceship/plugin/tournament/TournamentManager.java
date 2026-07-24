package com.spaceship.plugin.tournament;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.Team;
import com.spaceship.plugin.tournament.util.BracketUtil;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moteur central du système de tournoi SpaceShip : inscriptions, génération du bracket
 * à élimination directe, lancement de chaque match comme une vraie partie SpaceShip
 * (domination multi-zones) sur une arène SpaceShip existante et libre, avancement
 * automatique du bracket, gestion des forfaits, et désignation du champion final.
 *
 * Contrairement à HikaBrain, ce tournoi ne gère qu'un seul "moteur" de match : le jeu
 * SpaceShip lui-même. Chaque match du bracket réserve une arène SpaceShip existante
 * (voir {@link GameManager#reserveForTournament()}), y place les deux équipes du
 * tournoi (voir {@link GameManager#addPlayerToTeam}), puis force le démarrage.
 */
public class TournamentManager {

    private final SpaceShipPlugin plugin;

    private final Map<String, Tournament> tournaments = new LinkedHashMap<>();

    // Arènes SpaceShip actuellement occupées par un match de tournoi (partagées entre tous les tournois).
    private final Set<String> busyArenas = new HashSet<>();

    // Pour chaque arène réservée : quel tournoi/match y est en cours.
    private final Map<String, Tournament> matchTournamentByArena = new HashMap<>();
    private final Map<String, BracketMatch> matchByArena = new HashMap<>();

    // Tournois actuellement en "compte à rebours de préparation" entre deux tours : le
    // tour suivant est déjà connu (matchs PENDING) mais pas encore lancé, pour laisser
    // le temps aux joueurs de souffler / se re-préparer entre deux matchs.
    private final Set<String> tournamentsInPrepCountdown = new HashSet<>();
    private final Map<String, BukkitTask> prepCountdownTasks = new HashMap<>();

    private BukkitTask retryTask;

    public TournamentManager(SpaceShipPlugin plugin) {
        this.plugin = plugin;
        this.retryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::retryPendingMatches, 100L, 100L);
    }

    public void shutdown() {
        if (retryTask != null) retryTask.cancel();
        for (BukkitTask task : prepCountdownTasks.values()) {
            task.cancel();
        }
        prepCountdownTasks.clear();
    }

    /** Placeholder de persistance : les tournois en cours ne survivent pas à un redémarrage du serveur. */
    public void loadAll() {
    }

    /** Placeholder de persistance (voir {@link #loadAll()}). */
    public void saveAll() {
    }

    public Collection<Tournament> getAll() {
        return tournaments.values();
    }

    public Tournament get(String name) {
        if (name == null) return null;
        return tournaments.get(name.toLowerCase(Locale.ROOT));
    }

    // ================= CRÉATION / INSCRIPTION =================

    public enum CreateResult {
        OK, ALREADY_EXISTS, INVALID_SIZE, INVALID_ARENA
    }

    public CreateResult create(String name, int teamSize, int maxSlots, UUID creator, String linkedArena) {
        String key = name.toLowerCase(Locale.ROOT);
        if (tournaments.containsKey(key)) {
            return CreateResult.ALREADY_EXISTS;
        }
        if (maxSlots < 2 || teamSize < 1) {
            return CreateResult.INVALID_SIZE;
        }
        if (linkedArena != null && plugin.getArenaManager().get(linkedArena) == null) {
            return CreateResult.INVALID_ARENA;
        }
        Tournament tournament = new Tournament(name, teamSize, maxSlots, creator, linkedArena);
        tournaments.put(key, tournament);
        return CreateResult.OK;
    }

    public enum JoinResult {
        OK, NOT_FOUND, NOT_OPEN, FULL, ALREADY_REGISTERED, TEAM_FULL, TEAM_NAME_REQUIRED
    }

    public JoinResult join(String tournamentName, Player player, String teamTag) {
        Tournament tournament = get(tournamentName);
        if (tournament == null) return JoinResult.NOT_FOUND;
        if (tournament.getState() != TournamentState.REGISTRATION) return JoinResult.NOT_OPEN;
        if (tournament.isPlayerRegistered(player.getUniqueId())) return JoinResult.ALREADY_REGISTERED;

        if (tournament.getTeamSize() == 1) {
            if (tournament.isFull()) return JoinResult.FULL;
            TournamentTeam team = TournamentTeam.soloOf(player.getUniqueId(), player.getName());
            tournament.getRegistered().add(team);
            broadcastToTournament(tournament, "&a" + player.getName() + " &7a rejoint le tournoi &f(" +
                    tournament.getRegistered().size() + "/" + tournament.getMaxSlots() + ")");
            return JoinResult.OK;
        }

        if (teamTag == null || teamTag.isBlank()) {
            return JoinResult.TEAM_NAME_REQUIRED;
        }
        for (TournamentTeam team : tournament.getRegistered()) {
            if (team.getTag().equalsIgnoreCase(teamTag)) {
                if (team.getMembers().size() >= tournament.getTeamSize()) {
                    return JoinResult.TEAM_FULL;
                }
                team.addMember(player.getUniqueId(), player.getName());
                broadcastToTournament(tournament, "&a" + player.getName() + " &7a rejoint l'équipe &f" + teamTag);
                return JoinResult.OK;
            }
        }
        if (tournament.isFull()) return JoinResult.FULL;
        TournamentTeam team = new TournamentTeam(teamTag);
        team.addMember(player.getUniqueId(), player.getName());
        tournament.getRegistered().add(team);
        broadcastToTournament(tournament, "&a" + player.getName() + " &7a créé l'équipe &f" + teamTag + "&7 (" +
                tournament.getRegistered().size() + "/" + tournament.getMaxSlots() + ")");
        return JoinResult.OK;
    }

    /** Retire un joueur de son inscription (uniquement possible avant le début du tournoi). */
    public boolean leave(String tournamentName, Player player) {
        Tournament tournament = get(tournamentName);
        if (tournament == null || tournament.getState() != TournamentState.REGISTRATION) return false;
        return removePlayerFromRegistration(tournament, player.getUniqueId());
    }

    private boolean removePlayerFromRegistration(Tournament tournament, UUID uuid) {
        TournamentTeam team = tournament.findTeamOf(uuid);
        if (team == null) return false;
        int idx = team.getMembers().indexOf(uuid);
        team.getMembers().remove(idx);
        team.getMemberNames().remove(idx);
        if (team.getMembers().isEmpty()) {
            tournament.getRegistered().remove(team);
        }
        return true;
    }

    /** Appelé quand un joueur inscrit se déconnecte, pour ne pas laisser de tournoi bloqué. */
    public void handlePlayerQuit(Player player) {
        for (Tournament tournament : tournaments.values()) {
            if (tournament.getState() == TournamentState.REGISTRATION) {
                removePlayerFromRegistration(tournament, player.getUniqueId());
            }
        }
        // Les forfaits en plein match sont déjà gérés naturellement : le joueur fait partie
        // des playerTeams de l'arène SpaceShip réservée, donc PlayerConnectionListener +
        // GameManager#checkForfeit() déclenchent déjà endGame(), qui appelle notre callback.
    }

    public boolean cancel(String tournamentName) {
        Tournament tournament = get(tournamentName);
        if (tournament == null) return false;
        tournament.setState(TournamentState.CANCELLED);
        cancelPrepCountdown(tournament.getName());
        releaseAllArenasOf(tournament);
        broadcastToTournament(tournament, "&cLe tournoi &f" + tournament.getName() + " &ca été annulé.");
        tournaments.remove(tournament.getName().toLowerCase(Locale.ROOT));
        return true;
    }

    private void releaseAllArenasOf(Tournament tournament) {
        for (Map.Entry<String, Tournament> e : new HashMap<>(matchTournamentByArena).entrySet()) {
            if (e.getValue() == tournament) {
                GameManager gm = plugin.getArenaManager().get(e.getKey());
                if (gm != null) {
                    gm.releaseTournamentReservation();
                    gm.forceStop();
                }
                busyArenas.remove(e.getKey());
                matchTournamentByArena.remove(e.getKey());
                matchByArena.remove(e.getKey());
            }
        }
    }

    // ================= DÉMARRAGE / BRACKET =================

    public enum StartResult {
        OK, NOT_FOUND, NOT_ENOUGH_TEAMS, ALREADY_STARTED
    }

    public StartResult start(String tournamentName) {
        Tournament tournament = get(tournamentName);
        if (tournament == null) return StartResult.NOT_FOUND;
        if (tournament.getState() != TournamentState.REGISTRATION) return StartResult.ALREADY_STARTED;
        if (tournament.getRegistered().size() < 2) return StartResult.NOT_ENOUGH_TEAMS;

        List<List<BracketMatch>> rounds = BracketUtil.generateBracket(tournament.getRegistered());
        tournament.setRounds(rounds);
        tournament.setCurrentRoundIndex(0);
        tournament.setState(TournamentState.IN_PROGRESS);

        broadcastToTournament(tournament, "&a&lLe tournoi " + tournament.getName() + " commence !");
        announceRound(tournament);
        tryLaunchPendingMatches(tournament);
        return StartResult.OK;
    }

    private void announceRound(Tournament tournament) {
        List<BracketMatch> round = tournament.getCurrentRound();
        if (round == null) return;
        String roundName = BracketUtil.roundName(round.size(), tournament.isLastRound());
        broadcastToTournament(tournament, "&e⏳ &f" + roundName + " &e:");
        for (BracketMatch match : round) {
            if (match.getStatus() == MatchStatus.BYE) {
                broadcastToTournament(tournament, "&7  " + match.getWinner().getDisplayName()
                        + " &7est qualifié(e) automatiquement (BYE)");
            } else if (match.isReady()) {
                broadcastToTournament(tournament, "&7  " + match.getDisplayVersus());
            }
        }
    }

    private void retryPendingMatches() {
        for (Tournament tournament : tournaments.values()) {
            if (tournament.getState() == TournamentState.IN_PROGRESS) {
                tryLaunchPendingMatches(tournament);
            }
        }
    }

    private void tryLaunchPendingMatches(Tournament tournament) {
        if (tournamentsInPrepCountdown.contains(tournament.getName())) return;
        List<BracketMatch> round = tournament.getCurrentRound();
        if (round == null) return;
        for (BracketMatch match : round) {
            if (match.getStatus() == MatchStatus.PENDING) {
                launchMatch(tournament, match);
            }
        }
    }

    // ================= LANCEMENT D'UN MATCH =================

    private void launchMatch(Tournament tournament, BracketMatch match) {
        GameManager gm = findFreeArena(tournament);
        if (gm == null) {
            return; // Aucune arène SpaceShip libre pour l'instant, retenté par retryPendingMatches().
        }

        final GameManager arenaGm = gm;
        busyArenas.add(arenaGm.getName());
        arenaGm.reserveForTournament();
        match.setArenaName(arenaGm.getName());
        match.setStatus(MatchStatus.ONGOING);
        match.setStartedAt(System.currentTimeMillis());
        matchTournamentByArena.put(arenaGm.getName(), tournament);
        matchByArena.put(arenaGm.getName(), match);

        TournamentTeam teamA = match.getTeamA();
        TournamentTeam teamB = match.getTeamB();

        int onlineA = 0, onlineB = 0;
        for (UUID uuid : teamA.getMembers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) { arenaGm.addPlayerToTeam(p, Team.BLACK); onlineA++; }
        }
        for (UUID uuid : teamB.getMembers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) { arenaGm.addPlayerToTeam(p, Team.WHITE); onlineB++; }
        }

        if (onlineA == 0 || onlineB == 0) {
            // Un camp est totalement absent au moment du lancement : forfait immédiat.
            TournamentTeam winner = onlineA == 0 ? teamB : teamA;
            broadcastToTournament(tournament, "&c" + (onlineA == 0 ? teamA.getDisplayName() : teamB.getDisplayName())
                    + " &7est déclaré(e) forfait (aucun joueur en ligne).");
            arenaGm.releaseTournamentReservation();
            arenaGm.forceStop();
            busyArenas.remove(arenaGm.getName());
            matchTournamentByArena.remove(arenaGm.getName());
            matchByArena.remove(arenaGm.getName());
            finishMatch(tournament, match, winner);
            return;
        }

        arenaGm.setTournamentEndCallback(winnerColor -> onMatchEnd(tournament, match, arenaGm, winnerColor));

        broadcastToTournament(tournament, "&e⚔ Match lancé : &f" + match.getDisplayVersus()
                + " &7sur l'arène SpaceShip &f" + arenaGm.getName());

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (arenaGm.getState() == GameState.WAITING || arenaGm.getState() == GameState.COUNTDOWN) {
                arenaGm.forceStart();
            }
        }, 20L);
    }

    /** Cherche une arène SpaceShip libre et prête (configurée, en attente) pour lancer un match de ce tournoi. */
    private GameManager findFreeArena(Tournament tournament) {
        if (tournament.getLinkedArena() != null) {
            GameManager candidate = plugin.getArenaManager().get(tournament.getLinkedArena());
            if (candidate != null && !busyArenas.contains(candidate.getName())
                    && candidate.getArena().isFullyConfigured() && candidate.getState() == GameState.WAITING) {
                return candidate;
            }
            return null;
        }
        for (GameManager candidate : plugin.getArenaManager().getAll()) {
            if (!busyArenas.contains(candidate.getName())
                    && candidate.getArena().isFullyConfigured()
                    && candidate.getState() == GameState.WAITING) {
                return candidate;
            }
        }
        return null;
    }

    private void onMatchEnd(Tournament tournament, BracketMatch match, GameManager gm, Team winnerColor) {
        TournamentTeam winner = winnerColor == Team.BLACK ? match.getTeamA() : match.getTeamB();

        for (Map.Entry<UUID, Team> entry : gm.getPlayerTeams().entrySet()) {
            int kills = gm.getPlayerKills(entry.getKey());
            if (kills > 0) {
                match.getLiveKills().put(entry.getKey(), kills);
            }
        }

        finishMatch(tournament, match, winner);

        int delay = plugin.getConfig().getInt("restart-delay", 5) + 2;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            gm.releaseTournamentReservation();
            busyArenas.remove(gm.getName());
            matchTournamentByArena.remove(gm.getName());
            matchByArena.remove(gm.getName());
        }, delay * 20L);
    }

    /** Enregistre le résultat d'un match (normal ou forfait) et avance le bracket. */
    private void finishMatch(Tournament tournament, BracketMatch match, TournamentTeam winner) {
        match.setWinner(winner);
        match.setStatus(MatchStatus.FINISHED);

        broadcastToTournament(tournament, "&a&l" + winner.getDisplayName() + " &a&lremporte le match !");

        int roundIdx = tournament.getCurrentRoundIndex();
        BracketUtil.placeInNextRound(tournament.getRounds(), roundIdx, match);

        checkRoundCompletion(tournament);
    }

    private void checkRoundCompletion(Tournament tournament) {
        List<BracketMatch> round = tournament.getCurrentRound();
        if (round == null) return;
        for (BracketMatch m : round) {
            if (m.getStatus() != MatchStatus.FINISHED && m.getStatus() != MatchStatus.BYE) {
                return; // Il reste des matchs en cours dans ce tour.
            }
        }

        if (tournament.isLastRound()) {
            TournamentTeam champion = round.get(0).getWinner();
            finishTournament(tournament, champion);
            return;
        }

        tournament.setCurrentRoundIndex(tournament.getCurrentRoundIndex() + 1);
        BracketUtil.propagateByes(tournament.getRounds());

        // Traite immédiatement les BYE en cascade du nouveau tour courant.
        List<BracketMatch> newRound = tournament.getCurrentRound();
        boolean onlyByesLeft = newRound != null && !newRound.isEmpty();
        if (newRound != null) {
            for (BracketMatch m : newRound) {
                if (m.getStatus() != MatchStatus.BYE && m.getStatus() != MatchStatus.FINISHED) {
                    onlyByesLeft = false;
                    break;
                }
            }
        }
        if (onlyByesLeft) {
            // Tour entier déjà résolu par BYE en cascade : on avance directement encore.
            checkRoundCompletion(tournament);
            return;
        }

        startPrepCountdown(tournament);
    }

    private void startPrepCountdown(Tournament tournament) {
        String name = tournament.getName();
        cancelPrepCountdown(name);
        tournamentsInPrepCountdown.add(name);

        int prepSeconds = plugin.getConfig().getInt("tournament.prep-countdown", 15);
        announceRound(tournament);
        broadcastToTournament(tournament, "&e⏳ &fPréparez-vous, téléportation dans &f" + prepSeconds + "s&e !");

        int[] remaining = {prepSeconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remaining[0]--;
            int left = remaining[0];
            if (left <= 0) {
                tournamentsInPrepCountdown.remove(name);
                BukkitTask self = prepCountdownTasks.remove(name);
                if (self != null) self.cancel();
                broadcastToTournament(tournament, "&a&lC'est parti !");
                tryLaunchPendingMatches(tournament);
                return;
            }
            if (left <= 5 || left == 10) {
                broadcastToTournament(tournament, "&eTéléportation dans &f" + left + "&e...");
            }
        }, 20L, 20L);
        prepCountdownTasks.put(name, task);
    }

    private void cancelPrepCountdown(String tournamentName) {
        tournamentsInPrepCountdown.remove(tournamentName);
        BukkitTask task = prepCountdownTasks.remove(tournamentName);
        if (task != null) task.cancel();
    }

    private void finishTournament(Tournament tournament, TournamentTeam champion) {
        tournament.setChampion(champion);
        tournament.setState(TournamentState.FINISHED);
        cancelPrepCountdown(tournament.getName());

        broadcastToTournament(tournament, "&6&l========================================");
        broadcastToTournament(tournament, "&6&l   CHAMPION DU TOURNOI : " + champion.getDisplayName());
        broadcastToTournament(tournament, "&6&l========================================");

        tournaments.remove(tournament.getName().toLowerCase(Locale.ROOT));
    }

    // ================= UTILITAIRES =================

    private void broadcastToTournament(Tournament tournament, String rawMessage) {
        String message = MessageUtil.format("&8[&bTournoi&8] &r" + rawMessage);
        Set<UUID> notified = new HashSet<>();
        for (TournamentTeam team : tournament.getRegistered()) {
            for (UUID uuid : team.getMembers()) {
                if (!notified.add(uuid)) continue;
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.sendMessage(message);
            }
        }
    }

    public List<String> getNames() {
        return new ArrayList<>(tournaments.keySet());
    }
}
