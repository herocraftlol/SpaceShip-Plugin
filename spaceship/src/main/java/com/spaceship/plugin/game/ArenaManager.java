package com.spaceship.plugin.game;

import com.spaceship.plugin.SpaceShipPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Registre central de toutes les arènes SpaceShip configurées sur le serveur.
 * Permet de créer, lister, supprimer et récupérer des arènes nommées, chacune
 * pilotée par sa propre instance de GameManager — ce qui permet plusieurs parties
 * SpaceShip indépendantes et simultanées dans un même monde (arena1, arena2, ...).
 *
 * La liste des noms d'arènes existantes est elle-même persistée dans un petit fichier
 * dédié (arenas/arenas.yml) afin de savoir, au redémarrage du plugin, quelles arènes
 * recharger depuis leurs fichiers individuels.
 */
public class ArenaManager {

    private final SpaceShipPlugin plugin;
    private final Map<String, GameManager> arenas = new LinkedHashMap<>();
    private final File registryFile;

    public ArenaManager(SpaceShipPlugin plugin) {
        this.plugin = plugin;
        File arenasDir = new File(plugin.getDataFolder(), "arenas");
        if (!arenasDir.exists()) {
            arenasDir.mkdirs();
        }
        this.registryFile = new File(arenasDir, "arenas.yml");
    }

    /**
     * Charge toutes les arènes connues depuis le disque (appelé au démarrage du plugin).
     */
    public void loadAll() {
        YamlConfiguration registry = YamlConfiguration.loadConfiguration(registryFile);
        List<String> names = registry.getStringList("arenas");
        for (String name : names) {
            createOrLoad(name);
        }
    }

    private void saveRegistry() {
        YamlConfiguration registry = new YamlConfiguration();
        registry.set("arenas", new ArrayList<>(arenas.keySet()));
        try {
            registry.save(registryFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder la liste des arènes : " + e.getMessage());
        }
    }

    /**
     * Crée une nouvelle arène vide avec le nom donné. Renvoie false si une arène
     * avec ce nom existe déjà.
     */
    public boolean create(String name) {
        String normalized = normalize(name);
        if (arenas.containsKey(normalized)) {
            return false;
        }
        createOrLoad(normalized);
        saveRegistry();
        return true;
    }

    private void createOrLoad(String name) {
        GameManager gm = new GameManager(plugin, name);
        gm.loadArenaConfig();
        gm.loadGameZoneSnapshot();
        arenas.put(name, gm);
    }

    /**
     * Supprime une arène de la mémoire et du registre (elle ne sera plus rechargée au
     * redémarrage). Si une partie est en cours sur cette arène, elle est arrêtée au préalable.
     *
     * Note : les fichiers .yml/.snapshot de cette arène restent sur le disque (dans le dossier
     * "arenas/"), au cas où l'admin voudrait les récupérer ; ils ne sont pas supprimés automatiquement.
     */
    public boolean delete(String name) {
        String normalized = normalize(name);
        GameManager gm = arenas.remove(normalized);
        if (gm == null) {
            return false;
        }
        gm.forceStop();
        saveRegistry();
        return true;
    }

    public GameManager get(String name) {
        return arenas.get(normalize(name));
    }

    public boolean exists(String name) {
        return arenas.containsKey(normalize(name));
    }

    public Collection<GameManager> getAll() {
        return Collections.unmodifiableCollection(arenas.values());
    }

    /**
     * Alias pour getAll() pour compatibilité.
     */
    public Collection<GameManager> getAllGameManagers() {
        return getAll();
    }

    public Set<String> getNames() {
        return Collections.unmodifiableSet(arenas.keySet());
    }

    /**
     * Sauvegarde la configuration de toutes les arènes (appelé à l'arrêt du plugin).
     */
    public void saveAll() {
        for (GameManager gm : arenas.values()) {
            gm.saveArenaConfig();
        }
    }

    /**
     * Arrête toutes les parties en cours sur toutes les arènes (appelé à l'arrêt du plugin).
     */
    public void stopAll() {
        for (GameManager gm : arenas.values()) {
            gm.forceStop();
        }
    }

    /**
     * Trouve l'arène (s'il y en a une) dans laquelle le joueur donné est actuellement engagé.
     */
    public GameManager findArenaOf(org.bukkit.entity.Player player) {
        for (GameManager gm : arenas.values()) {
            if (gm.isPlaying(player)) {
                return gm;
            }
        }
        return null;
    }

    /**
     * Trouve l'arène (s'il y en a une) sur laquelle le joueur donné est actuellement en
     * mode spectateur.
     */
    public GameManager findSpectatingArenaOf(org.bukkit.entity.Player player) {
        for (GameManager gm : arenas.values()) {
            if (gm.isSpectating(player)) {
                return gm;
            }
        }
        return null;
    }

    /**
     * Sélectionne la meilleure arène disponible pour une jointure aléatoire (commande
     * /ss joinrandom). Logique de priorité :
     *
     *   1. Parmi les arènes jouables (configurées, pas en partie, pas pleines), on
     *      privilégie celles qui ont déjà au moins un joueur en attente : ça permet de
     *      compléter une partie existante (1v1 ou plus) plutôt que d'en ouvrir une vide.
     *      S'il y a plusieurs arènes avec des joueurs déjà en attente, on choisit celle
     *      qui en a le plus (la plus proche de démarrer / la plus remplie), et en cas
     *      d'égalité, on tire au hasard parmi elles.
     *   2. Si aucune arène n'a de joueur en attente, on tire au hasard parmi toutes les
     *      arènes disponibles (vides comprises), pour ne jamais renvoyer "aucune arène"
     *      tant qu'au moins une arène jouable existe.
     *
     * Renvoie null si aucune arène n'est actuellement disponible (aucune configurée,
     * ou toutes pleines/en partie).
     */
    public GameManager findBestArenaForRandomJoin() {
        List<GameManager> joinable = new ArrayList<>();
        for (GameManager gm : arenas.values()) {
            if (isJoinable(gm)) {
                joinable.add(gm);
            }
        }
        if (joinable.isEmpty()) {
            return null;
        }

        int maxPlayers = 0;
        List<GameManager> withPlayers = new ArrayList<>();
        for (GameManager gm : joinable) {
            int count = gm.getPlayerCount();
            if (count > 0) {
                if (count > maxPlayers) {
                    maxPlayers = count;
                    withPlayers.clear();
                    withPlayers.add(gm);
                } else if (count == maxPlayers) {
                    withPlayers.add(gm);
                }
            }
        }

        List<GameManager> candidates = withPlayers.isEmpty() ? joinable : withPlayers;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /**
     * Une arène est "joignable" pour la jointure aléatoire si elle est correctement
     * configurée et si elle n'est pas déjà en train de jouer/de se terminer/pleine.
     */
    private boolean isJoinable(GameManager gm) {
        if (!gm.getArena().isFullyConfigured()) {
            return false;
        }
        GameState state = gm.getState();
        if (state == GameState.PLAYING || state == GameState.ROUND_RESET || state == GameState.ENDING) {
            return false;
        }
        return gm.getPlayerCount() < gm.getMaxPlayers();
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
