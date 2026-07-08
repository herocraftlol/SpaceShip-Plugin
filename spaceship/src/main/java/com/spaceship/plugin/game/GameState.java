package com.spaceship.plugin.game;

/**
 * États possibles du cycle de vie d'une partie SpaceShip.
 */
public enum GameState {
    /** Le plugin attend que la map soit configurée (points manquants). */
    NOT_CONFIGURED,
    /** En attente de joueurs dans le lobby. */
    WAITING,
    /** Compte à rebours en cours avant le début de la partie. */
    COUNTDOWN,
    /** Partie en cours, les joueurs peuvent capturer la zone adverse. */
    PLAYING,
    /** Un point vient d'être marqué : compte à rebours avant le round suivant, capture désactivée. */
    ROUND_RESET,
    /** Partie terminée, affichage des résultats avant reset. */
    ENDING
}
