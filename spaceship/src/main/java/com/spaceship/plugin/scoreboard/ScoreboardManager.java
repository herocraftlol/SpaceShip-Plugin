package com.spaceship.plugin.scoreboard;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.GameManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Gère le scoreboard affiché aux joueurs pendant la partie SpaceShip.
 * Utilise un scoreboard SHARED par arène (pas par joueur) pour le TAB.
 * Affiche : score des équipes, temps écoulé, nom du serveur et du jeu.
 */
public class ScoreboardManager {

    private final SpaceShipPlugin plugin;
    
    // Scoreboard PAR JOUEUR (pour afficher leur K/D personnel)
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();
    
    private BukkitTask updateTask;

    private String title;

    public ScoreboardManager(SpaceShipPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
        startUpdateTask();
    }

    /**
     * Charge la configuration du scoreboard depuis config.yml
     */
    public void loadConfig() {
        title = plugin.getConfig().getString("scoreboard.title", "&8[&b&lHEROCRAFT&8] &6&lSpaceShip");
    }

    /**
     * Démarre la tâche de mise à jour du scoreboard (toutes les secondes)
     */
    private void startUpdateTask() {
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateAllPlayerScoreboards();
        }, 0L, 20L);
    }

    /**
     * Arrête le scoreboard pour tous les joueurs
     */
    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (UUID uuid : new HashSet<>(playerScoreboards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        playerScoreboards.clear();
    }

    /**
     * Crée et affiche le scoreboard pour un joueur
     */
    public void showScoreboard(Player player, GameManager gm) {
        Scoreboard board = createPlayerScoreboard(player, gm);
        playerScoreboards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
    }

    /**
     * Supprime le scoreboard d'un joueur
     */
    public void removeScoreboard(Player player) {
        playerScoreboards.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /**
     * Crée un scoreboard pour un joueur spécifique
     */
    private Scoreboard createPlayerScoreboard(Player player, GameManager gm) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        
        // Créer l'objectif pour le sidebar
        Objective objective = board.registerNewObjective("spaceship", Criteria.DUMMY, parseColor(title));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        
        // Ajouter les lignes du sidebar avec le K/D du joueur
        addPlayerSidebarLines(board, objective, player, gm);
        
        return board;
    }

    /**
     * Codes couleur Minecraft utilisés comme "entrées" invisibles pour chaque ligne du sidebar.
     * Une entrée différente est nécessaire par ligne, mais elle ne doit jamais s'afficher :
     * on utilise donc une suite de codes couleur (invisibles, car ChatColor ne produit aucun
     * caractère visible) plutôt qu'un simple chiffre "0", "1", "2"... qui apparaissait à
     * l'écran à droite de chaque ligne.
     */
    private static final char[] INVISIBLE_CODES = {
        '0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'
    };

    /**
     * Construit une entrée unique et invisible pour la ligne d'index donné.
     * Chaque ligne a besoin d'une entrée distincte dans le scoreboard, mais le texte de
     * cette entrée ne doit produire aucun caractère affiché.
     */
    private String invisibleEntry(int index) {
        StringBuilder sb = new StringBuilder();
        // On combine les codes pour garantir l'unicité même au-delà de 16 lignes
        sb.append(ChatColor.COLOR_CHAR).append(INVISIBLE_CODES[index % INVISIBLE_CODES.length]);
        sb.append(ChatColor.COLOR_CHAR).append(INVISIBLE_CODES[(index / INVISIBLE_CODES.length) % INVISIBLE_CODES.length]);
        sb.append(ChatColor.RESET);
        return sb.toString();
    }

    /**
     * Ajoute les lignes du sidebar pour un joueur spécifique.
     * Affiche : le score des deux équipes, le nombre de joueurs par équipe,
     * et le K/D personnel du joueur.
     */
    private void addPlayerSidebarLines(Scoreboard board, Objective objective, Player player, GameManager gm) {
        com.spaceship.plugin.game.Team BLACK = com.spaceship.plugin.game.Team.BLACK;
        com.spaceship.plugin.game.Team WHITE = com.spaceship.plugin.game.Team.WHITE;

        int blackPlayers = gm.getPlayerCountForTeam(BLACK);
        int whitePlayers = gm.getPlayerCountForTeam(WHITE);

        // K/D personnel du joueur (kills / deaths, ou kills si aucune mort -> ratio standard)
        int kills = gm.getPlayerKills(player.getUniqueId());
        int deaths = gm.getPlayerDeaths(player.getUniqueId());
        double kd = deaths > 0 ? (double) kills / deaths : kills;
        String kdStr = String.format("%.1f", kd);

        // Barre de progression le long du vaisseau : une case par zone, la case
        // courante (la ligne de front) est mise en évidence.
        String progressBar = buildProgressBar(gm);

        // Lignes du sidebar : progression de la partie + effectifs + K/D du joueur
        String[] lines = {
            "&6&lSpaceShip",
            "&7" + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500,
            "&fVaisseau: " + progressBar,
            "&8\u2764 Noir: &7(&8" + blackPlayers + " joueur" + (blackPlayers == 1 ? "" : "s") + "&7)",
            "&f\u2764 Blanc: &7(&f" + whitePlayers + " joueur" + (whitePlayers == 1 ? "" : "s") + "&7)",
            "&7" + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500 + (char) 0x2500,
            "&fTon K/D: &a" + kills + "&7/&c" + deaths + " &8(&a" + kdStr + "&8)"
        };

        // Une entrée invisible distincte par ligne : le prefix de l'équipe scoreboard porte
        // tout le texte affiché, et comme l'entrée elle-même est invisible, aucun chiffre
        // parasite n'apparaît plus à droite de la ligne.
        for (int i = 0; i < lines.length; i++) {
            String parsed = parseColor(lines[i]);
            String identifier = invisibleEntry(i);

            org.bukkit.scoreboard.Team mcTeam;
            String teamName2 = "sb_" + i;
            if (board.getTeam(teamName2) != null) {
                mcTeam = board.getTeam(teamName2);
            } else {
                mcTeam = board.registerNewTeam(teamName2);
            }

            mcTeam.setPrefix(parsed);
            mcTeam.setSuffix("");
            mcTeam.addEntry(identifier);
            objective.getScore(identifier).setScore(0);
        }
    }

    /**
     * Construit une barre "[carrés]" représentant la position actuelle de la ligne de
     * front le long du vaisseau (voir GameManager#getFrontier / #getBasesPerSide).
     * Un carré blanc plein = zone tenue par les Blancs, noir (gris foncé, pour rester
     * visible) plein = zone tenue par les Noirs, le losange gris clair au centre = le
     * Mid neutre.
     */
    private String buildProgressBar(GameManager gm) {
        int n = gm.getBasesPerSide();
        int frontier = gm.getFrontier();
        StringBuilder sb = new StringBuilder();
        for (int k = n; k >= 1; k--) {
            sb.append(frontier <= -k ? "&f\u25a0" : "&7\u25a1");
        }
        sb.append(frontier == 0 ? "&7\u25c6" : "&7\u25a1");
        for (int k = 1; k <= n; k++) {
            sb.append(frontier >= k ? "&8\u25a0" : "&7\u25a1");
        }
        return sb.toString();
    }

    /**
     * Met à jour tous les scoreboards des joueurs
     */
    private void updateAllPlayerScoreboards() {
        // Supprimer les joueurs qui ne jouent plus
        playerScoreboards.keySet().removeIf(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) return true;
            GameManager gm = plugin.getArenaManager().findArenaOf(player);
            return gm == null || !gm.isPlaying(player);
        });
        
        // Mettre à jour chaque scoreboard
        for (UUID uuid : new HashSet<>(playerScoreboards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            
            GameManager gm = plugin.getArenaManager().findArenaOf(player);
            if (gm == null || !gm.isPlaying(player)) continue;
            
            Scoreboard board = playerScoreboards.get(uuid);
            updatePlayerSidebar(board, player, gm);
            player.setScoreboard(board);
        }
    }

    /**
     * Met à jour le sidebar pour un joueur
     */
    private void updatePlayerSidebar(Scoreboard board, Player player, GameManager gm) {
        // Récupérer ou créer l'objectif
        Objective objective = board.getObjective("spaceship");
        if (objective == null) {
            objective = board.registerNewObjective("spaceship", Criteria.DUMMY, parseColor(title));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        
        // Supprimer les anciennes entrées
        Set<org.bukkit.scoreboard.Team> oldTeams = new HashSet<>(board.getTeams());
        for (org.bukkit.scoreboard.Team team : oldTeams) {
            if (team.getName().startsWith("sb_")) {
                for (String entry : team.getEntries()) {
                    board.resetScores(entry);
                }
                team.unregister();
            }
        }
        
        // Recréer les lignes
        addPlayerSidebarLines(board, objective, player, gm);
    }

    public void onGameStart(GameManager gm) {
        // Pas besoin pour le système par joueur
    }

    public void onLobbyReset(GameManager gm) {
        // Pas besoin pour le système par joueur
    }

    public void onRoundReset(GameManager gm) {
        // Pas besoin pour le système par joueur
    }

    private String parseColor(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public void reload() {
        plugin.reloadConfig();
        loadConfig();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
    
    // Méthodes de compatibilité (non utilisées dans le nouveau système)
    public void setServerName(String name) {}
    public void setGameName(String name) {}
    public String getServerName() { return "HEROCRAFT"; }
    public String getGameName() { return "SpaceShip"; }
    public void setLines(java.util.List<String> lines) {}
}
