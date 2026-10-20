package com.shilapi.xcertplay.pioneer;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reference b819's owned-rule protocol. No Android dependencies; command transport is injected. */
final class NativeNcmFirewall {
    enum State { ABSENT, PRESENT, CONFLICT }
    static final class Result {
        final int exit;
        final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }
    static final class CompletionUnknown extends IOException {
        final boolean childStopped;
        CompletionUnknown(boolean childStopped, String message) { super(message); this.childStopped = childStopped; }
    }
    interface Runner { Result run(String[] args) throws Exception; }
    interface Guard { void check() throws Exception; }
    interface Journal { void write(String pending, boolean unresolved) throws Exception; }

    private final String name, token;
    private final Runner runner;
    private final Guard guard;
    private final Journal journal;
    private boolean attempted, unresolved;
    private String pending = "none";
    private Map<Integer, String> installed;

    NativeNcmFirewall(String name, String token, Runner runner, Guard guard, Journal journal) {
        if (!name.matches("[A-Za-z0-9_.-]{1,15}") || !token.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid firewall lease identity");
        this.name = name; this.token = token; this.runner = runner; this.guard = guard; this.journal = journal;
    }

    void install() throws Exception {
        guard.check();
        String[] snapshots = { chain(0), chain(2) };
        requireChain(snapshots[0], "CARNET_IN", true);
        requireChain(snapshots[1], "CARNET_OUT", true);
        for (int id = 0; id < 4; id++) {
            guard.check();
            if (state(id, snapshots[id / 2]) != State.ABSENT) throw new IOException("FIREWALL_RULE_CONFLICT id=" + id);
            mutate(id, "-I");
            snapshots[id / 2] = chain(id);
            if (state(id, snapshots[id / 2]) != State.PRESENT) throw new IOException("FIREWALL_INSERT_UNCONFIRMED id=" + id);
            guard.check();
        }
        installed = ownedLines(chain(0), chain(2), token);
        verify();
    }

    void verify() throws Exception {
        guard.check();
        Map<Integer, String> current = ownedLines(chain(0), chain(2), token);
        if (installed == null || !installed.equals(current)) throw new IOException("FIREWALL_RULE_CHANGED");
        // The dump alone cannot prove that the complete intended match is still installed.
        for (int id = 0; id < 4; id++) if (state(id, id < 2 ? cachedInput(current) : cachedOutput(current)) != State.PRESENT)
            throw new IOException("FIREWALL_RULE_CHANGED id=" + id);
        guard.check();
    }

    private String cachedInput(Map<Integer, String> lines) { return lines.get(0) + "\n" + lines.get(1); }
    private String cachedOutput(Map<Integer, String> lines) { return lines.get(2) + "\n" + lines.get(3); }

    List<String> restore() {
        List<String> errors = new ArrayList<>();
        if (!attempted) return errors;
        if (unresolved || !pending.equals("none")) {
            errors.add("FIREWALL_COMMAND_UNRESOLVED pending=" + pending); return errors;
        }
        for (int id = 3; id >= 0; id--) {
            try {
                State before = state(id, chain(id));
                if (before == State.CONFLICT) throw new IOException("FIREWALL_RULE_CONFLICT id=" + id);
                if (before == State.PRESENT) {
                    mutate(id, "-D");
                    if (state(id, chain(id)) != State.ABSENT) throw new IOException("FIREWALL_DELETE_UNCONFIRMED id=" + id);
                }
            } catch (Exception error) { errors.add(error.getMessage()); }
            if (unresolved || !pending.equals("none")) break;
        }
        // A second pass confirms all owned rules are absent, including externally duplicated ones.
        if (!unresolved && pending.equals("none")) for (int id = 0; id < 4; id++) {
            try { if (state(id, chain(id)) != State.ABSENT) errors.add("FIREWALL_RELEASE_UNCONFIRMED id=" + id); }
            catch (Exception error) { errors.add(error.getMessage()); }
        }
        return errors;
    }

    private String chain(int id) throws Exception {
        String chain = id < 2 ? "CARNET_IN" : "CARNET_OUT";
        Result result = runner.run(new String[]{"/system/bin/ip6tables", "-t", "filter", "-S", chain});
        if (result.exit != 0) throw new IOException("FIREWALL_CHAIN_UNREADABLE " + chain);
        requireChain(result.output, chain, false);
        return result.output;
    }

    private State state(int id, String dump) throws Exception {
        Result check = runner.run(arguments(name, token, id, "-C"));
        return ruleState(dump, comment(token, id), check.exit);
    }

    private void mutate(int id, String action) throws Exception {
        if (unresolved || !pending.equals("none")) throw new IOException("FIREWALL_COMMAND_UNRESOLVED");
        attempted = true;
        pending = action.substring(1) + id;
        // Fsync must succeed before launching a mutating child.
        journal.write(pending, false);
        try {
            Result result = runner.run(arguments(name, token, id, action));
            if (result.exit != 0) throw new IOException("FIREWALL_COMMAND_FAILED " + pending + " exit=" + result.exit);
        } catch (CompletionUnknown error) {
            unresolved = !error.childStopped;
            journal.write(pending, unresolved);
            throw error;
        } finally {
            if (!unresolved) {
                pending = "none";
                journal.write(pending, false);
            }
        }
    }

    static String comment(String token, int id) { return "diplay-ncm-" + token + "-" + id; }

    static String[] arguments(String name, String token, int id, String action) {
        if (id < 0 || id > 3 || !Arrays.asList("-I", "-D", "-C").contains(action)) throw new IllegalArgumentException();
        List<String> args = new ArrayList<>(Arrays.asList("/system/bin/ip6tables", "-t", "filter", action, id < 2 ? "CARNET_IN" : "CARNET_OUT"));
        if (action.equals("-I")) args.add("1");
        args.addAll(Arrays.asList(id < 2 ? "-i" : "-o", name));
        if (id == 0) args.addAll(Arrays.asList("-s", "fe80::/10"));
        if (id == 1) args.addAll(Arrays.asList("-s", "::/128", "-d", "ff02::/16", "-p", "ipv6-icmp"));
        if (id == 2) args.addAll(Arrays.asList("-d", "fe80::/10"));
        if (id == 3) args.addAll(Arrays.asList("-d", "ff02::/16"));
        args.addAll(Arrays.asList("-m", "comment", "--comment", comment(token, id), "-j", "ACCEPT"));
        return args.toArray(new String[0]);
    }

    static void requireChain(String dump, String chain, boolean requireTerminalDrop) throws IOException {
        if (!Arrays.asList("CARNET_IN", "CARNET_OUT").contains(chain)) throw new IllegalArgumentException();
        List<String> lines = lines(dump);
        if (lines.isEmpty() || !lines.get(0).equals("-N " + chain)) throw new IOException("FIREWALL_CHAIN_CONTRACT_MISMATCH " + chain);
        for (String line : lines.subList(1, lines.size())) if (!line.startsWith("-A " + chain + " "))
            throw new IOException("FIREWALL_CHAIN_CONTRACT_MISMATCH " + chain);
        if (requireTerminalDrop && (lines.size() < 2 || !lines.get(lines.size() - 1).equals("-A " + chain + " -j DROP")))
            throw new IOException("FIREWALL_CHAIN_CONTRACT_MISMATCH missing terminal DROP");
    }

    static State ruleState(String dump, String comment, int checkExit) {
        Matcher match = Pattern.compile("(?:^|[\\s\"'])" + Pattern.quote(comment) + "(?=$|[\\s\"'])").matcher(dump);
        int count = 0; while (match.find()) count++;
        if (count == 0 && checkExit == 1) return State.ABSENT;
        if (count == 1 && checkExit == 0) return State.PRESENT;
        return State.CONFLICT;
    }

    static Map<Integer, String> ownedLines(String input, String output, String token) throws IOException {
        Map<Integer, String> owned = new LinkedHashMap<>();
        for (int side = 0; side < 2; side++) {
            String chain = side == 0 ? "CARNET_IN" : "CARNET_OUT";
            String dump = side == 0 ? input : output;
            requireChain(dump, chain, false);
            List<String> rules = lines(dump).subList(1, lines(dump).size());
            Set<String> heads = new HashSet<>();
            for (String rule : rules.subList(0, Math.min(2, rules.size()))) heads.add(ruleComment(rule));
            Set<String> expected = new HashSet<>(Arrays.asList(comment(token, side * 2), comment(token, side * 2 + 1)));
            if (!heads.equals(expected)) throw new IOException("FIREWALL_RULE_NOT_AT_HEAD " + chain);
            for (int id = side * 2; id < side * 2 + 2; id++) {
                List<String> found = new ArrayList<>();
                for (String rule : rules) if (comment(token, id).equals(ruleComment(rule))) found.add(rule);
                if (found.size() != 1) throw new IOException("FIREWALL_RULE_CONFLICT id=" + id);
                owned.put(id, found.get(0));
            }
        }
        return owned;
    }

    private static String ruleComment(String line) {
        Matcher match = Pattern.compile("(?:^|\\s)--comment\\s+(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))(?=\\s|$)").matcher(line);
        if (!match.find()) return null;
        return match.group(1) != null ? match.group(1) : match.group(2) != null ? match.group(2) : match.group(3);
    }
    private static List<String> lines(String dump) {
        List<String> lines = new ArrayList<>();
        for (String line : dump.split("\\n")) if (!line.trim().isEmpty()) lines.add(line.trim());
        return lines;
    }
}
