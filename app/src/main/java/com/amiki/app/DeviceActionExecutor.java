package com.amiki.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;

public final class DeviceActionExecutor {

    public interface Callback {
        void onResult(String message);
        void onError(String message);
    }

    public static final int CALL_CONTACT_PERMISSION_REQUEST = 3101;

    private final Activity activity;
    private Callback pendingCallback;
    private String pendingContactName;

    public DeviceActionExecutor(Activity activity) {
        this.activity = activity;
    }

    public boolean tryExecute(String rawMessage, Callback callback) {
        DeviceCommandParser.Command command =
                DeviceCommandParser.parse(rawMessage);

        if (command == null) {
            return false;
        }

        execute(command, callback);
        return true;
    }

    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        if (requestCode != CALL_CONTACT_PERMISSION_REQUEST) {
            return;
        }

        Callback callback = pendingCallback;
        String contactName = pendingContactName;
        pendingCallback = null;
        pendingContactName = null;

        if (callback == null || contactName == null) {
            return;
        }

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            executeContactCall(contactName, callback);
        } else {
            callback.onError(
                    "I need Contacts permission to find that person. " +
                    "No call was placed."
            );
        }
    }

    private void execute(
            DeviceCommandParser.Command command,
            Callback callback
    ) {
        try {
            switch (command.action) {
                case OPEN_SETTINGS:
                    activity.startActivity(
                            new Intent(Settings.ACTION_SETTINGS)
                    );
                    callback.onResult("Opening Android Settings.");
                    return;

                case OPEN_CHROME:
                    openChrome(callback);
                    return;

                case BATTERY_STATE:
                    BatteryManager batteryManager =
                            (BatteryManager) activity.getSystemService(
                                    Activity.BATTERY_SERVICE
                            );
                    if (batteryManager == null) {
                        callback.onError("I couldn't read the phone battery status.");
                        return;
                    }

                    int level = batteryManager.getIntProperty(
                            BatteryManager.BATTERY_PROPERTY_CAPACITY
                    );

                    if (level < 0 || level > 100) {
                        callback.onError("The phone did not provide a valid battery reading.");
                        return;
                    }

                    callback.onResult("Battery is at " + level + "%.");
                    return;

                case SET_ALARM:
                    Intent alarm = new Intent(AlarmClock.ACTION_SET_ALARM);
                    alarm.putExtra(AlarmClock.EXTRA_HOUR, command.hour);
                    alarm.putExtra(AlarmClock.EXTRA_MINUTES, command.minute);
                    if (command.message != null && !command.message.trim().isEmpty()) {
                        alarm.putExtra(AlarmClock.EXTRA_MESSAGE, command.message);
                    }

                    if (alarm.resolveActivity(activity.getPackageManager()) == null) {
                        callback.onError("This phone does not expose an alarm app Amiki can open.");
                        return;
                    }

                    activity.startActivity(alarm);
                    callback.onResult(
                            "Opening the alarm screen for " +
                            String.format(
                                    java.util.Locale.getDefault(),
                                    "%02d:%02d",
                                    command.hour,
                                    command.minute
                            ) + "."
                    );
                    return;

                case CALL_CONTACT:
                    startContactCall(command.argument, callback);
                    return;

                default:
                    callback.onError("That device action is not available.");
            }
        } catch (SecurityException error) {
            callback.onError("Android denied that device action.");
        } catch (RuntimeException error) {
            callback.onError("The device action could not be completed.");
        }
    }

    private void openChrome(Callback callback) {
        Intent launch = activity.getPackageManager()
                .getLaunchIntentForPackage("com.android.chrome");

        if (launch == null) {
            launch = activity.getPackageManager()
                    .getLaunchIntentForPackage("com.google.android.apps.chrome");
        }

        if (launch == null) {
            launch = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com")
            );
        }

        try {
            activity.startActivity(launch);
            callback.onResult("Opening Chrome.");
        } catch (RuntimeException error) {
            callback.onError("Chrome could not be opened.");
        }
    }

    private void startContactCall(String name, Callback callback) {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingContactName = name;
            pendingCallback = callback;
            activity.requestPermissions(
                    new String[]{Manifest.permission.READ_CONTACTS},
                    CALL_CONTACT_PERMISSION_REQUEST
            );
            return;
        }

        executeContactCall(name, callback);
    }

    private void executeContactCall(String name, Callback callback) {
        String number = findPhoneNumber(name);

        if (number == null) {
            callback.onError("I couldn't find a phone number for " + name + ".");
            return;
        }

        Intent dial = new Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:" + number)
        );

        if (dial.resolveActivity(activity.getPackageManager()) == null) {
            callback.onError("This phone does not have a dialer available.");
            return;
        }

        activity.startActivity(dial);
        callback.onResult(
                "I found " + name + ". Opening the dialer so you can place the call."
        );
    }

    private String findPhoneNumber(String name) {
        Uri uri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(name)
        );

        try (Cursor cursor = activity.getContentResolver().query(
                uri,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                );
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (SecurityException error) {
            return null;
        }

        return null;
    }
}
