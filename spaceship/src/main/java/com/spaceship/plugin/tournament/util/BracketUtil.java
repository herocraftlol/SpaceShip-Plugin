package com.spaceship.plugin.tournament.util;

import com.spaceship.plugin.tournament.BracketMatch;
import com.spaceship.plugin.tournament.MatchStatus;
import com.spaceship.plugin.tournament.TournamentTeam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Génère un bracket à élimination directe (simple élimination) à partir d'une liste
 * d'équipes inscrites, avec gestion automatique des "BYE" (qualification directe)
 * quand le nombre d'équipes n'est pas une puissance de 2.
 */
public final class BracketUtil {

    private BracketUtil() {
    }

    /**
     * Construit tous les tours du bracket. Le premier tour ({@code rounds.get(0)}) a ses
     * deux équipes déjà connues (ou une seule + BYE). Les tours suivants ont leurs matchs
     * créés à l'avance mais avec des équipes null, à remplir au fur et à mesure que les
     * matchs précédents se terminent (voir TournamentManager#advanceWinner).
     */
    public static List<List<BracketMatch>> generateBracket(List<TournamentTeam> registeredTeams) {
        List<TournamentTeam> teams = new ArrayList<>(registeredTeams);
        Collections.shuffle(teams);

        int size = nextPowerOfTwo(teams.size());
        List<List<BracketMatch>> rounds = new ArrayList<>();

        // ── Premier tour : on associe les équipes 2 par 2, en distribuant les BYE ──
        int firstRoundMatches = size / 2;
        List<BracketMatch> round1 = new ArrayList<>();
        int teamIndex = 0;
        for (int i = 0; i < firstRoundMatches; i++) {
            BracketMatch match = new BracketMatch(1, i);
            TournamentTeam a = teamIndex < teams.size() ? teams.get(teamIndex++) : null;
            TournamentTeam b = teamIndex < teams.size() ? teams.get(teamIndex++) : null;

            if (a != null && b != null) {
                match.setTeamA(a);
                match.setTeamB(b);
            } else if (a != null) {
                match.markBye(a);
            } else if (b != null) {
                match.markBye(b);
            }
            round1.add(match);
        }
        rounds.add(round1);

        // ── Tours suivants : squelette vide, rempli au fil des résultats ──
        int matchesInRound = firstRoundMatches / 2;
        int roundNumber = 2;
        while (matchesInRound >= 1) {
            List<BracketMatch> round = new ArrayList<>();
            for (int i = 0; i < matchesInRound; i++) {
                round.add(new BracketMatch(roundNumber, i));
            }
            rounds.add(round);
            matchesInRound /= 2;
            roundNumber++;
        }

        // Propage immédiatement les BYE du tour 1 vers le tour 2 (peut créer une cascade
        // de BYE en tour 2 si les deux matchs sources étaient déjà des BYE).
        propagateByes(rounds);

        return rounds;
    }

    /**
     * Fait avancer un vainqueur de match vers le match correspondant du tour suivant, et
     * gère la cascade de BYE si l'adversaire de ce tour suivant est lui-même déjà connu
     * comme qualifié sans jouer (BYE).
     */
    public static void propagateByes(List<List<BracketMatch>> rounds) {
        for (int r = 0; r < rounds.size() - 1; r++) {
            for (BracketMatch match : rounds.get(r)) {
                if (match.getStatus() == MatchStatus.BYE && match.getWinner() != null) {
                    placeInNextRound(rounds, r, match);
                }
            }
        }
    }

    /** Place le vainqueur de {@code match} (au tour {@code roundIndex}, 0-based) dans le match suivant. */
    public static void placeInNextRound(List<List<BracketMatch>> rounds, int roundIndex, BracketMatch match) {
        if (roundIndex + 1 >= rounds.size()) return; // c'était la finale
        List<BracketMatch> nextRound = rounds.get(roundIndex + 1);
        int nextIndex = match.getIndexInRound() / 2;
        if (nextIndex >= nextRound.size()) return;
        BracketMatch nextMatch = nextRound.get(nextIndex);

        if (match.getIndexInRound() % 2 == 0) {
            nextMatch.setTeamA(match.getWinner());
        } else {
            nextMatch.setTeamB(match.getWinner());
        }

        // Si l'autre côté de ce match suivant est vide et qu'il n'y a pas d'autre match en
        // attente qui pourrait encore le remplir (l'autre match source de ce tour est déjà
        // BYE/FINISHED avec un seul survivant), on ne force pas de BYE ici : on laisse
        // TournamentManager gérer le cas "un seul adversaire présent au lancement".
    }

    private static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p *= 2;
        return Math.max(p, 2);
    }

    /** Nom lisible d'un tour selon le nombre de matchs qu'il contient. */
    public static String roundName(int matchesInRound, boolean isFinal) {
        if (isFinal || matchesInRound == 1) return "Finale";
        if (matchesInRound == 2) return "Demi-finales";
        if (matchesInRound == 4) return "Quarts de finale";
        if (matchesInRound == 8) return "Huitièmes de finale";
        return "Tour à " + (matchesInRound * 2) + " participants";
    }
}
