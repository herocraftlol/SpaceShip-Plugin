package com.spaceship.plugin.tournament;

/**
 * États d'un match individuel dans le bracket.
 */
public enum MatchStatus {
    /** Un ou les deux adversaires ne sont pas encore connus (dépendent d'un match précédent). */
    WAITING_FOR_TEAMS,
    /** Les deux adversaires sont connus, le match attend une arène SpaceShip libre. */
    PENDING,
    /** Joueurs téléportés sur l'arène, compte à rebours ou partie en cours. */
    ONGOING,
    /** Match terminé, vainqueur désigné. */
    FINISHED,
    /** Match sans objet (un "BYE" : une seule équipe présente, qualifiée automatiquement). */
    BYE
}
