# Configuration reference

The plugin reads one file, `plugins/AntiSpeedrun/config.yml`, which is created from the
jar on first start. This page lists every section, its keys, and their defaults; for
installation, commands and troubleshooting see [administration.md](administration.md).

A default below is the value used when the key is absent from `config.yml`. It is
the same as the value in the shipped `SMP_STANDARD` file unless noted.

## 1. Sections at a glance

| Section | Status | Purpose |
| :--- | :--- | :--- |
| `profile` | Live (label only) | Names the preset the file was written from. |
| `dimension-gates` | Live | Requirements to enter the Nether and the End. |
| `item-progression` | Live | Tier-gated items. |
| `trim-progression` | **Planned** | Armor trim and smithing template gating. |
| `idle-reminder` | Live | Standing-still reminder of the next goal. |
| `progress-card` | Live | How `/progress` renders. |
| `journey-book` | Live | The Journey Guide Book. |
| `boss-scaling` | Partly live | Only `enabled`, `battle-prep-seconds` and `multi-dragon.enabled`, `multiplier`, `rounding-mode` and `max-dragons` do anything. |
| `anti-cheese` | Partly live | Only `enabled`, `block-early-eye-throwing` and `early-eye-rejection-message` do anything. |
| `villager-progression` | Live | Optional Mending trade gate. |

"Planned" means the keys are parsed and validated at load, and warnings about them are
logged, but no code acts on them. Setting them has no effect on the server.

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

## 4. Keys

### 4.1 `profile`

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

### 4.4 `trim-progression` (planned)

Keys `enabled`, `gate-natural-trim-chests`, `block-unearned-template-duplication`,
`block-unearned-smithing` and `block-wearing-unearned-trims` are parsed only. Tracked
in [#45](https://github.com/Ninja6-MC/AntiSpeedrun/issues/45).

The structure each lock will require is fixed, not configured:

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

The default config and every bundled profile set `enabled: false` for `v0.1.0`, while
the multi-dragon feature group is still in progress. Set it to `true` to try the current
behaviour on a test server. A profile application keeps it off until a later build ships
the complete fight.

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
that flag, such as the outer-End boundary, holds for the whole fight. Each extra dragon
drops at most 500 XP, vanilla's amount for a repeat kill, until balanced XP
([#22](https://github.com/Ninja6-MC/AntiSpeedrun/issues/22)) lands. This applies to
extra dragons already alive even after `enabled` is set to `false`.

**Crystal healing.** Extra dragons do not heal from End crystals; the original dragon
still does. The pillar crystals are sized for one dragon. If every dragon could heal
from them, each crystal destroyed would cut off healing for several dragons at once,
and a larger party would face an easier fight per dragon. Without it, each extra dragon
has a fixed 200 health however the crystals go. The client may still draw a crystal
beam to an extra dragon; the beam heals nothing.

`balanced-xp`, `exit-portal-egg.*`, `skull-drop-chance`, `exit-portal-lock-during-battle` and
`exit-portal-lock-release-minutes` are parsed only. Tracked in
[#46](https://github.com/Ninja6-MC/AntiSpeedrun/issues/46). Validation still applies:
`battle-prep-seconds` and `exit-portal-lock-release-minutes` have minimum `0`,
`multi-dragon.multiplier` must be greater than `0.0`, `multi-dragon.max-dragons` has
minimum `1`, and `rounding-mode` and `placement-mode` must be one of their options.

### 4.9 `anti-cheese`

| Key | Default | Status |
| :--- | :--- | :--- |
| `enabled` | `true` | Live. Master switch for the Eye of Ender block. |
| `block-early-eye-throwing` | `true` | Live. Refuse an Eye of Ender throw until the player has earned `minecraft:nether/find_fortress`. Setting an eye in an End portal frame is not a throw and is not affected. |
| `early-eye-rejection-message` | A built-in message | Live. MiniMessage. |
| `block-bed-anchor-boss-damage`, `max-single-hit-boss-damage`, `block-exit-portal-crystal-place`, `block-gateway-pre-dragon`, `outer-end-radius`, `outer-end-poll-seconds` | `true`, `12.0`, `true`, `true`, `500`, `2` | Planned; parsed only (tracked in [#47](https://github.com/Ninja6-MC/AntiSpeedrun/issues/47)). `outer-end-radius` and `outer-end-poll-seconds` have minimum `1`. |

The early Eye block is waived by `antispeedrun.bypass.anticheese`, an `/asr bypass`
grant, or `/asr unlock end`.

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

Rows for `trim-progression` differ between presets but have no effect yet. Of the
`boss-scaling` rows, only the keys listed as live in [4.8](#48-boss-scaling) take
effect. Because a preset overwrites `config.yml`, apply one before you customize,
not after.

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
