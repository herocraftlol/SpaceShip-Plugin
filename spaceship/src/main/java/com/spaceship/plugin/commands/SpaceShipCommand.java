package com.spaceship.plugin.commands;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.game.Arena;
import com.spaceship.plugin.game.ArenaManager;
import com.spaceship.plugin.game.CuboidRegion;
import com.spaceship.plugin.game.GameManager;
import com.spaceship.plugin.game.GameState;
import com.spaceship.plugin.game.Team;
import com.spaceship.plugin.game.ZoneRole;
import com.spaceship.plugin.hologram.CategoryLeaderboardManager;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Gère la commande /ss et tous ses sous-arguments.
 *
 * Le plugin gère plusieurs arènes SpaceShip nommées et indépendantes ; presque toutes
 * les commandes (autres que join/leave/list) attendent donc un nom d'arène en paramètre.
 *
 * Contrairement à HikaBrain (une seule zone de capture par équipe), une arène SpaceShip
 * est composée de 5, 7 ou 9 zones alignées le long d'un vaisseau : un Mid neutre au
 * centre, puis Base1, Base2 (et Base3/Base4 selon la taille) de chaque côté. Chaque
 * zone doit être configurée séparément, par équipe :
 *
 *   /ss create <nom>                                          - crée une nouvelle arène vide
 *   /ss delete <nom>                                          - supprime une arène
 *   /ss list                                                  - liste toutes les arènes
 *   /ss setlobby <nom>                                        - définit le lobby (à ta position)
 *   /ss setzonecount <nom> <5|7|9>                            - définit le nombre de zones du vaisseau
 *   /ss setspawn <nom> <black|white> <mid|base1|base2|base3|base4> <index> - définit/remplace un spawn de zone
 *   /ss delspawn <nom> <black|white> <mid|base1|base2|base3|base4> <index> - supprime un spawn de zone
 *   /ss setgoal <nom> <black|white> <mid|base1|base2|base3|base4> <pos1|pos2>  - définit le but (zone de capture) d'une zone
 *   /ss setgamezone <nom> <pos1|pos2>                         - définit + capture la zone de jeu protégée
 *   /ss setmaxplayers <nom> <nombre>                          - définit le max de joueurs de l'arène (0 = global)
 *   /ss join <nom>                                            - rejoindre une arène
 *   /ss joinrandom                                            - rejoindre une arène au hasard (priorité à celles déjà occupées)
 *   /ss leave                                                 - quitter l'arène en cours
 *   /ss start <nom>                                           - forcer le démarrage
 *   /ss stop <nom>                                            - forcer l'arrêt
 *   /ss info <nom>                                            - infos sur une arène
 *
 * Pour la sélection des coins de zone (buts, zone de jeu), on utilise une astuce simple
 * en deux étapes : pos1 enregistre le premier coin à la position du joueur, pos2
 * enregistre le second et finalise la zone.
 */
public class SpaceShipCommand implements CommandExecutor, TabCompleter {

    private final SpaceShipPlugin plugin;

    // Stocke temporairement le coin 1 d'un but (zone de capture) en attendant le coin 2,
    // par triplet (nom d'arène + équipe + rôle de zone).
    private final Map<String, Location> pendingGoalCorner1 = new HashMap<>();

    // Stocke temporairement le coin 1 de la zone de jeu globale, par nom d'arène.
    private final Map<String, Location> pendingGameZoneCorner1 = new HashMap<>();

    public SpaceShipCommand(SpaceShipPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "create" -> handleCreate(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "list" -> handleList(sender);
            case "setlobby" -> handleSetLobby(sender, args);
            case "setzonecount" -> handleSetZoneCount(sender, args);
            case "setspawn" -> handleSetSpawn(sender, args);
            case "delspawn" -> handleDelSpawn(sender, args);
            case "setgoal" -> handleSetGoal(sender, args);
            case "setgamezone" -> handleSetGameZone(sender, args);
            case "setmaxplayers" -> handleSetMaxPlayers(sender, args);
            case "join" -> handleJoin(sender, args);
            case "joinrandom" -> handleJoinRandom(sender);
            case "arenas" -> handleArenasGui(sender);
            case "leave" -> handleLeave(sender);
            case "start" -> handleStart(sender, args);
            case "stop" -> handleStop(sender, args);
            case "info" -> handleInfo(sender, args);
            case "stats" -> handleStats(sender, args);
            case "top" -> handleTop(sender, args);
            case "resetstats" -> handleResetStats(sender);
            case "leaderboard" -> handleLeaderboard(sender, args);
            // Scoreboard commands
            case "setsbserver" -> handleSetSbServer(sender, args);
            case "setsbgame" -> handleSetSbGame(sender, args);
            case "setsbtitle" -> handleSetSbTitle(sender, args);
            case "setsblines" -> handleSetSbLines(sender, args);
            case "reloadsb" -> handleReloadSb(sender);
            case "sbinfo" -> handleSbInfo(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    // ================= GESTION DES ARÈNES (ADMIN) =================

    private void handleCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss create <nom>");
            return;
        }
        String name = args[1];
        boolean created = plugin.getArenaManager().create(name);
        MessageUtil.send(sender, created
                ? "&aArène '" + name + "' créée. Configure-la avec /ss setlobby " + name + ", etc."
                : "&cUne arène '" + name + "' existe déjà.");
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss delete <nom>");
            return;
        }
        String name = args[1];
        boolean deleted = plugin.getArenaManager().delete(name);
        MessageUtil.send(sender, deleted ? "&aArène '" + name + "' supprimée." : "&cAucune arène '" + name + "' trouvée.");
    }

