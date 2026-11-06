'use strict';

// No Android/Frida dependencies: the same fail-open rules are replayed on the host.
class HudFilterLease {
    constructor(boot, token) { this.boot = boot; this.token = token; }
    accepts(lease, now, ownerAlive) {
        return lease !== null && lease.version === 1 && lease.active === true &&
            lease.boot === this.boot && lease.token === this.token &&
            Number.isInteger(lease.pid) && lease.pid > 1 && typeof lease.start === 'string' &&
            Number.isSafeInteger(lease.issuedMs) && Number.isSafeInteger(lease.expiresMs) &&
            lease.issuedMs <= now && lease.expiresMs > now &&
            lease.expiresMs - lease.issuedMs === 20000 && ownerAlive;
    }
    blocks(lease, now, alive, source) {
        return (source === 1 || source === 2) && this.accepts(lease, now, alive);
    }
}
if (typeof module !== 'undefined') module.exports = HudFilterLease;
