"""Respawning a dead dragon when a player enters the End (#271).

The End's dragon is killed first, if it is alive, and every End crystal is removed. Each case then
moves the player from the Overworld into the End by command and reads back what the server did:
the plugin's log line for a started respawn, whether an Ender Dragon exists, and whether the
pillars carry End crystals again. The player holds every advancement and every credit, so neither
the dimension gate nor the outer End rule is in the way, and is never an operator.
"""

import os
import re
import time

from credits import HELPER, CreditProbes, helper_client
from gates import set_key
from harness import ProbeError
from probes import END, OVERWORLD

RULE = "respawn on entry"
PROBES = ("respawn_on_entry",)
STARTED = r"entered \S+ with its dragon dead; the dragon respawn has started"
ENTRY = (0.5, 90, 30.5)
# An End portal block in the Overworld arena, for an entry by portal.
PORTAL = (4, 100, 4)


def set_respawn(config_text, scaling, respawn):
    """config.yml with boss-scaling.enabled and boss-scaling.respawn-on-entry.enabled set."""
    config_text = set_key(config_text, "boss-scaling", "enabled", "true" if scaling else "false")
    config_text, count = re.subn(r"(?m)^(  respawn-on-entry:\n    enabled:[ \t]*)(true|false)\b",
                                 lambda match: match.group(1) + ("true" if respawn else "false"),
                                 config_text)
    if count != 1:
        raise ProbeError("boss-scaling.respawn-on-entry.enabled is not in config.yml exactly once")
    return config_text


class RespawnProbes(CreditProbes):

    def configure(self, scaling, respawn):
        self.reload(lambda text: set_respawn(text, scaling, respawn))

    def dragon_alive(self):
        return self.exists(END, "@e[type=minecraft:ender_dragon]")

    def leave(self):
        self.arena(OVERWORLD, 0, 100, 0)
        self.tp(OVERWORLD, 0.5, 100, 0.5)
        time.sleep(1)

    def enter(self):
        mark = self.server.mark()
        self.tp(END, *ENTRY)
        return mark

    def started_since(self, mark, wait):
        deadline = time.monotonic() + wait
        while True:
            lines = [line for line in self.server.lines_since(mark) if re.search(STARTED, line)]
            if lines or time.monotonic() >= deadline:
                return lines
            time.sleep(0.5)

    def enter_by_portal(self):
        self.run(f"execute in {OVERWORLD} run setblock {PORTAL[0]} {PORTAL[1] - 1} {PORTAL[2]} minecraft:obsidian")
        self.run(f"execute in {OVERWORLD} run setblock {PORTAL[0]} {PORTAL[1]} {PORTAL[2]} minecraft:end_portal")
        mark = self.server.mark()
        self.tp(OVERWORLD, PORTAL[0] + 0.5, PORTAL[1], PORTAL[2] + 0.5)
        deadline = time.monotonic() + 15
        while self.where()["environment"] != "THE_END":
            if time.monotonic() > deadline:
                raise ProbeError("the End portal did not carry the player to the End")
            time.sleep(0.5)
        self.run(f"execute in {OVERWORLD} run setblock {PORTAL[0]} {PORTAL[1]} {PORTAL[2]} minecraft:air")
        return mark

    def progression(self, granted):
        verb = "grant" if granted else "revoke"
        self.run(f"advancement {verb} {self.player} everything")
        self.credit(verb, "all")
        self.reload()

    def wait_dead(self):
        deadline = time.monotonic() + 60
        while self.dragon_alive():
            if time.monotonic() > deadline:
                raise ProbeError("the End's dragon was still present a minute after /kill")
            time.sleep(2)
        # The death sequence, then the exit portal, must finish before the battle reads as won.
        time.sleep(12)

    def kill_first_dragon(self):
        """Leaves the End with its fight won before and no dragon alive."""
        self.tp(END, *ENTRY)
        deadline = time.monotonic() + 90
        while not self.dragon_alive() and not self.battle_won_once(deadline):
            time.sleep(2)
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon]")
        self.wait_dead()

    def battle_won_once(self, deadline):
        # A dragon killed by an earlier module leaves the exit portal; an unvisited End spawns one.
        if time.monotonic() > deadline:
            raise ProbeError("the End's first dragon never spawned")
        column = self.client.request("column", x=1, z=0, ymin=0, ymax=120)["names"]
        return "end_portal" in column

    # ------------------------------------------------------------------ cases

    def respawn_on_entry(self):
        self.kill_first_dragon()
        self.run(f"execute in {END} run kill @e[type=minecraft:end_crystal]")
        time.sleep(1)
        crystals_before = self.exists(END, "@e[type=minecraft:end_crystal]")
        self.results.check(RULE, "setup: the End has no dragon and no End crystal",
                           not self.dragon_alive() and not crystals_before,
                           f"dragon={self.dragon_alive()} crystals={crystals_before}")

        # Off by default.
        self.configure(scaling=True, respawn=False)
        self.leave()
        mark = self.enter()
        started = self.started_since(mark, 8)
        self.results.check(RULE, "with respawn-on-entry off, entering a dragonless End starts nothing",
                           not started and not self.dragon_alive(), f"log={started}")

        # On, but needs boss-scaling.enabled too.
        self.configure(scaling=False, respawn=True)
        self.leave()
        mark = self.enter()
        started = self.started_since(mark, 8)
        self.results.check(RULE, "with boss-scaling.enabled off, entering starts nothing",
                           not started and not self.dragon_alive(), f"log={started}")

        # A creative player never triggers it.
        self.configure(scaling=True, respawn=True)
        self.leave()
        self.run(f"gamemode creative {self.player}")
        mark = self.enter()
        started = self.started_since(mark, 8)
        self.results.check(RULE, "a creative player's entry starts nothing",
                           not started and not self.dragon_alive(), f"log={started}")
        self.leave()
        self.run(f"gamemode survival {self.player}")

        # A player the End gate does not admit, logging in already in the End, starts nothing,
        # although the gate only returns them a tick after the login.
        self.configure(scaling=True, respawn=False)
        self.progression(granted=False)
        self.tp(END, *ENTRY)
        self.configure(scaling=True, respawn=True)
        mark = self.server.mark()
        self.client.request("reconnect", timeout=35)
        started = self.started_since(mark, 8)
        returned = self.where()["environment"]
        self.results.check(RULE, "a login in the End by a player the End gate refuses starts nothing",
                           not started and not self.dragon_alive(),
                           f"log={started}, player returned to {returned}")
        self.progression(granted=True)
        self.leave()

        # A survival player's entry by portal starts vanilla's sequence without any crystal.
        mark = self.enter_by_portal()
        started = self.started_since(mark, 15)
        self.results.check(RULE, "a survival player's entry by End portal starts the respawn "
                           "with no crystal placed by a player",
                           len(started) == 1, f"log={started}")
        if not started:
            raise ProbeError("the respawn never started")

        # Leaving and entering again while the sequence runs starts no second one.
        self.leave()
        again = self.enter()
        repeated = self.started_since(again, 6)
        self.results.check(RULE, "an entry while the respawn runs starts no second one", not repeated,
                           f"log={repeated}")

        deadline = time.monotonic() + 150
        while not self.dragon_alive() and time.monotonic() < deadline:
            time.sleep(3)
        alive = self.dragon_alive()
        self.results.check(RULE, "the respawn the plugin starts summons a dragon", alive,
                           "a dragon exists" if alive else "no dragon within 150 s")
        crystals = self.exists(END, "@e[type=minecraft:end_crystal]")
        self.results.check(RULE, "the respawn rebuilds the pillars' End crystals", crystals,
                           f"end crystals present={crystals}")

        # With a dragon alive, another entry starts nothing.
        if not alive:
            raise ProbeError("no respawned dragon to continue with")
        self.leave()
        mark = self.enter()
        started = self.started_since(mark, 6)
        self.results.check(RULE, "an entry while the dragon lives starts nothing", not started,
                           f"log={started}")

    def same_tick(self, helper):
        """Two players entering in the same tick start one respawn."""
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon]")
        self.wait_dead()
        self.leave()
        self.tp(OVERWORLD, 1.5, 100, 0.5, player=HELPER)
        time.sleep(2)
        mark = self.server.mark()
        # One command moves both players, so both arrive in the same tick.
        self.run(f"execute in {END} run tp @a {ENTRY[0]} {ENTRY[1]} {ENTRY[2]}")
        started = self.started_since(mark, 15)
        time.sleep(4)
        started = [line for line in self.server.lines_since(mark) if re.search(STARTED, line)]
        self.results.check(RULE, "two players entering in the same tick start one respawn",
                           len(started) == 1, f"log={started}")


