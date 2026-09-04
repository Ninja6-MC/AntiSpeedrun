# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `/antispeedrun` (`/asr`) administration command with tab completion: `reload`, `profile apply
  <CASUAL|SMP_STANDARD|HARDCORE>`, `unlock <nether|end> [lock]`, `bypass <player> [duration|off]`
  and `inspect <player>`. Each subcommand is gated on its own `antispeedrun.admin.*` node, and
  completion offers nothing — including player names — under a subcommand the sender cannot run.
- Durable state, so an unlock or a bypass no longer evaporates at the next restart. Server-wide
  dimension unlocks are written to `state.yml`; per-player bypass grants, which carry an expiry,
  and the journey-book delivered flag live in the player's persistent data container.
- Journey-book delivery is recorded by the plugin rather than inferred from
  `hasPlayedBefore()`, which is false for every player who joined before the plugin was installed
  and would have skipped an established server's entire population. No listener consumes the flag
  yet; the journey-book feature itself is a separate task.
- Configuration profile presets shipped as `profiles/casual.yml`, `profiles/smp_standard.yml` and
  `profiles/hardcore.yml`. `/asr profile apply` copies the previous `config.yml` to
  `backups/config-<timestamp>.yml` before overwriting it.
- Wildcard match modes for `match-patterns` — prefix (`IRON_*`), suffix (`*_IRON_ORE`), contains (`*_DIAMOND_*`) and exact — compiled once per configuration snapshot into an `EnumMap`/`EnumSet` material lookup that allocates nothing per item pickup.
- Most-restrictive-wins precedence when two tiers claim one material, resolved by requirement dominance and then by document order; an incomparable pair fails startup with an error naming the material, both tiers and the `exclude-materials` line that resolves it.
- Folia platform support declaration (`folia-supported: true`), without which Folia refuses to enable the plugin.
- CI guards rejecting `BukkitScheduler` / `BukkitRunnable` usage, which throws on Folia, and verifying the Folia manifest key is present.
- CI `folia-smoke` job booting a real Folia 1.21.4 server against the built jar.
- Full `item-progression` and `trim-progression` configuration trees, previously documented but absent from the shipped config.
- Granular bypass permissions (`antispeedrun.bypass.gates`, `.items`, `.anticheese`) and the `journeybook` command registration.
- Configurable outer-End boundary (`outer-end-radius`, `outer-end-poll-seconds`) and exit portal combat lock with an escape valve (`exit-portal-lock-during-battle`, `exit-portal-lock-release-minutes`).
- Initial project scaffolding and baseline architecture for PaperMC 1.21+ (Java 21).
- Dynamic progression gating engine for Nether and The End portals.
- In-game `/progress` (`/asr progress`) survival milestone tracker card.
- Idle / standing-still progression reminder engine with configurable cooldowns.
- Journey Guide Book system (`/asr book`).
- Multi-dragon boss party scaling with configurable multiplier and rounding modes (`HALF_UP`, `CEIL`, `FLOOR`).
- Anti-cheese protections (blocking Bed/Anchor explosion damage on Ender Dragon and Wither, exit portal crystal placement blocking).
- Standards-compliant CI workflows for DCO, OpenSSF Scorecard, and Standards validation.

### Changed
- Item gating is a pure function of `(player advancements, material)`. The natural-structure provenance system is removed: no `ItemStack` carries a plugin tag, which makes gear laundering structurally impossible. Decision record in `docs/provenance-model.md`.
- `gate-natural-structure-chests`, `gate-player-placed-chests` and `gate-armor-stands` removed — each configured a distinction that no longer exists. `dropper-can-retrieve` and `death-drop-retrieval` collapse into `drop-recall-enabled`.
- Mob item pickup is no longer intercepted; piglin bartering and Allay sorters are unaffected.
- Soft-dependency matrix trimmed to Floodgate, the only optional integration the plugin consumes.

### Fixed
- `DIAMOND` is gated. `DIAMOND_*` has no trailing underscore to match the gem itself, so every tool made from a diamond was gated while the diamond was not.
- `NETHERITE_UPGRADE_SMITHING_TEMPLATE` is excluded from `netherite-tier`. It matches `NETHERITE_*` but belongs to `trim-progression`, and without the exclusion two systems claimed one material.
- `antispeedrun.bypass` is no longer a child of `antispeedrun.admin`. Because `antispeedrun.admin` defaults to `op`, every operator was silently exempt from every dimension gate, item lock, and anti-cheese rule.
- Dimension gate defaults are advancement-driven (`0` hours / `0` days), matching the specification. The shipped config previously required 2h playtime for the Nether and 20h plus a 7-day account age for The End.
- The `end-tier` item gate no longer contradicts the End dimension gate; a player could previously pass the gate and still be unable to pick up an Ender Eye.
- `max-single-hit-boss-damage` lowered from `50.0` to `12.0`. Against the Ender Dragon's 200 HP the previous cap still permitted a four-hit kill.
- The root `/antispeedrun` command no longer requires `antispeedrun.admin`, which had made `/asr progress` and `/asr book` unreachable for regular players despite both permissions defaulting to `true`.
