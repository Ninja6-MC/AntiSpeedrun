"""Personal Shulker kills, actual death drops and the optional box gate (#250)."""
import os
import time
from credits import CreditProbes, helper_client, HELPER, CX, CY, CZ, CHEST
from gates import GateProbes, set_key
from harness import ProbeError
from probes import OVERWORLD

PROBES = ("shulker_collection",)
RULE = "Shulker collection"


class ShulkerProbes(CreditProbes):
    def kill(self, *, drops=True, environmental=False):
        self.tidy()
        self.tp(OVERWORLD, CX + .5, CY, CZ + 3)
        self.run(f'execute in {OVERWORLD} run summon minecraft:shulker {CX + .5} {CY} {CZ + .5} '
                 '{NoAI:1b,Health:0.1f,Tags:["asrp"]}')
        time.sleep(.5)
        self.arm("shulker_shell")
        if not drops:
            self.server.query(f"asrprobe nodrops {self.player}", r"ASRPROBE nodrops armed=\S+")
        mark = self.server.mark()
        if environmental:
            self.run("kill @e[type=minecraft:shulker,tag=asrp]")
        else:
            self.run(f"give {self.player} minecraft:wooden_sword")
            self.wait_count("wooden_sword", 1)
            self.client.request("hold", item="wooden_sword")
            self.client.request("attack", entity="shulker")
        event = self.server.wait_for(r"ASRPROBE shulker-death (?P<rest>[^\r\n]+)", 15, since=mark)
        self.results.note(event.group(0))
        return event.group("rest")

    def withdraw(self, material, allowed):
        self.tidy()
        self.setblock(CHEST, f'chest{{Items:[{{Slot:0b,id:"minecraft:{material}",count:1}}]}}')
        self.stand(CHEST)
        self.client.request("open_block", x=CHEST[0], y=CHEST[1], z=CHEST[2])
        after = self.client.request("window_click", slot=0, button=0, mode=1)
        self.client.request("close_window")
        held = self.count(material)
        left = sum(slot["count"] for slot in after["slots"] if slot["name"] == material)
        self.results.check(RULE, f"{material} withdrawal {'allowed' if allowed else 'refused'}",
                           held == (1 if allowed else 0) and left == (0 if allowed else 1),
                           f"inventory={held}, chest={left}")
        self.setblock(CHEST, "air")

    def collection(self):
        with open(self.config_path, encoding="utf-8") as handle:
            original = handle.read()
        def change(key, value):
            return lambda text: set_key(text, "item-progression", key, value)
        try:
            self.reload(change("require-shulker-collection", "true"))
            self.reload(change("require-personal-credit", "false"))
            self.run(f"advancement grant {self.player} everything")
            self.baseline()
            self.baseline(player=HELPER)
            gate = GateProbes(self.server, self.client, self.player, self.results)
            gate.prepare()
            gate.pickup_case(RULE, "gifted shell without credit", "shulker_shell", False)
            self.withdraw("shulker_shell", False)
            gate.pickup_case(RULE, "storage box keeps its ordinary tier", "shulker_box", True)
            gate.pickup_case(RULE, "dyed storage box keeps its ordinary tier", "white_shulker_box", True)
            self.reload(change("gate-shulker-boxes", "true"))
            gate.pickup_case(RULE, "box option refuses an unearned box", "shulker_box", False)
            self.withdraw("red_shulker_box", False)
            self.kill(drops=False)
            self.expect("kill without a shell drop earns nothing", "kill-shulker", False)
            self.kill(environmental=True)
            self.expect("shell dropped without a player kill earns nothing", "kill-shulker", False)
            self.server.query(f"asrprobe npc {self.player} on", r"ASRPROBE npc player=\S+ \S+")
            try:
                self.kill()
                self.expect("NPC killer with a shell drop earns nothing", "kill-shulker", False)
            finally:
                self.server.query(f"asrprobe npc {self.player} off", r"ASRPROBE npc player=\S+ \S+")
            event = self.kill()
            self.results.check(RULE, "personal kill credits before the shell is picked up",
                               "drops=SHULKER_SHELL:1" in event and "credited=true" in event
                               and self.count("shulker_shell") == 0, event)
            self.expect("shell-dropping personal kill earns credit", "kill-shulker", True, "action")
            self.expect("spectator earns nothing", "kill-shulker", False, player=HELPER)
            self.tp(OVERWORLD, CX + .5, CY, CZ + .5)
            self.wait_count("shulker_shell", 1)
            self.results.check(RULE, "killer can pick up the actual shell drop", True, str(self.client.request("inventory")))
            self.withdraw("shulker_shell", True)
            gate.pickup_case(RULE, "earned credit allows dyed box", "red_shulker_box", True)
            self.run(f"advancement revoke {self.player} only minecraft:nether/find_fortress")
            self.reload()
            gate.pickup_case(RULE, "earned credit preserves ordinary tier prerequisites", "shulker_shell", False)
            self.run(f"advancement grant {self.player} everything")
            self.credit("revoke", "kill-shulker")
            self.reload(change("personal-credit-scope", "PERMISSION"))
            gate.pickup_case(RULE, "player outside credit scope", "shulker_shell", True)
            self.reload(change("personal-credit-scope", "ALL"))
            self.server.query(f"asr bypass {self.player} 30s", r"[Bb]ypass", timeout=15)
            gate.pickup_case(RULE, "administrative bypass", "shulker_shell", True)
            self.server.query(f"asr bypass {self.player} off", r"[Bb]ypass", timeout=15)
            self.reload(change("require-shulker-collection", "false"))
            gate.pickup_case(RULE, "disabled optional credit", "shulker_shell", True)
        finally:
            self.disarm()
            self.reload(lambda text: original)
            self.tidy()


def run_all(server, client, player, results, only=PROBES):
    if "shulker_collection" not in only:
        return
    helper = helper_client(client, os.path.join(os.path.dirname(client.log_path), "shulker-helper.log"))
    with helper:
        helper.wait_event("spawn", 120)
        probes = ShulkerProbes(server, client, player, results, helper)
        probes.prepare()
        try:
            probes.collection()
        except ProbeError as error:
            results.check(RULE, "probe completed", False, str(error))
        finally:
            helper.request("quit", timeout=10)
