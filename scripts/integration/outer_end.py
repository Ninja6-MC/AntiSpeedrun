"""The personal dragon-fight requirement for the outer End (#278).

The player holds every advancement and every credit except dragon-fight, so the dimension and
item gates stay out of the way, and is never an operator or a bypass holder. With
anti-cheese.outer-end-requires-dragon-fight on, as shipped, and the first-kill rule off, each case
puts the player beyond outer-end-radius by command and reads back from the server where the
bounds poll left them. The credit cases hit dragons the probe summons in the End with the
player's own sword, kill them by command, and read the credit from the plugin's store.
"""

import math
import time

from credits import CreditProbes
from gates import set_key
from harness import ProbeError
from probes import END, OVERWORLD

RULE = "outer End dragon-fight requirement"
PROBES = ("outer_end",)
KEY = "outer-end-requires-dragon-fight"
RADIUS = 500
# A small obsidian box beyond the radius, over the void.
OUT_X, OUT_Y, OUT_Z = 0, 72, 640
# Where an unqualified player with no remembered position inside is returned (SAFE_COLUMN_X/Z).
SAFE = (8.5, 8.5)
# The arena the credit cases fight in, inside the radius.
ARENA_X, ARENA_Y, ARENA_Z = 0, 100, 200
SECONDARY_NBT = 'BukkitValues:{"antispeedrun:n6_asr_secondary_dragon":1b}'


