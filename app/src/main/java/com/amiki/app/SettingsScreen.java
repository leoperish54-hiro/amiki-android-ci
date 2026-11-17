package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * @deprecated This settings screen belongs to the retained MainActivity prototype.
 * The active launcher uses MobileShellActivity's current Settings screen.
 */
@Deprecated
public class SettingsScreen {

    public interface SettingsCallbacks {
        void onSettingChanged(String key, boolean enabled);
        void onResetSettings();
        void onBack();
        void onMenu();
        void onOpenDatabase();
        void onOpenDiagnostics();
    }

    public static final String AUTO_RECOVERY_KEY = "auto_recovery";
    public static final String SYSTEM_AWARENESS_KEY = "system_awareness";
    public static final String KEEP_SCREEN_AWAKE_KEY = "keep_screen_awake";
    public static final String TTS_ENABLED_KEY = "tts_enabled";

    private static final int BG = Color.rgb(5, 7, 18);
    private static final int PANEL = Color.rgb(12, 15, 30);
    private static final int CARD = Color.rgb(17, 21, 40);
    private static final int PURPLE = Color.rgb(139, 70, 255);
    private static final int TEXT = Color.rgb(245, 245, 250);
    private static final int MUTED = Color.rgb(165, 165, 185);

    private final Activity activity;
    private final SettingsCallbacks callbacks;

    private Switch autoRecoverySwitch;
    private Switch systemAwarenessSwitch;
    private Switch keepScreenAwakeSwitch;
    private Switch ttsEnabledSwitch;

    public SettingsScreen(Activity activity, SettingsCallbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
    }

    public LinearLayout build(boolean autoRecovery, boolean systemAwareness,
                              boolean keepScreenAwake, boolean ttsEnabled) {
        LinearLayout screen = new LinearLayout(activity);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        screen.addView(buildHeader());

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(30));

        content.addView(text("Amiki connection, behavior, and runtime preferences", 14, MUTED));

        addSectionTitle(content, "CONNECTION");
        addInfoCard(content, "🌐 Amiki Backend",
                "Local Termux backend\n127.0.0.1:8000\n\nThe Android client uses the local Amiki service. The endpoint is intentionally read-only here so a bad setting cannot break the core connection.");

        addSectionTitle(content, "BEHAVIOR");
        autoRecoverySwitch = addToggle(content, "♻ Auto-recover Amiki",
                "When a chat request loses the local connection, Amiki attempts to restart Amiki through Termux and retries once the backend returns.",
                autoRecovery, AUTO_RECOVERY_KEY);

        addSectionTitle(content, "DASHBOARD");
        systemAwarenessSwitch = addToggle(content, "🧠 Show System Awareness",
                "Show runtime, capability, provider, model, and request information on the Dashboard.",
                systemAwareness, SYSTEM_AWARENESS_KEY);

        keepScreenAwakeSwitch = addToggle(content, "💡 Keep Screen Awake",
                "Prevent the display from sleeping while Amiki is open. This affects the current app session immediately.",
                keepScreenAwake, KEEP_SCREEN_AWAKE_KEY);

        ttsEnabledSwitch = addToggle(content, "🔊 Read Amiki Replies Aloud",
                "Use the device's built-in text-to-speech engine for Amiki responses.",
                ttsEnabled, TTS_ENABLED_KEY);

        addSectionTitle(content, "WEB");
        addActionCard(content, "🌐 Live Browser",
                "Open a limited live web browser without leaving Amiki. Web pages run in a separate Activity so browser failures cannot take down the chat screen.",
                "OPEN BROWSER", v -> activity.startActivity(new Intent(activity, BrowserActivity.class)));

        addSectionTitle(content, "DIAGNOSTICS");
        addActionCard(content, "🔎 Runtime Diagnostics",
                "Read-only checks for Amiki's configuration, capabilities, memory, attachments, and model router.",
                "OPEN DIAGNOSTICS", v -> callbacks.onOpenDiagnostics());

        addSectionTitle(content, "DATA");
        addActionCard(content, "🗄 Database",
                "Open Amiki's database controls for persistent memory and current-session storage.",
                "OPEN DATABASE", v -> callbacks.onOpenDatabase());

        addSectionTitle(content, "RESET");
        addActionCard(content, "⚠ Reset Settings",
                "Restore Amiki's local Settings preferences to their safe defaults. This does not delete memories or chat data.",
                "RESET SETTINGS", v -> confirmResetSettings());

