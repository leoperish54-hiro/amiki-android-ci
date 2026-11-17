package com.amiki.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/** Central palette and appearance-mode resolver for the active Amiki shell. */
final class AmikiTheme {
    static final String MODE_DARK = "dark";
    static final String MODE_LIGHT = "light";
    static final String MODE_SYSTEM = "system";
    static final String PREF_KEY = "appearance_mode";

    static final class Palette {
        final int background, panel, card, cardRaised;
        final int primary, accent, text, muted;

        Palette(int background, int panel, int card, int cardRaised,
                int primary, int accent, int text, int muted) {
            this.background = background;
            this.panel = panel;
            this.card = card;
            this.cardRaised = cardRaised;
            this.primary = primary;
            this.accent = accent;
            this.text = text;
            this.muted = muted;
        }
    }

    private static final Palette DARK = new Palette(
            0xFF0E1224, 0xFF171C33, 0xFF1D2340, 0xFF242B4A,
            0xFF8B7CFF, 0xFF4DE1C1, 0xFFE8EAF6, 0xFFADB4D0);
    private static final Palette LIGHT = new Palette(
            0xFFF6F4EF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFEAE7F4,
            0xFF6555D9, 0xFF147F70, 0xFF202238, 0xFF62657A);

    private AmikiTheme() {}

    static Palette paletteFor(String mode, boolean systemNight) {
        if (MODE_LIGHT.equals(mode)) return LIGHT;
        if (MODE_SYSTEM.equals(mode) && !systemNight) return LIGHT;
        return DARK;
    }

    static Palette resolve(Context context, String mode) {
        boolean systemNight = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Palette fallback = paletteFor(mode, systemNight);
        if (!MODE_SYSTEM.equals(mode) || Build.VERSION.SDK_INT < 31) return fallback;

        // Android 12+ dynamic resources are optional. If unavailable, retain our own palette.
        try {
            int primaryId = context.getResources().getIdentifier(
                    "system_accent1_500", "color", "android");
            int accentId = context.getResources().getIdentifier(
                    "system_accent2_500", "color", "android");
            if (primaryId == 0 || accentId == 0) return fallback;
            int primary = context.getColor(primaryId);
            int accent = context.getColor(accentId);
            return new Palette(fallback.background, fallback.panel, fallback.card,
                    fallback.cardRaised, primary, accent, fallback.text, fallback.muted);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
