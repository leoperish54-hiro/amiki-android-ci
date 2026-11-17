package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Toast;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * @deprecated The launcher now opens MobileShellActivity. This retained activity
 * exists only for source compatibility and should not receive new UI features.
 */
@Deprecated
public class MainActivity extends Activity {

    private static final String API_URL = ApiEndpoints.CHAT;
    private static final String DIRECT_AGENT_URL = ApiEndpoints.DIRECT_AGENT;
    private static final String ATTACHMENTS_API_URL = ApiEndpoints.CHAT_ATTACHMENTS;
    private static final String HEALTH_URL = ApiEndpoints.HEALTH;
    private static final String MEMORY_URL = ApiEndpoints.MEMORY;
    private static final String CAPABILITIES_URL = ApiEndpoints.CAPABILITIES;
    private static final String SYSTEM_STATE_URL = ApiEndpoints.SYSTEM_STATE;
    private static final String DATABASE_STATUS_URL = ApiEndpoints.DATABASE_STATUS;
    private static final String DATABASE_SESSION_URL = ApiEndpoints.DATABASE_SESSION;
    private static final String DIAGNOSTICS_URL = ApiEndpoints.DIAGNOSTICS;
    private static final String OBSERVABILITY_EVENTS_URL = ApiEndpoints.OBSERVABILITY_EVENTS;
    private static final String OBSERVABILITY_DIAGNOSTICS_URL = ApiEndpoints.OBSERVABILITY_DIAGNOSTICS;
    private static final String OBSERVABILITY_STATUS_URL = ApiEndpoints.OBSERVABILITY_STATUS;
    private String activeCapability = null;

    private String awarenessState = null;
    private String awarenessStage = null;
    private String awarenessCapability = null;
    private String awarenessProvider = null;
    private String awarenessModel = null;
    private JSONObject awarenessLastRequest = null;
    private TextView awarenessStatusBody = null;
    private TextView attachmentStatus = null;
    private Button chatSendButton = null;
    private static final int PICK_ATTACHMENTS_REQUEST = 1001;
    private final ArrayList<Uri> selectedAttachments = new ArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean activityDestroyed = false;

    private void postToUi(Runnable action) {
        if (activityDestroyed || isFinishing() || android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed()) {
            return;
        }

        mainHandler.post(() -> {
            if (activityDestroyed || isFinishing() || android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed()) {
                return;
            }
            action.run();
        });
    }

    private enum Screen {
        CHAT,
        DASHBOARD,
        MEMORY,
        TOOLS,
        UNIVERSITY,
        PROJECTS,
        ANALYSIS,
        DATABASE,
        SETTINGS,
        DIAGNOSTICS
    }

    private Screen currentScreen = Screen.CHAT;
    private FrameLayout navigationShell;
    private LinearLayout screenHost;
    private LinearLayout navigationDrawer;
    private View drawerOverlay;
    private WorkspaceScreens workspaceScreens;
    private ChatScreen chatScreen;
    private LinearLayout universityScreen;
    private LinearLayout projectsScreen;
    private LinearLayout analysisScreen;
    private LinearLayout databaseScreen;
    private LinearLayout settingsScreen;
    private SettingsScreen settingsController;
    private DiagnosticsScreen diagnosticsScreen;
    private TextToSpeech textToSpeech;
    private SpeechRecognizer speechRecognizer;
    private volatile boolean ttsReady = false;
    private SharedPreferences settingsPreferences;
    private static final String SETTINGS_PREFS = "amiki_settings";
    private DeviceActionExecutor deviceActionExecutor;

