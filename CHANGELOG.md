# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `/progress`, and `/asr progress` which delegates to it, both gated on `antispeedrun.progress`
  (default `true`). It draws the player their own progression card: every dimension gate in
  configured order with a completed, in-progress or locked mark, what each one is still waiting on,
  and a highlighted `NEXT STEP` line. That next step is the same string the idle reminder puts in
  `{NEXT_STEP}` — one implementation, so the two cannot drift apart and tell a player different
  things. The new `progress-card.simple-card` setting chooses the shape: `AUTO` (the default) draws
  a plain-ASCII card for a Bedrock client and the full one for a Java client, and `ALWAYS` and
  `NEVER` force one or the other. Bedrock's font carries none of the marks the full card uses, so
  without this a Geyser player saw a column of replacement boxes; the plain card is asserted
  character by character rather than described as "renders cleanly". `AUTO` recognises a Bedrock
  client only on a server actually running Floodgate, which stays a `softdepend` — nothing here
  compiles against it. Milestone display names and advancement keys read from `config.yml` reach
  the player as text, never as markup.
- Idle reminders, implementing the `idle-reminder` section, which until now was parsed and read by
  nothing. A player who stands still for `stand-still-seconds` is shown their next progression goal
  on the action bar, as a title, or in chat, at most once per `cooldown-minutes`. Standing still is
  detected by a poll on the player's own region scheduler, one per player and only while the feature
  is enabled — there is no `PlayerMoveEvent` handler anywhere in the plugin and no global tick loop,
  and a build that introduces either fails the test suite. An action bar reminder is re-sent while it
  is up so that `display-duration-seconds` is honoured past the client's own three-second fade. A
  player who has cleared every gate is told nothing.
- Item tier gates are now enforced. The compiled `item-progression.gated-items` table was
  previously built on every reload and read by nothing; three channels now consult it. A player who
  has not met a tier's requirements cannot pick a gated item up off the ground, take one out of a
  container by an ordinary, shift, hotbar-swap, off-hand-swap, double-click-gather or drop click,
  or buy one from a villager or wandering trader. The gate is waived by
  `antispeedrun.bypass.items`, by an unexpired `/asr bypass` grant, or by setting
  `item-progression.enabled: false`. Putting gated items *into* a container is never blocked,
  including onto a slot that already holds a matching stack, and neither is moving them around
  inside your own inventory — dragging included, since a drag only moves items out of the cursor
  and the clicks that would load the cursor from a container are refused. Views that hand a
  player's own item straight back — the crafting grid, a crafting table, and the anvil, smithing
  table, grindstone, enchanting table, cartography table, loom and stonecutter — are untouched;
  furnaces, brewing stands, Crafters and storage blocks are containers and are gated.
  Bundle contents are **not** yet gated: an item pulled out of a bundle is not checked, which is
  tracked separately and is why `gate-nested-bundles` still does nothing.
- `villager-progression.gate-mending-trade` is now enforced. With it switched on, a player who has
  not earned `villager-progression.required-advancement` (Zombie Doctor by default) cannot select a
  villager or wandering trader offer whose result carries Mending, and cannot take that result out
  of the merchant window if an offer was already selected when they filled the ingredient slots.
  The gate is keyed on the *enchantment*, so a Mending book is told apart from any other enchanted
  book — something the material-keyed item tier table cannot do — and it reads both an item's
  ordinary and its stored enchantments, so an already-enchanted tool offered by a datapack or a
  plugin merchant is covered as well as a librarian's book. It is off by default, costs nothing on
  the trade path while it is off, and is waived by `antispeedrun.bypass.items` or an unexpired
  `/asr bypass` grant. A result that is both above its item tier and enchanted with Mending is
  refused once, not twice.
- Drop recall, implementing `item-progression.drop-recall-enabled`, which until now was parsed and
  read by nothing. A player may always re-collect an item entity they dropped or died with,
  whatever its tier, so that gear held by administrative grant, gear predating installation, and
  gear held under a since-revoked bypass is gated rather than confiscated. The stamp is written to
  the item *entity*, never to an `ItemStack`, so it cannot be transferred, stockpiled or merged
  into a stack — see `docs/provenance-model.md` §4.
