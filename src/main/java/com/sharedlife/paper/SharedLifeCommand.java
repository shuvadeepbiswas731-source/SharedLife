package com.sharedlife.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SharedLifeCommand implements CommandExecutor, TabCompleter {

    private final SharedLifePlugin plugin;

    public SharedLifeCommand(SharedLifePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§6================= §eSharedLife Status §6=================");
            sender.sendMessage(String.format(Locale.US, "§7Shared HP: §c%.1f §7/ §c%.1f", plugin.getSharedHealth(), plugin.getMaxHealth()));
            sender.sendMessage(String.format(Locale.US, "§7Shared Food: §e%d §7(Saturation: §e%.1f§7)", plugin.getSharedFood(), plugin.getSharedSaturation()));
            sender.sendMessage("§7Active Run World: §a" + (plugin.getActiveWorld() != null ? plugin.getActiveWorld().getName() : "None"));
            sender.sendMessage("§7Online Players: §b" + Bukkit.getOnlinePlayers().size());
            sender.sendMessage("§7Resetting: " + (plugin.isResetting() ? "§cYes" : "§aNo"));
            sender.sendMessage("§6=================================================");
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("reset")) {
            if (!sender.hasPermission("sharedlife.admin")) {
                sender.sendMessage("§cYou do not have permission to execute this command.");
                return true;
            }

            sender.sendMessage("§e[SharedLife] Initiating manual hardcore world reset...");
            Player adminPlayer = (sender instanceof Player p) ? p : null;
            plugin.getWorldResetManager().triggerWipe(adminPlayer, DamageCause.CUSTOM, "Admin manually triggered hardcore reset");
            return true;
        }

        if (sub.equals("sync")) {
            if (!sender.hasPermission("sharedlife.admin")) {
                sender.sendMessage("§cYou do not have permission to execute this command.");
                return true;
            }

            plugin.syncAllVitals();
            sender.sendMessage("§a[SharedLife] All player stats and vitals synchronized.");
            return true;
        }

        if (sub.equals("reload")) {
            if (!sender.hasPermission("sharedlife.admin")) {
                sender.sendMessage("§cYou do not have permission to execute this command.");
                return true;
            }

            plugin.reloadConfig();
            sender.sendMessage("§a[SharedLife] Configuration reloaded successfully.");
            return true;
        }

        sender.sendMessage("§cUnknown subcommand. Usage: /sharedlife [status|reset|sync|reload]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            String query = args[0].toLowerCase();
            for (String sub : List.of("status", "reset", "sync", "reload")) {
                if (sub.startsWith(query)) {
                    list.add(sub);
                }
            }
        }
        return list;
    }
}
