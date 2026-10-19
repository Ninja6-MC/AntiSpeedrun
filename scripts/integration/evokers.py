"""Optional Totem credit, ordinary and raid-associated personal Evoker kills (#252)."""
import os
import re
import time
from credits import CreditProbes, helper_client, HELPER, CX, CY, CZ, CHEST
from gates import GateProbes, set_key
from harness import ProbeError
from probes import OVERWORLD

PROBES = ("evoker_totems",)
RULE = "Evoker Totems"


def raid_trigger_pattern(player):
    # Bukkit reports -1 during the trigger event, before the new raid is registered.
    return rf"ASRPROBE raid-trigger player={re.escape(player)} id=(?P<id>-?\d+)"


def enable_totem_tier(text):
    for old, new in (
        ("    # totem-tier:", "    totem-tier:"),
        ('    #   items: ["TOTEM_OF_UNDYING"]', '      items: ["TOTEM_OF_UNDYING"]'),
        ('    #   require-credits: ["kill-evoker"]', '      require-credits: ["kill-evoker"]'),
        ('    #   hint: "Kill an Evoker yourself (raid kills count)"',
         '      hint: "Kill an Evoker yourself (raid kills count)"'),
    ):
        if text.count(old) != 1:
            raise ProbeError(f"missing or duplicated Totem fixture line: {old}")
        text = text.replace(old, new, 1)
    return text


