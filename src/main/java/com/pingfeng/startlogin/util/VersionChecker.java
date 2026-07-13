package com.pingfeng.startlogin.util;

import org.bukkit.Bukkit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VersionChecker {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    // 将 major.minor 合并为单一版本号: 1.21 → 121, 26.1 → 261
    private static final int MIN_VERSION = 121; // 1.21
    private static final int MIN_PATCH = 7;     // 1.21.7
    private static final int MAX_VERSION = 269; // 26.9 (覆盖所有26.x)

    public static boolean isSupported() {
        String versionStr = Bukkit.getVersion();
        int[] version = parseVersion(versionStr);
        if (version == null) {
            return false;
        }
        return isInRange(version);
    }

    public static int[] parseVersion(String versionStr) {
        if (versionStr == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(versionStr);
        if (matcher.find()) {
            int major = parseIntSafe(matcher.group(1));
            int minor = parseIntSafe(matcher.group(2));
            int patch = matcher.group(3) != null ? parseIntSafe(matcher.group(3)) : 0;
            return new int[]{major, minor, patch};
        }
        return null;
    }

    private static boolean isInRange(int[] version) {
        int major = version[0];
        int minor = version[1];
        int patch = version[2];

        // 合并 major.minor 为单一数字: 1.21 → 121, 26.1 → 261
        int mainVersion = major * 10 + minor;

        if (mainVersion < MIN_VERSION) {
            return false;
        }
        if (mainVersion > MAX_VERSION) {
            return false;
        }
        // 刚好是最低主版本时，检查补丁号
        if (mainVersion == MIN_VERSION && patch < MIN_PATCH) {
            return false;
        }
        return true;
    }

    private static int parseIntSafe(String str) {
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String getVersionString() {
        return Bukkit.getVersion();
    }
}
