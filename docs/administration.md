# Administration guide

This guide is for people who install and run AntiSpeedrun. It covers installation,
first start, the administrative commands and permission nodes, what the plugin stores
and where, and how to diagnose the failures it reports. The configuration keys
themselves are in [configuration.md](configuration.md).

Everything here describes what the plugin does today. There is no public release yet;
the README lists what each build implements.

## 1. Requirements

| Requirement | Value | Source |
| :--- | :--- | :--- |
| Java | 21 | `build.gradle.kts` toolchain and `options.release` |
| Server | Paper or Folia 1.21 | `api-version: '1.21'` in `plugin.yml`; built against Paper API 1.21.4 |
| Folia | Supported | `folia-supported: true` in `plugin.yml` |
| Floodgate | Optional | `softdepend`; only used to pick the plain-ASCII `/progress` card for Bedrock players |

No other plugin is required. You need a permissions plugin (or `permissions.yml`) only
to grant the bypass nodes in section 4.

## 2. Installation

There is no release artifact yet. Two sources exist today.

1. **CI snapshot.** Every CI run on GitHub uploads an artifact named
   `AntiSpeedrun-SNAPSHOT` containing `AntiSpeedrun-<version>.jar`, kept for seven
   days. The version is derived from the nearest release tag and the commit, for
   example `0.0.0-SNAPSHOT-g1a2b3c4` (see `RELEASE_PROCESS.md`). A snapshot is not a
   release.
2. **Build it yourself.** With Java 21:

   ```bash
   ./gradlew build
   ```

   The jar is written to `build/libs/`.

Then:

1. Stop the server.
2. Copy the jar into the server's `plugins/` directory.
3. Start the server. On the first start the plugin writes its default `config.yml`
   to `plugins/AntiSpeedrun/`. The shipped default uses the `SMP_STANDARD` profile.
4. Review `config.yml` (see [configuration.md](configuration.md)), then run
   `/asr reload` or restart.

