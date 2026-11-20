package com.amiki.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * Narrow app handoff adapter. It opens supported app destinations but does not
 * automate UI controls, send messages, or claim Spotify playback.
 */
public final class CrossAppIntentGateway {
    public interface Callback {
        void onResult(String message);
    }

    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final String WHATSAPP_PACKAGE = "com.whatsapp";
    private final Context context;

    public CrossAppIntentGateway(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required.");
        this.context = context;
    }

    public void openSpotifySearch(String track, String artist, Callback callback) {
        try {
            String url = CrossAppRequestValidator.spotifySearchUrl(track, artist);
            openAppLink(url, SPOTIFY_PACKAGE);
            callback.onResult("Opened Spotify search for " + track.trim() + " by "
                    + artist.trim() + ". Select the intended result and start playback; playback is not yet automated or verified.");
        } catch (IllegalArgumentException e) {
            callback.onResult(e.getMessage() + " Nothing was opened.");
        } catch (Exception e) {
            callback.onResult("Spotify could not be opened. No playback was started.");
        }
    }

    public void openWhatsAppCompose(String phoneNumber, String message, Callback callback) {
        try {
            String url = CrossAppRequestValidator.whatsappComposeUrl(phoneNumber, message);
            openAppLink(url, WHATSAPP_PACKAGE);
            callback.onResult("Opened the WhatsApp compose screen for the approved recipient. Review and send the message in WhatsApp; Amiki has not sent it.");
        } catch (IllegalArgumentException e) {
            callback.onResult(e.getMessage() + " Nothing was opened.");
        } catch (Exception e) {
            callback.onResult("WhatsApp could not be opened. No message was sent.");
        }
    }

    private void openAppLink(String url, String packageName) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setPackage(packageName);
        PackageManager manager = context.getPackageManager();
        if (intent.resolveActivity(manager) == null) {
            throw new IllegalStateException("The target app is not installed or cannot handle this action.");
        }
        context.startActivity(intent);
    }
}
