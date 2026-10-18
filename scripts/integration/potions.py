"""Potion credit attribution and acquisition, using real brewing and player input."""
import os
import time
from credits import CreditProbes, helper_client, HELPER, FURNACE
from gates import GateProbes, set_key
from harness import ProbeError

PROBES = ("potion_brewing",)

class PotionProbes(CreditProbes):
    def brewed(self, player):
        query = f"brew{next(self._queries)}"
        reply = self.server.query(f"asrprobe brewed {player} {query}",
                                  rf"ASRPROBE brewed query={query} speed=(?P<held>true|false)")
        return reply.group("held") == "true"

    def brew(self, client, player, base="awkward", ingredient="sugar"):
        # All three bottles are command-supplied; only hand-loading the ingredient earns credit.
        bottles = ",".join(f'{{Slot:{slot}b,id:"minecraft:potion",count:1,'
                           f'components:{{"minecraft:potion_contents":{{potion:"minecraft:{base}"}}}}}}'
                           for slot in range(3))
        self.setblock(FURNACE, f'brewing_stand{{Fuel:20b,Items:[{bottles}]}}')
        self.run(f"clear {player}")
        self.run(f"give {player} minecraft:{ingredient}")
        time.sleep(0.5)
        self.stand(FURNACE, player=player)
        client.request("open_block", x=FURNACE[0], y=FURNACE[1], z=FURNACE[2])
        loaded = client.request("window_load", item=ingredient, slot=3)
        client.request("close_window")
        if not any(slot["slot"] == 3 and slot["name"] == ingredient for slot in loaded["slots"]):
            raise ProbeError(f"ingredient insert failed: {loaded}")
        time.sleep(22)

    def potion_brewing(self):
        rule = "personal brewing"
        self.reload(lambda text: set_key(text, "potion-progression", "enabled", "true"))
        self.results.check(rule, "fresh player has no speed credit", not self.brewed(self.player), self.player)
        self.brew(self.client, self.player, "swiftness", "gunpowder")
        self.results.check(rule, "converting a gifted potion earns nothing", not self.brewed(self.player), "splash conversion")
        self.run(f"effect clear {self.player} minecraft:speed")
        self.run(f"effect clear {self.player} minecraft:poison")
        mixed = ('minecraft:potion[minecraft:potion_contents={potion:"minecraft:swiftness",'
                 'custom_effects:[{id:"minecraft:poison",duration:200,amplifier:0}]}]')
        self.run(f"give {self.player} {mixed}")
        time.sleep(0.5)
        self.client.request("hold", item="potion")
        self.client.request("drink")
        query = f"mixed{next(self._queries)}"
        reply = self.server.query(f"asrprobe potion-effects {self.player} {query}",
                                  rf"ASRPROBE potion-effects query={query} speed=(?P<speed>true|false) poison=(?P<poison>true|false)")
        self.results.check(rule, "mixed drink preserves poison and withholds speed",
                           reply.group("speed") == "false" and reply.group("poison") == "true", reply.group(0))
        self.run(f"effect clear {self.player} minecraft:poison")
        self.brew(self.helper, HELPER)
        self.results.check(rule, "ingredient loader earns speed", self.brewed(HELPER), HELPER)
        self.results.check(rule, "another player earns nothing", not self.brewed(self.player), self.player)
        gate = GateProbes(self.server, self.client, self.player, self.results)
        gate.prepare()
        potion = 'components:{"minecraft:potion_contents":{potion:"minecraft:swiftness"}}'
        gate.pickup_case(rule, "gifted speed potion", "potion", False, potion)
        self.brew(self.client, self.player)
        self.results.check(rule, "own brewing earns speed", self.brewed(self.player), self.player)
        gate.pickup_case(rule, "speed after own brewing", "potion", True, potion)
        self.reload(lambda text: set_key(text, "potion-progression", "enabled", "false"))


def run_all(server, client, player, results, only=PROBES):
    if "potion_brewing" not in only:
        return
    helper = helper_client(client, os.path.join(os.path.dirname(client.log_path), "potion-helper.log"))
    with helper:
        helper.wait_event("spawn", 120)
        probes = PotionProbes(server, client, player, results, helper)
        probes.prepare()
        try:
            probes.potion_brewing()
        except ProbeError as error:
            results.check("personal brewing", "probe completed", False, str(error))
        finally:
            probes.reload(lambda text: set_key(text, "potion-progression", "enabled", "false"))
            probes.tidy()
            helper.request("quit", timeout=10)