To upgrade, replace the jar and restart. If your `config.yml` is from an older shape (or
has no `config-version`, as in v0.1.x), the plugin saves a copy to
`plugins/AntiSpeedrun/backups/`, adds the keys you are missing with their shipped
defaults and logs what it added. It does not remove your comments or change values you
set, with one exception on the first upgrade from v0.1.x:
`anti-cheese.block-bed-anchor-boss-damage`, `block-exit-portal-crystal-place` and
`block-gateway-pre-dragon` are set from `true` to `false`, each logged at `WARNING`.
v0.1.x shipped them `true` but never enforced them, so `false` keeps the server doing
what it did. Set any of them back to `true` and run `/asr reload` to turn the rule on. A
`config.yml` written by a newer version stops the plugin from starting, with the file
untouched. See [configuration.md](configuration.md#32-upgrading-an-older-configyml). The
state files are versioned the same way ([section 6.3](#63-state-file-versions)); player
data is not, so keep a backup of `plugins/AntiSpeedrun/` and `playerdata` before a jump
between builds.

A clean install is also fine: back up `plugins/AntiSpeedrun/` and `playerdata`, delete
`config.yml`, install the jar and restart. Deleting `config.yml` loses every local edit
and any preset applied with `/asr profile apply`; the plugin writes a fresh default
`config.yml` (the `SMP_STANDARD` profile), and the state files carry over. Every
anti-cheese rule added since v0.1.x ships off, so enable the ones you want in
`config.yml`.

## 3. Verifying a successful start

The server log should contain these lines and no `SEVERE` line from AntiSpeedrun:

```text
Item gates compiled: <n> materials across <m> tiers.
AntiSpeedrun enabled successfully.
```

Then check:

- `/plugins` lists AntiSpeedrun as enabled.
- `/asr` with no arguments lists the subcommands you may use.
- `/asr inspect <player>` for an online player prints their playtime, tenure, bypass
  state and one line per enabled dimension gate.
- Any `config.yml: ...` lines at `WARNING` level are recoverable problems the plugin
  worked around; read them (section 7.3).

If the plugin is missing or disabled, read section 7.1.

## 4. Commands and permissions

`/asr` is an alias of `/antispeedrun`. The player argument of `bypass` and `inspect`
must name an online player; `credit` also takes an offline player (section 5.6).

| Command | Permission | Default |
| :--- | :--- | :--- |
| `/progress` (or `/asr progress`) | `antispeedrun.progress` | everyone |
| `/journeybook` (alias `/rulesbook`, or `/asr book`) | `antispeedrun.book` | everyone |
| `/asr reload` | `antispeedrun.admin.reload` | op |
| `/asr profile apply <CASUAL\|SMP_STANDARD\|HARDCORE>` | `antispeedrun.admin.profile` | op |
| `/asr unlock <nether\|end> [lock]` | `antispeedrun.admin.unlock` | op |
| `/asr bypass <player> [duration\|off]` | `antispeedrun.admin.bypass` | op |
| `/asr inspect <player>` | `antispeedrun.admin.inspect` | op |
| `/asr credit <grant\|revoke> <player> <credit\|smelt-iron\|all>` | `antispeedrun.admin.credit` | op |

`/asr progress` and `/asr book` check the same node as their standalone command, so a
player who can run one spelling can run the other. A refused subcommand names the
missing node. `/progress` and `/journeybook` need a player: from the console they
refuse, and `/progress` points at `/asr inspect <player>`.

### 4.1 Permission nodes

| Node | Default | Effect |
| :--- | :--- | :--- |
| `antispeedrun.progress` | true | `/progress` and `/asr progress`. |
| `antispeedrun.book` | true | `/journeybook`, `/rulesbook` and `/asr book`. |
| `antispeedrun.admin` | op | Parent of the six `antispeedrun.admin.*` nodes. |
| `antispeedrun.admin.reload` | op | `/asr reload`. |
| `antispeedrun.admin.profile` | op | `/asr profile apply`. |
| `antispeedrun.admin.unlock` | op | `/asr unlock`. |
| `antispeedrun.admin.bypass` | op | `/asr bypass` (the right to hand out a bypass). |
| `antispeedrun.admin.inspect` | op | `/asr inspect`. |
| `antispeedrun.admin.credit` | op | `/asr credit`. |
| `antispeedrun.bypass` | false | Parent of the three bypass nodes below. |
| `antispeedrun.bypass.gates` | false | Exempt from dimension gates (foot, vehicle and stasis access) and the Dragon's Breath bottling gate. |
| `antispeedrun.bypass.items` | false | Exempt from item pickup, dispenser, container and Allay restrictions, and Dragon's Breath bottling. |
| `antispeedrun.bypass.anticheese` | false | Exempt from the early Eye of Ender throw block, and from the single-hit boss damage cap on hits the player causes. |

### 4.2 Why operator status does not grant a bypass

`antispeedrun.admin` defaults to `op`, but `antispeedrun.bypass` is deliberately not
one of its children and defaults to `false`. The right to administer the plugin,
including handing a bypass to someone else, is a different thing from being exempt
from the gates. Operators and staff playing survival are gated like everyone else, and
an operator testing the gates sees what a player sees.

To exempt someone, use one of:

- a standing permission (`antispeedrun.bypass` or one of its three children), granted
  through your permissions plugin;
- a temporary grant from `/asr bypass` (section 5.4);
- for the Nether and End gates, opening the dimension for everyone with `/asr unlock`
  (section 5.3).

Any one is enough. A permissions setup that gives operators every node (`*`) also
grants the bypass nodes; if operators are unexpectedly exempt, check for that.

## 5. Administration

### 5.1 Reload

`/asr reload` re-reads `config.yml` and applies it, all or nothing. The file is read
and validated in full and the item gate table is built from it before anything is
published. If anything fails, the previous configuration stays live, the plugin stays
enabled, the sender is told the file was not applied, and the cause is logged at
`SEVERE`. A reload that succeeds with recoverable problems replies "reloaded with N
warning(s)" and logs each one.

After a successful reload, online players are re-evaluated against the new
configuration.

Reload reads `config.yml` only. It does not load a new jar and does not touch
`state.yml` or player data.

A reload can be rejected for a reason that would also stop the next start (section
7.1). The log then warns that the running configuration would not survive a restart.
Fix the file before restarting.

### 5.2 Profiles

Three presets ship inside the jar: `CASUAL`, `SMP_STANDARD` and `HARDCORE`. They
differ in tuning, not structure; see [configuration.md](configuration.md#5-profiles).

```text
/asr profile apply HARDCORE
```

This does the following, in order:

1. Copies the current `config.yml` to
   `plugins/AntiSpeedrun/backups/config-<yyyyMMdd-HHmmss>.yml`, adding a `-1`, `-2`
   suffix on a name collision. If there is no `config.yml`, there is nothing to back
   up and the reply says so.
2. Replaces `config.yml` with the preset. The replacement is staged in a temporary
   file and moved into place, so a failure part-way does not leave a half-written
   file.
3. Reloads the configuration as `/asr reload` does.

Applying a profile overwrites the whole file. Every hand edit and comment in
`config.yml` is replaced by the preset; recover them from the backup. `CUSTOM` is what
the `profile` key reads as when you set it by hand; it is not a preset and cannot be
applied. The profile name is case-insensitive and `-` is read as `_`.

If the preset is written but fails to load, the previous configuration stays live and
the reply says so. The backup is untouched.

### 5.3 Dimension unlock

```text
/asr unlock nether
/asr unlock end
/asr unlock end lock
```

`unlock` opens a dimension for every player, whatever `dimension-gates` requires. It
is durable: it is written to `state.yml` (section 6) and survives restarts and
reloads. `lock` removes the override so the configured gate applies again. Both say
so when nothing changed. Only a third word of `lock` is accepted; anything else is
refused and changes nothing.

An unlock is an override on top of the gate, not a change to the configuration. When
the End is unlocked it also waives the early Eye of Ender block, because an operator
who has opened the End has decided the stronghold is fair game. It does not affect
item gates, which depend only on advancements and playtime.

### 5.4 Temporary bypass

```text
/asr bypass <player>
/asr bypass <player> 2h
/asr bypass <player> 1d12h
/asr bypass <player> permanent
/asr bypass <player> off
```

With no duration the grant lasts 30 minutes. A grant exempts the player from
dimension gates, item gates and the early Eye of Ender block until it expires.
Durations are one or more `<number><unit>` terms with units `s`, `m`, `h` and `d`;
`permanent` or `forever` never expires; `off`, `revoke`, `none` or `0` revokes. The
longest finite grant is 365 days. An unreadable duration is refused with examples and
changes nothing.

The grant is stored on the player (section 6), so it survives restarts and logouts.
It is separate from the `antispeedrun.bypass` permission: `/asr inspect` shows
`granted` for the first and `by permission` for the second, and revoking the grant
does not remove the permission.

### 5.5 Inspect

`/asr inspect <player>` prints the player's playtime in hours, tenure in days (or "not
recorded" when the server has no first-join time for them), bypass state, and for each
enabled dimension gate whether it is unlocked, open server-wide, or what is still
missing (advancements, playtime, tenure). It reads the live configuration.

### 5.6 Personal credits

```text
/asr credit grant <player> mine-diamond
/asr credit revoke <player> smelt-iron
/asr credit grant <player> all
```

Grants or revokes the personal-action credits that stand in for the possession-triggered
gate advancements (`docs/provenance-model.md`, Amendment). The credits are `mine-stone`,
`mined-iron`, `smelted-iron`, `iron-tools`, `upgrade-tools`, `mine-diamond` and
`obtain-blaze-rod`; underscores work in place of hyphens. `smelt-iron` names both
sub-credits of `story/smelt_iron`, and `all` names every credit. An unknown credit,
action or extra word is refused and changes nothing.

The player is an online player's name, a UUID, or the name of a player who has joined
this server before. A name the server has never seen is refused; give the UUID instead.

A grant counts whatever `item-progression.count-structure-loot` says. A revoke removes
the credit however it was earned, loot included, and the player can earn it again by
playing. Either takes effect at once and is kept in `personal-credits.yml`, so it
survives a restart. The reply names the credits that changed, or says nothing changed.

## 6. Persisted state

The plugin keeps its state in three places and nowhere else: its own folder, player
persistent data, and the persistent data of the extra dragons it spawns.

| State | Where | Scope |
| :--- | :--- | :--- |
| Dimension unlocks | `plugins/AntiSpeedrun/state.yml`, keys `dimension-unlocks.nether` and `dimension-unlocks.the_end` (unlock time in epoch milliseconds) | Server-wide |
| Reinforced dragon fights | `plugins/AntiSpeedrun/dragon-fights.yml`, one key `reinforced-fights.<world-uid>` per End world whose reinforcement window has run (time in epoch milliseconds) | Per End world |
| Sealed resummon exits | `plugins/AntiSpeedrun/portal-locks.yml`, one key `locked-exits.<world-uid>` per End world whose exit basin was sealed | Per End world |
| Extra dragon tag | Entity persistent data on each extra dragon, key `antispeedrun:n6_asr_secondary_dragon` | Per dragon |
| Bypass grants | Player persistent data, key `antispeedrun:bypass-expires-at` | Per player |
| Journey book delivered flag | Player persistent data, key `antispeedrun:journey-book-delivered` | Per player |
| Announced milestones | Player persistent data, key `antispeedrun:announced-milestones` | Per player |

Player persistent data lives in the player's `.dat` file in the server's `playerdata`
folder, not in `plugins/AntiSpeedrun/`. Advancements and playtime are the server's
own and are only read by the plugin.

### 6.1 Backup and restore boundaries

- Back up `plugins/AntiSpeedrun/` (`config.yml`, `state.yml`, `backups/`) and the
  world's `playerdata` folder together. `state.yml` alone does not carry bypass
  grants, and `playerdata` alone does not carry dimension unlocks.
- Restoring an older `playerdata` restores older bypass grants, the journey book flag
  (a player may be handed the book again on their next join when
  `journey-book.give-on-first-join` is on) and announced milestones.
- Restoring an older `state.yml` re-locks any dimension unlocked since.
- `state.yml` is written atomically (staged as `state.yml.tmp`, then moved). It is
  safe to read while the server runs. Edit it only while the server is stopped: the
  plugin holds unlocks in memory and rewrites the file on the next change.
- Backups from `/asr profile apply` are in `plugins/AntiSpeedrun/backups/`. The plugin
  never deletes them.
- `dragon-fights.yml` is written the same way. Deleting it while a first dragon fight
  is in progress is safe as long as the extra dragons are still alive: finding one
  when its chunk loads marks the fight as reinforced again. With the file gone and
  every extra dragon already dead, the next entry into the End opens a new window.
  If it cannot be read, the plugin logs `SEVERE`, starts with nothing recorded and
  moves the damaged file aside as `dragon-fights.yml.corrupt-<yyyyMMdd-HHmmss>`
  before its next write, as for `state.yml` below.
- `portal-locks.yml` is written before a resummon exit is sealed. If the server or
  plugin stops mid-fight, the next enable restores the active portal on its owning
  region, then removes the marker. Keep this file with the world during backup and
  restore; deleting it while the basin is sealed prevents automatic recovery.
- While an extra dragon lives, the original dragon cannot die, including by `/kill`.
  Killing an extra dragon with `/kill` counts as a death. An extra dragon that was
  never saved to disk (lost in a crash before a chunk save) is not waited for: only
  extra dragons spawned or seen in a loaded chunk since the server started hold the
  original.

### 6.2 Damaged `state.yml`

If `state.yml` cannot be read, the plugin starts with no unlocks recorded and logs
`SEVERE`; any unlock granted before is not in effect. The damaged file is left alone
at that point. The next time an unlock or lock has to be saved, the file is moved
aside as `state.yml.corrupt-<yyyyMMdd-HHmmss>` and a new one is written, so you can
read the old one to see what had been unlocked. If the move fails, the change holds
for that server run only and the log says to move or delete the file by hand.

To recover, fix or restore the file while the server is stopped and start again.

### 6.3 State file versions

`state.yml`, `dragon-fights.yml`, `portal-locks.yml`, `explored-structures.yml` and
`personal-credits.yml` each start with `state-version: 1`, written by the plugin. Do not
edit or remove it.

- A file with no `state-version` was written by 0.2.0 or earlier, whose format is
  version 1. On start the plugin adds the key, logs `Marked <file> as state-version 1`,
  and changes nothing else.
- A file whose `state-version` is higher than this build reads, or is not a whole
  number of at least 1, stops startup with a `SEVERE` line naming the file. The file is
  not changed, moved or renamed. Install the version that wrote it, or restore a copy
  this build can read, and start again.
- Player persistent data keys are not versioned: each holds one typed value, and a
  future format change uses a new key rather than reinterpreting an old one.

## 7. Troubleshooting

### 7.1 The plugin does not enable

The log names the cause. These refusals stop startup on purpose, because the
alternative is to run on built-in defaults that gate no items.

| Log message contains | Cause | Fix |
| :--- | :--- | :--- |
| `will not start while config.yml names an advancement this server cannot resolve` | An enabled gate or item tier has an advancement key that is not a valid `namespace:path` key (upper case, a space inside, a stray character). The key is named in the error above. | Correct the key (lower case; `a-z 0-9 / . _ -`), or disable the gate, then restart. The same message is logged for the other unenforceable-gate errors listed in [configuration.md](configuration.md#3-validation-fallback-and-refusal): an all-blank or single-value `require-advancements`, and `gate-mending-trade: true` with a blank `required-advancement`. |
| `will not start while item-progression.gated-items contains an unresolvable tier collision` | Two tiers claim the same material and neither requires a superset of the other's requirements. | Change `match-patterns` or `items` so one tier dominates, or add the material to `exclude-materials`. The tiers are named in the log. |
| `config.yml parsed, but the item gate table could not be built from it` | The file loaded but the gate table could not be built. | Fix what the log above it names. |

If the log says `plugin.yml declares no "..." command`, the jar is damaged or was built wrongly. The plugin still enables, but that one command (`/asr`, `/progress` or `/journeybook`) is unavailable; reinstall the jar.

A file that cannot be parsed at all (invalid YAML) does not stop startup. The plugin
logs `config.yml was rejected` and runs on the built-in defaults, with a warning that no
item tier is declared. That leaves item gating off, so treat it as a failure to fix.
Dimension gates and the Eye of Ender block do run on the defaults.

If the plugin fails before any AntiSpeedrun log line, check the server's own error:
Java older than 21 or a server older than 1.21 are the likely causes. Folia refuses
plugins that do not declare `folia-supported: true`; this one does, so a refusal
suggests a modified `plugin.yml`.

### 7.2 A reload is rejected

The sender is told the file was not applied and the log has a `SEVERE` line naming the
cause. The previous configuration is still live. Fix the file and reload again.

### 7.3 Configuration warnings

Lines prefixed `config.yml:` are recoverable: an unknown key was ignored, a value of
the wrong type or out of range fell back to its default, or a message was not valid
MiniMessage. Nothing was refused, but the running value is not the one you wrote. The
rules are in [configuration.md](configuration.md#3-validation-fallback-and-refusal).

A warning that `gated-items declares no tiers while enabled is true, so NO item is
gated` means item gating is effectively off. It is also what the built-in defaults
produce, so check whether `config.yml` failed to load.

### 7.4 A player is refused at a gate

The player is shown the gate's `rejection-message`, which by default points at
`/progress`. To see what is missing, run `/asr inspect <player>` (it names the missing
advancements, hours and days) or have the player run `/progress`.

Reasons a player is refused when you expect them to pass:

- **Not earned yet.** An advancement is missing, or playtime or tenure is short.
  Playtime and advancements are the server's own statistics.
- **Operators are not exempt.** See section 4.2.
- **The grant or unlock is gone.** A grant expired, or `state.yml` or `playerdata`
  was damaged or restored (sections 6.1 and 6.2). Check with `/asr inspect`.
- **An old configuration is live.** A rejected reload keeps the previous
  configuration; check the log.

Reasons a player passes when you expect a refusal:

- **A bypass applies.** A standing `antispeedrun.bypass*` permission, an unexpired
  `/asr bypass` grant, or an `/asr unlock` of that dimension (section 4.2).
- **Tenure not recorded.** With `require-account-age-days` above 0, a player the
  server has no first-join time for is waived rather than blocked, and `/asr inspect`
  shows "not recorded". Tenure is measured from the player's first join on this
  server.
- **Unknown advancement.** A well-formed key that names no advancement on this server
  (a typo in the path, or a datapack that is not loaded) passes validation. At
  runtime the plugin waives that requirement rather than enforce something nobody
  can earn, and logs the key once. Check the log for a warning naming it.

Teleports the plugin does not regulate, such as an operator's `/tp`, are honoured on
both Paper and Folia: the player is not sent back. On Folia a teleport from another
plugin raises no event and is judged when the player arrives. Any other arrival in a
gated dimension without qualifying, a waiver, or a recorded exempt teleport is sent
back.

### 7.5 An item cannot be picked up

Item progression refuses pickup, equipping, dispenser-fired armor, and taking gated
items out of containers and bundles, off armor stands, out of item frames, or to and
from an Allay until the player has earned the tier. Bottling Dragon's Breath is refused
until the player meets the End gate's requirement, while `dimension-gates.the-end` is
enabled; the End waivers and `antispeedrun.bypass.items` both exempt it. The message
is `item-progression.rejection-message` with `{ITEM}` and `{REQUIREMENT}` filled in,
throttled to one per `feedback-cooldown-seconds`. A player can always re-collect an
item they dropped (`drop-recall-enabled`). Exempt with `antispeedrun.bypass.items` or
`/asr bypass`. The tiers and their requirements are in `config.yml`; `/journeybook`
prints them for players.

### 7.6 A profile did not apply

The reply says whether the preset could not be found in the jar, could not be written,
or was written but not loaded. In each case the previous `config.yml` is untouched or
backed up in `plugins/AntiSpeedrun/backups/`. `CUSTOM` cannot be applied.

### 7.7 A bypass or unlock did not stick

- `/asr bypass` needs the target online. If they leave before it applies, the reply
  says so.
- The log says an unlock "will NOT survive a restart" when `state.yml` could not be
  written (permissions, full disk, read-only folder). Fix the cause, then run
  `/asr unlock <dimension> lock` followed by `/asr unlock <dimension>` to force a new
  write; unlocking an already unlocked dimension does not rewrite the file.

## 8. A structure chest had no trim template

With `trim-progression.enabled` and `gate-natural-trim-chests` both `true`, a Silence,
Ward, Snout or Spire trim template or a netherite upgrade template is removed from
naturally generated loot when the player it is generated for has not explored its
structure (the table in [configuration.md](configuration.md#44-trim-progression)). Loot
is generated once, when the chest or chest minecart is first opened or broken, so the
template is gone for everyone, not held back until the player qualifies.

- **Who is judged.** The player opening or breaking the container. Loot generated with
  no player, such as a hopper or hopper minecart pulling from an unopened chest, never
  receives a gated template.
- **Bypass.** `antispeedrun.bypass.items` and `/asr bypass` waive the lock for the
  player opening the chest.
- **Folia.** A player standing in another region from the chest is judged from
  `explored-structures.yml` instead of live, and bypasses are not read for them.
- **Ancient City.** Only `explored-structures.yml` proves it, whoever is judged: the entry
  `trim:ancient_city` (entering a city) or, while `count-structure-loot` is on,
  `trim:ancient_city/loot` (opening a city chest first). Nothing in the game removes either.
  To relock a player, stop the server and delete the entry from their list. Entries written
  by earlier versions came from Sneak 100 and cannot be told apart from a real visit.
- **Plugin loot.** Loot a plugin generates through the loot table API is not natural
  loot and is left alone.
