# AntiSpeedrun

<p align="center">
  <b>Unified anti-speedrun, dimension progression gates, anti-cheese, and multi-dragon boss combat scaling for PaperMC &amp; Folia.</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3" /></a>
</p>

Part of the [Ninja6-MC](https://github.com/Ninja6-MC) plugin suite.

---

## Status

🚧 **Under Development** — initial repository scaffolding.

---

## What it does

AntiSpeedrun provides comprehensive, modular survival progression pacing and boss combat scaling for modern multiplayer Minecraft servers. It prevents early-game speedrunning, guides players through survival milestones, stops combat exploit cheese, and dynamically scales end-game dragon battles for multiplayer parties.

### Core Features

- **Dynamic Dimension Progression Gates** — Configurable playtime, account age, and advancement prerequisites for Nether and The End portals.
- **Progress Tracking & "Next Step" Hints** — In-game `/progress` card and standing-still idle reminders guiding players to their next survival milestone.
- **The Journey Guide Book** — In-game written guide book (`/asr book` and first-join delivery) detailing survival progression rules and stages.
- **Dynamic Multi-Dragon Boss Scaling** — Scalable dragon combat per party size using a configurable multiplier and customizable rounding modes (`HALF_UP`, `CEIL`, `FLOOR`).
- **Anti-Cheese & Anti-Exploit** — Disables Bed & Respawn Anchor explosion damage on bosses (Ender Dragon and Wither), blocks early Eye of Ender throws, and blocks exit portal crystal placement.
- **100% Configurable & Optional** — Every module carries individual toggle switches in `config.yml`.
- **Folia & Paper Native** — Fully asynchronous and region-safe execution using Adventure MiniMessage.

---

## Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/progress` (or `/asr progress`) | `antispeedrun.progress` | Displays current survival progression milestones and next step. |
| `/asr book` | `antispeedrun.book` | Delivers the in-game Journey Guide Book. |
| `/asr reload` | `antispeedrun.admin.reload` | Reloads plugin configuration. |
| `/asr unlock <nether\|end>` | `antispeedrun.admin.unlock` | Manually unlocks dimension access for community events. |

---

## License

[GNU General Public License v3.0](LICENSE).