    private void handleList(CommandSender sender) {
        ArenaManager am = plugin.getArenaManager();
        Set<String> names = am.getNames();
        if (names.isEmpty()) {
            MessageUtil.send(sender, "&7Aucune arène configurée. Utilise /ss create <nom> pour en créer une.");
            return;
        }
        MessageUtil.send(sender, "&8&m----------&r &bArènes SpaceShip &8&m----------");
        for (String name : names) {
            GameManager gm = am.get(name);
            MessageUtil.send(sender, "&e" + name + " &7- État: &f" + gm.getState()
                    + " &7- Joueurs: &f" + gm.getPlayerCount()
                    + " &7- Configurée: " + (gm.getArena().isFullyConfigured() ? "&aOui" : "&cNon"));
        }
    }

    // ================= SETUP (ADMIN) =================

    /**
     * Récupère l'arène désignée par args[nameIndex] et envoie un message d'erreur si elle
     * n'existe pas. Renvoie null si l'arène n'existe pas (l'appelant doit alors arrêter le traitement).
     */
    private GameManager resolveArena(CommandSender sender, String[] args, int nameIndex) {
        if (args.length <= nameIndex) {
            return null;
        }
        GameManager gm = plugin.getArenaManager().get(args[nameIndex]);
        if (gm == null) {
            MessageUtil.send(sender, "&cAucune arène nommée '" + args[nameIndex] + "' n'existe. Utilise /ss create " + args[nameIndex] + " d'abord.");
        }
        return gm;
    }

    private void handleSetLobby(CommandSender sender, String[] args) {
        if (!checkAdminAndPlayer(sender)) return;
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss setlobby <nom>");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;
        Player player = (Player) sender;

        gm.getArena().setLobbySpawn(player.getLocation());
        gm.saveArenaConfig();

        MessageUtil.send(sender, "&aLe point de lobby de '" + args[1] + "' a été défini à ta position.");
    }

    /**
     * Définit le nombre de zones du vaisseau (5, 7 ou 9). Doit être fait avant de
     * configurer les spawns/buts des bases au-delà de Base2 (Base3/Base4).
     */
    private void handleSetZoneCount(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 3) {
            MessageUtil.send(sender, "&cUsage: /ss setzonecount <nom> <5|7|9>");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;

        int count;
        try {
            count = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            MessageUtil.send(sender, "&cLe nombre de zones doit être un entier (5, 7 ou 9).");
            return;
        }

        boolean ok = gm.getArena().setZoneCount(count);
        if (!ok) {
            MessageUtil.send(sender, "&cValeur invalide. Le nombre de zones doit être 5, 7 ou 9.");
            return;
        }
        gm.saveArenaConfig();
        MessageUtil.send(sender, "&aL'arène '" + args[1] + "' utilisera désormais &7" + count
                + " &azones (&7" + gm.getArena().getBasesPerSide() + " base(s) par équipe&a).");
    }

    /**
     * Parse le rôle de zone (mid/base1/base2/base3/base4) et vérifie qu'il est valide
     * pour le nombre de bases par équipe actuellement configuré sur cette arène.
     * Envoie un message d'erreur et renvoie null si invalide.
     */
    private ZoneRole parseZoneRoleOrError(CommandSender sender, GameManager gm, String arg) {
        ZoneRole role = ZoneRole.parse(arg);
        if (role == null) {
            MessageUtil.send(sender, "&cZone invalide. Utilise 'mid', 'base1', 'base2'"
                    + (gm.getArena().getBasesPerSide() >= 3 ? ", 'base3'" : "")
                    + (gm.getArena().getBasesPerSide() >= 4 ? " ou 'base4'" : " ou 'base2'") + ".");
            return null;
        }
        if (!role.isMid() && role.getBaseIndex() > gm.getArena().getBasesPerSide()) {
            MessageUtil.send(sender, "&cCette arène n'a que &7" + gm.getArena().getBasesPerSide()
                    + " &cbase(s) par équipe (zoneCount=" + gm.getArena().getZoneCount()
                    + "). Utilise /ss setzonecount pour en ajouter plus.");
            return null;
        }
        return role;
    }

