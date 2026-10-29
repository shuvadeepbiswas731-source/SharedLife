package com.sharedlife.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PingCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cUsage: /ping <player>");
                return true;
            }
            int ping = player.getPing();
            String pingColor = HudManager.getPingColor(ping);
            sender.sendMessage("§6[SharedLife] §7Your current ping: " + pingColor + ping + "ms");
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§cPlayer '" + targetName + "' was not found or is offline.");
            return true;
        }

        int ping = target.getPing();
        String pingColor = HudManager.getPingColor(ping);
        sender.sendMessage("§6[SharedLife] §7" + target.getName() + "'s ping: " + pingColor + ping + "ms");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String query = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(query))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}

