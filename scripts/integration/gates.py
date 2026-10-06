"""Gameplay probes for the dimension and item gates (#58): what a player can and cannot do through
the plugin's gates, done the way a player does it, on Paper and on Folia.

Every probe sets the player's progression itself (advancements revoked, then only what the case
needs granted, then /asr reload so no cached evaluation from an earlier case survives), builds its
own fixtures from console commands in an obsidian box it owns, and asserts on what the server
reports: which world the player is in, what is in their inventory and in the container, whether
an item entity is still on the ground, and the probe plugin's pickup, click, trade and portal
lines. Each refusal has a control: the same action by a player who qualifies, which must succeed,
or a refusal could just be the probe failing to act.

Item gating is material-only (#52, docs/provenance-model.md). The audit fixtures written for the
retired natural-provenance model are kept as what they still guard against: a stack handed over by
someone else, or carrying the old natural-origin tag, is judged on its material alone; breaking a
container rather than opening it gives its contents no owner; only the player's own drop or death
pile comes back to them.
"""

import re
import time

from harness import ProbeError
from probes import NETHER, OVERWORLD, Probes

GATES = "dimension-gates"
ITEMS = "item-progression"
NETHER_GATE = "minecraft:story/smelt_iron"
DIAMOND_TIER = "minecraft:story/iron_tools"
# The advancements answered from personal credits, and the /asr credit word for each.
CREDITED = {
    "minecraft:story/mine_stone": "mine-stone",
    "minecraft:story/smelt_iron": "smelt-iron",
    "minecraft:story/iron_tools": "iron-tools",
    "minecraft:story/upgrade_tools": "upgrade-tools",
    "minecraft:story/mine_diamond": "mine-diamond",
    "minecraft:nether/obtain_blaze_rod": "obtain-blaze-rod",
}
LOCKED_NETHER = "Nether is locked"
LOCKED_ITEM = "cannot pick up"

# The overworld box every gate fixture lives in, and a small one in the Nether to land in.
GX, GY, GZ = 200, 100, 0
LANDING = (300, 70, 0)
# A Nether portal in the box's north wall: portal blocks x 200..201, y 100..102, z -6.
PORTAL_Z = GZ - 6
START = (GX + 1.0, GY, GZ - 2.5)
SPAWN = (GX + 6, GY, GZ + 6)

PICKUP = re.compile(r"ASRPROBE pickup player=(?P<player>\S+) item=(?P<item>\S+) count=\S+ cancelled=(?P<cancelled>\S+)")
CLICK = re.compile(r"ASRPROBE click player=\S+ top=(?P<top>\S+) slot=(?P<slot>\S+) action=\S+ item=(?P<item>\S+) "
                   r"cancelled=(?P<cancelled>\S+)")
TRADE = re.compile(r"ASRPROBE trade-select player=\S+ index=\S+ result=(?P<result>\S+) cancelled=(?P<cancelled>\S+)")
TELEPORTED = re.compile(r"ASRPROBE teleport player=\S+ world=\S+ result=\S+")
WORLD = re.compile(r"ASRPROBE world player=\S+ from=(?P<from>\S+) to=(?P<to>\S+)")

# The tag the retired provenance model wrote on structure loot (#52), on a stack today's plugin
# must judge by its material alone.
NATURAL_TAG = ('components:{"minecraft:custom_data":{PublicBukkitValues:{'
               '"antispeedrun:n6_asr_natural_origin":1b,"antispeedrun:n6_asr_finder":"AsrProbe"}}}')


def set_key(config_text, section, key, value):
    """config.yml with section.key (a direct child, two-space indent) set to value.

    The match never leaves the section: only indented and blank lines may come between the
    section header and the key, so a key of the same name in a later section is not touched.
    """
    pattern = rf"(?m)^({re.escape(section)}:\n(?:[ \t].*\n|\n)*?  {re.escape(key)}:[ \t]*)(\S+)"
    config_text, count = re.subn(pattern, lambda match: match.group(1) + value, config_text, count=1)
    if count != 1:
        raise ProbeError(f"{section}.{key} is not in config.yml")
    return config_text