    private void handleSetSpawn(CommandSender sender, String[] args) {
        if (!checkAdminAndPlayer(sender)) return;
        if (args.length < 5) {
            MessageUtil.send(sender, "&cUsage: /ss setspawn <nom> <black|white> <mid|base1|base2|base3|base4> <index>");
            MessageUtil.send(sender, "&7L'index commence à 1. Utilise le prochain index disponible pour ajouter un nouveau spawn.");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;
        Player player = (Player) sender;

        Team team = parseTeam(args[2]);
        if (team == null) {
            MessageUtil.send(sender, "&cÉquipe invalide. Utilise 'black' ou 'white'.");
            return;
        }

        ZoneRole role = parseZoneRoleOrError(sender, gm, args[3]);
        if (role == null) return;

        int index;
        try {
            index = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            MessageUtil.send(sender, "&cL'index doit être un nombre entier (ex: 1, 2, 3...).");
            return;
        }

        int nextAvailable = gm.getArena().getZoneSpawnCount(team, role) + 1;
        boolean ok = gm.getArena().setZoneSpawn(team, role, index, player.getLocation());
        if (!ok) {
            MessageUtil.send(sender, "&cIndex invalide. Le prochain index disponible pour cette zone est &7" + nextAvailable + "&c.");
            return;
        }
        gm.saveArenaConfig();

        boolean wasReplaced = index <= (nextAvailable - 1);
        MessageUtil.send(sender, "&aLe spawn &7#" + index + " &ade " + team.getColoredName() + " &a(" + role.getLabel()
                + ") &asur '" + args[1] + "' a été " + (wasReplaced ? "remplacé" : "défini") + " à ta position. &7("
                + gm.getArena().getZoneSpawnCount(team, role) + " spawn(s) au total pour cette zone)");
    }

    private void handleDelSpawn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 5) {
            MessageUtil.send(sender, "&cUsage: /ss delspawn <nom> <black|white> <mid|base1|base2|base3|base4> <index>");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;

        Team team = parseTeam(args[2]);
        if (team == null) {
            MessageUtil.send(sender, "&cÉquipe invalide. Utilise 'black' ou 'white'.");
            return;
        }

        ZoneRole role = parseZoneRoleOrError(sender, gm, args[3]);
        if (role == null) return;

        int index;
        try {
            index = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            MessageUtil.send(sender, "&cL'index doit être un nombre entier (ex: 1, 2, 3...).");
            return;
        }

        boolean ok = gm.getArena().removeZoneSpawn(team, role, index);
        if (!ok) {
            MessageUtil.send(sender, "&cIndex invalide. Cette zone a actuellement &7"
                    + gm.getArena().getZoneSpawnCount(team, role) + " &cspawn(s).");
            return;
        }
        gm.saveArenaConfig();
        MessageUtil.send(sender, "&aSpawn &7#" + index + " &ade " + team.getColoredName() + " &a(" + role.getLabel()
                + ") &asupprimé sur '" + args[1] + "'.");
    }

    /**
     * Définit le but (zone de capture) d'une zone d'équipe : Mid (le "côté" de cette
     * équipe dans la salle centrale) ou une Base.
     */
    private void handleSetGoal(CommandSender sender, String[] args) {
        if (!checkAdminAndPlayer(sender)) return;
        if (args.length < 5) {
            MessageUtil.send(sender, "&cUsage: /ss setgoal <nom> <black|white> <mid|base1|base2|base3|base4> <pos1|pos2>");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;
        Player player = (Player) sender;

        Team team = parseTeam(args[2]);
        if (team == null) {
            MessageUtil.send(sender, "&cÉquipe invalide. Utilise 'black' ou 'white'.");
            return;
        }

        ZoneRole role = parseZoneRoleOrError(sender, gm, args[3]);
        if (role == null) return;

        String posArg = args[4].toLowerCase(Locale.ROOT);
        String pendingKey = args[1].toLowerCase(Locale.ROOT) + ":" + team.name() + ":" + role.name();

        if (posArg.equals("pos1")) {
            pendingGoalCorner1.put(pendingKey, player.getTargetBlock(null, 5).getLocation());
            MessageUtil.send(sender, "&aCoin 1 du but &7" + team.getColoredName() + " " + role.getLabel()
                    + "&a enregistré. Place-toi au coin opposé et fais &7/ss setgoal " + args[1] + " " + args[2] + " " + args[3] + " pos2");
        } else if (posArg.equals("pos2")) {
            Location corner1 = pendingGoalCorner1.get(pendingKey);
            if (corner1 == null) {
                MessageUtil.send(sender, "&cTu dois d'abord définir le coin 1 avec /ss setgoal " + args[1] + " " + args[2] + " " + args[3] + " pos1");
                return;
            }
            Location corner2 = player.getTargetBlock(null, 5).getLocation();
            if (corner1.getWorld() == null || !corner1.getWorld().equals(corner2.getWorld())) {
                MessageUtil.send(sender, "&cLes deux coins doivent être dans le même monde !");
                return;
            }

            CuboidRegion region = new CuboidRegion(corner1, corner2);
            gm.getArena().setZoneGoal(team, role.getBaseIndex(), region);
            pendingGoalCorner1.remove(pendingKey);
            gm.saveArenaConfig();

            MessageUtil.send(sender, "&aBut de " + team.getColoredName() + " " + role.getLabel() + " &asur '" + args[1] + "' défini avec succès !");
        } else {
            MessageUtil.send(sender, "&cUsage: /ss setgoal <nom> <black|white> <mid|base1|base2|base3|base4> <pos1|pos2>");
        }
    }

    /**
     * Définit la zone de jeu globale (protection des blocs + restauration de la map).
     * Une fois les deux coins posés, capture immédiatement un snapshot de tous les blocs
     * actuellement présents dans la zone : c'est cet état qui sera restauré à chaque round.
     */
    private void handleSetGameZone(CommandSender sender, String[] args) {
        if (!checkAdminAndPlayer(sender)) return;
        if (args.length < 3) {
            MessageUtil.send(sender, "&cUsage: /ss setgamezone <nom> <pos1|pos2>");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;
        Player player = (Player) sender;

        String posArg = args[2].toLowerCase(Locale.ROOT);
        String arenaKey = args[1].toLowerCase(Locale.ROOT);

        if (posArg.equals("pos1")) {
            pendingGameZoneCorner1.put(arenaKey, player.getTargetBlock(null, 5).getLocation());
            MessageUtil.send(sender, "&aCoin 1 de la zone de jeu enregistré. Place-toi au coin opposé et fais &7/ss setgamezone " + args[1] + " pos2");
        } else if (posArg.equals("pos2")) {
            Location corner1 = pendingGameZoneCorner1.get(arenaKey);
            if (corner1 == null) {
                MessageUtil.send(sender, "&cTu dois d'abord définir le coin 1 avec /ss setgamezone " + args[1] + " pos1");
                return;
            }
            Location corner2 = player.getTargetBlock(null, 5).getLocation();
            if (corner1.getWorld() == null || !corner1.getWorld().equals(corner2.getWorld())) {
                MessageUtil.send(sender, "&cLes deux coins doivent être dans le même monde !");
                return;
            }

            CuboidRegion region = new CuboidRegion(corner1, corner2);
            gm.getArena().setGameZone(region);
            pendingGameZoneCorner1.remove(arenaKey);
            gm.saveArenaConfig();

            MessageUtil.send(sender, "&aZone de jeu définie ! Capture du snapshot de la map en cours...");
            gm.captureGameZone();
            MessageUtil.send(sender, "&aSnapshot de la map capturé avec succès. Elle sera restaurée à chaque round.");
        } else {
            MessageUtil.send(sender, "&cUsage: /ss setgamezone <nom> <pos1|pos2>");
        }
    }

    /**
     * Définit le nombre maximum de joueurs pour une arène spécifique. Un nombre <= 0
     * réinitialise la valeur spécifique (l'arène retombera alors sur le max-players global).
     */
    private void handleSetMaxPlayers(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 3) {
            MessageUtil.send(sender, "&cUsage: /ss setmaxplayers <nom> <nombre>");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;

        int max;
        try {
            max = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            MessageUtil.send(sender, "&cLe nombre de joueurs doit être un entier.");
            return;
        }
        if (max < 0) {
            MessageUtil.send(sender, "&cLe nombre de joueurs ne peut pas être négatif.");
            return;
        }

        gm.getArena().setMaxPlayers(max);
        gm.saveArenaConfig();

        if (max == 0) {
            MessageUtil.send(sender, "&aLe nombre maximum de joueurs spécifique à l'arène &7" + gm.getName()
                    + " &aa été réinitialisé (utilisera désormais le max-players global).");
        } else {
            MessageUtil.send(sender, "&aLe nombre maximum de joueurs de l'arène &7" + gm.getName()
                    + " &aa été fixé à &7" + max + "&a.");
        }
    }

    // ================= JOUEUR =================

    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&cCette commande doit être exécutée par un joueur.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss join <nom>");
            return;
        }
        ArenaManager am = plugin.getArenaManager();
        GameManager gm = am.get(args[1]);
        if (gm == null) {
            MessageUtil.send(sender, "&cAucune arène nommée '" + args[1] + "' n'existe.");
            return;
        }

        GameManager current = am.findArenaOf(player);
        if (current != null) {
            MessageUtil.send(sender, "&cTu es déjà dans une partie (" + current.getName() + "). Fais /ss leave d'abord.");
            return;
        }

        gm.addPlayer(player);
    }

