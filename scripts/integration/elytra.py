"""Natural End Ship frame collection, cancellation, gifts and player replacements (#249)."""
import re
import time
from credits import CreditProbes
from gates import set_key
from harness import ProbeError

END = "minecraft:the_end"
RULE = "elytra collection"
PROBES = ("elytra_collection",)
SPAWN = re.compile(r"ASRPROBE elytra-spawn x=(-?\d+) y=(-?\d+) z=(-?\d+) natural=(true|false)")


def release(probes, area):
    x1, z1, x2, z2 = area
    probes.run(f"execute in {END} run forceload remove {x1} {z1} {x2} {z2}")


def acquire_frame(probes, results, forced):
    """Acquire actual generation evidence, bounded to eight starts and eight seconds per start."""
    cities = []
    for attempt in range(8):
        origin = 3000 + attempt * 5000
        located = probes.server.query(
            f"execute in {END} positioned {origin} 80 3000 run locate structure minecraft:end_city",
            r"nearest minecraft:end_city is at \[(-?\d+), ~, (-?\d+)\]", timeout=120)
        x, z = map(int, located.groups())
        cities.append((x, z))
        mark = probes.server.mark()
        probes.tp(END, x + .5, 100, z + .5)
        area = (x - 112, z - 112, x + 112, z + 112)
        forced.append(area)
        probes.run(f"execute in {END} run forceload add {area[0]} {area[1]} {area[2]} {area[3]}")
        deadline = time.monotonic() + 8
        while True:
            found = [SPAWN.search(line) for line in probes.server.lines_since(mark, "ASRPROBE elytra-spawn")]
            found = [entry for entry in found if entry]
            if found:
                marked = all(entry[4] == "true" for entry in found)
                results.check(RULE, "natural generation marks real ship frames", marked,
                              str([entry.group(0) for entry in found]))
                if not marked:
                    raise ProbeError("an actual generated Elytra frame was observed without its provenance marker")
                position = tuple(map(int, found[0].groups()[:3]))
                results.note(f"End Ship fixture acquired: attempt={attempt + 1}, city=({x},{z}), "
                             f"loaded-bounds={area}, frame={position}.")
                return position
            if time.monotonic() >= deadline:
                break
            time.sleep(.25)
        results.note(f"End Ship fixture attempt={attempt + 1}, city=({x},{z}), loaded-bounds={area}: "
                     "no Elytra frame observation within eight seconds; releasing forced chunks.")
        release(probes, area)
        forced.remove(area)
    raise ProbeError("End Ship fixture unavailable: no Elytra frame observations in eight located cities "
                     f"{cities}; inspect the recorded seed/city evidence (no provenance failure was observed)")


