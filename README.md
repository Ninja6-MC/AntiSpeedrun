# AntiSpeedrun

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/icon-transparent-dark.svg">
    <img src="docs/assets/icon-transparent-light.svg" width="128" height="128" alt="AntiSpeedrun Icon">
  </picture>
</p>

<p align="center">
  <b>Anti-speedrun dimension and item progression gates for PaperMC &amp; Folia, with anti-cheese and multi-dragon boss scaling planned.</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3" /></a>
</p>

Part of the [Ninja6-MC](https://github.com/Ninja6-MC) plugin suite.

---

## Status

Under active development; there is no public release yet. Only the modules listed
under "Implemented" below do anything on a running server. Everything under "Planned"
has configuration keys that are parsed and validated but no runtime behavior behind
them, so switching those keys on has no effect yet. The one shared key is
`anti-cheese.enabled`, which is live because the Eye of Ender block reads it.

---

## What it does

AntiSpeedrun paces early-game survival progression on multiplayer Minecraft servers:
it gates dimensions and gear behind milestones, points players at their next step, and
is intended to grow boss-combat scaling and further anti-cheese protection.

### Implemented

- **Dimension progression gates** -- Nether and End access is refused until the
  configured playtime, account age and advancement requirements are met. Covers
  portals, teleports across dimensions, vehicles and stasis. See `dimension-gates`
  in `config.yml`.
- **Item progression gates** -- tier-gated items cannot be picked up, equipped,
  fired from dispensers onto a player, taken out of containers and bundles, or taken
  off armor stands and out of item frames, or handed to or taken back from an Allay,
  until the player has earned the tier (`item-progression`). Bottling Dragon's Breath
  requires End access. Dropped items can always be recovered by the player who
  dropped them.
- **Mending trade gate** -- optional, off by default
  (`villager-progression.gate-mending-trade`).
- **Early Eye of Ender block** -- an Eye of Ender throw is refused until the player
  has earned `minecraft:nether/find_fortress`
  (`anti-cheese.block-early-eye-throwing`).
- **`/progress` card** -- an interactive card of milestones and the next step, with a
  glyph-safe plain-ASCII mode for Bedrock clients (`progress-card.simple-card`).
- **Idle reminders** -- a standing-still reminder of the next goal, shown as action
  bar, title or chat (`idle-reminder`).
- **Journey Guide Book** -- a written book generated from the live configuration,
  available through `/journeybook` and delivered on first join
  (`journey-book.give-on-first-join`).
- **Administration** -- config reload, configuration profiles, durable dimension
  unlocks, temporary bypass grants and per-player inspection.
- **Toggles** -- dimension gates (per dimension), item progression, idle reminders,
  the Mending trade gate (`villager-progression.gate-mending-trade`, off by default)
  and the early Eye of Ender block (`anti-cheese.enabled` and
  `anti-cheese.block-early-eye-throwing`) can be switched in `config.yml`. The
  `/progress` card, the Journey Guide Book and the administrative commands have no
  on/off switch.

### Planned

These are tracked in issues and are not implemented. Their keys in `config.yml` are
read and validated at load but nothing acts on them, with the exception of
`anti-cheese.enabled` noted above.

- **Multi-dragon boss scaling** (`boss-scaling`) -- tracked in
  [#46](https://github.com/Ninja6-MC/AntiSpeedrun/issues/46). Only the reinforcement
  window is live: the extra dragons a party earns on its first End entry
  (`enabled`, `battle-prep-seconds`, `multi-dragon.enabled`, `multiplier`,
  `rounding-mode`, `max-dragons`), with the original dragon held until the extra ones are dead so
  the fight ends in one victory.
- **Remaining anti-cheese rules** (`anti-cheese`) -- Bed and Respawn Anchor damage
  against bosses (`block-bed-anchor-boss-damage`), the single-hit boss damage cap
  (`max-single-hit-boss-damage`), exit portal crystal placement
  (`block-exit-portal-crystal-place`) and the Outer End boundary
  (`block-gateway-pre-dragon`, `outer-end-radius`, `outer-end-poll-seconds`); tracked
  in [#47](https://github.com/Ninja6-MC/AntiSpeedrun/issues/47). Only `enabled`,
  `block-early-eye-throwing` and `early-eye-rejection-message` are live.
- **Armor trim and smithing template gating** (`trim-progression`) -- tracked in
  [#45](https://github.com/Ninja6-MC/AntiSpeedrun/issues/45).

---

## Requirements

- Paper or Folia, Minecraft 1.21.4 through 26.2. The plugin compiles against Paper API
  1.21.4, so earlier 1.21 releases are not supported even though `api-version: 1.21` lets
  them load it. Every pull request boots the built jar on Paper and Folia 1.21.4, 1.21.11
  and 26.2; a weekly run boots it on the newest Paper and Folia release as well, so a
  version above 26.2 may work but is not yet claimed.
- Java 21 to build. The server needs whatever Java its Minecraft version requires: 21 for
  1.21.x, 25 for 26.1 and newer.
- Floodgate is optional. When present on the server it lets `/progress` detect
  Bedrock players and use the glyph-safe card.

Build with `./gradlew build`. The plugin jar is produced by the build; no released
artifact is published yet.

---

## Commands & Permissions

`/asr` is an alias of `/antispeedrun`.

| Command | Permission | Default | Description |
| :--- | :--- | :--- | :--- |
| `/progress` (or `/asr progress`) | `antispeedrun.progress` | everyone | Shows your milestones and next step. |
| `/journeybook` (alias `/rulesbook`, or `/asr book`) | `antispeedrun.book` | everyone | Gives you the Journey Guide Book. |
| `/asr reload` | `antispeedrun.admin.reload` | op | Reloads `config.yml`, all or nothing. |
| `/asr profile apply <CASUAL\|SMP_STANDARD\|HARDCORE>` | `antispeedrun.admin.profile` | op | Backs up the configuration and applies a preset. |
| `/asr unlock <nether\|end> [lock]` | `antispeedrun.admin.unlock` | op | Unlocks (or re-locks) a dimension for everyone, persistently. |
| `/asr bypass <player> [duration\|off]` | `antispeedrun.admin.bypass` | op | Grants or revokes a temporary bypass for a player. |
| `/asr inspect <player>` | `antispeedrun.admin.inspect` | op | Reports a player's progression state. |

`antispeedrun.admin` grants all the administrative nodes above. It does not include
`antispeedrun.bypass`, which exempts the holder from gates and is never granted by
default; its children `antispeedrun.bypass.gates`, `.items` and `.anticheese` exempt
from one category each.

---

## Documentation

- [Administration guide](docs/administration.md) -- installation, first start, commands
  and permissions, profiles, dimension unlock, temporary bypass, persisted state,
  backup boundaries and troubleshooting.
- [Configuration reference](docs/configuration.md) -- every `config.yml` section, its
  defaults, validation and reload behavior, the three profiles, and which modules are
  still planned.

---

## License

[GNU General Public License v3.0](LICENSE).

---

<p align="center">
  <a href="https://github.com/Ninja6-MC"><img src="assets/ninja6-primary-256.png" width="48" height="48" alt=""></a>
</p>

<p align="center">
  <sub>A <a href="https://github.com/Ninja6-MC">Ninja6</a> project.</sub>
</p>
