// Run with node; exercises the shipping agent without touching a vehicle or fabricating HUD writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../main/hud-filter');
const Lease = require(path.join(root, 'lease-policy.js'));
const policy = new Lease('boot', 'token');
const base = { version: 1, token: 'token', boot: 'boot', pid: 42, start: '100',
    active: true, issuedMs: 10000, expiresMs: 30000 };
assert(policy.accepts(base, 10000, true));
assert(policy.accepts(base, 29999, true));
assert(!policy.accepts(base, 30000, true));
assert(!policy.accepts(base, 9999, true));
assert(!policy.accepts(base, 15000, false));
for (const patch of [{ active: false }, { token: 'old' }, { boot: 'old' }, { version: 2 },
    { expiresMs: 21000 }, { issuedMs: null }, { pid: 0 }, { expiresMs: NaN }])
    assert(!policy.accepts({ ...base, ...patch }, 15000, true));
for (const source of [1, 2]) assert(policy.blocks(base, 15000, true, source));
for (const source of [0, 3, -1]) assert(!policy.blocks(base, 15000, true, source));

let now = 10000, passed = 0, passedExits = 0, pendingRead, reads = 0, allocations = 0;
const files = new Map(), hooks = new Map();
function proc(pid, start, name, state = 'S') {
    files.set(`/proc/${pid}/stat`, `${pid} (process) ${[state, ...Array(18).fill('0'), start].join(' ')}`);
    files.set(`/proc/${pid}/cmdline`, name + '\0');
}
proc(42, '100', 'com.diplay'); proc(60, '200', '/system/bin/app_process');
proc(80, '300', 'jp.pioneer.ceam.thirdpartyservice');
files.set('/proc/self/cmdline', 'jp.pioneer.ceam.thirdpartyservice\0');
files.set('/lease.json', JSON.stringify(base));
class MockFile {
    constructor(name) { this.name = name; }
    readText() { reads++; if (!files.has(this.name)) throw Error('Missing file'); const text = files.get(this.name); if (text.includes('\0')) throw Error('Invalid UTF-8 NUL'); return text; }
    readBytes() { reads++; if (!files.has(this.name)) throw Error('Missing file'); return Uint8Array.from(Buffer.from(files.get(this.name))).buffer; }
    close() { }
    static writeAllText(name, value) { files.set(name, value); }
}
function NativeFunction(address) {
    if (address === 'clock_gettime') return (_id, ptr) => { ptr.seconds = Math.floor(now / 1000); ptr.nanos = now % 1000 * 1000000; return 0; };
    if (address === 'rename') return (from, to) => { files.set(to, files.get(from)); files.delete(from); return 0; };
    if (address === 'chmod' || address === 'close') return () => 0;
    if (address === 'inotify_init1') return () => 123;
    if (address === 'inotify_add_watch') return (_fd, directory, mask) => { assert.equal(directory, '/'); assert(mask & 0x80); return 1; };
    if (address.includes('onNaviInfo')) return () => { passed++; return 1; };
    if (address.includes('onMapExit')) return () => { passedExits++; return 1; };
    throw Error(address);
}
class UnixInputStream {
    constructor(fd, options) { assert.equal(fd, 123); assert.equal(options.autoClose, true); }
    read() { return new Promise(resolve => { pendingRead = resolve; }); }
    close() { return Promise.resolve(); }
}
const context = vm.createContext({
    rpc: { exports: {} }, File: MockFile, NativeFunction, NativeCallback: function (fn) { return fn; },
    Memory: { allocUtf8String: value => value, alloc: () => { allocations++; return { readU32() { return this.seconds; }, add() { return { readU32: () => this.nanos }; } }; } },
    Module: { getGlobalExportByName: name => name }, UnixInputStream,
    Process: { id: 80, pointerSize: 4, getModuleByName: () => ({ getExportByName: name => name }) },
    Interceptor: { replace: (address, fn) => hooks.set(address, fn), revert: address => hooks.delete(address), flush() { } },
    setInterval: () => { throw Error('Periodic watchdog must not be installed'); }
});
vm.runInContext(fs.readFileSync(path.join(root, 'lease-policy.js'), 'utf8') + '\n' +
    fs.readFileSync(path.join(root, 'agent.js'), 'utf8'), context);
