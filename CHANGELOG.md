# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `trim-progression.gate-natural-trim-chests` now acts: while it and `trim-progression.enabled` are on, a Silence, Ward, Snout or Spire trim template or a netherite upgrade template is removed from naturally generated chest and chest minecart loot when the player it is generated for has not explored its structure. Loot generated with no player (a hopper pulling from an unopened chest) never keeps one. The item bypass waives it; plugin-generated loot is not affected. Off by default; the `HARDCORE` profile turns it on.
- The state files `state.yml`, `dragon-fights.yml`, `portal-locks.yml` and `explored-structures.yml` carry a `state-version` (1). On start an unversioned file is read as version 1 and the key is added with its data unchanged. A newer or malformed version stops startup with the file untouched. Player persistent data is not versioned.

### Fixed
- `anti-cheese.cap-single-hit-boss-damage` now holds for hits landing in the same tick. A stack of TNT minecarts detonated together still removed 43.1 from the Wither and 35.2 from the dragon against a cap of 12, because each explosion was capped on its own; every hit a boss takes in one tick now shares one cap, and a hit beyond it is cancelled.
- A multi-dragon fight no longer ends when a secondary dragon dies after the primary has been unloaded for a minute. Vanilla's dragon fight adopts any loaded Ender Dragon when its own has not ticked for 1,200 ticks, so a secondary's death ran the victory sequence (exit portal and egg) early. The plugin now checks once a second which dragon the fight tracks; an adopted secondary becomes the primary, and the dragon it replaced counts as a secondary at once, even while unloaded, and is tagged as one when its chunk loads. A WARNING is logged when it happens.

## [0.2.0] - 2026-10-05

### Added
- `config.yml` carries a `config-version` (1). On start and on `/asr reload` an older file, including the unversioned v0.1.x shape, is migrated forward: missing keys are added with their shipped off defaults, comments are kept, and the old file is saved to `backups/` first. The upgrade from the unversioned shape also sets `anti-cheese.block-bed-anchor-boss-damage`, `block-exit-portal-crystal-place` and `block-gateway-pre-dragon` from `true` to `false`, once, with a WARNING per key: v0.1.x shipped them `true` without enforcing them, so `false` keeps what the server did. Set one back to `true` to turn it on. No other value is changed. A newer or unreadable version stops startup with the file untouched. State files are not versioned yet.
- `anti-cheese.block-bed-anchor-boss-damage`, off by default, cancels bed and Respawn Anchor explosion damage to the Ender Dragon (including its parts) and the Wither, detected by `DamageType.BAD_RESPAWN_POINT`. TNT, arrows and melee are not affected. Its shipped default changes from `true` to `false`; it was parsed only until now.
- `anti-cheese.block-exit-portal-crystal-place`, off by default, refuses an End Crystal placed on the exit portal's centre column. The four resummon ritual positions are not affected. Its shipped default changes from `true` to `false`; it was parsed only until now.
- `anti-cheese.cap-single-hit-boss-damage`, off by default, enforces
  `max-single-hit-boss-damage` against the Ender Dragon and the Wither. The cap applies to final
  damage after armour and resistance, including hits on the dragon's parts, so stacked TNT
  minecarts and Mace smashes cannot skip the fight.
- Outer-End boundary (`anti-cheese.block-gateway-pre-dragon`, `outer-end-radius`, `outer-end-poll-seconds`) holds players within the radius until the world's first dragon is killed. It is off by default, including in every bundled profile.

### Upgrading from 0.1.x
- Every new anti-cheese rule ships off. Enable the ones you want in `config.yml` under
  `anti-cheese`, then run `/asr reload`.
- On first start the config migration adds `config-version: 1`, saves the old file to
  `plugins/AntiSpeedrun/backups/`, and switches `block-gateway-pre-dragon`,
  `block-exit-portal-crystal-place` and `block-bed-anchor-boss-damage` from `true` to
  `false`, once, because in 0.1.x they did nothing. Set one back to `true` to enforce it.
- A clean install also works: back up `plugins/AntiSpeedrun/` and `playerdata`, delete
  `config.yml`, install the jar and restart. Deleting `config.yml` loses every local edit
  and any preset applied with `/asr profile apply`; the regenerated file is the default
  `SMP_STANDARD`. State files carry over.

## [0.1.1] - 2026-10-02

### Fixed
- Journey Guide Book delivery on first join and through `/journeybook` or `/asr book`
  no longer fails with a `NoSuchMethodError` on Paper 26.2. The compatible Paper
  1.21.4 API baseline is retained.