- Dimension gates are now enforced. A player who has not met the configured
  `dimension-gates.<dimension>.require-*` requirements cannot reach the Nether or the End on foot,
  as the passenger of a boat, minecart or camel, or by a cross-dimensional Ender pearl teleport.
  Intra-dimensional teleports — `/spawn`, random-teleport plugins, chorus fruit — are untouched, as
  are teleports another plugin or an operator asked for. The gate is waived by
  `antispeedrun.bypass.gates`, by an unexpired `/asr bypass` grant, or by an `/asr unlock` override.
- Selective ejection at a portal: an unqualified rider is dismounted and set down on solid ground
  two blocks behind the vehicle, while qualified riders in the same boat carry on. The vehicle is
  only stopped when nobody aboard qualifies, so no empty vehicle is sent through.
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
- A `config.yml` that names an advancement key the server cannot resolve — or a
  `require-advancements` list written with entries that all name nothing — now stops the plugin at
  startup instead of starting it on the shipped defaults. Those defaults declare no gated item
  tiers, so the old fallback turned item gating off server-wide over a single typo while every gate
  still reported itself armed. A file that cannot be parsed at all is unchanged: it still falls back
  to the defaults and the server still starts, because a file that says nothing describes no gating
  to enforce. So does a bad key inside a gate that is switched off — `enabled: false`,
  `item-progression.enabled: false`, or `gate-mending-trade: false` — which is a warning, because a
  gate that is off admits everyone and says so, and a stale key in a section an operator has already
  turned off must not stop a server that booted yesterday. A file that parses but whose
  `gated-items` cannot be turned into a gate table at all now stops the plugin too, for the same
  reason a tier collision does. `/asr reload` is unchanged in every case — the configuration already
  running stays live and the plugin is never disabled — and a reload that would not survive a
  restart now says so in the log rather than waiting for the restart to say it.
- Two remaining ways to switch a gate on and gate on nothing now take that same startup arm rather than passing in silence. An item tier's `require-advancements` written as a plain value instead of a list used to fall back to the built-in default — which for a tier is no requirement at all — on a warning, so the tier shipped armed and open over a YAML shape mistake that an upper-case letter in the same key was already refused for. And `gate-mending-trade: true` beside a blank `required-advancement` gated the mending trade on nothing with no warning about either half, because each half is legitimate alone. Both now reject `config.yml`. Two limits, both because refusing a boot over a configuration that is still enforceable would be a false refusal: they are errors **only while the gate reading them is switched on** — under `enabled: false`, `item-progression.enabled: false` or `gate-mending-trade: false` they stay warnings — and a plain value under a **dimension gate** stays a warning too, since those ship a non-empty default, so the gate goes on requiring the shipped advancements rather than nothing.

### Fixed
- An idle reminder whose delivery *and* whose failure log both threw no longer escapes the poll. The report was made from outside the guarded region, so a broken logger propagated out, the engine never stored the stamped state, and the once-a-second reminder loop the stamp ordering exists to close came back.
- A dimension gate whose last outstanding requirement is `require-account-age-days` is now announced. Tenure advances while the player is offline, so the gate was already open by the time they logged back in, was recorded silently by the join prime, and had no advancement and no online watch left to announce it — the player was never told. The set of gates a player has been congratulated on is now persisted in their `PersistentDataContainer`, and a join announces the difference. A player with no persisted record is primed silently, so installing this on an established server does not congratulate its whole population at once.
- `DIAMOND` is gated. `DIAMOND_*` has no trailing underscore to match the gem itself, so every tool made from a diamond was gated while the diamond was not.
- `NETHERITE_UPGRADE_SMITHING_TEMPLATE` is excluded from `netherite-tier`. It matches `NETHERITE_*` but belongs to `trim-progression`, and without the exclusion two systems claimed one material.
- `antispeedrun.bypass` is no longer a child of `antispeedrun.admin`. Because `antispeedrun.admin` defaults to `op`, every operator was silently exempt from every dimension gate, item lock, and anti-cheese rule.
- Dimension gate defaults are advancement-driven (`0` hours / `0` days), matching the specification. The shipped config previously required 2h playtime for the Nether and 20h plus a 7-day account age for The End.
- The `end-tier` item gate no longer contradicts the End dimension gate; a player could previously pass the gate and still be unable to pick up an Ender Eye.
- `max-single-hit-boss-damage` lowered from `50.0` to `12.0`. Against the Ender Dragon's 200 HP the previous cap still permitted a four-hit kill.
- The root `/antispeedrun` command no longer requires `antispeedrun.admin`, which had made `/asr progress` and `/asr book` unreachable for regular players despite both permissions defaulting to `true`.
