"""Respawning a dead dragon when a player enters the End (#271).

The End's dragon is killed first, if it is alive, and every End crystal is removed. Each case then
moves the player from the Overworld into the End by command and reads back what the server did:
the plugin's log line for a started respawn, whether an Ender Dragon exists, and whether the
pillars carry End crystals again. The player holds every advancement and every credit, so neither
the dimension gate nor the outer End rule is in the way, and is never an operator.
"""

from datetime import datetime
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


def set_respawn(config_text, scaling, respawn, hours=None):
    """config.yml with boss-scaling.enabled, respawn-on-entry.enabled and, if given, its
    participant-cooldown-hours set."""
    config_text = set_key(config_text, "boss-scaling", "enabled", "true" if scaling else "false")
    config_text, count = re.subn(r"(?m)^(  respawn-on-entry:\n    enabled:[ \t]*)(true|false)\b",
                                 lambda match: match.group(1) + ("true" if respawn else "false"),
                                 config_text)
    if count != 1:
        raise ProbeError("boss-scaling.respawn-on-entry.enabled is not in config.yml exactly once")
    if hours is not None:
        config_text, count = re.subn(r"(?m)^(    participant-cooldown-hours:[ \t]*)\S+",
                                     lambda match: match.group(1) + f"{hours:.6f}", config_text)
        if count != 1:
            raise ProbeError("respawn-on-entry.participant-cooldown-hours is not in config.yml exactly once")
    return config_text


VICTORY = re.compile(r"helped win a dragon fight at (?P<at>\S+?Z)")
COOLING = "cannot start a respawn for another"
TOLD = "You helped defeat an Ender Dragon recently"
# The arena the cooldown cases stamp a victory in, inside the outer End radius.
ARENA = (0, 100, 200)
SECONDARY_FREE_DRAGON = "@e[type=minecraft:ender_dragon,tag=asrp,limit=1]"


class RespawnProbes(CreditProbes):

    def configure(self, scaling, respawn, hours=None):
        self.reload(lambda text: set_respawn(text, scaling, respawn, hours))

    def cooldown_reply(self, action):
        return self.server.query(f"asr cooldown {action} {self.player}",
                                 r"[^\r\n]*(?:helped win|has not helped|Cleared|has no dragon)[^\r\n]*",
                                 timeout=15).group(0)

    def victory_at(self):
        """When the store says the player last helped win a fight, in epoch milliseconds."""
        reply = self.cooldown_reply("show")
        match = VICTORY.search(reply)
        if not match:
            raise ProbeError(f"/asr cooldown show reported no victory: {reply}")
        return datetime.fromisoformat(match.group("at").replace("Z", "+00:00")).timestamp() * 1000, reply

    def win_fight(self):
        """The player hits a dragon summoned in the End and it is killed: one won fight."""
        x, y, z = ARENA
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon,tag=asrp]")
        time.sleep(0.5)
        self.run(f"execute in {END} run summon minecraft:ender_dragon {x + 0.5} {y + 2} {z + 0.5} "
                 "{DragonPhase:10,Tags:[\"asrp\"],Rotation:[0f,0f],PersistenceRequired:1b}")
        time.sleep(1.5)
        self.tp(END, x + 3.5, y, z - 5.5)
        before = self.health(SECONDARY_FREE_DRAGON)
        self.client.request("attack", entity="ender_dragon", offset=1)
        time.sleep(1)
        after = self.health(SECONDARY_FREE_DRAGON)
        if before is None or after is None or not after < before:
            raise ProbeError(f"the player's hit on the summoned dragon did not land: {before} -> {after}")
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon,tag=asrp]")
        time.sleep(2)

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

    def cooldown(self):
        """The participant cooldown (#272), in a dragonless End, before the same-tick case."""
        x, y, z = ARENA
        self.arena(END, x, y, z)
        self.run(f"give {self.player} minecraft:iron_sword")
        self.wait_count("iron_sword", 1)
        self.client.request("hold", item="iron_sword")

        # Two won fights in a row: the second restarts the cooldown from its own victory.
        self.configure(scaling=True, respawn=False, hours=1)
        self.win_fight()
        first, _ = self.victory_at()
        time.sleep(1.5)
        self.win_fight()
        second, reply = self.victory_at()
        self.results.check(RULE, "a second won fight restarts the cooldown from its own victory",
                           second > first and COOLING in reply, f"{first:.0f} -> {second:.0f}; {reply}")

        # On cooldown: an entry into the dragonless End starts nothing and says how long is left.
        self.configure(scaling=True, respawn=True, hours=1)
        self.leave()
        self.client.request("messages")
        mark = self.enter()
        started = self.started_since(mark, 6)
        heard = self.client.request("messages")["lines"]
        told = [line for line in heard if TOLD in line]
        self.results.check(RULE, "a player on cooldown entering a dragonless End starts nothing and "
                           "is told how long is left", not started and bool(told),
                           f"log={started}, told={told}")

        # A staff clear ends the cooldown and keeps the dragon-fight credit.
        cleared = self.cooldown_reply("clear")
        after = self.cooldown_reply("show")
        sources, _ = self.credits()["dragon-fight"]
        self.results.check(RULE, "/asr cooldown clear ends the cooldown and keeps dragon-fight",
                           "Cleared" in cleared and "has not helped" in after and "action" in sources,
                           f"{cleared}; then {after}; dragon-fight sources={sorted(sources)}")

        # Running out while the player stays in the End starts nothing.
        self.configure(scaling=True, respawn=False, hours=1)
        self.win_fight()
        mark = self.server.mark()
        victory, _ = self.victory_at()
        elapsed = time.time() * 1000 - victory
        hours = (elapsed + 12_000) / 3_600_000
        self.configure(scaling=True, respawn=True, hours=hours)
        _, cooling = self.victory_at()
        time.sleep(15)
        _, done = self.victory_at()
        started = [line for line in self.server.lines_since(mark) if re.search(STARTED, line)]
        self.results.check(RULE, "the cooldown running out while the player is in the End starts nothing",
                           COOLING in cooling and "run out" in done and not started,
                           f"before: {cooling}; after: {done}; log={started}")

    def same_tick(self, helper):
        """Two players entering in the same tick start one respawn."""
        self.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon]")
        self.wait_dead()
        self.cooldown()

        # Once the cooldown has run out, the same player's next entry, alone, starts the respawn.
        self.leave()
        mark = self.enter()
        started = self.started_since(mark, 15)
        own = [line for line in started if f"{self.player} entered" in line]
        self.results.check(RULE, "after the cooldown runs out, the player's own next entry starts the respawn",
                           len(started) == 1 and len(own) == 1, f"log={started}")
        if not started:
            raise ProbeError("the expired player's re-entry started no respawn")
        deadline = time.monotonic() + 150
        while not self.dragon_alive():
            if time.monotonic() > deadline:
                raise ProbeError("the respawn started after the cooldown summoned no dragon within 150 s")
            time.sleep(3)
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
            probes.configure(scaling=False, respawn=False, hours=6)
            probes.run(f"execute in {END} run kill @e[type=minecraft:ender_dragon]")
        except ProbeError as error:
            results.note(f"respawn cleanup: {error}")
