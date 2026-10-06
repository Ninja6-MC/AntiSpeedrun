#!/usr/bin/env python3
"""Run the real-server gameplay probes against one server jar and the AntiSpeedrun jar.

  python scripts/integration/run.py --server-jar paper.jar \
      --plugin-jar build/libs/AntiSpeedrun-<version>.jar \
      --probe-jar build/libs/AntiSpeedrunProbe.jar --client-version 1.21.11 --workdir run-probes

The server boots in a fresh <workdir>/server with only the plugins named here, a scripted player
joins as a non-operator, and each probe asserts on what the server reports. --deadline bounds the
whole run: when it passes, the server and the client are killed and the run fails. Whatever
happens, <workdir> keeps server/server.log, client.log and results.json (server build, plugin
version, every check and its evidence). The exit status is 0 only when every check passed.

The client speaks the protocols mineflayer supports. For a newer server, --via fetches the pinned
ViaVersion and ViaBackwards releases from Hangar into <workdir>/cache and installs them, so an
older --client-version can join.
"""

import argparse
import hashlib
import importlib.util
import json
import os
import re
import sys
import threading
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from harness import Client, ProbeError, Server  # noqa: E402
import anti_cheese  # noqa: E402
import credits  # noqa: E402
import gates  # noqa: E402
import trims  # noqa: E402
from probes import Results  # noqa: E402

USER_AGENT = "AntiSpeedrun-CI/1.0 (+https://github.com/Ninja6-MC/AntiSpeedrun)"
# Pinned by version and SHA-256, as Hangar publishes them. Test servers only.
VIA = (
    ("ViaVersion-5.12.0.jar",
     "https://hangarcdn.papermc.io/plugins/ViaVersion/ViaVersion/versions/5.12.0/PAPER/ViaVersion-5.12.0.jar",
     "c4d512fa9760fa41d17abaedde12aa1f4c9bde920d0a992fe0fc016962f126be"),
    ("ViaBackwards-5.12.0.jar",
     "https://hangarcdn.papermc.io/plugins/ViaVersion/ViaBackwards/versions/5.12.0/PAPER/ViaBackwards-5.12.0.jar",
     "f902f7da7eb99e8bfaf461f80283c4e2750b7d9727e6b508ea4bb9163f55b1db"),
)
PLAYER = "AsrProbe"
# The gate probes run first: they set the player's progression case by case. The credit probes
# set the player's credits the same way, the trim probes its advancements, and the anti-cheese
# probes then grant every advancement and credit once and leave them so.
MODULES = (gates, credits, trims, anti_cheese)
PROBES = tuple(name for module in MODULES for name in module.PROBES)
SERVER_BUILD = re.compile(r"This server is running (?P<build>[^\r\n]+)")
PLUGIN_VERSION = re.compile(r"Enabling AntiSpeedrun v(?P<version>\S+)")
PROBE_SERVER = re.compile(r"ASRPROBE server (?P<line>[^\r\n]+)")


def load_boot_smoke():
    path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "boot-smoke.py")
    spec = importlib.util.spec_from_file_location("boot_smoke", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fetch_pinned(cache, name, url, sha256):
    os.makedirs(cache, exist_ok=True)
    path = os.path.join(cache, name)
    if not os.path.exists(path):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=60) as response, open(path + ".part", "wb") as out:
            out.write(response.read())
        os.replace(path + ".part", path)
    with open(path, "rb") as handle:
        actual = hashlib.sha256(handle.read()).hexdigest()
    if actual != sha256:
        os.remove(path)
        raise ProbeError(f"{name}: expected SHA-256 {sha256}, got {actual}")
    return path


def plugin_failures(log):
    """AntiSpeedrun errors, warnings about config.yml and stack traces anywhere in the log."""
    smoke = load_boot_smoke()
    return [message for pattern, message in smoke.FORBIDDEN if pattern.search(log)]


