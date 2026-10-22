"""Real inventory and player-visible regressions for the guide and progression card (#58)."""

import base64
import time

from gates import DIAMOND_TIER, NETHER_GATE, GateProbes, set_key
from probes import OVERWORLD

PROBES = ("journey_book", "progress_card", "bundles")


class PresentationProbes(GateProbes):

    def inventory_state(self):
        query = f"i{next(self._queries)}"
        match = self.server.query(f"asrprobe inventory {self.player} {query}",
                                  rf"ASRPROBE inventory query={query} (?P<rest>[^\r\n]+)")
        return dict(pair.split("=", 1) for pair in match.group("rest").split())

    def command(self, text):
        self.client.request("messages")
        self.client.request("chat", text=text)
        time.sleep(0.5)
        return self.client.request("messages")["lines"]

    def journey_book(self):
        rule = "Journey Guide Book"
        # Runs before any fixture clears the freshly joined player's inventory.
        time.sleep(1)
        state = self.inventory_state()
        text = base64.b64decode(state["text"]).decode("utf-8") if state["text"] != "none" else ""
        self.results.check(rule, "first join supplies one marked, readable guide with metadata",
                           state["guides"] == "1" and int(state["pages"]) > 0
                           and state["title"] == "Ninja6_Survival_Guide" and state["author"] == "Ninja6-MC"
                           and "Nether" in text and "End" in text,
                           f"guides={state['guides']}, pages={state['pages']}, title={state['title']}, "
                           f"author={state['author']}, readable characters={len(text)}")
        self.client.request("reconnect", timeout=35)
        state = self.inventory_state()
        self.results.check(rule, "rejoining does not duplicate the guide", state["guides"] == "1",
                           f"guides={state['guides']}")
        lines = self.command("/journeybook")
        self.results.check(rule, "a carried copy prevents command duplication",
                           self.inventory_state()["guides"] == "1"
                           and any("already carry" in line for line in lines), str(lines))
        for command in ("/journeybook", "/rulesbook", "/asr book"):
            self.run(f"clear {self.player}")
            time.sleep(0.5)
            lines = self.command(command)
            self.results.check(rule, f"{command} replaces a removed guide",
                               self.inventory_state()["guides"] == "1"
                               and any("received the Journey" in line for line in lines), str(lines))
        self.run(f"clear {self.player}")
        self.run(f"give {self.player} minecraft:cobblestone 2304")
        time.sleep(1)
        before = self.near(OVERWORLD, 0, -60, 0, 32, "item", "written_book")
        lines = self.command("/journeybook")
        state = self.inventory_state()
        after = self.near(OVERWORLD, 0, -60, 0, 32, "item", "written_book")
        self.results.check(rule, "a full inventory refuses without adding or dropping a guide",
                           self.count("cobblestone") == 2304 and state["guides"] == "0"
                           and before == after and any("inventory is full" in line for line in lines),
                           f"guides={state['guides']}, ground books {before}->{after}, messages={lines}")
        self.run(f"clear {self.player}")

    def progress_card(self):
        rule = "progress card"
        with open(self.config_path, encoding="utf-8") as handle:
            original = handle.read()
        try:
            self.reload(lambda text: set_key(text, "progress-card", "simple-card", '"ALWAYS"'))
            self.progression()
            lines = self.command("/progress")
            self.results.check(rule, "a fresh player sees Nether in progress, End locked and a next step",
                               any("[>]" in line and "Nether" in line for line in lines)
                               and any("[ ]" in line and "End" in line for line in lines)
                               and any("NEXT STEP:" in line for line in lines), str(lines))
            self.results.check(rule, "the configured simple card adds only printable ASCII",
                               bool(lines) and all(all(32 <= ord(ch) <= 126 for ch in line) for line in lines),
                               str(lines))
            self.progression(NETHER_GATE)
            lines = self.command("/asr progress")
            self.results.check(rule, "earned Nether credit updates the card without reconnecting",
                               any("[x]" in line and "Nether" in line for line in lines)
                               and any("[>]" in line and "End" in line for line in lines), str(lines))
        finally:
            self.reload(lambda _: original)

    def bundles(self):
        rule = "item gate: bundle extraction (R-14)"
        for qualified in (False, True):
            self.progression(*(DIAMOND_TIER,) if qualified else ())
            self.run(f"clear {self.player}")
            self.run(f"give {self.player} minecraft:bundle[minecraft:bundle_contents="
                     '[{id:"minecraft:diamond",count:1}]]')
            time.sleep(1)
            before = self.inventory_state()
            mark = self.server.mark()
            action = self.client.request("bundle_extract", timeout=20)
            time.sleep(1)
            after = self.inventory_state()
            held = self.count("diamond")
            events = [line for line in self.server.lines_since(mark) if "ASRPROBE click" in line]
            self.results.check(rule, "qualified extraction succeeds" if qualified else
                               "an ineligible player cannot extract into their own inventory",
                               before["bundled-diamonds"] == "1" and
                               (held == 1 and after["bundled-diamonds"] == "0" if qualified else
                                held == 0 and after["bundled-diamonds"] == "1")
                               and any("FROM_BUNDLE" in line for line in events),
                               f"diamonds={held}, bundled {before['bundled-diamonds']}->"
                               f"{after['bundled-diamonds']}, action={action}, events={events}")
        self.run(f"clear {self.player}")


def run_all(server, client, player, results, only=PROBES):
    selected = [name for name in PROBES if name in only]
    if not selected:
        return
    probes = PresentationProbes(server, client, player, results)
    # First-join delivery must be observed before prepare() clears the inventory.
    if "journey_book" in selected:
        probes.journey_book()
    if any(name != "journey_book" for name in selected):
        probes.prepare()
        for name in selected:
            if name != "journey_book":
                getattr(probes, name)()
    probes.run(f"clear {player}")
