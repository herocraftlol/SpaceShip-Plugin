package com.spaceship.plugin;

import com.spaceship.plugin.commands.SpaceShipCommand;
import com.spaceship.plugin.game.ArenaManager;
import com.spaceship.plugin.game.KitManager;
import com.spaceship.plugin.gui.ArenaGUI;
import com.spaceship.plugin.gui.ArenaGUIListener;
import com.spaceship.plugin.hologram.CategoryLeaderboardManager;
import com.spaceship.plugin.listeners.ArenaProtectionListener;
import com.spaceship.plugin.listeners.BlockPlaceListener;
import com.spaceship.plugin.listeners.ForceStartItemListener;
import com.spaceship.plugin.listeners.LeaveItemListener;
import com.spaceship.plugin.listeners.PlayerConnectionListener;
import com.spaceship.plugin.listeners.PlayerDamageListener;
import com.spaceship.plugin.listeners.PlayerDeathListener;
import com.spaceship.plugin.listeners.PlayerItemListener;
import com.spaceship.plugin.listeners.PlayerMoveListener;
import com.spaceship.plugin.listeners.TeamSelectListener;
import com.spaceship.plugin.scoreboard.ScoreboardManager;
import com.spaceship.plugin.stats.StatsManager;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public class SpaceShipPlugin extends JavaPlugin {

    private ArenaManager         arenaManager;
    private ScoreboardManager    scoreboardManager;
    private StatsManager         statsManager;
    private ArenaGUI             arenaGUI;
    private CategoryLeaderboardManager leaderboardManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.arenaManager      = new ArenaManager(this);
        this.arenaManager.loadAll();
        this.scoreboardManager = new ScoreboardManager(this);
        this.statsManager      = new StatsManager(this);
        this.leaderboardManager = new CategoryLeaderboardManager(this);
        KitManager.init(this);

        this.arenaGUI = new ArenaGUI(this);

        // Respawn instantané
        for (World world : getServer().getWorlds()) {
            world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        }

        // Commandes
        SpaceShipCommand commandExecutor = new SpaceShipCommand(this);
        getCommand("ss").setExecutor(commandExecutor);
        getCommand("ss").setTabCompleter(commandExecutor);
        getCommand("ssarenas").setExecutor((sender, command, label, args) -> {
            commandExecutor.onCommand(sender, command, label, new String[]{"arenas"});
            return true;
        });

        // Listeners
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerDamageListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this), this);
        getServer().getPluginManager().registerEvents(new TeamSelectListener(this), this);
        getServer().getPluginManager().registerEvents(new ArenaProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new ForceStartItemListener(this), this);
        getServer().getPluginManager().registerEvents(new LeaveItemListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerItemListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockPlaceListener(this), this);
        getServer().getPluginManager().registerEvents(new ArenaGUIListener(this, arenaGUI), this);

        getLogger().info("SpaceShip activé ! (" + arenaManager.getNames().size() + " arène(s) chargée(s))");
    }

    @Override
    public void onDisable() {
        if (leaderboardManager != null) leaderboardManager.despawnAll();
        if (scoreboardManager != null) scoreboardManager.stop();
        if (statsManager      != null) statsManager.saveStats();
        if (arenaManager      != null) { arenaManager.stopAll(); arenaManager.saveAll(); }
        getLogger().info("SpaceShip désactivé.");
    }

    public ArenaManager         getArenaManager()      { return arenaManager; }
    public ArenaGUI             getArenaGUI()           { return arenaGUI; }
    public ScoreboardManager    getScoreboardManager()  { return scoreboardManager; }
    public StatsManager         getStatsManager()       { return statsManager; }
    public CategoryLeaderboardManager getLeaderboardManager() { return leaderboardManager; }
}
