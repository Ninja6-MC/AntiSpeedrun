"""Server and client lifecycle for the real-server integration harness.

A Server is a disposable Paper or Folia process in a fresh working directory, driven through its
console and observed through its log. A Client is the scripted player in client/client.js, driven
one JSON request at a time. Both are context managers that kill their process on the way out, so
a probe that raises leaves nothing running.
"""

import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time

DONE = re.compile(r"Done \([0-9.,]+s\)!")
# Paper colours command feedback on its console even when stdout is a file.
ANSI = re.compile(r"\x1b\[[0-9;]*m")
HERE = os.path.dirname(os.path.abspath(__file__))


class ProbeError(Exception):
    """A probe could not reach the state it asserts on: a setup or harness failure, not a verdict."""


def write_properties(path, values):
    with open(path, "w", encoding="utf-8") as handle:
        for key, value in values.items():
            handle.write(f"{key}={value}\n")


def read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except FileNotFoundError:
        return ""


def platform(log):
    """"folia" or "paper", from the server's own "This server is running" line."""
    match = re.search(r"This server is running (\w+)", log)
    return "folia" if match and match.group(1).lower() == "folia" else "paper"


class Server:
    """One disposable server. Everything it writes stays under workdir for the run's artifacts."""

    def __init__(self, workdir, server_jar, plugins, java="java", memory="3G", port=25599,
                 properties=None):
        self.workdir = os.path.abspath(workdir)
        self.server_jar = server_jar
        self.plugins = list(plugins)
        self.java = java
        self.memory = memory
        self.port = port
        self.properties = properties or {}
        self.log_path = os.path.join(self.workdir, "server.log")
        self.process = None
        self._log = None
        self.platform = None

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *exc):
        self.stop()

    def start(self):
        if os.path.exists(self.workdir):
            shutil.rmtree(self.workdir)
        os.makedirs(os.path.join(self.workdir, "plugins"))
        for plugin in self.plugins:
            shutil.copy(plugin, os.path.join(self.workdir, "plugins"))
        shutil.copy(self.server_jar, os.path.join(self.workdir, "server.jar"))
        with open(os.path.join(self.workdir, "eula.txt"), "w", encoding="utf-8") as handle:
            handle.write("eula=true\n")
        properties = {
            "server-port": self.port,
            "online-mode": "false",
            "enforce-secure-profile": "false",
            # A flat overworld, so no probe depends on terrain.
            "level-type": "minecraft\\:flat",
            "generator-settings": '{"layers":[{"block":"minecraft:bedrock","height":1},'
                                  '{"block":"minecraft:stone","height":3}],"biome":"minecraft:plains"}',
            "spawn-protection": 0,
            "allow-flight": "true",
            "view-distance": 6,
            "simulation-distance": 6,
        }
        properties.update(self.properties)
        write_properties(os.path.join(self.workdir, "server.properties"), properties)
        self._log = open(self.log_path, "wb")
        self.process = subprocess.Popen(
            [self.java, f"-Xmx{self.memory}", "-jar", "server.jar", "--nogui"],
            cwd=self.workdir, stdin=subprocess.PIPE, stdout=self._log, stderr=subprocess.STDOUT)

    def log(self):
        return ANSI.sub("", read(self.log_path))

    def mark(self):
        """A position in the log; pass it to wait_for or lines_since to look only after it."""
        return len(self.log())

    def lines_since(self, mark, pattern=None):
        lines = self.log()[mark:].splitlines()
        if pattern is None:
            return lines
        return [line for line in lines if re.search(pattern, line)]

    def wait_for(self, pattern, timeout, since=0):
        deadline = time.monotonic() + timeout
        compiled = re.compile(pattern)
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise ProbeError(f"the server exited with status {self.process.returncode}")
            match = compiled.search(self.log()[since:])
            if match:
                return match
            time.sleep(0.25)
        raise ProbeError(f"timed out after {timeout}s waiting for /{pattern}/ in the server log")

    def wait_started(self, timeout):
        match = self.wait_for(DONE.pattern, timeout)
        self.platform = platform(self.log())
        return match

    def command(self, line):
        if self.process is None or self.process.poll() is not None:
            raise ProbeError("the server is not running")
        self.process.stdin.write((line + "\n").encode("utf-8"))
        self.process.stdin.flush()

    def query(self, line, pattern, timeout=10):
        """Runs a console command and returns the first match of pattern in what follows it."""
        mark = self.mark()
        self.command(line)
        return self.wait_for(pattern, timeout, since=mark)

    def stop(self, timeout=60):
        if self.process is None:
            return
        if self.process.poll() is None:
            try:
                self.command("stop")
                self.process.wait(timeout=timeout)
            except (ProbeError, OSError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait()
        if self._log:
            self._log.close()
            self._log = None


class Client:
    """The scripted player. Requests are synchronous; unsolicited events are kept in order."""

    def __init__(self, host, port, username, version, log_path, node="node"):
        self.args = [node, os.path.join(HERE, "client", "client.js"), "--host", host,
                     "--port", str(port), "--username", username, "--version", version]
        self.log_path = log_path
        self.process = None
        self.replies = queue.Queue()
        self.events = queue.Queue()
        self._next = 0
        self._stderr = None

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *exc):
        self.close()

    def start(self):
        self._stderr = open(self.log_path, "wb")
        self.process = subprocess.Popen(self.args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self._stderr, cwd=os.path.join(HERE, "client"))
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for raw in self.process.stdout:
            try:
                message = json.loads(raw.decode("utf-8"))
            except ValueError:
                continue
            (self.replies if "id" in message else self.events).put(message)
        self.events.put({"event": "exited"})

    def wait_event(self, name, timeout):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ProbeError(f"timed out after {timeout}s waiting for client event {name}")
            try:
                event = self.events.get(timeout=remaining)
            except queue.Empty:
                continue
            if event.get("event") == name:
                return event
            if event.get("event") in ("kicked", "error", "end", "exited"):
                raise ProbeError(f"the client stopped while waiting for {name}: {event}")

    def request(self, op, timeout=30, **fields):
        if self.process.poll() is not None:
            raise ProbeError(f"the client exited with status {self.process.returncode}")
        self._next += 1
        ident = self._next
        payload = json.dumps({"id": ident, "op": op, **fields}) + "\n"
        self.process.stdin.write(payload.encode("utf-8"))
        self.process.stdin.flush()
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ProbeError(f"client request {op} timed out after {timeout}s")
            try:
                reply = self.replies.get(timeout=min(remaining, 1.0))
            except queue.Empty:
                if self.process.poll() is not None:
                    raise ProbeError(f"the client exited during {op}")
                continue
            if reply.get("id") != ident:
                continue
            if not reply.get("ok"):
                raise ProbeError(f"client request {op} failed: {reply.get('error')}")
            return reply

    def close(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            try:
                self.process.stdin.close()
                self.process.wait(timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait()
        if self._stderr:
            self._stderr.close()
            self._stderr = None