## [0.1.0] - 2026-09-30

### Added
- Resummoned dragon fights keep the exit portal sealed in its bedrock basin until
  victory, with a configurable inactivity escape. Repeat victories award a
  configurable Dragon Egg trophy, and each dragon can drop a Dragon Head.
- `boss-scaling.multi-dragon.balanced-xp` now awards 1,000 XP per extra dragon when enabled.
  Turning it off retains the previous 500 XP cap. The original dragon keeps vanilla's
  first-kill or resummon reward, and `doMobLoot` still prevents XP drops.
- `boss-scaling.scale-resummoned-dragons`, which until now was parsed and read by nothing, scales
  fights resummoned with four End crystals. The resummoned dragon opens its own
  `battle-prep-seconds` window when it appears, and the party on the main island is counted and
  reinforced exactly as for the first fight. `/summon`, spawn eggs and other plugins' dragons do not
  open one.
- `boss-scaling.multi-dragon.rounding-mode`, which until now was parsed and read by nothing, sets
  how the party's `players * multiplier` rounds to a dragon count: `HALF_UP` (the default), `CEIL`
  or `FLOOR`. The product is exact, so `10 * 0.7` is seven under `CEIL`. A party of one still
  fights one dragon under every mode, and an unknown mode warns and falls back to `HALF_UP`.
- One victory for a multi-dragon fight. The original Ender Dragon cannot die while an extra dragon
  lives, so the exit portal, the egg, the 12,000 XP award and the "dragon killed" flag wait for the
  last dragon. Extra dragons do not heal from End crystals. A resolved
  reinforcement window is recorded in `dragon-fights.yml`, so a restart mid-fight no longer spawns
  a second set of extra dragons.
- The multi-dragon reinforcement window. The first player to enter an End whose dragon has never
  been killed starts a `boss-scaling.battle-prep-seconds` countdown, shown to everyone in the End;
  the dragon fights from the start, so a solo player is never kept waiting. When it ends, survival
  and adventure players within 300 blocks of the centre are counted and the extra dragons the
  party earns under `multi-dragon.multiplier` and `max-dragons` spawn above the main island.
  Resummoned fights can now lock their existing exit portal.
- Handing a tier-gated item to an Allay, or taking one back from it, is refused for a player who
  has not earned the tier. Allays collecting and delivering items on their own are unaffected, so
  sorters keep working. Bottling Dragon's Breath is refused until the player meets the End gate's
  requirement; it follows `dimension-gates.the-end.enabled` and is waived by the End waivers and
  the item bypass. Mobs picking up items are not gated.
- Taking a tier-gated item off an armor stand or out of an item frame is refused for a player who
  has not earned the tier. Swapping an item onto an armor stand slot that holds a gated piece counts
  as taking it, and a framed item knocked out by the player's punch or projectile stays in the frame.
  Placing items and rotating a framed item are unaffected. Follows `item-progression.enabled`, the
  tier table and the item bypass.
- `item-progression.gate-nested-bundles`, which until now was parsed and read by nothing, gates
  bundle contents. Taking an item out of a bundle is refused while the bundle holds a stack the
  player has not earned, wherever the bundle sits: in a container, or in the player's own hotbar,
  main inventory or off hand. Using such a bundle in either hand to spray its contents onto the
  ground is refused too, without blocking the block that was clicked. Every stack in the bundle is
  checked, because the item the client selects is not visible to the server, so a bundle holding
  only ungated or earned items is untouched. Shulker box contents were already covered: a placed
  shulker box is a container like any other.
- A dispenser firing tier-gated armour onto a player who has not earned that tier is now cancelled, so
  the armour stays in the dispenser and the player gets the usual item-gate rejection line. It follows
  `item-progression.gate-dispensers` (default on), `item-progression.enabled`, the tier table and the item bypass, and only players are gated.
- `anti-cheese.block-early-eye-throwing`, which until now was parsed and read by nothing, refuses an
  Eye of Ender throw until the player has earned `minecraft:nether/find_fortress`. The item use is
  denied at the interaction, so the eye stays in the hand and no Eye of Ender entity spawns; setting
  an eye into an empty End Portal frame is not a throw and is left alone. Waived by
  `antispeedrun.bypass.anticheese`, an `/asr bypass` grant, or `/asr unlock the_end`. The refusal
  line is the new `anti-cheese.early-eye-rejection-message`, validated as MiniMessage at load like
  every other rejection message.
