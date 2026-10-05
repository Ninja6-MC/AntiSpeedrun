# Real-server integration harness

Gameplay probes that run the built AntiSpeedrun jar on a real Paper or Folia server, with a
scripted player doing what a player does, and assert on what the server reports: which world
the player is in, what is in their inventory and in a container, whether an item is still on
the ground, boss health before and after a hit, and the damage type and final damage each hit
carried. A server that merely boots proves none of that; the `Server Boot` CI legs
(`scripts/boot-smoke.py`) cover startup only.

It is not wired into CI yet. Run it by hand before claiming a rule works on a server version.

## What it covers

`gates.py` covers the dimension and item gates (#58). Each case sets the player's progression
itself (every advancement revoked, then only what the case needs granted, then `/asr reload`
so no cached evaluation survives), and each refusal has a control that must succeed:

| Probe | What it does |
| :--- | :--- |
| `portals` | An ineligible player walks into a Nether portal (stays in the Overworld and is told why); an eligible one reaches the Nether. |
| `vehicles` | The same, riding a boat steered into the portal. |
| `operator` | An operator holds no bypass node, is refused at the portal and cannot pick up a diamond. |
| `bypass` | `/asr bypass` lets an ineligible player through the portal and pick up a diamond; revoked, and once a 5-second grant expires, both are refused again. |
| `teleports` | The deliberate-teleport contract (#135): the console's cross-dimension `/tp` and an operator's own `/execute in ... run tp @s` are honoured on both platforms. Another plugin's `teleportAsync` is honoured on Paper; on Folia it fires no event and is judged on arrival, so the player is returned to the Overworld spawn and told why (`docs/administration.md` section 7.4). |
| `pickups` | An ineligible player walks over a diamond, silk-touched deepslate diamond ore (C-11) and a diamond sword carrying the retired natural-origin tag (C-01): each is refused and stays on the ground. Control: an eligible player picks up the diamond. |
| `containers` | An ineligible player cannot shift-click or pick diamonds out of a chest; an eligible one can. |
| `container_break` | An ineligible player breaks a chest and a Decorated Pot holding diamonds rather than opening them (C-02): the spilled diamonds are refused. |
| `merchant` | An ineligible player cannot buy a diamond chestplate from an Armorer (C-09); an eligible one can. |
| `recall` | No false positives: an ineligible player re-collects the diamond they dropped and their own death pile, and an eligible player who mines diamond ore keeps the diamond. |
| `reload_active` | With the player online, a reload that switches the Nether gate or item progression off lets them through at once, and back on refuses them again; then ten reloads in quick succession while they stand on a gated stack, every pickup attempt refused. |

Item gating is material-only (#52, `docs/provenance-model.md`). The audit fixtures #58 first
listed for the retired provenance model are kept as what they still guard against: a stack
handed over by someone else, or carrying the old natural-origin tag, is judged by its material
alone; breaking a container rather than opening it gives its contents no owner; only the
player's own drop or death pile comes back to them. Nothing reintroduces a provenance tag.

`anti_cheese.py` covers the three section 7 boss rules (#197), each switched on alone through
`config.yml` and `/asr reload`:

| Rule | Probes |
| :--- | :--- |
| `block-exit-portal-crystal-place` | Kills the End's first dragon, places a crystal on the exit portal's centre pillar with the rule off (placed) and on (refused, crystal kept), then on each of the four ritual positions with the rule on, and waits for the ritual to resummon the dragon. |
| `cap-single-hit-boss-damage` | With the cap off and on: a Sharpness 255 sword hit on a Wither and on the dragon's head and body parts, an 8-TNT-minecart stack, and a Mace smash from about 25 blocks. With the cap on, `/kill` on both bosses. On Folia the TNT stack also shows whether the per-tick budget (#203) holds when the stack lands in one region tick. |
| `block-bed-anchor-boss-damage` | With the rule off and on: a bed (in the Nether) and a charged Respawn Anchor (in the Overworld) detonated beside a Wither and a dragon, checking the reported damage type is `BAD_RESPAWN_POINT`. With the rule on, a TNT control beside each boss. |

The gate probes run first. The probing player is never an operator except where `operator`
and `teleports` make it one for a case, and never holds a bypass node; a check asserts it. For
the anti-cheese probes it is given every advancement, so the dimension and item gates stay out
of the way. It always has Resistance V, so explosions beside it do not end the run.

## Layout

| Path | What it is |
| :--- | :--- |
| `run.py` | Entry point: boots the server, joins the player, runs the probes, writes the results. |
| `harness.py` | Server and client lifecycle, console commands, log matching and platform detection. |
| `probes.py` | What every probe module shares: results, `KNOWN_ISSUES`, and the console helpers. |
| `gates.py` | The dimension and item gate probes. |
| `anti_cheese.py` | The section 7 probes. |
| `client/` | The scripted player: a [mineflayer](https://github.com/PrismarineJS/mineflayer) client that takes one JSON request per line from `run.py`. |
| `src/probe/` (repository root) | `AntiSpeedrunProbe`, a test-only plugin that logs one `ASRPROBE` line per boss damage event, crystal click, pickup attempt, container click, trade selection, portal event and world change, and answers the harness's console queries (`/asrprobe`). Apart from the one plugin teleport `teleports` asks of it, it changes nothing. Never install it on a real server. |

## Running it

Needs Java 25 for 26.x servers (21 for 1.21.x), Python 3 and Node.js 20 or newer.

```bash
./gradlew build probeJar
npm ci --prefix scripts/integration/client
python3 scripts/fetch-server.py --project paper --version 1.21.11 --build <build> --sha256 <sha256> --out paper.jar
python3 scripts/integration/run.py --server-jar paper.jar \
    --plugin-jar build/libs/AntiSpeedrun-<version>.jar \
    --probe-jar build/libs/AntiSpeedrunProbe.jar \
    --client-version 1.21.11 --workdir run-probes/1.21.11 --label "Paper 1.21.11"
```

The same command runs a Folia jar (`--project folia`); the harness reads the platform from the
server's own startup line and records it in `results.json`.

`--client-version` must be a version mineflayer supports. For a newer server, pass the newest
one it has and add `--via`: the harness then fetches the pinned ViaVersion and ViaBackwards
releases from Hangar, checks their SHA-256 and installs them on the test server so the
older client can join. For Paper and Folia 26.2 that is `--client-version 26.1 --via`.

`--probe <name>` (repeatable) runs a subset; the names are the first column of the two tables
above, and `crystal`, `damage_cap` and `bed_anchor` for the anti-cheese rules.

## Results

Everything lands in `--workdir`. Each run rebuilds `server/` from scratch and deletes any
earlier `results.json` before it starts; `cache/` (the pinned Via jars) is kept:

* `results.json`: the platform, server build, the embedded AntiSpeedrun version, the client
  version, every check with its evidence, and notes;
* `server/server.log`: the full console log, `ASRPROBE` lines included;
* `client.log`: the scripted player's own output, including what it was told.

The exit status is 0 only when every check passed. Any exception, not only a probe failure,
is recorded as a failed `harness` check, so `results.json` is always written. A probe that
cannot reach the state it asserts on (a boss that never spawns, a hit that never lands) is a
failure, never a pass. The whole run is bounded by `--deadline` (25 minutes by default); when
it passes, the server and client are killed. Both are also killed on any exception.

A check that fails today because of an open bug passes `known_issue=<n>` and lists the issue in
`KNOWN_ISSUES` in `probes.py`. It is still run and recorded, with status `known-failure`, but
does not fail the run. Once the bug is fixed the check passes, is recorded as
`unexpected-pass` and fails the run until its `KNOWN_ISSUES` entry is removed. No check is
marked so today.

## Writing a probe

Build every piece of state from console commands in a place the probe owns, so the result
does not depend on terrain or an earlier probe. Run the action through the client, and
assert on what the server reports, not on a log line saying a handler ran. Each new check
gets a control where one is possible: the same action with the rule off, or by a player who
qualifies, must have the effect the rule refuses, or a refusal could just be the probe failing
to act.

Server behaviours that already cost a cycle:

* a `NoAI` Ender Dragon never positions its parts, which stay at the world origin where no hit
  reaches them; summon it with `DragonPhase:10` (hovering) instead;
* Paper colours command feedback on the console even when it writes to a file; `harness.py`
  strips the codes before matching;
* Folia has no `/data` command; read health and whereabouts with `Probes.health` and
  `Probes.where`, which ask the probe plugin;
* Folia refuses a positional entity selector (`x=`, `distance=`) from the console, which runs on
  no region, and logs an `IllegalStateException` instead of an answer; count or remove entities
  near a point with `Probes.near`, and keep `kill` and `execute if entity` selectors free of a
  position;
* Folia raises no `PlayerChangedWorldEvent`, so its `world` lines are missing there; assert on
  `Probes.where` instead;
* `/advancement revoke` fires no event, so the plugin's progression cache only forgets on a
  reload; `GateProbes.progression` reloads after every change.