class TotemProbes(CreditProbes):
    def kill(self, *, drops=True, environmental=False, raid=False):
        self.tidy()
        self.tp(OVERWORLD, CX + .5, CY, CZ + 3)
        tags = '["asrp","raid-fixture"]' if raid else '["asrp"]'
        mark = self.server.mark()
        self.run(f'execute in {OVERWORLD} run summon minecraft:evoker {CX + .5} {CY} {CZ + .5} '
                 f'{{NoAI:1b,Health:1f,Tags:{tags}}}')
        if raid:
            joined = self.server.wait_for(r"ASRPROBE evoker-raid joined=(?P<joined>\S+)", 10, since=mark)
            if joined.group("joined") != "true":
                raise ProbeError("the Evoker did not join the active raid")
        time.sleep(.5)
        if not drops:
            self.server.query(f"asrprobe nodrops {self.player}", r"ASRPROBE nodrops armed=\S+")
        mark = self.server.mark()
        if environmental:
            self.run("kill @e[type=minecraft:evoker,tag=asrp]")
        else:
            self.run(f"give {self.player} minecraft:wooden_sword")
            self.wait_count("wooden_sword", 1)
            self.client.request("hold", item="wooden_sword")
            self.client.request("attack", entity="evoker")
        event = self.server.wait_for(r"ASRPROBE evoker-death (?P<rest>[^\r\n]+)", 15, since=mark)
        self.results.note(event.group(0))
        return event.group("rest")

    def withdraw(self, allowed):
        self.tidy()
        self.setblock(CHEST, 'chest{Items:[{Slot:0b,id:"minecraft:totem_of_undying",count:1}]}')
        self.stand(CHEST)
        self.client.request("open_block", x=CHEST[0], y=CHEST[1], z=CHEST[2])
        after = self.client.request("window_click", slot=0, button=0, mode=1)
        self.client.request("close_window")
        held = self.count("totem_of_undying")
        left = sum(slot["count"] for slot in after["slots"] if slot["name"] == "totem_of_undying")
        self.results.check(RULE, f"Totem withdrawal {'allowed' if allowed else 'refused'}",
                           held == (1 if allowed else 0) and left == (0 if allowed else 1),
                           f"inventory={held}, chest={left}")
        self.setblock(CHEST, "air")

    def start_raid(self):
        # A villager claims a real bed at night, creating occupied village POI for Raid Omen.
        bx, bz = CX + 8, CZ + 8
        self.run("time set night")
        self.run(f"execute in {OVERWORLD} run setblock {bx} {CY} {bz} minecraft:white_bed[facing=south,part=foot]")
        self.run(f"execute in {OVERWORLD} run setblock {bx} {CY} {bz + 1} minecraft:white_bed[facing=south,part=head]")
        self.run(f'execute in {OVERWORLD} run summon minecraft:villager {bx + .5} {CY} {bz + 1.5} '
                 '{Invulnerable:1b,PersistenceRequired:1b,Tags:["totem-villager"]}')
        self.tp(OVERWORLD, bx - 1.5, CY, bz + .5)
        time.sleep(10)
        mark = self.server.mark()
        self.run(f"effect give {self.player} minecraft:bad_omen 60 0 true")
        event = self.server.wait_for(raid_trigger_pattern(self.player),
                                     70, since=mark)
        self.results.check(RULE, "native Raid Omen triggers an actual village raid", True, event.group(0))

    def collection(self):
        with open(self.config_path, encoding="utf-8") as handle:
            original = handle.read()
        def change(key, value):
            return lambda text: set_key(text, "item-progression", key, value)
        try:
            gate = GateProbes(self.server, self.client, self.player, self.results)
            gate.prepare()
            self.baseline()
            self.baseline(player=HELPER)
            self.run(f"advancement revoke {self.player} everything")
            gate.pickup_case(RULE, "default profile leaves Totems outside End tier", "totem_of_undying", True)
            self.reload(enable_totem_tier)
            gate.pickup_case(RULE, "gifted Totem without personal kill", "totem_of_undying", False)
            self.withdraw(False)
            self.kill(environmental=True)
            self.expect("environmental Evoker death earns nothing", "kill-evoker", False)
            self.server.query(f"asrprobe npc {self.player} on", r"ASRPROBE npc player=\S+ \S+")
            try:
                self.kill()
                self.expect("NPC Evoker killer earns nothing", "kill-evoker", False)
            finally:
                self.server.query(f"asrprobe npc {self.player} off", r"ASRPROBE npc player=\S+ \S+")
            event = self.kill(drops=False)
            self.results.check(RULE, "personal Evoker kill counts without a Totem drop",
                               "drops=none" in event and "credited=true" in event, event)
            self.expect("personal Evoker kill earns credit", "kill-evoker", True, "action")
            self.expect("non-killing helper remains uncredited", "kill-evoker", False, player=HELPER)
            gate.pickup_case(RULE, "own Evoker kill unlocks Totem pickup without End advancements", "totem_of_undying", True)
            self.withdraw(True)
            self.credit("revoke", "kill-evoker")
            self.reload(change("personal-credit-scope", "PERMISSION"))
            gate.pickup_case(RULE, "outside credit scope", "totem_of_undying", True)
            self.reload(change("personal-credit-scope", "ALL"))
            self.server.query(f"asr bypass {self.player} 30s", r"[Bb]ypass", timeout=15)
            gate.pickup_case(RULE, "administrative bypass", "totem_of_undying", True)
            self.server.query(f"asr bypass {self.player} off", r"[Bb]ypass", timeout=15)
            self.reload(change("require-personal-credit", "false"))
            gate.pickup_case(RULE, "personal-credit enforcement disabled", "totem_of_undying", True)
            event = self.kill(drops=False)
            self.results.check(RULE, "recording continues while credit enforcement is off",
                               "credited=true" in event, event)
            self.credit("revoke", "kill-evoker")
            self.reload(change("require-personal-credit", "true"))
            self.start_raid()
            event = self.kill(raid=True)
            self.results.check(RULE, "personal raid Evoker kill counts", "raid=true" in event
                               and "credited=true" in event, event)
            self.expect("raid kill earns action credit", "kill-evoker", True, "action")
            gate.pickup_case(RULE, "raid kill unlocks Totem pickup", "totem_of_undying", True)
        finally:
            self.run("time set day")
            self.run("kill @e[type=minecraft:villager,tag=totem-villager]")
            self.setblock((CX + 8, CY, CZ + 8), "air")
            self.setblock((CX + 8, CY, CZ + 9), "air")
            self.run("effect clear @a minecraft:raid_omen")
            self.run("effect clear @a minecraft:bad_omen")
            self.reload(lambda text: original)
            self.tidy()


def run_all(server, client, player, results, only=PROBES):
    if "evoker_totems" not in only:
        return
    helper = helper_client(client, os.path.join(os.path.dirname(client.log_path), "evoker-helper.log"))
    with helper:
        helper.wait_event("spawn", 120)
        probes = TotemProbes(server, client, player, results, helper)
        probes.prepare()
        try:
            probes.collection()
        except ProbeError as error:
            results.check(RULE, "probe completed", False, str(error))
        finally:
            helper.request("quit", timeout=10)
