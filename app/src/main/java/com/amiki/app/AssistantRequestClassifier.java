package com.amiki.app;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conservative gate between device-action language and ordinary assistant chat.
 * It does not execute or authorize anything; it prevents unsupported control
 * requests from being answered conversationally as though the phone acted.
 */
public final class AssistantRequestClassifier {
    private static final Pattern ACTION_PREFIX = Pattern.compile(
            "^(?:please\\s+)?(?:open|launch|start|search|find|play|pause|send|message|text|"
                    + "call|dial|set|create|tap|click|press|type|scroll|swipe|navigate|visit|"
                    + "go\\s+to|turn\\s+on|turn\\s+off|enable|disable|switch\\s+on|"
                    + "switch\\s+off|install|uninstall)\\b.*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private AssistantRequestClassifier() { }

    public static boolean isDeviceActionRequest(String command) {
        if (command == null) return false;
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("amiki,")) normalized = normalized.substring(6).trim();
        else if (normalized.startsWith("amiki ")) normalized = normalized.substring(6).trim();
        return !normalized.isEmpty() && ACTION_PREFIX.matcher(normalized).matches();
    }
}
