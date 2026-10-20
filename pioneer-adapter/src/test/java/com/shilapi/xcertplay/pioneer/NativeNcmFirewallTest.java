package com.shilapi.xcertplay.pioneer;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class NativeNcmFirewallTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static class Fixture implements NativeNcmFirewall.Runner {
        final Map<String, List<String>> chains = new LinkedHashMap<>();
        final List<String> journal = new ArrayList<>();
        int inserts, deletes, dumps; boolean ignoreInsert, ignoreDelete, uncertain, stopped, corruptCheck, guardFails;
        NativeNcmFirewall firewall;
        Fixture() {
            chains.put("CARNET_IN", new ArrayList<>(Collections.singletonList("-A CARNET_IN -j DROP")));
            chains.put("CARNET_OUT", new ArrayList<>(Collections.singletonList("-A CARNET_OUT -j DROP")));
            firewall = new NativeNcmFirewall("usb0", TOKEN, this, () -> {
                if (guardFails) throw new IOException("NETD_IDENTITY_CHANGED");
            }, (pending, unresolved) -> journal.add(pending + ":" + unresolved));
        }
        @Override public NativeNcmFirewall.Result run(String[] args) throws Exception {
            String action = args[3], chain = args[4];
            List<String> rules = chains.get(chain);
            if (rules == null) return new NativeNcmFirewall.Result(1, "chain missing");
            if (action.equals("-S")) { dumps++; return new NativeNcmFirewall.Result(0, "-N " + chain + "\n" + String.join("\n", rules)); }
            String line = "-A " + chain + " " + String.join(" ", Arrays.copyOfRange(args, action.equals("-I") ? 6 : 5, args.length));
            if (action.equals("-C")) return new NativeNcmFirewall.Result(corruptCheck ? 2 : rules.contains(line) ? 0 : 1, "");
            if (action.equals("-I")) {
                assertEquals("I" + inserts + ":false", journal.get(journal.size() - 1));
                inserts++;
                if (!ignoreInsert) rules.add(0, line);
                if (uncertain) { uncertain = false; throw new NativeNcmFirewall.CompletionUnknown(stopped, "timeout"); }
            }
            if (action.equals("-D")) { deletes++; if (!ignoreDelete) rules.remove(line); }
            return new NativeNcmFirewall.Result(0, "");
        }
    }
    @Test public void installsAtHeadJournalsBeforeMutationAndRestoresOnlyOwnRules() throws Exception {
        Fixture f = new Fixture(); f.firewall.install();
        assertEquals(4, f.inserts); f.firewall.verify();
        assertTrue(f.firewall.restore().isEmpty()); assertEquals(4, f.deletes);
        assertEquals(Collections.singletonList("-A CARNET_IN -j DROP"), f.chains.get("CARNET_IN"));
        assertEquals("none:false", f.journal.get(f.journal.size() - 1));
    }
    @Test public void optimizedInstallUsesFourSnapshotsAndFinalVerifyStillDetectsTampering() throws Exception {
        Fixture f = new Fixture();
        f.firewall.install();
        assertEquals(4, f.dumps);
        String original = f.chains.get("CARNET_IN").get(0);
        f.chains.get("CARNET_IN").set(0, original.replace("-j ACCEPT", "-j DROP"));
        assertThrows(IOException.class, f.firewall::verify);
    }

    @Test public void finalVerificationRejectsFailedExactMatchWithoutFurtherMutation() throws Exception {
        Fixture f = new Fixture(); f.firewall.install(); f.corruptCheck = true;
        assertThrows(IOException.class, f.firewall::verify);
        assertEquals(4, f.inserts); assertEquals(0, f.deletes);
    }

    @Test public void successExitWithoutRuleIsNotSuccess() throws Exception {
        Fixture f = new Fixture(); f.ignoreInsert = true;
        assertThrows(IOException.class, f.firewall::install);
        assertEquals(1, f.inserts); assertTrue(f.firewall.restore().isEmpty());
    }
    @Test public void wrongCheckExitCannotMasqueradeAsAbsentRule() {
        assertEquals(NativeNcmFirewall.State.CONFLICT, NativeNcmFirewall.ruleState("", "owned", 2));
        assertEquals(NativeNcmFirewall.State.CONFLICT, NativeNcmFirewall.ruleState("--comment owned", "owned", 1));
        assertEquals(NativeNcmFirewall.State.PRESENT, NativeNcmFirewall.ruleState("--comment \"owned\"", "owned", 0));
        assertEquals(NativeNcmFirewall.State.ABSENT, NativeNcmFirewall.ruleState("--comment owned-longer", "owned", 1));
    }
    @Test public void duplicateOrChangedRuleIsConflictAndIsNeverBlindlyDeleted() throws Exception {
        Fixture f = new Fixture(); f.firewall.install();
        f.chains.get("CARNET_IN").add(f.chains.get("CARNET_IN").get(0));
        assertThrows(IOException.class, f.firewall::verify);
        assertFalse(f.firewall.restore().isEmpty());
        assertTrue(f.chains.get("CARNET_IN").size() >= 3);
    }
    @Test public void ruleMustRemainAtHeadButUnrelatedTailChangesAreAllowed() throws Exception {
        Fixture f = new Fixture(); f.firewall.install();
        f.chains.get("CARNET_IN").add("-A CARNET_IN -i wlan0 -j ACCEPT"); f.firewall.verify();
        f.chains.get("CARNET_IN").add(0, "-A CARNET_IN -j DROP");
        assertThrows(IOException.class, f.firewall::verify);
    }
    @Test public void netdRestartStopsVerificationWithoutReinstallingRules() throws Exception {
        Fixture f = new Fixture(); f.firewall.install(); f.guardFails = true;
        assertThrows(IOException.class, f.firewall::verify); assertEquals(4, f.inserts);
    }
    @Test public void unresolvedChildFencesEverySubsequentMutation() throws Exception {
        Fixture f = new Fixture(); f.uncertain = true; f.stopped = false;
        assertThrows(NativeNcmFirewall.CompletionUnknown.class, f.firewall::install);
        assertEquals("I0:true", f.journal.get(f.journal.size() - 1));
        assertFalse(f.firewall.restore().isEmpty()); assertEquals(0, f.deletes);
    }
    @Test public void confirmedStoppedTimeoutCanBeReconciledByReadback() throws Exception {
        Fixture f = new Fixture(); f.uncertain = true; f.stopped = true;
        assertThrows(NativeNcmFirewall.CompletionUnknown.class, f.firewall::install);
        assertTrue(f.firewall.restore().isEmpty()); assertEquals(1, f.deletes);
    }
    @Test public void deleteMustBeConfirmedRatherThanTrustingZeroExit() throws Exception {
        Fixture f = new Fixture(); f.firewall.install(); f.ignoreDelete = true;
        assertFalse(f.firewall.restore().isEmpty());
    }
    @Test public void missingTerminalDropStopsBeforeFirstMutation() {
        Fixture f = new Fixture(); f.chains.get("CARNET_IN").clear();
        assertThrows(IOException.class, f.firewall::install); assertEquals(0, f.inserts);
    }
    @Test public void requiresExpectedChainContract() throws Exception {
        NativeNcmFirewall.requireChain("-N CARNET_IN\n-A CARNET_IN -j DROP", "CARNET_IN", true);
        assertThrows(IOException.class, () -> NativeNcmFirewall.requireChain("-P INPUT ACCEPT", "CARNET_IN", false));
        assertThrows(IOException.class, () -> NativeNcmFirewall.requireChain("-N CARNET_IN\n-A OTHER -j DROP", "CARNET_IN", false));
        assertThrows(IOException.class, () -> NativeNcmFirewall.requireChain("-N CARNET_IN", "CARNET_IN", true));
    }
}
