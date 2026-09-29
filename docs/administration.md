# Administration guide

This guide is for people who install and run AntiSpeedrun. It covers installation,
first start, the administrative commands and permission nodes, what the plugin stores
and where, and how to diagnose the failures it reports. The configuration keys
themselves are in [configuration.md](configuration.md).

Everything here describes what the plugin does today. There is no public release yet;
the README lists the implemented and planned modules.

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

To upgrade, replace the jar and restart. The plugin does not rewrite an existing
`config.yml` on start, so keys added by a newer version are absent from your file
until you add them; an absent key uses the shipped default.

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
must name an online player.

| Command | Permission | Default |
| :--- | :--- | :--- |
| `/progress` (or `/asr progress`) | `antispeedrun.progress` | everyone |
| `/journeybook` (alias `/rulesbook`, or `/asr book`) | `antispeedrun.book` | everyone |
| `/asr reload` | `antispeedrun.admin.reload` | op |
| `/asr profile apply <CASUAL\|SMP_STANDARD\|HARDCORE>` | `antispeedrun.admin.profile` | op |
| `/asr unlock <nether\|end> [lock]` | `antispeedrun.admin.unlock` | op |
| `/asr bypass <player> [duration\|off]` | `antispeedrun.admin.bypass` | op |
| `/asr inspect <player>` | `antispeedrun.admin.inspect` | op |

`/asr progress` and `/asr book` check the same node as their standalone command, so a
player who can run one spelling can run the other. A refused subcommand names the
missing node. `/progress` and `/journeybook` need a player: from the console they
refuse, and `/progress` points at `/asr inspect <player>`.

### 4.1 Permission nodes

| Node | Default | Effect |
| :--- | :--- | :--- |
| `antispeedrun.progress` | true | `/progress` and `/asr progress`. |
| `antispeedrun.book` | true | `/journeybook`, `/rulesbook` and `/asr book`. |
| `antispeedrun.admin` | op | Parent of the five `antispeedrun.admin.*` nodes. |
| `antispeedrun.admin.reload` | op | `/asr reload`. |
| `antispeedrun.admin.profile` | op | `/asr profile apply`. |
| `antispeedrun.admin.unlock` | op | `/asr unlock`. |
| `antispeedrun.admin.bypass` | op | `/asr bypass` (the right to hand out a bypass). |
| `antispeedrun.admin.inspect` | op | `/asr inspect`. |
| `antispeedrun.bypass` | false | Parent of the three bypass nodes below. |
| `antispeedrun.bypass.gates` | false | Exempt from dimension gates (foot, vehicle and stasis access). |
| `antispeedrun.bypass.items` | false | Exempt from item pickup, dispenser and container restrictions. |
| `antispeedrun.bypass.anticheese` | false | Exempt from the early Eye of Ender throw block. |

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

## 6. Persisted state

The plugin keeps its state in two places and nowhere else.

| State | Where | Scope |
| :--- | :--- | :--- |
| Dimension unlocks | `plugins/AntiSpeedrun/state.yml`, keys `dimension-unlocks.nether` and `dimension-unlocks.the_end` (unlock time in epoch milliseconds) | Server-wide |
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

### 6.2 Damaged `state.yml`

If `state.yml` cannot be read, the plugin starts with no unlocks recorded and logs
`SEVERE`; any unlock granted before is not in effect. The damaged file is left alone
at that point. The next time an unlock or lock has to be saved, the file is moved
aside as `state.yml.corrupt-<yyyyMMdd-HHmmss>` and a new one is written, so you can
read the old one to see what had been unlocked. If the move fails, the change holds
for that server run only and the log says to move or delete the file by hand.

To recover, fix or restore the file while the server is stopped and start again.

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
items out of containers and bundles, off armor stands or out of item frames until the
player has earned the tier. The message
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

## 8. Planned modules

`boss-scaling`, the remaining `anti-cheese` rules and `trim-progression` have no
runtime behavior yet (see the README). Their keys are validated and otherwise ignored,
so there is nothing to administer or troubleshoot for them.
