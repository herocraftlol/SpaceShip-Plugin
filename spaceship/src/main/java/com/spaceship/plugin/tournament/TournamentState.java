package com.spaceship.plugin.tournament;

/**
 * États du cycle de vie d'un tournoi SpaceShip.
 */
public enum TournamentState {
    /** Inscriptions ouvertes, en attente du nombre de places ou d'un /sstournament start. */
    REGISTRATION,
    /** Bracket généré, matchs en cours de déroulement sur les arènes SpaceShip. */
    IN_PROGRESS,
    /** Tournoi terminé, un champion a été désigné. */
    FINISHED,
    /** Tournoi annulé par un admin avant la fin. */
    CANCELLED
}
