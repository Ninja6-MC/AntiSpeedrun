"""Restoring the End's pillar crystals and cages when reinforcements spawn (#273).

Runs the world's first dragon fight, so it comes before the anti-cheese probes, which win it. With
boss-scaling on, a multiplier of 1.0 and reset-towers-on-scale on, both players enter the End: the
party of two earns one extra dragon. During the countdown every crystal is destroyed, one bar of a
caged pillar is broken and another is replaced by a player's glass block, and the helper stands on
an uncaged pillar's top, boxed in barriers so the dragon cannot knock them off. Until the extra
dragon spawns nothing is restored (the control). Then every free pillar has its crystal back, the
broken bar is back and the glass is still there, while the occupied pillar waits; it gets its
crystal once the helper leaves, and no pillar ever holds two.
"""

import os
import re
import time

from credits import HELPER, helper_client
from gates import set_key
from harness import ProbeError
from probes import END, OVERWORLD, Probes

RULE = "boss-scaling.multi-dragon.reset-towers-on-scale"
PROBES = ("towers",)
# Vanilla's ten pillar centres (EndTowerRules.pillarCentres) and their possible heights.
CENTRES = ((42, 0), (33, 24), (12, 39), (-13, 39), (-34, 24),
           (-42, -1), (-34, -25), (-13, -40), (12, -40), (33, -25))
HEIGHTS = tuple(76 + 3 * size for size in range(10))
CAGED = (79, 82)
PREP_SECONDS = 25
# Where the scripted player waits: an obsidian box clear of every pillar, inside the main island.
ARENA = (0, 110, 0)
SECONDARY = '@e[type=minecraft:ender_dragon,nbt={BukkitValues:{"antispeedrun:n6_asr_secondary_dragon":1b}}]'


def set_nested(text, section, child, key, value):
    """config.yml with section.child.key (four-space indent) set to value."""
    pattern = (rf"(?m)^({re.escape(section)}:\n(?:[ \t].*\n|\n)*?  {re.escape(child)}:\n"
               rf"(?:    .*\n|\n)*?    {re.escape(key)}:[ \t]*)(\S+)")
    text, count = re.subn(pattern, lambda match: match.group(1) + value, text, count=1)
    if count != 1:
        raise ProbeError(f"{section}.{child}.{key} is not in config.yml")
    return text


