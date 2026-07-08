package com.spaceship.plugin.hologram;

import com.spaceship.plugin.SpaceShipPlugin;
import com.spaceship.plugin.stats.StatsManager;
import com.spaceship.plugin.stats.StatsManager.PlayerStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gère 4 hologrammes de leaderboard INDÉPENDANTS, un par catégorie :
 *   - VICTOIRES : top 10 par victoires
 *   - KILLS     : top 10 par kills
 *   - KD        : top 10 par ratio K/D
 *   - PARTIES   : top 10 par parties jouées
 *
 * Chaque catégorie peut être spawnée/déspawnée séparément, à un endroit
 * différent, via /ss leaderboard <catégorie> [remove|size <taille>].
 *
 * La taille (échelle) de chaque hologramme peut être réglée précisément via
 * /ss leaderboard <catégorie> size <taille> (ex: 1.5 = 1,5x plus grand).
 */
public class CategoryLeaderboardManager {

    // ── Catégories ─────────────────────────────────────────────────────────────

    public enum Category {
        VICTOIRES("victoires", "🏆 TOP VICTOIRES", NamedTextColor.GOLD),
        KILLS("kills", "⚔ TOP KILLS", NamedTextColor.RED),
        KD("kd", "💀 TOP K/D", NamedTextColor.LIGHT_PURPLE),
        PARTIES("parties", "🎮 TOP PARTIES JOUÉES", NamedTextColor.AQUA);

        public final String        key;
        public final String        title;
        public final NamedTextColor color;

        Category(String key, String title, NamedTextColor color) {
            this.key   = key;
            this.title = title;
            this.color = color;
        }

        public static Category fromKey(String key) {
            for (Category c : values()) {
                if (c.key.equalsIgnoreCase(key)) return c;
            }
            return null;
        }
    }

    private static final double LINE_GAP               = 0.27;
    private static final String HOLOGRAM_FILE           = "leaderboards.yml";
    private static final int    TOP_SIZE                = 10;
    /** Échelle par défaut d'un hologramme (taille normale). */
    private static final double DEFAULT_SCALE           = 1.0;
    /** Rafraîchissement automatique : toutes les 10 secondes. */
    private static final long   REFRESH_INTERVAL_TICKS  = 20L * 10;

    private final SpaceShipPlugin plugin;
    private final File             cfgFile;
    private FileConfiguration      cfg;
    private final NamespacedKey    pdcKey;

    // Données par catégorie active
    private final Map<Category, Location>     locations    = new EnumMap<>(Category.class);
    private final Map<Category, List<UUID>>   lineEntities = new EnumMap<>(Category.class);
    private final Map<Category, Double>       scales       = new EnumMap<>(Category.class);
    private BukkitTask refreshTask = null;

    public CategoryLeaderboardManager(SpaceShipPlugin plugin) {
        this.plugin  = plugin;
        this.cfgFile = new File(plugin.getDataFolder(), HOLOGRAM_FILE);
        this.pdcKey  = new NamespacedKey(plugin, "category_leaderboard");
        for (Category c : Category.values()) lineEntities.put(c, new ArrayList<>());
        loadConfig();
    }

    // ── Persistance ────────────────────────────────────────────────────────────

    private void loadConfig() {
        if (!cfgFile.exists()) return;
        cfg = YamlConfiguration.loadConfiguration(cfgFile);

        ConfigurationSection root = cfg.getConfigurationSection("locations");
        if (root == null) return;

        for (Category c : Category.values()) {
            ConfigurationSection s = root.getConfigurationSection(c.key);
            if (s == null) continue;
            String worldName = s.getString("world");
            World world = worldName == null ? null : plugin.getServer().getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("[SpaceShip] Leaderboard '" + c.key + "' : monde '" + worldName + "' introuvable au démarrage.");
                continue;
            }
            Location loc = new Location(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
            locations.put(c, loc);
            scales.put(c, s.getDouble("scale", DEFAULT_SCALE));
            Chunk chunk = world.getChunkAt(loc);
            world.addPluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
        }

        if (!locations.isEmpty()) {
            purgeOrphanArmorStands();
            for (Category c : locations.keySet()) buildLines(c);
            startRefreshTask();
        }
    }

