package com.sharedlife.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DamageManager implements Listener {

    private final SharedLifePlugin plugin;

    // Periodic hazard tracking: Cause -> Last applied timestamp
    private final Map<DamageCause, Long> lastHazardTime = new ConcurrentHashMap<>();

    // Multi-player simultaneous damage anti-amplification window
    private long lastDamageWindowStart = 0;
    private double highestDamageInWindow = 0.0;

    public DamageManager(SharedLifePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (plugin.isResetting()) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        if (victim.getGameMode() == GameMode.SPECTATOR || victim.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        DamageCause cause = event.getCause();
        double rawFinalDamage = event.getFinalDamage();

        // Prevent zero or negative damage events
        if (rawFinalDamage <= 0.0) {
            return;
        }

        Entity damagerEntity = (event instanceof EntityDamageByEntityEvent edbe) ? edbe.getDamager() : null;

        // 1. Friendly Fire Check
        if (damagerEntity != null) {
            Player attacker = getPlayerAttacker(damagerEntity);
            if (attacker != null) {
                // If friendly fire is false, cancel it; if true (default), allow damage through
                if (!plugin.getConfig().getBoolean("damage.friendly-fire", true)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // Cancel vanilla event so health is managed atomically through our shared system
        event.setCancelled(true);

        long now = System.currentTimeMillis();
        boolean isHazard = isHazardCause(cause);
        int defaultInvuln = plugin.getConfig().getInt("damage.team-invulnerability-ticks", 10);
        int invulnTicks = isHazard ? (isFastHazard(cause) ? 8 : 10) : defaultInvuln;

        double damageToApply = rawFinalDamage;

        if (isHazard) {
            // 2. Periodic Hazard Protection (Lava, Fire, Drowning, Poison, etc.)
            long cooldownMs = getHazardCooldownMs(cause);
            Long lastTime = lastHazardTime.get(cause);

            if (lastTime != null && (now - lastTime) < cooldownMs) {
                // Throttled! Set brief noDamageTicks so Minecraft doesn't spam every tick
                victim.setNoDamageTicks(invulnTicks);

                if (plugin.getConfig().getBoolean("logging.log-throttled-damage", false)) {
                    plugin.getLogger().info(String.format(Locale.US,
                            "[HAZARD-THROTTLE] %s took %s damage, throttled to maintain single-player rate (Team HP: %.2f).",
                            victim.getName(), cause.name(), plugin.getSharedHealth()));
                }
                return;
            }

            // Hazard tick accepted: update cooldown timestamp
            lastHazardTime.put(cause, now);
            double tunedDamage = getHazardTunedDamage(cause);
            damageToApply = Math.min(rawFinalDamage, tunedDamage);
        } else {
            // 3. Multi-Player Simultaneous Damage Anti-Amplification (Explosions, Sweeping Attacks, Mob Swarms)
            long aoeWindowMs = plugin.getConfig().getLong("damage.aoe-anti-amplify-ms", 300L);
            if (now - lastDamageWindowStart < aoeWindowMs && cause != DamageCause.VOID && cause != DamageCause.KILL) {
                if (rawFinalDamage <= highestDamageInWindow) {
                    // Absorbed by simultaneous team hit (e.g. 2 players in the same explosion)
                    victim.setNoDamageTicks(invulnTicks);
                    victim.playHurtAnimation(victim.getLocation().getYaw());
                    applyKnockback(victim, damagerEntity, cause);
                    return;
                } else {
                    // New hit is larger than previous: only apply the difference
                    damageToApply = rawFinalDamage - highestDamageInWindow;
                    highestDamageInWindow = rawFinalDamage;
                }
            } else {
                lastDamageWindowStart = now;
                highestDamageInWindow = rawFinalDamage;
            }
        }

        // Apply invulnerability frames so Minecraft triggers next damage at natural tick interval
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setNoDamageTicks(invulnTicks);
        }

        // Apply armor durability wear
        damageArmor(victim, rawFinalDamage);

        // Play hurt animation & sound for the victim
        victim.playHurtAnimation(victim.getLocation().getYaw());
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);

        // Apply knockback to the victim
        applyKnockback(victim, damagerEntity, cause);

        // Calculate attacker name if entity-based
        String attackerName = "None";
        if (damagerEntity != null) {
            if (damagerEntity instanceof Player pDamager) {
                attackerName = pDamager.getName();
            } else if (damagerEntity instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
                attackerName = shooter.getName();
            } else {
                attackerName = damagerEntity.getName();
            }
        }

        double oldHp = plugin.getSharedHealth();
        double newHp = Math.max(0.0, oldHp - damageToApply);
        plugin.setSharedHealth(newHp);

        // 1. Console Logging
        if (plugin.getConfig().getBoolean("logging.log-damage", true)) {
            String attackerInfo = !attackerName.equals("None") ? " (Attacker: " + attackerName + ")" : "";
            plugin.getLogger().info(String.format(Locale.US,
                    "[DAMAGE] %s took %.1f damage from %s%s. Team HP: %.1f -> %.1f",
                    victim.getName(), damageToApply, cause.name(), attackerInfo, oldHp, newHp));
        }

        // 2. On-Screen Notification for Every Player
        if (plugin.getConfig().getBoolean("hud.show-damage-on-screen", true)) {
            String screenText;
            if (!attackerName.equals("None")) {
                screenText = String.format(Locale.US, "§c⚠ §e%s §cwas hurt by §6%s §7(-%.1f HP)",
                        victim.getName(), attackerName, damageToApply);
            } else {
                screenText = String.format(Locale.US, "§c⚠ §e%s §ctook §6-%.1f HP §cdamage from §e%s",
                        victim.getName(), damageToApply, formatCause(cause));
            }
            Component comp = Component.text(screenText);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendActionBar(comp);
            }
        }

        // Optional Chat Notification
        if (plugin.getConfig().getBoolean("hud.show-damage-in-chat", false)) {
            String chatText = (!attackerName.equals("None"))
                    ? String.format(Locale.US, "§c[SharedLife] §e%s §cwas hurt by §6%s §7(-%.1f HP)", victim.getName(), attackerName, damageToApply)
                    : String.format(Locale.US, "§c[SharedLife] §e%s §ctook §6-%.1f HP §cdamage from §e%s", victim.getName(), damageToApply, formatCause(cause));
            Bukkit.broadcastMessage(chatText);
        }

        // Physical feedback on teammates
        if (plugin.getConfig().getBoolean("health.physical-feedback", true)) {
            for (Player teammate : Bukkit.getOnlinePlayers()) {
                if (!teammate.equals(victim)) {
                    teammate.playHurtAnimation(0.0f);
                    teammate.playSound(teammate.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.6f, 1.2f);
                }
            }
        }

        // Death Check: Fatal damage wipe
        if (newHp <= 0.0) {
            String deathDesc = formatDeathMessage(victim, cause, attackerName);
            plugin.getLogger().warning(String.format(Locale.US,
                    "[FATAL DAMAGE] %s took %.2f fatal damage from %s (Attacker: %s). Team HP reached 0.0!",
                    victim.getName(), damageToApply, cause.name(), attackerName));
            plugin.getWorldResetManager().triggerWipe(victim, cause, deathDesc);
        } else {
            plugin.syncAllVitals();
        }
    }

    private void applyKnockback(Player victim, Entity damager, DamageCause cause) {
        // Do not apply knockback for damage causes that have no physical impact
        if (cause == DamageCause.STARVATION || cause == DamageCause.DROWNING
                || cause == DamageCause.POISON || cause == DamageCause.WITHER
                || cause == DamageCause.SUFFOCATION || cause == DamageCause.FREEZE
                || cause == DamageCause.VOID || cause == DamageCause.FALL) {
            return;
        }

        double strength = 0.45;
        double dx = 0.0;
        double dz = 0.0;

        if (damager instanceof Player attacker) {
            strength = 0.45;
            if (attacker.isSprinting()) {
                strength += 0.45; // Sprint attack knockback boost
            }
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (weapon != null) {
                int kb = weapon.getEnchantmentLevel(Enchantment.KNOCKBACK);
                strength += kb * 0.4;
            }
            dx = attacker.getLocation().getX() - victim.getLocation().getX();
            dz = attacker.getLocation().getZ() - victim.getLocation().getZ();
        } else if (damager instanceof Projectile proj) {
            strength = 0.4;
            // Arrow direction
            dx = -proj.getVelocity().getX();
            dz = -proj.getVelocity().getZ();
        } else if (damager != null) {
            // General mob attack (Zombie, Skeleton, Spider, etc.) or Creeper/TNT
            dx = damager.getLocation().getX() - victim.getLocation().getX();
            dz = damager.getLocation().getZ() - victim.getLocation().getZ();
            if (cause == DamageCause.BLOCK_EXPLOSION || cause == DamageCause.ENTITY_EXPLOSION) {
                strength = 0.75;
            } else {
                strength = 0.45;
            }
        } else if (cause == DamageCause.BLOCK_EXPLOSION) {
            strength = 0.75;
            double rad = Math.toRadians(victim.getLocation().getYaw());
            dx = Math.sin(rad);
            dz = -Math.cos(rad);
        } else if (cause == DamageCause.LAVA || cause == DamageCause.FIRE) {
            // Subtle twitch when stepping into lava/fire
            strength = 0.15;
            double rad = Math.toRadians(victim.getLocation().getYaw());
            dx = Math.sin(rad);
            dz = -Math.cos(rad);
        }

        if (Math.abs(dx) < 1e-4 && Math.abs(dz) < 1e-4) {
            double rad = Math.toRadians(victim.getLocation().getYaw());
            dx = Math.sin(rad);
            dz = -Math.cos(rad);
        }

        victim.knockback(strength, dx, dz);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHealthRegain(EntityRegainHealthEvent event) {
        if (plugin.isResetting()) {
            return;
        }

        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        event.setCancelled(true);

        double oldHp = plugin.getSharedHealth();
        double newHp = Math.min(plugin.getMaxHealth(), oldHp + event.getAmount());
        plugin.setSharedHealth(newHp);
        plugin.syncAllVitals();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (plugin.isResetting()) {
            return;
        }

        event.setCancelled(true);
        Player victim = event.getPlayer();
        String vanillaMsg = event.getDeathMessage();
        if (vanillaMsg == null || vanillaMsg.isBlank()) {
            vanillaMsg = victim.getName() + " died from fatal damage";
        }

        plugin.getLogger().warning("[FATAL DEATH EVENT] " + vanillaMsg);
        plugin.getWorldResetManager().triggerWipe(victim, DamageCause.VOID, vanillaMsg);
    }

    private Player getPlayerAttacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private boolean isHazardCause(DamageCause cause) {
        return switch (cause) {
            case LAVA, FIRE, FIRE_TICK, DROWNING, POISON, WITHER, HOT_FLOOR, FREEZE, STARVATION, SUFFOCATION, CONTACT -> true;
            default -> false;
        };
    }

    private boolean isFastHazard(DamageCause cause) {
        return switch (cause) {
            case LAVA, CONTACT -> true;
            default -> false;
        };
    }

    private long getHazardCooldownMs(DamageCause cause) {
        return switch (cause) {
            case LAVA -> plugin.getConfig().getLong("damage.lava.cooldown-ms", 450L);
            case FIRE, FIRE_TICK -> plugin.getConfig().getLong("damage.fire.cooldown-ms", 900L);
            case DROWNING -> plugin.getConfig().getLong("damage.drowning.cooldown-ms", 900L);
            case HOT_FLOOR -> plugin.getConfig().getLong("damage.hot-floor.cooldown-ms", 900L);
            case POISON -> plugin.getConfig().getLong("damage.poison.cooldown-ms", 1100L);
            case WITHER -> plugin.getConfig().getLong("damage.wither.cooldown-ms", 900L);
            case FREEZE -> plugin.getConfig().getLong("damage.freeze.cooldown-ms", 900L);
            case STARVATION -> plugin.getConfig().getLong("damage.starvation.cooldown-ms", 1800L);
            case SUFFOCATION -> plugin.getConfig().getLong("damage.suffocation.cooldown-ms", 900L);
            case CONTACT -> plugin.getConfig().getLong("damage.contact.cooldown-ms", 450L);
            default -> 500L;
        };
    }

    private double getHazardTunedDamage(DamageCause cause) {
        return switch (cause) {
            case LAVA -> plugin.getConfig().getDouble("damage.lava.damage-amount", 3.0);
            case FIRE, FIRE_TICK -> plugin.getConfig().getDouble("damage.fire.damage-amount", 1.0);
            case DROWNING -> plugin.getConfig().getDouble("damage.drowning.damage-amount", 2.0);
            case HOT_FLOOR -> plugin.getConfig().getDouble("damage.hot-floor.damage-amount", 1.0);
            case POISON -> plugin.getConfig().getDouble("damage.poison.damage-amount", 1.0);
            case WITHER -> plugin.getConfig().getDouble("damage.wither.damage-amount", 1.0);
            case FREEZE -> plugin.getConfig().getDouble("damage.freeze.damage-amount", 1.0);
            case STARVATION -> plugin.getConfig().getDouble("damage.starvation.damage-amount", 1.0);
            case SUFFOCATION -> plugin.getConfig().getDouble("damage.suffocation.damage-amount", 1.0);
            case CONTACT -> plugin.getConfig().getDouble("damage.contact.damage-amount", 1.0);
            default -> 1.0;
        };
    }

    private void damageArmor(Player player, double rawDamage) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        int damageToApply = Math.max(1, (int) (rawDamage / 4.0));

        for (ItemStack piece : armor) {
            if (piece != null && piece.getItemMeta() instanceof Damageable damageable) {
                int currentDmg = damageable.getDamage();
                int maxDmg = piece.getType().getMaxDurability();
                if (maxDmg > 0) {
                    if (currentDmg + damageToApply >= maxDmg) {
                        piece.setAmount(0);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    } else {
                        damageable.setDamage(currentDmg + damageToApply);
                        piece.setItemMeta(damageable);
                    }
                }
            }
        }
    }

    public static String formatDeathMessage(Player victim, DamageCause cause, String attacker) {
        String name = victim.getName();
        boolean hasAttacker = attacker != null && !attacker.equalsIgnoreCase("None") && !attacker.isBlank();

        return switch (cause) {
            case LAVA -> name + " tried to swim in lava";
            case FIRE, FIRE_TICK -> hasAttacker ? (name + " was burnt to crisp while fighting " + attacker) : (name + " burned to death");
            case DROWNING -> hasAttacker ? (name + " drowned while escaping " + attacker) : (name + " drowned");
            case FALL -> hasAttacker ? (name + " was doomed to fall by " + attacker) : (name + " fell from a high place");
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> hasAttacker ? (name + " was slain by " + attacker) : (name + " was slain by a monster");
            case PROJECTILE -> hasAttacker ? (name + " was shot by " + attacker) : (name + " was shot");
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> hasAttacker ? (name + " was blown up by " + attacker) : (name + " blew up");
            case POISON -> hasAttacker ? (name + " was poisoned by " + attacker) : (name + " was poisoned to death");
            case WITHER -> hasAttacker ? (name + " withered away fighting " + attacker) : (name + " withered away");
            case STARVATION -> name + " starved to death";
            case SUFFOCATION -> name + " suffocated in a wall";
            case HOT_FLOOR -> name + " discovered the floor was lava";
            case FREEZE -> name + " froze to death";
            case LIGHTNING -> name + " was struck by lightning";
            case FLY_INTO_WALL -> name + " experienced kinetic energy";
            case VOID -> name + " fell out of the world";
            default -> hasAttacker ? (name + " was killed by " + attacker + " (" + cause.name() + ")") : (name + " died from " + cause.name());
        };
    }

    private String formatCause(DamageCause cause) {
        return switch (cause) {
            case LAVA -> "Lava";
            case FIRE, FIRE_TICK -> "Fire";
            case DROWNING -> "Drowning";
            case FALL -> "Fall";
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> "Melee Attack";
            case PROJECTILE -> "Arrow";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "Explosion";
            case POISON -> "Poison";
            case WITHER -> "Wither";
            case STARVATION -> "Hunger";
            case SUFFOCATION -> "Suffocation";
            case HOT_FLOOR -> "Magma";
            case FREEZE -> "Freezing";
            default -> cause.name().toLowerCase();
        };
    }
}
