#ifndef PIONEER_AP_GATE_PROTOCOL_H
#define PIONEER_AP_GATE_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* This is a vendor band selector, not IEEE channel 2/5. */
#define PIONEER_AP_FREQUENCY_2 "IFNAME=bcm1 SET_NETWORK 0 frequency 2"
#define PIONEER_AP_FREQUENCY_5 "IFNAME=bcm1 SET_NETWORK 0 frequency 5"
#define PIONEER_AP_GET_FREQUENCY "IFNAME=bcm1 GET_NETWORK 0 frequency"

/* ARM pt_regs stores pointers in signed long. Zero-extend before pread64. */
static inline uint64_t pioneer_ap_gate_address(long value) { return (uint32_t)value; }

/* The vendor normalizes band selector 5 into an actual MHz value on GET_NETWORK. */
static inline int pioneer_ap_gate_is_five_ghz(const char *reply) {
    if (!strcmp(reply,"5")) return 1;
    uint32_t value=0;
    if(!*reply) return 0;
    for(const char *p=reply;*p;p++) {
        if(*p<'0' || *p>'9') return 0;
        value=value*10+(uint32_t)(*p-'0');
        if(value>5924) return 0;
    }
    return value>=5000;
}

static inline int pioneer_ap_gate_matches(const char *bytes, size_t count) {
    return count == sizeof(PIONEER_AP_FREQUENCY_2) - 1 &&
           memcmp(bytes, PIONEER_AP_FREQUENCY_2, count) == 0;
}

/* A match may be a sendto ENTRY or EXIT stop. One further syscall stop is
 * required in BOTH cases: only then has the original datagram been sent. */
struct pioneer_ap_gate_barrier { int observed; };
static inline int pioneer_ap_gate_advance(struct pioneer_ap_gate_barrier *gate,
                                         int syscall_stop, int matched) {
    if (!syscall_stop) { gate->observed = 0; return 0; }
    if (gate->observed) return 1;
    gate->observed = matched;
    return 0;
}
#endif
