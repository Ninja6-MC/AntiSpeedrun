"""Probes for section 7's three boss rules (#197): the exit portal crystal block, the single-hit
boss damage cap, and the bed and Respawn Anchor block.

Each rule is switched on alone, by editing the run's config.yml and running /asr reload, and is
exercised by a non-operator player without any bypass node. Every probe builds its own state from
console commands in an obsidian box it owns, so nothing depends on terrain or on an earlier probe,
and asserts on what the server reports: boss health before and after, entity presence, the
player's inventory and the probe plugin's damage lines.
"""

import os
import re
import time

from harness import ProbeError, read

RULES = (
    "block-exit-portal-crystal-place",
    "cap-single-hit-boss-damage",
    "block-bed-anchor-boss-damage",
)
EPSILON = 1.0e-3

HEALTH = re.compile(r"has the following entity data: (-?[0-9.]+)f")
NO_ENTITY = r"No entity was found|Found no elements"
DAMAGE = re.compile(
    r"ASRPROBE damage victim=(?P<victim>\S+) uuid=(?P<uuid>\S+) part=(?P<part>\S+) type=(?P<type>\S+) "
    r"bad-respawn-point=(?P<bad>\S+) causing=(?P<causing>\S+) direct=(?P<direct>\S+) cause=(?P<cause>\S+) "
    r"base=(?P<base>\S+) final=(?P<final>\S+) cancelled=(?P<cancelled>\S+) health=(?P<health>\S+)")
JOIN = re.compile(r"ASRPROBE join name=(?P<name>\S+) op=(?P<op>\S+) bypass=(?P<bypass>\S+) "
                  r"anticheese-bypass=(?P<anticheese>\S+)")
CRYSTAL = re.compile(r"ASRPROBE crystal-click .* block=(?P<block>\S+) .* cancelled=(?P<cancelled>\S+)")

OVERWORLD = "minecraft:overworld"
NETHER = "minecraft:the_nether"
END = "minecraft:the_end"
BOSSES = {"wither": "minecraft:wither", "dragon": "minecraft:ender_dragon"}
CLIENT_NAMES = {"wither": "wither", "dragon": "ender_dragon"}


def parse_damage(lines):
    """Every probe damage line among lines, as dicts with numbers and booleans converted."""
    events = []
    for line in lines:
        match = DAMAGE.search(line)
        if not match:
            continue
        event = match.groupdict()
        for key in ("base", "final", "health"):
            event[key] = float(event[key])
        for key in ("bad", "cancelled"):
            event[key] = event[key] == "true"
        events.append(event)
    return events


def landed(events):
    """The damage events that went through: not cancelled and with a final amount above zero."""
    return [event for event in events if not event["cancelled"] and event["final"] > 0.0]


def set_rules(config_text, enabled):
    """config.yml with the three rules set: those named in enabled on, the others off."""
    for key in RULES:
        value = "true" if key in enabled else "false"
        config_text, count = re.subn(rf"(?m)^(\s+{re.escape(key)}:[ \t]*)(true|false)\b",
                                     lambda match, value=value: match.group(1) + value, config_text)
        if count != 1:
            raise ProbeError(f"anti-cheese.{key} is not in config.yml exactly once")
    return config_text


def configured_cap(config_text):
    """anti-cheese.max-single-hit-boss-damage as the run's config.yml sets it."""
    match = re.search(r"(?m)^\s+max-single-hit-boss-damage:[ \t]*([0-9.]+)", config_text)
    if not match:
        raise ProbeError("anti-cheese.max-single-hit-boss-damage is not in config.yml")
    return float(match.group(1))


# Checks that fail today because of an open bug, keyed by issue number. Such a check is still
# run and recorded, as "known-failure", but does not fail the run. Once it passes, the issue is
# fixed and the entry is stale: the check then fails as "unexpected-pass" until it is removed here.
KNOWN_ISSUES = {}


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


