package com.spaceship.plugin.tournament;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Commande /sstournament : gère la création, l'inscription et le déroulement des
 * tournois SpaceShip (bracket à élimination directe, chaque match étant une vraie
 * partie SpaceShip jouée sur une arène existante).
 *
 *   /sstournament create <nom> [tailleEquipe] [maxEquipes] [arène]  - crée un tournoi (admin)
 *   /sstournament join <nom> [tagEquipe]                            - s'inscrire (tagEquipe requis si tailleEquipe > 1)
 *   /sstournament leave <nom>                                       - se désinscrire
 *   /sstournament start <nom>                                       - démarrer le bracket (admin)
 *   /sstournament cancel <nom>                                      - annuler le tournoi (admin)
 *   /sstournament list                                              - lister les tournois
 *   /sstournament info <nom>                                        - voir l'état/bracket d'un tournoi
 */
public class TournamentCommand implements CommandExecutor, TabCompleter {

    private final SpaceShipPlugin plugin;

    public TournamentCommand(SpaceShipPlugin plugin) {
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
            case "join" -> handleJoin(sender, args);
            case "leave" -> handleLeave(sender, args);
            case "start" -> handleStart(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "list" -> handleList(sender);
            case "info", "bracket" -> handleInfo(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.tournament.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /sstournament create <nom> [tailleEquipe] [maxEquipes] [arène]");
            return;
        }
        String name = args[1];
        int teamSize = args.length >= 3 ? parseIntOr(args[2], 1) : 1;
        int maxSlots = args.length >= 4 ? parseIntOr(args[3], 8) : 8;
        String linkedArena = args.length >= 5 ? args[4] : null;

        java.util.UUID creator = sender instanceof Player p ? p.getUniqueId() : null;

        TournamentManager.CreateResult result = plugin.getTournamentManager()
                .create(name, teamSize, maxSlots, creator, linkedArena);

        switch (result) {
            case OK -> MessageUtil.send(sender, "&aTournoi '" + name + "' créé ! (équipes de " + teamSize
                    + ", " + maxSlots + " équipes max). Les joueurs peuvent rejoindre avec /sstournament join " + name);
            case ALREADY_EXISTS -> MessageUtil.send(sender, "&cUn tournoi '" + name + "' existe déjà.");
            case INVALID_SIZE -> MessageUtil.send(sender, "&cTaille d'équipe ou nombre d'équipes invalide (min 2 équipes).");
            case INVALID_ARENA -> MessageUtil.send(sender, "&cAucune arène SpaceShip nommée '" + linkedArena + "' n'existe.");
        }
    }

    private void handleJoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&cCette commande doit être exécutée par un joueur.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /sstournament join <nom> [tagEquipe]");
            return;
        }
        String teamTag = args.length >= 3 ? args[2] : null;
        TournamentManager.JoinResult result = plugin.getTournamentManager().join(args[1], player, teamTag);

