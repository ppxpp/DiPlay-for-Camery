"""Source probes for the HUD process/IPC boundary; no Android build or device needed."""
from pathlib import Path
import re
import unittest


MAIN = Path(__file__).resolve().parents[2] / "main"
JAVA = MAIN / "java/com/shilapi/xcertplay/pioneer"


class UnifiedHudBoundaryTest(unittest.TestCase):
    def test_app_has_no_legacy_disk_or_process_transport(self):
        client = (JAVA / "PioneerHudFilter.kt").read_text()
        self.assertIn("PioneerRootSession.request(", client)
        for forbidden in ("java.io.File", "android.system.Os", "/proc/", "app_process",
                          "PioneerRootDispatcher", "FileObserver", "writeText(", "readText("):
            self.assertNotIn(forbidden, client)
        # Frame readiness and diagnostic snapshots must stay entirely local.
        for name in ("ready", "snapshot"):
            method = re.search(r"fun " + name + r"\(\).*?\n    }", client, re.S).group()
            self.assertNotIn("request(", method)

    def test_shared_code_never_owns_a_second_java_process_or_exits_the_host(self):
        helper = (JAVA / "PioneerHudFilterHelper.java").read_text()
        service = (JAVA / "PioneerRootHudService.java").read_text()
        for source in (helper, service):
            self.assertNotIn("System.exit(", source)
            self.assertNotRegex(source, r"static\s+void\s+main\s*\(")
            self.assertNotIn("app_process", source)
        # Native runtime-host sets PDEATHSIG and verifies this exact unified-root parent PID.
        self.assertRegex(helper, r"new ProcessBuilder\(runtimeHost.getPath\(\),\s*"
                                 r"String.valueOf\(Process.myPid\(\)\),\s*injector.getPath\(\)")
        native = (MAIN / "cpp/wireless/pioneer_hud_runtime_host.c").read_text()
        self.assertIn("PR_SET_PDEATHSIG", native)
        self.assertIn("getppid() != owner", native)

    def test_state_monitor_never_contains_filesystem_or_observer_operations(self):
        service = (JAVA / "PioneerRootHudService.java").read_text()
        for match in re.finditer(r"synchronized \(lock\)\s*\{", service):
            depth, end = 1, match.end()
            while depth:
                if service[end] == "{": depth += 1
                elif service[end] == "}": depth -= 1
                end += 1
            block = service[match.end():end - 1]
            for forbidden in ("Os.", "leaseIo.", "readAcknowledgement(", "new FileObserver",
                              ".startWatching(", ".stopWatching(", ".delete(", ".start()"):
                self.assertNotIn(forbidden, block)

    def test_root_status_and_close_do_not_read_or_wait_for_runtime(self):
        service = (JAVA / "PioneerRootHudService.java").read_text()
        for name in ("snapshot", "close"):
            method = re.search(r"(?:JSONObject|void) " + name + r"\(\).*?\n    }", service, re.S).group()
            for forbidden in (".read(", "readText(", "Os.", "Thread.sleep(", ".join(", ".waitFor(", "publishLease("):
                self.assertNotIn(forbidden, method)


if __name__ == "__main__":
    unittest.main()
