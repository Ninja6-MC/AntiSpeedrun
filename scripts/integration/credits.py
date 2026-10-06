"""Gameplay probes for the personal-action credits (#210, #218): every credit earned by doing the
action on a real server, and refused when the item only arrives as a gift, from loot that does not
count, from a block someone placed, or through a helper.

Each case starts from a known record: every credit revoked through /asr credit, which is waited
for, then only the credits that keep the item gates out of the way granted back. The action is
done the way a player does it, by the scripted player or by a second scripted player, the helper,
where the case is about someone else's hand. The verdict is read from AntiSpeedrun's own credit
store through the probe plugin's /asrprobe credits: each credit's sources and whether it counts
under the live count-structure-loot, which is what the gates read. Where a case is about the gate
itself, /asr inspect says whether the Nether is open.

Loot cases use vanilla's own paths (a chest with a loot table opened for the first time, a trial
vault unlocked with a key, suspicious sand brushed), with the probe plugin replacing what the next
generation yields, so the result does not depend on a roll.
"""

import os
import re
import time

from gates import set_key
from harness import Client, ProbeError
from probes import OVERWORLD, Probes

RULE = "personal credit"
ITEMS = "item-progression"
HELPER = "AsrHelper"
CREDIT_REPLY = r"Granted |Revoked |already held |held none of |No player named"
# The credits that open the iron and diamond tiers, so the item gates stay out of the way of a
# case about another credit.
IRON_TIER = "mine-stone"
DIAMOND_TIER = "iron-tools"

# The box every fixture lives in.
CX, CY, CZ = 400, 100, 0
DIG = (CX, CY, CZ - 3)
CHEST = (CX - 4, CY, CZ + 4)
VAULT = (CX + 4, CY, CZ + 4)
FURNACE = (CX - 6, CY, CZ - 4)
# A furnace with a hopper under it collecting the output, and one with a hopper above feeding it.
COLLECTED = (CX - 8, CY + 1, CZ - 4)
FED = (CX - 10, CY, CZ - 4)
TABLE = (CX + 6, CY, CZ - 4)
CRAFTER = (CX + 9, CY, CZ - 4)
BLAZE = (CX + 4, CY, CZ - 6)
SAND = (CX, CY, CZ + 7)
PARK = (CX + 10.5, CY, CZ + 10.5)

FUEL = '{Items:[{Slot:1b,id:"minecraft:coal",count:16}]}'
LOOT = re.compile(r"ASRPROBE loot-generate entity=(?P<entity>\S+) table=(?P<table>\S+) plugin=(?P<plugin>\S+) "
                  r"holder=\S+ items=(?P<items>\S+) cancelled=(?P<cancelled>\S+)")
DISPENSED = re.compile(r"ASRPROBE loot-dispense player=(?P<player>\S+) block=\S+ items=(?P<items>\S+) "
                       r"cancelled=\S+")


def count_loot(enabled):
    value = "true" if enabled else "false"
    return lambda text: set_key(text, ITEMS, "count-structure-loot", value)


