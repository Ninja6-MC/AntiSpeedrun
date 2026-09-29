#!/usr/bin/env python3
"""Boot a Paper or Folia server with the AntiSpeedrun jar and assert that the plugin started.

  boot-smoke.py --server-jar server.jar --plugin-jar AntiSpeedrun-<version>.jar \
      --workdir run --java /path/to/java [--label "Folia 1.21.4"]

The server gets a fresh working directory holding only the plugin. Once it logs "Done (" it
is sent "stop"; a server that never gets there is stopped at --boot-timeout, and one that
ignores "stop" is killed at --stop-timeout. Its console output is kept at <workdir>/server.log
whatever happens, so the workflow can upload it.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import time

DONE = re.compile(r"Done \([0-9.,]+s\)!")

# Every one of these must appear before the server is told to stop. "Enabling AntiSpeedrun"
# is logged by Bukkit BEFORE onEnable runs, so on its own it proves only that the jar loaded;
# the gate-compilation and "enabled successfully" lines are only reached by an onEnable that
# ran to the end. The gate line is the only place a tier collision in config.yml is checked
# against the real Material enum: the unit tests compile config.yml against a fixture of about
# 100 constants, not Bukkit's ~1,400, so do not weaken it to a grep for "Enabling".
REQUIRED_BEFORE_STOP = (
    (re.compile(r"Enabling AntiSpeedrun"), "the server never loaded AntiSpeedrun"),
    (re.compile(r"Item gates compiled: [1-9][0-9]* materials across [1-9][0-9]* tiers"),
     "AntiSpeedrun never compiled its item gates against the real Material enum "
     "(a tier collision or an unparseable config.yml does this)"),
    (re.compile(r"AntiSpeedrun enabled successfully\."), "AntiSpeedrun's onEnable did not complete"),
    (DONE, "the server did not finish starting"),
)

# None of these may appear anywhere in the log, shutdown included.
FORBIDDEN = (
    (re.compile(r"unresolvable tier collision", re.I),
     "item-progression.gated-items contains a tier collision over the real Material enum"),
    (re.compile(r"UnsupportedOperationException|is not supported on Folia", re.I),
     "the server rejected an unsupported API call"),
    (re.compile(r"Could not load '?plugins|Error occurred while (enabling|disabling|loading) AntiSpeedrun",
                re.I),
     "the server reported a plugin failure"),
    (re.compile(r"(ERROR|SEVERE)\]:? \[AntiSpeedrun\]"), "AntiSpeedrun logged an error"),
    # The shipped config.yml is the only config on these servers, so any warning about it means
    # a default stopped matching the server it runs on (a material renamed, say) and is quietly
    # gating something other than what it documents.
    (re.compile(r"(WARN|WARNING)\]:? \[AntiSpeedrun\] config\.yml"),
     "AntiSpeedrun warned about its shipped config.yml on this server"),
    (re.compile(r"^\s+at com\.ninja6\.antispeedrun\.", re.M), "a stack trace passed through AntiSpeedrun"),
)

# Shutting down disables the plugin, so this is only a failure before "stop" was sent: that is
# onEnable refusing to start, or the server disabling the plugin after an exception.
FORBIDDEN_BEFORE_STOP = (
    (re.compile(r"Disabling AntiSpeedrun"), "AntiSpeedrun was disabled while the server was running"),
)


def check_log(before_stop, after_stop=""):
    """Every failure the log shows, as a list of messages; empty when the boot passed."""
    whole = before_stop + after_stop
    problems = [message for pattern, message in REQUIRED_BEFORE_STOP if not pattern.search(before_stop)]
    problems += [message for pattern, message in FORBIDDEN if pattern.search(whole)]
    problems += [message for pattern, message in FORBIDDEN_BEFORE_STOP if pattern.search(before_stop)]
    return problems


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def boot(args):
    workdir = os.path.abspath(args.workdir)
    if os.path.exists(workdir):
        shutil.rmtree(workdir)
    os.makedirs(os.path.join(workdir, "plugins"))
    shutil.copy(args.plugin_jar, os.path.join(workdir, "plugins"))
    shutil.copy(args.server_jar, os.path.join(workdir, "server.jar"))
    with open(os.path.join(workdir, "eula.txt"), "w", encoding="utf-8") as handle:
        handle.write("eula=true\n")
    # Only the port is set; everything else is the server's own default, as an operator gets it.
    with open(os.path.join(workdir, "server.properties"), "w", encoding="utf-8") as handle:
        handle.write(f"server-port={args.port}\n")
    log_path = os.path.join(workdir, "server.log")

    command = [args.java, f"-Xmx{args.memory}", "-jar", "server.jar", "--nogui"]
    print(f"Booting {args.label}: {' '.join(command)}", flush=True)
    with open(log_path, "wb") as log:
        server = subprocess.Popen(command, cwd=workdir, stdin=subprocess.PIPE,
                                  stdout=log, stderr=subprocess.STDOUT)
        started = time.monotonic()
        while server.poll() is None and time.monotonic() - started < args.boot_timeout:
            if DONE.search(read(log_path)):
                break
            time.sleep(2)

        booted_in = time.monotonic() - started
        before_stop = read(log_path)
        if server.poll() is None:
            if not DONE.search(before_stop):
                print(f"::error::{args.label} did not finish starting within {args.boot_timeout}s.")
            else:
                print(f"{args.label} started in {booted_in:.0f}s; stopping it.", flush=True)
            try:
                server.stdin.write(b"stop\n")
                server.stdin.flush()
            except OSError:
                pass
            try:
                server.wait(timeout=args.stop_timeout)
            except subprocess.TimeoutExpired:
                print(f"::error::{args.label} ignored 'stop' for {args.stop_timeout}s; killing it.")
                server.kill()
                server.wait()
        else:
            print(f"::error::{args.label} exited with status {server.returncode} before it was stopped.")

    after_stop = read(log_path)[len(before_stop):]
    return before_stop, after_stop


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--server-jar", required=True)
    parser.add_argument("--plugin-jar", required=True)
    parser.add_argument("--workdir", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--label", default="server")
    parser.add_argument("--memory", default="3G")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--boot-timeout", type=int, default=240)
    parser.add_argument("--stop-timeout", type=int, default=60)
    args = parser.parse_args(argv)

    before_stop, after_stop = boot(args)
    problems = check_log(before_stop, after_stop)
    for line in (before_stop + after_stop).splitlines():
        if "Item gates compiled:" in line:
            print(line)
    if problems:
        for problem in problems:
            print(f"::error::{args.label}: {problem}.")
        print("---- last 200 lines of server.log ----")
        print("\n".join((before_stop + after_stop).splitlines()[-200:]))
        return 1
    print(f"AntiSpeedrun started cleanly on {args.label}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
