#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../../main/cpp/wireless/pioneer_ap_gate_protocol.h"

int main(void) {
    assert(pioneer_ap_gate_address((long)(int32_t)0xb6001234u) == UINT64_C(0xb6001234));
    assert(pioneer_ap_gate_address(0x1000) == 0x1000);
    assert(pioneer_ap_gate_is_five_ghz("5"));
    assert(pioneer_ap_gate_is_five_ghz("5745"));
    assert(pioneer_ap_gate_is_five_ghz("5785"));
    assert(!pioneer_ap_gate_is_five_ghz("2412"));
    assert(!pioneer_ap_gate_is_five_ghz("5925"));
    assert(!pioneer_ap_gate_is_five_ghz("5oops"));
    assert(!pioneer_ap_gate_is_five_ghz("999999999999"));
    assert(pioneer_ap_gate_matches(PIONEER_AP_FREQUENCY_2, strlen(PIONEER_AP_FREQUENCY_2)));
    assert(!pioneer_ap_gate_matches(PIONEER_AP_FREQUENCY_5, strlen(PIONEER_AP_FREQUENCY_5)));
    assert(!pioneer_ap_gate_matches(PIONEER_AP_FREQUENCY_2, strlen(PIONEER_AP_FREQUENCY_2) - 1));
    const char *other = "IFNAME=wlan0 SET_NETWORK 0 frequency 2";
    assert(!pioneer_ap_gate_matches(other, strlen(other)));
    other = "IFNAME=bcm1 SET_NETWORK 1 frequency 2";
    assert(!pioneer_ap_gate_matches(other, strlen(other)));
    struct pioneer_ap_gate_barrier gate = {0};
    assert(!pioneer_ap_gate_advance(&gate, 1, 0));
    assert(!pioneer_ap_gate_advance(&gate, 1, 1));
    assert(pioneer_ap_gate_advance(&gate, 1, 0));
    gate.observed = 0;
    assert(!pioneer_ap_gate_advance(&gate, 1, 1));
    assert(!pioneer_ap_gate_advance(&gate, 0, 0)); /* signal/cancellation cannot authorize a write */
    assert(!pioneer_ap_gate_advance(&gate, 1, 0));
    puts("pioneer AP gate protocol: passed");
    return 0;
}
