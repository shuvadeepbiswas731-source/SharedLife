package com.sharedlife.paper;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HudManager {

    private final SharedLifePlugin plugin;
    private final Map<UUID, BossBar> playerBars = new ConcurrentHashMap<>();

    public HudManager(SharedLifePlugin plugin) {
        this.plugin = plugin;
    }

    public void onPlayerJoin(Player player) {
        if (!plugin.getConfig().getBoolean("hud.show-top-bar", false)) {
            return;
        }
        BossBar bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
        bar.addPlayer(player);
        bar.setVisible(true);
        playerBars.put(player.getUniqueId(), bar);
        updatePlayerBar(player, bar);
    }

    public void onPlayerQuit(Player player) {
        BossBar bar = playerBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    public void update() {
        boolean showBar = plugin.getConfig().getBoolean("hud.show-top-bar", false);
        boolean showTabPing = plugin.getConfig().getBoolean("hud.show-ping-tab", true);

        if (!showBar) {
            if (!playerBars.isEmpty()) {
                cleanUp();
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (showBar) {
                BossBar bar = playerBars.computeIfAbsent(player.getUniqueId(), id -> {
                    BossBar newBar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
                    newBar.addPlayer(player);
                    newBar.setVisible(true);
                    return newBar;
                });
                updatePlayerBar(player, bar);
            }

            if (showTabPing) {
                int ping = player.getPing();
                String pingColor = getPingColor(ping);
                player.setPlayerListName(player.getName() + " §7[" + pingColor + ping + "ms§7]");
            }
        }
    }

    private void updatePlayerBar(Player player, BossBar bar) {
        double currentHp = plugin.getSharedHealth();
        double maxHp = plugin.getMaxHealth();
        int currentFood = plugin.getSharedFood();
        int ping = player.getPing();
        String pingColor = getPingColor(ping);

        double progress = Math.max(0.0, Math.min(1.0, currentHp / maxHp));
        bar.setProgress(progress);

        if (progress > 0.5) {
            bar.setColor(BarColor.GREEN);
        } else if (progress > 0.25) {
            bar.setColor(BarColor.YELLOW);
        } else {
            bar.setColor(BarColor.RED);
        }

        String title = String.format(Locale.US,
                "§c❤ §fHP: §c%.1f§7/§c%.1f §8| §6🍗 §fFood: §e%d §8| §b📶 §fPing: %s%dms",
                currentHp, maxHp, currentFood, pingColor, ping);
        bar.setTitle(title);
    }

    public static String getPingColor(int ping) {
        if (ping < 60) {
            return "§a";
        } else if (ping < 140) {
            return "§e";
        } else {
            return "§c";
        }
    }

    public void cleanUp() {
        for (BossBar bar : playerBars.values()) {
            bar.removeAll();
            bar.setVisible(false);
        }
        playerBars.clear();
    }
}