class OuterEndProbes(CreditProbes):

    def rules(self, personal, world=False):
        def edit(text):
            text = set_key(text, "anti-cheese", KEY, "true" if personal else "false")
            return set_key(text, "anti-cheese", "block-gateway-pre-dragon", "true" if world else "false")
        self.reload(edit)

    def position(self):
        where = self.where()
        x, y, z = (float(where[axis]) for axis in ("x", "y", "z"))
        return where["environment"], x, y, z

    def outside(self):
        self.tp(END, OUT_X + 0.5, OUT_Y, OUT_Z + 0.5)

    def returned(self, label, near=None, wait=12):
        """Checks that the poll brought the player back inside the radius, on ground that holds."""
        deadline = time.monotonic() + wait
        while True:
            environment, x, y, z = self.position()
            inside = environment == "THE_END" and math.hypot(x, z) <= RADIUS
            if inside or time.monotonic() >= deadline:
                break
            time.sleep(0.5)
        detail = f"{environment} ({x:.1f}, {y:.1f}, {z:.1f})"
        ok = inside
        if inside:
            time.sleep(2)
            _, x2, y2, z2 = self.position()
            # Standing, not falling into the void.
            ok = abs(y2 - y) < 0.6 and math.hypot(x2 - x, z2 - z) < 1.5
            detail += f", then ({x2:.1f}, {y2:.1f}, {z2:.1f}) two seconds later"
            if near is not None:
                ok = ok and math.hypot(x2 - near[0], z2 - near[1]) < 3
                detail += f", expected near ({near[0]}, {near[1]})"
        self.results.check(RULE, label, ok, detail)
        return ok

    def stays(self, label, wait=6):
        time.sleep(wait)
        environment, x, y, z = self.position()
        self.results.check(RULE, label, environment == "THE_END" and math.hypot(x, z) > RADIUS,
                           f"{environment} ({x:.1f}, {y:.1f}, {z:.1f}) after {wait}s")

    def leave_end(self):
        """Into the Overworld, so nothing inside the End is remembered."""
        self.arena(OVERWORLD, 0, 100, 0)
        self.tp(OVERWORLD, 0.5, 100, 0.5)
        time.sleep(3)

    def dragon(self, secondary=False):
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon,tag=asrp]")
        time.sleep(0.5)
        extra = f",{SECONDARY_NBT}" if secondary else ""
        # Hovering (phase 10) keeps the dragon where it is summoned, head part about six blocks north.
        self.run(f"execute in {END} run summon minecraft:ender_dragon {ARENA_X + 0.5} {ARENA_Y + 2} "
                 f"{ARENA_Z + 0.5} {{DragonPhase:10,Tags:[\"asrp\"],Rotation:[0f,0f],"
                 f"PersistenceRequired:1b{extra}}}")
        time.sleep(1.5)
        selector = "@e[type=minecraft:ender_dragon,tag=asrp,limit=1]"
        if self.health(selector) is None:
            raise ProbeError("the probe's dragon did not appear in the End")
        return selector

    def hit(self, selector, label):
        self.tp(END, ARENA_X + 3.5, ARENA_Y, ARENA_Z - 5.5)
        before = self.health(selector)
        self.client.request("attack", entity="ender_dragon", offset=1)
        time.sleep(1)
        after = self.health(selector)
        landed = before is not None and after is not None and after < before
        self.results.check(RULE, label, landed, f"health {before} -> {after}")
        if not landed:
            raise ProbeError(f"{label}: the hit did not land")

    def kill(self, selector):
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon,tag=asrp]")
        time.sleep(2)
        if self.health(selector) is not None:
            raise ProbeError("the probe's dragon survived /kill")

    # ------------------------------------------------------------------ cases

    def boundary(self):
        self.rules(personal=True)
        self.leave_end()
        self.arena(END, OUT_X, OUT_Y, OUT_Z, half=3, height=4)

        self.outside()
        self.returned("an unqualified player arriving beyond the radius with no position inside "
                      "is returned to standing ground beside the exit portal", near=SAFE)

        time.sleep(3)
        _, x, _, z = self.position()
        self.outside()
        self.returned("an unqualified player who crosses again is returned to the last position "
                      "they held inside", near=(x, z))

        self.credit("grant", "dragon-fight")
        self.outside()
        self.stays("a player with dragon-fight may stay beyond the radius")

        self.credit("revoke", "dragon-fight")
        self.returned("revoking dragon-fight while beyond the radius returns the player to the "
                      "exit portal", near=SAFE)

        self.rules(personal=False)
        self.outside()
        self.stays("with the rule off, an unqualified player may stay beyond the radius")
        self.rules(personal=True)
        self.returned("switching the rule on while the player is beyond the radius returns them",
                      near=SAFE)

    def credit_cases(self):
        self.arena(END, ARENA_X, ARENA_Y, ARENA_Z)
        self.run(f"give {self.player} minecraft:iron_sword")
        self.wait_count("iron_sword", 1)
        self.client.request("hold", item="iron_sword")

        self.credit("revoke", "dragon-fight")
        self.tp(END, ARENA_X + 3.5, ARENA_Y, ARENA_Z - 5.5)
        self.kill(self.dragon())
        self.expect("a dragon fight won without the player's damage earns them nothing",
                    "dragon-fight", False)

        selector = self.dragon(secondary=True)
        self.hit(selector, "the player's hit lands on a secondary dragon")
        self.kill(selector)
        self.expect("a secondary dragon's death completes no fight", "dragon-fight", False)

        self.kill(self.dragon())
        self.expect("winning the fight credits a player who hit only its secondary",
                    "dragon-fight", True, "action")

        self.credit("revoke", "dragon-fight")
        selector = self.dragon()
        self.hit(selector, "the player's hit lands on the primary dragon")
        self.kill(selector)
        self.expect("the primary's death credits the player who hit it", "dragon-fight", True, "action")


def run_all(server, client, player, results, only=PROBES):
    if "outer_end" not in only:
        return
    probes = OuterEndProbes(server, client, player, results, None)
    try:
        for line in (f"gamemode survival {player}",
                     f"advancement grant {player} everything",
                     f"clear {player}",
                     f"effect give {player} minecraft:resistance infinite 4 true",
                     f"effect give {player} minecraft:saturation infinite 0 true",
                     # The battle is read on the region owning the End's chunk (0, 0).
                     f"execute in {END} run forceload add 0 0"):
            probes.run(line)
        probes.credit("grant", "all")
        probes.credit("revoke", "dragon-fight")
        probes.boundary()
        probes.credit_cases()
    except ProbeError as error:
        results.check(RULE, "the outer End probes completed", False, str(error))
    finally:
        try:
            probes.rules(personal=True)
            probes.credit("grant", "dragon-fight")
            probes.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon,tag=asrp]")
        except ProbeError as error:
            results.note(f"outer End cleanup: {error}")