    /**
     * Rejoint automatiquement la "meilleure" arène disponible :
     * - en priorité une arène qui a déjà des joueurs en attente (pour la compléter,
     *   typiquement pour finir un 1v1 ou rejoindre une partie qui se remplit), en
     *   choisissant celle qui en a le plus, et au hasard en cas d'égalité ;
     * - sinon, une arène vide tirée au hasard parmi celles disponibles.
     */
    private void handleJoinRandom(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&cCette commande doit être exécutée par un joueur.");
            return;
        }
        ArenaManager am = plugin.getArenaManager();

        GameManager current = am.findArenaOf(player);
        if (current != null) {
            MessageUtil.send(sender, "&cTu es déjà dans une partie (" + current.getName() + "). Fais /ss leave d'abord.");
            return;
        }

        GameManager gm = am.findBestArenaForRandomJoin();
        if (gm == null) {
            MessageUtil.send(sender, "&cAucune arène disponible pour le moment. Réessaie plus tard.");
            return;
        }

        gm.addPlayer(player);
    }

    private void handleArenasGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&cCette commande doit être exécutée par un joueur.");
            return;
        }
        plugin.getArenaGUI().open(player);
    }

    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&cCette commande doit être exécutée par un joueur.");
            return;
        }
        GameManager gm = plugin.getArenaManager().findArenaOf(player);
        if (gm == null) {
            MessageUtil.send(sender, "&cTu n'es pas dans une partie.");
            return;
        }
        gm.removePlayer(player);
    }

    private void handleInfo(CommandSender sender, String[] args) {
        ArenaManager am = plugin.getArenaManager();

        if (args.length < 2) {
            // Sans nom précisé : si le joueur est en partie, affiche son arène actuelle.
            if (sender instanceof Player player) {
                GameManager current = am.findArenaOf(player);
                if (current != null) {
                    printArenaInfo(sender, current);
                    return;
                }
            }
            MessageUtil.send(sender, "&cUsage: /ss info <nom>");
            return;
        }

        GameManager gm = am.get(args[1]);
        if (gm == null) {
            MessageUtil.send(sender, "&cAucune arène nommée '" + args[1] + "' n'existe.");
            return;
        }
        printArenaInfo(sender, gm);
    }

    private void printArenaInfo(CommandSender sender, GameManager gm) {
        Arena arena = gm.getArena();
        int n = arena.getBasesPerSide();
        MessageUtil.send(sender, "&8&m----------&r &bSpaceShip: " + gm.getName() + " &8&m----------");
        MessageUtil.send(sender, "&7État : &f" + gm.getState());
        MessageUtil.send(sender, "&7Joueurs en partie : &f" + gm.getPlayerCount() + "&7/&f" + gm.getMaxPlayers()
                + (arena.getMaxPlayers() > 0 ? " &8(spécifique)" : " &8(global)"));
        MessageUtil.send(sender, "&7Map configurée : " + (arena.isFullyConfigured() ? "&aOui" : "&cNon"));
        MessageUtil.send(sender, "&7Nombre de zones : &f" + arena.getZoneCount() + " &7(" + n + " base(s) par équipe)");
        MessageUtil.send(sender, "&7Spawns Mid : &8" + arena.getMidSpawnCount(Team.BLACK) + " noir(s) &7/ &f" + arena.getMidSpawnCount(Team.WHITE) + " blanc(s)");
        MessageUtil.send(sender, "&7But Mid : &8noir=" + (arena.getMidGoal(Team.BLACK) != null ? "&aOK" : "&cmanquant")
                + " &7/ &fblanc=" + (arena.getMidGoal(Team.WHITE) != null ? "&aOK" : "&cmanquant"));
        for (int k = 1; k <= n; k++) {
            MessageUtil.send(sender, "&7Base" + k + " : &8noir spawns=" + arena.getBaseSpawnCount(Team.BLACK, k)
                    + " but=" + (arena.getBaseGoal(Team.BLACK, k) != null ? "&aOK" : "&cmanquant")
                    + " &7/ &fblanc spawns=" + arena.getBaseSpawnCount(Team.WHITE, k)
                    + " but=" + (arena.getBaseGoal(Team.WHITE, k) != null ? "&aOK" : "&cmanquant"));
        }
        MessageUtil.send(sender, "&7Zone de jeu (protection) : " + (gm.getArenaSnapshot().isCaptured() ? "&aOui" : "&cNon configurée"));
        MessageUtil.send(sender, "&7Ligne de front : " + buildFrontierBar(gm));
    }

    /**
     * Construit une petite barre textuelle représentant la position actuelle de la
     * ligne de front le long du vaisseau, ex: [W][W][M][N][ ] avec la case courante en surbrillance.
     * W = Blancs, N = Noirs, M = Mid (neutre).
     */
    private String buildFrontierBar(GameManager gm) {
        int n = gm.getBasesPerSide();
        int frontier = gm.getFrontier();
        StringBuilder sb = new StringBuilder();
        for (int k = n; k >= 1; k--) {
            sb.append(frontier <= -k ? "&f[W]" : "&8[ ]");
        }
        sb.append(frontier == 0 ? "&7[M]" : "&8[ ]");
        for (int k = 1; k <= n; k++) {
            sb.append(frontier >= k ? "&8[N]" : "&8[ ]");
        }
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }


    // ================= ADMIN: START/STOP =================

    private void handleStart(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss start <nom>");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;

        if (!gm.getArena().isFullyConfigured()) {
            MessageUtil.send(sender, "&cLa map n'est pas complètement configurée (lobby/spawns/zones manquants).");
            return;
        }
        if (gm.getState() == GameState.PLAYING || gm.getState() == GameState.ROUND_RESET) {
            MessageUtil.send(sender, "&cUne partie est déjà en cours sur cette arène.");
            return;
        }
        boolean started = gm.forceStart();
        MessageUtil.send(sender, started ? "&aPartie démarrée de force." : "&cImpossible de démarrer : il faut au moins un joueur dans chaque équipe.");
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss stop <nom>");
            return;
        }
        GameManager gm = resolveArena(sender, args, 1);
        if (gm == null) return;

        gm.forceStop();
        MessageUtil.send(sender, "&aPartie arrêtée sur '" + args[1] + "', retour au lobby.");
    }

    // ================= SCOREBOARD (ADMIN) =================

    private void handleSetSbServer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss setsbserver <nom_du_serveur>");
            return;
        }
        String serverName = args[1];
        plugin.getScoreboardManager().setServerName(serverName);
        MessageUtil.send(sender, "&aNom du serveur défini sur: &f" + serverName);
    }

    private void handleSetSbGame(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss setsbgame <nom_du_jeu>");
            return;
        }
        String gameName = args[1];
        plugin.getScoreboardManager().setGameName(gameName);
        MessageUtil.send(sender, "&aNom du jeu défini sur: &f" + gameName);
    }

    private void handleSetSbTitle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss setsbtitle <titre> (utilise & pour les couleurs)");
            return;
        }
        // Reconstruire le titre à partir de tous les arguments
        StringBuilder title = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) title.append(" ");
            title.append(args[i]);
        }
        plugin.getScoreboardManager().setTitle(title.toString());
        MessageUtil.send(sender, "&aTitre du scoreboard défini sur: &f" + title);
    }

    private void handleSetSbLines(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss setsblines <ligne1> | <ligne2> | ...");
            MessageUtil.send(sender, "&7Variables disponibles: %server%, %game%, %red_score%, %blue_score%, %players%, %elapsed_time%");
            MessageUtil.send(sender, "&7Sépare les lignes par des | (pipe character)");
            return;
        }
        // Reconstruire les lignes à partir de tous les arguments séparés par |
        StringBuilder allArgs = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) allArgs.append(" ");
            allArgs.append(args[i]);
        }
        
        String[] linesArray = allArgs.toString().split("\\|");
        List<String> lines = new ArrayList<>();
        for (String line : linesArray) {
            lines.add(line.trim());
        }
        
        plugin.getScoreboardManager().setLines(lines);
        MessageUtil.send(sender, "&aLignes du scoreboard mises à jour. (" + lines.size() + " lignes)");
    }

    private void handleReloadSb(CommandSender sender) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        plugin.getScoreboardManager().reload();
        MessageUtil.send(sender, "&aConfiguration du scoreboard rechargée.");
    }

    private void handleSbInfo(CommandSender sender) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        MessageUtil.send(sender, "&8&m----------&r &bScoreboard &8&m----------");
        MessageUtil.send(sender, "&7Titre: &f" + plugin.getScoreboardManager().getTitle());
        MessageUtil.send(sender, "&7Serveur: &f" + plugin.getScoreboardManager().getServerName());
        MessageUtil.send(sender, "&7Jeu: &f" + plugin.getScoreboardManager().getGameName());
        MessageUtil.send(sender, "&7Lignes: &f" + plugin.getConfig().getStringList("scoreboard.lines").size());
        MessageUtil.send(sender, "&8&m----------&r &7Commandes &8&m----------");
        MessageUtil.send(sender, "&e/ss setsbserver <nom> &7- Définir le nom du serveur");
        MessageUtil.send(sender, "&e/ss setsbgame <nom> &7- Définir le nom du jeu");
        MessageUtil.send(sender, "&e/ss setsbtitle <titre> &7- Définir le titre");
        MessageUtil.send(sender, "&e/ss setsblines <lignes> &7- Définir les lignes (séparées par |)");
        MessageUtil.send(sender, "&e/ss reloadsb &7- Recharger la config");
    }

    // ================= STATISTIQUES =================

    private void handleStats(CommandSender sender, String[] args) {
        UUID targetUuid;
        String targetName;

        if (args.length >= 2) {
            // /ss stats <pseudo>
            targetName = args[1];
            org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(targetName);
            if (!offline.hasPlayedBefore() && !offline.isOnline()) {
                MessageUtil.send(sender, "&cAucune statistique trouvée pour &e" + targetName + "&c.");
                return;
            }
            targetUuid = offline.getUniqueId();
            targetName = offline.getName() != null ? offline.getName() : targetName;
        } else {
            // /ss stats → propres stats
            if (!(sender instanceof Player player)) {
                MessageUtil.send(sender, "&cPrécise un pseudo : &e/ss stats <pseudo>");
                return;
            }
            targetUuid = player.getUniqueId();
            targetName = player.getName();
        }

        com.spaceship.plugin.stats.StatsManager.PlayerStats stats =
                plugin.getStatsManager().getPlayerStats(targetUuid, targetName);

        int losses = stats.gamesPlayed - stats.gamesWon;
        double winRate = stats.gamesPlayed > 0
                ? Math.round((double) stats.gamesWon / stats.gamesPlayed * 1000.0) / 10.0
                : 0.0;

        MessageUtil.send(sender, "&8&m----------&r &bStats de " + targetName + " &8&m----------");
        MessageUtil.send(sender, "&f▸ Kills: &a" + stats.kills + " &7/ Deaths: &c" + stats.deaths + " &7/ K/D: &e" + stats.getKD());
        MessageUtil.send(sender, "&f▸ Parties jouées: &7" + stats.gamesPlayed);
        MessageUtil.send(sender, "&f▸ Parties gagnées: &a" + stats.gamesWon + " &7(défaites: &c" + losses + "&7)");
        MessageUtil.send(sender, "&f▸ Taux de victoire: &6" + winRate + "%");
        MessageUtil.send(sender, "&8&m----------&r &7Stats SpaceShip Global &8&m----------");
        MessageUtil.send(sender, "&fParties jouées: &7" + plugin.getStatsManager().getTotalGames()
                + "  &fCaptures: &7" + plugin.getStatsManager().getTotalCaptures());
        MessageUtil.send(sender, "&8&m----------&r");
    }

    private void handleTop(CommandSender sender, String[] args) {
        // /ss top [kd|kills|wins|games]  -- "wins" par défaut
        String criterion = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "wins";

        Comparator<com.spaceship.plugin.stats.StatsManager.PlayerStats> comparator;
        String label;
        switch (criterion) {
            case "kd" -> {
                comparator = Comparator.comparingDouble(com.spaceship.plugin.stats.StatsManager.PlayerStats::getKD);
                label = "Meilleur K/D";
            }
            case "kills" -> {
                comparator = Comparator.comparingInt(s -> s.kills);
                label = "Plus de Kills";
            }
            case "games" -> {
                comparator = Comparator.comparingInt(s -> s.gamesPlayed);
                label = "Plus de Parties Jouées";
            }
            case "wins" -> {
                comparator = Comparator.comparingInt(s -> s.gamesWon);
                label = "Plus de Victoires";
            }
            default -> {
                MessageUtil.send(sender, "&cCritère inconnu. Utilise : &e/ss top <kd|kills|wins|games>");
                return;
            }
        }

        List<Map.Entry<UUID, com.spaceship.plugin.stats.StatsManager.PlayerStats>> top =
                plugin.getStatsManager().getTopPlayers(10, comparator);

        MessageUtil.send(sender, "&8&m----------&r &6&lClassement: " + label + " &8&m----------");

        if (top.isEmpty()) {
            MessageUtil.send(sender, "&7Aucune statistique enregistrée pour le moment.");
        } else {
            int rank = 1;
            for (Map.Entry<UUID, com.spaceship.plugin.stats.StatsManager.PlayerStats> entry : top) {
                com.spaceship.plugin.stats.StatsManager.PlayerStats s = entry.getValue();
                String rankColor = switch (rank) {
                    case 1 -> "&6";
                    case 2 -> "&7";
                    case 3 -> "&c";
                    default -> "&f";
                };
                String value = switch (criterion) {
                    case "kd"    -> String.valueOf(s.getKD());
                    case "kills" -> String.valueOf(s.kills);
                    case "games" -> String.valueOf(s.gamesPlayed);
                    default      -> String.valueOf(s.gamesWon);
                };
                MessageUtil.send(sender, rankColor + "&l#" + rank + " &f" + s.name + " &7- &e" + value);
                rank++;
            }
        }

        MessageUtil.send(sender, "&8&m----------&r");
    }

    private void handleLeaderboard(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /ss leaderboard <victoires|kills|kd|parties> [remove|size <taille>]");
            return;
        }

        CategoryLeaderboardManager.Category category = CategoryLeaderboardManager.Category.fromKey(args[1]);
        if (category == null) {
            MessageUtil.send(sender, "&cCatégorie inconnue. Utilise: victoires, kills, kd ou parties.");
            return;
        }

        CategoryLeaderboardManager lm = plugin.getLeaderboardManager();

        if (args.length >= 3 && args[2].equalsIgnoreCase("remove")) {
            boolean removed = lm.despawn(category);
            MessageUtil.send(sender, removed
                    ? "&aLeaderboard '" + category.key + "' supprimé."
                    : "&cAucun leaderboard '" + category.key + "' n'est actuellement actif.");
            return;
        }

        if (args.length >= 3 && args[2].equalsIgnoreCase("size")) {
            if (args.length < 4) {
                MessageUtil.send(sender, "&cUsage: /ss leaderboard <catégorie> size <taille>");
                MessageUtil.send(sender, "&7La taille est un multiplicateur (ex: 1.0 = normal, 2.0 = deux fois plus grand, 0.5 = deux fois plus petit). Doit être entre 0.1 et 10.");
                return;
            }
            if (!lm.isSpawned(category)) {
                MessageUtil.send(sender, "&cAucun leaderboard '" + category.key + "' n'est actuellement actif.");
                return;
            }
            double scale;
            try {
                scale = Double.parseDouble(args[3]);
            } catch (NumberFormatException e) {
                MessageUtil.send(sender, "&cTaille invalide. Utilise un nombre (ex: 1.5).");
                return;
            }
            if (scale < 0.1 || scale > 10.0) {
                MessageUtil.send(sender, "&cLa taille doit être comprise entre 0.1 et 10.");
                return;
            }
            lm.setScale(category, scale);
            MessageUtil.send(sender, "&aTaille du leaderboard '" + category.key + "' réglée sur " + scale + ".");
            return;
        }

        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&cCette commande doit être exécutée par un joueur (elle utilise ta position).");
            return;
        }

        lm.spawn(category, player.getLocation());
        MessageUtil.send(sender, "&aLeaderboard '" + category.key + "' (top 10) spawné à ta position !");
    }

    private void handleResetStats(CommandSender sender) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        plugin.getStatsManager().resetStats();
        MessageUtil.send(sender, "&aToutes les statistiques ont été réinitialisées.");
    }

    // ================= UTILITAIRES =================

    private boolean checkAdminAndPlayer(CommandSender sender) {
        if (!sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return false;
        }
        if (!(sender instanceof Player)) {
            MessageUtil.send(sender, "&cCette commande doit être exécutée par un joueur (elle utilise ta position).");
            return false;
        }
        return true;
    }

    private Team parseTeam(String arg) {
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "black", "noir" -> Team.BLACK;
            case "white", "blanc" -> Team.WHITE;
            default -> null;
        };
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&8&m----------&r &bSpaceShip &8&m----------");
        MessageUtil.send(sender, "&e/ss join <nom> &7- Rejoindre une arène");
        MessageUtil.send(sender, "&e/ss joinrandom &7- Rejoindre une arène au hasard");
        MessageUtil.send(sender, "&e/ss leave &7- Quitter la partie en cours");
        MessageUtil.send(sender, "&e/ss list &7- Lister toutes les arènes");
        MessageUtil.send(sender, "&e/ss arenas &7- Ouvrir le menu pour rejoindre une arène");
        MessageUtil.send(sender, "&e/ss info [nom] &7- Voir l'état d'une arène");
        MessageUtil.send(sender, "&e/ss stats [pseudo] &7- Voir tes statistiques (ou celles d'un joueur)");
        MessageUtil.send(sender, "&e/ss top [kd|kills|wins|games] &7- Voir le classement des meilleurs joueurs");
        if (sender.hasPermission("spaceship.admin")) {
            MessageUtil.send(sender, "&c/ss create <nom> &7- Créer une nouvelle arène");
            MessageUtil.send(sender, "&c/ss delete <nom> &7- Supprimer une arène");
            MessageUtil.send(sender, "&c/ss setlobby <nom> &7- Définir le point de lobby");
            MessageUtil.send(sender, "&c/ss setzonecount <nom> <5|7|9> &7- Définir le nombre de zones du vaisseau");
            MessageUtil.send(sender, "&c/ss setspawn <nom> <black|white> <mid|base1|base2|base3|base4> <index> &7- Définir/remplacer un spawn de zone");
            MessageUtil.send(sender, "&c/ss delspawn <nom> <black|white> <mid|base1|base2|base3|base4> <index> &7- Supprimer un spawn de zone");
            MessageUtil.send(sender, "&c/ss setgoal <nom> <black|white> <mid|base1|base2|base3|base4> <pos1|pos2> &7- Définir le but d'une zone");
            MessageUtil.send(sender, "&c/ss setgamezone <nom> <pos1|pos2> &7- Définir la zone de jeu (protection + restauration)");
            MessageUtil.send(sender, "&c/ss setmaxplayers <nom> <nombre> &7- Définir le nombre max de joueurs de l'arène (0 = global)");
            MessageUtil.send(sender, "&c/ss start <nom> &7- Forcer le démarrage");
            MessageUtil.send(sender, "&c/ss stop <nom> &7- Forcer l'arrêt");
            MessageUtil.send(sender, "&c/ss resetstats &7- Réinitialiser les statistiques");
            MessageUtil.send(sender, "&b/ss leaderboard <victoires|kills|kd|parties> &7- Spawner un leaderboard top 10 à ta position");
            MessageUtil.send(sender, "&b/ss leaderboard <catégorie> remove &7- Supprimer ce leaderboard");
            MessageUtil.send(sender, "&b/ss leaderboard <catégorie> size <taille> &7- Régler la taille de l'hologramme (ex: 1.5)");
            MessageUtil.send(sender, "&8&m----------&r &dScoreboard &8&m----------");
            MessageUtil.send(sender, "&d/ss setsbserver <nom> &7- Définir le nom du serveur");
            MessageUtil.send(sender, "&d/ss setsbgame <nom> &7- Définir le nom du jeu");
            MessageUtil.send(sender, "&d/ss setsbtitle <titre> &7- Définir le titre");
            MessageUtil.send(sender, "&d/ss setsblines <lignes> &7- Définir les lignes (| pour séparer)");
            MessageUtil.send(sender, "&d/ss reloadsb &7- Recharger la config");
            MessageUtil.send(sender, "&d/ss sbinfo &7- Voir les infos du scoreboard");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("join", "joinrandom", "leave", "info", "list", "arenas", "stats", "top"));
            if (sender.hasPermission("spaceship.admin")) {
                options.addAll(List.of("create", "delete", "setlobby", "setzonecount", "setspawn", "delspawn", "setgoal", "setgamezone", "setmaxplayers", "start", "stop"));
                options.addAll(List.of("setsbserver", "setsbgame", "setsbtitle", "setsblines", "reloadsb", "sbinfo"));
                options.addAll(List.of("resetstats", "leaderboard"));
            }
            return filterStartingWith(options, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // Le 2e argument est presque toujours un nom d'arène existant (sauf pour "create").
        if (args.length == 2 && Set.of("join", "info", "delete", "setlobby", "setzonecount", "setspawn", "delspawn", "setgoal", "setgamezone", "setmaxplayers", "start", "stop").contains(sub)) {
            return filterStartingWith(new ArrayList<>(plugin.getArenaManager().getNames()), args[1]);
        }

        if (args.length == 3 && sub.equals("setzonecount")) {
            return filterStartingWith(List.of("5", "7", "9"), args[2]);
        }

        if (args.length == 3 && (sub.equals("setspawn") || sub.equals("delspawn") || sub.equals("setgoal"))) {
            return filterStartingWith(List.of("black", "white"), args[2]);
        }

        if (args.length == 3 && sub.equals("setgamezone")) {
            return filterStartingWith(List.of("pos1", "pos2"), args[2]);
        }

        if (args.length == 4 && (sub.equals("setspawn") || sub.equals("delspawn"))) {
            return filterStartingWith(List.of("mid", "base1", "base2", "base3", "base4"), args[3]);
        }

        if (args.length == 4 && sub.equals("setgoal")) {
            return filterStartingWith(List.of("mid", "base1", "base2", "base3", "base4"), args[3]);
        }

        if (args.length == 5 && sub.equals("setgoal")) {
            return filterStartingWith(List.of("pos1", "pos2"), args[4]);
        }

        if (args.length == 5 && (sub.equals("setspawn") || sub.equals("delspawn"))) {
            return filterStartingWith(List.of("1", "2", "3", "4"), args[4]);
        }

        if (args.length == 2 && sub.equals("top")) {
            return filterStartingWith(List.of("kd", "kills", "wins", "games"), args[1]);
        }

        if (args.length == 2 && sub.equals("leaderboard")) {
            return filterStartingWith(List.of("victoires", "kills", "kd", "parties"), args[1]);
        }

        if (args.length == 3 && sub.equals("leaderboard")) {
            return filterStartingWith(List.of("remove", "size"), args[2]);
        }

        if (args.length == 4 && sub.equals("leaderboard") && args[2].equalsIgnoreCase("size")) {
            return filterStartingWith(List.of("0.5", "1.0", "1.5", "2.0", "3.0"), args[3]);
        }

        if (args.length == 2 && sub.equals("stats")) {
            List<String> onlineNames = new ArrayList<>();
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) onlineNames.add(p.getName());
            return filterStartingWith(onlineNames, args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filterStartingWith(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                result.add(option);
            }
        }
        return result;
    }
}
