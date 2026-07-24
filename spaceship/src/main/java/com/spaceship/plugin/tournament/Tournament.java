package com.spaceship.plugin.tournament;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un tournoi SpaceShip : inscriptions, puis bracket à élimination directe où chaque
 * match est une vraie partie SpaceShip (capture de zones / domination), jouée sur une
 * arène SpaceShip existante et pilotée par le GameManager habituel du plugin.
 */
public class Tournament {

    private final String name;
    private final int teamSize;
    private final int maxSlots;
    private final UUID creator;
    /** Arène SpaceShip spécifique à utiliser pour tous les matchs, ou null pour piocher une arène libre. */
    private final String linkedArena;

    private TournamentState state = TournamentState.REGISTRATION;
    private final List<TournamentTeam> registered = new ArrayList<>();

    /** rounds.get(0) = premier tour, rounds.get(rounds.size()-1) = finale. */
    private List<List<BracketMatch>> rounds = new ArrayList<>();
    private int currentRoundIndex = 0;

    private TournamentTeam champion;

    public Tournament(String name, int teamSize, int maxSlots, UUID creator, String linkedArena) {
        this.name = name;
        this.teamSize = teamSize;
        this.maxSlots = maxSlots;
        this.creator = creator;
        this.linkedArena = linkedArena;
    }

    public String getName() {
        return name;
    }

    public int getTeamSize() {
        return teamSize;
    }

    public int getMaxSlots() {
        return maxSlots;
    }

    public UUID getCreator() {
        return creator;
    }

    public String getLinkedArena() {
        return linkedArena;
    }

    public TournamentState getState() {
        return state;
    }

    public void setState(TournamentState state) {
        this.state = state;
    }

    public List<TournamentTeam> getRegistered() {
        return registered;
    }

    public boolean isFull() {
        return registered.size() >= maxSlots;
    }

    public boolean isPlayerRegistered(UUID uuid) {
        for (TournamentTeam team : registered) {
            if (team.isMember(uuid)) return true;
        }
        return false;
    }

    public TournamentTeam findTeamOf(UUID uuid) {
        for (TournamentTeam team : registered) {
            if (team.isMember(uuid)) return team;
        }
        return null;
    }

    public List<List<BracketMatch>> getRounds() {
        return rounds;
    }

    public void setRounds(List<List<BracketMatch>> rounds) {
        this.rounds = rounds;
    }

    public int getCurrentRoundIndex() {
        return currentRoundIndex;
    }

    public void setCurrentRoundIndex(int currentRoundIndex) {
        this.currentRoundIndex = currentRoundIndex;
    }

    /** Renvoie la liste des matchs du tour courant, ou null si le bracket est terminé. */
    public List<BracketMatch> getCurrentRound() {
        if (currentRoundIndex < 0 || currentRoundIndex >= rounds.size()) return null;
        return rounds.get(currentRoundIndex);
    }

    public boolean isLastRound() {
        return currentRoundIndex == rounds.size() - 1;
    }

    public TournamentTeam getChampion() {
        return champion;
    }

    public void setChampion(TournamentTeam champion) {
        this.champion = champion;
    }
}