class CreditProbes(Probes):

    def __init__(self, server, client, player, results, helper):
        super().__init__(server, client, player, results)
        self.helper = helper

    # ------------------------------------------------------------------ helpers

    def credit(self, action, credit, player=None):
        """Runs /asr credit and waits for its reply: it applies on the async scheduler."""
        who = player or self.player
        reply = self.server.query(f"asr credit {action} {who} {credit}", CREDIT_REPLY, timeout=15)
        if reply.group(0) == "No player named":
            raise ProbeError(f"/asr credit {action} could not resolve {who}")

    def baseline(self, *granted, player=None):
        """Every credit revoked, then only those named granted back."""
        self.credit("revoke", "all", player)
        for credit in granted:
            self.credit("grant", credit, player)

    def credits(self, player=None):
        """{credit id: (sources, counts)} as AntiSpeedrun's store has it."""
        query = f"c{next(self._queries)}"
        match = self.server.query(f"asrprobe credits {player or self.player} {query}",
                                  rf"ASRPROBE credits query={query} (?P<rest>[^\r\n]*)")
        rest = match.group("rest")
        if not rest.startswith("count-loot="):
            raise ProbeError(f"/asrprobe credits answered {rest}")
        state = {}
        for pair in rest.split()[1:]:
            name, value = pair.split("=", 1)
            sources, counts = value.split("/")
            state[name] = (set() if sources == "none" else set(sources.split(",")), counts == "true")
        return state

    def expect(self, label, credit, held, source=None, counts=None, player=None, detail="", wait=4):
        """Checks one credit. A held credit is polled for up to wait seconds: a smelt or a kill
        records it on the region that owns the furnace or the blaze."""
        deadline = time.monotonic() + (wait if held else 0)
        while True:
            sources, counted = self.credits(player)[credit]
            if held:
                ok = (source in sources if source else bool(sources)) and (counts is None or counted == counts)
            else:
                ok = not sources
            if ok or time.monotonic() >= deadline:
                break
            time.sleep(0.5)
        evidence = f"{credit}: sources {sorted(sources) or 'none'}, counts {counted}"
        self.results.check(RULE, label, ok, f"{evidence}; {detail}" if detail else evidence)

    def contents(self, x, y, z):
        """{slot: (material, count)} of the container block at a point."""
        query = f"k{next(self._queries)}"
        match = self.server.query(f"asrprobe container {OVERWORLD} {x} {y} {z} {query}",
                                  rf"ASRPROBE container query={query} block=\S+ slots=(?P<slots>\S+)")
        slots = match.group("slots")
        if slots == "none":
            raise ProbeError(f"no container at {x} {y} {z}")
        found = {}
        if slots != "empty":
            for entry in slots.split(","):
                slot, material, count = entry.split(":")
                found[int(slot)] = (material, int(count))
        return found

    def ingots(self, x, y, z, slot=2):
        material, count = self.contents(x, y, z).get(slot, (None, 0))
        return count if material == "IRON_INGOT" else 0

    def wait_ingots(self, x, y, z, count, slot=2, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.ingots(x, y, z, slot) >= count:
                return
            time.sleep(0.5)
        raise ProbeError(f"the furnace at {x} {y} {z} never smelted {count} iron ingot(s): "
                         f"{self.contents(x, y, z)}")

    def nether_open(self):
        """Whether /asr inspect reports the Nether gate open for the player."""
        match = self.server.query(f"asr inspect {self.player}",
                                  r"[^\r\n]*The Nether[^\r\n]*", timeout=15)
        line = match.group(0)
        if "unlocked" not in line and "needs" not in line:
            raise ProbeError(f"/asr inspect gave no verdict on the Nether: {line}")
        return "unlocked" in line, line.split("The Nether", 1)[1].strip()

    def stand(self, at, yaw=180, pitch=30, player=None):
        x, _, z = at
        self.tp(OVERWORLD, x + 0.5, CY, z + 2.5, yaw, pitch, player=player)

    def tidy(self):
        self.run(f"clear {self.player}")
        self.run(f"clear {HELPER}")
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:item]")
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:experience_orb]")

    def setblock(self, at, block):
        x, y, z = at
        self.run(f"execute in {OVERWORLD} run setblock {x} {y} {z} minecraft:air")
        if block != "air":
            self.run(f"execute in {OVERWORLD} run setblock {x} {y} {z} minecraft:{block}")
        time.sleep(0.3)

    def block(self, at, client=None):
        x, y, z = at
        time.sleep(0.5)
        return (client or self.client).request("column", x=x, z=z, ymin=y, ymax=y)["names"][0]

    def mine(self, block, tool, at=DIG, ms=None):
        """Sets block natural (by command) and breaks it with tool, for ms when given. Returns what
        is left there."""
        self.tidy()
        self.setblock(at, block)
        give, _, _ = tool.partition("[")
        self.run(f"give {self.player} minecraft:{tool}")
        time.sleep(0.5)
        self.client.request("hold", item=give)
        self.stand((at[0], at[1], at[2] - 1))
        self.client.request("dig", x=at[0], y=at[1], z=at[2], ms=ms, timeout=30)
        return self.block(at)

    def place(self, client, player, block, at=DIG):
        """player places block at at by hand, standing on the floor in front of it."""
        self.run(f"clear {player}")
        self.setblock(at, "air")
        self.run(f"give {player} minecraft:{block}")
        time.sleep(0.5)
        client.request("hold", item=block)
        self.stand((at[0], at[1], at[2] - 1), player=player)
        client.request("place", x=at[0], y=at[1] - 1, z=at[2], face="up", timeout=15)
        return self.block(at, client)

    def arm(self, item, count=1):
        self.server.query(f"asrprobe loot {item} {count}", r"ASRPROBE loot armed=\S+")

    def disarm(self):
        self.server.query("asrprobe loot off", r"ASRPROBE loot armed=none")

    def loot_chest(self, item, at=CHEST, table="minecraft:chests/simple_dungeon"):
        self.setblock(at, f'chest{{LootTable:"{table}"}}')
        self.arm(item)

    def loot_lines(self, mark):
        return [m.groupdict() for m in map(LOOT.search, self.server.lines_since(mark)) if m]

    def open_and_close(self, client, player, at):
        self.stand(at, player=player)
        opened = client.request("open_block", x=at[0], y=at[1], z=at[2])
        client.request("close_window")
        return opened["slots"]

    def furnace(self, at):
        self.setblock(at, f"blast_furnace{FUEL}")

    def hand_load(self, client, player, at, item="raw_iron", count=1):
        self.run(f"give {player} minecraft:{item} {count}")
        time.sleep(0.5)
        self.stand(at, pitch=20 if at[1] == CY else -10, player=player)
        client.request("open_block", x=at[0], y=at[1], z=at[2])
        loaded = client.request("window_load", item=item, slot=0)
        client.request("close_window")
        if not any(slot["slot"] == 0 and slot["name"] == item for slot in loaded["slots"]):
            raise ProbeError(f"{player} could not load {item} into the furnace: {loaded['slots']}")

    def smelt(self, at=FURNACE):
        """The player hand-loads one raw iron into a fresh blast furnace and it smelts."""
        self.furnace(at)
        self.hand_load(self.client, self.player, at)
        self.wait_ingots(*at, 1)

    def park_helper(self):
        self.tp(OVERWORLD, *PARK, player=HELPER)

    # ------------------------------------------------------------------ setup

    def prepare(self):
        for line in (f"gamemode survival {self.player}",
                     f"gamemode survival {HELPER}",
                     f"effect give {self.player} minecraft:resistance infinite 4 true",
                     f"effect give {self.player} minecraft:saturation infinite 0 true",
                     f"effect give {HELPER} minecraft:resistance infinite 4 true",
                     f"effect give {HELPER} minecraft:saturation infinite 0 true"):
            self.run(line)
        self.arena(OVERWORLD, CX, CY, CZ, half=12, height=8)
        self.tidy()
        self.park_helper()
        self.stand(DIG)

    # ------------------------------------------------------------------ iron tier: mine_stone

    def credit_iron_tier(self):
        label = "iron tier (mine-stone)"
        self.baseline()
        self.tidy()
        self.run(f"give {self.player} minecraft:cobblestone 8")
        time.sleep(1)
        self.expect(f"{label}: gifted cobblestone earns nothing", "mine-stone", False,
                    detail=f"cobblestone in inventory {self.count('cobblestone')}")
        left = self.mine("stone", "wooden_pickaxe")
        self.expect(f"{label}: mining natural stone with a pickaxe earns it", "mine-stone", True, "action",
                    detail=f"block left {left}")
        self.baseline()
        placed = self.place(self.client, self.player, "cobblestone")
        if placed != "cobblestone":
            raise ProbeError(f"the player could not place cobblestone: {placed}")
        self.run(f"give {self.player} minecraft:wooden_pickaxe")
        time.sleep(0.5)
        self.client.request("hold", item="wooden_pickaxe")
        self.client.request("dig", x=DIG[0], y=DIG[1], z=DIG[2], timeout=30)
        left = self.block(DIG)
        self.expect(f"{label}: cobblestone the player placed, then mined, earns nothing", "mine-stone", False,
                    detail=f"placed {placed}, block left after mining {left}")

    # ------------------------------------------------------------------ Nether: smelt_iron

    def credit_nether(self):
        label = "Nether (smelt-iron)"
        try:
            self._nether(label)
        finally:
            self.disarm()

    def _nether(self, label):
        # An unopened chest holding raw iron, then one holding an ingot, with count-structure-loot on.
        for item in ("raw_iron", "iron_ingot"):
            self.baseline(IRON_TIER)
            self.tidy()
            self.loot_chest(item)
            mark = self.server.mark()
            slots = self.open_and_close(self.client, self.player, CHEST)
            self.expect(f"{label}: opening an unopened chest holding {item.replace('_', ' ')} stands in for "
                        "mining iron while count-structure-loot is on", "mined-iron", True, "loot", counts=True,
                        detail=f"chest held {slots}, loot events {self.loot_lines(mark)}")
        opened, line = self.nether_open()
        self.results.check(RULE, f"{label}: looted iron alone does not open the Nether; smelting is still "
                                 "required", not opened, line)
        self.smelt()
        opened, line = self.nether_open()
        self.results.check(RULE, f"{label}: looted iron and a hand-loaded smelt open the Nether", opened, line)

        # The same with count-structure-loot off: the record is kept, and never counts.
        self.reload(count_loot(False))
        try:
            self.baseline(IRON_TIER)
            self.tidy()
            self.loot_chest("raw_iron")
            self.open_and_close(self.client, self.player, CHEST)
            self.expect(f"{label}: the same chest with count-structure-loot off is recorded but does not "
                        "count", "mined-iron", True, "loot", counts=False)
            self.smelt()
            opened, line = self.nether_open()
            self.results.check(RULE, f"{label}: with count-structure-loot off, looted iron and a smelt leave "
                                     "the Nether shut", not opened, line)
        finally:
            self.reload(count_loot(True))

        # Mining natural iron ore and smelting hand-loaded raw iron.
        self.baseline(IRON_TIER)
        left = self.mine("iron_ore", "stone_pickaxe")
        self.expect(f"{label}: mining natural iron ore with a stone pickaxe earns the mined half", "mined-iron",
                    True, "action", detail=f"block left {left}")
        self.smelt()
        self.expect(f"{label}: smelting hand-loaded raw iron earns the smelted half", "smelted-iron", True,
                    "action")
        opened, line = self.nether_open()
        self.results.check(RULE, f"{label}: mined and smelted iron open the Nether", opened, line)

        # Smelting with no iron ore mined.
        self.baseline(IRON_TIER)
        self.tidy()
        self.smelt()
        self.expect(f"{label}: smelting without having mined iron ore earns only the smelted half",
                    "mined-iron", False)
        opened, line = self.nether_open()
        self.results.check(RULE, f"{label}: a smelt with no iron ore mined leaves the Nether shut", not opened,
                           line)

        # Iron ore a friend placed.
        self.baseline(IRON_TIER)
        self.baseline(IRON_TIER, player=HELPER)
        placed = self.place(self.helper, HELPER, "iron_ore")
        self.park_helper()
        if placed != "iron_ore":
            raise ProbeError(f"the helper could not place iron ore: {placed}")
        self.run(f"clear {self.player}")
        self.run(f"give {self.player} minecraft:stone_pickaxe")
        time.sleep(0.5)
        self.client.request("hold", item="stone_pickaxe")
        self.stand((DIG[0], DIG[1], DIG[2] - 1))
        self.client.request("dig", x=DIG[0], y=DIG[1], z=DIG[2], timeout=30)
        left = self.block(DIG)
        self.expect(f"{label}: iron ore a friend placed does not count as mined", "mined-iron", False,
                    detail=f"placed by {HELPER} {placed}, block left after mining {left}")

        # A helper-loaded furnace: the recipient takes the ingot and is credited nothing.
        self.baseline(IRON_TIER)
        self.baseline(IRON_TIER, player=HELPER)
        self.tidy()
        self.furnace(FURNACE)
        self.hand_load(self.helper, HELPER, FURNACE)
        self.park_helper()
        self.wait_ingots(*FURNACE, 1)
        self.stand(FURNACE)
        self.client.request("open_block", x=FURNACE[0], y=FURNACE[1], z=FURNACE[2])
        self.client.request("window_click", slot=2, button=0, mode=1)
        self.client.request("close_window")
        taken = self.count("iron_ingot")
        if taken < 1:
            raise ProbeError("the player could not take the helper's ingot out of the furnace")
        self.expect(f"{label}: an ingot a helper loaded and the recipient took out credits the recipient "
                    "nothing", "smelted-iron", False, detail=f"ingots taken {taken}")
        self.expect(f"{label}: control: the helper who loaded it is credited", "smelted-iron", True, "action",
                    player=HELPER)

        # Hopper-collected output still credits the loader.
        self.baseline(IRON_TIER)
        self.tidy()
        hx, hy, hz = COLLECTED
        self.setblock((hx, hy - 1, hz), "hopper")
        self.furnace(COLLECTED)
        self.hand_load(self.client, self.player, COLLECTED)
        self.stand(DIG)
        deadline = time.monotonic() + 30
        collected = {}
        while time.monotonic() < deadline:
            collected = self.contents(hx, hy - 1, hz)
            if any(material == "IRON_INGOT" for material, _ in collected.values()):
                break
            time.sleep(0.5)
        else:
            raise ProbeError(f"the hopper under the furnace never collected the ingot: {collected}")
        self.expect(f"{label}: an ingot a hopper collects still credits the player who loaded the furnace",
                    "smelted-iron", True, "action", detail=f"hopper holds {collected}")

        # Hopper-fed iron after a hand-load: only the hand-loaded count credits.
        self.baseline(IRON_TIER)
        self.tidy()
        fx, fy, fz = FED
        self.setblock((fx, fy + 1, fz), "air")
        self.furnace(FED)
        self.hand_load(self.client, self.player, FED)
        self.setblock((fx, fy + 1, fz), 'hopper[facing=down]{Items:[{Slot:0b,id:"minecraft:raw_iron",count:2}]}')
        self.stand(DIG)
        deadline = time.monotonic() + 30
        while not self.credits()["smelted-iron"][0]:
            if time.monotonic() > deadline:
                raise ProbeError("the hand-loaded raw iron never credited the loader")
            time.sleep(0.3)
        self.credit("revoke", "smelted-iron")
        at_revoke = self.ingots(*FED)
        if at_revoke != 1:
            raise ProbeError(f"the revoke landed after {at_revoke} ingots, not between the first and second")
        self.wait_ingots(*FED, 3)
        self.expect(f"{label}: hopper-fed iron after a one-item hand-load credits nothing beyond the first "
                    "ingot", "smelted-iron", False,
                    detail=f"ingots when the first credit was revoked {at_revoke}, after {self.ingots(*FED)}")
        self.setblock((fx, fy + 1, fz), "air")

    # ------------------------------------------------------------------ iron pickaxe: iron_tools

    def credit_iron_pickaxe(self):
        label = "iron pickaxe (iron-tools)"
        try:
            self._iron_pickaxe(label)
        finally:
            self.disarm()

    def _iron_pickaxe(self, label):
        self.baseline(IRON_TIER)
        self.tidy()
        self.setblock(TABLE, "crafting_table")
        self.run(f"give {self.player} minecraft:iron_ingot 3")
        self.run(f"give {self.player} minecraft:stick 2")
        time.sleep(0.5)
        self.stand(TABLE)
        crafted = self.client.request("craft", item="iron_pickaxe", table=list(TABLE), timeout=30)["count"]
        if crafted < 1:
            raise ProbeError("the player could not craft an iron pickaxe")
        self.expect(f"{label}: crafting one in a crafting table earns it", "iron-tools", True, "action",
                    detail=f"iron pickaxes in inventory {crafted}")

        self.baseline(IRON_TIER)
        self.tidy()
        self.run(f"give {self.player} minecraft:iron_pickaxe")
        time.sleep(1)
        self.expect(f"{label}: a gifted iron pickaxe earns nothing", "iron-tools", False)

        self.baseline(IRON_TIER)
        self.tidy()
        self.loot_chest("iron_pickaxe")
        mark = self.server.mark()
        self.stand(CHEST)
        opened = self.client.request("open_block", x=CHEST[0], y=CHEST[1], z=CHEST[2])
        for slot in opened["slots"]:
            self.client.request("window_click", slot=slot["slot"], button=0, mode=1)
        self.client.request("close_window")
        self.expect(f"{label}: a looted iron pickaxe earns nothing", "iron-tools", False,
                    detail=f"chest held {opened['slots']}, pickaxes taken {self.count('iron_pickaxe')}, "
                           f"loot events {self.loot_lines(mark)}")

        # A Crafter block crafts one beside the player.
        self.baseline(IRON_TIER)
        self.tidy()
        x, y, z = CRAFTER
        self.setblock((x + 1, y, z), "air")
        self.setblock(CRAFTER, 'crafter[orientation=up_north]{Items:['
                               '{Slot:0b,id:"minecraft:iron_ingot",count:1},{Slot:1b,id:"minecraft:iron_ingot",count:1},'
                               '{Slot:2b,id:"minecraft:iron_ingot",count:1},{Slot:4b,id:"minecraft:stick",count:1},'
                               '{Slot:7b,id:"minecraft:stick",count:1}]}')
        self.stand(CRAFTER)
        self.setblock((x + 1, y, z), "redstone_block")
        time.sleep(2)
        made = self.near(OVERWORLD, x + 0.5, y + 1, z + 0.5, 3, "item", "iron_pickaxe")
        if made < 1:
            raise ProbeError(f"the Crafter made no iron pickaxe: {self.contents(*CRAFTER)}")
        self.expect(f"{label}: one a Crafter block makes earns nothing", "iron-tools", False,
                    detail=f"pickaxes the Crafter ejected {made}")
        self.setblock((x + 1, y, z), "air")
        self.setblock(CRAFTER, "air")

    # ------------------------------------------------------------------ stone pickaxe: upgrade_tools

    def credit_stone_pickaxe(self):
        label = "stone pickaxe (upgrade-tools, hardcore Nether gate)"
        self.baseline()
        self.tidy()
        self.setblock(TABLE, "crafting_table")
        self.run(f"give {self.player} minecraft:cobblestone 3")
        self.run(f"give {self.player} minecraft:stick 2")
        time.sleep(0.5)
        self.stand(TABLE)
        crafted = self.client.request("craft", item="stone_pickaxe", table=list(TABLE), timeout=30)["count"]
        if crafted < 1:
            raise ProbeError("the player could not craft a stone pickaxe")
        self.expect(f"{label}: crafting one in a crafting table earns it", "upgrade-tools", True, "action",
                    detail=f"stone pickaxes in inventory {crafted}")
        self.baseline()
        self.tidy()
        self.run(f"give {self.player} minecraft:stone_pickaxe")
        time.sleep(1)
        self.expect(f"{label}: a gifted stone pickaxe earns nothing", "upgrade-tools", False)

    # ------------------------------------------------------------------ diamond: mine_diamond

    def credit_diamond(self):
        label = "diamond (mine-diamond)"
        try:
            self._diamond(label)
        finally:
            self.disarm()
            self.run(f"gamemode survival {self.player}")

    def _diamond(self, label):
        # Mining: only a break that yields a diamond earns it, as vanilla grants the advancement on
        # obtaining one.
        for tool, earns, what in (("iron_pickaxe", True, "an iron pickaxe"),
                                  ("stone_pickaxe", False, "a stone pickaxe, which drops nothing"),
                                  ('iron_pickaxe[enchantments={"minecraft:silk_touch":1}]', False,
                                   "a silk touch pickaxe, which drops the ore")):
            self.baseline(IRON_TIER, DIAMOND_TIER)
            left = self.mine("diamond_ore", tool, ms=2000 if "enchantments" in tool else None)
            if left == "diamond_ore":
                raise ProbeError(f"the player could not break diamond ore with {what}")
            time.sleep(1)
            dropped = {item: self.near(OVERWORLD, DIG[0] + 0.5, DIG[1], DIG[2] + 0.5, 3, "item", item)
                       + self.count(item) for item in ("diamond", "diamond_ore")}
            self.expect(f"{label}: mining natural diamond ore with {what} "
                        f"{'earns it' if earns else 'earns nothing'}", "mine-diamond", earns,
                        "action" if earns else None, detail=f"block left {left}, yielded {dropped}")

        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.run(f"gamemode creative {self.player}")
        time.sleep(1)
        try:
            left = self.mine("diamond_ore", "iron_pickaxe")
        finally:
            self.run(f"gamemode survival {self.player}")
            time.sleep(1)
        if left == "diamond_ore":
            raise ProbeError("the player could not break diamond ore in creative mode")
        self.expect(f"{label}: breaking natural diamond ore in creative mode earns nothing", "mine-diamond", False,
                    detail=f"block left {left}")

        # A break whose drops another plugin disabled, as a protection plugin may.
        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.server.query(f"asrprobe nodrops {self.player}", r"ASRPROBE nodrops armed=\S+")
        mark = self.server.mark()
        left = self.mine("diamond_ore", "iron_pickaxe")
        disabled = self.server.lines_since(mark, r"ASRPROBE nodrops player=")
        if left == "diamond_ore" or not disabled:
            raise ProbeError(f"the drops-disabled break did not happen: block left {left}, {disabled}")
        time.sleep(1)
        dropped = self.near(OVERWORLD, DIG[0] + 0.5, DIG[1], DIG[2] + 0.5, 3, "item", "diamond") + self.count("diamond")
        self.expect(f"{label}: breaking natural diamond ore with an iron pickaxe while drops are disabled earns "
                    "nothing", "mine-diamond", False, detail=f"block left {left}, diamonds yielded {dropped}")

        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.tidy()
        self.run(f"give {self.player} minecraft:diamond 3")
        time.sleep(1)
        self.expect(f"{label}: gifted diamonds earn nothing", "mine-diamond", False,
                    detail=f"diamonds in inventory {self.count('diamond')}")

        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.tidy()
        placed = self.place(self.client, self.player, "diamond_ore")
        if placed != "diamond_ore":
            raise ProbeError(f"the player could not place diamond ore: {placed}")
        self.run(f"give {self.player} minecraft:iron_pickaxe")
        time.sleep(0.5)
        self.client.request("hold", item="iron_pickaxe")
        self.client.request("dig", x=DIG[0], y=DIG[1], z=DIG[2], timeout=30)
        left = self.block(DIG)
        self.expect(f"{label}: diamond ore the player placed, then mined, earns nothing", "mine-diamond", False,
                    detail=f"block left {left}")

        # Loot: an unopened chest, then a trial vault.
        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.tidy()
        self.loot_chest("diamond")
        mark = self.server.mark()
        slots = self.open_and_close(self.client, self.player, CHEST)
        self.expect(f"{label}: opening an unopened chest holding a diamond earns it while count-structure-loot "
                    "is on", "mine-diamond", True, "loot", counts=True,
                    detail=f"chest held {slots}, loot events {self.loot_lines(mark)}")
        self.reload(count_loot(False))
        try:
            self.expect(f"{label}: the same record does not count once count-structure-loot is off",
                        "mine-diamond", True, "loot", counts=False)
        finally:
            self.reload(count_loot(True))
        self.expect(f"{label}: and counts again once it is back on", "mine-diamond", True, "loot", counts=True)

        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.tidy()
        self.setblock(VAULT, "vault")
        self.run(f"give {self.player} minecraft:trial_key")
        time.sleep(0.5)
        self.client.request("hold", item="trial_key")
        self.stand(VAULT, pitch=20)
        # The vault turns active once a player is in range, checked each second.
        time.sleep(2.5)
        self.arm("diamond")
        mark = self.server.mark()
        self.client.request("use_block", x=VAULT[0], y=VAULT[1], z=VAULT[2], face="south")
        time.sleep(2)
        dispensed = [m.groupdict() for m in map(DISPENSED.search, self.server.lines_since(mark)) if m]
        if not dispensed:
            raise ProbeError(f"the vault dispensed nothing; key left {self.count('trial_key')}")
        self.expect(f"{label}: unlocking a trial vault that yields a diamond earns it", "mine-diamond", True,
                    "loot", counts=True, detail=f"vault events {dispensed}")
        self.setblock(VAULT, "air")

        # A friend opens the chest first and the player takes the diamond from it.
        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.baseline(player=HELPER)
        self.tidy()
        self.loot_chest("diamond")
        mark = self.server.mark()
        self.open_and_close(self.helper, HELPER, CHEST)
        self.park_helper()
        self.stand(CHEST)
        opened = self.client.request("open_block", x=CHEST[0], y=CHEST[1], z=CHEST[2])
        for slot in opened["slots"]:
            self.client.request("window_click", slot=slot["slot"], button=0, mode=1)
        self.client.request("close_window")
        taken = self.count("diamond")
        if taken < 1:
            raise ProbeError(f"the player could not take the diamond the helper's chest held: {opened['slots']}")
        self.expect(f"{label}: diamonds taken from a chest a friend opened first earn nothing", "mine-diamond",
                    False, detail=f"diamonds taken {taken}, loot events {self.loot_lines(mark)}")
        self.expect(f"{label}: control: the friend who opened it is credited", "mine-diamond", True, "loot",
                    player=HELPER)

        # Recorded in the Amendment of docs/provenance-model.md: breaking an unopened loot chest.
        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.tidy()
        self.loot_chest("diamond")
        self.run(f"give {self.player} minecraft:iron_axe")
        time.sleep(0.5)
        self.client.request("hold", item="iron_axe")
        self.stand(CHEST)
        mark = self.server.mark()
        self.client.request("dig", x=CHEST[0], y=CHEST[1], z=CHEST[2], timeout=30)
        time.sleep(1)
        events = self.loot_lines(mark)
        # Counted on the ground and in the inventory: the player may already have picked it up.
        spilled = (self.near(OVERWORLD, CHEST[0] + 0.5, CHEST[1], CHEST[2] + 0.5, 3, "item", "diamond")
                   + self.count("diamond"))
        self.expect(f"{label}: breaking an unopened chest that holds a diamond earns nothing", "mine-diamond",
                    False, detail=f"loot events {events}, diamonds spilled {spilled}")
        self.results.note(f"breaking an unopened loot chest: loot events {events}, diamonds spilled {spilled}")

        # Recorded there too: brushing desert-pyramid suspicious sand.
        self.baseline(IRON_TIER, DIAMOND_TIER)
        self.tidy()
        self.setblock(SAND, 'suspicious_sand{LootTable:"minecraft:archaeology/desert_pyramid"}')
        self.run(f"give {self.player} minecraft:brush")
        time.sleep(0.5)
        self.client.request("hold", item="brush")
        self.tp(OVERWORLD, SAND[0] + 0.5, CY, SAND[2] + 1.6, 180, 50)
        before = self.client.request("inventory")["items"]
        self.arm("diamond")
        mark = self.server.mark()
        brushed = self.client.request("brush", x=SAND[0], y=SAND[1], z=SAND[2], face="up", ms=9000, timeout=20)
        time.sleep(1)
        self.disarm()
        events = self.loot_lines(mark)
        if brushed["block"] == "suspicious_sand":
            raise ProbeError(f"the brushing never finished: loot events {events}")
        # The roll is seen apart from the event: what was brushed out lies on the ground or, the
        # player standing beside it, is in the inventory. The armed diamond is consumed only by a
        # loot event, so a brushed item other than a diamond came from the table with none fired.
        found = self.near(OVERWORLD, SAND[0] + 0.5, SAND[1], SAND[2] + 0.5, 3, "item")
        after = self.client.request("inventory")["items"]
        gained = {name: count - before.get(name, 0) for name, count in after.items() if count > before.get(name, 0)}
        pickups = [line.split("ASRPROBE ", 1)[1] for line in self.server.lines_since(mark, r"ASRPROBE pickup ")]
        if not found and not gained:
            raise ProbeError(f"brushing yielded nothing, so the loot table never rolled: loot events {events}")
        named = [event for event in events if event["entity"] == self.player]
        detail = (f"block left {brushed['block']}, loot events {events}, items on the ground {found}, "
                  f"gained in the inventory {gained}, pickups {pickups}")
        self.results.check(RULE, f"{label}: brushing desert-pyramid suspicious sand fires no loot event naming "
                                 "the player", not named, detail)
        self.expect(f"{label}: brushing desert-pyramid suspicious sand earns nothing", "mine-diamond", False,
                    detail=detail)
        self.results.note(f"brushing suspicious sand: {detail}")

    # ------------------------------------------------------------------ blaze: obtain_blaze_rod

    def credit_blaze(self):
        label = "blaze (obtain-blaze-rod)"
        self.baseline(IRON_TIER)
        self.tidy()
        self.run(f"give {self.player} minecraft:blaze_rod 2")
        time.sleep(1)
        self.expect(f"{label}: gifted blaze rods earn nothing", "obtain-blaze-rod", False,
                    detail=f"blaze rods in inventory {self.count('blaze_rod')}")
        x, y, z = BLAZE
        self.run(f"execute in {OVERWORLD} run kill @e[type=minecraft:blaze,tag=asrp]")
        self.run(f"execute in {OVERWORLD} run summon minecraft:blaze {x + 0.5} {y} {z + 0.5} "
                 "{NoAI:1b,Health:1f,Tags:[\"asrp\"]}")
        self.tp(OVERWORLD, x + 0.5, y, z + 2.5, 180, 0)
        self.client.request("hold", item=None)
        self.client.request("attack", entity="blaze")
        time.sleep(1)
        alive = self.health("@e[type=minecraft:blaze,tag=asrp]")
        if alive is not None:
            raise ProbeError(f"the blaze survived the hit with {alive} health")
        self.expect(f"{label}: killing a blaze earns it", "obtain-blaze-rod", True, "action")

    # ------------------------------------------------------------------ NPCs

    def credit_npc(self):
        label = "NPC players"
        self.baseline()
        reply = self.server.query(f"asrprobe npc {self.player} on", r"ASRPROBE npc player=\S+ \S+")
        try:
            if "npc=true" not in reply.group(0):
                raise ProbeError(f"the NPC metadata was not set: {reply.group(0)}")
            left = self.mine("stone", "wooden_pickaxe")
            self.expect(f"{label}: a player entity carrying NPC metadata mining natural stone earns nothing",
                        "mine-stone", False, detail=f"block left {left}")
        finally:
            self.server.query(f"asrprobe npc {self.player} off", r"ASRPROBE npc player=\S+ \S+")
        left = self.mine("stone", "wooden_pickaxe")
        self.expect(f"{label}: control: the same player without it earns it", "mine-stone", True, "action",
                    detail=f"block left {left}")


