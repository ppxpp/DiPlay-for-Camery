"""Run with a host-compiled pioneer_command_runner binary as the first argument."""
import pathlib
import fcntl
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

RUNNER = str(pathlib.Path(sys.argv.pop(1)).resolve())
SHELL = shutil.which("sh")


class CommandRunnerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.guard = str(pathlib.Path(self.directory.name) / "command.lock")

    def run_command(self, command, timeout=100, cancel="-"):
        return subprocess.run([RUNNER, str(timeout), cancel, SHELL, command, self.guard, str(os.getpid())],
                              capture_output=True, text=True, timeout=5)

    def test_normal_exit_preserves_output_and_status(self):
        result = self.run_command("printf ready; exit 7", timeout=1000)
        self.assertEqual(7, result.returncode)
        self.assertEqual("ready", result.stdout)

    def test_closed_stdout_does_not_hide_a_hung_process(self):
        result = self.run_command("exec 1>&- 2>&-; sleep 10")
        self.assertEqual(124, result.returncode)

    def test_timeout_reaps_grandchildren_before_returning(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = pathlib.Path(directory) / "late-rule"
            result = self.run_command(f"(sleep 0.4; printf late > {shlex.quote(str(marker))}) & wait")
            self.assertEqual(124, result.returncode)
            time.sleep(0.5)
            self.assertFalse(marker.exists(), "a timed-out child mutated state after cleanup")

    def test_cancellation_prevents_late_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            cancel = pathlib.Path(directory) / "cancel"
            cancel.touch()
            marker = pathlib.Path(directory) / "late-rule"
            result = self.run_command(f"sleep 0.4; touch {shlex.quote(str(marker))}", cancel=str(cancel))
            self.assertEqual(125, result.returncode)
            time.sleep(0.5)
            self.assertFalse(marker.exists())

    def test_parent_death_cannot_leave_a_command_behind_recovery(self):
        marker = pathlib.Path(self.directory.name) / "late-rule"
        command = f"sleep 0.6; touch {shlex.quote(str(marker))}"
        arguments = [RUNNER, "3000", "-", SHELL, command, self.guard]
        code = ("import os, subprocess, time; "
                f"subprocess.Popen({arguments!r} + [str(os.getpid())], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); "
                "time.sleep(0.1); os._exit(0)")
        subprocess.run([sys.executable, "-c", code], check=True, timeout=3)
        deadline = time.monotonic() + 3
        with open(self.guard, "a") as guard:
            while True:
                try:
                    fcntl.lockf(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    self.assertLess(time.monotonic(), deadline, "old command group did not release recovery lock")
                    time.sleep(0.01)
            # Recovery now owns the barrier. No old process may mutate after this point.
            time.sleep(0.7)
            self.assertFalse(marker.exists())
            fcntl.lockf(guard, fcntl.LOCK_UN)


if __name__ == "__main__":
    unittest.main()