context.rpc.exports.init('early', { appPid: 42, appStart: '100', package: 'com.diplay', helperPid: 60,
    helperStart: '200', helperName: '/system/bin/app_process', tpsStart: '300', token: 'token', boot: 'boot',
    control: '/lease.json', status: '/status' });
const navi = [...hooks].find(([name]) => name.includes('onNaviInfo'))[1];
const exit = [...hooks].find(([name]) => name.includes('onMapExit'))[1];
function frame(source) { navi(null, { add: offset => { assert.equal(offset, 28); return { readS32: () => source }; } }); }
async function notify(mask = 0x80, name = 'lease.json') {
    const data = new ArrayBuffer(32), view = new DataView(data);
    view.setUint32(4, mask, true); view.setUint32(12, 16, true);
    new Uint8Array(data, 16).set(Buffer.from(name));
    assert(pendingRead); const resolve = pendingRead; pendingRead = null; resolve(data);
    await Promise.resolve(); await Promise.resolve();
}
(async () => {
    const initialReads = reads;
    for (let i = 0; i < 1000; i++) frame(i % 2 + 1);
    exit(); assert.equal(passed, 0); assert.equal(passedExits, 0);
    assert.equal(reads, initialReads, 'Native callbacks must not read files or /proc');
    assert.equal(allocations, 1, 'Reuse native clock storage');
    frame(3); assert.equal(passed, 1);
    navi(null, { add() { throw Error('Malformed pointer'); } }); assert.equal(passed, 2);
    // Death/PID reuse no longer does synchronous process checks: the original lease expires.
    proc(42, '101', 'com.diplay'); files.delete('/proc/60/stat');
    now = 29999; frame(1); assert.equal(passed, 2);
    now = 30000; frame(1); exit(); assert.equal(passed, 3); assert.equal(passedExits, 1);
    now = 30001;
    const renewed = { ...base, issuedMs: 30000, expiresMs: 50000 };
    files.set('/lease.json', JSON.stringify(renewed)); await notify();
    frame(2); assert.equal(passed, 3);
    assert.equal(JSON.parse(files.get('/status')).healthMs, now);
    files.set('/lease.json', JSON.stringify({ ...renewed, active: false })); await notify();
    frame(1); exit(); assert.equal(passed, 4); assert.equal(passedExits, 2);
    // Missing/malformed/foreign-token updates must release immediately, including after a valid lease.
    for (const bad of ['{', JSON.stringify({ ...renewed, token: 'old' }), JSON.stringify({ ...renewed, pid: 43 })]) {
        files.set('/lease.json', JSON.stringify(renewed)); await notify();
        files.set('/lease.json', bad); await notify();
        const before = passed; frame(1); assert.equal(passed, before + 1);
    }
    files.set('/lease.json', JSON.stringify(renewed)); await notify();
    files.delete('/lease.json'); await notify(0x200);
    const deleted = passed; frame(1); assert.equal(passed, deleted + 1);
    files.set('/lease.json', JSON.stringify(renewed)); await notify();
    now = 50000; const expired = passed; frame(2); assert.equal(passed, expired + 1);
    now = 45000; await notify(0x4000, '');
    assert.equal(JSON.parse(files.get('/status')).installed, false);
    const failedWatch = passed; frame(1); assert.equal(passed, failedWatch + 1);
    console.log('PASS event-driven renew/release/delete, exact 20s expiry without polling, cached hot path, PID-bound lease, malformed input and watcher failure');
})().catch(error => { console.error(error); process.exitCode = 1; });
