package com.amiki.app;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Pure validation and URI construction for supported cross-app handoffs. */
public final class CrossAppRequestValidator {
    private CrossAppRequestValidator() {}

    public static String spotifySearchUrl(String track, String artist) {
        String safeTrack = required(track, "Track title");
        String safeArtist = required(artist, "Artist");
        return "https://open.spotify.com/search/" + encode(safeTrack + " " + safeArtist);
    }

    /**
     * Creates a WhatsApp compose URL only. Opening it does not send the message.
     * The phone number must include a country calling code (8-15 digits total).
     */
    public static String whatsappComposeUrl(String phoneNumber, String message) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("[^0-9]", "");
        if (digits.length() < 8 || digits.length() > 15) {
            throw new IllegalArgumentException("Recipient phone must include a valid country code.");
        }
        String safeMessage = required(message, "Message");
        return "https://wa.me/" + digits + "?text=" + encode(safeMessage);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable.", impossible);
        }
    }
}
