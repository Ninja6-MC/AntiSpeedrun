"""Full-run containment and explicit focused-run deadline controls."""

from contextlib import ExitStack
import json
from pathlib import Path
import re
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))
import run as integration_run  # noqa: E402


class IntegrationDeadlineTest(unittest.TestCase):
    def run_harness(self, deadline=None, expire=False, process_state="running"):
        server, client, timer = mock.MagicMock(), mock.MagicMock(), mock.Mock()
        server.__enter__.return_value = server
        client.__enter__.return_value = client
        server.log.return_value = ""
        server.platform = "paper"
        for owner in (client, server):
            if process_state == "absent":
                owner.process = None
            else:
                owner.process.poll.return_value = None if process_state == "running" else 0
        module = SimpleNamespace(run_all=mock.Mock())
        with tempfile.TemporaryDirectory() as workdir, ExitStack() as stack:
            timer_factory = stack.enter_context(mock.patch.object(integration_run.threading, "Timer",
                                                                  return_value=timer))
            stack.enter_context(mock.patch.object(integration_run, "Server", return_value=server))
            stack.enter_context(mock.patch.object(integration_run, "Client", return_value=client))
            stack.enter_context(mock.patch.object(integration_run, "Probes"))
            stack.enter_context(mock.patch.object(integration_run, "MODULES", (module,)))
            stack.enter_context(mock.patch.object(integration_run, "plugin_failures", return_value=[]))
            stack.enter_context(mock.patch.object(integration_run.sys, "stdout"))
            if expire:
                def interrupted(*args):
                    timer_factory.call_args.args[1]()
                    raise integration_run.ProbeError("server process exited")
                module.run_all.side_effect = interrupted
            args = ["--server-jar", "server.jar", "--plugin-jar", "plugin.jar",
                    "--probe-jar", "probe.jar", "--client-version", "1.21.11",
                    "--workdir", workdir, "--probe", "evoker_totems"]
            if deadline is not None:
                args += ["--deadline", str(deadline)]
            status = integration_run.main(args)
            summary = json.loads((Path(workdir) / "results.json").read_text(encoding="utf-8"))
        timer.start.assert_called_once_with()
        timer.cancel.assert_called_once_with()
        client.close.assert_called_once_with()
        server.stop.assert_called_once_with()
        return status, summary, timer_factory.call_args.args[0], client, server

    def test_full_run_default_and_ci_limits_leave_cleanup_and_upload_headroom(self):
        status, summary, deadline, _, _ = self.run_harness()
        self.assertEqual(status, 0)
        self.assertTrue(summary["passed"])
        self.assertEqual(deadline, 2400)
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml").read_text()
        gameplay = workflow.split("  gameplay-probes:\n", 1)[1]
        job_minutes = int(re.search(r"^    timeout-minutes: (\d+)$", gameplay, re.M)[1])
        step_minutes = int(re.search(r"- name: Run Gameplay Probes\n        timeout-minutes: (\d+)",
                                     gameplay)[1])
        self.assertGreaterEqual(step_minutes * 60, deadline + 300)
        self.assertGreaterEqual(job_minutes, step_minutes + 10)

    def test_focused_run_preserves_explicit_shorter_deadline(self):
        status, summary, deadline, _, _ = self.run_harness(deadline=60)
        self.assertEqual(deadline, 60)
        self.assertEqual(status, 0)
        self.assertTrue(summary["passed"])

    def test_expiry_kills_live_processes_and_writes_failing_result(self):
        status, summary, _, client, server = self.run_harness(deadline=1, expire=True)
        client.process.kill.assert_called_once_with()
        server.process.kill.assert_called_once_with()
        self.assertEqual(status, 1)
        self.assertFalse(summary["passed"])
        self.assertIn({"rule": "harness", "check": "run completed", "passed": False,
                       "detail": "the run passed its deadline"}, summary["checks"])

    def test_expiry_tolerates_unstarted_or_already_exited_processes(self):
        for state in ("absent", "exited"):
            with self.subTest(state=state):
                status, summary, _, client, server = self.run_harness(expire=True, process_state=state)
                self.assertEqual(status, 1)
                self.assertFalse(summary["passed"])
                if state == "exited":
                    client.process.kill.assert_not_called()
                    server.process.kill.assert_not_called()


if __name__ == "__main__":
    unittest.main()
