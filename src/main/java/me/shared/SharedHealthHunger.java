package me.shared;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SharedHealthHunger extends JavaPlugin implements Listener {

    private double sharedHealth = 20.0;
    private double sharedAbsorption = 0.0;
    private int sharedFood = 20;
    private float sharedSaturation = 5.0f;
    private float sharedExhaustion = 0.0f;
    private long regenTick = 0L;
    private boolean dirtyState = true;

    private final Map<UUID, Long> immunityMap = new ConcurrentHashMap<>();
    private final Map<UUID, DamageRecord> damageBuffer = new ConcurrentHashMap<>();
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<DamageCause, Long> hazardCooldowns = new ConcurrentHashMap<>();
    private long lastAoeTime = 0L;
    private double lastAoeDamage = 0.0;

    private static final long DAMAGE_COALESCE_WINDOW = 1200L;

    private boolean isHazard(DamageCause cause) {
        return cause == DamageCause.LAVA ||
               cause == DamageCause.FIRE ||
               cause == DamageCause.FIRE_TICK ||
               cause == DamageCause.HOT_FLOOR ||
               cause == DamageCause.DROWNING ||
               cause == DamageCause.FREEZE ||
               cause == DamageCause.SUFFOCATION ||
               cause == DamageCause.STARVATION ||
               cause == DamageCause.CONTACT ||
               cause == DamageCause.POISON ||
               cause == DamageCause.WITHER;
    }

    private long getHazardCooldown(DamageCause cause) {
        switch (cause) {
            case LAVA:
                return 450L;
            case STARVATION:
                return 1800L;
            case CONTACT:
                return 450L;
            case POISON:
                return 1000L;
            default:
                return 900L;
        }
    }

    private String currentWorldName = "lobby";
    private boolean isGenerating = false;
    private int runId = 1;
    private File dataFile;
    private int countdownSeconds = 5;
    private BossBar activeLoadingBar;

    static class DamageRecord {
        double totalDamage;
        String cause;
        long lastUpdate;

        DamageRecord(double totalDamage, String cause, long lastUpdate) {
            this.totalDamage = totalDamage;
            this.cause = cause;
            this.lastUpdate = lastUpdate;
        }
    }

    class LoadingTask extends BukkitRunnable {
        private int seconds;
        private final int totalSeconds;
        private final World world;
        private final BossBar bossBar;

        public LoadingTask(SharedHealthHunger plugin, int seconds, World world) {
            this.seconds = seconds;
            this.totalSeconds = seconds;
            this.world = world;
            this.bossBar = Bukkit.createBossBar(
                "§dGenerating Run #" + SharedHealthHunger.this.runId + "...",
                BarColor.PURPLE,
                BarStyle.SOLID
            );
            for (Player p : Bukkit.getOnlinePlayers()) {
                this.bossBar.addPlayer(p);
            }
            SharedHealthHunger.this.activeLoadingBar = this.bossBar;
        }

        @Override
        public void run() {
            if (seconds > 0) {
                double progress = Math.max(0.0, Math.min(1.0, (double) seconds / totalSeconds));
                bossBar.setProgress(progress);
                bossBar.setTitle("§dGenerating World... " + seconds + "s");

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!bossBar.getPlayers().contains(p)) {
                        bossBar.addPlayer(p);
                    }
                    if (!SharedHealthHunger.this.mutedPlayers.contains(p.getUniqueId())) {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
                    }
                }
                seconds--;
            } else {
                bossBar.removeAll();
                long immunityEnd = System.currentTimeMillis() + 5000L;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.removePotionEffect(PotionEffectType.NIGHT_VISION);
                    p.teleport(world.getSpawnLocation());
                    p.setGameMode(GameMode.SURVIVAL);
                    p.getInventory().clear();
                    SharedHealthHunger.this.immunityMap.put(p.getUniqueId(), immunityEnd);
                    p.sendMessage("§aGo! Run #" + SharedHealthHunger.this.runId + " has started!");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

                    Title title = Title.title(
                        Component.text("§aGO!"),
                        Component.text("§7Run #" + SharedHealthHunger.this.runId),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500))
                    );
                    p.showTitle(title);
                }
                SharedHealthHunger.this.isGenerating = false;
                SharedHealthHunger.this.activeLoadingBar = null;
                cancel();
            }
        }
    }

    private boolean checkWorldExists(String worldName) {
        File container = Bukkit.getWorldContainer();
        // Check root location
        if (new File(container, worldName).exists()) {
            return true;
        }
        // Check Paper 26.x dimension location
        if (new File(container, "world/dimensions/minecraft/" + worldName).exists()) {
            return true;
        }
        for (World w : Bukkit.getWorlds()) {
            if (new File(container, w.getName() + "/dimensions/minecraft/" + worldName).exists()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        loadData();

        boolean worldExists = checkWorldExists(currentWorldName);

        if (worldExists && sharedHealth > 0.0) {
            getLogger().info("✦ Found active run " + currentWorldName + ". Resuming...");
            if (Bukkit.getWorld(currentWorldName) == null) {
                createGameWorlds(currentWorldName);
            }
        } else {
            if (sharedHealth <= 0.0) {
                getLogger().info("✦ Previous run ended in death. Starting fresh...");
            } else {
                getLogger().info("✦ No run found. Generating new run...");
            }
            sharedHealth = 20.0;
            startNewRun();
        }

        // Damage buffer & immunity cleanup task (every 5 ticks)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, DamageRecord>> it = damageBuffer.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, DamageRecord> entry = it.next();
                DamageRecord record = entry.getValue();
                if (now - record.lastUpdate > DAMAGE_COALESCE_WINDOW) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p != null) {
                        flushDamage(p, record);
                    }
                    it.remove();
                }
            }
            immunityMap.entrySet().removeIf(entry -> now > entry.getValue());
        }, 20L, 5L);

        // Natural regen and absorption synchronization task (every 1 tick)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (isGenerating) return;
            int gamePlayers = getGamePlayers();
            if (gamePlayers == 0) return;

            // Detect new external absorption (e.g. golden apples)
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isGameWorld(p.getWorld())) {
                    double pAbs = p.getAbsorptionAmount();
                    if (pAbs > sharedAbsorption + 0.1) {
                        sharedAbsorption = pAbs;
                        dirtyState = true;
                    }
                }
            }

            // Sync absorption back to players if differing
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isGameWorld(p.getWorld())) {
                    if (Math.abs(p.getAbsorptionAmount() - sharedAbsorption) > 0.1) {
                        p.setAbsorptionAmount(sharedAbsorption);
                    }
                }
            }

            // Natural regeneration mechanic
            if (sharedFood >= 18 && sharedHealth < 20.0) {
                regenTick++;
                int regenRate = (sharedSaturation > 0.0f) ? 10 : 80;
                if (regenTick >= regenRate) {
                    regenTick = 0;
                    sharedHealth = Math.min(20.0, sharedHealth + 1.0);
                    if (sharedSaturation > 0.0f) {
                        sharedSaturation = Math.max(0.0f, sharedSaturation - 3.0f);
                    } else if (sharedFood > 0) {
                        if (Math.random() < 0.25) {
                            sharedFood -= 1;
                        }
                    }
                    dirtyState = true;
                }
            } else {
                regenTick = 0;
            }

            if (dirtyState) {
                forceSyncAll();
                dirtyState = false;
            }
        }, 20L, 1L);

        // Auto-save data task (every 60 seconds)
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, 1200L, 1200L);

        getLogger().info("SharedHealthHunger (Ver 1.26 - Burst Physics) enabled");
    }

    private void forceSyncAll() {
        Component bar = Component.text(String.format("❤ %.1f   ✦ %.1f   🍗 %d   Run: #%d",
            sharedHealth, sharedAbsorption, sharedFood, runId));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isGameWorld(p.getWorld())) {
                AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = (attr != null) ? attr.getValue() : 20.0;
                p.setHealth(Math.max(0.0, Math.min(sharedHealth, maxHealth)));
                p.setAbsorptionAmount(sharedAbsorption);
                p.setFoodLevel(sharedFood);
                p.setSaturation(sharedSaturation);
                p.setExhaustion(0.0f);
                p.sendActionBar(bar);
            }
        }
    }

    private boolean isGameWorld(World world) {
        return world != null && world.getName().startsWith("hc_run_");
    }

    private World getLobby() {
        World world = Bukkit.getWorld("world");
        if (world == null) {
            List<World> worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty()) {
                world = worlds.get(0);
            }
        }
        return world;
    }

    private void createGameWorlds(String baseName) {
        World overworld = new WorldCreator(baseName).createWorld();
        World nether = new WorldCreator(baseName + "_nether").environment(World.Environment.NETHER).createWorld();
        World theEnd = new WorldCreator(baseName + "_the_end").environment(World.Environment.THE_END).createWorld();

        for (World w : new World[]{overworld, nether, theEnd}) {
            if (w != null) {
                w.setDifficulty(Difficulty.HARD);
                try {
                    w.setHardcore(true);
                } catch (Throwable ignored) {
                }
                w.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, true);
            }
        }
    }

    @Override
    public void onDisable() {
        saveData();
        if (activeLoadingBar != null) {
            activeLoadingBar.removeAll();
            activeLoadingBar = null;
        }
        Bukkit.getBossBars().forEachRemaining(BossBar::removeAll);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("lobbysound") || command.getName().equalsIgnoreCase("mute")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command is for players only.");
                return true;
            }
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();
            if (mutedPlayers.contains(uuid)) {
                mutedPlayers.remove(uuid);
                player.sendMessage("§a✦ Lobby ticking sound enabled.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
            } else {
                mutedPlayers.add(uuid);
                player.sendMessage("§c✦ Lobby ticking sound muted.");
            }
            return true;
        }

        if (!sender.isOp() && !sender.hasPermission("shared.admin")) {
            sender.sendMessage("§cOnly OPs can use this.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("resetrun")) {
            runId = 0;
            sharedHealth = 0.0;
            saveData();
            Bukkit.broadcast(Component.text("§c✦ ADMIN HAS TRIGGERED A FULL RESET! ✦"));
            triggerGameover();
            return true;
        }

        if (command.getName().equalsIgnoreCase("setrun")) {
            if (args.length != 1) {
                return false;
            }
            try {
                runId = Integer.parseInt(args[0]);
                saveData();
                sender.sendMessage("§aRun ID set to " + runId + ". (Takes effect next death)");
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid number.");
            }
            return true;
        }

        return false;
    }

    public void startNewRun() {
        if (isGenerating) return;
        isGenerating = true;

        World lobby = getLobby();
        if (lobby != null) {
            lobby.setTime(18000L);
            lobby.setGameRule(GameRules.ADVANCE_TIME, false);
            lobby.setGameRule(GameRules.ADVANCE_WEATHER, false);

            for (int x = -25; x <= 25; x++) {
                for (int z = -25; z <= 25; z++) {
                    lobby.getBlockAt(x, 200, z).setType(Material.TINTED_GLASS);
                    if (x == -25 || x == 25 || z == -25 || z == 25) {
                        for (int y = 1; y <= 5; y++) {
                            lobby.getBlockAt(x, 200 + y, z).setType(Material.GLASS);
                        }
                    } else {
                        for (int y = 1; y <= 10; y++) {
                            lobby.getBlockAt(x, 200 + y, z).setType(Material.AIR);
                        }
                    }
                }
            }
        }

        Location lobbySpawn = lobby != null ? new Location(lobby, 0.5, 201.0, 0.5) : null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) {
                p.spigot().respawn();
            }
            if (lobbySpawn != null) {
                p.teleport(lobbySpawn);
            }
            p.setFireTicks(0);
            p.setArrowsInBody(0);
            for (PotionEffect effect : p.getActivePotionEffects()) {
                p.removePotionEffect(effect.getType());
            }
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.setLevel(0);
            p.setExp(0.0f);
            p.setHealth(20.0);
            p.setFoodLevel(20);
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 99999, 0, false, false));

            Title title = Title.title(
                Component.text("§d✦ GAME OVER ✦"),
                Component.text("§7Run #" + (runId + 1) + " Loading..."),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(1000))
            );
            p.showTitle(title);
        }

        damageBuffer.clear();
        immunityMap.clear();

        Bukkit.getScheduler().runTaskLater(this, () -> {
            unloadWorld(currentWorldName);
            unloadWorld(currentWorldName + "_nether");
            unloadWorld(currentWorldName + "_the_end");
            System.gc();

            Bukkit.getScheduler().runTaskLater(this, this::generateAndStart, 20L);
        }, 30L);
    }

    private void generateAndStart() {
        runId++;
        currentWorldName = "hc_run_" + runId;
        saveData();

        createGameWorlds(currentWorldName);
        World newWorld = Bukkit.getWorld(currentWorldName);
        if (newWorld == null) {
            getLogger().severe("Failed to load or create game world: " + currentWorldName);
            isGenerating = false;
            return;
        }

        int safeX = 0;
        int safeZ = 0;
        Random rand = new Random();
        boolean foundLand = false;

        getLogger().info("✦ Scanning for dry land...");
        for (int i = 0; i < 50; i++) {
            int testX = rand.nextInt(4000) - 2000;
            int testZ = rand.nextInt(4000) - 2000;
            int highestY = newWorld.getHighestBlockYAt(testX, testZ);
            Material groundType = newWorld.getBlockAt(testX, highestY - 1, testZ).getType();
            String name = groundType.name();

            if (!name.contains("WATER") && !name.contains("LAVA") && !name.contains("MAGMA")) {
                safeX = testX;
                safeZ = testZ;
                foundLand = true;
                getLogger().info("✦ Land found at: " + safeX + ", " + safeZ);
                break;
            }
        }

        if (!foundLand) {
            getLogger().warning("✦ Could not find land. Spawning at 0,0.");
        }

        int spawnY = newWorld.getHighestBlockYAt(safeX, safeZ) + 1;
        newWorld.setSpawnLocation(safeX, spawnY, safeZ);
        resetStats();

        new LoadingTask(this, countdownSeconds, newWorld).runTaskTimer(this, 0L, 20L);
    }

    private void unloadWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            world.setAutoSave(false);
            if (!Bukkit.unloadWorld(world, false)) {
                getLogger().warning("✦ Failed to unload world: " + worldName);
            }
        }
    }

    private int getGamePlayers() {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isGameWorld(p.getWorld())) {
                count++;
            }
        }
        return count;
    }

    public void loadData() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create data.yml: " + e.getMessage());
            }
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        if (config.contains("health")) {
            sharedHealth = config.getDouble("health");
        }
        if (config.contains("runId")) {
            runId = config.getInt("runId");
        }
        if (config.contains("countdown-seconds")) {
            countdownSeconds = config.getInt("countdown-seconds");
        } else {
            countdownSeconds = 5;
        }
        currentWorldName = "hc_run_" + runId;
    }

    public void saveData() {
        if (dataFile == null || !dataFile.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        config.set("health", sharedHealth);
        config.set("runId", runId);
        config.set("countdown-seconds", countdownSeconds);
        try {
            config.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    public void resetStats() {
        sharedHealth = 20.0;
        sharedFood = 20;
        sharedSaturation = 5.0f;
        sharedAbsorption = 0.0;
        sharedExhaustion = 0.0f;
        dirtyState = true;
        saveData();
    }

    public void triggerGameover() {
        if (isGenerating) return;
        startNewRun();
    }

    private void flushDamage(Player player, DamageRecord record) {
        String msg = String.format("§c☠ %s took %.1f dmg (%s)", player.getName(), record.totalDamage, record.cause);
        Bukkit.broadcast(Component.text(msg));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.isDead()) {
            player.spigot().respawn();
        }

        if (mutedPlayers.contains(player.getUniqueId())) {
            player.sendMessage("§7✦ Lobby sounds are currently muted. Use §f/mute §7to enable.");
        }

        World lobby = getLobby();
        if (isGenerating) {
            if (lobby != null) {
                player.teleport(new Location(lobby, 0.5, 201.0, 0.5));
            }
            player.setGameMode(GameMode.ADVENTURE);
            return;
        }

        World gameWorld = Bukkit.getWorld(currentWorldName);
        if (gameWorld != null) {
            if (!isGameWorld(player.getWorld())) {
                player.getInventory().clear();
                player.setLevel(0);
                player.setExp(0.0f);

                boolean teleported = false;
                for (Player teammate : Bukkit.getOnlinePlayers()) {
                    if (teammate != player && isGameWorld(teammate.getWorld())) {
                        player.teleport(teammate.getLocation());
                        player.sendMessage("§a✦ Teleported to teammate " + teammate.getName());
                        teleported = true;
                        break;
                    }
                }

                if (!teleported) {
                    player.teleport(gameWorld.getSpawnLocation());
                    player.sendMessage("§e✦ Joined run. (No teammates found to tp to)");
                }

                immunityMap.merge(player.getUniqueId(), System.currentTimeMillis() + 5000L, Math::max);
            }
            player.setGameMode(GameMode.SURVIVAL);
            sync(player);
        } else if (lobby != null) {
            player.teleport(new Location(lobby, 0.5, 201.0, 0.5));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        immunityMap.remove(event.getPlayer().getUniqueId());
        damageBuffer.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        World gameWorld = Bukkit.getWorld(currentWorldName);
        World lobby = getLobby();
        if (isGenerating || gameWorld == null) {
            if (lobby != null) {
                event.setRespawnLocation(new Location(lobby, 0.5, 201.0, 0.5));
            }
        } else {
            event.setRespawnLocation(gameWorld.getSpawnLocation());
            Bukkit.getScheduler().runTask(this, () -> {
                Player player = event.getPlayer();
                player.setGameMode(GameMode.SURVIVAL);
                player.getInventory().clear();
                sync(player);
            });
        }
    }

    private void sync(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = (attr != null) ? attr.getValue() : 20.0;
        player.setHealth(Math.max(0.0, Math.min(sharedHealth, maxHealth)));
        player.setAbsorptionAmount(sharedAbsorption);
        player.setFoodLevel(sharedFood);
        player.setSaturation(sharedSaturation);
        player.setExhaustion(0.0f);
    }

    @EventHandler
    public void onExhaustion(EntityExhaustionEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (!isGameWorld(player.getWorld())) return;

        float exhaustion = event.getExhaustion();
        event.setExhaustion(0.0f);

        int gamePlayers = getGamePlayers();
        if (gamePlayers == 0) gamePlayers = 1;

        sharedExhaustion += exhaustion / (float) gamePlayers;

        while (sharedExhaustion >= 4.0f) {
            sharedExhaustion -= 4.0f;
            if (sharedSaturation > 0.0f) {
                sharedSaturation = Math.max(0.0f, sharedSaturation - 1.0f);
            } else if (sharedFood > 0) {
                sharedFood -= 1;
            }
            dirtyState = true;
        }
    }

    @EventHandler
    public void onVanillaRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (isGenerating) return;

        if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.MAGIC ||
            event.getRegainReason() == EntityRegainHealthEvent.RegainReason.MAGIC_REGEN) {
            sharedHealth = Math.min(20.0, sharedHealth + event.getAmount());
            dirtyState = true;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        if (isGenerating || !isGameWorld(player.getWorld())) {
            event.setCancelled(true);
            World lobby = getLobby();
            if (lobby != null && player.getLocation().getY() < 0.0) {
                player.teleport(new Location(lobby, 0.5, 201.0, 0.5));
            }
            return;
        }

        if (event.isCancelled() || event.getFinalDamage() <= 0.0) {
            return;
        }

        long now = System.currentTimeMillis();
        long immunityEnd = immunityMap.getOrDefault(player.getUniqueId(), 0L);
        if (now < immunityEnd) {
            event.setCancelled(true);
            return;
        }

        DamageCause cause = event.getCause();

        // 1. Shared Environmental Hazard Cooldown (anti-multiplayer multiplication)
        if (isHazard(cause)) {
            long nextAllowed = hazardCooldowns.getOrDefault(cause, 0L);
            if (now < nextAllowed) {
                event.setCancelled(true);
                return;
            }
            hazardCooldowns.put(cause, now + getHazardCooldown(cause));
        }

        double finalDamage = event.getFinalDamage();
        if (cause == DamageCause.LAVA) {
            finalDamage *= 0.5;
        }

        // 2. Anti-Explosion / AOE Multi-Player Damage Amplifier
        if (cause == DamageCause.BLOCK_EXPLOSION || cause == DamageCause.ENTITY_EXPLOSION || cause == DamageCause.SONIC_BOOM) {
            if (now - lastAoeTime < 300L) {
                if (finalDamage > lastAoeDamage) {
                    double extra = finalDamage - lastAoeDamage;
                    lastAoeDamage = finalDamage;
                    finalDamage = extra;
                } else {
                    event.setCancelled(true);
                    return;
                }
            } else {
                lastAoeTime = now;
                lastAoeDamage = finalDamage;
            }
        }

        double effectiveHealth = sharedHealth + sharedAbsorption;
        if (finalDamage >= effectiveHealth) {
            // Fatal damage branch
            if (cause == DamageCause.VOID) {
                event.setCancelled(true);
                damageBuffer.clear();
                hazardCooldowns.clear();
                sharedHealth = 0.0;
                Bukkit.broadcast(Component.text("§c☠ " + player.getName() + " fell into the Void! (Run #" + runId + " Ended)"));
                triggerGameover();
                return;
            }

            boolean hasTotem = false;
            ItemStack totemStack = null;
            boolean isMainHand = false;

            ItemStack main = player.getInventory().getItemInMainHand();
            ItemStack off = player.getInventory().getItemInOffHand();

            if (main != null && main.getType() == Material.TOTEM_OF_UNDYING) {
                hasTotem = true;
                totemStack = main;
                isMainHand = true;
            } else if (off != null && off.getType() == Material.TOTEM_OF_UNDYING) {
                hasTotem = true;
                totemStack = off;
                isMainHand = false;
            }

            if (hasTotem) {
                event.setCancelled(true);
                if (totemStack.getAmount() > 1) {
                    totemStack.setAmount(totemStack.getAmount() - 1);
                } else {
                    if (isMainHand) {
                        player.getInventory().setItemInMainHand(null);
                    } else {
                        player.getInventory().setItemInOffHand(null);
                    }
                }

                sharedHealth = 4.0;
                DamageRecord prev = damageBuffer.remove(player.getUniqueId());
                if (prev != null) {
                    flushDamage(player, prev);
                }

                String causeClean = cause.name().toLowerCase().replace('_', ' ');
                flushDamage(player, new DamageRecord(finalDamage, causeClean, now));
                Bukkit.broadcast(Component.text("§6✦ " + player.getName() + " used a Totem of Undying!"));

                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
                    immunityMap.merge(p.getUniqueId(), now + 2000L, Math::max);
                }

                hazardCooldowns.clear();
                sharedAbsorption = 8.0;
                dirtyState = true;
                return;
            } else {
                event.setCancelled(true);
                damageBuffer.clear();
                hazardCooldowns.clear();
                sharedHealth = 0.0;
                String causeClean = cause.name().toLowerCase().replace('_', ' ');
                Bukkit.broadcast(Component.text("§c☠ " + player.getName() + " died to " + causeClean + " (Run #" + runId + " Ended)"));
                triggerGameover();
                return;
            }
        }

        // Non-fatal damage branch
        event.setDamage(0.0);
        player.playEffect(EntityEffect.HIT);

        // Feedback for teammates: subtle hit flinch & hurt sound so team is aware in real time
        for (Player teammate : Bukkit.getOnlinePlayers()) {
            if (teammate != player && isGameWorld(teammate.getWorld())) {
                teammate.playEffect(EntityEffect.HIT);
                teammate.playSound(teammate.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.4f, 1.2f);
            }
        }

        double damageApplied = finalDamage;
        if (sharedAbsorption > 0.0) {
            double absDmg = Math.min(finalDamage, sharedAbsorption);
            sharedAbsorption -= absDmg;
            damageApplied -= absDmg;
        }

        if (damageApplied > 0.0) {
            sharedHealth = Math.max(0.0, sharedHealth - damageApplied);
        }

        UUID uuid = player.getUniqueId();
        String causeClean = cause.name().toLowerCase().replace('_', ' ');
        DamageRecord record = damageBuffer.get(uuid);

        if (record != null && record.cause.equals(causeClean) && (now - record.lastUpdate <= DAMAGE_COALESCE_WINDOW)) {
            record.totalDamage += finalDamage;
            record.lastUpdate = now;
        } else {
            if (record != null) {
                flushDamage(player, record);
            }
            damageBuffer.put(uuid, new DamageRecord(finalDamage, causeClean, now));
        }

        dirtyState = true;
        long iFrameDuration = isHazard(cause) ? 400L : 500L;
        immunityMap.merge(uuid, now + iFrameDuration, Math::max);
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        if (!isGameWorld(player.getWorld())) return;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.getSaturation() > sharedSaturation) {
                sharedSaturation = player.getSaturation();
            }
            if (player.getFoodLevel() > sharedFood) {
                sharedFood = player.getFoodLevel();
            }
            dirtyState = true;
        }, 1L);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (!isGameWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
            return;
        }
        if (event.getFoodLevel() > sharedFood) {
            event.setCancelled(false);
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent event) {
        if (isGenerating) return;

        Location from = event.getFrom();
        World fromWorld = from.getWorld();
        if (fromWorld == null) return;

        immunityMap.merge(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 5000L, Math::max);
        event.setCreationRadius(128);

        if (fromWorld.getName().equals(currentWorldName) && event.getCause() == TeleportCause.NETHER_PORTAL) {
            World nether = Bukkit.getWorld(currentWorldName + "_nether");
            if (nether != null) {
                Location to = from.clone();
                to.setWorld(nether);
                to.setX(to.getX() / 8.0);
                to.setZ(to.getZ() / 8.0);
                event.setTo(to);
                event.setCanCreatePortal(true);
            }
        } else if (fromWorld.getName().equals(currentWorldName + "_nether") && event.getCause() == TeleportCause.NETHER_PORTAL) {
            World overworld = Bukkit.getWorld(currentWorldName);
            if (overworld != null) {
                Location to = from.clone();
                to.setWorld(overworld);
                to.setX(to.getX() * 8.0);
                to.setZ(to.getZ() * 8.0);
                event.setTo(to);
                event.setCanCreatePortal(true);
            }
        } else if (event.getCause() == TeleportCause.END_PORTAL) {
            if (fromWorld.getName().equals(currentWorldName)) {
                World end = Bukkit.getWorld(currentWorldName + "_the_end");
                if (end != null) {
                    Location to = new Location(end, 100.0, 50.0, 0.0);
                    event.setTo(to);

                    for (int x = 99; x <= 101; x++) {
                        for (int z = -1; z <= 1; z++) {
                            end.getBlockAt(x, 49, z).setType(Material.OBSIDIAN);
                            for (int y = 50; y <= 53; y++) {
                                end.getBlockAt(x, y, z).setType(Material.AIR);
                            }
                        }
                    }
                }
            } else if (fromWorld.getName().equals(currentWorldName + "_the_end")) {
                World overworld = Bukkit.getWorld(currentWorldName);
                if (overworld != null) {
                    event.setTo(overworld.getSpawnLocation());
                }
            }
        }
    }
}
