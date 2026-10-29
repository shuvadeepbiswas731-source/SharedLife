# 🩸 SharedLife

**SharedLife** is a feature-rich, high-performance hardcore multiplayer plugin for **Paper / Purpur 1.21+**.

All players on the server share a **single collective life, hunger pool, and status effects**. If one takes damage, all feel it. If one starves, all starve. If the team dies, the hardcore run ends, and the server automatically constructs a sky lobby and generates a brand-new hardcore world.

---

## 🌟 Key Features

- **💔 Shared Health Pool**: All players share a single pool of health (default 10 hearts / 20.0 HP). When anyone takes damage, the team's health drops atomically. Teammates receive hurt animations and sound feedback.
- **🛡️ Hazard Anti-Multiplication**: Fixes the classic shared-health flaw where lava or fire instantly kills everyone in 0.3 seconds. Periodic hazards (lava, fire, drowning, poison, wither, freezing, starvation, suffocation) use calibrated cooldowns to prevent damage multiplication across multiple players.
- **💥 AoE Simultaneous Hit Capping**: Prevents creeper explosions or AoE attacks from multiplying per nearby player. Multiple hits within `300ms` only apply the highest single hit.
- **👊 Combat Knockback & Friendly Fire**: Full physics-based knockback calculation supporting sprinting, melee weapons, knockback enchantments, projectiles, and explosions. Friendly fire is enabled so teammates can fight or interact naturally.
- **🍗 Player-Count Scaled Hunger**: Hunger exhaustion is scaled proportionally to online player count (`(1 + (N - 1) * 0.15) / N`). A group of 5 players running around won't burn through food 5x faster than a solo player.
- **🧪 Synchronized Potion Effects**: Any potion or status effect (buff or debuff) gained or lost by one player is seamlessly mirrored to all online players with infinite recursion guards.
- **🌍 Automatic Hardcore World Reset**: On death, the plugin creates an enclosed sky platform in a lobby world at $Y=240$. Players wait safely with countdown subtitles while Paper generates a fresh world seed in the background, then spawns everyone together.
- **📢 Clear In-Game & Console Alerts**: In-game actionbar alerts notify everyone who took damage and the cause (`⚠ Player took -4.0 HP from Fall`), accompanied by detailed server console logging.
- **📶 Built-in Ping Utility**: `/ping [player]` command and live player latency (`[45ms]`) in the Tab player list.

---

## 📥 Installation

1. Download **`SharedLife-1.1.0.jar`**.
2. Place the jar into your server's `plugins/` directory.
3. Requires **Paper** or **Purpur** **1.21+** running **Java 21** or newer.
4. Restart your server.
5. Edit `plugins/SharedLife/config.yml` to customize settings, then run `/sharedlife reload`.

---

## ⚙️ Configuration (`config.yml`)

```yaml
health:
  max-health: 20.0             # 20.0 = 10 hearts
  physical-feedback: true      # Plays hurt sound & red flash for teammates

damage:
  friendly-fire: true          # Allow teammates to hit each other
  team-invulnerability-ticks: 10 # Post-hit invulnerability (0.5s matching vanilla)
  aoe-anti-amplify-ms: 300     # Window to cap simultaneous explosion/mob damage

  # Periodic Hazard Cooldowns (prevents exponential damage scaling)
  lava:
    damage-amount: 3.0
    cooldown-ms: 450
  fire:
    damage-amount: 1.0
    cooldown-ms: 900
  drowning:
    damage-amount: 2.0
    cooldown-ms: 900

hunger:
  rate-per-extra-player: 0.15  # Scaled exhaustion per additional player

effects:
  share-potions: true          # Replicate potion buffs and debuffs

hud:
  show-top-bar: false          # Top BossBar display (disabled by default)
  show-ping-tab: true          # Ping displayed in Tab list
  show-damage-on-screen: true  # Who took damage actionbar notification
  show-damage-in-chat: false   # Chat broadcast of damage

world-reset:
  lobby-world: "sharedlife_lobby"
  platform-y: 240
  platform-radius: 4
  platform-wait-seconds: 5

logging:
  log-damage: true             # Log damage to server console
```

---

## 💻 Commands & Permissions

| Command | Permission | Description |
|---|---|---|
| `/ping [player]` | *(Everyone)* | Check your ping or another player's latency |
| `/sharedlife status` | `sharedlife.admin` | View current shared health, food, and active run world |
| `/sharedlife reset` | `sharedlife.admin` | Manually trigger a hardcore reset and create a new world |
| `/sharedlife sync` | `sharedlife.admin` | Force-synchronize all online players' vitals |
| `/sharedlife reload` | `sharedlife.admin` | Reload `config.yml` configuration |

---

## 🔨 Building from Source

Ensure you have **JDK 21+** and **Maven** installed:

```bash
git clone https://github.com/shuvadeepbiswas731-source/SharedLife.git
cd SharedLife
mvn clean package
```

The compiled jar will be located in the `target/` directory.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
