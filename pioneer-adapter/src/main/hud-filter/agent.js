'use strict';

// This agent is loaded into the verified TPS process only. DiPlay has its own MeterClient.
const FILTER_PROCESS = 'jp.pioneer.ceam.thirdpartyservice';
let configuration, leasePolicy, clockTime, lease = null, timer;
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
    const value = Memory.alloc(8);
    if (clockTime(7, value) !== 0) throw new Error('BOOTTIME unavailable');
    return value.readU32() * 1000 + value.add(4).readU32() / 1000000;
}
function ownerAlive(candidate) {
    return candidate !== null && candidate.pid === configuration.appPid &&
        candidate.start === configuration.appStart &&
        identity(candidate.pid, candidate.start, configuration.package) &&
        identity(configuration.helperPid, configuration.helperStart, configuration.helperName);
}
function protectedNow() {
    if (!installed) return false;
    try {
        const candidate = JSON.parse(readText(configuration.control));
        lease = candidate;
        return leasePolicy.accepts(candidate, nowMs(), ownerAlive(candidate));
    } catch (_) { lease = null; return false; }
}
function publish() {
    const active = protectedNow();
    const state = { version: 1, token: configuration.token, boot: configuration.boot,
        tpsPid: Process.id, tpsStart: configuration.tpsStart,
        helperPid: configuration.helperPid, helperStart: configuration.helperStart,
        installed: installed, active: active, expiresMs: active ? lease.expiresMs : 0,
        healthMs: Math.floor(nowMs() / 5000) * 5000,
        blockedFrames: blockedFrames, blockedExits: blockedExits };
    // Count in memory; acknowledge every five seconds or immediately on a lease/lifecycle change.
    const key = JSON.stringify([state.installed, state.active, state.expiresMs, state.healthMs]);
    if (key === lastStatus) return;
    const data = JSON.stringify(state);
    const tmp = configuration.status + '.tmp';
    File.writeAllText(tmp, data);
    const chmod = new NativeFunction(Module.getGlobalExportByName('chmod'), 'int', ['pointer', 'int']);
    if (chmod(Memory.allocUtf8String(tmp), 420) !== 0) throw new Error('Cannot publish filter status');
    const rename = new NativeFunction(Module.getGlobalExportByName('rename'), 'int', ['pointer', 'pointer']);
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
    clockTime = new NativeFunction(Module.getGlobalExportByName('clock_gettime'), 'int', ['int', 'pointer']);
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
        publish();
        timer = setInterval(() => {
            try {
                if (!identity(config.appPid, config.appStart, config.package) ||
                    !identity(config.helperPid, config.helperStart, config.helperName)) {
                    installed = false;
                    Interceptor.revert(navi); Interceptor.revert(exit); Interceptor.flush();
                    clearInterval(timer);
                }
                publish();
            } catch (_) { /* A failed status write cannot extend the navigation lease. */ }
        }, 500);
    } catch (error) {
        installed = false;
        Interceptor.revert(navi); Interceptor.revert(exit); Interceptor.flush();
        throw error;
    }
};
