package com.amiki.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One-shot, permission-aware location context for explicit user requests and safety assistance. */
final class LocationContextProvider {
    private static final long MAX_AGE_MS = 5L * 60L * 1000L;
    private static final long WAIT_SECONDS = 3L;

    private LocationContextProvider() {}

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isSafetySensitiveMessage(String message) {
        if (message == null || message.trim().isEmpty()) return false;
        String text = message.toLowerCase(Locale.ROOT);
        String[] signals = {
                "ways to die", "want to die", "i want to die", "kill myself",
                "end my life", "suicide", "self-harm", "self harm",
                "hurt myself", "overdose", "i am in danger", "i'm in danger",
                "not safe", "emergency", "crisis", "someone is threatening me",
                "help me stay safe"
        };
        for (String signal : signals) {
            if (text.contains(signal)) return true;
        }
        return false;
    }

    static boolean isCurrentLocationRequest(String message) {
        if (message == null || message.trim().isEmpty()) return false;
        String text = message.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
        String[] signals = {
                "what is my current location", "what's my current location",
                "what is my location", "what's my location", "where am i",
                "where am i right now", "tell me my location",
                "tell me where i am", "my current location",
                "what place am i in", "which area am i in",
                "where are we right now"
        };
        for (String signal : signals) {
            if (text.contains(signal)) return true;
        }
        return false;
    }

    static boolean shouldAttachLocationContext(String message) {
        return isCurrentLocationRequest(message) || isSafetySensitiveMessage(message);
    }

    static JSONObject getFreshLocationContext(Context context) {
        if (!hasPermission(context)) return null;
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;

        AtomicReference<Location> best = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!isFresh(location)) return;
                Location current = best.get();
                if (current == null || (location.hasAccuracy()
                        && (!current.hasAccuracy() || location.getAccuracy() < current.getAccuracy()))) {
                    best.set(location);
                }
                if (location.hasAccuracy() && location.getAccuracy() <= 1000f) received.countDown();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            long now = System.currentTimeMillis();
            for (String provider : manager.getProviders(true)) {
                try {
                    Location cached = manager.getLastKnownLocation(provider);
                    if (isFresh(cached) && (best.get() == null
                            || cached.getTime() > best.get().getTime())) {
                        best.set(cached);
                    }
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
                } catch (SecurityException ignored) {
                    // Permission may be revoked while the request is being prepared.
                } catch (IllegalArgumentException ignored) {
                    // Ignore providers that cannot be requested on this device.
                }
            }
            received.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (SecurityException ignored) {
            return null;
        } finally {
            try { manager.removeUpdates(listener); } catch (SecurityException ignored) {}
        }

        Location location = best.get();
        if (!isFresh(location)) return null;
        try {
            JSONObject result = new JSONObject();
            result.put("latitude", location.getLatitude());
            result.put("longitude", location.getLongitude());
            result.put("accuracy_meters", location.hasAccuracy()
                    ? Math.max(1f, location.getAccuracy()) : 10000f);
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            result.put("timestamp", format.format(new Date(location.getTime())));
            result.put("source", "android_location_provider");
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                    List<Address> addresses = geocoder.getFromLocation(
                            location.getLatitude(), location.getLongitude(), 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address address = addresses.get(0);
                        String countryCode = address.getCountryCode();
                        String countryName = address.getCountryName();
                        String locality = address.getLocality();
                        if (locality == null || locality.trim().isEmpty()) {
                            locality = address.getSubAdminArea();
                        }
                        if (locality == null || locality.trim().isEmpty()) {
                            locality = address.getAdminArea();
                        }
                        if (countryCode != null && !countryCode.trim().isEmpty()) {
                            result.put("country_code", countryCode.trim().toUpperCase(Locale.ROOT));
                        }
                        if (countryName != null && !countryName.trim().isEmpty()) {
                            result.put("country_name", countryName.trim());
                        }
                        if (locality != null && !locality.trim().isEmpty()) {
                            result.put("locality", locality.trim());
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // Reverse geocoding is best-effort; do not block safety help on it.
            }
            return result;
        } catch (Exception invalidPayload) {
            return null;
        }
    }

    private static boolean isFresh(Location location) {
        if (location == null) return false;
        long age = System.currentTimeMillis() - location.getTime();
        return age >= -60_000L && age <= MAX_AGE_MS
                && location.getLatitude() >= -90 && location.getLatitude() <= 90
                && location.getLongitude() >= -180 && location.getLongitude() <= 180;
    }
}
