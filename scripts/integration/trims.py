"""Gameplay probes for the structure trim unlocks (#221): which actions open the Ancient City,
Bastion and End City trims.

The verdict is read through the natural loot lock, trim-progression.gate-natural-trim-chests,
which the probes switch on: an unopened chest whose loot the probe plugin replaces with one gated
smithing template keeps it for a player who has explored the template's structure and loses it for
one who has not. The lock judges the looter through the same evaluation the smithing, wearing and
duplication locks use, so a kept template means every Ancient City trim is open to that player.

The overworld is a flat Deep Dark world (harness.py), so Ancient Cities generate in it. The one
the probe enters is found from the world seed with the vanilla placement rule for the
minecraft:ancient_cities structure set, then confirmed through Paper's structure API by the probe
plugin's /asrprobe enter, which moves the player into one of its pieces and reports what the API
returned.
"""

import re
import time

from credits import CreditProbes, count_loot
from gates import set_key
from harness import ProbeError
from probes import OVERWORLD

RULE = "trim unlock"
TRIMS = "trim-progression"
SNEAK_ADVANCEMENT = "minecraft:adventure/avoid_vibration"
BASTION_ADVANCEMENT = "minecraft:nether/find_bastion"
END_CITY_ADVANCEMENT = "minecraft:end/find_end_city"

# The box every fixture lives in, beside the credit probes' one and at the same height.
TX, TY, TZ = 600, 100, 0
SENSOR = (TX, TY, TZ + 2)
CHEST = (TX - 4, TY, TZ + 4)
# Standing on the flat world's top layer of stone.
FLOOR = -60

# The minecraft:ancient_cities structure set: random spread, spacing 24, separation 8.
CITY_SPACING, CITY_SEPARATION, CITY_SALT = 24, 8, 20083232
# The placement cell the probe uses, far from every other probe's fixtures.
CITY_CELL = (5, 5)
SEED = re.compile(r"Seed: \[(?P<seed>-?\d+)\]")
FOUND = re.compile(r"structures=(?P<structures>\d+) pieces=(?P<pieces>\d+) bounds=(?P<bounds>\S+) "
                   r"lowest=(?P<lowest>\S+)(?: piece=(?P<piece>\S+))?(?: to=(?P<to>\S+) result=(?P<result>\S+))?")

_MASK = (1 << 48) - 1


def _long(value):
    """value wrapped to a Java long."""
    value &= (1 << 64) - 1
    return value - (1 << 64) if value >= 1 << 63 else value