def set_nether_gate(config_text, enabled):
    """config.yml with dimension-gates.nether.enabled set."""
    value = "true" if enabled else "false"
    config_text, count = re.subn(r"(?m)^(  nether:\n    enabled:[ \t]*)(true|false)\b",
                                 lambda match: match.group(1) + value, config_text)
    if count != 1:
        raise ProbeError("dimension-gates.nether.enabled is not in config.yml exactly once")
    return config_text


class GateProbes(Probes):

    # ------------------------------------------------------------------ helpers

    def progression(self, *advancements):
        """Revokes every advancement and personal credit, grants only those named, and drops
        cached evaluations.

        A possession-triggered key is answered from personal credits while
        item-progression.require-personal-credit is on (#215), so the matching credit is granted
        beside the advancement; the advancement grant is kept for every other key.
        """
        self.run(f"advancement revoke {self.player} everything")
        self.run(f"asr credit revoke {self.player} all")
        for key in advancements:
            self.run(f"advancement grant {self.player} only {key}")
            if key in CREDITED:
                self.run(f"asr credit grant {self.player} {CREDITED[key]}")
        # A revoke fires no event, so the plugin's progression cache only forgets on a reload.
        self.reload()
        self.client.request("messages")

    def environment(self):
        return self.where()["environment"]

    def heard(self, text):
        lines = self.client.request("messages")["lines"]
        return any(text in line for line in lines), lines

    def to_start(self):
        """Back in the gate box's overworld start position, on foot, facing the portal."""
        if self.where().get("vehicle", "none") != "none":
            self.client.request("dismount")
        self.tp(OVERWORLD, *START, 180, 0)
        if self.environment() != "NORMAL":
            raise ProbeError("the player could not be brought back to the overworld")

    def build(self):
        self.arena(OVERWORLD, GX, GY, GZ, half=12, height=12)
        # The portal: an obsidian frame with portal blocks lit inside it. The floor is obsidian too.
        self.run(f"execute in {OVERWORLD} run fill {GX - 1} {GY - 1} {PORTAL_Z} {GX + 2} {GY + 3} {PORTAL_Z} "
                 "minecraft:obsidian")
        self.run(f"execute in {OVERWORLD} run fill {GX} {GY} {PORTAL_Z} {GX + 1} {GY + 2} {PORTAL_Z} "
                 "minecraft:nether_portal[axis=x]")
        # A wall right behind it, so a player walking in stops inside the portal blocks.
        self.run(f"execute in {OVERWORLD} run fill {GX} {GY} {PORTAL_Z - 1} {GX + 1} {GY + 2} {PORTAL_Z - 1} "
                 "minecraft:obsidian")
        lx, ly, lz = LANDING
        self.arena(NETHER, lx, ly, lz, half=5, height=6)
        self.tp(OVERWORLD, *START, 180, 0)
        time.sleep(2)
        column = self.client.request("column", x=GX, z=PORTAL_Z, ymin=GY, ymax=GY + 2)
        if column["names"] != ["nether_portal"] * 3:
            raise ProbeError(f"the test portal did not form: {column['names']}")

    def walk_in(self):
        """Walks into the portal and stands in it for long enough to be carried through."""
        self.to_start()
        self.client.request("messages")
        mark = self.server.mark()
        self.client.request("walk", toward=[GX + 1.0, GY + 0.5, PORTAL_Z + 0.5], ms=1200, timeout=10)
        return mark

    def settle(self, mark, timeout=12):
        """Waits for the player to reach the Nether, or for timeout. Returns the final environment
        and the world changes in between: the probe plugin's lines where the platform reports one
        (Folia raises no PlayerChangedWorldEvent), otherwise "seen in the Nether" when a poll
        caught the player there."""
        deadline = time.monotonic() + timeout
        seen = False
        while time.monotonic() < deadline:
            if self.environment() == "NETHER":
                seen = True
                break
            time.sleep(1)
        # A Folia arrival can be returned a tick or two later; give the backstop time to act.
        time.sleep(4)
        moves = [f"{m.group('from')}->{m.group('to')}" for m in map(WORLD.search, self.server.lines_since(mark)) if m]
        if seen and not moves:
            moves = ["seen in the Nether"]
        return self.environment(), moves

    def portal_case(self, rule, label, expect_through, vehicle=False):
        if vehicle:
            self.to_start()
            self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:oak_boat]")
            self.run(f"execute in {OVERWORLD} run summon minecraft:oak_boat {START[0]} {GY} {START[2]} "
                     "{Rotation:[180f,0f]}")
            time.sleep(1.5)
            self.client.request("mount", name="boat")
            self.client.request("messages")
            mark = self.server.mark()
            self.client.request("drive", dx=0.0, dz=-0.2, steps=30, step_ms=100, timeout=20)
        else:
            mark = self.walk_in()
        final, moves = self.settle(mark)
        told, lines = self.heard(LOCKED_NETHER)
        detail = f"ended in {final}, world changes {moves}, told {lines[-3:] if lines else []}"
        if expect_through:
            self.results.check(rule, f"{label} reaches the Nether", final == "NETHER", detail)
        else:
            self.results.check(rule, f"{label} stays out of the Nether", final == "NORMAL", detail)
            self.results.check(rule, f"{label} is told the Nether is locked", told, detail)
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:oak_boat]")
        self.to_start()

    def item(self, material, extra="", at=None):
        """Summons a stack of material at the player's feet (or at), tagged so it can be found."""
        x, y, z = at or (START[0], GY, START[2])
        components = f",{extra}" if extra else ""
        self.run(f"execute in {OVERWORLD} run summon minecraft:item {x} {y} {z} "
                 f"{{Item:{{id:\"minecraft:{material}\",count:1{components}}},PickupDelay:0,Tags:[\"asrpi\"]}}")

    def on_ground(self, material):
        return self.exists(OVERWORLD, f"@e[type=minecraft:item,tag=asrpi,nbt={{Item:{{id:\"minecraft:{material}\"}}}}]")

    def pickup_case(self, rule, label, material, expect_taken, extra=""):
        self.to_start()
        self.run(f"clear {self.player}")
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")
        self.client.request("messages")
        mark = self.server.mark()
        self.item(material, extra)
        time.sleep(2.5)
        held = self.count(material)
        ground = self.on_ground(material)
        attempts = [m.group("cancelled") for m in map(PICKUP.search, self.server.lines_since(mark))
                    if m and m.group("item") == material.upper()]
        told, _ = self.heard(LOCKED_ITEM)
        detail = f"in inventory {held}, still on the ground {ground}, pickup attempts cancelled={attempts}, told={told}"
        if expect_taken:
            self.results.check(rule, f"{label}: picked up", held == 1 and not ground, detail)
        else:
            self.results.check(rule, f"{label}: refused and left on the ground",
                               held == 0 and ground and "true" in attempts, detail)
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")
        self.run(f"clear {self.player}")

    # ------------------------------------------------------------------ setup

    def prepare(self):
        for line in (f"gamemode survival {self.player}",
                     f"clear {self.player}",
                     f"effect give {self.player} minecraft:resistance infinite 4 true",
                     f"effect give {self.player} minecraft:fire_resistance infinite 0 true",
                     f"effect give {self.player} minecraft:saturation infinite 0 true",
                     f"effect give {self.player} minecraft:regeneration infinite 4 true",
                     f"execute in {OVERWORLD} run setworldspawn {SPAWN[0]} {SPAWN[1]} {SPAWN[2]}"):
            self.run(line)
        self.build()

    # ------------------------------------------------------------------ portals and vehicles

    def portals(self):
        rule = "dimension gate: portal travel"
        self.progression()
        self.portal_case(rule, "an ineligible player walking into a Nether portal", False)
        self.progression(NETHER_GATE)
        self.portal_case(rule, "control: an eligible player walking into a Nether portal", True)

    def vehicles(self):
        rule = "dimension gate: boat transit"
        self.progression()
        self.portal_case(rule, "an ineligible player riding a boat into a Nether portal", False, vehicle=True)
        self.progression(NETHER_GATE)
        self.portal_case(rule, "control: an eligible player riding a boat into a Nether portal", True,
                         vehicle=True)

    # ------------------------------------------------------------------ operators and bypasses

    def operator(self):
        rule = "operator without a bypass node"
        self.progression()
        self.run(f"op {self.player}")
        time.sleep(1)
        try:
            state = self.where()
            self.results.check(rule, "an operator holds no antispeedrun.bypass node",
                               state["op"] == "true" and state["bypass"] == "false"
                               and state["bypass-gates"] == "false" and state["bypass-items"] == "false",
                               f"op={state['op']} bypass={state['bypass']} gates={state['bypass-gates']} "
                               f"items={state['bypass-items']}")
            self.portal_case(rule, "an ineligible operator walking into a Nether portal", False)
            self.pickup_case(rule, "an ineligible operator walking over a diamond", "diamond", False)
        finally:
            self.run(f"deop {self.player}")
            time.sleep(1)

    def bypass(self):
        rule = "temporary bypass"
        self.progression()
        reply = self.server.query(f"asr bypass {self.player} 10m", r"Granted [^\r\n]*|[^\r\n]*refused[^\r\n]*")
        self.results.note(f"/asr bypass replied: {reply.group(0)}")
        self.portal_case(rule, "an ineligible player under /asr bypass walking into a Nether portal", True)
        self.pickup_case(rule, "an ineligible player under /asr bypass walking over a diamond", "diamond", True)
        self.server.query(f"asr bypass {self.player} off", r"\[AntiSpeedrun\][^\r\n]*")
        time.sleep(1)
        self.portal_case(rule, "the same player once the bypass is revoked", False)
        self.pickup_case(rule, "the same player, revoked, walking over a diamond", "diamond", False)
        # A grant runs out on its own, too. It must have been granted, or the refusal below
        # proves nothing.
        reply = self.server.query(f"asr bypass {self.player} 5s", r"\[AntiSpeedrun\][^\r\n]*")
        if "Granted" not in reply.group(0):
            raise ProbeError(f"the 5s bypass was not granted: {reply.group(0)}")
        time.sleep(8)
        self.pickup_case(rule, "the same player once a 5s bypass has expired, walking over a diamond",
                         "diamond", False)

    # ------------------------------------------------------------------ deliberate teleports (#135)

    def teleports(self):
        rule = "deliberate teleport (#135)"
        lx, ly, lz = LANDING
        self.to_start()
        overworld = self.where()["world"]

        def case(label, action, expect_stays):
            self.to_start()
            self.client.request("messages")
            mark = self.server.mark()
            action()
            time.sleep(1)
            final, moves = self.settle(mark, timeout=8)
            at = self.where()
            told, _ = self.heard(LOCKED_NETHER)
            moved = [m.group(0) for m in map(TELEPORTED.search, self.server.lines_since(mark)) if m]
            detail = (f"ended in {final} at ({at['x']}, {at['y']}, {at['z']}), world changes {moves}, "
                      f"plugin teleport {moved}, told the Nether is locked {told}")
            if expect_stays:
                self.results.check(rule, f"{label} is honoured: the player stays in the Nether",
                                   final == "NETHER", detail)
            else:
                # The backstop returns a refused arrival to the overworld spawn, which prepare()
                # set inside the gate box, well away from the start position.
                at_spawn = abs(float(at["x"]) - SPAWN[0] - 0.5) < 1.5 and abs(float(at["z"]) - SPAWN[2] - 0.5) < 1.5
                self.results.check(rule, f"{label} is judged on arrival: the player is returned to the "
                                         "overworld spawn and told why",
                                   final == "NORMAL" and at_spawn and told
                                   and any("result=true" in line for line in moved), detail)
            self.to_start()

        self.progression()
        case("the console's cross-dimension /tp of an ineligible player",
             lambda: self.run(f"execute in {NETHER} run tp {self.player} {lx + 0.5} {ly} {lz + 0.5}"), True)
        self.run(f"op {self.player}")
        time.sleep(1)
        try:
            case("an operator's own /execute in the_nether run tp @s",
                 lambda: self.client.request("chat", text=f"/execute in {NETHER} run tp @s {lx + 0.5} {ly} {lz + 0.5}"),
                 True)
        finally:
            self.run(f"deop {self.player}")
            time.sleep(1)
        # A plugin teleport: honoured on Paper, where it fires PlayerTeleportEvent; on Folia it fires
        # nothing and is judged when the player arrives (docs/administration.md section 7.4).
        folia = self.server.platform == "folia"
        nether_world = overworld + "_nether"
        case("another plugin's teleportAsync into the Nether" + (" on Folia" if folia else ""),
             lambda: self.run(f"asrprobe teleport {self.player} {nether_world} {lx + 0.5} {ly} {lz + 0.5}"),
             not folia)
        # The same teleport announced first with AntiSpeedrunPlugin#expectTeleport (#137), the API
        # a plugin calls so its deliberate teleport is honoured on Folia too. Paper needs no call.
        case("another plugin's teleportAsync announced with expectTeleport",
             lambda: self.run(f"asrprobe teleport {self.player} {nether_world} {lx + 0.5} {ly} {lz + 0.5} "
                              "expect"),
             True)

    # ------------------------------------------------------------------ item gates

    def pickups(self):
        rule = "item gate: pickup"
        self.progression()
        self.pickup_case(rule, "an ineligible player walking over a diamond", "diamond", False)
        self.pickup_case(rule, "an ineligible player walking over silk-touched deepslate diamond ore (C-11)",
                         "deepslate_diamond_ore", False)
        # C-01 under #52: the old natural-origin tag naming this very player is no exemption.
        self.pickup_case(rule, "an ineligible player walking over a diamond sword carrying the retired "
                               "natural-origin tag (C-01)", "diamond_sword", False, extra=NATURAL_TAG)
        self.progression(DIAMOND_TIER)
        self.pickup_case(rule, "control: an eligible player walking over a diamond", "diamond", True)

    def containers(self):
        rule = "item gate: container"
        cx, cy, cz = GX - 4, GY, GZ + 4

        def case(label, eligible):
            self.progression(*([DIAMOND_TIER] if eligible else []))
            self.run(f"clear {self.player}")
            self.run(f"execute in {OVERWORLD} run setblock {cx} {cy} {cz} minecraft:air")
            self.run(f"execute in {OVERWORLD} run setblock {cx} {cy} {cz} "
                     "minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}")
            self.tp(OVERWORLD, cx + 0.5, cy, cz + 2.5, 180, 30)
            mark = self.server.mark()
            opened = self.client.request("open_block", x=cx, y=cy, z=cz)
            if not opened["slots"]:
                raise ProbeError(f"the test chest opened empty: {opened}")
            after_shift = self.client.request("window_click", slot=0, button=0, mode=1)
            after_pick = self.client.request("window_click", slot=0, button=0, mode=0)
            self.client.request("close_window")
            time.sleep(0.5)
            held = self.count("diamond")
            left = sum(slot["count"] for slot in after_pick["slots"] if slot["name"] == "diamond")
            clicks = [m.group("cancelled") for m in map(CLICK.search, self.server.lines_since(mark)) if m]
            detail = (f"diamonds in inventory {held}, left in the chest after shift-click "
                      f"{after_shift['slots']}, after pickup click {left}, clicks cancelled={clicks}")
            if eligible:
                self.results.check(rule, f"control: {label}", held == 3, detail)
            else:
                self.results.check(rule, label, held == 0 and left == 3 and clicks and all(c == "true" for c in clicks),
                                   detail)
            self.run(f"execute in {OVERWORLD} run setblock {cx} {cy} {cz} minecraft:air")
            self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")

        case("an ineligible player cannot shift-click or pick a diamond out of a chest (C-02 opened)", False)
        case("an eligible player takes the diamonds out of the chest", True)

    def container_break(self):
        rule = "item gate: broken container"
        self.progression()
        for block, label in (("minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}", "a chest"),
                             ("minecraft:decorated_pot{item:{id:\"minecraft:diamond\",count:1}}", "a Decorated Pot")):
            bx, by, bz = GX + 4, GY, GZ + 4
            self.run(f"clear {self.player}")
            self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")
            self.run(f"execute in {OVERWORLD} run setblock {bx} {by} {bz} {block}")
            self.tp(OVERWORLD, bx + 0.5, by, bz - 1.5, 0, 40)
            mark = self.server.mark()
            self.client.request("hold", item=None)
            self.client.request("dig", x=bx, y=by, z=bz, timeout=30)
            # Stand on the spilled contents.
            self.tp(OVERWORLD, bx + 0.5, by, bz + 0.5)
            time.sleep(3)
            held = self.count("diamond")
            ground = self.near(OVERWORLD, bx + 0.5, by, bz + 0.5, 3, "item", "diamond") > 0
            attempts = [m.group("cancelled") for m in map(PICKUP.search, self.server.lines_since(mark))
                        if m and m.group("item") == "DIAMOND"]
            self.results.check(rule, f"an ineligible player who breaks {label} rather than opening it cannot "
                                     "pick up the diamonds it spills (C-02)",
                               held == 0 and ground and "true" in attempts,
                               f"diamonds in inventory {held}, on the ground {ground}, pickup attempts "
                               f"cancelled={attempts}")
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")

    def merchant(self):
        rule = "item gate: merchant"
        vx, vy, vz = GX - 6, GY, GZ - 2

        def case(label, eligible):
            self.progression(*([DIAMOND_TIER] if eligible else []))
            self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:villager,tag=asrp]")
            self.run(f"clear {self.player}")
            self.run(f"give {self.player} minecraft:emerald 4")
            self.run(f"execute in {OVERWORLD} run summon minecraft:villager {vx + 0.5} {vy} {vz + 0.5} "
                     "{NoAI:1b,Invulnerable:1b,Tags:[\"asrp\"],"
                     "VillagerData:{profession:\"minecraft:armorer\",level:5,type:\"minecraft:plains\"},"
                     "Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},"
                     "sell:{id:\"minecraft:diamond_chestplate\",count:1},maxUses:99,rewardExp:0b}]}}")
            self.tp(OVERWORLD, vx + 0.5, vy, vz + 2.5, 180, 10)
            time.sleep(1)
            mark = self.server.mark()
            offers = self.client.request("open_merchant", entity="villager")
            self.client.request("trade", index=0)
            self.client.request("close_window")
            time.sleep(0.5)
            held = self.count("diamond_chestplate")
            emeralds = self.count("emerald")
            selects = [m.group("cancelled") for m in map(TRADE.search, self.server.lines_since(mark)) if m]
            detail = (f"offers {offers['trades']}, chestplates in inventory {held}, emeralds left {emeralds}, "
                      f"trade selections cancelled={selects}")
            if eligible:
                self.results.check(rule, f"control: {label}", held >= 1, detail)
            else:
                self.results.check(rule, label, held == 0 and emeralds == 4, detail)
            self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:villager,tag=asrp]")
            self.run(f"clear {self.player}")

        case("an ineligible player cannot buy diamond armour from an Armorer (C-09)", False)
        case("an eligible player buys the diamond chestplate", True)

    def recall(self):
        rule = "item gate: no false positive"
        # A one-block cell, so a thrown stack or a death pile stays where the player stands.
        rx, rz = GX + 6, GZ + 2
        cell = (rx + 0.5, GY, rz + 0.5)
        self.run(f"execute in {OVERWORLD} run fill {rx - 1} {GY - 1} {rz - 1} {rx + 1} {GY + 2} {rz + 1} "
                 "minecraft:obsidian hollow")

        # The player's own manual drop comes back to them (drop-recall-enabled).
        self.progression()
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")
        self.run(f"clear {self.player}")
        self.tp(OVERWORLD, *cell)
        self.run(f"give {self.player} minecraft:diamond 1")
        time.sleep(1)
        given = self.count("diamond")
        mark = self.server.mark()
        tossed = self.client.request("toss", item="diamond", count=1)["left"]
        time.sleep(4)
        held = self.count("diamond")
        attempts = [m.group("cancelled") for m in map(PICKUP.search, self.server.lines_since(mark)) if m]
        self.results.check(rule, "an ineligible player re-collects the diamond they dropped",
                           given == 1 and tossed == 0 and held == 1 and "false" in attempts,
                           f"given {given}, left after the toss {tossed}, back in the inventory {held}, "
                           f"pickup attempts cancelled={attempts}")

        # Their own death pile comes back to them as well.
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")
        self.run(f"clear {self.player}")
        self.run(f"give {self.player} minecraft:diamond 2")
        time.sleep(1)
        self.tp(OVERWORLD, *cell)
        self.run(f"kill {self.player}")
        time.sleep(2)
        self.client.request("respawn", timeout=15)
        time.sleep(1)
        dropped = self.near(OVERWORLD, *cell, 2, "item", "diamond") > 0
        mark = self.server.mark()
        self.tp(OVERWORLD, *cell)
        time.sleep(3)
        held = self.count("diamond")
        attempts = [m.group("cancelled") for m in map(PICKUP.search, self.server.lines_since(mark)) if m]
        self.results.check(rule, "an ineligible player re-collects the diamonds from their own death pile",
                           dropped and held == 2,
                           f"death pile on the ground {dropped}, back in the inventory {held}, "
                           f"pickup attempts cancelled={attempts}")
        self.run(f"execute in {OVERWORLD} run fill {rx - 1} {GY} {rz - 1} {rx + 1} {GY + 2} {rz + 1} minecraft:air")

        # The mining loop: an eligible player mines diamond ore and keeps the diamond.
        self.progression(DIAMOND_TIER)
        self.run(f"clear {self.player}")
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")
        ox, oy, oz = GX + 4, GY, GZ - 3
        self.run(f"execute in {OVERWORLD} run setblock {ox} {oy} {oz} minecraft:diamond_ore")
        self.run(f"give {self.player} minecraft:iron_pickaxe")
        self.tp(OVERWORLD, ox + 0.5, oy, oz + 1.5, 180, 30)
        self.client.request("hold", item="iron_pickaxe")
        self.client.request("dig", x=ox, y=oy, z=oz, timeout=20)
        self.tp(OVERWORLD, ox + 0.5, oy, oz + 0.5)
        time.sleep(3)
        held = self.count("diamond")
        self.results.check(rule, "an eligible player mining diamond ore keeps the diamond", held >= 1,
                           f"diamonds in inventory {held}")
        self.run(f"clear {self.player}")
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")

    # ------------------------------------------------------------------ reload

    def reload_active(self):
        rule = "reload while players are active"
        self.progression()
        # A gate switched off by a reload lets the online player through at once, and back on
        # refuses them again, without a rejoin.
        self.reload(lambda text: set_nether_gate(text, False))
        self.portal_case(rule, "an ineligible player once a reload switches the Nether gate off", True)
        self.reload(lambda text: set_nether_gate(text, True))
        self.portal_case(rule, "the same player once a reload switches it back on", False)
        self.reload(lambda text: set_key(text, ITEMS, "enabled", "false"))
        self.pickup_case(rule, "an ineligible player once a reload switches item progression off",
                         "diamond", True)
        self.reload(lambda text: set_key(text, ITEMS, "enabled", "true"))
        self.pickup_case(rule, "the same player once a reload switches it back on", "diamond", False)

        # A burst of reloads while the player stands on a gated stack and walks about: every
        # pickup attempt in between is still refused, and nothing in the log breaks.
        self.to_start()
        self.run(f"clear {self.player}")
        mark = self.server.mark()
        self.item("diamond")
        for index in range(10):
            self.run("asr reload")
            if index % 3 == 0:
                self.client.request("walk", toward=[START[0], GY + 0.5, START[2] + 3], ms=200)
                self.client.request("walk", toward=[START[0], GY + 0.5, START[2] - 1], ms=200)
            time.sleep(0.2)
        self.tp(OVERWORLD, *START)
        time.sleep(3)
        lines = self.server.lines_since(mark)
        reloaded = sum(1 for line in lines if "Configuration reloaded" in line)
        attempts = [m.group("cancelled") for m in map(PICKUP.search, lines) if m and m.group("item") == "DIAMOND"]
        held = self.count("diamond")
        self.results.check(rule, "ten reloads in quick succession with the player on a gated stack: every "
                                 "pickup attempt is refused",
                           reloaded == 10 and held == 0 and attempts and all(a == "true" for a in attempts),
                           f"reloads applied {reloaded}, diamonds in inventory {held}, pickup attempts "
                           f"cancelled={attempts}")
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")


PROBES = ("portals", "vehicles", "operator", "bypass", "teleports", "pickups", "containers",
          "container_break", "merchant", "recall", "reload_active")


def run_all(server, client, player, results, only=PROBES):
    selected = [name for name in PROBES if name in only]
    if not selected:
        return
    probes = GateProbes(server, client, player, results)
    probes.prepare()
    for name in selected:
        try:
            getattr(probes, name)()
        except ProbeError as error:
            results.check(name, "probe completed", False, str(error))
            # A broken probe leaves unknown state behind: put the player and both gates back.
            try:
                probes.run(f"deop {player}")
                probes.run(f"asr bypass {player} off")
                probes.reload(lambda text: set_key(set_nether_gate(text, True), ITEMS, "enabled", "true"))
                probes.client.request("close_window")
                probes.to_start()
            except ProbeError:
                raise error
    # Leave the player as the anti-cheese probes expect to find them.
    probes.run(f"setworldspawn 0 -60 0")
    probes.run(f"clear {player}")
