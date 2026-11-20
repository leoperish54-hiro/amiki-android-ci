package com.amiki.app;

import java.util.Locale;

/** Strict normalizer for the one wake phrase configured in the KWS stream. */
final class AmikiWakeWordPhrase {
    private AmikiWakeWordPhrase() { }

    static boolean isWakePhrase(String result) {
        if (result == null) return false;
        String normalized = result.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return "heyamiki".equals(normalized);
    }
}