class TowerProbes(Probes):

    def __init__(self, server, client, player, results, helper):
        super().__init__(server, client, player, results)
        self.helper = helper
        self.pillars = {}

    def rules(self, on):
        def edit(text):
            text = set_key(text, "boss-scaling", "enabled", "true" if on else "false")
            text = set_key(text, "boss-scaling", "battle-prep-seconds", str(PREP_SECONDS if on else 30))
            text = set_nested(text, "boss-scaling", "multi-dragon", "multiplier", "1.0" if on else "0.5")
            return set_nested(text, "boss-scaling", "multi-dragon", "reset-towers-on-scale",
                              "true" if on else "false")
        self.reload(edit)

    def block_is(self, x, y, z, block):
        """Whether the End block at a point is block, read by the probe plugin on its region.

        Folia cannot run `execute if block` from the console, which belongs to no region.
        """
        query = f"b{next(self._queries)}"
        match = self.server.query(f"asrprobe container {END} {x} {y} {z} {query}",
                                  rf"ASRPROBE container query={query} (?:block=(?P<block>\S+)|unknown-world)")
        if match.group("block") is None:
            raise ProbeError("the probe plugin knows no End world")
        return match.group("block") == block.upper()

    def setblock(self, x, y, z, block):
        # fill, not setblock: the arena helper has fill working from Folia's console.
        self.run(f"execute in {END} run fill {x} {y} {z} {x} {y} {z} minecraft:{block}")

    def find_pillars(self):
        for x, z in CENTRES:
            for height in reversed(HEIGHTS):
                if self.block_is(x, height, z, "bedrock"):
                    self.pillars[(x, z)] = height
                    break
        if len(self.pillars) != len(CENTRES):
            raise ProbeError(f"found {len(self.pillars)} of ten pillar tops: {self.pillars}")

    def crystals(self, centre):
        x, z = centre
        return self.near(END, x + 0.5, self.pillars[centre] + 1, z + 0.5, 1.5, "end_crystal")

    def crystal_counts(self):
        return {centre: self.crystals(centre) for centre in self.pillars}

    def secondaries(self):
        return self.exists(END, SECONDARY)

    def towers(self):
        for name in (self.player, HELPER):
            for line in (f"gamemode survival {name}",
                         f"advancement grant {name} everything",
                         f"effect give {name} minecraft:resistance infinite 4 true",
                         f"effect give {name} minecraft:fire_resistance infinite 0 true",
                         f"effect give {name} minecraft:saturation infinite 0 true"):
                self.run(line)
            self.server.query(f"asr credit grant {name} all", r"Granted |already held ", timeout=15)
        # Out of the End before the rule goes on: the window opens on the next entry.
        self.tp(OVERWORLD, 0.5, 100, 0.5)
        self.tp(OVERWORLD, 2.5, 100, 0.5, player=HELPER)
        self.run(f"execute in {END} run forceload add -48 -48 48 48")
        time.sleep(3)
        self.find_pillars()
        caged = [centre for centre, height in self.pillars.items() if height in CAGED]
        open_ = [centre for centre, height in self.pillars.items() if height not in CAGED]
        if not caged or not open_:
            raise ProbeError(f"no caged or no open pillar among {self.pillars}")
        cage = caged[0]
        cx, cz = cage
        ch = self.pillars[cage]
        broken = (cx - 2, ch + 1, cz)
        glass = (cx + 2, ch + 2, cz)
        if not self.block_is(*broken, "iron_bars") or not self.block_is(*glass, "iron_bars"):
            raise ProbeError(f"the caged pillar at {cage} has no vanilla cage to break")
        occupied = min(open_, key=lambda centre: self.pillars[centre])
        ox, oz = occupied
        oh = self.pillars[occupied]

        self.rules(on=True)
        self.arena(END, *ARENA, half=3, height=4)
        self.tp(END, ARENA[0] + 0.5, ARENA[1], ARENA[2] + 0.5)
        opened = time.monotonic()

        # The party wrecks the towers during the countdown.
        self.run(f"execute in {END} run kill @e[type=minecraft:end_crystal]")
        self.setblock(*broken, "air")
        self.setblock(*glass, "glass")
        # The helper stands on the open pillar's bedrock, boxed in so the dragon cannot move them.
        self.run(f"execute in {END} run fill {ox - 1} {oh + 1} {oz - 1} {ox + 1} {oh + 3} {oz + 1} "
                 f"minecraft:barrier")
        self.run(f"execute in {END} run fill {ox} {oh + 1} {oz} {ox} {oh + 2} {oz} minecraft:air")
        self.tp(END, ox + 0.5, oh + 1, oz + 0.5, player=HELPER)
        time.sleep(2)

        before = self.crystal_counts()
        self.results.check(RULE, "control: nothing is restored before the extra dragons spawn",
                           all(count == 0 for count in before.values())
                           and not self.block_is(*broken, "iron_bars"),
                           f"crystals {before}, {PREP_SECONDS - (time.monotonic() - opened):.0f}s left")

        deadline = opened + PREP_SECONDS + 30
        while not self.secondaries():
            if time.monotonic() > deadline:
                raise ProbeError("no extra dragon spawned for a party of two")
            time.sleep(1)
        self.results.check(RULE, "a party of two at multiplier 1.0 fights an extra dragon", True,
                           "a tagged secondary dragon is in the End")

        deadline = time.monotonic() + 15
        while True:
            after = self.crystal_counts()
            free = {centre: count for centre, count in after.items() if centre != occupied}
            if all(count == 1 for count in free.values()) or time.monotonic() > deadline:
                break
            time.sleep(1)
        self.results.check(RULE, "every crystal destroyed in the countdown is back on its free pillar",
                           all(count == 1 for count in free.values()), f"crystals {after}")
        self.results.check(RULE, "the broken cage bar is rebuilt", self.block_is(*broken, "iron_bars"),
                           f"bar at {broken}")
        self.results.check(RULE, "a player's block in the cage is kept", self.block_is(*glass, "glass"),
                           f"glass at {glass}")

        time.sleep(3)
        held = self.crystals(occupied)
        where = self.where(HELPER)
        self.results.check(RULE, "the pillar with a player on top is left alone while they stand there",
                           held == 0 and abs(float(where["y"]) - (oh + 1)) < 0.6,
                           f"crystals {held} at {occupied}, helper at y={where['y']}")

        self.tp(END, ARENA[0] + 1.5, ARENA[1], ARENA[2] + 0.5, player=HELPER)
        deadline = time.monotonic() + 10
        while self.crystals(occupied) != 1 and time.monotonic() < deadline:
            time.sleep(1)
        final = self.crystal_counts()
        self.results.check(RULE, "the pillar gets its crystal once the player leaves",
                           final[occupied] == 1, f"crystals {final[occupied]} at {occupied}")
        self.results.check(RULE, "no pillar holds two crystals", all(count == 1 for count in final.values()),
                           f"crystals {final}")
        self.run(f"execute in {END} run fill {ox - 1} {oh + 1} {oz - 1} {ox + 1} {oh + 3} {oz + 1} "
                 f"minecraft:air replace minecraft:barrier")

    def cleanup(self):
        # The extra dragons go; the primary stays for the anti-cheese probes, which win the fight.
        self.run(f"execute in {END} run kill {SECONDARY}")
        time.sleep(1)
        self.rules(on=False)
        self.tp(OVERWORLD, 0.5, 100, 0.5)
        self.tp(OVERWORLD, 2.5, 100, 0.5, player=HELPER)
        self.run(f"execute in {END} run forceload remove -48 -48 48 48")


def run_all(server, client, player, results, only=PROBES):
    if "towers" not in only:
        return
    helper = helper_client(client, os.path.join(os.path.dirname(client.log_path), "towers-helper.log"))
    with helper:
        helper.wait_event("spawn", 120)
        probes = TowerProbes(server, client, player, results, helper)
        try:
            probes.towers()
        except ProbeError as error:
            results.check(RULE, "the tower probes completed", False, str(error))
        finally:
            try:
                probes.cleanup()
            except ProbeError as error:
                results.note(f"tower cleanup: {error}")
            helper.request("quit", timeout=10)
