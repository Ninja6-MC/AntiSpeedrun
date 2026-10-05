#!/usr/bin/env python3
"""Benchmark multi-dragon fights on a live Folia server, and probe dragon-fight adoption.

  python scripts/benchmark/max_dragons.py bench --server-jar folia.jar \
      --plugin-jar build/libs/AntiSpeedrun-<version>.jar --via-dir <dir> --workdir run-bench \
      [--counts 1 2 3 4 5] [--window 120] [--threshold 50]

  python scripts/benchmark/max_dragons.py adoption --server-jar folia.jar \
      --plugin-jar build/libs/AntiSpeedrun-<version>.jar --via-dir <dir> --workdir run-adoption

bench boots one fresh server per dragon count. Five scripted players (client/bots.js) enter the
End, the reinforcement window counts them with multiplier 1.0 and max-dragons set to the count,
and the plugin spawns the secondaries itself. The players stay on the main island shooting and
hitting the nearest dragon while Folia's /tps is sampled every 15 seconds for --window seconds.
Each sample is Folia's 15-second report for every region; the End region holding the fight is
recorded.

adoption sets up a three-dragon fight for two players, then moves the untagged primary to an
unloaded chunk for longer than vanilla's 1,200-tick search and watches whether the fight takes a
tagged secondary as its dragon: through the vanilla boss bar, and by killing that secondary and
looking for the exit portal and the egg a victory places. A secondary killed while the primary is
still tracked is the control.

Both write <workdir>/results.json and keep every server.log. The client needs `npm ci` in
scripts/benchmark/client first. Test servers only: the players are offline-mode bots.
"""

import argparse
import json
import math
import os
import platform
import queue
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SHIPPED_CONFIG = os.path.join(REPO, "src", "main", "resources", "config.yml")

DONE = re.compile(r"Done \([0-9.,]+s\)!")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
SERVER_BUILD = re.compile(r"This server is running (?P<build>[^\r\n]+)")
WINDOW_CLOSED = re.compile(r"Reinforcement window closed in \S+ for the first fight: "
                           r"(?P<party>\d+) on the main island, (?P<dragons>\d+) dragon\(s\)\.")
REGION = re.compile(r"Region around block \[w:'(?P<world>[^']+)',(?P<x>-?\d+),(?P<y>-?\d+),(?P<z>-?\d+)\]:\s*\n"
                    r"\s*(?P<util>[0-9.]+)% util at (?P<mspt>[0-9.]+) MSPT at (?P<tps>[0-9.]+) TPS\s*\n"
                    r"\s*Chunks: (?P<chunks>\d+) Players: (?P<players>\d+) Entities: (?P<entities>\d+)")
LOWEST_TPS = re.compile(r"Lowest Region TPS: (?P<tps>[0-9.]+)")
COUNT = re.compile(r"Test passed\. Count: (?P<count>\d+)|Test failed")

SECONDARY_NBT = '{BukkitValues:{"antispeedrun:n6_asr_secondary_dragon":1b}}'
TAGGED = f"@e[type=ender_dragon,nbt={SECONDARY_NBT}]"
UNTAGGED = f"@e[type=ender_dragon,nbt=!{SECONDARY_NBT}]"
IN_END = "execute in minecraft:the_end"
REPORT_SECONDS = 15
# Long enough for the whole party to be teleported onto the island before the census counts it.
PREP_SECONDS = 20
# Where the primary is parked to unload it: beyond the party's view distance (8 chunks), but close
# enough that its chunk rejoins the island's Folia region when it loads again. A dragon ticking in a
# region of its own throws on Folia when its AI looks for players at the portal.
PARKING = "0 100 320"


class RunError(Exception):
    """The run could not reach the state it measures: a harness failure, not a result."""