    private void loadDatabaseStatus(
            LinearLayout content,
            TextView status
    ) {
        postToUi(() -> status.setText("LOADING DATABASE STATUS..."));

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(DATABASE_STATUS_URL);
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int statusCode = connection.getResponseCode();

                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + statusCode);
                }

                StringBuilder response = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                connection.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                JSONObject databaseStatus = new JSONObject(
                        response.toString()
                );

                postToUi(() -> {
                    renderDatabaseStatus(content, status, databaseStatus);
                });
            } catch (Exception e) {
                postToUi(() -> {
                    status.setText(
                            "DATABASE STATUS UNAVAILABLE\n" +
                            "Open Diagnostics for details."
                    );
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void renderDatabaseStatus(
            LinearLayout content,
            TextView status,
            JSONObject databaseStatus
    ) {
        content.removeAllViews();

        String overallStatus = databaseStatus.optString(
                "overall_status",
                "unknown"
        );

        addDatabaseCard(
                content,
                "🗄 Overall Status",
                "Storage status: " + overallStatus
        );

        JSONObject persistentMemory =
                databaseStatus.optJSONObject("persistent_memory");

        if (persistentMemory != null) {
            addDatabaseCard(
                    content,
                    "🧠 Persistent Memory",
                    "Enabled: " + persistentMemory.optBoolean("enabled") +
                    "\nMemories: " + persistentMemory.optInt("count") +
                    "\nCategories: " +
                    persistentMemory.optInt("categories") +
                    "\nStatus: " +
                    persistentMemory.optString("status")
            );
        }

        JSONObject currentSession =
                databaseStatus.optJSONObject("current_session");

        if (currentSession != null) {
            addDatabaseCard(
                    content,
                    "💬 Current Session",
                    "Enabled: " +
                    currentSession.optBoolean("enabled") +
                    "\nActive: " +
                    currentSession.optBoolean("active") +
                    "\nMessages: " +
                    currentSession.optInt("message_count") +
                    " / " +
                    currentSession.optInt("max_messages") +
                    "\nStatus: " +
                    currentSession.optString("status")
            );
        }

        JSONObject attachments =
                databaseStatus.optJSONObject("attachments");

        if (attachments != null) {
            addDatabaseCard(
                    content,
                    "📎 Attachments",
                    "Available: " +
                    attachments.optBoolean("available") +
                    "\nPersistent: " +
                    attachments.optBoolean("persistent") +
                    "\nStatus: " +
                    attachments.optString("status")
            );
        }

        JSONObject chatLogs =
                databaseStatus.optJSONObject("chat_logs");

        if (chatLogs != null) {
            addDatabaseCard(
                    content,
                    "📝 Chat Logs",
                    "Available: " +
                    chatLogs.optBoolean("available") +
                    "\nPersistent: " +
                    chatLogs.optBoolean("persistent") +
                    "\nStatus: " +
                    chatLogs.optString("status")
            );
        }

        addDatabaseAction(
                content,
                "🧠 Clear Persistent Memory",
                "Permanently remove all persistent memories. " +
                "A recovery snapshot is created before the clear operation.",
                "CLEAR ALL MEMORY",
                v -> confirmClearPersistentMemory(content, status)
        );

        addDatabaseAction(
                content,
                "💬 Clear Current Session",
                "Clear Amiki's current conversation session from storage. " +
                "The chat screen is cleared after a successful operation.",
                "CLEAR SESSION",
                v -> confirmClearCurrentSession(content, status)
        );

        status.setText(
                "DATABASE STATUS: " +
                overallStatus.toUpperCase()
        );
    }

    private void addDatabaseCard(
            LinearLayout content,
            String heading,
            String body
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
        );
        card.setBackground(
                roundRect(
                        Color.rgb(17, 21, 40),
                        16
                )
        );

        TextView cardHeading = text(
                heading,
                16,
                Color.rgb(245, 245, 250)
        );
        cardHeading.setTypeface(
                null,
                android.graphics.Typeface.BOLD
        );

        TextView cardBody = text(
                body,
                14,
                Color.rgb(165, 165, 185)
        );
        cardBody.setPadding(0, dp(10), 0, 0);

        card.addView(cardHeading);
        card.addView(cardBody);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        params.setMargins(0, 0, 0, dp(12));
        content.addView(card, params);
    }

    private void addDatabaseAction(
            LinearLayout content,
            String heading,
            String body,
            String buttonLabel,
            View.OnClickListener listener
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundRect(Color.rgb(17, 21, 40), 16));

        TextView cardHeading = text(heading, 16, TEXT);
        cardHeading.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView cardBody = text(body, 14, MUTED);
        cardBody.setPadding(0, dp(10), 0, 0);

        Button action = new Button(this);
        action.setText(buttonLabel);
        action.setTextColor(TEXT);
        action.setTextSize(12);
        action.setOnClickListener(listener);

        card.addView(cardHeading);
        card.addView(cardBody);

        LinearLayout.LayoutParams actionParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                );
        actionParams.setMargins(0, dp(14), 0, 0);
        card.addView(action, actionParams);

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        cardParams.setMargins(0, 0, 0, dp(12));
        content.addView(card, cardParams);
    }

    private void confirmClearPersistentMemory(
            LinearLayout content,
            TextView status
    ) {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Clear Persistent Memory?")
                .setMessage(
                        "This permanently removes every persistent memory stored by Amiki." +
                        "\n\nA recovery snapshot is created before the clear operation."
                )
                .setNegativeButton("CANCEL", null)
                .setPositiveButton(
                        "CLEAR ALL",
                        (dialog, which) -> clearPersistentMemory(content, status)
                )
                .show();
    }

    private void clearPersistentMemory(
            LinearLayout content,
            TextView status
    ) {
        performDatabaseDelete(
                MEMORY_URL,
                "Persistent memory cleared.",
                content,
                status,
                null
        );
    }

    private void confirmClearCurrentSession(
            LinearLayout content,
            TextView status
    ) {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Clear Current Session?")
                .setMessage(
                        "This clears Amiki's current conversation session from storage." +
                        "\n\nA recovery snapshot is created before the clear operation. " +
                        "The chat screen will also be cleared after success."
                )
                .setNegativeButton("CANCEL", null)
                .setPositiveButton(
                        "CLEAR SESSION",
                        (dialog, which) -> clearCurrentSession(content, status)
                )
                .show();
    }

    private void clearCurrentSession(
            LinearLayout content,
            TextView status
    ) {
        performDatabaseDelete(
                DATABASE_SESSION_URL,
                "Current session cleared.",
                content,
                status,
                this::clearCurrentChatUi
        );
    }

    private void performDatabaseDelete(
            String endpoint,
            String successMessage,
            LinearLayout content,
            TextView status,
            Runnable onSuccess
    ) {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(endpoint);
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("DELETE");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);

                int statusCode = connection.getResponseCode();
                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new RuntimeException("HTTP " + statusCode);
                }

                postToUi(() -> {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    Toast.makeText(
                            this,
                            successMessage,
                            Toast.LENGTH_SHORT
                    ).show();
                    loadDatabaseStatus(content, status);
                });
            } catch (Exception e) {
                postToUi(() ->
                        Toast.makeText(
                                this,
                                "Unable to complete database action.",
                                Toast.LENGTH_SHORT
                        ).show()
                );
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void clearCurrentChatUi() {
        if (chatScreen == null) {
            return;
        }

        LinearLayout content = chatScreen.content;
        content.removeAllViews();
        chatScreen.welcomeState.setVisibility(View.VISIBLE);
        content.addView(
                chatScreen.welcomeState,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );
        selectedAttachments.clear();
        if (attachmentStatus != null) {
            attachmentStatus.setVisibility(View.GONE);
        }
    }

    private void updateAwarenessStatusView() {
        if (awarenessStatusBody == null) {
            return;
        }

        String state = awarenessState != null
                ? awarenessState.toUpperCase()
                : "UNKNOWN";

        String capability = awarenessCapability != null
                ? awarenessCapability
                : "—";

        String provider = awarenessProvider != null
                ? awarenessProvider
                : "—";

        String model = awarenessModel != null
                ? awarenessModel
                : "—";

        StringBuilder body = new StringBuilder();
        body.append("Runtime: ").append(state);
        body.append("\nCapability: ").append(capability);
        body.append("\nProvider: ").append(provider);
        body.append("\nModel: ").append(model);

        if (awarenessStage != null) {
            body.append("\nStage: ").append(awarenessStage);
        }

        if (awarenessLastRequest != null) {
            String lastCapability = awarenessLastRequest.optString(
                    "selected_capability",
                    null
            );
            String lastProvider = awarenessLastRequest.optString(
                    "final_provider",
                    null
            );
            String lastModel = awarenessLastRequest.optString(
                    "final_model",
                    null
            );
            String lastResult = awarenessLastRequest.optString(
                    "result",
                    null
            );

            body.append("\n\nLast Request");
            if (lastCapability != null && !lastCapability.isEmpty()) {
                body.append("\nCapability: ").append(lastCapability);
            }
            if (lastProvider != null && !lastProvider.isEmpty()) {
                body.append("\nProvider: ").append(lastProvider);
            }
            if (lastModel != null && !lastModel.isEmpty()) {
                body.append("\nModel: ").append(lastModel);
            }
            if (lastResult != null && !lastResult.isEmpty()) {
                body.append("\nResult: ").append(lastResult);
            }
        }

        awarenessStatusBody.setText(body.toString());
    }

    private void loadSystemAwareness() {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(SYSTEM_STATE_URL);
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int statusCode = connection.getResponseCode();

                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + statusCode);
                }

                StringBuilder response = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                connection.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                JSONObject systemState = new JSONObject(response.toString());
                JSONObject awareness = systemState.optJSONObject("awareness");

                if (awareness == null) {
                    throw new Exception("Missing awareness object");
                }

                String state = awareness.optString("state", null);
                String stage = awareness.optString("current_stage", null);
                String capability = awareness.optString(
                        "current_capability",
                        null
                );
                String provider = awareness.optString(
                        "current_provider",
                        null
                );
                String model = awareness.optString(
                        "current_model",
                        null
                );
                JSONObject lastRequest = awareness.optJSONObject(
                        "last_request"
                );

                postToUi(() -> {
                    awarenessState = state;
                    awarenessStage = stage;
                    awarenessCapability = capability;
                    awarenessProvider = provider;
                    awarenessModel = model;
                    awarenessLastRequest = lastRequest;
                    updateAwarenessStatusView();
                });

            } catch (Exception e) {
                postToUi(() -> {
                    awarenessState = null;
                    awarenessStage = null;
                    awarenessCapability = null;
                    awarenessProvider = null;
                    awarenessModel = null;
                    awarenessLastRequest = null;
                    updateAwarenessStatusView();
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private boolean isSettingEnabled(String key, boolean defaultValue) {
        if (settingsPreferences == null) {
            settingsPreferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        }
        return settingsPreferences.getBoolean(key, defaultValue);
    }

    private void saveSetting(String key, boolean enabled) {
        if (settingsPreferences == null) {
            settingsPreferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        }
        settingsPreferences.edit().putBoolean(key, enabled).apply();
        if (SettingsScreen.KEEP_SCREEN_AWAKE_KEY.equals(key)) {
            applyKeepScreenAwake();
        }
    }

    private void resetSettings() {
        if (settingsPreferences == null) {
            settingsPreferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        }
        settingsPreferences.edit().clear().apply();
        applyKeepScreenAwake();
        Toast.makeText(this, "Settings restored to defaults.", Toast.LENGTH_SHORT).show();
        if (currentScreen == Screen.SETTINGS && settingsController != null) {
            settingsScreen = settingsController.build(true, true, false, false);
            screenHost.removeAllViews();
            screenHost.addView(settingsScreen, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
    }

    private void applyKeepScreenAwake() {
        if (isSettingEnabled(SettingsScreen.KEEP_SCREEN_AWAKE_KEY, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void navigateTo(Screen screen) {
        currentScreen = screen;

        if (screen == Screen.UNIVERSITY) {
            activeCapability = "university";
        } else if (screen == Screen.PROJECTS) {
            activeCapability = "workspace";
        } else if (screen == Screen.ANALYSIS) {
            activeCapability = "analysis";
        } else {
            activeCapability = null;
        }

        closeDrawer();
        screenHost.removeAllViews();

        if (screen == Screen.CHAT) {
            if (chatScreen == null) {
                chatScreen = buildChatScreen();
            }

            screenHost.addView(
                    chatScreen.root,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );

            waitForAstra(chatScreen.status, chatScreen.send);
        }

        if (screen == Screen.DASHBOARD) {
            if (isSettingEnabled(SettingsScreen.SYSTEM_AWARENESS_KEY, true)) {
                loadSystemAwareness();
            }

            LinearLayout dashboard = new LinearLayout(this);
            dashboard.setOrientation(LinearLayout.VERTICAL);
            dashboard.setBackgroundColor(BG);

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(14), dp(10), dp(16), dp(10));
            header.setBackgroundColor(PANEL);

            Button menu = new Button(this);
            menu.setText("☰");
            menu.setTextColor(TEXT);
            menu.setTextSize(22);
            menu.setAllCaps(false);
            menu.setBackgroundColor(Color.TRANSPARENT);
            menu.setPadding(0, 0, 0, 0);
            menu.setOnClickListener(v -> openDrawer());

            TextView title = text("DASHBOARD", 20, TEXT);
            title.setTypeface(null, 1);

            header.addView(
                    menu,
                    new LinearLayout.LayoutParams(dp(48), dp(48))
            );

            header.addView(
                    title,
                    new LinearLayout.LayoutParams(0, dp(48), 1)
            );

            dashboard.addView(header);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(BG);
            content.setPadding(dp(24), dp(24), dp(24), dp(24));

            TextView subtitle = text(
                    "Amiki system overview",
                    14,
                    MUTED
            );
            content.addView(subtitle);

            LinearLayout statusCard = new LinearLayout(this);
            statusCard.setOrientation(LinearLayout.VERTICAL);
            statusCard.setPadding(dp(16), dp(16), dp(16), dp(16));
            statusCard.setBackground(roundRect(CARD, 18));

            TextView statusTitle = text(
                    "🌸 AMIKI",
                    18,
                    TEXT
            );
            statusTitle.setTypeface(null, 1);

            awarenessStatusBody = text(
                    "Runtime: UNKNOWN\nCapability: —\nProvider: —\nModel: —",
                    14,
                    MUTED
            );
            awarenessStatusBody.setPadding(0, dp(6), 0, 0);
            updateAwarenessStatusView();

            statusCard.addView(statusTitle);
            statusCard.addView(awarenessStatusBody);

            LinearLayout.LayoutParams statusParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
            statusParams.setMargins(0, dp(18), 0, dp(12));

            if (isSettingEnabled(SettingsScreen.SYSTEM_AWARENESS_KEY, true)) {
                content.addView(statusCard, statusParams);
            }

            LinearLayout navigationRow = new LinearLayout(this);
            navigationRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView chatCard = text(
                    "CHAT\n\nContinue conversation",
                    14,
                    TEXT
            );
            chatCard.setGravity(Gravity.CENTER);
            chatCard.setPadding(dp(12), dp(16), dp(12), dp(16));
            chatCard.setBackground(roundRect(CARD, 18));
            chatCard.setOnClickListener(v -> navigateTo(Screen.CHAT));

            TextView memoryCard = text(
                    "MEMORY\n\nView remembered context",
                    14,
                    TEXT
            );
            memoryCard.setGravity(Gravity.CENTER);
            memoryCard.setPadding(dp(12), dp(16), dp(12), dp(16));
            memoryCard.setBackground(roundRect(CARD, 18));
            memoryCard.setOnClickListener(v -> navigateTo(Screen.MEMORY));

            LinearLayout.LayoutParams halfParams =
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1
                    );
            halfParams.setMargins(0, 0, dp(6), 0);

            navigationRow.addView(chatCard, halfParams);

            LinearLayout.LayoutParams memoryParams =
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1
                    );
            memoryParams.setMargins(dp(6), 0, 0, 0);

            navigationRow.addView(memoryCard, memoryParams);

            content.addView(navigationRow);

            TextView toolsCard = text(
                    "TOOLS\n\nAnalysis • Workspace • University",
                    14,
                    TEXT
            );
            toolsCard.setGravity(Gravity.CENTER_VERTICAL);
            toolsCard.setPadding(dp(16), dp(18), dp(16), dp(18));
            toolsCard.setBackground(roundRect(CARD, 18));
            toolsCard.setOnClickListener(v -> navigateTo(Screen.TOOLS));

            LinearLayout.LayoutParams toolsParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
            toolsParams.setMargins(0, dp(12), 0, 0);

            content.addView(toolsCard, toolsParams);

            dashboard.addView(
                    content,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1
                    )
            );

            screenHost.addView(
                    dashboard,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (screen == Screen.MEMORY) {
            LinearLayout memory = new LinearLayout(this);
            memory.setOrientation(LinearLayout.VERTICAL);
            memory.setBackgroundColor(BG);

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(14), dp(10), dp(16), dp(10));
            header.setBackgroundColor(PANEL);

            Button menu = new Button(this);
            menu.setText("☰");
            menu.setTextColor(TEXT);
            menu.setTextSize(22);
            menu.setAllCaps(false);
            menu.setBackgroundColor(Color.TRANSPARENT);
            menu.setPadding(0, 0, 0, 0);
            menu.setOnClickListener(v -> openDrawer());

            TextView title = text("MEMORY", 20, TEXT);
            title.setTypeface(null, 1);

            header.addView(
                    menu,
                    new LinearLayout.LayoutParams(dp(48), dp(48))
            );

            header.addView(
                    title,
                    new LinearLayout.LayoutParams(0, dp(48), 1)
            );

            memory.addView(header);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(24), dp(24), dp(24), dp(24));

            TextView subtitle = text(
                    "Amiki's persistent memory",
                    14,
                    MUTED
            );

            content.addView(subtitle);

            LinearLayout memoryList = new LinearLayout(this);
            memoryList.setOrientation(LinearLayout.VERTICAL);

            content.addView(
                    memoryList,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            );

            loadMemory(memoryList);

            memory.addView(
                    content,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1
                    )
            );

            screenHost.addView(
                    memory,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (screen == Screen.TOOLS) {
            LinearLayout tools = new LinearLayout(this);
            tools.setOrientation(LinearLayout.VERTICAL);
            tools.setBackgroundColor(BG);

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(14), dp(10), dp(16), dp(10));
            header.setBackgroundColor(PANEL);

            Button menu = new Button(this);
            menu.setText("☰");
            menu.setTextColor(TEXT);
            menu.setTextSize(22);
            menu.setAllCaps(false);
            menu.setBackgroundColor(Color.TRANSPARENT);
            menu.setPadding(0, 0, 0, 0);
            menu.setOnClickListener(v -> openDrawer());

            TextView title = text("TOOLS", 20, TEXT);
            title.setTypeface(null, 1);

            header.addView(
                    menu,
                    new LinearLayout.LayoutParams(dp(48), dp(48))
            );

            header.addView(
                    title,
                    new LinearLayout.LayoutParams(0, dp(48), 1)
            );

            tools.addView(header);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setBackgroundColor(BG);
            content.setPadding(dp(24), dp(24), dp(24), dp(24));

            TextView subtitle = text(
                    "Amiki's available capabilities",
                    14,
                    MUTED
            );

            content.addView(subtitle);

            LinearLayout capabilitiesContainer = new LinearLayout(this);
            capabilitiesContainer.setOrientation(LinearLayout.VERTICAL);
            content.addView(
                    capabilitiesContainer,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            );

            loadCapabilities(capabilitiesContainer);

            tools.addView(
                    content,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1
                    )
            );

            screenHost.addView(
                    tools,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (screen == Screen.UNIVERSITY) {
            if (universityScreen == null) {
                universityScreen = workspaceScreens.buildSectionScreen(
                        "UNIVERSITY",
                        "Study, courses and academic assistance"
                );
            }

            screenHost.addView(
                    universityScreen,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (screen == Screen.PROJECTS) {
            if (projectsScreen == null) {
                projectsScreen = workspaceScreens.buildSectionScreen(
                        "PROJECTS",
                        "Amiki projects and workspace"
                );
            }

            screenHost.addView(
                    projectsScreen,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (screen == Screen.ANALYSIS) {
            if (analysisScreen == null) {
                analysisScreen = workspaceScreens.buildSectionScreen(
                        "ANALYSIS",
                        "Reasoning, analysis and problem solving"
                );
            }

            screenHost.addView(
                    analysisScreen,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (screen == Screen.DATABASE) {
            if (databaseScreen == null) {
                databaseScreen = workspaceScreens.buildDatabaseScreen();
            }

            screenHost.addView(
                    databaseScreen,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (screen == Screen.DIAGNOSTICS) {
            if (diagnosticsScreen == null) {
                diagnosticsScreen = new DiagnosticsScreen(
                        this,
                        new DiagnosticsScreen.Callbacks() {
                            @Override
                            public void onBack() {
                                navigateTo(Screen.CHAT);
                            }

                            @Override
                            public void onMenu() {
                                openDrawer();
                            }

                            @Override
                            public void onDirectInspection(
                                    String message,
                                    LinearLayout content,
                                    ScrollView scroll,
                                    Button send,
                                    TextView status
                            ) {
                                sendToDirectAgent(
                                        "inspection",
                                        message,
                                        content,
                                        scroll,
                                        send,
                                        status
                                );
                            }
                        },
                        DIAGNOSTICS_URL,
                        OBSERVABILITY_EVENTS_URL,
                        OBSERVABILITY_DIAGNOSTICS_URL,
                        OBSERVABILITY_STATUS_URL
                );
            }

            screenHost.addView(
                    diagnosticsScreen.build(),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );
        }

        if (screen == Screen.SETTINGS) {
            if (settingsPreferences == null) {
                settingsPreferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
            }

            if (settingsController == null) {
                settingsController = new SettingsScreen(
                        this,
                        new SettingsScreen.SettingsCallbacks() {
                            @Override
                            public void onBack() {
                                navigateTo(Screen.CHAT);
                            }

                            @Override
                            public void onMenu() {
                                openDrawer();
                            }

                            @Override
                            public void onSettingChanged(String key, boolean enabled) {
                                saveSetting(key, enabled);
                            }

                            @Override
                            public void onResetSettings() {
                                resetSettings();
                            }

                            @Override
                            public void onOpenDatabase() {
                                navigateTo(Screen.DATABASE);
                            }

                            @Override
                            public void onOpenDiagnostics() {
                                navigateTo(Screen.DIAGNOSTICS);
                            }
                        }
                );
            }

            settingsScreen = settingsController.build(
                    isSettingEnabled(SettingsScreen.AUTO_RECOVERY_KEY, true),
                    isSettingEnabled(SettingsScreen.SYSTEM_AWARENESS_KEY, true),
                    isSettingEnabled(SettingsScreen.KEEP_SCREEN_AWAKE_KEY, false),
                    isSettingEnabled(SettingsScreen.TTS_ENABLED_KEY, false)
            );

            screenHost.addView(
                    settingsScreen,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            );

            applyKeepScreenAwake();
        }

    }

    private volatile boolean requestInFlight = false;
    private volatile boolean requestCancelled = false;
    private volatile HttpURLConnection activeConnection = null;

    private static final int BG = Color.rgb(5, 7, 18);
    private static final int PANEL = Color.rgb(12, 15, 30);
    private static final int CARD = Color.rgb(17, 21, 40);
    private static final int PURPLE = Color.rgb(139, 70, 255);
    private static final int TEXT = Color.rgb(245, 245, 250);
    private static final int MUTED = Color.rgb(165, 165, 185);

    private void loadCapabilities(LinearLayout content) {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(CAPABILITIES_URL);
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int statusCode = connection.getResponseCode();

                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP " + statusCode);
                }

                StringBuilder response = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                connection.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                JSONObject capabilities = new JSONObject(response.toString());

                postToUi(() -> {
                    content.removeAllViews();

                    addCapabilityView(
                            content,
                            "ANALYSIS",
                            "Reasoning and response processing",
                            capabilities.optBoolean("analysis", false)
                    );

                    addCapabilityView(
                            content,
                            "WORKSPACE",
                            "File inspection and project operations",
                            capabilities.optBoolean("workspace", false)
                    );

                    addCapabilityView(
                            content,
                            "UNIVERSITY",
                            "Study and academic assistance",
                            capabilities.optBoolean("university", false)
                    );

                    addCapabilityView(
                            content,
                            "NOCTIS REQUIEM",
                            "Lore and world-building assistance",
                            capabilities.optBoolean("noctis_requiem", false)
                    );

                    addCapabilityView(
                            content,
                            "WEB",
                            "Bounded live internet evidence",
                            capabilities.optBoolean("web", false)
                    );
                });

            } catch (Exception e) {
                postToUi(() -> {
                    content.removeAllViews();

                    TextView error = text(
                            "Unable to load capabilities. Check Diagnostics for details.",
                            15,
                            MUTED
                    );
                    error.setPadding(0, dp(20), 0, dp(12));

                    content.addView(error);
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void addCapabilityView(
            LinearLayout content,
            String name,
            String description,
            boolean available
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        View accent = new View(this);
        accent.setBackgroundColor(capabilityAccent(name));
        row.addView(accent, new LinearLayout.LayoutParams(dp(3), dp(42)));

        TextView capability = text(
                name + "\n" +
                description + "\n" +
                (available ? "AVAILABLE" : "UNAVAILABLE"),
                15,
                available ? TEXT : MUTED
        );
        capability.setPadding(dp(12), dp(9), dp(10), dp(9));
        row.addView(capability, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int capabilityAccent(String name) {
        if ("WEB".equalsIgnoreCase(name)) return Color.rgb(76, 160, 255);
        if ("WORKSPACE".equalsIgnoreCase(name)) return Color.rgb(179, 140, 255);
        if ("UNIVERSITY".equalsIgnoreCase(name)) return Color.rgb(72, 205, 190);
        if ("NOCTIS REQUIEM".equalsIgnoreCase(name)) return Color.rgb(196, 133, 255);
        if ("ANALYSIS".equalsIgnoreCase(name)) return Color.rgb(120, 166, 255);
        if ("DEVICE".equalsIgnoreCase(name)) return Color.rgb(85, 205, 130);
        if ("SECURITY".equalsIgnoreCase(name)) return Color.rgb(242, 184, 75);
        return Color.rgb(139, 124, 255);
    }

    private void waitForAstra(
            TextView status,
            Button send
    ) {
        new Thread(() -> {
            boolean online = false;

            for (int attempt = 0; attempt < 15; attempt++) {
                HttpURLConnection connection = null;

                try {
                    URL url = new URL(HEALTH_URL);
                    connection = ApiConnection.open(this, url);
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(1000);
                    connection.setReadTimeout(1000);

                    int statusCode = connection.getResponseCode();

                    if (statusCode == HttpURLConnection.HTTP_OK) {
                        online = true;
                        break;
                    }

                } catch (Exception ignored) {
                    // Astra may still be starting.
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            final boolean isOnline = online;

            postToUi(() -> {
                if (isOnline) {
                    status.setText("ONLINE");
                    status.setTextColor(Color.rgb(100, 230, 130));
                    send.setEnabled(true);
                } else {
                    status.setText("OFFLINE");
                    status.setTextColor(Color.rgb(255, 100, 100));
                    send.setEnabled(false);
                }
            });
        }).start();
    }

    private boolean startAstraThroughTermux(TextView status, Button send) {
        if (TermuxBackendLauncher.start(this)) return true;
        showRecoveryFailure(
                status,
                send,
                "Recovery could not start Termux. Verify Termux is installed, Run Command permission is enabled, and the Astra Core path is correct."
        );
        return false;
    }

    private void showRecoveryFailure(
            TextView status,
            Button send,
            String message
    ) {
        postToUi(() -> {
            if (status != null) {
                status.setText("OFFLINE");
                status.setTextColor(Color.rgb(255, 100, 100));
            }

            if (send != null) {
                send.setEnabled(false);
            }

            Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
            ).show();
        });
    }

    private static class ChatScreen {
        final View root;
        final TextView status;
        final Button send;
        final View welcomeState;
        final LinearLayout content;

        ChatScreen(
                View root,
                TextView status,
                Button send,
                View welcomeState,
                LinearLayout content
        ) {
            this.root = root;
            this.status = status;
            this.send = send;
            this.welcomeState = welcomeState;
            this.content = content;
        }
    }

    private static class RateLimitException extends Exception {
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                textToSpeech.setLanguage(Locale.getDefault());
            }
        });
    }

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override public void onError(int error) {
                Toast.makeText(MainActivity.this, "Voice input unavailable. You can type instead.", Toast.LENGTH_SHORT).show();
            }

            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty() && chatScreen != null) {
                    View inputView = chatScreen.root.findViewWithTag("amiki_input");
                    if (inputView instanceof EditText) {
                        EditText input = (EditText) inputView;
                        input.setText(matches.get(0));
                        input.setSelection(input.length());
                    }
                }
            }
        });
    }

    private void startVoiceInput() {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO},
                    2001
            );
            return;
        }

        if (speechRecognizer == null) {
            Toast.makeText(this, "Voice input is not available on this device.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizer.startListening(intent);
    }

    private void speakReply(String text) {
        if (!isSettingEnabled(SettingsScreen.TTS_ENABLED_KEY, false) ||
                !ttsReady || text == null || text.trim().isEmpty()) {
            return;
        }

        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "amiki_reply");
        }
    }

    private ChatScreen buildChatScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Top bar
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(10), dp(16), dp(10));
        header.setBackgroundColor(PANEL);

        Button menu = new Button(this);
        menu.setText("☰");
        menu.setTextColor(TEXT);
        menu.setTextSize(22);
        menu.setAllCaps(false);
        menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setPadding(0, 0, 0, 0);
        menu.setOnClickListener(v -> openDrawer());

        TextView logo = text("🌸  Amiki", 20, TEXT);
        logo.setTypeface(null, 1);

        TextView status = text(
                "● STARTING",
                11,
                Color.rgb(255, 190, 80)
        );
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(10), 0, dp(10), 0);
        status.setBackground(roundRect(PANEL, 14));

        header.addView(
                menu,
                new LinearLayout.LayoutParams(dp(48), dp(48))
        );

        header.addView(
                logo,
                new LinearLayout.LayoutParams(0, dp(48), 1)
        );

        header.addView(
                status,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(48)
                )
        );

        root.addView(header);

        // Scrollable conversation area
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));
        content.setClipToPadding(false);

        LinearLayout welcomeState = new LinearLayout(this);
        welcomeState.setOrientation(LinearLayout.VERTICAL);
        welcomeState.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView mark = text("🌸", 42, TEXT);
        mark.setGravity(Gravity.CENTER);
        mark.setContentDescription("Amiki");
        welcomeState.addView(
                mark,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(70)
                )
        );

        TextView welcome = text("Welcome back.", 26, TEXT);
        welcome.setTypeface(null, 1);
        welcome.setGravity(Gravity.CENTER);
        welcomeState.addView(welcome);

        TextView subtitle = text(
                "Your AI companion. Your memory. Your edge.",
                15,
                MUTED
        );
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(28));
        welcomeState.addView(subtitle);

        TextView help = text(
                "What are we working on today?",
                17,
                TEXT
        );
        help.setPadding(0, dp(4), 0, dp(12));
        help.setTypeface(null, 1);

        String[] prompts = {
                "Continue my university prep",
                "Help me build Amiki",
                "Let's learn something new"
        };

        content.addView(
                welcomeState,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        content.addView(
                help,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        scroll.addView(content);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        // Message input
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        inputBar.setBackground(roundRect(PANEL, 18));

        Button voice = new Button(this);
        voice.setText("🎙");
        voice.setTextSize(18);
        voice.setTextColor(TEXT);
        voice.setAllCaps(false);
        voice.setContentDescription("Voice input");
        voice.setBackground(roundRect(CARD, 18));
        voice.setOnClickListener(v -> startVoiceInput());

        Button attach = new Button(this);
        attach.setText("📎");
        attach.setTextSize(18);
        attach.setTextColor(TEXT);
        attach.setAllCaps(false);
        attach.setContentDescription("Attach file");
        attach.setBackground(roundRect(CARD, 18));
        attach.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, PICK_ATTACHMENTS_REQUEST);
        });

        attachmentStatus = new TextView(this);
        attachmentStatus.setTextColor(TEXT);
        attachmentStatus.setTextSize(12);
        attachmentStatus.setPadding(dp(14), dp(8), dp(14), dp(8));
        attachmentStatus.setBackground(roundRect(CARD, 14));
        attachmentStatus.setVisibility(View.GONE);

        EditText input = new EditText(this);
        input.setTag("amiki_input");
        input.setHint("Message Amiki...");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(8);
        input.setMinHeight(dp(54));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setBackground(roundRect(CARD, 20));

        for (String prompt : prompts) {
            TextView promptCard = text(prompt, 15, TEXT);
            promptCard.setGravity(Gravity.CENTER_VERTICAL);
            promptCard.setPadding(dp(16), 0, dp(16), 0);
            promptCard.setBackground(roundRect(CARD, dp(14)));
            promptCard.setOnClickListener(v -> {
                input.setText(prompt);
                input.setSelection(input.length());
                input.requestFocus();
                ((android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE))
                        .showSoftInput(
                                input,
                                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
                        );
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(54)
            );
            params.setMargins(0, 0, 0, dp(10));
            welcomeState.addView(promptCard, params);
        }


        Button send = new Button(this);
        chatSendButton = send;
        send.setText("➤");
        send.setTextColor(Color.WHITE);
        send.setTextSize(18);
        send.setAllCaps(false);
        send.setContentDescription("Send message");
        send.setEnabled(false);
        send.setBackground(roundRect(PURPLE, 18));

        Runnable refreshSendState = () -> {
            boolean hasMessage = !input.getText().toString().trim().isEmpty();
            boolean hasAttachments = !selectedAttachments.isEmpty();
            send.setEnabled(hasMessage || hasAttachments);
        };

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshSendState.run();
                // Grow the composer with the message, then allow internal scrolling.
                input.post(() -> {
                    int desiredLines = Math.min(8, Math.max(1, input.getLineCount()));
                    if (input.getMinLines() != desiredLines) {
                        input.setMinLines(desiredLines);
                    }
                    inputBar.requestLayout();
                });
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                send.performClick();
                return true;
            }
            return false;
        });

        send.setOnClickListener(v -> {
            if (requestInFlight) {
                requestCancelled = true;
                HttpURLConnection connection = activeConnection;
                if (connection != null) {
                    connection.disconnect();
                }
                requestInFlight = false;
                send.setText("➤");
                send.setContentDescription("Send message");
                send.setEnabled(true);
                status.setText("ONLINE");
                status.setTextColor(Color.rgb(100, 230, 130));
                return;
            }

            String message = input.getText().toString().trim();

            if (message.isEmpty() && selectedAttachments.isEmpty()) {
                return;
            }

            welcomeState.setVisibility(View.GONE);

            if (!message.isEmpty()) {
                content.addView(
                        messageCard(
                                "👤  You",
                                message,
                                false
                        )
                );
            } else {
                content.addView(
                        messageCard(
                                "👤  You",
                                "[Attachment]",
                                false
                        )
                );
            }

            input.setText("");

            scroll.post(() ->
                    scroll.fullScroll(View.FOCUS_DOWN)
            );

            if (!message.isEmpty()
                    && selectedAttachments.isEmpty()
                    && handleLocalDeviceCommand(
                            message,
                            content,
                            scroll,
                            send,
                            status
                    )) {
                return;
            }

            String requestMessage = message.isEmpty()
                    ? "Please inspect the attached file."
                    : message;

            sendToAmiki(
                    requestMessage,
                    content,
                    scroll,
                    send,
                    status,
                    isSettingEnabled(SettingsScreen.AUTO_RECOVERY_KEY, true)
            );
        });

        LinearLayout.LayoutParams voiceParams =
                new LinearLayout.LayoutParams(dp(54), dp(54));
        voiceParams.setMargins(0, 0, dp(6), 0);
        inputBar.addView(voice, voiceParams);

        LinearLayout.LayoutParams attachParams =
                new LinearLayout.LayoutParams(dp(54), dp(54));
        attachParams.setMargins(0, 0, dp(8), 0);
        inputBar.addView(attach, attachParams);

        inputBar.addView(
                input,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1
                )
        );

        LinearLayout.LayoutParams sendParams =
                new LinearLayout.LayoutParams(dp(54), dp(54));
        sendParams.setMargins(dp(8), 0, 0, 0);

        inputBar.addView(send, sendParams);

        root.addView(
                attachmentStatus,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        root.addView(
                inputBar,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        return new ChatScreen(root, status, send, welcomeState, content);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (deviceActionExecutor != null) {
            deviceActionExecutor.onRequestPermissionsResult(
                    requestCode,
                    permissions,
                    grantResults
            );
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_ATTACHMENTS_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }

        selectedAttachments.clear();

        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                selectedAttachments.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            selectedAttachments.add(data.getData());
        }

        if (chatSendButton != null) {
            chatSendButton.setEnabled(!selectedAttachments.isEmpty());
        }

        if (attachmentStatus != null) {
            if (selectedAttachments.isEmpty()) {
                attachmentStatus.setVisibility(View.GONE);
            } else {
                attachmentStatus.setText(
                        selectedAttachments.size() == 1
                                ? "1 attachment selected"
                                : selectedAttachments.size() + " attachments selected"
                );
                attachmentStatus.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settingsPreferences = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE);
        applyKeepScreenAwake();
        initTextToSpeech();
        initSpeechRecognizer();
        deviceActionExecutor = new DeviceActionExecutor(this);
        startAstraThroughTermux(null, null);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        navigationShell = new FrameLayout(this);
        navigationShell.setBackgroundColor(BG);

        screenHost = new LinearLayout(this);
        screenHost.setOrientation(LinearLayout.VERTICAL);
        screenHost.setBackgroundColor(BG);

        workspaceScreens = new WorkspaceScreens(
                this,
                new WorkspaceScreens.NavigationCallbacks() {
                    @Override
                    public void onBack() {
                        navigateTo(Screen.CHAT);
                    }

                    @Override
                    public void onMenu() {
                        openDrawer();
                    }

                    @Override
                    public void onDatabaseRefresh(
                            LinearLayout content,
                            TextView status
                    ) {
                        loadDatabaseStatus(content, status);
                    }

                    @Override
                    public void onWorkspaceMessage(
                            String message,
                            LinearLayout content,
                            ScrollView scroll,
                            Button send,
                            TextView status
                    ) {
                        sendToAmiki(
                                message,
                                content,
                                scroll,
                                send,
                                status,
                                isSettingEnabled(SettingsScreen.AUTO_RECOVERY_KEY, true)
                        );
                    }
                    @Override
                    public void onDirectAgentMessage(
                            String agent,
                            String message,
                            LinearLayout content,
                            ScrollView scroll,
                            Button send,
                            TextView status
                    ) {
                        sendToDirectAgent(agent, message, content, scroll, send, status);
                    }

                }
        );

        navigationShell.addView(
                screenHost,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        drawerOverlay = new View(this);
        drawerOverlay.setBackgroundColor(Color.argb(140, 0, 0, 0));
        drawerOverlay.setVisibility(View.GONE);
        drawerOverlay.setOnClickListener(v -> closeDrawer());

        navigationShell.addView(
                drawerOverlay,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        navigationDrawer = buildSidebar();
        navigationDrawer.setVisibility(View.GONE);

        FrameLayout.LayoutParams drawerParams =
                new FrameLayout.LayoutParams(
                        dp(280),
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
        drawerParams.gravity = Gravity.START;

        navigationShell.addView(navigationDrawer, drawerParams);

        setContentView(navigationShell);
        navigateTo(Screen.CHAT);
        if (isSettingEnabled(SettingsScreen.SYSTEM_AWARENESS_KEY, true)) {
            loadSystemAwareness();
        }
    }

    private void openDrawer() {
        drawerOverlay.setVisibility(View.VISIBLE);
        navigationDrawer.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerOverlay.setVisibility(View.GONE);
        navigationDrawer.setVisibility(View.GONE);
    }

    private LinearLayout buildSidebar() {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(PANEL);
        sidebar.setPadding(dp(10), dp(14), dp(10), dp(14));
        sidebar.setClipToPadding(false);

        TextView identity = text("🌸 AMIKI\nDigital companion", 16, TEXT);
        identity.setPadding(dp(14), dp(14), dp(14), dp(20));
        identity.setLineSpacing(0f, 1.08f);

        sidebar.addView(
                identity,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView mainLabel = text("MAIN", 11, MUTED);
        mainLabel.setPadding(dp(12), dp(4), dp(12), dp(8));

        sidebar.addView(
                mainLabel,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView chat = text("CHAT", 12, TEXT);
        chat.setGravity(Gravity.CENTER);
        chat.setPadding(dp(4), dp(12), dp(4), dp(12));
        chat.setBackground(roundRect(CARD, 12));
        chat.setOnClickListener(v -> navigateTo(Screen.CHAT));

        sidebar.addView(
                chat,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView dashboard = text("DASH", 12, TEXT);
        dashboard.setGravity(Gravity.CENTER);
        dashboard.setPadding(dp(4), dp(12), dp(4), dp(12));
        dashboard.setBackground(roundRect(CARD, 12));
        dashboard.setOnClickListener(v -> navigateTo(Screen.DASHBOARD));

        LinearLayout.LayoutParams dashboardParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        dashboardParams.setMargins(0, dp(8), 0, 0);

        sidebar.addView(dashboard, dashboardParams);

        TextView memory = text("MEMORY", 12, TEXT);
        memory.setGravity(Gravity.CENTER);
        memory.setPadding(dp(4), dp(12), dp(4), dp(12));
        memory.setBackground(roundRect(CARD, 12));
        memory.setOnClickListener(v -> navigateTo(Screen.MEMORY));

        LinearLayout.LayoutParams memoryParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        memoryParams.setMargins(0, dp(8), 0, 0);

        sidebar.addView(memory, memoryParams);

        TextView tools = text("TOOLS", 12, TEXT);
        tools.setGravity(Gravity.CENTER);
        tools.setPadding(dp(4), dp(12), dp(4), dp(12));
        tools.setBackground(roundRect(CARD, 12));
        tools.setOnClickListener(v -> navigateTo(Screen.TOOLS));

        LinearLayout.LayoutParams toolsParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        toolsParams.setMargins(0, dp(8), 0, 0);

        sidebar.addView(tools, toolsParams);

        TextView workspaceLabel = text("WORKSPACE", 11, MUTED);
        workspaceLabel.setPadding(dp(12), dp(20), dp(12), dp(8));

        sidebar.addView(
                workspaceLabel,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView university = text("UNIVERSITY", 12, TEXT);
        university.setGravity(Gravity.CENTER);
        university.setPadding(dp(4), dp(12), dp(4), dp(12));
        university.setBackground(roundRect(CARD, 12));
        university.setOnClickListener(v -> navigateTo(Screen.UNIVERSITY));

        sidebar.addView(
                university,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView projects = text("PROJECTS", 12, TEXT);
        projects.setGravity(Gravity.CENTER);
        projects.setPadding(dp(4), dp(12), dp(4), dp(12));
        projects.setBackground(roundRect(CARD, 12));
        projects.setOnClickListener(v -> navigateTo(Screen.PROJECTS));

        LinearLayout.LayoutParams projectsParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        projectsParams.setMargins(0, dp(8), 0, 0);

        sidebar.addView(projects, projectsParams);

        TextView analysis = text("ANALYSIS", 12, TEXT);
        analysis.setGravity(Gravity.CENTER);
        analysis.setPadding(dp(4), dp(12), dp(4), dp(12));
        analysis.setBackground(roundRect(CARD, 12));
        analysis.setOnClickListener(v -> navigateTo(Screen.ANALYSIS));

        LinearLayout.LayoutParams analysisParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        analysisParams.setMargins(0, dp(8), 0, 0);

        sidebar.addView(analysis, analysisParams);

        TextView database = text("DATABASE", 12, TEXT);
        database.setGravity(Gravity.CENTER);
        database.setPadding(dp(4), dp(12), dp(4), dp(12));
        database.setBackground(roundRect(CARD, 12));
        database.setOnClickListener(v -> navigateTo(Screen.DATABASE));

        LinearLayout.LayoutParams databaseParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        databaseParams.setMargins(0, dp(8), 0, 0);

        sidebar.addView(database, databaseParams);

        TextView diagnostics = text("DIAGNOSTICS", 12, TEXT);
        diagnostics.setGravity(Gravity.CENTER);
        diagnostics.setPadding(dp(4), dp(12), dp(4), dp(12));
        diagnostics.setBackground(roundRect(CARD, 12));
        diagnostics.setOnClickListener(v -> navigateTo(Screen.DIAGNOSTICS));

        LinearLayout.LayoutParams diagnosticsParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        diagnosticsParams.setMargins(0, dp(8), 0, 0);

        sidebar.addView(diagnostics, diagnosticsParams);

        TextView browser = text("BROWSER", 12, TEXT);
        browser.setGravity(Gravity.CENTER);
        browser.setPadding(dp(4), dp(12), dp(4), dp(12));
        browser.setBackground(roundRect(CARD, 12));
        browser.setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, BrowserActivity.class));
        });

        LinearLayout.LayoutParams browserParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        browserParams.setMargins(0, dp(8), 0, 0);
        sidebar.addView(browser, browserParams);

        TextView settings = text("SETTINGS", 12, TEXT);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(dp(4), dp(12), dp(4), dp(12));
        settings.setBackground(roundRect(CARD, 12));
        settings.setOnClickListener(v -> navigateTo(Screen.SETTINGS));

        LinearLayout.LayoutParams settingsParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        settingsParams.setMargins(0, dp(8), 0, 0);

        sidebar.addView(settings, settingsParams);

        return sidebar;
    }

    private void copyUriToStream(Uri uri, OutputStream output) throws java.io.IOException {
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new java.io.IOException("Unable to open attachment.");
            }

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        }
    }

    private String getAttachmentMimeType(Uri uri) {
        String mimeType = getContentResolver().getType(uri);
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    private String getAttachmentFileName(Uri uri) {
        Cursor cursor = getContentResolver().query(
                uri,
                null,
                null,
                null,
                null
        );

        if (cursor != null) {
            try {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex);
                }
            } finally {
                cursor.close();
            }
        }

        return "attachment";
    }

    private String readHttpErrorBody(HttpURLConnection connection) {
        try {
            java.io.InputStream stream = connection.getErrorStream();
            if (stream == null) {
                return "";
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }

            return body.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String formatApiError(int statusCode, String errorBody) {
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return "The Core API requires an active UOSA owner session. Open Owner Security and create a fresh L1 session.";
        }

        if (statusCode == HttpURLConnection.HTTP_FORBIDDEN) {
            return "The UOSA session does not have the required scope or fresh biometric authorization for this action.";
        }

        if (statusCode == 429) {
            return "The AI service is temporarily rate-limited. Please try again in a moment.";
        }

        if (statusCode >= 500) {
            return "The Amiki backend encountered a temporary internal problem.";
        }

        if (statusCode >= 400) {
            return "Amiki could not process that request. Check the request and try again.";
        }

        return "Amiki could not complete the request.";
    }

    private String sendMultipartRequest(
            String message,
            ArrayList<Uri> attachments
    ) throws Exception {
        String boundary = "----AmikiBoundary" + System.currentTimeMillis();

        URL url = new URL(ATTACHMENTS_API_URL);

        HttpURLConnection connection =
                ApiConnection.open(this, url);
        activeConnection = connection;

        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=" + boundary
        );

        try (OutputStream output = connection.getOutputStream()) {
            String lineBreak = "\r\n";

            output.write(
                    ("--" + boundary + lineBreak
                            + "Content-Disposition: form-data; name=\"message\""
                            + lineBreak + lineBreak
                            + message + lineBreak)
                            .getBytes(StandardCharsets.UTF_8)
            );

            if (activeCapability != null) {
                output.write(
                        ("--" + boundary + lineBreak
                                + "Content-Disposition: form-data; name=\"capability\""
                                + lineBreak + lineBreak
                                + activeCapability + lineBreak)
                                .getBytes(StandardCharsets.UTF_8)
                );
            }

            for (Uri uri : attachments) {
                String filename = getAttachmentFileName(uri);
                String mimeType = getAttachmentMimeType(uri);

                output.write(
                        ("--" + boundary + lineBreak
                                + "Content-Disposition: form-data; name=\"attachments\"; filename=\""
                                + filename + "\""
                                + lineBreak
                                + "Content-Type: " + mimeType
                                + lineBreak + lineBreak)
                                .getBytes(StandardCharsets.UTF_8)
                );

                copyUriToStream(uri, output);

                output.write(lineBreak.getBytes(StandardCharsets.UTF_8));
            }

            output.write(
                    ("--" + boundary + "--" + lineBreak)
                            .getBytes(StandardCharsets.UTF_8)
            );
        }

        int statusCode = connection.getResponseCode();

        if (statusCode < 200 || statusCode >= 300) {
            if (statusCode == 429) {
                throw new RateLimitException();
            }

            throw new RuntimeException(
                    formatApiError(
                            statusCode,
                            readHttpErrorBody(connection)
                    )
            );
        }

        StringBuilder responseBody = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        connection.getInputStream(),
                        StandardCharsets.UTF_8
                )
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                responseBody.append(line);
            }
        } finally {
            connection.disconnect();
        }

        return responseBody.toString();
    }

    private void showChatFailure(
            LinearLayout content,
            ScrollView scroll,
            Button send,
            TextView status,
            String message
    ) {
        requestInFlight = false;
        requestCancelled = false;
        postToUi(() -> {
            content.addView(messageCard(
                    "⚠️  Amiki",
                    message,
                    true
            ));

            scroll.post(() ->
                    scroll.fullScroll(View.FOCUS_DOWN)
            );

            status.setText("OFFLINE");
            status.setTextColor(Color.rgb(255, 100, 100));
            send.setEnabled(true);
        });
    }

    private boolean handleLocalDeviceCommand(
            String message,
            LinearLayout content,
            ScrollView scroll,
            Button send,
            TextView status
    ) {
        if (DeviceCommandParser.parse(message) == null
                || deviceActionExecutor == null) {
            return false;
        }

        status.setText("DEVICE");
        status.setTextColor(Color.rgb(190, 120, 255));
        requestInFlight = false;
        requestCancelled = false;
        send.setText("➤");
        send.setContentDescription("Send message");
        send.setEnabled(true);

        deviceActionExecutor.tryExecute(
                message,
                new DeviceActionExecutor.Callback() {
                    @Override
                    public void onResult(String result) {
                        postToUi(() -> {
                            content.addView(
                                    messageCard(
                                            "🌸  Amiki",
                                            result,
                                            true
                                    )
                            );
                            scroll.post(() ->
                                    scroll.fullScroll(View.FOCUS_DOWN)
                            );
                            status.setText("ONLINE");
                            status.setTextColor(Color.rgb(100, 230, 130));
                            send.setText("➤");
                            send.setContentDescription("Send message");
                            send.setEnabled(true);
                        });
                    }

                    @Override
                    public void onError(String error) {
                        postToUi(() -> {
                            content.addView(
                                    messageCard(
                                            "⚠️  Amiki",
                                            error,
                                            true
                                    )
                            );
                            scroll.post(() ->
                                    scroll.fullScroll(View.FOCUS_DOWN)
                            );
                            status.setText("ONLINE");
                            status.setTextColor(Color.rgb(100, 230, 130));
                            send.setText("➤");
                            send.setContentDescription("Send message");
                            send.setEnabled(true);
                        });
                    }
                }
        );

        return true;
    }

    private void sendToDirectAgent(
            String agent,
            String message,
            LinearLayout content,
            ScrollView scroll,
            Button send,
            TextView status
    ) {
        send.setText("■");
        send.setEnabled(false);
        status.setText(agent.toUpperCase() + " AGENT • RUNNING...");
        status.setTextColor(Color.rgb(190, 120, 255));

        content.addView(messageCard("YOU", message, false));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(DIRECT_AGENT_URL);
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                JSONObject request = new JSONObject();
                request.put("agent", agent);
                request.put("input", message);
                request.put("session_id", "android-direct");
                request.put("conversation_id", "android-" + agent);

                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code);

                StringBuilder raw = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) raw.append(line);
                }

                JSONObject response = new JSONObject(raw.toString());
                JSONArray results = response.optJSONArray("results");
                StringBuilder answer = new StringBuilder();

                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        Object item = results.opt(i);
                        if (item instanceof JSONObject) {
                            JSONObject result = (JSONObject) item;
                            String value = extractDirectAgentResultText(result);
                            if (!value.isEmpty()) {
                                answer.append(value).append("\n\n");
                            } else {
                                answer.append("Result ").append(i + 1)
                                        .append(" returned no readable details.\n\n");
                            }
                        } else if (item != null) {
                            answer.append(String.valueOf(item)).append("\n\n");
                        }
                    }
                }

                if (answer.length() == 0) {
                    String statusValue = response.optString("status", "unknown");
                    if ("success".equalsIgnoreCase(statusValue)
                            && response.optInt("completed_steps", 0) > 0) {
                        answer.append("The agent ran ")
                                .append(response.optInt("completed_steps", 0))
                                .append(" step(s), but returned no readable result details. ")
                                .append("The request transport succeeded; the task outcome cannot be confirmed.");
                    } else {
                        answer.append("No readable result details were returned. Agent status: ")
                                .append(statusValue)
                                .append(". Check Diagnostics for the underlying failure.");
                    }
                }

                String finalAnswer = answer.toString().trim();
                postToUi(() -> {
                    content.addView(messageCard(
                            agent.toUpperCase() + " AGENT • DIRECT",
                            finalAnswer,
                            true
                    ));
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                    status.setText(agent.toUpperCase() + " AGENT • READY");
                    status.setTextColor(Color.rgb(100, 230, 130));
                    send.setText("➤");
                    send.setEnabled(true);
                });
            } catch (Exception e) {
                postToUi(() -> {
                    content.addView(messageCard(
                            agent.toUpperCase() + " AGENT • DIRECT",
                            "The direct agent could not complete that request. Check Diagnostics for details.",
                            true
                    ));
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                    status.setText(agent.toUpperCase() + " AGENT • UNAVAILABLE");
                    status.setTextColor(Color.rgb(255, 100, 100));
                    send.setText("➤");
                    send.setEnabled(true);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private boolean isTransportOnlySuccessMessage(String message) {
        return message != null && message.trim().matches("(?i)HTTPS?\\s+[0-9]{3}\\s+SUCCESS");
    }

    private String extractDirectAgentResultText(JSONObject item) {
        JSONObject data = item.optJSONObject("data");
        if (data != null) {
            JSONObject nestedData = data.optJSONObject("data");
            String wrapperMessage = data.optString("message", "").trim();
            if (nestedData != null && (wrapperMessage.isEmpty()
                    || "null".equalsIgnoreCase(wrapperMessage)
                    || isTransportOnlySuccessMessage(wrapperMessage))) {
                String nested = extractDirectAgentResultText(nestedData);
                if (!nested.isEmpty()) return nested;
            }

            String message = data.optString("message", "").trim();
            String instruction = data.optString("instruction", "").trim();
            if (!message.isEmpty() && !"null".equalsIgnoreCase(message)
                    && !isTransportOnlySuccessMessage(message)) {
                return instruction.isEmpty() || "null".equalsIgnoreCase(instruction)
                        ? message : instruction + "\n\n" + message;
            }

            String structured = formatDirectAgentData(data);
            if (!structured.isEmpty()) return structured;
        }

        for (String key : new String[]{"message", "summary", "detail", "error", "content"}) {
            String value = item.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)
                    && !isTransportOnlySuccessMessage(value)) return value;
        }
        return "";
    }

    private String formatDirectAgentData(JSONObject data) {
        StringBuilder out = new StringBuilder();
        appendDirectAgentField(out, data, "summary", "Summary");
        appendDirectAgentField(out, data, "error", "Error");
        appendDirectAgentField(out, data, "detail", "Details");
        appendDirectAgentField(out, data, "status", "Status");
        appendDirectAgentField(out, data, "type", "Type");
        appendDirectAgentField(out, data, "path", "Path");
        appendDirectAgentField(out, data, "root", "Workspace root");
        appendDirectAgentField(out, data, "verified", "Verified");
        appendDirectAgentField(out, data, "checks", "Checks");
        appendDirectAgentField(out, data, "entries", "Entries");
        appendDirectAgentField(out, data, "files", "Files");
        appendDirectAgentField(out, data, "directories", "Directories");
        appendDirectAgentField(out, data, "findings", "Findings");
        appendDirectAgentField(out, data, "limitations", "Limitations");
        appendDirectAgentField(out, data, "branch", "Branch");
        appendDirectAgentField(out, data, "commit", "Commit");
        appendDirectAgentField(out, data, "return_code", "Exit code");
        appendDirectAgentField(out, data, "stdout", "Output");
        appendDirectAgentField(out, data, "stderr", "Errors");
        appendDirectAgentField(out, data, "content", "Content");
        return out.toString().trim();
    }

    private void appendDirectAgentField(
            StringBuilder out, JSONObject data, String key, String label
    ) {
        if (!data.has(key) || data.isNull(key)) return;
        Object value = data.opt(key);
        if (value == null || value == JSONObject.NULL) return;
        String rendered = value instanceof JSONObject || value instanceof JSONArray
                ? value.toString() : String.valueOf(value);
        if (rendered.trim().isEmpty() || "null".equalsIgnoreCase(rendered)) return;
        if (out.length() > 0) out.append("\n");
        out.append(label).append(": ").append(rendered);
    }

    private void sendToAmiki(
            String message,
            LinearLayout content,
            ScrollView scroll,
            Button send,
            TextView status,
            boolean allowRecovery
    ) {
        requestInFlight = true;
        requestCancelled = false;
        send.setText("■");
        send.setContentDescription("Cancel request");
        send.setEnabled(true);
        status.setText("THINKING...");
        status.setTextColor(Color.rgb(190, 120, 255));

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                String responseBodyText;

                if (!selectedAttachments.isEmpty()) {
                    responseBodyText = sendMultipartRequest(
                            message,
                            new ArrayList<>(selectedAttachments)
                    );
                } else {
                    URL url = new URL(API_URL);
                connection = ApiConnection.open(this, url);
                activeConnection = connection;

                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );

                JSONObject request = new JSONObject();
                request.put("message", message);

                if (activeCapability != null) {
                    request.put("capability", activeCapability);
                }

                byte[] body = request.toString()
                        .getBytes(StandardCharsets.UTF_8);

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }

                int statusCode = connection.getResponseCode();

                if (statusCode < 200 || statusCode >= 300) {
                    if (statusCode == 429) {
                        throw new RateLimitException();
                    }

                    throw new RuntimeException(
                            formatApiError(
                                    statusCode,
                                    readHttpErrorBody(connection)
                            )
                    );
                }

                StringBuilder responseBody = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                connection.getInputStream(),
                                StandardCharsets.UTF_8
                        ))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        responseBody.append(line);
                    }
                }

                responseBodyText = responseBody.toString();
                }

                JSONObject response = new JSONObject(responseBodyText);

                String responseText = response.getString("response");

                postToUi(() -> {
                    content.addView(messageCard(
                            "🌸  Amiki",
                            responseText,
                            true
                    ));

                    scroll.post(() ->
                            scroll.fullScroll(View.FOCUS_DOWN)
                    );

                    status.setText("ONLINE");
                    status.setTextColor(Color.rgb(100, 230, 130));

                    requestInFlight = false;
                    send.setText("➤");
                    send.setContentDescription("Send message");
                    send.setEnabled(true);
                    speakReply(responseText);
                    selectedAttachments.clear();
                    if (attachmentStatus != null) {
                        attachmentStatus.setVisibility(View.GONE);
                    }
                });

            } catch (java.io.IOException e) {
                if (requestCancelled) {
                    postToUi(() -> {
                        status.setText("ONLINE");
                        status.setTextColor(Color.rgb(100, 230, 130));
                        send.setText("➤");
                        send.setContentDescription("Send message");
                        send.setEnabled(true);
                    });
                    return;
                }

                if (allowRecovery) {
                    recoverAstra(
                            message,
                            content,
                            scroll,
                            send,
                            status,
                            e
                    );
                } else {
                    showChatFailure(
                            content,
                            scroll,
                            send,
                            status,
                            "I couldn't reach the Amiki backend. Check that Amiki is online and try again."
                    );
                }

            } catch (RateLimitException e) {
                postToUi(() -> {
                    content.addView(messageCard(
                            "⚠️  Amiki",
                            "I'm temporarily rate-limited by the AI service. Please try again in a moment.",
                            true
                    ));

                    scroll.post(() ->
                            scroll.fullScroll(View.FOCUS_DOWN)
                    );

                    status.setText("ONLINE");
                    status.setTextColor(Color.rgb(100, 230, 130));
                    send.setEnabled(true);
                });

            } catch (Exception e) {
                showChatFailure(
                        content,
                        scroll,
                        send,
                        status,
                        "I couldn't complete that request. Check Diagnostics for details."
                );

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                activeConnection = null;
                requestInFlight = false;
            }
        }).start();
    }

    private void recoverAstra(
            String message,
            LinearLayout content,
            ScrollView scroll,
            Button send,
            TextView status,
            Exception originalError
    ) {
        postToUi(() -> {
            status.setText("RECOVERING...");
            status.setTextColor(Color.rgb(255, 190, 80));
            send.setEnabled(false);
        });

        boolean recoveryStarted = startAstraThroughTermux(status, send);

        new Thread(() -> {
            boolean online = false;

            if (!recoveryStarted) {
                return;
            }

            for (int attempt = 0; attempt < 15; attempt++) {
                HttpURLConnection connection = null;

                try {
                    URL url = new URL(HEALTH_URL);
                    connection = ApiConnection.open(this, url);
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(1000);
                    connection.setReadTimeout(1000);

                    if (connection.getResponseCode()
                            == HttpURLConnection.HTTP_OK) {
                        online = true;
                        break;
                    }

                } catch (Exception ignored) {
                    // Astra may still be restarting.
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            final boolean recovered = online;

            postToUi(() -> {
                if (recovered) {
                    status.setText("ONLINE");
                    status.setTextColor(Color.rgb(100, 230, 130));
                } else {
                    status.setText("OFFLINE");
                    status.setTextColor(Color.rgb(255, 100, 100));

                    content.addView(messageCard(
                            "⚠️  Amiki",
                            "I couldn't reconnect to Amiki. Open Diagnostics for details.",
                            true
                    ));

                    scroll.post(() ->
                            scroll.fullScroll(View.FOCUS_DOWN)
                    );

                    send.setEnabled(false);
                }
            });

            if (recovered) {
                sendToAmiki(
                        message,
                        content,
                        scroll,
                        send,
                        status,
                        false
                );
            }
        }).start();
    }

    private void loadMemory(LinearLayout memoryList) {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(MEMORY_URL);
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);

                int statusCode = connection.getResponseCode();

                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new RuntimeException(
                            "Memory request failed: HTTP " + statusCode
                    );
                }

                StringBuilder responseBody = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                connection.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseBody.append(line);
                    }
                }

                JSONObject response = new JSONObject(responseBody.toString());
                JSONArray memories = response.getJSONArray("memories");

                postToUi(() -> {
                    memoryList.removeAllViews();

                    if (memories.length() == 0) {
                        TextView empty = text(
                                "No memories stored yet.",
                                15,
                                MUTED
                        );
                        memoryList.addView(empty);
                        return;
                    }

                    for (int i = 0; i < memories.length(); i++) {
                        try {
                            JSONObject memory = memories.getJSONObject(i);

                            LinearLayout card = new LinearLayout(this);
                            card.setOrientation(LinearLayout.VERTICAL);
                            card.setPadding(
                                    dp(16), dp(14), dp(16), dp(14)
                            );
                            card.setBackground(roundRect(CARD, 16));

                            TextView category = text(
                                    memory.getString("category").toUpperCase(),
                                    12,
                                    MUTED
                            );

                            TextView key = text(
                                    memory.getString("key"),
                                    16,
                                    TEXT
                            );
                            key.setTypeface(null, 1);

                            TextView value = text(
                                    memory.getString("value"),
                                    14,
                                    TEXT
                            );

                            card.addView(category);
                            card.addView(key);
                            card.addView(value);

                            String memoryCategory =
                                    memory.getString("category");
                            String memoryKey =
                                    memory.getString("key");

                            Button delete = new Button(this);
                            delete.setText("DELETE");
                            delete.setTextSize(12);
                            delete.setTextColor(TEXT);
                            delete.setOnClickListener(v ->
                                    confirmDeleteMemory(
                                            memoryCategory,
                                            memoryKey,
                                            memoryList
                                    )
                            );

                            LinearLayout.LayoutParams deleteParams =
                                    new LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    );
                            deleteParams.topMargin = dp(8);
                            card.addView(delete, deleteParams);


                            LinearLayout.LayoutParams params =
                                    new LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    );
                            params.setMargins(0, 0, 0, dp(10));

                            memoryList.addView(card, params);
                        } catch (Exception ignored) {
                            // Skip malformed memory entries.
                        }
                    }
                });

            } catch (Exception e) {
                postToUi(() -> {
                    memoryList.removeAllViews();

                    TextView error = text(
                            "Unable to load memories.",
                            15,
                            MUTED
                    );
                    memoryList.addView(error);
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void confirmDeleteMemory(
            String category,
            String key,
            LinearLayout memoryList
    ) {
        String message = new StringBuilder()
                .append("This will permanently remove this memory from Amiki's active memory.")
                .append("\n\n")
                .append("Memory: ")
                .append(key)
                .toString();

        new AlertDialog.Builder(this)
                .setTitle("⚠️ Delete Memory?")
                .setMessage(message)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton(
                        "DELETE",
                        (dialog, which) ->
                                deleteMemory(category, key, memoryList)
                )
                .show();
    }

    private void deleteMemory(
            String category,
            String key,
            LinearLayout memoryList
    ) {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                String encodedCategory =
                        URLEncoder.encode(
                                category,
                                StandardCharsets.UTF_8.name()
                        ).replace("+", "%20");

                String encodedKey =
                        URLEncoder.encode(
                                key,
                                StandardCharsets.UTF_8.name()
                        ).replace("+", "%20");

                String endpoint = new StringBuilder()
                        .append(MEMORY_URL)
                        .append("/")
                        .append(encodedCategory)
                        .append("/")
                        .append(encodedKey)
                        .toString();

                URL url = new URL(endpoint);

                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("DELETE");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);

                int statusCode = connection.getResponseCode();

                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new RuntimeException(
                            "Delete memory failed: HTTP " + statusCode
                    );
                }

                postToUi(() -> loadMemory(memoryList));

            } catch (Exception e) {
                postToUi(() ->
                        Toast.makeText(
                                this,
                                "Unable to delete memory.",
                                Toast.LENGTH_SHORT
                        ).show()
                );
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout messageCard(
            String sender,
            String message,
            boolean amiki
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(roundRect(amiki ? CARD : PANEL, 18));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        amiki ? ViewGroup.LayoutParams.MATCH_PARENT : dp(292),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        params.gravity = amiki ? Gravity.START : Gravity.END;
        params.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(params);

        TextView name = text(
                sender,
                11,
                amiki ? PURPLE : Color.rgb(190, 120, 255)
        );
        name.setTypeface(null, 1);
        name.setLetterSpacing(0.04f);

        TextView body = text(message, 15, TEXT);
        body.setLineSpacing(0f, 1.12f);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(6), 0, 0);

        card.addView(name);
        card.addView(body);

        return card;
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        requestCancelled = true;
        requestInFlight = false;

        HttpURLConnection connection = activeConnection;
        activeConnection = null;
        if (connection != null) {
            connection.disconnect();
        }

        mainHandler.removeCallbacksAndMessages(null);

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (navigationDrawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }

        if (currentScreen != Screen.CHAT) {
            navigateTo(Screen.CHAT);
            return;
        }

        super.onBackPressed();
    }
}
