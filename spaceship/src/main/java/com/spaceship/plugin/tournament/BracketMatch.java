package com.spaceship.plugin.tournament;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Un match du bracket : oppose deux {@link TournamentTeam} (l'une pouvant être encore
 * inconnue si elle dépend du vainqueur d'un match précédent), sur une arène SpaceShip.
 */
public class BracketMatch {

    private final int round;
    private final int indexInRound;

    /** Slot 0 et slot 1 : les deux équipes qui s'affrontent (peuvent être null tant qu'inconnues). */
    private TournamentTeam teamA;
    private TournamentTeam teamB;

    private TournamentTeam winner;
    private MatchStatus status = MatchStatus.WAITING_FOR_TEAMS;
    private String arenaName;
    private long startedAt;

    /** Kills individuels enregistrés pendant ce match (pour stats/affichage). */
    private final Map<UUID, Integer> liveKills = new HashMap<>();

    public BracketMatch(int round, int indexInRound) {
        this.round = round;
        this.indexInRound = indexInRound;
    }

    public int getRound() {
        return round;
    }

    public int getIndexInRound() {
        return indexInRound;
    }

    public TournamentTeam getTeamA() {
        return teamA;
    }

    public void setTeamA(TournamentTeam teamA) {
        this.teamA = teamA;
        recomputeStatus();
    }

    public TournamentTeam getTeamB() {
        return teamB;
    }

    public void setTeamB(TournamentTeam teamB) {
        this.teamB = teamB;
        recomputeStatus();
    }

    /** Recalcule le statut du match dès qu'une équipe est renseignée (WAITING_FOR_TEAMS -> PENDING/BYE). */
    private void recomputeStatus() {
        if (status != MatchStatus.WAITING_FOR_TEAMS) return;
        if (teamA != null && teamB != null) {
            status = MatchStatus.PENDING;
        } else if (teamA != null || teamB != null) {
            // Un seul adversaire connu pour l'instant : on ne sait pas encore si ce sera
            // un BYE définitif (l'autre match amont peut encore désigner un 2e adversaire).
        }
    }

    /** Marque ce match comme BYE (une seule équipe présente) et désigne son vainqueur immédiat. */
    public void markBye(TournamentTeam onlyTeam) {
        this.teamA = onlyTeam;
        this.teamB = null;
        this.winner = onlyTeam;
        this.status = MatchStatus.BYE;
    }

    public TournamentTeam getWinner() {
        return winner;
    }

    public void setWinner(TournamentTeam winner) {
        this.winner = winner;
    }

    public MatchStatus getStatus() {
        return status;
    }

    public void setStatus(MatchStatus status) {
        this.status = status;
    }

    public String getArenaName() {
        return arenaName;
    }

    public void setArenaName(String arenaName) {
        this.arenaName = arenaName;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public Map<UUID, Integer> getLiveKills() {
        return liveKills;
    }

    public boolean isReady() {
        return teamA != null && teamB != null;
    }

    public String getDisplayVersus() {
        String a = teamA != null ? teamA.getDisplayName() : "?";
        String b = teamB != null ? teamB.getDisplayName() : "?";
        return a + " §7vs§r " + b;
    }
}
