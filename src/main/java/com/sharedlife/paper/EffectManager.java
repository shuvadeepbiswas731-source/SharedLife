package com.sharedlife.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class EffectManager implements Listener {

    private final SharedLifePlugin plugin;
    private final Set<UUID> syncingPlayers = Collections.synchronizedSet(new HashSet<>());

    public EffectManager(SharedLifePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!plugin.getConfig().getBoolean("effects.share-potions", true)) {
            return;
        }

        if (plugin.isResetting()) {
            return;
        }

        if (!(event.getEntity() instanceof Player sourcePlayer)) {
            return;
        }

        if (syncingPlayers.contains(sourcePlayer.getUniqueId())) {
            return;
        }

        EntityPotionEffectEvent.Action action = event.getAction();
        PotionEffect newEffect = event.getNewEffect();
        PotionEffect oldEffect = event.getOldEffect();

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(sourcePlayer)) {
                continue;
            }

            syncingPlayers.add(target.getUniqueId());
            try {
                if (action == EntityPotionEffectEvent.Action.ADDED || action == EntityPotionEffectEvent.Action.CHANGED) {
                    if (newEffect != null) {
                        target.addPotionEffect(new PotionEffect(
                                newEffect.getType(),
                                newEffect.getDuration(),
                                newEffect.getAmplifier(),
                                newEffect.isAmbient(),
                                newEffect.hasParticles(),
                                newEffect.hasIcon()
                        ));
                    }
                } else if (action == EntityPotionEffectEvent.Action.REMOVED) {
                    if (oldEffect != null) {
                        target.removePotionEffect(oldEffect.getType());
                    }
                } else if (action == EntityPotionEffectEvent.Action.CLEARED) {
                    target.clearActivePotionEffects();
                }
            } finally {
                syncingPlayers.remove(target.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("effects.share-potions", true)) {
            return;
        }

        Player joined = event.getPlayer();
        // Give the joined player the active effects from any existing team member
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(joined)) {
                syncingPlayers.add(joined.getUniqueId());
                try {
                    for (PotionEffect effect : online.getActivePotionEffects()) {
                        joined.addPotionEffect(effect);
                    }
                } finally {
                    syncingPlayers.remove(joined.getUniqueId());
                }
                break;
            }
        }
    }

    public void clearAllEffects() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            syncingPlayers.add(p.getUniqueId());
            try {
                p.clearActivePotionEffects();
            } finally {
                syncingPlayers.remove(p.getUniqueId());
            }
        }
    }
}

