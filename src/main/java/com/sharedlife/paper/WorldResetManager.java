package com.sharedlife.paper;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Locale;

public class WorldResetManager {

    private final SharedLifePlugin plugin;
    private World lobbyWorld;
    private Location platformLocation;

    public WorldResetManager(SharedLifePlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        String lobbyName = plugin.getConfig().getString("world-reset.lobby-world", "sharedlife_lobby");
        lobbyWorld = Bukkit.getWorld(lobbyName);
        if (lobbyWorld == null) {
            plugin.getLogger().info("Creating lobby world: " + lobbyName);
            WorldCreator creator = new WorldCreator(lobbyName);
            creator.environment(World.Environment.NORMAL);
            lobbyWorld = Bukkit.createWorld(creator);
        }

        buildPlatform();
    }

    public void buildPlatform() {
        if (lobbyWorld == null) {
            return;
        }

        int platformY = plugin.getConfig().getInt("world-reset.platform-y", 240);
        int radius = plugin.getConfig().getInt("world-reset.platform-radius", 4);

        // Build a safe sky platform
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                // Platform floor
                Block floor = lobbyWorld.getBlockAt(x, platformY, z);
                if (x == 0 && z == 0) {
                    floor.setType(Material.SEA_LANTERN, false);
                } else if (Math.abs(x) == radius && Math.abs(z) == radius) {
                    floor.setType(Material.SEA_LANTERN, false);
                } else {
                    floor.setType(Material.OBSIDIAN, false);
                }

                // Air space
                for (int y = platformY + 1; y <= platformY + 3; y++) {
                    lobbyWorld.getBlockAt(x, y, z).setType(Material.AIR, false);
                }

                // Perimeter barrier walls
                if (Math.abs(x) == radius || Math.abs(z) == radius) {
                    lobbyWorld.getBlockAt(x, platformY + 1, z).setType(Material.BARRIER, false);
                    lobbyWorld.getBlockAt(x, platformY + 2, z).setType(Material.BARRIER, false);
                }

                // Barrier roof to prevent jumping/falling out
                lobbyWorld.getBlockAt(x, platformY + 4, z).setType(Material.BARRIER, false);
            }
        }

        platformLocation = new Location(lobbyWorld, 0.5, platformY + 1.0, 0.5, 0f, 0f);
    }

    public Location getPlatformLocation() {
        if (platformLocation == null) {
            buildPlatform();
        }
        return platformLocation;
    }

    public void triggerWipe(Player causePlayer, DamageCause cause, String deathDetails) {
        if (plugin.isResetting()) {
            return;
        }

        plugin.setResetting(true);

        String victimName = (causePlayer != null) ? causePlayer.getName() : "Team";
        String causeName = (cause != null) ? cause.name() : "FATAL_DAMAGE";
        String desc = (deathDetails != null && !deathDetails.isBlank())
                ? deathDetails
                : (victimName + " died from " + causeName);

        String locInfo = "Unknown Location";
        if (causePlayer != null && causePlayer.getLocation() != null) {
            Location loc = causePlayer.getLocation();
            locInfo = String.format(Locale.US, "%s (X: %.1f, Y: %.1f, Z: %.1f)",
                    loc.getWorld() != null ? loc.getWorld().getName() : "world",
                    loc.getX(), loc.getY(), loc.getZ());
        }

        // 1. Prominent Console Output
        plugin.getLogger().warning("======================================================================");
        plugin.getLogger().warning(" [DEATH / HARDCORE WIPE] " + desc);
        plugin.getLogger().warning(" Victim: " + victimName);
        plugin.getLogger().warning(" Cause: " + causeName);
        plugin.getLogger().warning(" Location: " + locInfo);
        plugin.getLogger().warning(" Action: Moving players to sky platform & generating new world...");
        plugin.getLogger().warning("======================================================================");

        // 2. In-Game Chat Broadcast
        Bukkit.broadcastMessage("§c☠ =========================================");
        Bukkit.broadcastMessage("§c§l           HARDCORE TEAM WIPE!");
        Bukkit.broadcastMessage("§e   " + desc);
        Bukkit.broadcastMessage("§7   Moving players to the high platform while");
        Bukkit.broadcastMessage("§7   a brand new world is generated...");
        Bukkit.broadcastMessage("§c☠ =========================================");

        Location platform = getPlatformLocation();

        // 3. In-Game Screen Notice (Using compact subtitle font to reduce text size)
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setInvulnerable(true);
            player.setFireTicks(0);
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.clearActivePotionEffects();
            player.setExp(0.0f);
            player.setLevel(0);
            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.setSaturation(5.0f);
            player.teleport(platform);

            // Compact subtitle text (non-intrusive)
            player.sendTitle("", "§c" + desc, 10, 70, 20);
            player.playSound(platform, Sound.ENTITY_WITHER_DEATH, 0.8f, 0.8f);
        }

        // Generate the new run world
        String newWorldName = "hardcore_world_" + System.currentTimeMillis();
        plugin.getLogger().info("[WORLD-RESET] Generating new run world: " + newWorldName);

        World oldRunWorld = plugin.getActiveWorld();
        WorldCreator creator = new WorldCreator(newWorldName);
        creator.environment(World.Environment.NORMAL);
        World newWorld = Bukkit.createWorld(creator);

        if (newWorld == null) {
            plugin.getLogger().severe("[WORLD-RESET] Failed to create new world! Players remain safe on the platform.");
            Bukkit.broadcastMessage("§cError generating new world! Please contact server administrator.");
            return;
        }

        int waitSeconds = Math.max(3, plugin.getConfig().getInt("world-reset.platform-wait-seconds", 5));

        new BukkitRunnable() {
            int remaining = waitSeconds;

            @Override
            public void run() {
                if (remaining > 0) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        // Compact subtitle text instead of screen-filling title
                        player.sendTitle("", "§eGenerating new world... §f(" + remaining + "s)", 0, 25, 5);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);
                    }
                    remaining--;
                    return;
                }

                // Countdown finished! Deploy players into the new world
                cancel();

                Location spawn = newWorld.getSpawnLocation();
                Block highest = newWorld.getHighestBlockAt(spawn);
                Location safeSpawn = highest.getLocation().add(0.5, 1.0, 0.5);

                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.teleport(safeSpawn);
                    player.setGameMode(GameMode.SURVIVAL);
                    player.setInvulnerable(false);
                    player.setHealth(plugin.getMaxHealth());
                    player.setFoodLevel(20);
                    player.setSaturation(5.0f);
                    player.setExhaustion(0.0f);

                    // Compact subtitle text for new run
                    player.sendTitle("", "§aNew Hardcore Run! §7Survive together.", 10, 60, 20);
                    player.playSound(safeSpawn, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
                }

                // Reset shared state
                plugin.setSharedHealth(plugin.getMaxHealth());
                plugin.setSharedFood(20);
                plugin.setSharedSaturation(5.0f);
                plugin.setSharedExhaustion(0.0);
                plugin.setActiveWorld(newWorld);

                plugin.getConfig().set("state.run-world", newWorld.getName());
                plugin.saveConfig();

                plugin.setResetting(false);
                plugin.syncAllVitals();

                plugin.getLogger().info("[WORLD-RESET] Successfully deployed all players to " + newWorldName);

                // Safely unload old world to conserve memory
                if (plugin.getConfig().getBoolean("world-reset.unload-old-worlds", true)
                        && oldRunWorld != null
                        && !oldRunWorld.equals(lobbyWorld)
                        && !oldRunWorld.getName().equals("world")
                        && !oldRunWorld.getName().equals(newWorldName)) {
                    Bukkit.unloadWorld(oldRunWorld, false);
                    plugin.getLogger().info("[WORLD-RESET] Unloaded previous world: " + oldRunWorld.getName());
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }
}
