package com.spaceship.plugin.game;

import java.util.Locale;

/**
 * Rôle d'une zone dans le vaisseau : soit le "Mid" central et neutre, soit l'une des
 * bases numérotées (Base1 = la plus proche du centre, Base2/3/4 = de plus en plus
 * profondes dans le territoire d'une équipe).
 *
 * Rappel du layout (exemple à 5 zones, donc N=2 bases par équipe) :
 *   Base2_Rouge | Base1_Rouge | Mid | Base1_Bleu | Base2_Bleu
 *
 * Avec 7 zones (N=3) ou 9 zones (N=4), on ajoute simplement Base3 et/ou Base4 de
 * chaque côté :
 *   Base3 | Base2 | Base1 | Mid | Base1 | Base2 | Base3   (7 zones)
 *   Base4 | Base3 | Base2 | Base1 | Mid | Base1 | Base2 | Base3 | Base4   (9 zones)
 */
public enum ZoneRole {
    MID(0),
    BASE1(1),
    BASE2(2),
    BASE3(3),
    BASE4(4);

    private final int baseIndex;

    ZoneRole(int baseIndex) {
        this.baseIndex = baseIndex;
    }

    /**
     * 0 pour MID, sinon 1 à 4 pour BASE1..BASE4.
     */
    public int getBaseIndex() {
        return baseIndex;
    }

    public boolean isMid() {
        return this == MID;
    }

    /**
     * Renvoie le ZoneRole correspondant à un index de base (1..4). N'accepte pas 0
     * (utiliser MID directement pour ça).
     */
    public static ZoneRole fromBaseIndex(int index) {
        return switch (index) {
            case 1 -> BASE1;
            case 2 -> BASE2;
            case 3 -> BASE3;
            case 4 -> BASE4;
            default -> null;
        };
    }

    /**
     * Parse une chaîne de commande ("mid", "base1", "base2"...) en ZoneRole.
     * Renvoie null si invalide.
     */
    public static ZoneRole parse(String arg) {
        if (arg == null) return null;
        String normalized = arg.toLowerCase(Locale.ROOT).trim();
        if (normalized.equals("mid") || normalized.equals("milieu")) {
            return MID;
        }
        switch (normalized) {
            case "base1": return BASE1;
            case "base2": return BASE2;
            case "base3": return BASE3;
            case "base4": return BASE4;
            default: return null;
        }
    }

    public String getLabel() {
        return this == MID ? "Mid" : "Base" + baseIndex;
    }
}
