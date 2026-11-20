package com.amiki.app;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class CrossAppRequestValidatorTest {
    @Test public void spotifySearchIncludesTrackAndArtist() {
        String url = CrossAppRequestValidator.spotifySearchUrl("Lucid Dreams", "Juice WRLD");
        assertTrue(url.startsWith("https://open.spotify.com/search/"));
        assertTrue(url.contains("Lucid%20Dreams"));
        assertTrue(url.contains("Juice%20WRLD"));
    }

    @Test public void whatsappComposeEncodesMessageAndCountryCode() {
        String url = CrossAppRequestValidator.whatsappComposeUrl("+260 977 123 456", "Are you gonna be home at ten?");
        assertTrue(url.startsWith("https://wa.me/260977123456?text="));
        assertTrue(url.contains("Are%20you%20gonna%20be%20home%20at%20ten%3F"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void whatsappRejectsNumberWithoutCountryCodeLength() {
        CrossAppRequestValidator.whatsappComposeUrl("12345", "Hello");
    }

    @Test(expected = IllegalArgumentException.class)
    public void whatsappRejectsEmptyMessage() {
        CrossAppRequestValidator.whatsappComposeUrl("+260977123456", "  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void spotifyRequiresArtist() {
        CrossAppRequestValidator.spotifySearchUrl("Lucid Dreams", " ");
    }
}
