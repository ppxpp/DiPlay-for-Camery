'use strict';

// This agent is loaded into the verified TPS process only. DiPlay has its own MeterClient.
const FILTER_PROCESS = 'jp.pioneer.ceam.thirdpartyservice';
let configuration, leasePolicy, clockTime, clockBuffer, chmod, rename, lease = null, controlStream;
let blockedFrames = 0, blockedExits = 0, lastStatus = '';
let installed = false;
const callbacks = [];

function readText(path) {
    const file = new File(path, 'r');
    try { return file.readText(4097); } finally { file.close(); }
}
function command(path) {
    const file = new File(path, 'rb');
    try {
        // procfs cmdline is NUL-delimited; Frida's UTF-8 readText rejects embedded NUL bytes.
        const bytes = new Uint8Array(file.readBytes(4096));
        const end = bytes.indexOf(0);
        return String.fromCharCode(...bytes.subarray(0, end < 0 ? bytes.length : end));
    } finally { file.close(); }
}
function identity(pid, start, name) {
    try {
        const stat = readText('/proc/' + pid + '/stat');
        const fields = stat.slice(stat.lastIndexOf(') ') + 2).split(' ');
        if (fields[0] === 'Z' || fields[19] !== start) return false;
        return command('/proc/' + pid + '/cmdline') === name;
    } catch (_) { return false; }
}
function nowMs() {
    const value = clockBuffer;
    if (clockTime(7, value) !== 0) throw new Error('BOOTTIME unavailable');
    return value.readU32() * 1000 + value.add(4).readU32() / 1000000;
}
function protectedNow() {
    return installed && lease !== null && lease.pid === configuration.appPid &&
        lease.start === configuration.appStart && leasePolicy.accepts(lease, nowMs(), true);
}
function refreshLease() {
    try { lease = JSON.parse(readText(configuration.control)); }
    catch (_) { lease = null; } // Failed/malformed updates always release protection.
    publish();
}
// Watch the directory because DiPlay atomically renames lease.json on each heartbeat/release.
// No polling or file/process reads execute on a native navigation callback.
function watchLease() {
    const init = new NativeFunction(Module.getGlobalExportByName('inotify_init1'), 'int', ['int']);
    const watch = new NativeFunction(Module.getGlobalExportByName('inotify_add_watch'), 'int', ['int', 'pointer', 'uint']);
    const close = new NativeFunction(Module.getGlobalExportByName('close'), 'int', ['int']);
    const fd = init(0x80000); // O_CLOEXEC; asynchronous stream reads wait without a polling timer.
    if (fd < 0) throw new Error('Cannot watch navigation lease');
    const directory = configuration.control.slice(0, configuration.control.lastIndexOf('/')) || '/';
    if (watch(fd, Memory.allocUtf8String(directory), 0x80 | 0x200 | 0x400 | 0x800) < 0) {
        close(fd); throw new Error('Cannot watch navigation lease directory');
    }
    controlStream = new UnixInputStream(fd, { autoClose: true });
    async function receive() {
        try {
            while (installed) {
                const data = await controlStream.read(4096);
                if (data.byteLength === 0) throw new Error('Lease watcher closed');
                const bytes = new Uint8Array(data), view = new DataView(data);
                let changed = false;
                for (let offset = 0; offset + 16 <= bytes.length;) {
                    const mask = view.getUint32(offset + 4, true);
                    const length = view.getUint32(offset + 12, true);
                    if (offset + 16 + length > bytes.length) throw new Error('Truncated lease event');
                    if (mask & (0x400 | 0x800 | 0x4000 | 0x8000)) throw new Error('Lease watcher invalidated');
                    const nameBytes = bytes.subarray(offset + 16, offset + 16 + length);
                    const end = nameBytes.indexOf(0);
                    const name = String.fromCharCode(...nameBytes.subarray(0, end < 0 ? nameBytes.length : end));
                    if (name === 'lease.json' && mask & (0x80 | 0x200)) changed = true;
                    offset += 16 + length;
                }
                if (changed) refreshLease();
            }
        } catch (_) {
            lease = null;
            installed = false; // The root supervisor sees this acknowledgement and reinstalls the agent.
            try { publish(); } catch (_) { }
            try { await controlStream.close(); } catch (_) { }
        }
    }
    receive();
}
function publish() {
    const active = protectedNow();
    const state = { version: 1, token: configuration.token, boot: configuration.boot,
        tpsPid: Process.id, tpsStart: configuration.tpsStart,
        helperPid: configuration.helperPid, helperStart: configuration.helperStart,
        installed: installed, active: active, expiresMs: active ? lease.expiresMs : 0,
        healthMs: Math.floor(nowMs()),
        blockedFrames: blockedFrames, blockedExits: blockedExits };
    // Count in memory; acknowledge every five seconds or immediately on a lease/lifecycle change.
    const key = JSON.stringify([state.installed, state.active, state.expiresMs, Math.floor(state.healthMs / 5000)]);
    if (key === lastStatus) return;
    const data = JSON.stringify(state);
    const tmp = configuration.status + '.tmp';
    File.writeAllText(tmp, data);
    if (chmod(Memory.allocUtf8String(tmp), 420) !== 0) throw new Error('Cannot publish filter status');
    if (rename(Memory.allocUtf8String(tmp), Memory.allocUtf8String(configuration.status)) !== 0)
        throw new Error('Cannot acknowledge installed filter');
    lastStatus = key;
}
rpc.exports.init = function (_stage, config) {
    configuration = config;
    if (Process.pointerSize !== 4 || command('/proc/self/cmdline') !== FILTER_PROCESS)
        throw new Error('Unsupported filter process');
    if (!identity(Process.id, config.tpsStart, FILTER_PROCESS)) throw new Error('TPS changed');
    leasePolicy = new HudFilterLease(config.boot, config.token);
    if (!identity(config.appPid, config.appStart, config.package) ||
        !identity(config.helperPid, config.helperStart, config.helperName)) throw new Error('Owner changed');
    clockTime = new NativeFunction(Module.getGlobalExportByName('clock_gettime'), 'int', ['int', 'pointer'], { scheduling: 'exclusive' });
    clockBuffer = Memory.alloc(8);
    chmod = new NativeFunction(Module.getGlobalExportByName('chmod'), 'int', ['pointer', 'int']);
    rename = new NativeFunction(Module.getGlobalExportByName('rename'), 'int', ['pointer', 'pointer']);
    const library = Process.getModuleByName('libmeterservice_jni.so');
    const navi = library.getExportByName('_ZN7android11MeterClient10onNaviInfoERNS_19MeterNaviInfoNotifyE');
    const exit = library.getExportByName('_ZN7android11MeterClient9onMapExitEv');
    const originalNavi = new NativeFunction(navi, 'bool', ['pointer', 'pointer']);
    const originalExit = new NativeFunction(exit, 'bool', ['pointer']);
    const naviCallback = new NativeCallback(function (client, info) {
        try {
            const source = info.add(28).readS32();
            if ((source === 1 || source === 2) && protectedNow()) { blockedFrames++; return 1; }
        } catch (_) { /* Unknown input must pass. */ }
        return originalNavi(client, info);
    }, 'bool', ['pointer', 'pointer']);
    const exitCallback = new NativeCallback(function (client) {
        try { if (protectedNow()) { blockedExits++; return 1; } } catch (_) { }
        return originalExit(client);
    }, 'bool', ['pointer']);
    callbacks.push(naviCallback, exitCallback);
    try {
        Interceptor.replace(navi, naviCallback);
        Interceptor.replace(exit, exitCallback);
        Interceptor.flush();
        installed = true;
        watchLease();
        refreshLease();
    } catch (error) {
        installed = false;
        Interceptor.revert(navi); Interceptor.revert(exit); Interceptor.flush();
        throw error;
    }
};