def run_all(server, client, player, results, only=PROBES):
    if "elytra_collection" not in only:
        return
    probes = CreditProbes(server, client, player, results, None)
    def toggle(value):
        return lambda text: set_key(text, "item-progression", "require-elytra-collection", value)
    def frame(action="inspect"):
        query = f"f{next(probes._queries)}"
        observed = server.query(f"asrprobe frame {player} {action} {query}",
                                rf"ASRPROBE frame query={query} (?P<rest>[^\r\n]*)").group("rest")
        if observed == "none":
            results.note(f"frame {query} disappeared near player: {client.request('state')}")
        return observed
    forced = []
    try:
        probes.run(f"gamemode survival {player}")
        probes.run(f"effect give {player} minecraft:resistance infinite 4 true")
        probes.run(f"effect give {player} minecraft:saturation infinite 0 true")
        probes.run(f"advancement grant {player} everything")
        probes.credit("grant", "all")
        probes.credit("revoke", "elytra")
        probes.reload(toggle("true"))
        probes.run(f"clear {player}")
        # Locate selects starts; only actual WORLD_GENERATION frame observations qualify.
        x, y, z = acquire_frame(probes, results, forced)
        # Generation can report a ship beyond the located start's original loaded area.
        area = (x - 32, z - 32, x + 32, z + 32)
        forced.append(area)
        probes.run(f"execute in {END} run forceload add {area[0]} {area[1]} {area[2]} {area[3]}")
        # Shulker bullets must not move the client away during the frame assertions.
        for entity in ("shulker", "shulker_bullet"):
            removed = probes.near(END, x, y, z, 48, entity, remove=True)
            results.note(f"Removed {removed} {entity} beside the selected End Ship frame.")
        probes.run(f"effect clear {player} minecraft:levitation")
        # Keep the player beside the frame on a safe platform without replacing its backing block.
        probes.run(f"execute in {END} run fill {x - 3} {y - 2} {z - 3} {x + 10} {y - 2} {z + 3} minecraft:obsidian")
        probes.tp(END, x + .5, y - 1, z + 2.5)
        frame("stage")
        time.sleep(1)
        staged = frame()
        results.check(RULE, "selected natural frame remains loaded beside the staged player",
                      "item=ELYTRA natural=true" in staged,
                      f"{staged}; player={client.request('state')}")
        if "item=ELYTRA natural=true" not in staged:
            raise ProbeError("the selected natural frame was unavailable after staging")
        client.request("hold", item=None)
        frame("cancel-next")
        client.request("attack", entity="item_frame")
        time.sleep(.5)
        probes.expect("cancelled natural removal earns nothing", "elytra", False)
        results.check(RULE, "cancelled removal retains original frame", "item=ELYTRA natural=true" in frame(), frame())
        client.request("attack", entity="item_frame")
        time.sleep(1)
        probes.expect("successful original removal earns before pickup", "elytra", True, "action")
        # Approach from the frame's actual outward face, without entering its backing wall.
        frame("approach")
        time.sleep(2)
        results.check(RULE, "collector can pick up original Elytra", probes.count("elytra") > 0, str(client.request("inventory")))
        # Refill through the actual client interaction; removing a replacement must not recreate credit.
        probes.run(f"give {player} minecraft:elytra")
        client.request("hold", item="elytra")
        client.request("use_entity", entity="item_frame")
        probes.credit("revoke", "elytra")
        client.request("hold", item=None)
        client.request("attack", entity="item_frame")
        probes.expect("refilled frame cannot earn credit", "elytra", False)
        results.check(RULE, "refilled frame removal is refused", "item=ELYTRA natural=false" in frame(), frame())
        # A summoned frame models an unmarked old frame and a frame carrying a player's Elytra.
        probes.run(f"execute in {END} run setblock {x + 7} {y} {z - 1} minecraft:obsidian")
        probes.run(f"execute in {END} run summon minecraft:item_frame {x + 7} {y} {z} {{Facing:3b,Item:{{id:\"minecraft:elytra\",count:1}}}}")
        probes.tp(END, x + 7.5, y - 1, z + 2.5)
        client.request("attack", entity="item_frame")
        probes.expect("unmarked or player frame cannot earn credit", "elytra", False)
        # A gifted loose Elytra is refused, then the existing admin restoration grants it.
        probes.run(f"clear {player}")
        probes.run(f"execute in {END} run summon minecraft:item {x + 7.5} {y - 1} {z + 2.5} {{Item:{{id:\"minecraft:elytra\",count:1}},PickupDelay:0s}}")
        time.sleep(3)
        results.check(RULE, "gifted Elytra pickup refused", probes.count("elytra") == 0, str(client.request("inventory")))
        probes.credit("grant", "elytra")
        time.sleep(3)
        results.check(RULE, "admin restoration permits pickup", probes.count("elytra") > 0, str(client.request("inventory")))
    except ProbeError as error:
        results.check(RULE, "probe completed", False, str(error))
    finally:
        # Forced chunks are a set: retain overlapping city/frame areas through every assertion.
        for area in reversed(forced):
            release(probes, area)
        probes.reload(toggle("false"))
