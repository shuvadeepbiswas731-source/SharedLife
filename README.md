# 🩸 SharedHealthHunger

**SharedHealthHunger** is an automated, high-performance hardcore multiplayer plugin for modern Minecraft (**Paper / Purpur 1.21+ & 26.3**, Java 21+).

All players on the server share a **single collective life pool, hunger, absorption, and saturation**. If one takes damage, all feel it. If one starves, all starve. When the team dies, the hardcore run ends, and the server automatically constructs an enclosed sky lobby, resets the run, and prepares a fresh hardcore world.

---

## 🌟 Key Features

- **💔 Shared Health Pool**: All players share a collective health pool (default 10 hearts / 20.0 HP). When anyone takes damage, the team's health drops atomically. Teammates receive subtle hit animations and sound feedback.
- **🛡️ Hazard Anti-Multiplication**: Fixes the classic shared-health flaw where multiple players touching lava or fire multiplies the damage and wipes the team in seconds. Environmental hazards (`LAVA`, `FIRE_TICK`, `HOT_FLOOR`, `DROWNING`, `POISON`, `WITHER`, `FREEZING`, `SUFFOCATION`, `STARVATION`) use calibrated cooldowns to prevent damage multiplication across players.
- **💥 AoE & Explosion Anti-Wipeout**: Prevents Creeper explosions, TNT, or Dragon breath from stacking damage per nearby player. Simultaneous blast damage within `300ms` caps to the single highest hit.
- **✨ Full Totem of Undying Support**: Popping a Totem of Undying saves the whole team, restores shared health to 4 HP, adds 8 absorption hearts, and grants **40 seconds of Fire Resistance** to everyone to escape hazards like lava.
- **🍗 Shared Food & Scaled Exhaustion**: Food level, saturation, and natural health regeneration are synchronized. Exhaustion is divided proportionally across online players so teams don't burn through food unnaturally fast.
- **🌍 Fast Automated Hardcore Run Reset**: On death, the plugin creates a sky platform in the lobby world. Players wait with an on-screen boss bar countdown (default 5s, configurable in `data.yml`), and are then teleported directly into the new run world (`hc_run_X`).
- **🛡️ Genuine Spawn Immunity**: Players receive 5 seconds of damage immunity starting the exact moment they land in the new run.
- **📊 Coalesced Damage Alerts**: Continuous damage (e.g. burning or poison) is coalesced into clean summary messages instead of flooding chat every tick.

---

## 📥 Installation

1. Download **`SharedHealthHunger-2.0.0.jar`**.
2. Place the jar into your server's `plugins/` directory.
3. Requires **Paper** or **Purpur** **1.21+ / 26.3** running **Java 21, 25, or 26**.
4. Start your server.

---

## ⚙️ Configuration (`plugins/SharedHealthHunger/data.yml`)

```yaml
health: 20.0
runId: 1
countdown-seconds: 5   # Lobby wait time before starting the next hardcore run
```

---

## 🎮 Commands & Permissions

| Command | Permission | Description |
|---|---|---|
| `/resetrun` | `shared.admin` | Resets the current run and triggers a new world generation |
| `/setrun <id>` | `shared.admin` | Manually sets the next run number |
| `/lobbysound` *(alias: `/mute`)* | Everyone | Toggles the lobby countdown ticking note block sound for yourself |

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