PROBES = ("credit_iron_tier", "credit_nether", "credit_iron_pickaxe", "credit_stone_pickaxe", "credit_diamond",
          "credit_blaze", "credit_npc")


def helper_client(client, log_path):
    """A second scripted player speaking the same protocol, for the cases about someone else."""
    args = client.args

    def value(name):
        return args[args.index(f"--{name}") + 1]

    return Client(value("host"), int(value("port")), HELPER, value("version"), log_path, node=args[0])


def run_all(server, client, player, results, only=PROBES):
    selected = [name for name in PROBES if name in only]
    if not selected:
        return
    helper = helper_client(client, os.path.join(os.path.dirname(client.log_path), "helper.log"))
    mark = server.mark()
    with helper:
        helper.wait_event("spawn", 120)
        server.wait_for(rf"ASRPROBE join name={HELPER}", 30, since=mark)
        probes = CreditProbes(server, client, player, results, helper)
        probes.prepare()
        for name in selected:
            try:
                getattr(probes, name)()
            except ProbeError as error:
                results.check(name, "probe completed", False, str(error))
                # A broken probe leaves unknown state behind: put both players and the config back.
                try:
                    probes.run(f"gamemode survival {player}")
                    probes.server.query(f"asrprobe npc {player} off", r"ASRPROBE npc player=\S+ \S+")
                    probes.disarm()
                    probes.reload(count_loot(True))
                    probes.client.request("close_window")
                    probes.helper.request("close_window")
                    probes.park_helper()
                except ProbeError:
                    raise error
        probes.baseline(player=HELPER)
        probes.baseline()
        probes.tidy()
        helper.request("quit", timeout=10)
