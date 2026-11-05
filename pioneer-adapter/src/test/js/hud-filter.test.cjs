// Run with node; exercises the shipping agent without touching a vehicle or fabricating HUD writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../main/hud-filter');
const Lease = require(path.join(root, 'lease-policy.js'));
const policy = new Lease('boot', 'token');
const base = { version: 1, token: 'token', boot: 'boot', pid: 42, start: '100',
    active: true, issuedMs: 10000, expiresMs: 20000 };
assert(policy.accepts(base, 10000, true));
assert(policy.accepts(base, 19999, true));
assert(!policy.accepts(base, 20000, true));
assert(!policy.accepts(base, 9999, true));
assert(!policy.accepts(base, 15000, false));
for (const patch of [{ active: false }, { token: 'old' }, { boot: 'old' }, { version: 2 },
    { expiresMs: 21000 }, { issuedMs: null }, { pid: 0 }, { expiresMs: NaN }])
    assert(!policy.accepts({ ...base, ...patch }, 15000, true));
for (const source of [1, 2]) assert(policy.blocks(base, 15000, true, source));
for (const source of [0, 3, -1]) assert(!policy.blocks(base, 15000, true, source));

let now = 10000, passed = 0, passedExits = 0, interval;
const files = new Map(), hooks = new Map();
function proc(pid, start, name, state = 'S') {
    files.set(`/proc/${pid}/stat`, `${pid} (process) ${[state, ...Array(18).fill('0'), start].join(' ')}`);
    files.set(`/proc/${pid}/cmdline`, name + '\0');
}
proc(42, '100', 'com.diplay'); proc(60, '200', '/system/bin/app_process');
proc(80, '300', 'jp.pioneer.ceam.thirdpartyservice');
files.set('/proc/self/cmdline', 'jp.pioneer.ceam.thirdpartyservice\0');
files.set('/control', JSON.stringify(base));
class MockFile {
    constructor(name) { this.name = name; }
    readText() { if (!files.has(this.name)) throw Error('Missing file'); const text = files.get(this.name); if (text.includes('\0')) throw Error('Invalid UTF-8 NUL'); return text; }
    readBytes() { if (!files.has(this.name)) throw Error('Missing file'); return Uint8Array.from(Buffer.from(files.get(this.name))).buffer; }
    close() { }
    static writeAllText(name, value) { files.set(name, value); }
}
function NativeFunction(address) {
    if (address === 'clock_gettime') return (_id, ptr) => { ptr.seconds = Math.floor(now / 1000); ptr.nanos = now % 1000 * 1000000; return 0; };
    if (address === 'rename') return (from, to) => { files.set(to, files.get(from)); files.delete(from); return 0; };
    if (address === 'chmod') return () => 0;
    if (address.includes('onNaviInfo')) return () => { passed++; return 1; };
    if (address.includes('onMapExit')) return () => { passedExits++; return 1; };
    throw Error(address);
}
const context = vm.createContext({
    rpc: { exports: {} }, File: MockFile, NativeFunction, NativeCallback: function (fn) { return fn; },
    Memory: { allocUtf8String: value => value, alloc: () => ({ readU32() { return this.seconds; }, add() { return { readU32: () => this.nanos }; } }) },
    Module: { getGlobalExportByName: name => name },
    Process: { id: 80, pointerSize: 4, getModuleByName: () => ({ getExportByName: name => name }) },
    Interceptor: { replace: (address, fn) => hooks.set(address, fn), revert: address => hooks.delete(address), flush() { } },
    setInterval: fn => { interval = fn; return 1; }, clearInterval() { }
});
vm.runInContext(fs.readFileSync(path.join(root, 'lease-policy.js'), 'utf8') + '\n' +
    fs.readFileSync(path.join(root, 'agent.js'), 'utf8'), context);
context.rpc.exports.init('early', { appPid: 42, appStart: '100', package: 'com.diplay', helperPid: 60,
    helperStart: '200', helperName: '/system/bin/app_process', tpsStart: '300', token: 'token', boot: 'boot',
    control: '/control', status: '/status' });
const navi = [...hooks].find(([name]) => name.includes('onNaviInfo'))[1];
const exit = [...hooks].find(([name]) => name.includes('onMapExit'))[1];
function frame(source, state) { navi(null, { add: offset => { assert.equal(offset, 28); return { readS32: () => source }; }, state }); }
for (const source of [1, 2]) for (const state of [0, 1, 2]) frame(source, state);
exit(null); assert.equal(passed, 0); assert.equal(passedExits, 0);
frame(3, 1); assert.equal(passed, 1);
navi(null, { add() { throw Error('Malformed pointer'); } }); assert.equal(passed, 2);
// Release is immediate; no need to wait for the polling timer.
files.set('/control', JSON.stringify({ ...base, active: false }));
frame(1, 1); exit(null); assert.equal(passed, 3); assert.equal(passedExits, 1);
files.set('/control', JSON.stringify(base));
now = 20000; frame(2, 2); assert.equal(passed, 4);
now = 15000; proc(42, '101', 'com.diplay'); frame(1, 1); assert.equal(passed, 5); // PID reuse.
proc(42, '100', 'com.diplay', 'Z'); frame(1, 1); assert.equal(passed, 6);
proc(42, '100', 'com.diplay'); files.delete('/proc/60/stat'); frame(1, 1); assert.equal(passed, 7);
proc(60, '200', '/system/bin/app_process'); files.set('/control', '{'); frame(2, 1); assert.equal(passed, 8);
files.set('/control', JSON.stringify(base)); interval();
const status = JSON.parse(files.get('/status'));
assert.equal(status.installed, true); assert.equal(status.active, true);
assert.equal(status.blockedFrames, 6); assert.equal(status.blockedExits, 1);
files.delete('/proc/60/stat'); interval();
assert.equal(hooks.size, 0); assert.equal(JSON.parse(files.get('/status')).installed, false);
console.log('PASS lease expiry/release, all OEM navigation states, foreign sources, malformed input, process death/PID reuse, helper death and acknowledgement');
