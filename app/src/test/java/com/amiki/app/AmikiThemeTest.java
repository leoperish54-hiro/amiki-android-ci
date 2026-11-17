package com.amiki.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class AmikiThemeTest {
    @Test
    public void darkModeUsesMidnightScholarPalette() {
        AmikiTheme.Palette palette = AmikiTheme.paletteFor(AmikiTheme.MODE_DARK, false);
        assertEquals(0xFF0E1224, palette.background);
        assertEquals(0xFF171C33, palette.panel);
        assertEquals(0xFF8B7CFF, palette.primary);
        assertEquals(0xFF4DE1C1, palette.accent);
        assertEquals(0xFFE8EAF6, palette.text);
    }

    @Test
    public void lightModeUsesReadableLightPalette() {
        AmikiTheme.Palette palette = AmikiTheme.paletteFor(AmikiTheme.MODE_LIGHT, true);
        assertEquals(0xFFF6F4EF, palette.background);
        assertEquals(0xFF202238, palette.text);
        assertNotEquals(palette.background, palette.text);
    }

    @Test
    public void systemModeFollowsSystemAppearanceOnOlderAndroidFallback() {
        assertEquals(0xFF0E1224,
                AmikiTheme.paletteFor(AmikiTheme.MODE_SYSTEM, true).background);
        assertEquals(0xFFF6F4EF,
                AmikiTheme.paletteFor(AmikiTheme.MODE_SYSTEM, false).background);
    }

    @Test
    public void unknownModeSafelyFallsBackToDark() {
        assertEquals(0xFF0E1224, AmikiTheme.paletteFor("unexpected", false).background);
    }
}