        switch (result) {
            case OK -> MessageUtil.send(sender, "&aInscription réussie au tournoi '" + args[1] + "' !");
            case NOT_FOUND -> MessageUtil.send(sender, "&cAucun tournoi '" + args[1] + "' n'existe.");
            case NOT_OPEN -> MessageUtil.send(sender, "&cLes inscriptions pour ce tournoi sont fermées.");
            case FULL -> MessageUtil.send(sender, "&cCe tournoi est complet.");
            case ALREADY_REGISTERED -> MessageUtil.send(sender, "&cTu es déjà inscrit à ce tournoi.");
            case TEAM_FULL -> MessageUtil.send(sender, "&cCette équipe est déjà complète.");
            case TEAM_NAME_REQUIRED -> MessageUtil.send(sender, "&cCe tournoi nécessite une équipe : /sstournament join "
                    + args[1] + " <tagEquipe>");
        }
    }

    private void handleLeave(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, "&cCette commande doit être exécutée par un joueur.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /sstournament leave <nom>");
            return;
        }
        boolean left = plugin.getTournamentManager().leave(args[1], player);
        MessageUtil.send(sender, left ? "&aTu as quitté le tournoi '" + args[1] + "'."
                : "&cImpossible de quitter ce tournoi (inexistant, déjà démarré, ou tu n'es pas inscrit).");
    }

    private void handleStart(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.tournament.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /sstournament start <nom>");
            return;
        }
        TournamentManager.StartResult result = plugin.getTournamentManager().start(args[1]);
        switch (result) {
            case OK -> MessageUtil.send(sender, "&aTournoi '" + args[1] + "' démarré !");
            case NOT_FOUND -> MessageUtil.send(sender, "&cAucun tournoi '" + args[1] + "' n'existe.");
            case NOT_ENOUGH_TEAMS -> MessageUtil.send(sender, "&cIl faut au moins 2 équipes inscrites pour démarrer.");
            case ALREADY_STARTED -> MessageUtil.send(sender, "&cCe tournoi est déjà démarré ou terminé.");
        }
    }

    private void handleCancel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("spaceship.tournament.admin")) {
            MessageUtil.send(sender, "&cTu n'as pas la permission.");
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /sstournament cancel <nom>");
            return;
        }
        boolean cancelled = plugin.getTournamentManager().cancel(args[1]);
        MessageUtil.send(sender, cancelled ? "&aTournoi '" + args[1] + "' annulé."
                : "&cAucun tournoi '" + args[1] + "' n'existe.");
    }

    private void handleList(CommandSender sender) {
        List<String> names = plugin.getTournamentManager().getNames();
        if (names.isEmpty()) {
            MessageUtil.send(sender, "&7Aucun tournoi en cours. Utilise /sstournament create <nom> pour en créer un.");
            return;
        }
        MessageUtil.send(sender, "&8&m----------&r &bTournois SpaceShip &8&m----------");
        for (String name : names) {
            Tournament t = plugin.getTournamentManager().get(name);
            if (t == null) continue;
            MessageUtil.send(sender, "&e" + t.getName() + " &7- " + t.getState() + " &7("
                    + t.getRegistered().size() + "/" + t.getMaxSlots() + " équipes)");
        }
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(sender, "&cUsage: /sstournament info <nom>");
            return;
        }
        Tournament t = plugin.getTournamentManager().get(args[1]);
        if (t == null) {
            MessageUtil.send(sender, "&cAucun tournoi '" + args[1] + "' n'existe.");
            return;
        }
        MessageUtil.send(sender, "&8&m----------&r &bTournoi: " + t.getName() + " &8&m----------");
        MessageUtil.send(sender, "&7État : &f" + t.getState());
        MessageUtil.send(sender, "&7Taille d'équipe : &f" + t.getTeamSize());
        MessageUtil.send(sender, "&7Équipes inscrites : &f" + t.getRegistered().size() + "&7/&f" + t.getMaxSlots());
        for (TournamentTeam team : t.getRegistered()) {
            MessageUtil.send(sender, "&7  - " + team.getDisplayName());
        }
        if (t.getState() == TournamentState.IN_PROGRESS) {
            List<BracketMatch> round = t.getCurrentRound();
            if (round != null) {
                MessageUtil.send(sender, "&7Tour en cours (" + (t.getCurrentRoundIndex() + 1) + "/" + t.getRounds().size() + ") :");
                for (BracketMatch match : round) {
                    String status = switch (match.getStatus()) {
                        case FINISHED -> "&aterminé (" + match.getWinner().getDisplayName() + "&a gagne)";
                        case ONGOING -> "&een cours sur &f" + match.getArenaName();
                        case BYE -> "&7BYE";
                        case PENDING -> "&een attente d'arène libre";
                        case WAITING_FOR_TEAMS -> "&7en attente des équipes";
                    };
                    MessageUtil.send(sender, "&7  " + match.getDisplayVersus() + " &7- " + status);
                }
            }
        }
        if (t.getState() == TournamentState.FINISHED && t.getChampion() != null) {
            MessageUtil.send(sender, "&6Champion : " + t.getChampion().getDisplayName());
        }
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&8&m----------&r &bTournois SpaceShip &8&m----------");
        MessageUtil.send(sender, "&e/sstournament join <nom> [tagEquipe] &7- S'inscrire");
        MessageUtil.send(sender, "&e/sstournament leave <nom> &7- Se désinscrire");
        MessageUtil.send(sender, "&e/sstournament list &7- Lister les tournois");
        MessageUtil.send(sender, "&e/sstournament info <nom> &7- Voir l'état/bracket d'un tournoi");
        if (sender.hasPermission("spaceship.tournament.admin")) {
            MessageUtil.send(sender, "&c/sstournament create <nom> [tailleEquipe] [maxEquipes] [arène] &7- Créer un tournoi");
            MessageUtil.send(sender, "&c/sstournament start <nom> &7- Démarrer le bracket");
            MessageUtil.send(sender, "&c/sstournament cancel <nom> &7- Annuler un tournoi");
        }
    }

    private int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("join", "leave", "list", "info"));
            if (sender.hasPermission("spaceship.tournament.admin")) {
                options.addAll(List.of("create", "start", "cancel"));
            }
            return filterStartingWith(options, args[0]);
        }
        if (args.length == 2 && List.of("join", "leave", "start", "cancel", "info", "bracket")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            return filterStartingWith(plugin.getTournamentManager().getNames(), args[1]);
        }
        return new ArrayList<>();
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