    private void saveConfig() {
        if (cfg == null) cfg = new YamlConfiguration();
        cfg.set("locations", null);
        for (Map.Entry<Category, Location> e : locations.entrySet()) {
            String path = "locations." + e.getKey().key;
            Location loc = e.getValue();
            cfg.set(path + ".world", loc.getWorld().getName());
            cfg.set(path + ".x", loc.getX());
            cfg.set(path + ".y", loc.getY());
            cfg.set(path + ".z", loc.getZ());
            cfg.set(path + ".scale", scales.getOrDefault(e.getKey(), DEFAULT_SCALE));
        }
        try {
            cfgFile.getParentFile().mkdirs();
            cfg.save(cfgFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[SpaceShip] Impossible de sauvegarder leaderboards.yml : " + e.getMessage());
        }
    }

    // ── API publique ───────────────────────────────────────────────────────────

    /** Spawne (ou déplace) le leaderboard d'une catégorie à la position donnée. */
    public void spawn(Category category, Location loc) {
        despawnEntities(category);
        locations.put(category, loc.clone());
        scales.putIfAbsent(category, DEFAULT_SCALE);
        Chunk chunk = loc.getWorld().getChunkAt(loc);
        loc.getWorld().addPluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
        buildLines(category);
        startRefreshTask();
        saveConfig();
    }

    /**
     * Modifie la taille (échelle) de l'hologramme d'une catégorie déjà spawnée
     * et reconstruit immédiatement ses lignes avec la nouvelle taille.
     * @param scale multiplicateur de taille (1.0 = taille normale).
     */
    public void setScale(Category category, double scale) {
        if (!locations.containsKey(category)) return;
        scales.put(category, scale);
        refresh(category);
        saveConfig();
    }

    /** Supprime le leaderboard d'une catégorie. Renvoie false s'il n'était pas spawné. */
    public boolean despawn(Category category) {
        Location loc = locations.get(category);
        if (loc == null) return false;
        Chunk chunk = loc.getWorld().getChunkAt(loc);
        loc.getWorld().removePluginChunkTicket(chunk.getX(), chunk.getZ(), plugin);
        despawnEntities(category);
        locations.remove(category);
        scales.remove(category);
        saveConfig();
        if (locations.isEmpty()) stopRefreshTask();
        return true;
    }

    /** Supprime tous les leaderboards (appelé à l'arrêt du plugin). */
    public void despawnAll() {
        stopRefreshTask();
        for (Category c : Category.values()) despawnEntities(c);
        locations.clear();
        scales.clear();
    }

    public boolean isSpawned(Category category) { return locations.containsKey(category); }

    /** Rafraîchit manuellement toutes les catégories spawnées (relit la DB). */
    public void refreshAll() {
        for (Category c : new ArrayList<>(locations.keySet())) refresh(c);
    }

    private void refresh(Category category) {
        Location loc = locations.get(category);
        if (loc == null) return;
        despawnEntities(category);
        buildLines(category);
    }

    // ── Tâche de rafraîchissement automatique ──────────────────────────────────

    private void startRefreshTask() {
        if (refreshTask != null) return;
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::refreshAll,
                REFRESH_INTERVAL_TICKS,
                REFRESH_INTERVAL_TICKS
        );
    }

    private void stopRefreshTask() {
        if (refreshTask != null && !refreshTask.isCancelled()) refreshTask.cancel();
        refreshTask = null;
    }

    // ── Construction des lignes ────────────────────────────────────────────────

    private void buildLines(Category category) {
        Location loc = locations.get(category);
        if (loc == null) return;
        World w = loc.getWorld();
        StatsManager sm = plugin.getStatsManager();

        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(category.title).color(category.color).decorate(TextDecoration.BOLD));
        lines.add(sep());

        Comparator<PlayerStats> comparator = switch (category) {
            case VICTOIRES -> Comparator.comparingInt(s -> s.gamesWon);
            case KILLS     -> Comparator.comparingInt(s -> s.kills);
            case KD        -> Comparator.comparingDouble(PlayerStats::getKD);
            case PARTIES   -> Comparator.comparingInt(s -> s.gamesPlayed);
        };

        List<Map.Entry<UUID, PlayerStats>> top = sm.getTopPlayers(TOP_SIZE, comparator);

        if (top.isEmpty()) {
            lines.add(gray("  Aucune donnée."));
        } else {
            int rank = 1;
            for (Map.Entry<UUID, PlayerStats> entry : top) {
                lines.add(buildLine(category, rank, entry.getValue()));
                rank++;
            }
        }

