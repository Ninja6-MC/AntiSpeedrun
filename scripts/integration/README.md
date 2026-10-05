# Real-server integration harness

Gameplay probes that run the built AntiSpeedrun jar on a real Paper server, with a scripted
player doing what a player does, and assert on what the server reports: boss health before
and after a hit, whether an entity exists, what is in the player's inventory, and the damage
type and final damage each hit carried. A server that merely boots proves none of that; the
`Server Boot` CI legs (`scripts/boot-smoke.py`) cover startup only.

It is not wired into CI yet. Run it by hand before claiming a rule works on a server version.

## What it covers

`anti_cheese.py` covers the three section 7 boss rules (#197), each switched on alone through
`config.yml` and `/asr reload`:

| Rule | Probes |
| :--- | :--- |
| `block-exit-portal-crystal-place` | Kills the End's first dragon, places a crystal on the exit portal's centre pillar with the rule off (placed) and on (refused, crystal kept), then on each of the four ritual positions with the rule on, and waits for the ritual to resummon the dragon. |
| `cap-single-hit-boss-damage` | With the cap off and on: a Sharpness 255 sword hit on a Wither and on the dragon's head and body parts, an 8-TNT-minecart stack, and a Mace smash from about 25 blocks. With the cap on, `/kill` on both bosses. |
| `block-bed-anchor-boss-damage` | With the rule off and on: a bed (in the Nether) and a charged Respawn Anchor (in the Overworld) detonated beside a Wither and a dragon, checking the reported damage type is `BAD_RESPAWN_POINT`. With the rule on, a TNT control beside each boss. |

The probing player is never an operator and never holds a bypass node; the first check
asserts it. It is given every advancement, so the dimension and item gates stay out of the
way, and Resistance V, so explosions beside it do not end the run.

## Layout

| Path | What it is |
| :--- | :--- |
| `run.py` | Entry point: boots the server, joins the player, runs the probes, writes the results. |
| `harness.py` | Server and client lifecycle, console commands and log matching. |
| `anti_cheese.py` | The section 7 probes. |
| `client/` | The scripted player: a [mineflayer](https://github.com/PrismarineJS/mineflayer) client that takes one JSON request per line from `run.py`. |
| `src/probe/` (repository root) | `AntiSpeedrunProbe`, a test-only plugin that logs one `ASRPROBE` line per boss damage event and crystal click. It changes nothing. Never install it on a real server. |

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

`--client-version` must be a version mineflayer supports. For a newer server, pass the newest
one it has and add `--via`: the harness then fetches the pinned ViaVersion and ViaBackwards
releases from Hangar, checks their SHA-256 and installs them on the test server so the
older client can join. For Paper 26.2 that is `--client-version 26.1 --via`.

`--probe crystal`, `--probe damage_cap` or `--probe bed_anchor` (repeatable) runs a subset.

## Results

Everything lands in `--workdir`. Each run rebuilds `server/` from scratch and deletes any
earlier `results.json` before it starts; `cache/` (the pinned Via jars) is kept:

* `results.json`: the server build, the embedded AntiSpeedrun version, the client version,
  every check with its evidence, and notes;
* `server/server.log`: the full console log, `ASRPROBE` lines included;
* `client.log`: the scripted player's own output.

The exit status is 0 only when every check passed. Any exception, not only a probe failure,
is recorded as a failed `harness` check, so `results.json` is always written. A probe that
cannot reach the state it asserts on (a boss that never spawns, a hit that never lands) is a
failure, never a pass. The whole run is bounded by `--deadline` (25 minutes by default); when
it passes, the server and client are killed. Both are also killed on any exception.

A check that fails today because of an open bug passes `known_issue=<n>` and lists the issue in
`KNOWN_ISSUES` in `anti_cheese.py`. It is still run and recorded, with status `known-failure`,
but does not fail the run. Once the bug is fixed the check passes, is recorded as
`unexpected-pass` and fails the run until its `KNOWN_ISSUES` entry is removed. Today that is
#203: the whole TNT minecart stack with the cap on.

## Writing a probe

Build every piece of state from console commands in a place the probe owns, so the result
does not depend on terrain or an earlier probe. Run the action through the client, and
assert on what the server reports, not on a log line saying a handler ran. Each new check
gets a control where one is possible: the same action with the rule off must have the effect
the rule refuses, or a refusal could just be the probe failing to act.

Two server behaviours already cost a cycle:

* a `NoAI` Ender Dragon never positions its parts, which stay at the world origin where no hit
  reaches them; summon it with `DragonPhase:10` (hovering) instead;
* Paper colours command feedback on the console even when it writes to a file; `harness.py`
  strips the codes before matching.
