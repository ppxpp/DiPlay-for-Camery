package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import org.junit.Assert.*
import org.junit.Test

class PioneerLocalOnlyPolicyTest {
    private val tag="diplay-ap-10001-"+"a".repeat(32)
    private fun initialSnapshot() = PioneerLocalOnlyPolicy.rules().groupBy { it.binary to it.chain }.mapValues { (key,rules) ->
        "-P ${key.second} DROP\n"+rules.asReversed().joinToString("\n") {
            it.command("-I",tag).substringAfter("-t filter ").replace("-I ${key.second} 1 ","-A ${key.second} ")
        }+"\n-A ${key.second} -j OEM_CHAIN\n[exit=0]"
    }
    @Test fun reusesOnlyACompleteIsolationBlockAtTheFront() {
        val snapshot=initialSnapshot()
        assertTrue(PioneerLocalOnlyPolicy.initialRulesAtFront(snapshot,tag))
        val key="/system/bin/iptables" to "INPUT"
        val shadowed=snapshot.toMutableMap()
        shadowed[key]="-A INPUT -j ACCEPT\n"+snapshot.getValue(key)
        assertFalse(PioneerLocalOnlyPolicy.initialRulesAtFront(shadowed,tag))
        val missing=snapshot.toMutableMap()
        missing[key]=snapshot.getValue(key).lineSequence().filterNot { it.contains("dns-tcp") }.joinToString("\n")
        assertFalse(PioneerLocalOnlyPolicy.initialRulesAtFront(missing,tag))
        assertFalse(PioneerLocalOnlyPolicy.initialRulesAtFront(snapshot,"diplay-ap-10001-"+"b".repeat(32)))
        val truncated=snapshot.mapValues { it.value+"\n[OUTPUT_TRUNCATED]" }
        assertThrows(IllegalStateException::class.java) { PioneerLocalOnlyPolicy.initialRulesAtFront(truncated,tag) }
    }
    @Test fun onlyOwnedDebugHotspotEnablesExperiment() {
        for(created in listOf(false,true)) for(debug in listOf(false,true)) for(disabled in listOf(false,true)) {
            assertEquals(created && debug && !disabled,PioneerLocalOnlyPolicy.enabled(created,debug,disabled))
        }
    }
    @Test fun deniesBothForwardingDirectionsForBothAddressFamilies() {
        for(binary in listOf("/system/bin/iptables","/system/bin/ip6tables")) {
            val forward=PioneerLocalOnlyPolicy.rules().filter { it.binary==binary && it.chain=="FORWARD" }
            assertEquals(setOf("-i bcm1","-o bcm1"),forward.map { it.match }.toSet())
            assertTrue(forward.all { it.target=="REJECT" })
        }
    }
    @Test fun dnsProxyIsDeniedButDhcpMdnsAndLocalCarPlayPortsArePreserved() {
        val input=PioneerLocalOnlyPolicy.rules().filter { it.chain=="INPUT" }
        assertEquals(4,input.size)
        for(rule in input) {
            assertTrue(rule.match=="-i bcm1 -p udp --dport 53" || rule.match=="-i bcm1 -p tcp --dport 53")
            assertTrue(rule.target.startsWith("REJECT"))
        }
        assertFalse(PioneerLocalOnlyPolicy.rules().any { it.chain=="PREROUTING" || it.chain=="POSTROUTING" })
    }
    @Test fun onlyRouterAdvertisementsAreFilteredOnLocalOutput() {
        val output=PioneerLocalOnlyPolicy.rules().filter { it.chain=="OUTPUT" }.single()
        assertEquals("/system/bin/ip6tables",output.binary)
        assertEquals("-o bcm1 -p ipv6-icmp --icmpv6-type 134",output.match)
        assertEquals("DROP",output.target)
    }
    @Test fun cleanupUsesExactLeaseAndSameRuleWithoutFlushingSharedChains() {
        val rules=PioneerLocalOnlyPolicy.rules()
        assertEquals(rules.size,rules.map { it.id }.distinct().size)
        for(rule in rules) {
            val insert=rule.command("-I",tag)
            val remove=rule.command("-D",tag)
            assertEquals(insert.replace(" -I ${rule.chain} 1 "," -D ${rule.chain} "),remove)
            assertTrue(insert.contains("$tag-local-${rule.id}"))
            assertTrue(insert.contains("bcm1"))
            assertFalse(insert.contains("usb0"));assertFalse(insert.contains("wlan0"))
            assertFalse(insert.contains(" -F "))
        }
    }
    @Test fun chainListingDistinguishesMissingRuleFromQueryFailureOnFirmware1420() {
        val rule=PioneerLocalOnlyPolicy.rules().first()
        assertFalse(PioneerLocalOnlyPolicy.taggedRulePresent("-P FORWARD ACCEPT\n[exit=0]",rule,tag))
        val line="-A FORWARD -i bcm1 -m comment --comment \"$tag-local-${rule.id}\" -j REJECT\n[exit=0]"
        assertTrue(PioneerLocalOnlyPolicy.taggedRulePresent(line,rule,tag))
        assertFalse(PioneerLocalOnlyPolicy.taggedRulePresent(line.replace(rule.id,rule.id+"-ready"),rule,tag))
        for(reply in listOf("[TIMEOUT]","syntax error\n[exit=2]","Permission denied\n[exit=4]","No chain/target/match by that name\n[exit=1]","[OUTPUT_TRUNCATED]\n[exit=0]")) {
            try { PioneerLocalOnlyPolicy.taggedRulePresent(reply,rule,tag);fail("must retain recovery record") }
            catch(expected: IllegalStateException) { }
        }
    }
    @Test fun batchedSnapshotsKeepChainAndFamilyIdentity() {
        val keys=listOf("/system/bin/iptables" to "FORWARD", "/system/bin/ip6tables" to "OUTPUT")
        val reply="__DIPLAY_AP_CHAIN_0__\n-P FORWARD DROP\n__DIPLAY_AP_CHAIN_1__\n-P OUTPUT DROP\n[exit=0]"
        val snapshots=PioneerLocalOnlyPolicy.splitChainListings(reply,keys)
        assertEquals("-P FORWARD DROP\n[exit=0]",snapshots.getValue(keys[0]))
        assertEquals("-P OUTPUT DROP\n[exit=0]",snapshots.getValue(keys[1]))
        for(bad in listOf(reply.replace("[exit=0]","[exit=1]"), reply.replace("-P OUTPUT DROP",""),reply.replace("__DIPLAY_AP_CHAIN_1__","__DIPLAY_AP_CHAIN_4__"))) {
            try { PioneerLocalOnlyPolicy.splitChainListings(bad,keys);fail("must reject incomplete snapshot") }
            catch(expected: IllegalStateException) { }
        }
    }
    @Test fun readyRulesCanBeInstalledBeforePreparationRulesAreRemoved() {
        for(rule in PioneerLocalOnlyPolicy.rules()) {
            val ready=rule.copy(id=rule.id+"-ready")
            assertNotEquals(rule.command("-D",tag),ready.command("-D",tag))
            assertEquals(rule.match,ready.match)
            assertEquals(rule.target,ready.target)
        }
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUntrustedTag() {
        PioneerLocalOnlyPolicy.rules().first().command("-I","a; echo bad")
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnscopedOperation() {
        PioneerLocalOnlyPolicy.rules().first().command("-F",tag)
    }
}