class AntiCheeseProbes:
    def __init__(self, server, client, player, results):
        self.server = server
        self.client = client
        self.player = player
        self.results = results
        self.config_path = os.path.join(server.workdir, "plugins", "AntiSpeedrun", "config.yml")

    # ------------------------------------------------------------------ helpers

    def run(self, line):
        self.server.command(line)

    def rules(self, *enabled):
        with open(self.config_path, "r", encoding="utf-8") as handle:
            text = handle.read()
        with open(self.config_path, "w", encoding="utf-8") as handle:
            handle.write(set_rules(text, enabled))
        match = self.server.query("asr reload", r"Configuration reloaded(?P<rest>[^\n]*)", timeout=30)
        if "warning" in match.group("rest").lower():
            self.results.note(f"/asr reload with {enabled or 'no rule'} on reported: {match.group(0)}")
        time.sleep(0.5)

    def health(self, selector):
        match = self.server.query(f"data get entity {selector} Health",
                                  rf"{HEALTH.pattern}|{NO_ENTITY}")
        return float(match.group(1)) if match.group(1) is not None else None

    def exists(self, dimension, selector):
        match = self.server.query(f"execute in {dimension} if entity {selector}",
                                  r"Test passed|Test failed")
        return match.group(0) == "Test passed"

    def tp(self, dimension, x, y, z, yaw=0, pitch=0):
        self.run(f"execute in {dimension} run tp {self.player} {x} {y} {z} {yaw} {pitch}")
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
        self.run(f"execute in {dimension} run kill @e[tag=asrp]")
        self.run(f"execute in {dimension} run kill @e[type=minecraft:item,x={x},y={y},z={z},distance=..40]")
        self.run(f"execute in {dimension} run kill @e[type=minecraft:experience_orb,x={x},y={y},z={z},distance=..40]")
        inner = half - 1
        self.run(f"execute in {dimension} run fill {x - inner} {y} {z - inner} {x + inner} {y + 8} {z + inner} "
                 f"minecraft:air replace minecraft:fire")
        time.sleep(1)

    def summon(self, boss, dimension, x, y, z):
        self.run(f"execute in {dimension} run kill @e[tag=asrp]")
        time.sleep(0.5)
        # The Wither is frozen with NoAI. The dragon cannot be: a NoAI dragon never positions its
        # parts, which stay at the world origin where no hit or explosion reaches them. Outside the
        # End it keeps the HOVERING phase (10) it is given and stays where it was summoned, facing
        # north: head part (+1) about 6 blocks to the north, body part (+3) above the origin.
        state = "DragonPhase:10" if boss == "dragon" else "NoAI:1b"
        self.run(f"execute in {dimension} run summon {BOSSES[boss]} {x} {y} {z} "
                 f"{{{state},Tags:[\"asrp\"],Rotation:[0f,0f],PersistenceRequired:1b}}")
        time.sleep(1.5)
        selector = f"@e[type={BOSSES[boss]},tag=asrp,limit=1]"
        if self.health(selector) is None:
            raise ProbeError(f"the {boss} did not appear at {dimension} {x} {y} {z}")
        return selector

    def measure(self, selector, action, settle=2.0):
        """Health before, health after (None once the boss is gone), and the damage lines between."""
        before = self.health(selector)
        mark = self.server.mark()
        action()
        time.sleep(settle)
        after = self.health(selector)
        return before, after, parse_damage(self.server.lines_since(mark))

    def count(self, item):
        return self.client.request("count", item=item)["count"]

    # ------------------------------------------------------------------ setup

    def prepare_player(self):
        mark_line = JOIN.search(self.server.log())
        if not mark_line:
            raise ProbeError("the probe plugin logged no join line for the player")
        join = mark_line.groupdict()
        self.results.check("setup", "the probing player is not an operator and lacks every bypass node",
                           join["op"] == "false" and join["bypass"] == "false" and join["anticheese"] == "false",
                           f"op={join['op']} antispeedrun.bypass={join['bypass']} "
                           f"antispeedrun.bypass.anticheese={join['anticheese']}")
        for line in (f"gamemode survival {self.player}",
                     # The End gate and the item gates are not under test; finished progression
                     # keeps them out of the way. Advancements are not a bypass.
                     f"advancement grant {self.player} everything",
                     f"clear {self.player}",
                     f"effect give {self.player} minecraft:resistance infinite 4 true",
                     f"effect give {self.player} minecraft:fire_resistance infinite 0 true",
                     f"effect give {self.player} minecraft:saturation infinite 0 true",
                     f"effect give {self.player} minecraft:regeneration infinite 4 true"):
            self.run(line)
        time.sleep(2)

    # ------------------------------------------------------------------ crystal

    def crystal(self):
        rule = "block-exit-portal-crystal-place"
        self.rules()
        self.tp(END, 0.5, 90, 30.5)
        deadline = time.monotonic() + 90
        while not self.exists(END, "@e[type=minecraft:ender_dragon]"):
            if time.monotonic() > deadline:
                raise ProbeError("the End's first dragon never spawned")
            time.sleep(2)
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon]")

        # The exit portal: portal blocks on (1, py, 0) and a bedrock pillar on (0, 0).
        py = ptop = None
        deadline = time.monotonic() + 60
        while py is None:
            if time.monotonic() > deadline:
                raise ProbeError("the exit portal never generated after the dragon was killed")
            time.sleep(2)
            east = self.client.request("column", x=1, z=0, ymin=0, ymax=120)["names"]
            centre = self.client.request("column", x=0, z=0, ymin=0, ymax=120)["names"]
            if "end_portal" in east:
                py = east.index("end_portal")
                ptop = max(y for y, name in enumerate(centre) if name == "bedrock")
                if "dragon_egg" in centre:
                    self.run(f"execute in {END} run setblock 0 {centre.index('dragon_egg')} 0 minecraft:air")
        self.results.note(f"Exit portal at y={py}; centre pillar top at y={ptop}.")

        rim = {"east": (3, 0), "west": (-3, 0), "south": (0, 3), "north": (0, -3)}
        for name, (dx, dz) in rim.items():
            px, pz = dx + (1 if dx > 0 else -1 if dx < 0 else 0), dz + (1 if dz > 0 else -1 if dz < 0 else 0)
            self.run(f"execute in {END} run setblock {px} {py} {pz} minecraft:obsidian")
            self.run(f"execute in {END} run fill {px} {py + 1} {pz} {px} {py + 2} {pz} minecraft:air")
        time.sleep(1)
        for name, (dx, dz) in rim.items():
            column = self.client.request("column", x=dx, z=dz, ymin=py, ymax=py + 1)["names"]
            if column != ["bedrock", "air"]:
                raise ProbeError(f"the {name} ritual position ({dx}, {py}, {dz}) is {column}, not bedrock under air")

        self.run(f"give {self.player} minecraft:end_crystal 8")
        time.sleep(1)
        self.client.request("hold", item="end_crystal")

        centre_crystal = f"@e[type=minecraft:end_crystal,x=0.5,y={ptop + 1},z=0.5,distance=..1]"

        def place(target, stand):
            self.tp(END, stand[0] + 0.5, py + 1, stand[1] + 0.5)
            before = self.count("end_crystal")
            mark = self.server.mark()
            self.client.request("use_block", x=target[0], y=target[1], z=target[2], face="up")
            time.sleep(1.5)
            clicks = [m.groupdict() for m in map(CRYSTAL.search, self.server.lines_since(mark)) if m]
            return before, self.count("end_crystal"), clicks

        # Control: with the rule off, the same click on the centre pillar does place a crystal, so a
        # refusal below is the rule and not the probe failing to place.
        before, after, _ = place((0, ptop, 0), (4, 0))
        placed = self.exists(END, centre_crystal)
        self.results.check(rule, "control, rule off: a crystal on the centre column (0, 0) is placed",
                           placed and after == before - 1,
                           f"crystal present={placed}, crystals in hand {before} -> {after}")
        self.run(f"execute in {END} run kill {centre_crystal}")
        time.sleep(1)

        self.rules(rule)
        before, after, clicks = place((0, ptop, 0), (4, 0))
        placed = self.exists(END, centre_crystal)
        refused = bool(clicks) and all(click["cancelled"] == "true" for click in clicks)
        self.results.check(rule, "rule on: a crystal on the centre column (0, 0) is refused",
                           not placed and after == before and refused,
                           f"crystal present={placed}, crystals in hand {before} -> {after}, "
                           f"interact event denied={refused}")

        for index, (name, (dx, dz)) in enumerate(rim.items()):
            stand = (dx + (1 if dx > 0 else -1 if dx < 0 else 0), dz + (1 if dz > 0 else -1 if dz < 0 else 0))
            before, after, _ = place((dx, py, dz), stand)
            selector = f"@e[type=minecraft:end_crystal,x={dx + 0.5},y={py + 1},z={dz + 0.5},distance=..1]"
            placed = self.exists(END, selector) or (index == 3 and after == before - 1)
            self.results.check(rule, f"rule on: a crystal on the {name} ritual position ({dx}, {py}, {dz}) is placed",
                               placed and after == before - 1,
                               f"crystal present={placed}, crystals in hand {before} -> {after}")

        deadline = time.monotonic() + 150
        respawned = False
        while time.monotonic() < deadline:
            if self.exists(END, "@e[type=minecraft:ender_dragon]"):
                respawned = True
                break
            time.sleep(3)
        self.results.check(rule, "rule on: the four-crystal ritual resummons the dragon", respawned,
                           "a new Ender Dragon spawned" if respawned else "no dragon within 150 s")
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon]")
        self.run(f"clear {self.player} minecraft:end_crystal")
        self.rules()

    # ------------------------------------------------------------------ single-hit cap

    def damage_cap(self):
        rule = "cap-single-hit-boss-damage"
        cap = configured_cap(read(self.config_path))
        cx, cy, cz = 0, 100, 0
        self.arena(OVERWORLD, cx, cy, cz)
        self.tp(OVERWORLD, 3.5, cy, -3.5)
        self.run(f"give {self.player} minecraft:netherite_sword[minecraft:enchantments={{sharpness:255}}]")
        self.run(f"give {self.player} minecraft:mace")
        time.sleep(1)

        for armed in (False, True):
            self.rules(rule) if armed else self.rules()
            state = "cap on" if armed else "cap off"

            def verdict(events, before, after, label, single=True):
                hits = landed(events)
                delta = None if after is None else before - after
                finals = [round(e["final"], 3) for e in events]
                detail = (f"health {before} -> {after}, delta={delta}, event finals={finals}, "
                          f"types={sorted({e['type'] for e in events})}")
                if not hits:
                    raise ProbeError(f"{label} ({state}) never landed: {detail}")
                if armed:
                    ok = all(e["final"] <= cap + EPSILON for e in hits)
                    if single:
                        ok = ok and delta is not None and delta <= cap + EPSILON
                    return ok, detail
                return delta is not None and delta > cap + EPSILON, detail

            def expectation(label):
                return (f"{state}: {label} removes at most {cap}" if armed
                        else f"{state}: {label} removes its full amount, more than {cap}")

            # Melee: the Wither, the dragon's head and the dragon's body part.
            self.client.request("hold", item="netherite_sword")
            for boss, offset, label, at in (
                    ("wither", 0, "a Sharpness 255 sword hit on the Wither", (2.5, 0.5)),
                    ("dragon", 1, "a Sharpness 255 sword hit on the dragon's head part", (3.5, -5.5)),
                    ("dragon", 3, "a Sharpness 255 sword hit on the dragon's body part", (4.0, 0.5))):
                y = cy if boss == "wither" else cy + 2
                selector = self.summon(boss, OVERWORLD, cx + 0.5, y, cz + 0.5)
                self.tp(OVERWORLD, cx + at[0], cy, cz + at[1])
                before, after, events = self.measure(selector, lambda: self.client.request(
                    "attack", entity=CLIENT_NAMES[boss], offset=offset))
                ok, detail = verdict(events, before, after, label.replace("a Sharpness 255 sword hit", "hit"))
                self.results.check(rule, expectation(label), ok, detail)

            # A stack of eight TNT minecarts detonated together at the boss.
            for boss in ("wither", "dragon"):
                y = cy if boss == "wither" else cy + 2
                selector = self.summon(boss, OVERWORLD, cx + 0.5, y, cz + 0.5)
                self.tp(OVERWORLD, 8.5, cy, 8.5)

                # At the Wither's feet, or under the dragon's head part, which takes a hit in full.
                at = (cx + 0.5, cz + 1.5) if boss == "wither" else (cx + 0.5, cz - 6.0)

                def stack(at=at):
                    for _ in range(8):
                        self.run(f"execute in {OVERWORLD} run summon minecraft:tnt_minecart {at[0]} {cy} "
                                 f"{at[1]} {{fuse:1,TNTFuse:1}}")
                before, after, events = self.measure(selector, stack)
                ok, detail = verdict(events, before, after, "TNT minecart stack", single=False)
                label = f"each hit of an 8-TNT-minecart stack on the {'Wither' if boss == 'wither' else 'dragon'}"
                self.results.check(rule, expectation(label), ok, detail)
                if armed:
                    # The whole stack removes at most the cap, not only each hit (#197, #203).
                    total = None if after is None else before - after
                    self.results.check(
                        rule, f"cap on: the whole 8-TNT-minecart stack removes at most {cap} from the "
                        f"{'Wither' if boss == 'wither' else 'dragon'}",
                        total is not None and total <= cap + EPSILON,
                        f"total delta={total} across {len(landed(events))} hits")
                self.clear_arena(OVERWORLD, cx, cy, cz)

            # A Mace smash from a fall of about 25 blocks.
            self.client.request("hold", item="mace")
            for boss, offset, label, at in (("wither", 0, "a Mace smash on the Wither", (1.5, 0.5)),
                                            ("dragon", 1, "a Mace smash on the dragon's head part", (2.0, -6.0))):
                y = cy if boss == "wither" else cy + 2
                selector = self.summon(boss, OVERWORLD, cx + 0.5, y, cz + 0.5)
                before = self.health(selector)
                mark = self.server.mark()
                self.run(f"execute in {OVERWORLD} run tp {self.player} {at[0]} {cy + 27} {at[1]} 0 90")
                time.sleep(0.3)
                fall = self.client.request("smash", timeout=40, entity=CLIENT_NAMES[boss], offset=offset,
                                           floor=cy, trigger=4.5, limit=15000)
                time.sleep(1.5)
                after = self.health(selector)
                events = parse_damage(self.server.lines_since(mark))
                ok, detail = verdict(events, before, after, "smash")
                self.results.check(rule, expectation(label), ok, f"{detail}, fell {fall['fell']:.1f} blocks")
            self.client.request("hold", item="netherite_sword")

        # /kill is an operator's act, not a hit: with the cap on it still kills outright.
        selector = self.summon("wither", OVERWORLD, cx + 0.5, cy, cz + 0.5)
        mark = self.server.mark()
        self.run(f"execute in {OVERWORLD} run kill {selector}")
        time.sleep(2)
        gone = self.health(selector) is None
        events = parse_damage(self.server.lines_since(mark))
        self.results.check(rule, "cap on: /kill on the Wither is not capped", gone,
                           f"wither gone={gone}, events={[(e['type'], e['final'], e['cancelled']) for e in events]}")
        selector = self.summon("dragon", OVERWORLD, cx + 0.5, cy + 2, cz + 0.5)
        mark = self.server.mark()
        self.run(f"execute in {OVERWORLD} run kill {selector}")
        time.sleep(2)
        gone = self.health(selector) is None
        events = parse_damage(self.server.lines_since(mark))
        self.results.check(rule, "cap on: /kill on the dragon is not capped", gone,
                           f"dragon gone={gone}, events={[(e['type'], e['final'], e['cancelled']) for e in events]}")
        self.rules()
        self.clear_arena(OVERWORLD, cx, cy, cz)

    # ------------------------------------------------------------------ bed and anchor

    def bed_anchor(self):
        rule = "block-bed-anchor-boss-damage"
        nether = (0, 40, 0)
        anchor = (40, 100, 0)
        self.arena(NETHER, *nether)
        self.arena(OVERWORLD, *anchor)
        self.run(f"clear {self.player}")
        time.sleep(1)

        for armed in (False, True):
            self.rules(rule) if armed else self.rules()
            state = "rule on" if armed else "rule off"
            for boss in ("wither", "dragon"):
                name = "Wither" if boss == "wither" else "dragon"
                for kind in ("bed", "anchor"):
                    dimension, (ax, ay, az) = (NETHER, nether) if kind == "bed" else (OVERWORLD, anchor)
                    self.clear_arena(dimension, ax, ay, az)
                    y = ay if boss == "wither" else ay + 2
                    selector = self.summon(boss, dimension, ax + 0.5, y, az + 0.5)
                    if kind == "bed":
                        self.run(f"execute in {dimension} run setblock {ax + 3} {ay} {az} "
                                 "minecraft:red_bed[part=foot,facing=south]")
                        self.run(f"execute in {dimension} run setblock {ax + 3} {ay} {az + 1} "
                                 "minecraft:red_bed[part=head,facing=south]")
                    else:
                        self.run(f"execute in {dimension} run setblock {ax + 3} {ay} {az} "
                                 "minecraft:respawn_anchor[charges=1]")
                    self.tp(dimension, ax + 6.5, ay, az + 0.5, 90, 20)
                    self.client.request("hold", item=None)
                    before, after, events = self.measure(selector, lambda: self.client.request(
                        "use_block", x=ax + 3, y=ay, z=az, face="up"))
                    delta = None if after is None else round(before - after, 3)
                    types = sorted({e["type"] for e in events})
                    flags = sorted({e["bad"] for e in events})
                    detail = (f"health {before} -> {after}, delta={delta}, damage types={types}, "
                              f"BAD_RESPAWN_POINT={flags}, cancelled={sorted({e['cancelled'] for e in events})}")
                    if not events:
                        raise ProbeError(f"the {kind} explosion ({state}) never reached the {boss}: {detail}")
                    typed = all(e["type"] == "minecraft:bad_respawn_point" and e["bad"] for e in events)
                    self.results.check(rule, f"{state}: a {kind} explosion beside the {name} is reported as "
                                       "DamageType BAD_RESPAWN_POINT", typed, detail)
                    if armed:
                        self.results.check(rule, f"{state}: a {kind} explosion deals the {name} 0 damage",
                                           delta == 0 and all(e["cancelled"] for e in events), detail)
                    else:
                        self.results.check(rule, f"{state}: a {kind} explosion damages the {name} (control)",
                                           delta is not None and delta > 0, detail)

                if armed:
                    dimension, (ax, ay, az) = NETHER, nether
                    self.clear_arena(dimension, ax, ay, az)
                    y = ay if boss == "wither" else ay + 2
                    selector = self.summon(boss, dimension, ax + 0.5, y, az + 0.5)
                    self.tp(dimension, ax + 6.5, ay, az + 0.5, 90, 20)
                    before, after, events = self.measure(selector, lambda: self.run(
                        f"execute in {dimension} run summon minecraft:tnt {ax + 3.5} {ay} {az + 0.5} "
                        "{fuse:1s,Fuse:1s}"))
                    delta = None if after is None else round(before - after, 3)
                    self.results.check(rule, f"{state}: a TNT control beside the {name} still damages it",
                                       delta is not None and delta > 0,
                                       f"health {before} -> {after}, delta={delta}, "
                                       f"damage types={sorted({e['type'] for e in events})}")
        self.rules()


PROBES = ("crystal", "damage_cap", "bed_anchor")


def run_all(server, client, player, results, only=PROBES):
    probes = AntiCheeseProbes(server, client, player, results)
    probes.prepare_player()
    for probe in (getattr(probes, name) for name in PROBES if name in only):
        try:
            probe()
        except ProbeError as error:
            results.check(probe.__name__, "probe completed", False, str(error))
            # A broken probe leaves unknown state behind; put every rule back off for the next.
            try:
                probes.rules()
            except ProbeError:
                raise error