- The Journey Guide Book. `/journeybook` (alias `/rulesbook`) and `/asr book` hand the player a
  written book generated from the live configuration: the dimension gates and what each requires,
  the item tiers with their hints, and the Eye of Ender and Mending rules while they are on.
  Vanilla advancements are named by their in-game titles in the reader's own language. With
  `journey-book.give-on-first-join` on, a player who has never received the book is given one a
  tick after joining — including players who joined before the plugin was installed, since
  delivery is decided by the plugin's own persisted flag rather than `hasPlayedBefore()`. Both
  spellings of the command are gated on `antispeedrun.book` and refuse while the player already
  carries a copy or has no room for one.
- Brand vector master (`docs/assets/icon-master.svg`) and automated multi-resolution icon suite
  (`scripts/export-icons.mjs`) featuring the Progression Crown and Tri-Realm Apex, with CI drift
  gate enforcement (`.github/workflows/icons.yml`).
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
  refused once, not twice. The refusal is worded by `villager-progression.hint`, shipped as "Cure a
  Zombie Villager (Zombie Doctor)"; left blank, it names the raw advancement key. While the gate is
  switched on, a `required-advancement` changed from the shipped key beside that shipped hint is a
  warning at load, since the refusal would describe a requirement the gate no longer asks for; a
  switched-off gate refuses nothing, so it is not warned about. The gate is
  independent of `item-progression.enabled` but borrows that section's `rejection-message` and
  `feedback-cooldown-seconds`. An item tier id containing `:` is warned about at load: it still gates
  every item it names, but a tier named exactly `villager:mending` shares the Mending gate's feedback
  throttle and suppresses one of the two refusal lines.
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
  and would have skipped an established server's entire population.
- Configuration profile presets shipped as `profiles/casual.yml`, `profiles/smp_standard.yml` and
  `profiles/hardcore.yml`. `/asr profile apply` copies the previous `config.yml` to
  `backups/config-<timestamp>.yml` before overwriting it.
