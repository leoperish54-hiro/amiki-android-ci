package com.amiki.app;

import android.content.Context;

/**
 * Reads the device's encrypted, non-expiring general access token.
 * General Amiki access is never renewed on a timer; it remains valid until
 * the owner revokes the grant or the enrolled device is revoked.
 */
final class OwnerSessionProvider {
    private OwnerSessionProvider() {}

    static String getGeneralToken(Context context) throws Exception {
        return OwnerSessionVault.readGeneralToken(context.getApplicationContext());
    }

    static String getPrivilegedToken(Context context) throws Exception {
        return OwnerSessionVault.readPrivilegedToken(context.getApplicationContext());
    }
}
