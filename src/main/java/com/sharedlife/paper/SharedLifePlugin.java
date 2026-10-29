package com.sharedlife.paper;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SharedLifePlugin extends JavaPlugin implements Listener {

    private double sharedHealth = 20.0;
    private int sharedFood = 20;
    private float sharedSaturation = 5.0f;
    private double sharedExhaustion = 0.0;
    private boolean resetting = false;

    private World activeWorld;
    private DamageManager damageManager;
    private HungerManager hungerManager;
    private EffectManager effectManager;
    private WorldResetManager worldResetManager;
    private HudManager hudManager;

    private BukkitTask hudTask;
    private BukkitTask starvationTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Load saved state
        this.sharedHealth = getConfig().getDouble("state.shared-health", getMaxHealth());
        this.sharedFood = getConfig().getInt("state.shared-food", 20);
        this.sharedSaturation = (float) getConfig().getDouble("state.shared-saturation", 5.0);
        this.sharedExhaustion = getConfig().getDouble("state.shared-exhaustion", 0.0);

        // Initialize managers
        this.damageManager = new DamageManager(this);
        this.hungerManager = new HungerManager(this);
        this.effectManager = new EffectManager(this);
        this.worldResetManager = new WorldResetManager(this);
        this.hudManager = new HudManager(this);

        // Initialize lobby world and waiting platform
        this.worldResetManager.initialize();

        // Determine active run world
        String savedRunWorld = getConfig().getString("state.run-world", "");
        if (savedRunWorld != null && !savedRunWorld.isBlank()) {
            this.activeWorld = Bukkit.getWorld(savedRunWorld);
            if (this.activeWorld == null) {
                WorldCreator wc = new WorldCreator(savedRunWorld);
                this.activeWorld = Bukkit.createWorld(wc);
            }
        }

        if (this.activeWorld == null) {
            if (!Bukkit.getWorlds().isEmpty()) {
                this.activeWorld = Bukkit.getWorlds().get(0);
            }
        }

        // Register event listeners
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(damageManager, this);
        getServer().getPluginManager().registerEvents(hungerManager, this);
        getServer().getPluginManager().registerEvents(effectManager, this);

        // Register commands
        PingCommand pingCommand = new PingCommand();
        if (getCommand("ping") != null) {
            getCommand("ping").setExecutor(pingCommand);
            getCommand("ping").setTabCompleter(pingCommand);
        }

        SharedLifeCommand sharedLifeCommand = new SharedLifeCommand(this);
        if (getCommand("sharedlife") != null) {
            getCommand("sharedlife").setExecutor(sharedLifeCommand);
            getCommand("sharedlife").setTabCompleter(sharedLifeCommand);
        }

        // Schedule periodic HUD update (every 10 ticks = 500ms)
        this.hudTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            hudManager.update();
        }, 10L, 10L);

        // Starvation damage check (every 80 ticks = 4 seconds)
        this.starvationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (resetting || sharedFood > 0) return;
            if (activeWorld != null && activeWorld.getDifficulty() == Difficulty.PEACEFUL) return;

            if (!Bukkit.getOnlinePlayers().isEmpty()) {
                Player victim = Bukkit.getOnlinePlayers().iterator().next();
                victim.damage(1.0); // Handled by damageManager
            }
        }, 80L, 80L);

        // Synchronize any already online players
        syncAllVitals();

        getLogger().info("SharedLife enabled successfully! Hardcore shared health and hunger active.");
    }

    @Override
    public void onDisable() {
        if (hudTask != null) {
            hudTask.cancel();
        }
        if (starvationTask != null) {
            starvationTask.cancel();
        }

        if (hudManager != null) {
            hudManager.cleanUp();
        }

        // Save state to config
        getConfig().set("state.shared-health", sharedHealth);
        getConfig().set("state.shared-food", sharedFood);
        getConfig().set("state.shared-saturation", sharedSaturation);
        getConfig().set("state.shared-exhaustion", sharedExhaustion);
        if (activeWorld != null) {
            getConfig().set("state.run-world", activeWorld.getName());
        }
        saveConfig();

        getLogger().info("SharedLife disabled successfully. State saved.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (resetting) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setInvulnerable(true);
            player.teleport(worldResetManager.getPlatformLocation());
            player.sendTitle("", "§eResetting world... Waiting on platform", 10, 60, 20);
        } else {
            // Teleport to active run world if joining player is in another world (e.g. lobby or old run world)
            if (activeWorld != null && !player.getWorld().equals(activeWorld)) {
                player.teleport(activeWorld.getSpawnLocation());
            }

            syncVitals(player);
            hudManager.onPlayerJoin(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        hudManager.onPlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (resetting) {
            event.setRespawnLocation(worldResetManager.getPlatformLocation());
        } else if (activeWorld != null) {
            event.setRespawnLocation(activeWorld.getSpawnLocation());
        }

        Bukkit.getScheduler().runTask(this, () -> syncVitals(event.getPlayer()));
    }

    public void syncVitals(Player player) {
        if (resetting) return;

        double targetHp = Math.max(0.5, Math.min(player.getMaxHealth(), sharedHealth));
        player.setHealth(targetHp);
        player.setFoodLevel(sharedFood);
        player.setSaturation(Math.min((float) sharedFood, sharedSaturation));
        player.setExhaustion(0.0f);
    }

    public void syncAllVitals() {
        if (resetting) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            syncVitals(p);
        }
    }

    public void addSharedExhaustion(double exhaustion) {
        if (resetting) return;

        this.sharedExhaustion += exhaustion;
        while (this.sharedExhaustion >= 4.0) {
            this.sharedExhaustion -= 4.0;
            if (this.sharedSaturation > 0.0f) {
                this.sharedSaturation = Math.max(0.0f, this.sharedSaturation - 1.0f);
            } else {
                this.sharedFood = Math.max(0, this.sharedFood - 1);
            }
        }

        syncAllVitals();
    }

    // Getters and Setters
    public double getSharedHealth() {
        return sharedHealth;
    }

    public void setSharedHealth(double sharedHealth) {
        this.sharedHealth = Math.max(0.0, Math.min(getMaxHealth(), sharedHealth));
    }

    public double getMaxHealth() {
        return getConfig().getDouble("health.max-health", 20.0);
    }

    public int getSharedFood() {
        return sharedFood;
    }

    public void setSharedFood(int sharedFood) {
        this.sharedFood = Math.max(0, Math.min(20, sharedFood));
    }

    public float getSharedSaturation() {
        return sharedSaturation;
    }

    public void setSharedSaturation(float sharedSaturation) {
        this.sharedSaturation = Math.max(0.0f, Math.min(20.0f, sharedSaturation));
    }

    public double getSharedExhaustion() {
        return sharedExhaustion;
    }

    public void setSharedExhaustion(double sharedExhaustion) {
        this.sharedExhaustion = Math.max(0.0, sharedExhaustion);
    }

    public boolean isResetting() {
        return resetting;
    }

    public void setResetting(boolean resetting) {
        this.resetting = resetting;
    }

    public World getActiveWorld() {
        return activeWorld;
    }

    public void setActiveWorld(World activeWorld) {
        this.activeWorld = activeWorld;
    }

    public DamageManager getDamageManager() {
        return damageManager;
    }

    public HungerManager getHungerManager() {
        return hungerManager;
    }

    public EffectManager getEffectManager() {
        return effectManager;
    }

    public WorldResetManager getWorldResetManager() {
        return worldResetManager;
    }

    public HudManager getHudManager() {
        return hudManager;
    }
}
