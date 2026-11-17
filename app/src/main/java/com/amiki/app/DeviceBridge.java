package com.amiki.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.AlarmClock;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class DeviceBridge {
    public interface Callback {
        void onResult(String message);
    }

    private final Context context;
    private static final String PLAN = ApiEndpoints.DEVICE_PLAN;

    public DeviceBridge(Context context) {
        this.context = context;
    }

    public void request(String action, JSONObject arguments, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("action", action);
                request.put("arguments", arguments == null ? new JSONObject() : arguments);

                connection = ApiConnection.open(context, PLAN);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(request.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    callback.onResult("Device action was rejected by the backend.");
                    return;
                }

                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }

                JSONObject plan = new JSONObject(body.toString());
                executePlan(plan, callback);
            } catch (Exception e) {
                callback.onResult("Device integration is unavailable.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void executePlan(JSONObject plan, Callback callback) {
        try {
            if (!"planned".equals(plan.optString("status"))) {
                callback.onResult("Device action was not approved by the device planner.");
                return;
            }

            // Fail closed: plans require an explicit local confirmation unless the
            // backend explicitly marks the action as not requiring approval.
            if (plan.optBoolean("requires_user_approval", true)) {
                requestUserApproval(plan, callback);
                return;
            }

            executeApprovedPlan(plan, callback);
        } catch (Exception e) {
            callback.onResult("The device action could not be completed.");
        }
    }

    private void requestUserApproval(JSONObject plan, Callback callback) {
        if (!(context instanceof android.app.Activity)) {
            callback.onResult("This device action needs approval in the Amiki app. No action was executed.");
            return;
        }

        android.app.Activity activity = (android.app.Activity) context;
        String action = plan.optString("action", "unknown");
        JSONObject args = plan.optJSONObject("arguments");
        if (args == null) args = new JSONObject();

        StringBuilder message = new StringBuilder();
        message.append(plan.optString("reason", "Amiki wants to perform a device action."));
        message.append("\n\nAction: ").append(action);
        if ("open_url".equals(action)) {
            message.append("\nURL: ").append(args.optString("url", "(missing)"));
        } else if ("open_app".equals(action)) {
            message.append("\nApp package: ").append(args.optString("package", "(missing)"));
        } else if ("spotify_search".equals(action)) {
            message.append("\nTrack: ").append(args.optString("track", "(missing)"));
            message.append("\nArtist: ").append(args.optString("artist", "(missing)"));
            message.append("\nThis opens Spotify search; it does not start playback.");
        } else if ("whatsapp_compose".equals(action)) {
            message.append("\nRecipient phone: ").append(args.optString("phone_number", "(missing)"));
            message.append("\nExact message: ").append(args.optString("message", "(missing)"));
            message.append("\nThis opens a compose screen; you must review and send in WhatsApp.");
        } else if ("speak".equals(action)) {
            message.append("\nText to speak: ").append(args.optString("text", "(missing)"));
        } else if ("share_text".equals(action)) {
            message.append("\nText to share: ").append(args.optString("text", "(missing)"));
        } else if ("call_contact".equals(action)) {
            message.append("\nContact: ").append(args.optString("name", "(missing)"));
        }

        final String approvalMessage = message.toString();
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()
                    || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                callback.onResult("Approval could not be requested because Amiki is closing. No action was executed.");
                return;
            }

            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Approve device action?")
                    .setMessage(approvalMessage)
                    .setNegativeButton("Cancel", (dialog, which) ->
                            callback.onResult("Device action cancelled. Nothing was executed."))
                    .setPositiveButton("Approve", (dialog, which) ->
                            executeApprovedPlan(plan, callback))
                    .setOnCancelListener(dialog ->
                            callback.onResult("Device action cancelled. Nothing was executed."))
                    .show();
        });
    }

    private void executeApprovedPlan(JSONObject plan, Callback callback) {
        try {
            final String action = plan.getString("action");
            JSONObject args = plan.optJSONObject("arguments");
            if (args == null) args = new JSONObject();

            if ("open_url".equals(action)) {
                String url = args.getString("url");
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                callback.onResult("Opened the requested URL.");
            } else if ("open_app".equals(action)) {
                String packageName = args.getString("package");
                DeviceCapabilityGateway.LaunchResult launch =
                        new DeviceCapabilityGateway(context).launchConfirmedApp(packageName);
                callback.onResult(launch.message);
            } else if ("spotify_search".equals(action)) {
                new CrossAppIntentGateway(context).openSpotifySearch(
                        args.optString("track", ""), args.optString("artist", ""), callback::onResult);
            } else if ("whatsapp_compose".equals(action)) {
                new CrossAppIntentGateway(context).openWhatsAppCompose(
                        args.optString("phone_number", ""), args.optString("message", ""), callback::onResult);
            } else if ("speak".equals(action)) {
                String text = args.getString("text");
                if (context instanceof MobileShellActivity) {
                    ((MobileShellActivity) context).speakDeviceText(text, callback);
                } else {
                    callback.onResult("Speech requires the Amiki shell.");
                }
            } else if ("share_text".equals(action)) {
                String text = args.getString("text");
                Intent share = Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                        "Share with");
                context.startActivity(share);
                callback.onResult("Opened the Android share sheet.");
            } else if ("battery_state".equals(action)) {
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                int percent = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                callback.onResult(percent < 0 ? "Battery level unavailable." : "Battery: " + percent + "%.");
            } else if ("set_alarm".equals(action)) {
                int hour = args.optInt("hour", -1);
                int minute = args.optInt("minute", 0);
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                    callback.onResult("The alarm time is invalid.");
                    return;
                }
                Intent alarm = new Intent(AlarmClock.ACTION_SET_ALARM);
                alarm.putExtra(AlarmClock.EXTRA_HOUR, hour);
                alarm.putExtra(AlarmClock.EXTRA_MINUTES, minute);
                if (args.has("message") && !args.optString("message").trim().isEmpty()) {
                    alarm.putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("message").trim());
                }
                if (alarm.resolveActivity(context.getPackageManager()) == null) {
                    callback.onResult("This phone does not expose an alarm app Amiki can open.");
                    return;
                }
                context.startActivity(alarm);
                callback.onResult(String.format(Locale.getDefault(),
                        "Opening the alarm screen for %02d:%02d.", hour, minute));
            } else if ("call_contact".equals(action)) {
                String name = args.optString("name", "").trim();
                if (name.isEmpty()) {
                    callback.onResult("A contact name is required. No call was placed.");
                } else if (context instanceof MobileShellActivity) {
                    ((MobileShellActivity) context).executeDeviceContactCall(name, callback);
                } else {
                    callback.onResult("Contact dialing is only available from the active Amiki shell.");
                }
            } else if ("device_state".equals(action)) {
                callback.onResult("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ").");
            } else {
                callback.onResult("Unsupported device action.");
            }
        } catch (Exception e) {
            callback.onResult("The device action could not be completed.");
        }
     }
}
