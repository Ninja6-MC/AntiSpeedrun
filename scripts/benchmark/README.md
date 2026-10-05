# Multi-dragon benchmark on Folia

`max_dragons.py` measures what a multi-dragon fight costs a live Folia server, and checks
whether vanilla's dragon fight can adopt a secondary dragon. It answers #165 and is kept so
the result can be reproduced on another machine or a newer Folia build. It is not wired
into CI.

## What it does

`bench` boots one fresh server per dragon count (default 1 to 5). Five scripted players
(`client/bots.js`, mineflayer) enter the End and stand on the main island at 20 blocks from
the centre, with Resistance V so the fight cannot kill them. The reinforcement window counts
them with `multiplier: 1.0` and `max-dragons` set to the count, so the plugin spawns the
secondaries itself, exactly as on a real server. The player count is the same for every
dragon count; only the dragons change. Each player shoots the nearest dragon with a bow,
or hits it when it is within reach. A player knocked off the island is put back.

After a 30 second warm-up, Folia's `/tps` is read every 15 seconds for 120 seconds. Each
read is Folia's 15-second report per region (`TickRegionScheduler` 15 s tick report), so
the eight samples cover the window without overlap. The End region with the highest MSPT
is recorded. A count **holds** when every sample's MSPT is under `--threshold` (50 ms, the
budget of a 20 TPS tick).

`adoption` sets up a three-dragon fight for two players, kills one secondary while the
primary is tracked (the control), damages the other, then parks the primary in an unloaded
chunk for 95 seconds, past vanilla's 1,200-tick search. The vanilla boss bar shows which
dragon the fight is tracking. It then kills that secondary and looks for the exit portal
and the egg a victory places. With the guard in place it instead brings the old primary
back, checks that it loads as a secondary and holds the promoted dragon alive, and only then
lets the fight end.

## Running it

Needs Java 25 for Folia 26.x, Python 3 and Node.js 20 or newer. The bots speak 26.1, the
newest protocol mineflayer 4.39 has, so the server gets ViaVersion and ViaBackwards: put
the two jars in one directory and pass it as `--via-dir`. The integration harness pins the
versions it uses; any release that supports the server version works.

```bash
./gradlew build
npm ci --prefix scripts/benchmark/client
python3 scripts/fetch-server.py --project folia --version latest --out folia.jar
python3 scripts/benchmark/max_dragons.py bench --server-jar folia.jar \
    --plugin-jar build/libs/AntiSpeedrun-<version>.jar --via-dir via --workdir run-bench
python3 scripts/benchmark/max_dragons.py adoption --server-jar folia.jar \
    --plugin-jar build/libs/AntiSpeedrun-<version>.jar --via-dir via --workdir run-adoption
```

Everything lands in `--workdir`: one server directory per run with its `server.log`, the
bots' log, and `results.json` with the hardware, every sample and the summary. `bench`
prints a Markdown table at the end.

## Folia notes

- Folia never fires `PlayerChangedWorldEvent`, so a player entering the End does not open
  the reinforcement window there (#205). The script works around it: the party logs out
  and back in once it stands in the End, and the login opens the window.
- Folia has no `/tag` command, and a console selector with a position (`x=`, `distance=`)
  fails its thread check. The script tells dragons apart by the plugin's persistent-data
  tag through `nbt=` selectors instead, and reads blocks through the first bot, because
  `execute if block` from the console fails the same check.
- A dragon that ticks in a region of its own, far from the island, fails Folia's thread
  check when its AI looks for players at the portal (`Entity threw exception`). The adoption
  probe parks the primary 320 blocks out so that its chunk rejoins the island's region when
  it loads.
