# Configuration reference

The plugin reads one file, `plugins/AntiSpeedrun/config.yml`, which is created from the
jar on first start. This page lists every section, its keys, and their defaults; for
installation, commands and troubleshooting see [administration.md](administration.md).

A default below is the value used when the key is absent from `config.yml`. It is
the same as the value in the shipped `SMP_STANDARD` file unless noted.

## 1. Sections at a glance

| Section | Status | Purpose |
| :--- | :--- | :--- |
| `config-version` | Live | Schema version of the file. Maintained by the plugin. |
| `profile` | Live (label only) | Names the preset the file was written from. |
| `dimension-gates` | Live | Requirements to enter the Nether and the End. |
| `item-progression` | Live | Tier-gated items. |
| `trim-progression` | Live | Armor trim and smithing template gating. |
| `idle-reminder` | Live | Standing-still reminder of the next goal. |
| `progress-card` | Live | How `/progress` renders. |
| `journey-book` | Live | The Journey Guide Book. |
| `boss-scaling` | Live (ships off) | The multi-dragon fight, the resummoned-fight exit portal lock and the dragon trophies. `enabled` is `false` by default. |
| `anti-cheese` | Live (new rules ship off) | The Eye of Ender block is on by default. The single-hit cap, the exit portal crystal block, the bed and anchor damage block and the Outer End boundary are off by default. |
| `villager-progression` | Live | Optional Mending trade gate. |

"Partly live" means some keys in the section are parsed and validated at load but no
code acts on them yet; setting those keys has no effect on the server. The section
names the ones that do nothing.

## 2. Precedence

There is one source of configuration: the file. There are no per-world or per-player
overrides, no environment overrides and no layering of profiles. In detail:

1. **The file wins over defaults.** An absent key uses the default on this page.
2. **A profile is a whole file, not a layer.** `/asr profile apply` replaces
   `config.yml` with the preset after backing the old one up. Nothing from the old file
   is merged in. The `profile` key itself does not change any behavior; setting
   `profile: HARDCORE` by hand without changing the other keys does nothing except
   change the label. `profile` is one of `CASUAL`, `SMP_STANDARD`, `HARDCORE` or
   `CUSTOM`.