        addSectionTitle(content, "ABOUT");
        addInfoCard(content, "🌸 Amiki",
                "Android client\nSettings are stored locally on this device and survive app restarts.");

        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return screen;
    }

    public void refreshSettings(boolean autoRecovery, boolean systemAwareness,
                                boolean keepScreenAwake, boolean ttsEnabled) {
        setSwitchState(autoRecoverySwitch, autoRecovery);
        setSwitchState(systemAwarenessSwitch, systemAwareness);
        setSwitchState(keepScreenAwakeSwitch, keepScreenAwake);
        setSwitchState(ttsEnabledSwitch, ttsEnabled);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.setBackgroundColor(PANEL);

        Button back = new Button(activity);
        back.setText("‹ BACK");
        back.setTextColor(TEXT);
        back.setTextSize(13);
        back.setAllCaps(false);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> callbacks.onBack());

        TextView title = text("SETTINGS", 20, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(back, new LinearLayout.LayoutParams(dp(80), dp(48)));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView menu = text("☰", 24, TEXT);
        menu.setGravity(Gravity.CENTER);
        menu.setOnClickListener(v -> callbacks.onMenu());
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return header;
    }

    private Switch addToggle(LinearLayout content, String title, String description,
                             boolean checked, String key) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(12), dp(14));
        card.setBackground(roundRect(CARD, 16));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView heading = text(title, 16, TEXT);
        heading.setTypeface(null, Typeface.BOLD);

        Switch toggle = new Switch(activity);
        toggle.setChecked(checked);
        toggle.setTextColor(TEXT);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                callbacks.onSettingChanged(key, isChecked));

        top.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = text(description, 13, MUTED);
        body.setPadding(0, dp(8), 0, 0);
        card.addView(top);
        card.addView(body);
        addCardWithMargin(content, card);
        return toggle;
    }

    private void addInfoCard(LinearLayout content, String heading, String body) {
        LinearLayout card = baseCard();
        TextView title = text(heading, 16, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        TextView message = text(body, 13, MUTED);
        message.setPadding(0, dp(9), 0, 0);
        card.addView(title);
        card.addView(message);
        addCardWithMargin(content, card);
    }

    private void addActionCard(LinearLayout content, String heading, String body,
                               String buttonLabel, View.OnClickListener listener) {
        LinearLayout card = baseCard();
        TextView title = text(heading, 16, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        TextView message = text(body, 13, MUTED);
        message.setPadding(0, dp(9), 0, 0);

        Button action = new Button(activity);
        action.setText(buttonLabel);
        action.setTextColor(TEXT);
        action.setTextSize(12);
        action.setAllCaps(false);
        action.setBackground(roundRect(PURPLE, 12));
        action.setOnClickListener(listener);

        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        actionParams.setMargins(0, dp(14), 0, 0);
        card.addView(title);
        card.addView(message);
        card.addView(action, actionParams);
        addCardWithMargin(content, card);
    }

    private LinearLayout baseCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundRect(CARD, 16));
        return card;
    }

    private void addSectionTitle(LinearLayout content, String title) {
        TextView section = text(title, 11, Color.rgb(190, 120, 255));
        section.setTypeface(null, Typeface.BOLD);
        section.setPadding(dp(2), dp(18), 0, dp(8));
        content.addView(section);
    }

    private void addCardWithMargin(LinearLayout content, View card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        content.addView(card, params);
    }

    private void confirmResetSettings() {
        new AlertDialog.Builder(activity)
                .setTitle("⚠ Reset Settings?")
                .setMessage("Restore Auto-recover Amiki, System Awareness, Keep Screen Awake, and TTS to their safe defaults.\n\nMemories and conversation data will not be touched.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("RESET", (dialog, which) -> callbacks.onResetSettings())
                .show();
    }

    private void setSwitchState(Switch toggle, boolean checked) {
        if (toggle == null) return;
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String key;
            if (buttonView == autoRecoverySwitch) key = AUTO_RECOVERY_KEY;
            else if (buttonView == systemAwarenessSwitch) key = SYSTEM_AWARENESS_KEY;
            else if (buttonView == keepScreenAwakeSwitch) key = KEEP_SCREEN_AWAKE_KEY;
            else key = TTS_ENABLED_KEY;
            callbacks.onSettingChanged(key, isChecked);
        });
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    public interface MainActivitySettingsBridge {
        void onMenu();
    }
}
