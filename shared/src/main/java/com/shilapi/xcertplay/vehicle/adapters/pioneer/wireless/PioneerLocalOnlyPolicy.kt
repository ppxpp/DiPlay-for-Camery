package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

/** Interface-scoped isolation. This blocks Internet sharing; iPhone cellular selection is unverified. */
internal object PioneerLocalOnlyPolicy {
    const val DISABLE_FILE = "pioneer-wireless-local-only.disabled"

    fun enabled(created: Boolean, disabled: Boolean) = created && !disabled

    data class Rule(val binary: String, val chain: String, val match: String, val target: String, val id: String) {
        fun command(operation: String, tag: String): String {
            require(operation in listOf("-C", "-I", "-D"))
            require(tag.matches(Regex("diplay-ap-[0-9]+-[0-9a-f]{32}")))
            val position = if(operation=="-I") " 1" else ""
            return "$binary -w -t filter $operation $chain$position $match -m comment --comment $tag-local-$id -j $target"
        }
    }

    fun taggedRulePresent(listing: String, rule: Rule, tag: String): Boolean {
        rule.command("-C",tag) // Validate the tag before matching it against kernel output.
        check(listing.endsWith("[exit=0]") && !listing.contains("[OUTPUT_TRUNCATED]")) {
            "AP local-only rule listing failed; recovery record must be retained"
        }
        val comment=Regex("--comment \"?"+Regex.escape("$tag-local-${rule.id}")+"\"?(?:\\s|$)")
        return listing.lineSequence().any { it.startsWith("-A ${rule.chain} ") && comment.containsMatchIn(it) }
    }

    fun splitChainListings(reply: String, keys: List<Pair<String,String>>): Map<Pair<String,String>,String> {
        check(reply.endsWith("[exit=0]") && !reply.contains("[OUTPUT_TRUNCATED]")) { "AP rule snapshot failed" }
        val body=reply.removeSuffix("[exit=0]")
        return keys.mapIndexed { index,key ->
            val marker="__DIPLAY_AP_CHAIN_${index}__\n"
            val begin=body.indexOf(marker)
            check(begin>=0 && body.indexOf(marker,begin+marker.length)<0) { "AP rule snapshot identity missing" }
            val end=if(index+1<keys.size) body.indexOf("__DIPLAY_AP_CHAIN_${index+1}__\n",begin+marker.length) else body.length
            check(end>=begin+marker.length) { "AP rule snapshot incomplete" }
            val section=body.substring(begin+marker.length,end).trim()
            check(section.lineSequence().any { it.startsWith("-P ${key.second} ") || it.startsWith("-A ${key.second} ") }) { "AP rule chain identity missing" }
            key to (section+"\n[exit=0]")
        }.toMap()
    }

    /** Reuse only when no OEM jump/ACCEPT can precede our complete isolation block. */
    fun initialRulesAtFront(snapshot: Map<Pair<String,String>,String>, tag: String): Boolean =
        rules().groupBy { it.binary to it.chain }.all { (key, expected) ->
            val listing=snapshot.getValue(key)
            check(listing.endsWith("[exit=0]") && !listing.contains("[OUTPUT_TRUNCATED]")) { "AP rule snapshot failed" }
            val front=listing.lineSequence().filter { it.startsWith("-A ${key.second} ") }
                .take(expected.size).toList()
            front.size==expected.size && expected.asReversed().zip(front).all { (rule,line) ->
                taggedRulePresent(line+"\n[exit=0]",rule,tag)
            }
        }

    fun rules(): List<Rule> = buildList {
        for((family,binary) in listOf("4" to "/system/bin/iptables", "6" to "/system/bin/ip6tables")) {
            add(Rule(binary,"FORWARD","-i bcm1","REJECT",family+"-from"))
            add(Rule(binary,"FORWARD","-o bcm1","REJECT",family+"-to"))
            // DNS proxying is INPUT/OUTPUT, so FORWARD rules alone do not stop it.
            // DHCP 67/68, mDNS 5353, AirPlay and all other local services remain untouched.
            add(Rule(binary,"INPUT","-i bcm1 -p udp --dport 53","REJECT",family+"-dns-udp"))
            add(Rule(binary,"INPUT","-i bcm1 -p tcp --dport 53","REJECT --reject-with tcp-reset",family+"-dns-tcp"))
        }
        // Preserve link-local IPv6, RS and neighbor discovery; suppress upstream RA/RDNSS.
        add(Rule("/system/bin/ip6tables","OUTPUT","-o bcm1 -p ipv6-icmp --icmpv6-type 134","DROP","6-ra"))
    }
}