def run_all(server, client, player, results, only=PROBES):
    if "respawn_on_entry" not in only:
        return
    probes = RespawnProbes(server, client, player, results, None)
    try:
        for line in (f"gamemode survival {player}",
                     f"advancement grant {player} everything",
                     f"effect give {player} minecraft:resistance infinite 4 true",
                     f"effect give {player} minecraft:slow_falling infinite 0 true",
                     f"execute in {END} run forceload add 0 0"):
            probes.run(line)
        probes.credit("grant", "all")
        probes.respawn_on_entry()
        helper = helper_client(client, os.path.join(os.path.dirname(client.log_path), "respawn-helper.log"))
        mark = server.mark()
        with helper:
            helper.wait_event("spawn", 120)
            server.wait_for(rf"ASRPROBE join name={HELPER}", 30, since=mark)
            for line in (f"gamemode survival {HELPER}", f"advancement grant {HELPER} everything",
                         f"effect give {HELPER} minecraft:resistance infinite 4 true",
                         f"effect give {HELPER} minecraft:slow_falling infinite 0 true"):
                probes.run(line)
            probes.credit("grant", "all", player=HELPER)
            probes.same_tick(helper)
            helper.request("quit", timeout=10)
    except ProbeError as error:
        results.check(RULE, "the respawn probes completed", False, str(error))
    finally:
        try:
            probes.run(f"gamemode survival {player}")
            probes.configure(scaling=False, respawn=False)
            probes.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon]")
        except ProbeError as error:
            results.note(f"respawn cleanup: {error}")
