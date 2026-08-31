# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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

### Fixed
- `antispeedrun.bypass` is no longer a child of `antispeedrun.admin`. Because `antispeedrun.admin` defaults to `op`, every operator was silently exempt from every dimension gate, item lock, and anti-cheese rule.
- Dimension gate defaults are advancement-driven (`0` hours / `0` days), matching the specification. The shipped config previously required 2h playtime for the Nether and 20h plus a 7-day account age for The End.
- The `end-tier` item gate no longer contradicts the End dimension gate; a player could previously pass the gate and still be unable to pick up an Ender Eye.
- `max-single-hit-boss-damage` lowered from `50.0` to `12.0`. Against the Ender Dragon's 200 HP the previous cap still permitted a four-hit kill.
- The root `/antispeedrun` command no longer requires `antispeedrun.admin`, which had made `/asr progress` and `/asr book` unreachable for regular players despite both permissions defaulting to `true`.