class Server:
    """One disposable Folia server in a fresh directory, driven through its console."""

    def __init__(self, workdir, server_jar, plugins, config_overrides, java, memory, port):
        self.workdir = os.path.abspath(workdir)
        self.server_jar = server_jar
        self.plugins = plugins
        self.config_overrides = config_overrides
        self.java = java
        self.memory = memory
        self.port = port
        self.log_path = os.path.join(self.workdir, "server.log")
        self.process = None
        self._log = None

    def __enter__(self):
        if os.path.exists(self.workdir):
            shutil.rmtree(self.workdir)
        os.makedirs(os.path.join(self.workdir, "plugins", "AntiSpeedrun"))
        for plugin in self.plugins:
            shutil.copy(plugin, os.path.join(self.workdir, "plugins"))
        shutil.copy(self.server_jar, os.path.join(self.workdir, "server.jar"))
        write(os.path.join(self.workdir, "eula.txt"), "eula=true\n")
        write(os.path.join(self.workdir, "server.properties"), "".join(f"{k}={v}\n" for k, v in {
            "server-port": self.port, "online-mode": "false", "enforce-secure-profile": "false",
            "spawn-protection": 0, "allow-flight": "true", "view-distance": 8,
            "simulation-distance": 8, "max-players": 20}.items()))
        # The bots join from one address a second or two apart; the default throttle refuses that.
        write(os.path.join(self.workdir, "bukkit.yml"), "settings:\n  connection-throttle: -1\n")
        write(os.path.join(self.workdir, "plugins", "AntiSpeedrun", "config.yml"),
              configure(read(SHIPPED_CONFIG), self.config_overrides))
        self._log = open(self.log_path, "wb")
        self.process = subprocess.Popen([self.java, f"-Xms{self.memory}", f"-Xmx{self.memory}", "-jar",
                                         "server.jar", "--nogui"], cwd=self.workdir,
                                        stdin=subprocess.PIPE, stdout=self._log, stderr=subprocess.STDOUT)
        return self

    def __exit__(self, *exc):
        if self.process is not None and self.process.poll() is None:
            try:
                self.command("stop")
                self.process.wait(timeout=90)
            except (OSError, RunError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait()
        if self._log:
            self._log.close()

    def log(self):
        return ANSI.sub("", read(self.log_path))

    def mark(self):
        return len(self.log())

    def wait_for(self, pattern, timeout, since=0):
        compiled = re.compile(pattern) if isinstance(pattern, str) else pattern
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RunError(f"the server exited with status {self.process.returncode}")
            match = compiled.search(self.log()[since:])
            if match:
                return match
            time.sleep(0.25)
        raise RunError(f"timed out after {timeout}s waiting for /{compiled.pattern}/")

    def command(self, line):
        if self.process is None or self.process.poll() is not None:
            raise RunError("the server is not running")
        self.process.stdin.write((line + "\n").encode("utf-8"))
        self.process.stdin.flush()

    def query(self, line, pattern, timeout=15):
        mark = self.mark()
        self.command(line)
        return self.wait_for(pattern, timeout, since=mark)

    def count(self, selector):
        """How many entities in the End match selector, among those in loaded chunks."""
        match = self.query(f"{IN_END} if entity {selector}", COUNT)
        return int(match.group("count")) if match.group("count") else 0

    def health_report(self):
        """One /tps report: the lowest region TPS and every region it lists."""
        mark = self.mark()
        self.command("tps")
        self.wait_for(r"Highest \d+ utilisation regions", 15, since=mark)
        time.sleep(1.0)
        text = self.log()[mark:]
        lowest = LOWEST_TPS.search(text)
        regions = [{"world": m.group("world"), "block": [int(m.group(a)) for a in "xyz"],
                    "util": float(m.group("util")), "mspt": float(m.group("mspt")),
                    "tps": float(m.group("tps")), "chunks": int(m.group("chunks")),
                    "players": int(m.group("players")), "entities": int(m.group("entities"))}
                   for m in REGION.finditer(text)]
        return {"lowest_tps": float(lowest.group("tps")) if lowest else None, "regions": regions}


class Bots:
    """The scripted party in client/bots.js. Requests are synchronous; events are queued."""

    def __init__(self, port, count, version, log_path, node, passive=False):
        self.args = [node, os.path.join(HERE, "client", "bots.js"), "--host", "127.0.0.1",
                     "--port", str(port), "--version", version, "--count", str(count), "--prefix", "Bench"]
        if passive:
            self.args.append("--passive")
        self.names = [f"Bench{i}" for i in range(1, count + 1)]
        self.log_path = log_path
        self.replies = queue.Queue()
        self.events = queue.Queue()
        self.process = None
        self._next = 0
        self._stderr = None

    def __enter__(self):
        self._stderr = open(self.log_path, "wb")
        self.process = subprocess.Popen(self.args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self._stderr, cwd=os.path.join(HERE, "client"))
        threading.Thread(target=self._read, daemon=True).start()
        return self

    def __exit__(self, *exc):
        if self.process is not None and self.process.poll() is None:
            try:
                self.process.stdin.close()
                self.process.wait(timeout=15)
            except (OSError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait()
        if self._stderr:
            self._stderr.close()

    def _read(self):
        for raw in self.process.stdout:
            try:
                message = json.loads(raw.decode("utf-8"))
            except ValueError:
                continue
            (self.replies if "id" in message else self.events).put(message)
        self.events.put({"event": "exited"})

    def wait_spawned(self, timeout):
        spawned = set()
        deadline = time.monotonic() + timeout
        while len(spawned) < len(self.names):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RunError(f"only {sorted(spawned)} of {self.names} joined within {timeout}s")
            try:
                event = self.events.get(timeout=remaining)
            except queue.Empty:
                continue
            if event.get("event") == "spawn":
                spawned.add(event["bot"])
            elif event.get("event") in ("kicked", "error", "end", "exited"):
                raise RunError(f"a bot stopped while joining: {event}")

    def request(self, op, timeout=30, **fields):
        self._next += 1
        ident = self._next
        self.process.stdin.write((json.dumps({"id": ident, "op": op, **fields}) + "\n").encode("utf-8"))
        self.process.stdin.flush()
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RunError(f"bot request {op} timed out")
            try:
                reply = self.replies.get(timeout=min(remaining, 1.0))
            except queue.Empty:
                if self.process.poll() is not None:
                    raise RunError(f"the bot client exited during {op}")
                continue
            if reply.get("id") == ident:
                if not reply.get("ok"):
                    raise RunError(f"bot request {op} failed: {reply.get('error')}")
                return reply


def read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except FileNotFoundError:
        return ""


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def configure(text, overrides):
    """The shipped config.yml with each dotted key in overrides set, comments kept."""
    lines = text.split("\n")
    for dotted, value in overrides.items():
        path = dotted.split(".")
        depth = 0
        for index, line in enumerate(lines):
            stripped = line.lstrip(" ")
            if not stripped or stripped.startswith("#"):
                continue
            indent = len(line) - len(stripped)
            if indent < depth * 2:
                break
            if indent == depth * 2 and stripped.startswith(path[depth] + ":"):
                if depth == len(path) - 1:
                    setting = " " * indent + f"{path[depth]}: {value}"
                    if " #" in line:
                        # Keep the trailing comment in its column.
                        column = line.index(" #")
                        comment = line[column:].lstrip(" ")
                        setting = setting.ljust(column) + " " + comment
                    lines[index] = setting
                    break
                depth += 1
        else:
            raise RunError(f"config.yml has no key {dotted}")
        if not lines[index].lstrip(" ").startswith(f"{path[-1]}: {value}"):
            raise RunError(f"config.yml has no key {dotted}")
    return "\n".join(lines)


def post(index, total, radius=20):
    angle = 2 * math.pi * index / total
    return round(radius * math.cos(angle), 1), 75, round(radius * math.sin(angle), 1)


def equip(server, bots, passive):
    for name in bots.names:
        server.command(f"advancement grant {name} everything")
        server.command(f"gamemode survival {name}")
        server.command(f"clear {name}")
        if not passive:
            server.command(f'give {name} bow[enchantments={{"minecraft:infinity":1}}]')
            server.command(f"give {name} arrow 1")
        # Resistance V cancels all damage but the void's, so the party survives the fight.
        server.command(f"effect give {name} resistance infinite 4 true")
        server.command(f"effect give {name} saturation infinite 0 true")
    time.sleep(2)
    for i, name in enumerate(bots.names):
        x, y, z = post(i, len(bots.names))
        server.command(f"{IN_END} run tp {name} {x} {y} {z}")


def keep_on_island(server, bots):
    """Returns any bot knocked off the island, or respawned in the Overworld, to its post."""
    moved = 0
    for i, bot in enumerate(bots.request("state")["bots"]):
        position = bot["position"]
        if bot["dimension"] != "the_end" or position is None or position[1] < 45 or bot["health"] <= 0:
            x, y, z = post(i, len(bots.names))
            server.command(f"{IN_END} run tp {bot['name']} {x} {y} {z}")
            moved += 1
    return moved


def boot(server, args):
    server.wait_for(DONE, args.boot_timeout)
    match = SERVER_BUILD.search(server.log())
    return match.group("build") if match else None


def plugins(args):
    jars = [args.plugin_jar]
    for name in sorted(os.listdir(args.via_dir)):
        if name.startswith(("ViaVersion", "ViaBackwards")) and name.endswith(".jar"):
            jars.append(os.path.join(args.via_dir, name))
    return jars


def bench_one(args, dragons):
    workdir = os.path.join(os.path.abspath(args.workdir), f"dragons-{dragons}")
    overrides = {
        "boss-scaling.enabled": "true",
        "boss-scaling.battle-prep-seconds": PREP_SECONDS,
        "boss-scaling.multi-dragon.multiplier": "1.0",
        "boss-scaling.multi-dragon.max-dragons": dragons,
    }
    result = {"dragons": dragons, "samples": []}
    server = Server(os.path.join(workdir, "server"), args.server_jar, plugins(args), overrides,
                    args.java, args.memory, args.port)
    with server:
        result["server_build"] = boot(server, args)
        with Bots(args.port, args.party, args.client_version, os.path.join(workdir, "bots.log"),
                  args.node) as bots:
            bots.wait_spawned(120)
            mark = server.mark()
            equip(server, bots, passive=False)
            closed = server.wait_for(WINDOW_CLOSED, 120, since=mark)
            result["party_counted"] = int(closed.group("party"))
            result["dragons_earned"] = int(closed.group("dragons"))
            if result["dragons_earned"] != dragons:
                raise RunError(f"the party earned {result['dragons_earned']} dragons, not {dragons}")
            settle = time.monotonic() + args.warmup
            while time.monotonic() < settle:
                keep_on_island(server, bots)
                time.sleep(5)
            result["dragons_at_start"] = server.count("@e[type=ender_dragon]")
            relocated = 0
            for _ in range(args.window // REPORT_SECONDS):
                due = time.monotonic() + REPORT_SECONDS
                while time.monotonic() < due - 5:
                    relocated += keep_on_island(server, bots)
                    time.sleep(5)
                time.sleep(max(0.0, due - time.monotonic()))
                report = server.health_report()
                end = [r for r in report["regions"] if r["world"].endswith("the_end")]
                fight = max(end, key=lambda r: r["mspt"]) if end else None
                result["samples"].append({"lowest_tps": report["lowest_tps"], "end_region": fight,
                                          "regions": report["regions"]})
            result["dragons_at_end"] = server.count("@e[type=ender_dragon]")
            result["bots"] = bots.request("state")["bots"]
            result["relocations"] = relocated
    log = server.log()
    result["plugin_errors"] = len(re.findall(r"(ERROR|SEVERE)\]:? \[AntiSpeedrun\]", log))
    fights = [sample["end_region"] for sample in result["samples"]]
    if not fights or None in fights:
        raise RunError("a /tps report did not list the End region")
    mspt = [region["mspt"] for region in fights]
    result["summary"] = {
        "mspt_mean": round(statistics.mean(mspt), 2),
        "mspt_max": round(max(mspt), 2),
        "tps_min": round(min(region["tps"] for region in fights), 2),
        "util_mean": round(statistics.mean(region["util"] for region in fights), 1),
        "holds": max(mspt) < args.threshold,
    }
    return result


def bench(args):
    results = {"mode": "bench", "hardware": hardware(args), "threshold_mspt": args.threshold,
               "window_seconds": args.window, "party": args.party, "runs": []}
    for dragons in args.counts:
        print(f"-- {dragons} dragon(s)", flush=True)
        results["runs"].append(bench_one(args, dragons))
        save(args, results)
        s = results["runs"][-1]["summary"]
        print(f"   End region MSPT mean {s['mspt_mean']} worst {s['mspt_max']}, TPS worst {s['tps_min']}, "
              f"util {s['util_mean']}%: {'holds' if s['holds'] else 'DOES NOT HOLD'}", flush=True)
    print(table(results))
    return 0 if all(run["summary"]["holds"] for run in results["runs"]) else 1


def table(results):
    rows = ["| Dragons | End region MSPT, mean | MSPT, worst 15 s | TPS, worst 15 s | Utilisation, mean "
            "| Dragons alive at end | Holds |",
            "| ---: | ---: | ---: | ---: | ---: | ---: | :--- |"]
    for run in results["runs"]:
        s = run["summary"]
        rows.append(f"| {run['dragons']} | {s['mspt_mean']} | {s['mspt_max']} | {s['tps_min']} | "
                    f"{s['util_mean']}% | {run['dragons_at_end']} | {'yes' if s['holds'] else 'no'} |")
    return "\n".join(rows)


def portal_state(bots):
    """Exit portal cells and the egg, as the first bot sees the columns at (0, 0) and (1, 0)."""
    centre = bots.request("column", x=0, z=0, ymin=40, ymax=100)["names"]
    basin = bots.request("column", x=1, z=0, ymin=40, ymax=100)["names"]
    return {"egg": "dragon_egg" in centre, "end_portal_cells": basin.count("end_portal"),
            "portal_bedrock": centre.count("bedrock") + basin.count("bedrock")}


def vanilla_bar(bots):
    """The health on the vanilla dragon-fight bar, told from the plugin's bars by its flags."""
    bars = bots.request("bossbars")["bars"]
    dragon = [bar["health"] for bar in bars if bar["dragonBar"]]
    return {"vanilla": dragon[0] if dragon else None, "bars": len(bars)}


def adoption(args):
    workdir = os.path.abspath(args.workdir)
    # Two players at 1.5 earn three dragons: the primary and secondaries A and B.
    overrides = {
        "boss-scaling.enabled": "true",
        "boss-scaling.battle-prep-seconds": PREP_SECONDS,
        "boss-scaling.multi-dragon.multiplier": "1.5",
        "boss-scaling.multi-dragon.max-dragons": 3,
    }
    steps = []

    def record(step, **data):
        steps.append({"step": step, **data})
        print(f"   {step}: {data}", flush=True)

    result = {"mode": "adoption", "hardware": hardware(args), "steps": steps}
    server = Server(os.path.join(workdir, "server"), args.server_jar, plugins(args), overrides,
                    args.java, args.memory, args.port)
    with server:
        result["server_build"] = boot(server, args)
        with Bots(args.port, 2, args.client_version, os.path.join(workdir, "bots.log"), args.node,
                  passive=True) as bots:
            bots.wait_spawned(120)
            mark = server.mark()
            equip(server, bots, passive=True)
            closed = server.wait_for(WINDOW_CLOSED, 120, since=mark)
            record("window closed", party=int(closed.group("party")), dragons=int(closed.group("dragons")))
            time.sleep(10)
            keep_on_island(server, bots)
            record("dragons", all=server.count("@e[type=ender_dragon]"), tagged=server.count(TAGGED),
                   untagged=server.count(UNTAGGED), **portal_state(bots), bar=vanilla_bar(bots))
            # Folia has no /tag, so A is whichever secondary dies first and B is the one left.
            # Control: a secondary killed while the primary is tracked must not end the fight.
            kill(server, bots.names[0], expect_left=1)
            keep_on_island(server, bots)
            record("control: secondary A killed with the primary tracked", tagged_left=server.count(TAGGED),
                   untagged_left=server.count(UNTAGGED), **portal_state(bots), bar=vanilla_bar(bots))
            # Half B's health, so the vanilla bar shows which dragon the fight is tracking.
            server.command(f"{IN_END} run damage {first(TAGGED)} 100 minecraft:player_attack by {bots.names[0]}")
            time.sleep(3)
            record("secondary B damaged by 100", bar=vanilla_bar(bots))
            server.command(f"{IN_END} run tp {first(UNTAGGED)} {PARKING}")
            time.sleep(5)
            record("primary moved to an unloaded chunk", untagged_loaded=server.count(UNTAGGED),
                   tagged_loaded=server.count(TAGGED), bar=vanilla_bar(bots))
            waited = 5
            while waited < args.absence:
                time.sleep(10)
                waited += 10
                keep_on_island(server, bots)
                record(f"primary absent {waited}s (~{waited * 20} ticks)", tagged_loaded=server.count(TAGGED),
                       bar=vanilla_bar(bots))
            tagged, untagged = server.count(TAGGED), server.count(UNTAGGED)
            relabelled = "took secondary dragon" in server.log()
            # B is the only dragon at less than full health, so a bar below 1 is the fight tracking B.
            result["adopted"] = relabelled or any(
                step["bar"]["vanilla"] not in (None, 1) for step in steps if step["step"].startswith("primary absent"))
            record("after the absence", tagged_loaded=tagged, untagged_loaded=untagged,
                   plugin_relabelled=relabelled, bar=vanilla_bar(bots))
            if not relabelled:
                # Unhandled: B still carries the tag and dies like any secondary.
                kill(server, bots.names[0], expect_left=0)
                time.sleep(5)
                keep_on_island(server, bots)
                after = portal_state(bots)
                record("secondary B killed while the primary is still unloaded", tagged_left=server.count(TAGGED),
                       **after, bar=vanilla_bar(bots))
                result["victory_early"] = after["end_portal_cells"] > 0 or after["egg"]
            else:
                # Handled: B is the primary now. While the old primary is still unloaded it must already
                # count as a secondary, so a lethal hit on B is refused.
                server.command(f"{IN_END} run damage {first(UNTAGGED)} 1000 minecraft:player_attack by {bots.names[0]}")
                time.sleep(13)
                keep_on_island(server, bots)
                unloaded = portal_state(bots)
                record("lethal hit on B while the old primary is unloaded", untagged_left=server.count(UNTAGGED),
                       tagged_left=server.count(TAGGED), **unloaded, bar=vanilla_bar(bots))
                # Bring the old primary back; it must load as a secondary and still hold B alive.
                runner = bots.names[1]
                server.command(f"gamemode spectator {runner}")
                server.command(f"{IN_END} run tp {runner} {PARKING}")
                time.sleep(6)
                # The only tagged dragon left is the old primary, if it loaded as a secondary.
                server.command(f"{IN_END} run tp {first(TAGGED)} 0 90 0")
                time.sleep(4)
                server.command(f"gamemode survival {runner}")
                keep_on_island(server, bots)
                server.command(f"{IN_END} run tp {runner} {' '.join(str(v) for v in post(1, 2))}")
                time.sleep(4)
                record("old primary back on the island", tagged_loaded=server.count(TAGGED),
                       untagged_loaded=server.count(UNTAGGED),
                       demoted_logged="is a secondary now" in server.log(), bar=vanilla_bar(bots))
                server.command(f"{IN_END} run damage {first(UNTAGGED)} 1000 minecraft:player_attack by {bots.names[0]}")
                time.sleep(13)
                keep_on_island(server, bots)
                after = portal_state(bots)
                record("lethal hit on B while the old primary lives", untagged_left=server.count(UNTAGGED),
                       tagged_left=server.count(TAGGED), **after, bar=vanilla_bar(bots))
                result["victory_early"] = any(state["end_portal_cells"] > 0 or state["egg"]
                                              for state in (unloaded, after))
                kill(server, bots.names[0], expect_left=0)
                server.command(f"{IN_END} run damage {first(UNTAGGED)} 1000 minecraft:player_attack by {bots.names[0]}")
                time.sleep(25)
                keep_on_island(server, bots)
                record("old primary killed, then B", untagged_left=server.count(UNTAGGED),
                       tagged_left=server.count(TAGGED), **portal_state(bots), bar=vanilla_bar(bots))
    print(f"Fight adopted a secondary: {'yes' if result['adopted'] else 'no'}; "
          f"victory sequence ran early: {'YES' if result.get('victory_early') else 'no'}")
    save(args, result)
    return 0


def first(selector, extra=None):
    inner = selector[len("@e["):-1]
    return f"@e[{inner},{extra + ',' if extra else ''}limit=1]"


def kill(server, player, expect_left):
    """A player-attributed lethal hit on one secondary, so its death runs vanilla's dying sequence.

    Falls back to /kill when the hit leaves more than expect_left secondaries after the animation.
    """
    server.command(f"{IN_END} run damage {first(TAGGED)} 1000 minecraft:player_attack by {player}")
    time.sleep(13)
    if server.count(TAGGED) > expect_left:
        server.command(f"{IN_END} run kill {first(TAGGED)}")
        time.sleep(3)


def hardware(args):
    info = {"platform": platform.platform(), "python": platform.python_version(),
            "server_heap": args.memory}
    try:
        info["java"] = subprocess.run([args.java, "-version"], capture_output=True,
                                      text=True).stderr.strip().splitlines()
    except OSError as error:
        info["java"] = str(error)
    if sys.platform == "win32":
        out = subprocess.run(["powershell", "-NoProfile", "-Command",
                              "(Get-CimInstance Win32_Processor).Name; "
                              "(Get-CimInstance Win32_Processor).NumberOfLogicalProcessors; "
                              "[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1)"],
                             capture_output=True, text=True).stdout.splitlines()
        info["cpu"], info["threads"], info["ram_gb"] = (part.strip() for part in (out + ["", "", ""])[:3])
    else:
        info["cpu"] = platform.processor()
        info["threads"] = os.cpu_count()
    return info


def save(args, results):
    os.makedirs(os.path.abspath(args.workdir), exist_ok=True)
    with open(os.path.join(os.path.abspath(args.workdir), "results.json"), "w", encoding="utf-8") as handle:
        json.dump(results, handle, indent=2)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("mode", choices=("bench", "adoption"))
    parser.add_argument("--server-jar", required=True)
    parser.add_argument("--plugin-jar", required=True)
    parser.add_argument("--via-dir", required=True,
                        help="directory holding the ViaVersion and ViaBackwards jars for an older client")
    parser.add_argument("--client-version", default="26.1", help="protocol the bots speak")
    parser.add_argument("--workdir", required=True)
    parser.add_argument("--counts", type=int, nargs="+", default=[1, 2, 3, 4, 5])
    parser.add_argument("--party", type=int, default=5, help="players in the End for every count")
    parser.add_argument("--warmup", type=int, default=30, help="seconds of fighting before sampling")
    parser.add_argument("--window", type=int, default=120, help="seconds sampled per count")
    parser.add_argument("--threshold", type=float, default=50.0,
                        help="worst 15 s End region MSPT that still holds 20 TPS")
    parser.add_argument("--absence", type=int, default=95,
                        help="seconds the primary stays unloaded; vanilla searches after 1,200 ticks")
    parser.add_argument("--java", default="java")
    parser.add_argument("--node", default="node")
    parser.add_argument("--memory", default="4G")
    parser.add_argument("--port", type=int, default=25599)
    parser.add_argument("--boot-timeout", type=int, default=300)
    args = parser.parse_args(argv)
    try:
        return bench(args) if args.mode == "bench" else adoption(args)
    except RunError as error:
        print(f"::error::{error}", flush=True)
        return 2


if __name__ == "__main__":
    sys.exit(main())