def main(argv=None):
    # Check details quote what the player was told, emoji included, whatever the console encoding.
    sys.stdout.reconfigure(errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--server-jar", required=True)
    parser.add_argument("--plugin-jar", required=True)
    parser.add_argument("--probe-jar", required=True)
    parser.add_argument("--client-version", required=True,
                        help="the protocol version the scripted player speaks, e.g. 1.21.11")
    parser.add_argument("--workdir", required=True)
    parser.add_argument("--via", action="store_true",
                        help="install the pinned ViaVersion and ViaBackwards on the server")
    parser.add_argument("--java", default="java")
    parser.add_argument("--node", default="node")
    parser.add_argument("--memory", default="3G")
    parser.add_argument("--port", type=int, default=25599)
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--deadline", type=int, default=1500, help="seconds the whole run may take")
    parser.add_argument("--label", default="server")
    parser.add_argument("--probe", action="append", choices=PROBES,
                        help="run only this probe; repeatable (default: all)")
    args = parser.parse_args(argv)

    workdir = os.path.abspath(args.workdir)
    os.makedirs(workdir, exist_ok=True)
    results_path = os.path.join(workdir, "results.json")
    # A previous run's verdict must never be mistaken for this one's.
    if os.path.exists(results_path):
        os.remove(results_path)

    results = Results()
    summary = {"label": args.label, "client_version": args.client_version, "via": args.via}
    plugins = [args.plugin_jar, args.probe_jar]
    server = Server(os.path.join(workdir, "server"), args.server_jar, plugins, java=args.java,
                    memory=args.memory, port=args.port)
    client = Client("127.0.0.1", args.port, PLAYER, args.client_version,
                    os.path.join(workdir, "client.log"), node=args.node)
    expired = threading.Event()

    def abort():
        expired.set()
        for process in (client.process, server.process):
            if process is not None and process.poll() is None:
                process.kill()

    watchdog = threading.Timer(args.deadline, abort)
    watchdog.daemon = True
    watchdog.start()
    try:
        if args.via:
            plugins += [fetch_pinned(os.path.join(workdir, "cache"), *via) for via in VIA]
            server.plugins = plugins
        with server:
            server.wait_started(args.boot_timeout)
            with client:
                client.wait_event("spawn", 120)
                server.wait_for(rf"ASRPROBE join name={PLAYER}", 30)
                for module in MODULES:
                    module.run_all(server, client, PLAYER, results, args.probe or PROBES)
                client.request("quit", timeout=10)
    except Exception as error:  # noqa: BLE001 - any failure still writes results.json
        if expired.is_set():
            reason = "the run passed its deadline"
        elif isinstance(error, ProbeError):
            reason = str(error)
        else:
            reason = f"{type(error).__name__}: {error}"
        results.check("harness", "run completed", False, reason)
    finally:
        watchdog.cancel()
        client.close()
        server.stop()

    log = server.log()
    for name, pattern, group in (("server_build", SERVER_BUILD, "build"),
                                 ("plugin_version", PLUGIN_VERSION, "version"),
                                 ("probe_server", PROBE_SERVER, "line")):
        match = pattern.search(log)
        summary[name] = match.group(group) if match else None
    failures = plugin_failures(log)
    results.check("harness", "the server log shows no AntiSpeedrun error, config warning or stack trace",
                  not failures, "; ".join(failures) or "clean")
    summary["platform"] = server.platform
    summary["checks"] = results.checks
    summary["notes"] = results.notes
    summary["passed"] = results.passed
    with open(results_path, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2)

    print(f"{args.label}: {summary['server_build']}; AntiSpeedrun {summary['plugin_version']}; "
          f"client {args.client_version}{' via ViaVersion' if args.via else ''}")
    failed = [check for check in results.checks if not check["passed"]]
    known = [check for check in results.checks if check.get("status") == "known-failure"]
    print(f"{len(results.checks) - len(failed)} of {len(results.checks)} checks passed"
          f"{f', {len(known)} of them known failures' if known else ''}.")
    for check in failed:
        print(f"::error::{args.label}: {check['rule']}: {check['check']} -- {check['detail']}")
    return 0 if results.passed else 1


if __name__ == "__main__":
    sys.exit(main())
