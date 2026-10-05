"""What every probe module shares: the recorded results, the known-issue list, and the console
helpers each probe builds its state with.

The helpers work the same on Paper and Folia. Folia has no /data command, so an entity's health
and a player's whereabouts come from the probe plugin's console command, /asrprobe, which reads
each on the thread that owns the entity. Everything else is vanilla commands both platforms run
from the console.
"""

import itertools
import os
import re
import time

from harness import ProbeError

OVERWORLD = "minecraft:overworld"
NETHER = "minecraft:the_nether"
END = "minecraft:the_end"

# Checks that fail today because of an open bug, keyed by issue number. Such a check is still
# run and recorded, as "known-failure", but does not fail the run. Once it passes, the issue is
# fixed and the entry is stale: the check then fails as "unexpected-pass" until it is removed here.
KNOWN_ISSUES = {}

ENTITY_TYPE = re.compile(r"type=minecraft:(\w+)")


class Results:
    def __init__(self):
        self.checks = []
        self.notes = []

    def check(self, rule, name, passed, detail, known_issue=None):
        entry = {"rule": rule, "check": name, "passed": bool(passed), "detail": detail}
        label = "PASS" if passed else "FAIL"
        if known_issue is not None:
            if known_issue not in KNOWN_ISSUES:
                raise ProbeError(f"#{known_issue} is not in KNOWN_ISSUES")
            entry["known_issue"] = known_issue
            if passed:
                entry.update(passed=False, status="unexpected-pass")
                entry["detail"] = (f"{detail}; passes now, so #{known_issue} looks fixed: "
                                   f"remove it from KNOWN_ISSUES")
                label = "FAIL"
            else:
                entry.update(passed=True, status="known-failure")
                label = f"KNOWN #{known_issue}"
        self.checks.append(entry)
        print(f"[{label}] {rule}: {name} -- {entry['detail']}", flush=True)

    def note(self, text):
        self.notes.append(text)
        print(f"[NOTE] {text}", flush=True)

    @property
    def passed(self):
        return bool(self.checks) and all(check["passed"] for check in self.checks)


class Probes:
    """Console helpers over one server and one scripted player."""

    def __init__(self, server, client, player, results):
        self.server = server
        self.client = client
        self.player = player
        self.results = results
        self.config_path = os.path.join(server.workdir, "plugins", "AntiSpeedrun", "config.yml")
        self._queries = itertools.count(1)

    def run(self, line):
        self.server.command(line)

    def reload(self, edit=None):
        """Applies edit (config text -> config text) to config.yml, if given, and runs /asr reload."""
        if edit is not None:
            with open(self.config_path, "r", encoding="utf-8") as handle:
                text = handle.read()
            with open(self.config_path, "w", encoding="utf-8") as handle:
                handle.write(edit(text))
        match = self.server.query("asr reload", r"Configuration reloaded(?P<rest>[^\n]*)|not applied",
                                  timeout=30)
        if match.group(0) == "not applied":
            raise ProbeError("/asr reload refused the edited config.yml")
        time.sleep(0.5)
        return match

    def health(self, selector):
        """The health of the one tagged entity selector names, or None once it is dead or gone.

        selector must name a type and the asrp tag: the probe plugin tracks tagged entities only.
        """
        kind = ENTITY_TYPE.search(selector)
        if not kind or "tag=asrp" not in selector:
            raise ProbeError(f"health() needs a typed, asrp-tagged selector, not {selector}")
        query = f"h{next(self._queries)}"
        match = self.server.query(
            f"asrprobe health {kind.group(1)} {query}",
            rf"ASRPROBE health query={query} (?:none|uuid=\S+ alive=(?P<alive>\S+) health=(?P<health>\S+))")
        if match.group("alive") != "true":
            return None
        return float(match.group("health"))

    def where(self, player=None):
        """Where the server has the player: world, environment, position, vehicle and permissions."""
        query = f"w{next(self._queries)}"
        match = self.server.query(f"asrprobe where {player or self.player} {query}",
                                  rf"ASRPROBE where query={query} (?P<rest>[^\r\n]*)")
        rest = match.group("rest")
        if rest == "offline":
            raise ProbeError(f"{player or self.player} is not online")
        return dict(pair.split("=", 1) for pair in rest.split())

    def near(self, dimension, x, y, z, radius, entity, item="any", remove=False):
        """How many entities of a type are within radius of a point; for items, of one material.

        Folia refuses a positional selector from the console, which runs on no region, so this
        asks the probe plugin, which counts on the region that owns the point. exists() is for
        selectors without a position. With remove, what is counted is also removed.
        """
        query = f"n{next(self._queries)}"
        verb = "remove" if remove else "near"
        match = self.server.query(f"asrprobe {verb} {dimension} {x} {y} {z} {radius} {entity} {item} {query}",
                                  rf"ASRPROBE near query={query} (?:count=(?P<count>\d+)|unknown-world)")
        if match.group("count") is None:
            raise ProbeError(f"the probe plugin knows no world {dimension}")
        return int(match.group("count"))

    def exists(self, dimension, selector):
        match = self.server.query(f"execute in {dimension} if entity {selector}",
                                  r"Test passed|Test failed")
        return match.group(0) == "Test passed"

    def tp(self, dimension, x, y, z, yaw=0, pitch=0, player=None):
        self.run(f"execute in {dimension} run tp {player or self.player} {x} {y} {z} {yaw} {pitch}")
        time.sleep(1.5)

    def arena(self, dimension, x, y, z, half=12, height=30):
        """A hollow obsidian box with its floor at y - 1, lit so nothing spawns inside it."""
        self.run(f"execute in {dimension} run forceload add {x - half} {z - half} {x + half} {z + half}")
        box = (f"{x - half} {y - 1} {z - half} {x + half} {y + height} {z + half}")
        for _ in range(40):
            match = self.server.query(f"execute in {dimension} run fill {box} minecraft:obsidian hollow",
                                      r"Successfully filled|No blocks were filled|not loaded|"
                                      r"Unknown or incomplete|Too many blocks|Incorrect argument")
            if match.group(0) in ("Successfully filled", "No blocks were filled"):
                break
            if match.group(0) != "not loaded":
                raise ProbeError(f"could not build the arena: {match.group(0)}")
            time.sleep(1)
        else:
            raise ProbeError(f"the arena at {dimension} {x} {y} {z} never loaded")
        inner = half - 1
        for level in (y, y + height // 2):
            self.run(f"execute in {dimension} run fill {x - inner} {level} {z - inner} {x + inner} {level} "
                     f"{z + inner} minecraft:light[level=15] replace minecraft:air")
        time.sleep(1)

    def clear_arena(self, dimension, x, y, z, half=12):
        # No positional selector: Folia refuses one from the console. Every probe's loose items and
        # orbs are in its own arena anyway.
        self.run(f"execute in {dimension} run kill @e[tag=asrp]")
        self.run(f"execute in {dimension} run kill @e[type=minecraft:item]")
        self.run(f"execute in {dimension} run kill @e[type=minecraft:experience_orb]")
        inner = half - 1
        self.run(f"execute in {dimension} run fill {x - inner} {y} {z - inner} {x + inner} {y + 8} {z + inner} "
                 f"minecraft:air replace minecraft:fire")
        time.sleep(1)

    def count(self, item):
        return self.client.request("count", item=item)["count"]