3. **Gate decisions are layered on top of the configuration** for a given player, in
   this order of waivers (any one waives the gate): the standing `antispeedrun.bypass*`
   permission, an unexpired `/asr bypass` grant, and an `/asr unlock` of the
   dimension. These do not modify the configuration; see
   [administration.md](administration.md#4-commands-and-permissions).

## 3. Validation, fallback and refusal

Every value is checked when the file is loaded, at startup and on `/asr reload`. Three
outcomes exist.

**Silent default.** A key that is absent uses its default without a message.

**Warning and fallback.** The plugin keeps running and logs a `WARNING` line prefixed
`config.yml:`. The reload reply says how many warnings there were. This happens when:

- a key is not recognised (it is ignored);
- a value has the wrong type, is not a number, or is not one of the allowed options
  (the default is used);
- a number is below its minimum (the default is used; minimums are in the tables
  below);
- a message is not valid MiniMessage (the default is used);
- a list entry is not a string (the entry is dropped);
- a dimension gate's `require-advancements` is written as a single value rather than a
  list (the shipped advancements are used);
- an item tier's id contains a colon (the tier works but one of its refusal messages is
  suppressed while the other's cooldown runs; rename it);
- `item-progression.enabled` is true and `gated-items` declares no tiers (nothing is
  gated).

A bad value inside a section whose gate is switched off (`enabled: false`,
`item-progression.enabled: false`, or `gate-mending-trade: false`) is a warning, never
a refusal, because a gate that is off gates nothing.

**Refusal.** These are errors, not warnings. On a reload the previous configuration
stays live and the plugin is not disabled. At startup they behave as follows:

| Condition | Startup | Reload |
| :--- | :--- | :--- |
| The YAML cannot be parsed, or has no root mapping | Runs on built-in defaults (no item tiers, so item gating is off) | Rejected; previous configuration stays live |
| An enabled gate or tier names an advancement key that is not a valid namespaced key (upper case, inner space, stray character) | Plugin refuses to start | Rejected; previous configuration stays live |
| A `require-advancements` list whose entries are all blank | Plugin refuses to start | Rejected |
| An item tier's `require-advancements` written on one line as a plain value | Plugin refuses to start | Rejected |
| `villager-progression.gate-mending-trade: true` with a blank `required-advancement` | Plugin refuses to start | Rejected |
| Two item tiers claim one material and neither dominates the other | Plugin refuses to start | Rejected |
| The item gate table cannot be built from a file that parsed | Plugin refuses to start | Rejected |

The reason startup refuses rather than falling back is that the defaults declare no
item tiers: falling back would silently turn item gating off. A malformed advancement
key is refused because it can never be resolved, and an unresolvable requirement is
waived at runtime, so a gate naming it would report itself armed and let everyone
through. To require no advancement, write an empty list: `[]`.

A key that is well formed but names no advancement on the server (a typo in the path,
or an unloaded datapack) is not checked at load. It is waived at runtime and logged.
Check spelling against the game's advancement names.

Advancement keys are `namespace:path`. The `minecraft:` namespace is added when you
leave it off, so `story/smelt_iron` and `minecraft:story/smelt_iron` are the same. Spaces
around a key are ignored.

MiniMessage settings (`rejection-message`, `early-eye-rejection-message`, `message`
and `title`) accept MiniMessage tags such as `<red>` and `<gold>`. A section-sign
(`§`) code is not MiniMessage: it is refused with a warning and the default is used.
An `&` code is not detected; it loads without a warning and is shown as written.

Hints (`hint` on an item tier and on `villager-progression`) are plain text. They are
checked at load like the settings above, but a tag in a hint is shown to the player
literally. A `§` code in a tier hint drops that hint; in the villager hint it is
replaced by the default.

### 3.1 Reload behavior

`/asr reload` parses the whole file, builds the item gate table and only then swaps
the new configuration in as one unit, so no player is ever judged by a mix of old and
new settings. A rejected reload changes nothing. Changes take effect on the next event
that reads them; there is nothing to restart. A change to an advancement-driven gate
that makes it time-driven applies to players already online after the reload.

Reload does not read `state.yml` or player data. Editing the jar or `plugin.yml` needs
a restart.

### 3.2 Upgrading an older `config.yml`

Each version of the file's shape has a number, `config-version`. On every load, at
startup and on `/asr reload`, the plugin reads that number before it parses anything:

- **No `config-version`** is the shape released as v0.1.x. It is migrated to the
  current version.
- **An older number** is migrated forward one version at a time.
- **The current number** is read as it is. Nothing is rewritten.
- **A newer number, or one that is not a whole number of at least 1,** is refused. At
  startup the plugin does not start and says so; on reload nothing changes. The file is
  not touched. A newer number means a newer version of the plugin wrote the file:
  install that version, or restore an older file from `backups/`. It does not fall back
  to the defaults, because the file may describe gating this build cannot enforce.

A migration adds the keys the file is missing, each with its shipped default. A key you
already have is left alone even when its value differs from the default, with the one
exception below. Every key added so far ships switched off, so a migrated server
enforces what it enforced before. A section you deleted is not recreated: an absent
section already behaves as the defaults. Your comments, key order and line endings are
kept.

**The one exception: three rules are switched off once.** v0.1.x shipped
`anti-cheese.block-bed-anchor-boss-damage`, `block-exit-portal-crystal-place` and
`block-gateway-pre-dragon` as `true`, in `config.yml` and in the `HARDCORE` and
`SMP_STANDARD` presets (`CASUAL` had the first two), but no code enforced them, so a
v0.1.x server never applied them. Carrying `true` forward would switch on three rules
your server has never run. The migration from the unversioned shape therefore sets each
of the three to `false` where it reads `true`, editing the value in place and keeping
any comment on the line, and logs a `WARNING` naming each key it changed. The backup
holds the old values. To turn a rule on, set it back to `true` and run `/asr reload`; a
file that already has `config-version` is never changed this way again.

Before it writes, the plugin copies the old file to
`plugins/AntiSpeedrun/backups/config-<yyyyMMdd-HHmmss>.yml` and logs the name, the version
change and the keys added. The new file is staged and moved into place, so a failure
leaves the original as it was and the server reads it as it is.

Version 1 is the first numbered version. Going from v0.1.x to it adds
`config-version` and `anti-cheese.cap-single-hit-boss-damage: false`, and switches the
three rules above off. The version header is written after any `%YAML` directive or
`---` document-start line, so the file stays one YAML document.

The state files the plugin writes carry their own `state-version`; see
[administration.md](administration.md#63-state-file-versions). Player persistent data
is not versioned.

## 4. Keys

### 4.1 `config-version` and `profile`

| Key | Default | Notes |
| :--- | :--- | :--- |
| `config-version` | `1` | The schema version of the file, written by the plugin. Do not edit or remove it. See [section 3.2](#32-upgrading-an-older-configyml). |

| Key | Default | Notes |
| :--- | :--- | :--- |
| `profile` | `SMP_STANDARD` | `CASUAL`, `SMP_STANDARD`, `HARDCORE` or `CUSTOM`. Label only. |

### 4.2 `dimension-gates`

Two gates: `nether` and `the_end`. Each has the same keys. A player must satisfy all
of the requirements that are set.

| Key | Default | Notes |
| :--- | :--- | :--- |
| `enabled` | `true` | `false` admits everyone. |
| `require-playtime-hours` | `0` | Decimal hours of playtime. `0` means no time requirement. |
| `require-account-age-days` | `0` | Whole days since the player's first join on this server. `0` means none. A player the server has no first-join time for is waived rather than blocked. |
| `require-advancements` | Nether: `minecraft:story/smelt_iron`. End: `minecraft:story/mine_diamond`, `minecraft:nether/obtain_blaze_rod`, `minecraft:nether/find_fortress` | A list of advancement keys, all required. `[]` requires none. |
| `rejection-message` | A built-in message pointing at `/progress` | MiniMessage. Shown when a player is refused. |

Covered transits: portals, teleports across dimensions, vehicles and stasis.
`/asr unlock` overrides these gates server-wide (see
[administration.md](administration.md#53-dimension-unlock)).

The End gate's shipped `require-account-age-days` and `require-playtime-hours` are `0`
so that progression is advancement-driven by default.

### 4.3 `item-progression`

| Key | Default | Notes |
| :--- | :--- | :--- |
| `enabled` | `true` | Master switch for item tiers. |
| `drop-recall-enabled` | `true` | A player can always re-collect an item entity they dropped or died with. |
| `gate-dispensers` | `true` | Refuse dispenser-fired tier-locked armor onto an unqualified player. |
| `gate-nested-bundles` | `true` | Refuse extracting locked items from bundles. |
| `feedback-cooldown-seconds` | `3` | Minimum `0`. Throttle between refusal messages. |
| `rejection-message` | A built-in message | MiniMessage with `{ITEM}` and `{REQUIREMENT}` placeholders. |
| `gated-items` | none | A map of tier id to tier. There is no code default: with none declared, no item is gated. |

Each tier under `gated-items` takes:

| Key | Default | Notes |
| :--- | :--- | :--- |
| `match-patterns` | `[]` | Material name patterns with `*` wildcards, for example `IRON_*`. |
| `exclude-materials` | `[]` | Material names removed from the tier. |
| `items` | `[]` | Exact material names. |
| `require-advancements` | `[]` | List of advancement keys, all required. There is no built-in default, so an empty list requires nothing for the tier. |
| `require-playtime-hours` | `0` | Decimal hours. |
| `require-account-age-days` | `0` | Whole days. |
| `hint` | empty | Plain text shown as `{REQUIREMENT}`. |

The shipped file defines `iron-tier`, `diamond-tier`, `nether-tier`, `end-tier` and
`netherite-tier`. When two tiers claim one material, the tier whose requirements are a
superset of the others' wins. If none dominates, the plugin refuses to load the
file (section 3).

#### Shipped unlock progression

The Nether gate and the diamond and Nether tiers each unlock at their own step, so no
single advancement opens all three:

| Step | Advancement | Unlocks |
| :--- | :--- | :--- |
| 1 | `story/smelt_iron` (Acquire Hardware) | The Nether gate |
| 2 | `story/iron_tools` (Isn't It Iron Pick) | `diamond-tier` |
| 3 | `story/enter_the_nether` (We Need to Go Deeper) | `nether-tier` |

Iron smelting is the only cost of the portal, so a player can reach the Nether as soon as
they have iron. Diamonds wait for the iron pickaxe, the tool that mines them, which keeps
diamonds from arriving in the same moment as the Nether. Blaze, wart and ghast items are
gated on having entered the Nether, not on being allowed to, so they cannot be hoarded
before the first visit. Exception: `BREEZE_ROD` and `WIND_CHARGE` are in `nether-tier` but come from overworld trial chambers, so a player who finds them before entering the Nether meets the lock. The `HARDCORE` profile uses the same diamond and Nether-tier keys and
adds its playtime requirement; `CASUAL` and `SMP_STANDARD` match the table above.

### 4.4 `trim-progression`

With `enabled` and `block-unearned-template-duplication` set to `true`, a player cannot
copy a gated template in a crafting table until they have earned its structure
advancement. Crafters use the advancement record of the player who placed them; they
continue to work while that player is offline. A Crafter without a recorded owner
refuses gated template recipes. Smithing and wearing locks also apply to unearned trims.
The three locks `block-unearned-template-duplication`, `block-unearned-smithing` and
`block-wearing-unearned-trims` default to `true`.

With `gate-natural-trim-chests` (default `false`) also `true`, a gated template that
generates in a naturally generated chest or chest minecart is removed from the loot when
the player opening or breaking it has not earned its structure. Loot generated with no
player, such as a hopper pulling from an unopened chest, never keeps a gated template,
the same rule as a Crafter without an owner. The removal is permanent for that container.
`antispeedrun.bypass.items` and `/asr bypass` waive it; loot a plugin generates is not
affected. See [administration.md](administration.md#8-a-structure-chest-had-no-trim-template).

The structure each lock requires is fixed, not configured:

| Structure | Gates | Advancement |
| :--- | :--- | :--- |
| Ancient City | Silence and Ward trims | `minecraft:adventure/avoid_vibration` (vanilla has no Ancient City advancement) |
| Bastion Remnant | Snout trim, netherite upgrade template | `minecraft:nether/find_bastion` |
| End City | Spire trim | `minecraft:end/find_end_city` |

Every other trim is ungated.

### 4.5 `idle-reminder`

| Key | Default | Notes |
| :--- | :--- | :--- |
| `enabled` | `true` | |
| `stand-still-seconds` | `15` | Minimum `1`. Standing still this long triggers a reminder. |
| `cooldown-minutes` | `10` | Minimum `0`. Minimum gap between reminders. |
| `display-duration-seconds` | `5` | Minimum `1`. |
| `display-type` | `ACTIONBAR` | `ACTIONBAR`, `TITLE` or `CHAT`. |
| `message` | A built-in message | MiniMessage with a `{NEXT_STEP}` placeholder. |

### 4.6 `progress-card`

| Key | Default | Notes |
| :--- | :--- | :--- |
| `simple-card` | `AUTO` | `AUTO` uses the plain-ASCII card for Bedrock players detected through Floodgate; `ALWAYS` uses it for everyone; `NEVER` uses the glyph card for everyone. |

### 4.7 `journey-book`

| Key | Default | Notes |
| :--- | :--- | :--- |
| `give-on-first-join` | `true` | Gives the book once, a tick after a player joins, to anyone who has not received one. This includes players who joined before the plugin was installed. |
| `title` | `<gold>Ninja6 Survival Guide` | MiniMessage. |
| `author` | `Ninja6-MC` | Plain text. |

The book's content is generated from the live configuration: the dimension gates and
their requirements, the item tiers with their hints, and the Eye of Ender and Mending
rules while they are on. `/journeybook` refuses while the player already carries a
copy or has no room for one.

### 4.8 `boss-scaling`

The default config and every bundled profile set `enabled: false`. Set it to `true` to
turn the multi-dragon fight on.

The reinforcement window is live. The first player to enter an End whose dragon has
never been killed starts a countdown of `battle-prep-seconds`; the dragon is not held
back and fights from the start. When the countdown ends, every survival or adventure
player within 300 blocks of the centre is counted, and the party fights
`max(1, min(max-dragons, round(players * multiplier)))` dragons: the extra ones spawn
above the main island at that moment. A party of one always fights exactly one dragon,
even with a `multiplier` above `1.0`. With `enabled: false` nothing happens, and with
`multi-dragon.enabled: false` the count is always one. `battle-prep-seconds: 0` counts
on entry.

`round` is `multi-dragon.rounding-mode`. The product is taken on the multiplier as
written, so `10 * 0.7` is exactly `7`.

| `rounding-mode` | Rounds | 3 players at `0.5` | 4 players at `0.3` | 4 players at `0.7` |
| :--- | :--- | :--- | :--- | :--- |
| `HALF_UP` (default) | A half or more up, less than a half down | 2 | 1 | 3 |
| `CEIL` | Any fraction up | 2 | 2 | 3 |
| `FLOOR` | Any fraction down, but never below one dragon | 1 | 1 | 2 |

Case and hyphens are ignored (`ceil` is `CEIL`). Any other value logs a warning at load
and falls back to `HALF_UP`. The solo rule wins over every mode: a party of one fights
one dragon even under `CEIL` with a `multiplier` above `1.0`.

The first fight's window opens once per End world. That it has run is recorded in
`dragon-fights.yml` (see [administration.md](administration.md#6-persisted-state)), so a
restart during the fight does not spawn a second set of dragons. An extra dragon found
when its chunk loads also marks the window as run, in case that file is lost.

**Resummoned fights.** With `scale-resummoned-dragons: true` (and `enabled: true`), a
dragon brought back by placing four End crystals on the exit portal opens a window of
its own the moment it appears: the same `battle-prep-seconds` countdown, the same
count on the main island and the same dragon formula, so each resummon is scaled to the
party that is there for it. `/summon`, spawn eggs and other plugins' dragons do not open
one. The window ends early, with nothing spawned, if the resummoned dragon dies first.
Resummoned windows are not recorded in `dragon-fights.yml`: a restart during a
resummoned fight never opens a second window, but a restart during its countdown loses
that fight's extra dragons.

Vanilla treats the End as a one-dragon fight: the exit portal, the egg, the 12,000 XP
first-kill award and the "dragon killed" flag all belong to the original dragon. While
any extra dragon is alive, the original one cannot die. A killing blow leaves it on one
health, and players in the End are told how many extra dragons remain. Once the last
extra dragon is dead, the original can be killed and vanilla's victory runs once: the
portal opens, the egg forms, the 12,000 XP drops and the flag is set. Anything keyed to
that flag, such as the outer-End boundary, holds for the whole fight. The primary's XP
is untouched: 12,000 on the world's first kill and 500 on a resummoned kill. With
`multi-dragon.balanced-xp: true` (the default), each extra dragon drops 1,000 XP.
With it off, each extra dragon drops at most 500 XP, vanilla's repeat-kill amount.
An extra dragon drops no XP when `doMobLoot` disables the vanilla reward. This applies
to extra dragons already alive even after `enabled` is set to `false`.

Vanilla's fight follows its dragon by identity. If that dragon goes unloaded for 1,200
ticks, for example after flying out of every loaded chunk, the fight adopts another loaded
dragon, which would be an extra one. The plugin notices within a second and swaps the
roles: the adopted dragon becomes the original, held until the extra dragons are dead, and
the dragon it replaced counts as an extra one at once, even while its chunk is unloaded, so
the fight cannot end until it has been found and killed. It carries the extra-dragon tag
from its next chunk load. A WARNING is logged when this happens. The swap is not saved, so
after a restart the replaced dragon is not counted until its chunk loads, and then comes
back as a dragon the fight ignores: it is held like the original while extra dragons live,
and its death ends nothing.

**Crystal healing.** Extra dragons do not heal from End crystals; the original dragon
still does. The pillar crystals are sized for one dragon. If every dragon could heal
from them, each crystal destroyed would cut off healing for several dragons at once,
and a larger party would face an easier fight per dragon. Without it, each extra dragon
has a fixed 200 health however the crystals go. The client may still draw a crystal
beam to an extra dragon; the beam heals nothing.

During a resummoned fight, `exit-portal-lock-during-battle: true` keeps the existing
exit portal a solid bedrock basin. Portal transit is refused even if another plugin
reopens its blocks. The first fight has no exit portal until victory, so it keeps
vanilla's first-entry behaviour. The lock ends when the resummoned primary dies,
after every extra dragon has fallen. `exit-portal-lock-release-minutes` opens the
portal early after that many minutes without a successful player hit on any dragon;
`0` disables this escape. Turning the lock off with `/asr reload` also opens it.
After a server restart or plugin reload, a sealed exit is reopened on startup using
the durable marker in `portal-locks.yml`; a new resummon must start to lock it again.

On a resummoned victory, `exit-portal-egg.enabled` awards another egg. Placement
mode `TOP_PILLAR` puts it above the centre pillar if the space is empty, and drops
the item there if that space is occupied. `ITEM_DROP` always drops the item, and
`NONE` awards no egg. The first victory's egg is still vanilla's. Each dragon death
independently rolls `skull-drop-chance` for a Dragon Head, respecting `doMobLoot`.
These features run only with `boss-scaling.enabled: true`.

Validation still applies:
`battle-prep-seconds` and `exit-portal-lock-release-minutes` have minimum `0`,
`multi-dragon.multiplier` must be greater than `0.0`, `multi-dragon.max-dragons` has
minimum `1`, and `rounding-mode` and `placement-mode` must be one of their options.

### 4.9 `anti-cheese`

| Key | Default | Status |
| :--- | :--- | :--- |
| `enabled` | `true` | Live. Master switch for the Eye of Ender block. |
| `block-early-eye-throwing` | `true` | Live. Refuse an Eye of Ender throw until the player has earned `minecraft:nether/find_fortress`. Setting an eye in an End portal frame is not a throw and is not affected. |
| `early-eye-rejection-message` | A built-in message | Live. MiniMessage. |
| `block-gateway-pre-dragon` | `false` | Live. Hold players inside `outer-end-radius` of the origin in an End until that world's first dragon has been killed. Refuses ender pearls and chorus fruit that would land beyond it, and a poll every `outer-end-poll-seconds` sends anyone found beyond it (bridging, Elytra, Wind Charges) back to the last position they held inside. Creative and spectator players and holders of `antispeedrun.bypass.anticheese` or a bypass grant are exempt. Off by default for now; once the first dragon is killed the outer End stays open. Also needs `enabled`. |
| `outer-end-radius`, `outer-end-poll-seconds` | `500`, `2` | Live with `block-gateway-pre-dragon`. Both have minimum `1`. |
| `cap-single-hit-boss-damage` | `false` | Live. Enforces `max-single-hit-boss-damage` against the Ender Dragon and the Wither. Off by default. |
| `max-single-hit-boss-damage` | `12.0` | Live when `cap-single-hit-boss-damage` is `true`. The most a single hit may do after armour and resistance. A time-to-kill budget: against the dragon's 200 HP, `12.0` is roughly a 17-hit fight. Hits on the dragon's parts count, and hits landing in the same tick share one cap, so a stack of explosions removes at most this much. `/kill` is not capped. |
| `block-exit-portal-crystal-place` | `false` | Live. Refuses an End Crystal placed on the exit portal's centre column, block (0, 0) of an End world, which duplicates crystals. The four crystals of the resummon ritual go on the blocks beside it and are not affected. Holders of `antispeedrun.bypass.anticheese` or a bypass grant are exempt. Off by default for now. Also needs `enabled`. |
| `block-bed-anchor-boss-damage` | `false` | Live. Cancels bed and Respawn Anchor explosion damage to the Ender Dragon (including hits on its parts) and the Wither, detected by damage type `BAD_RESPAWN_POINT`. TNT, arrows and melee are unaffected. The damage has no causing entity, so this rule cannot be bypassed, including by `antispeedrun.bypass.anticheese` or an `/asr bypass` grant. Off by default for now. Also needs `enabled`. |

The early Eye block is waived by `antispeedrun.bypass.anticheese`, an `/asr bypass`
grant, or `/asr unlock end`. The damage cap is waived for a hit caused by a player with
`antispeedrun.bypass.anticheese` or an `/asr bypass` grant.

### 4.10 `villager-progression`

| Key | Default | Notes |
| :--- | :--- | :--- |
| `gate-mending-trade` | `false` | When true, the Mending trade is refused until the player has earned `required-advancement`. |
| `required-advancement` | `minecraft:story/cure_zombie_villager` | Must not be blank while `gate-mending-trade` is true. |
| `hint` | `Cure a Zombie Villager (Zombie Doctor)` | Plain text. A warning is logged if you change `required-advancement` and leave the shipped hint. |

## 5. Profiles

The three presets are `src/main/resources/profiles/<name>.yml`, and each is a complete
file. The differences in the live sections:

| Setting | `CASUAL` | `SMP_STANDARD` | `HARDCORE` |
| :--- | :--- | :--- | :--- |
| Nether gate | `story/smelt_iron` | `story/smelt_iron` | `story/smelt_iron`, `story/upgrade_tools`, 2 hours |
| Diamond tier | `story/iron_tools` | `story/iron_tools` | `story/iron_tools`, 2 hours |
| Nether tier | `story/enter_the_nether` | `story/enter_the_nether` | `story/enter_the_nether`, 2 hours |
| End gate | `nether/obtain_blaze_rod` | `story/mine_diamond`, `nether/obtain_blaze_rod`, `nether/find_fortress` | those three plus `story/enchant_item`, 10 hours |
| `item-progression.enabled` | `false` | `true` | `true` |
| `item-progression.feedback-cooldown-seconds` | 3 | 3 | 5 |
| Item tier playtime | 0 | 0 | 2 hours (diamond, nether), 10 hours (end, netherite) |
| `idle-reminder` | 20 s stand, 15 min cooldown | 15 s, 10 min | 15 s, 5 min |
| `anti-cheese.block-early-eye-throwing` | `false` | `true` | `true` |
| `villager-progression.gate-mending-trade` | `false` | `false` | `true` |
| `trim-progression.enabled` and the three locks (`block-unearned-template-duplication`, `block-unearned-smithing`, `block-wearing-unearned-trims`) | `false` | `true` | `true` |
| `trim-progression.gate-natural-trim-chests` | `false` | `false` | `true` |
| `boss-scaling.multi-dragon.enabled` | `false` | `true` | `true` |

Because a preset overwrites `config.yml`, apply one before you customize, not after.

## 6. Example: enable the Mending gate and add a time requirement to the End

```yaml
villager-progression:
  gate-mending-trade: true
  required-advancement: "minecraft:story/cure_zombie_villager"

dimension-gates:
  the_end:
    require-playtime-hours: 5.0
```

Run `/asr reload`. The reply says "Configuration reloaded" and shows the item gate
count, or reports warnings. To confirm the effect, run `/asr inspect <player>`: the
End line now lists hours of playtime still missing.
