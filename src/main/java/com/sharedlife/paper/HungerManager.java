package com.sharedlife.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

public class HungerManager implements Listener {

    private final SharedLifePlugin plugin;

    public HungerManager(SharedLifePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (plugin.isResetting()) {
            return;
        }

        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        // Cancel vanilla individual exhaustion so we can control shared team exhaustion
        event.setCancelled(true);

        int playerCount = Math.max(1, Bukkit.getOnlinePlayers().size());
        double ratePerExtraPlayer = plugin.getConfig().getDouble("hunger.rate-per-extra-player", 0.15);

        // Normalize across all online players and scale
        double multiplier = (1.0 + (playerCount - 1) * ratePerExtraPlayer) / playerCount;
        double addedExhaustion = event.getExhaustion() * multiplier;

        plugin.addSharedExhaustion(addedExhaustion);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (plugin.isResetting()) {
            return;
        }

        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        event.setCancelled(true);

        int oldLevel = player.getFoodLevel();
        int newLevel = event.getFoodLevel();

        // If food was gained (e.g. eating food item)
        if (newLevel > oldLevel || event.getItem() != null) {
            int gained = Math.max(1, newLevel - oldLevel);
            int currentFood = plugin.getSharedFood();
            int updatedFood = Math.min(20, currentFood + gained);
            plugin.setSharedFood(updatedFood);

            // Add saturation proportionally
            float currentSat = plugin.getSharedSaturation();
            float updatedSat = Math.min((float) updatedFood, currentSat + (gained * 0.6f));
            plugin.setSharedSaturation(updatedSat);

            plugin.syncAllVitals();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (plugin.isResetting()) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null) return;

        // Schedule vitals sync after consumption finishes
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isResetting()) {
                plugin.syncAllVitals();
            }
        });
    }

    public void syncHunger(Player player) {
        player.setFoodLevel(plugin.getSharedFood());
        player.setSaturation(plugin.getSharedSaturation());
        player.setExhaustion(0.0f);
    }
}

