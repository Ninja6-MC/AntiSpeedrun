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

Under active development. Development builds are published as GitHub pre-releases,
with the plugin jar and its checksum, on the
[Releases page](https://github.com/Ninja6-MC/AntiSpeedrun/releases), which lists the
current build. There is no stable release yet. Only the modules listed under
"Implemented" below do anything on a running server. Everything under "Planned" has
configuration keys that are parsed and validated but no runtime behavior behind them, so
switching those keys on has no effect yet. Every anti-cheese rule beyond the Eye of Ender
block ships off and must be enabled by the server operator.

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
- **Multi-dragon boss scaling** (`boss-scaling`) -- the reinforcement
  window: the extra dragons a party earns on its first End entry and on each
  four-crystal resummon (`enabled`, `scale-resummoned-dragons`, `battle-prep-seconds`,
  `multi-dragon.enabled`, `multiplier`, `rounding-mode`, `max-dragons`), with the original
  dragon held until the extra ones are dead so the fight ends in one victory. The
  `multi-dragon.balanced-xp` option sets each extra dragon's reward to 1,000 XP;
  turning it off retains the earlier 500 XP cap.
- **Anti-cheese rules** (`anti-cheese`) -- `enabled`,
  `block-early-eye-throwing`, `early-eye-rejection-message`, the single-hit cap
  (`cap-single-hit-boss-damage`, off by default, with `max-single-hit-boss-damage`), the
  exit portal centre crystal block (`block-exit-portal-crystal-place`, off by default), the
  bed and Respawn Anchor boss damage block (`block-bed-anchor-boss-damage`, off by default) and the
  Outer End boundary (`block-gateway-pre-dragon`, off by default, `outer-end-radius`,
  `outer-end-poll-seconds`).

### Planned

These are tracked in issues and are not implemented. Their keys in `config.yml` are
read and validated at load but nothing acts on them.

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

Download the plugin jar from the
[Releases page](https://github.com/Ninja6-MC/AntiSpeedrun/releases), or build it with
`./gradlew build`. Releases to date are pre-releases. Upgrading from v0.1.x: see
[docs/administration.md](docs/administration.md#2-installation).

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
