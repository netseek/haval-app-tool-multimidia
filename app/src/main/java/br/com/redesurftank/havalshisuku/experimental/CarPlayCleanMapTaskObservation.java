package br.com.redesurftank.havalshisuku.experimental;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One bounded ActivityManager observation; unknown input is never absence. */
public final class CarPlayCleanMapTaskObservation {
    private static final String PACKAGE = "com.ts.carplay.app";
    private static final Pattern HEADER = Pattern.compile("^Stack id=([0-9]+)(?:[ \\t].*)?$");
    private static final Pattern DISPLAY = Pattern.compile("(?:^|[ \\t])displayId=([0-9]+)(?=[ \\t]|$)");
    private static final Pattern TASK = Pattern.compile("^[ \\t]+taskId=[0-9]+: ([A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)*)/[A-Za-z0-9_.$]+ bounds=\\[-?[0-9]+,-?[0-9]+\\]\\[-?[0-9]+,-?[0-9]+\\] userId=[0-9]+(?:[ \\t].*)?$");
    private static final Pattern VISIBLE = Pattern.compile("(?:^|[ \\t])visible=(true|false)(?=[ \\t]|$)");
    private static final Pattern TOP = Pattern.compile(" topActivity=(?:ComponentInfo\\{[A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+\\}|null)$");
    public final long observedAtElapsedMs;
    public final boolean known;
    public final boolean carPlayOnMain;
    public final boolean carPlayOnSecondary;

    private CarPlayCleanMapTaskObservation(long observedAt, boolean known, boolean main, boolean secondary) {
        observedAtElapsedMs = observedAt;
        this.known = known;
        carPlayOnMain = main;
        carPlayOnSecondary = secondary;
    }

    public static CarPlayCleanMapTaskObservation unknown() {
        return new CarPlayCleanMapTaskObservation(-1, false, false, false);
    }

    public static CarPlayCleanMapTaskObservation parse(String raw, long observedAt) {
        if (raw == null || raw.length() == 0 || raw.length() > 262144 || observedAt < 0) return unknown();
        String[] lines = raw.split("\\n", -1);
        if (lines.length > 4096) return unknown();
        Integer display = null;
        int headers = 0, mainVisible = 0;
        boolean secondary = false;
        Set<String> stackIds = new HashSet<>();
        for (String original : lines) {
            String line = original.endsWith("\r") ? original.substring(0, original.length() - 1) : original;
            String trimmed = line.trim();
            if (trimmed.startsWith("Error") || trimmed.startsWith("Exception") ||
                    trimmed.startsWith("SecurityException") || trimmed.startsWith("java.")) return unknown();
            if (trimmed.startsWith("Stack ") && !line.startsWith("Stack id=")) return unknown();
            if (line.startsWith("Stack id=")) {
                Matcher header = HEADER.matcher(line), owner = DISPLAY.matcher(line);
                if (!header.matches() || !owner.find() || !stackIds.add(header.group(1))) return unknown();
                String value = owner.group(1);
                if (owner.find() || countField(line, "displayId=") != 1) return unknown();
                try { display = Integer.valueOf(value); }
                catch (NumberFormatException failure) { return unknown(); }
                headers++;
                continue;
            }
            if (!trimmed.startsWith("taskId") && !line.contains(PACKAGE)) continue;
            Matcher task = TASK.matcher(line);
            if (display == null || !task.matches() || !TOP.matcher(line).find() ||
                    countField(line, "taskId=") != 1 || countField(line, "bounds=") != 1 ||
                    countField(line, "userId=") != 1 || countField(line, "topActivity=") != 1) return unknown();
            Matcher visible = VISIBLE.matcher(line);
            if (!visible.find()) return unknown();
            boolean shown = visible.group(1).equals("true");
            if (visible.find() || countField(line, "visible=") != 1) return unknown();
            if (shown && line.endsWith(" topActivity=null")) return unknown();
            if (!task.group(1).equals(PACKAGE)) {
                if (line.contains(PACKAGE)) return unknown();
                continue;
            }
            if (shown && !line.contains(" topActivity=ComponentInfo{" + PACKAGE + "/")) return unknown();
            if (display == 0 && shown) mainVisible++;
            // A retained D3 CarPlay task is conservatively incompatible with a D0-only offer.
            if (display == 3) secondary = true;
        }
        if (headers == 0 || mainVisible > 1) return unknown();
        return new CarPlayCleanMapTaskObservation(observedAt, true, mainVisible == 1 && !secondary, secondary);
    }

    private static int countField(String line, String prefix) {
        int count = 0;
        for (String field : line.trim().split("[ \\t]+")) if (field.startsWith(prefix)) count++;
        return count;
    }
}