class _LegacyRandom:
    """java.util.Random, which vanilla's LegacyRandomSource reproduces."""

    def __init__(self, seed):
        self.state = (seed ^ 0x5DEECE66D) & _MASK

    def next(self, bits):
        self.state = (self.state * 0x5DEECE66D + 0xB) & _MASK
        return self.state >> (48 - bits)

    def next_int(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            value = bits % bound
            if bits - value + (bound - 1) < 1 << 31:
                return value


def city_chunk(seed, cell_x, cell_z):
    """The chunk the Ancient City of one placement cell starts in, as vanilla's
    RandomSpreadStructurePlacement.getPotentialStructureChunk computes it."""
    random = _LegacyRandom(_long(cell_x * 341873128712 + cell_z * 132897987541 + seed + CITY_SALT))
    spread = CITY_SPACING - CITY_SEPARATION
    dx = random.next_int(spread)
    dz = random.next_int(spread)
    return cell_x * CITY_SPACING + dx, cell_z * CITY_SPACING + dz


def gated(text):
    return set_key(text, TRIMS, "gate-natural-trim-chests", "true")


def ungated(text):
    return set_key(text, TRIMS, "gate-natural-trim-chests", "false")


class TrimProbes(CreditProbes):

    def __init__(self, server, client, player, results):
        super().__init__(server, client, player, results, helper=None)

    # ------------------------------------------------------------------ helpers

    def advancements(self, *granted):
        """Every advancement revoked, then only those named granted, and cached evaluations dropped."""
        self.run(f"advancement revoke {self.player} everything")
        for key in granted:
            self.run(f"advancement grant {self.player} only {key}")
        # A revoke fires no event, so the plugin's progression cache only forgets on a reload.
        self.reload()

    def done(self, key):
        query = f"a{next(self._queries)}"
        match = self.server.query(f"asrprobe advancement {self.player} {key} {query}",
                                  rf"ASRPROBE advancement query={query} (?:unknown|key=\S+ done=(?P<done>\S+))")
        if match.group("done") is None:
            raise ProbeError(f"the server knows no advancement {key}")
        return match.group("done") == "true"

    def template_kept(self, template):
        """Opens an unopened chest whose loot is one template. Whether the player was left it, and
        what the server reported."""
        self.tp(OVERWORLD, TX + 0.5, TY, TZ + 0.5)
        self.loot_chest(template, at=CHEST)
        mark = self.server.mark()
        slots = self.open_and_close(self.client, self.player, CHEST)
        time.sleep(0.5)
        held = self.contents(*CHEST)
        kept = any(material == template.upper() for material, _ in held.values())
        return kept, f"chest held {slots}; container {held}; loot events {self.loot_lines(mark)}"

    def expect_template(self, label, template, kept):
        actual, evidence = self.template_kept(template)
        self.results.check(RULE, label, actual == kept, evidence)

    def city(self):
        """The block x and z of the probe's Ancient City start chunk, its chunks force-loaded."""
        seed = int(self.server.query("seed", SEED.pattern).group("seed"))
        chunk_x, chunk_z = city_chunk(seed, *CITY_CELL)
        x, z = chunk_x * 16 + 8, chunk_z * 16 + 8
        self.run(f"execute in {OVERWORLD} run forceload add {x - 16} {z - 16} {x + 16} {z + 16}")
        time.sleep(3)
        return x, z

    def structure(self, verb, x, z):
        """What the structure API reports for the Ancient City in the chunk at x z; with verb enter,
        the player is also moved into its largest piece."""
        query = f"s{next(self._queries)}"
        who = f"{self.player} " if verb == "enter" else ""
        match = self.server.query(f"asrprobe {verb} {who}{OVERWORLD} minecraft:ancient_city {x} {z} {query}",
                                  rf"ASRPROBE {verb} query={query} [^\r\n]*", timeout=60)
        found = FOUND.search(match.group(0))
        if found is None or found.group("structures") == "0":
            raise ProbeError(f"the structure API found no Ancient City at {x} {z}: {match.group(0)}")
        return found.groupdict()

    # ------------------------------------------------------------------ setup

    def prepare(self):
        for line in (f"gamemode survival {self.player}",
                     f"effect give {self.player} minecraft:resistance infinite 4 true",
                     f"effect give {self.player} minecraft:saturation infinite 0 true",
                     f"effect give {self.player} minecraft:night_vision infinite 0 true"):
            self.run(line)
        self.arena(OVERWORLD, TX, TY, TZ, half=12, height=8)
        self.run(f"clear {self.player}")
        self.reload(gated)

    # ------------------------------------------------------------------ Ancient City

    def trim_ancient_city(self):
        label = "Ancient City trims"
        try:
            self._ancient_city(label)
        finally:
            self.disarm()
            self.reload(count_loot(True))

    def _ancient_city(self, label):
        # The gap: a Sculk Sensor set down in the player's box, nowhere near an Ancient City, and the
        # player sneaking past it, which is all Sneak 100 asks. A crouching player on the ground makes
        # no vibrations at all, so the player hops as it goes, as a player earning it does.
        self.advancements()
        self.setblock(SENSOR, "sculk_sensor")
        self.tp(OVERWORLD, TX - 4.5, TY, TZ + 0.5, -90, 0)
        walked = self.client.request("walk", toward=[TX + 5, TY + 1.6, TZ + 0.5], ms=4000, sneak=True,
                                     jump=True, timeout=30)
        self.setblock(SENSOR, "air")
        if not self.done(SNEAK_ADVANCEMENT):
            raise ProbeError(f"sneaking past the Sculk Sensor did not earn adventure/avoid_vibration: walked to "
                             f"{walked.get('position')}, server has {self.where()}")
        self.expect_template(f"{label}: sneaking past a Sculk Sensor placed outside any Ancient City "
                             "(adventure/avoid_vibration earned) does not unlock them",
                             "silence_armor_trim_smithing_template", False)
        self.advancements(SNEAK_ADVANCEMENT)
        self.expect_template(f"{label}: adventure/avoid_vibration granted by command does not unlock them",
                             "ward_armor_trim_smithing_template", False)
        self.advancements()

        # Deep Dark depth in the overworld, but below the city rather than in it.
        x, z = self.city()
        found = self.structure("structure", x, z)
        if FLOOR >= int(found["lowest"]):
            raise ProbeError(f"the city's pieces reach the world floor, leaving no point under them: {found}")
        self.tp(OVERWORLD, x + 0.5, FLOOR, z + 0.5)
        time.sleep(4)
        self.expect_template(f"{label}: standing at Deep Dark depth under an Ancient City, outside its pieces, "
                             "does not unlock them", "silence_armor_trim_smithing_template", False)

        # Loot from an Ancient City chest the player opens first, while count-structure-loot is on.
        self.tp(OVERWORLD, TX + 0.5, TY, TZ + 0.5)
        self.loot_chest("echo_shard", at=CHEST, table="minecraft:chests/ancient_city")
        mark = self.server.mark()
        self.open_and_close(self.client, self.player, CHEST)
        lines = self.loot_lines(mark)
        self.expect_template(f"{label}: generating an Ancient City chest's loot unlocks them while "
                             "count-structure-loot is on", "silence_armor_trim_smithing_template", True)
        self.results.note(f"{label}: the Ancient City chest's loot events were {lines}")
        self.reload(count_loot(False))
        self.expect_template(f"{label}: the same loot does not count once count-structure-loot is off",
                             "silence_armor_trim_smithing_template", False)

        # Walking into an Ancient City, still with count-structure-loot off.
        entered = self.structure("enter", x, z)
        if entered["result"] != "true":
            raise ProbeError(f"the player could not be moved into the Ancient City: {entered}")
        self.results.note(f"{label}: the structure API found {entered['structures']} Ancient City with "
                          f"{entered['pieces']} pieces, bounds {entered['bounds']}; the player was moved to "
                          f"{entered['to']} in piece {entered['piece']}")
        time.sleep(4)
        self.expect_template(f"{label}: walking into an Ancient City unlocks them",
                             "silence_armor_trim_smithing_template", True)
        self.expect_template(f"{label}: and Ward with them", "ward_armor_trim_smithing_template", True)

    # ------------------------------------------------------------------ Bastion and End City

    def trim_bastion_end_city(self):
        label = "Bastion and End City trims"
        try:
            self.advancements()
            self.expect_template(f"{label}: without nether/find_bastion the Snout template is removed",
                                 "snout_armor_trim_smithing_template", False)
            self.expect_template(f"{label}: without end/find_end_city the Spire template is removed",
                                 "spire_armor_trim_smithing_template", False)
            self.advancements(BASTION_ADVANCEMENT)
            self.expect_template(f"{label}: nether/find_bastion unlocks Snout", "snout_armor_trim_smithing_template",
                                 True)
            self.expect_template(f"{label}: and the netherite upgrade", "netherite_upgrade_smithing_template", True)
            self.expect_template(f"{label}: but not Spire", "spire_armor_trim_smithing_template", False)
            self.advancements(END_CITY_ADVANCEMENT)
            self.expect_template(f"{label}: end/find_end_city unlocks Spire", "spire_armor_trim_smithing_template",
                                 True)
        finally:
            self.disarm()


PROBES = ("trim_ancient_city", "trim_bastion_end_city")


def run_all(server, client, player, results, only=PROBES):
    selected = [name for name in PROBES if name in only]
    if not selected:
        return
    probes = TrimProbes(server, client, player, results)
    probes.prepare()
    try:
        for name in selected:
            try:
                getattr(probes, name)()
            except ProbeError as error:
                results.check(name, "probe completed", False, str(error))
                try:
                    probes.disarm()
                    probes.reload(count_loot(True))
                    probes.client.request("close_window")
                except ProbeError:
                    raise error
    finally:
        probes.reload(ungated)
        probes.advancements()
        probes.run(f"clear {player}")