        double scale = scales.getOrDefault(category, DEFAULT_SCALE);
        double lineGap = LINE_GAP * scale;
        double topY = loc.getY() + (lines.size() - 1) * lineGap;
        List<UUID> entities = lineEntities.get(category);
        for (int i = 0; i < lines.size(); i++) {
            Location lineLoc = loc.clone();
            lineLoc.setY(topY - i * lineGap);
            ArmorStand as = spawnStand(w, lineLoc, lines.get(i), scale);
            entities.add(as.getUniqueId());
        }
    }

    private Component buildLine(Category category, int rank, PlayerStats ps) {
        Component prefix = rankPrefix(rank)
                .append(Component.text(ps.name + "  ").color(NamedTextColor.WHITE));

        return switch (category) {
            case VICTOIRES -> prefix
                    .append(Component.text(ps.gamesWon + " wins").color(NamedTextColor.YELLOW))
                    .append(gray("  K/D: "))
                    .append(Component.text(String.valueOf(ps.getKD())).color(NamedTextColor.GREEN));
            case KILLS -> prefix
                    .append(Component.text(ps.kills + " kills").color(NamedTextColor.RED))
                    .append(gray("  morts: "))
                    .append(Component.text(String.valueOf(ps.deaths)).color(NamedTextColor.GRAY));
            case KD -> prefix
                    .append(Component.text("K/D: " + ps.getKD()).color(NamedTextColor.GREEN))
                    .append(gray("  (" + ps.kills + "K / " + ps.deaths + "D)"));
            case PARTIES -> {
                double wr = ps.gamesPlayed > 0
                        ? Math.round((double) ps.gamesWon / ps.gamesPlayed * 1000.0) / 10.0
                        : 0.0;
                yield prefix
                        .append(Component.text(ps.gamesPlayed + " parties").color(NamedTextColor.AQUA))
                        .append(gray("  WR: " + wr + "%"));
            }
        };
    }

    // ── Helpers visuels ────────────────────────────────────────────────────────

    private static Component rankPrefix(int rank) {
        NamedTextColor color = switch (rank) {
            case 1  -> NamedTextColor.GOLD;
            case 2  -> NamedTextColor.GRAY;
            case 3  -> NamedTextColor.RED;
            default -> NamedTextColor.WHITE;
        };
        return Component.text("#" + rank + " ").color(color).decorate(TextDecoration.BOLD);
    }

    private static Component sep() {
        return Component.text("─────────────────────").color(NamedTextColor.DARK_GRAY);
    }

    private static Component gray(String text) {
        return Component.text(text).color(NamedTextColor.GRAY);
    }

    // ── Spawn / despawn des armor stands ───────────────────────────────────────

    private ArmorStand spawnStand(World world, Location loc, Component name, double scale) {
        ArmorStand as = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);
        as.customName(name);
        as.setCustomNameVisible(true);
        as.setInvisible(true);
        as.setGravity(false);
        as.setInvulnerable(true);
        as.setSmall(true);
        as.setCollidable(false);
        as.setMarker(true);
        as.getPersistentDataContainer().set(pdcKey, PersistentDataType.STRING, "leaderboard");
        applyScale(as, scale);
        return as;
    }

    /**
     * Applique une échelle précise à l'entité via l'attribut générique SCALE
     * (disponible depuis Minecraft 1.20.5+ / Paper 1.20.5+), ce qui permet une
     * taille continue (ex: 0.5, 1.75, 3.0) au lieu du simple "petit/grand" de
     * {@link ArmorStand#setSmall(boolean)}.
     */
    private void applyScale(LivingEntity entity, double scale) {
        AttributeInstance attr = entity.getAttribute(Attribute.GENERIC_SCALE);
        if (attr != null) {
            attr.setBaseValue(scale);
        }
    }

    private void despawnEntities(Category category) {
        List<UUID> entities = lineEntities.get(category);
        for (UUID id : entities) {
            Entity e = plugin.getServer().getEntity(id);
            if (e != null) e.remove();
        }
        entities.clear();
    }

    private void purgeOrphanArmorStands() {
        Map<String, World> worlds = new HashMap<>();
        for (Location loc : locations.values()) worlds.put(loc.getWorld().getName(), loc.getWorld());
        for (World world : worlds.values()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof ArmorStand as) {
                    String val = as.getPersistentDataContainer().get(pdcKey, PersistentDataType.STRING);
                    if ("leaderboard".equals(val)) as.remove();
                }
            }
        }
    }
}