- Wildcard match modes for `match-patterns` — prefix (`IRON_*`), suffix (`*_IRON_ORE`), contains (`*_DIAMOND_*`) and exact — compiled once per configuration snapshot into an `EnumMap`/`EnumSet` material lookup that allocates nothing per item pickup.
- Most-restrictive-wins precedence when two tiers claim one material, resolved by requirement dominance and then by document order; an incomparable pair fails startup with an error naming the material, both tiers and the `exclude-materials` line that resolves it.
- Folia platform support declaration (`folia-supported: true`), without which Folia refuses to enable the plugin.
- CI guards rejecting `BukkitScheduler` / `BukkitRunnable` usage, which throws on Folia, and verifying the Folia manifest key is present.
- CI `server-smoke` matrix booting the built jar on real Paper and Folia servers across the supported range.
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
- Supported servers are stated as Paper or Folia, Minecraft 1.21.4 through 26.2, and CI now boots the built jar on each end of that range and on 1.21.11 for both platforms, plus a weekly run on the newest Paper and Folia release. Earlier 1.21 releases are no longer implied: the plugin compiles against the 1.21.4 API.
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
- A progression check reached through a stale reference to a player who has already left no longer reads their statistics, first-join time or advancements, none of which any Folia region owns once the player is removed. The check now answers without touching the player, with every requirement unmet, and caches nothing.
- The dimension gate is now enforced on Folia. Its arrival backstop listened for `PlayerChangedWorldEvent`, which Folia never fires, so it acted on Paper only; and on Folia 1.21.4 neither portal event fires either, on foot or in a vehicle, so a player riding a boat through a Nether portal, or walking through one, crossed any gate unchecked. The backstop now listens for the player being added to the destination world, which both platforms report, and an ineligible, unwaived arrival is returned to the source world's spawn and told why. Paper still stops the transit at the portal as before, with the backstop as the second line behind it. On Folia a cross-dimension teleport by a command or another plugin is judged by the backstop too, because Folia reports no teleport cause from which to exempt it; the entry below narrows that.
- On Folia an operator's cross-dimension `/tp` no longer sends an ineligible player straight back. Folia's `teleportAsync` fires no `PlayerTeleportEvent`, so the backstop could not tell a deliberate teleport from a boat through a portal. A vanilla `tp` or `teleport` is now read off the command line when the sender holds `minecraft.command.teleport` (and `minecraft.command.execute` when it is wrapped in `execute`), and the players it names are let into the world it names. The forms read are the four vanilla shapes, bare or under `execute` with `in`, `as`, `at` and the position-only subcommands; a line it cannot read with certainty, including one with a conditional, is judged as before. Another plugin can announce its own teleport with `AntiSpeedrunPlugin#expectTeleport(Player, World)`; a plugin that does not is still judged on Folia. Paper is unchanged.
- A refusal no longer depends on its message being sent. The item gate, the Mending trade gate, the vehicle portal check and the arrival backstop (Paper only until the entry above) each told the player why before cancelling the event or scheduling the return, so a message that threw left the event uncancelled and the player through. Each now refuses first and messages after.
- A configuration warning that quotes an operator's value now strips Unicode line and paragraph separators and bidirectional controls as well as control characters, so a value carrying U+2028 cannot split one warning into several console lines and one carrying U+202E cannot reverse the rest of it. The warnings for an unresolvable advancement key, an unknown enum value and a value of the wrong type now quote through the same sanitiser and length bound as the rest.
- `config.yml` no longer says an item tier `hint` takes MiniMessage colour tags. It is checked as MiniMessage at load, but shown as plain text, so a tag in it appears as written.
- A player returned to a world's spawn by a dimension gate is no longer set down inside a sealed pocket. The landing search accepted any two blocks of harmless air over solid ground, which is also the shape of an ore pocket in the middle of netherrack, and the spawn column of the Nether is searched over the world's whole height looking for a gap. A spot now counts only when a player standing on it could walk off it: the search steps out over the ground around the candidate, as far as three blocks and two dozen places to stand, and refuses a space that closes in on itself inside those bounds — a single block, a pair of them, or a small room alike. Anything larger is kept, because room to move, dig and light is not the trap this refuses, and neither is a cave a long way from anywhere. Ground the server cannot be asked about from the thread doing the asking counts as walled in rather than being read anyway. When the column offers nothing better the spawn is handed back unchanged, as before. The same rule applies to the retreat behind a portal, which falls back to where the rider already was.
- A `rejection-message`, an item tier `hint` and `journey-book.title` are validated as MiniMessage when the configuration is read, instead of being deserialised on a region thread at the moment they are sent. A template MiniMessage refuses — a legacy section-sign colour code is the one an operator is likely to write — is now reported by a warning at startup and on every `/asr reload`, naming the key, and the shipped default for that same key is used in its place; for a tier `hint`, whose default is blank, that means no hint, so the refusal names what is still outstanding. Previously the template threw on every refusal, and because `ItemProgressionListener` and the arrival backstop in `ProgressionGateListener` sent the message before they cancelled or ejected, the throw could let the refused player through. Those paths now refuse first, as the entry above describes.
- `villager-progression.hint` is validated as MiniMessage when the configuration is read, like a tier `hint`. A legacy section-sign colour code in it threw when the Mending refusal was deserialised, before the trade was cancelled, so an ineligible player could buy Mending once per feedback cooldown. It now warns at load and the shipped hint is used; that fallback is not also reported as a stale hint.
- A dimension gate whose last outstanding requirement is `require-account-age-days` is now announced. Tenure advances while the player is offline, so the gate was already open by the time they logged back in, was recorded silently by the join prime, and had no advancement and no online watch left to announce it — the player was never told. The set of gates a player has been congratulated on is now persisted in their `PersistentDataContainer`, and a join announces the difference. A player with no persisted record is primed silently, so installing this on an established server does not congratulate its whole population at once.
- `DIAMOND` is gated. `DIAMOND_*` has no trailing underscore to match the gem itself, so every tool made from a diamond was gated while the diamond was not.
- `NETHERITE_UPGRADE_SMITHING_TEMPLATE` is excluded from `netherite-tier`. It matches `NETHERITE_*` but belongs to `trim-progression`, and without the exclusion two systems claimed one material.
- `antispeedrun.bypass` is no longer a child of `antispeedrun.admin`. Because `antispeedrun.admin` defaults to `op`, every operator was silently exempt from every dimension gate, item lock, and anti-cheese rule.
- Dimension gate defaults are advancement-driven (`0` hours / `0` days), matching the specification. The shipped config previously required 2h playtime for the Nether and 20h plus a 7-day account age for The End.
- The `end-tier` item gate no longer contradicts the End dimension gate; a player could previously pass the gate and still be unable to pick up an Ender Eye.
- `max-single-hit-boss-damage` lowered from `50.0` to `12.0`. Against the Ender Dragon's 200 HP the previous cap still permitted a four-hit kill.
- The root `/antispeedrun` command no longer requires `antispeedrun.admin`, which had made `/asr progress` and `/asr book` unreachable for regular players despite both permissions defaulting to `true`.
