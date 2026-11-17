package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.speech.tts.UtteranceProgressListener;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Space;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.provider.ContactsContract;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Calendar;
import java.util.UUID;

public class MobileShellActivity extends Activity {
    // Active palette is resolved from the saved appearance preference before building views.
    private int BG = 0xFF0E1224;
    private int PANEL = 0xFF171C33;
    private int CARD = 0xFF1D2340;
    private int CARD_2 = 0xFF242B4A;
    private int PURPLE = 0xFF8B7CFF;
    private int CYAN = 0xFF4DE1C1;
    private int TEXT = 0xFFE8EAF6;
    private int MUTED = 0xFFADB4D0;
    private static final String API = ApiEndpoints.CHAT;
    private static final String DIRECT_AGENT = ApiEndpoints.DIRECT_AGENT;
    private static final String HEALTH = ApiEndpoints.HEALTH;
    private static final String MEMORY = ApiEndpoints.MEMORY;
    private static final String CURRENT_SESSION_MEMORY = MEMORY + "/current-session";
    private static final String PERSISTENT_MEMORY = MEMORY + "/persistent";
    private static final String CAPABILITIES = ApiEndpoints.CAPABILITIES;
    private static final String DATABASE = ApiEndpoints.DATABASE_STATUS;
    private static final String DIAGNOSTICS = ApiEndpoints.DIAGNOSTICS;
    private static final String SYSTEM_STATE = ApiEndpoints.SYSTEM_STATE;
    private static final String ACADEMIC = ApiEndpoints.ACADEMIC;
    private static final String CONVERSATION_HISTORY_PREFERENCES = "amiki_conversation_history";
    private static final String CONVERSATION_INDEX_KEY = "conversations_v2_index";
    private static final String ACTIVE_CONVERSATION_ID_KEY = "active_conversation_id_v2";
    private static final String CONVERSATION_TRANSCRIPT_PREFIX = "conversation_v2_";


    private static final class ConversationInfo {
        final String id;
        String title;
        long updatedAtMillis;

        ConversationInfo(String id, String title, long updatedAtMillis) {
            this.id = id;
            this.title = title;
            this.updatedAtMillis = updatedAtMillis;
        }
    }
    private static final int MAX_CACHED_CONVERSATIONS = 20;
    private static final int MAX_CACHED_CONVERSATION_MESSAGES = 80;
    private static final int MAX_CACHED_CONVERSATION_MESSAGE_CHARS = 8000;
    private static final int MAX_PERSISTED_AGENT_MESSAGES = 80;
    private static final int MAX_PERSISTED_MESSAGE_CHARS = 24000;
    // Keep Chat usable on low-memory devices even when the saved transcript is large.
    private static final int MAX_RENDERED_CHAT_MESSAGES = 30;
    private static final int MAX_RENDERED_CHAT_MESSAGE_CHARS = 8000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout content;
    private LinearLayout bottomBar;
    private LinearLayout drawer;
    private View scrim;
    private TextView pageTitle;
    private int currentAccent = Color.rgb(139, 124, 255);
    private TextView online;
    private int active = 0;
    private static final int PICK_ATTACHMENT = 7001;
    private static final int VOICE_INPUT = 7002;
    private static final int LOCATION_PERMISSION = 7003;
    private static final int PICK_WHATSAPP_CONTACT = 7004;
    private String pendingWhatsAppMessage;
    private DeviceBridge.Callback pendingWhatsAppCallback;
    private static final int MAX_ATTACHMENTS = 5;
    private static final long MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 40L * 1024L * 1024L;
    private static final String[] SUPPORTED_ATTACHMENT_MIME_TYPES = {
        "image/jpeg",
        "image/png",
        "image/webp",
        "text/plain",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    };
    private TextView attachmentBadge;
    private TextView attachmentWorkspaceStatus;
    private final ArrayList<String[]> chatHistory = new ArrayList<>();
    private final ArrayList<ConversationInfo> conversationIndex = new ArrayList<>();
    private String activeConversationId;
    private boolean conversationHistoryLoading;
    private boolean conversationTranscriptCacheWritable = true;
    private boolean conversationRemoteHydrationAttempted;
    private final ArrayList<Uri> selectedAttachments = new ArrayList<>();
    private final Map<String, ArrayList<String[]>> directAgentHistory = new HashMap<>();
    private final Map<String, String> directAgentDrafts = new HashMap<>();
    private String lastChatError = "No failed chat request recorded.";
    private int lastChatHttpCode = 0;
    private long lastChatTime = 0L;
    private android.content.SharedPreferences settingsPrefs;
    private TextToSpeech amikiTts;
    private boolean ttsReady = false;
    private boolean ttsSpeaking = false;
    private MediaPlayer elevenLabsPlayer;
    private File elevenLabsAudioFile;
    private volatile String voiceRequestText = null;
    private String voicePlaybackText = "";
    private boolean elevenLabsSpeaking = false;
    private boolean chatRequestInFlight = false;
    private boolean voiceInputInFlight = false;
    private boolean backendHealthKnown = false;
    private boolean backendOnline = false;
    private String chatDraft = "";
    private DeviceBridge deviceBridge;
    private DeviceActionExecutor deviceActionExecutor;

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private TextView t(String s, float size, int color) {
        TextView v = new TextView(this);
        float scale = settingsPrefs == null ? 1.0f : settingsPrefs.getFloat("font_scale", 1.0f);
        v.setText(s);
        v.setTextSize(size * scale);
        v.setTextColor(color);
        return v;
    }
    private GradientBox bg(int color, float radius) { return new GradientBox(color, dp(radius)); }

    private void applyAppearanceTheme() {
        String mode = settingsPrefs.getString(AmikiTheme.PREF_KEY, AmikiTheme.MODE_DARK);
        AmikiTheme.Palette palette = AmikiTheme.resolve(this, mode);
        BG = palette.background;
        PANEL = palette.panel;
        CARD = palette.card;
        CARD_2 = palette.cardRaised;
        PURPLE = palette.primary;
        CYAN = palette.accent;
        TEXT = palette.text;
        MUTED = palette.muted;
    }

    private String resolveOrbState() {
        return AmikiOrbState.resolve(voiceInputInFlight, chatRequestInFlight, ttsSpeaking,
                backendHealthKnown, backendOnline);
    }

    private String orbStateLabel() {
        String state = resolveOrbState();
        if (AmikiOrbView.STATE_LISTENING.equals(state)) return "Listening";
        if (AmikiOrbView.STATE_THINKING.equals(state)) return "Thinking";
        if (AmikiOrbView.STATE_SPEAKING.equals(state)) return "Speaking";
        if (AmikiOrbView.STATE_OFFLINE.equals(state)) return "Backend offline";
        return "Ready when you are";
    }

    private static class GradientBox extends android.graphics.drawable.GradientDrawable {
        GradientBox(int color, int radius) { setColor(color); setCornerRadius(radius); }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        settingsPrefs = getSharedPreferences("amiki_settings", MODE_PRIVATE);
        applyAppearanceTheme();
        restoreConversationHistory();
        if(settingsPrefs.getBoolean("keep_screen_awake", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        deviceBridge = new DeviceBridge(this);
        deviceActionExecutor = new DeviceActionExecutor(this);
        amikiTts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if(ttsReady){
                amikiTts.setLanguage(Locale.getDefault());
                amikiTts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    @Override public void onStart(String utteranceId){ handler.post(() -> { ttsSpeaking = true; if(active == 0) showChat(); }); }
                    @Override public void onDone(String utteranceId){ handler.post(() -> { ttsSpeaking = false; if(active == 0) showChat(); }); }
                    @Override public void onError(String utteranceId){ handler.post(() -> { ttsSpeaking = false; if(active == 0) showChat(); }); }
                });
            }
        });
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            boolean lightSurface = (Color.red(BG) * 299 + Color.green(BG) * 587 + Color.blue(BG) * 114) / 1000 > 150;
            // Keep system bar icons readable against both built-in palettes.
            android.view.View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                flags = lightSurface
                        ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : flags & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            } else {
                flags = lightSurface
                        ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
        buildShell();
        persistConversationHistory();
        showDashboard();
        checkHealth();
    }

    private void buildShell() {
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        LinearLayout vertical = new LinearLayout(this); vertical.setOrientation(LinearLayout.VERTICAL); vertical.setBackgroundColor(BG);
        vertical.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(58)));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content);
        vertical.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomBar = buildBottomBar(); vertical.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(68)));
        root.addView(vertical, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(this); scrim.setBackgroundColor(Color.argb(150, 0, 0, 0)); scrim.setVisibility(View.GONE); scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        drawer = buildDrawer(); drawer.setVisibility(View.GONE);
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(this.dp(300), -1); dp.gravity = Gravity.START; root.addView(drawer, dp);
        setContentView(root);
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(16), dp(6), dp(12), dp(6)); bar.setBackgroundColor(PANEL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_amiki_mark);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setContentDescription("Amiki emblem");
        LinearLayout.LayoutParams markParams = lp(dp(36), dp(36));
        markParams.setMargins(0, 0, dp(8), 0);
        bar.addView(mark, markParams);
        TextView logo = t("A M I K I", 18, TEXT);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setLetterSpacing(.18f);
        TextView menu = t("☰", 24, TEXT);
        menu.setGravity(Gravity.CENTER);
        menu.setOnClickListener(v -> openDrawer());
        online = t("● ONLINE", 10, Color.rgb(100,230,130));
        online.setGravity(Gravity.CENTER);
        bar.addView(logo, new LinearLayout.LayoutParams(0, -1, 1));
        bar.addView(online, new LinearLayout.LayoutParams(dp(78), -1));
        bar.addView(menu, new LinearLayout.LayoutParams(dp(44), -1));
        return bar;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER); bar.setPadding(dp(5), dp(5), dp(5), dp(5)); bar.setBackgroundColor(PANEL);
        String[] labels = {"◉\nChat", "✿\nMemory", "◇\nStudy", "▣\nProjects", "⋯\nMore"};
        for (int i=0;i<labels.length;i++) { final int index=i; TextView item=t(labels[i], 11, MUTED); item.setGravity(Gravity.CENTER); item.setLineSpacing(0, .9f); item.setOnClickListener(v -> nav(index)); bar.addView(item,new LinearLayout.LayoutParams(0,-1,1)); }
        return bar;
    }

    private void nav(int index) {
        active=index; if(index==0) showChat(); else if(index==1) showMemory(); else if(index==2) showStudy(); else if(index==3) showProjects(); else openDrawer();
    }

    private void clear(String title) { content.removeAllViews(); currentAccent = screenAccent(title); pageTitle = t(title, 20, TEXT); pageTitle.setTypeface(null,Typeface.BOLD); content.addView(pageTitle, lp( -1, dp(48))); pageTitle.setGravity(Gravity.CENTER_VERTICAL); }
    private int screenAccent(String title) {
        String value = title.toLowerCase(Locale.ROOT);
        if (value.contains("dashboard")) return Color.rgb(66, 153, 225);      // dashboard: azure
        if (value.contains("memory")) return Color.rgb(45, 190, 170);         // memory: jade
        if (value.contains("analysis")) return Color.rgb(111, 126, 255);      // analysis: periwinkle
        if (value.contains("activity")) return Color.rgb(255, 112, 112);      // activity: coral
        if (value.contains("database")) return Color.rgb(56, 189, 248);       // database: cyan
        if (value.contains("engineering")) return Color.rgb(217, 70, 239);    // engineering: fuchsia
        if (value.contains("project")) return Color.rgb(167, 110, 255);       // projects: violet
        if (value.contains("setting")) return Color.rgb(245, 158, 11);        // settings: amber
        if (value.contains("profile")) return Color.rgb(255, 205, 86);        // profile: gold
        if (value.contains("device")) return Color.rgb(52, 211, 153);         // device: emerald
        if (value.contains("web")) return Color.rgb(14, 165, 233);            // web: sky
        if (value.contains("tool")) return Color.rgb(129, 140, 248);           // tools: indigo
        if (value.contains("university")) return Color.rgb(20, 184, 166);     // university: teal
        if (value.contains("study")) return Color.rgb(163, 230, 53);          // study: lime
        return Color.rgb(192, 132, 252);                                      // fallback: orchid
    }
    private void accentCard(LinearLayout view, int color) {
        GradientDrawable shape = bg(CARD, 16);
        shape.setStroke(dp(1), color);
        view.setBackground(shape);
    }
    private LinearLayout lpView() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18),dp(14),dp(18),dp(18)); return v; }
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}

    private void showDashboard() {
        active = 0;
        currentAccent = screenAccent("Dashboard");
        content.removeAllViews();

        LinearLayout body = lpView();
        TextView greeting = t(greetingText(), 14, MUTED);
        body.addView(greeting, lp(-1, dp(30)));

        TextView welcome = t("Welcome back,", 29, TEXT);
        welcome.setTypeface(null, Typeface.BOLD);
        body.addView(welcome, lp(-1, dp(40)));
        TextView owner = t("Chimz.", 31, PURPLE);
        owner.setTypeface(null, Typeface.BOLD);
        body.addView(owner, lp(-1, dp(42)));

        TextView promise = t("Your AI companion. Your memory. Your edge.", 15, PURPLE);
        promise.setTypeface(null, Typeface.BOLD);
        promise.setLineSpacing(dp(1), 1.0f);
        LinearLayout.LayoutParams promiseParams = lp(-1, -2);
        promiseParams.setMargins(0, 0, 0, dp(14));
        body.addView(promise, promiseParams);

        // Keep the Midnight Scholar portrait as a first-class part of the new dashboard.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable heroBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1A2144, 0xFF10162F, 0xFF0B1027});
        heroBackground.setCornerRadius(dp(22));
        heroBackground.setStroke(dp(1), 0x665F50C8);
        hero.setBackground(heroBackground);

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setPadding(0, 0, dp(8), 0);

        TextView scholar = t("MIDNIGHT SCHOLAR", 11, CYAN);
        scholar.setTypeface(null, Typeface.BOLD);
        scholar.setLetterSpacing(.10f);
        identity.addView(scholar, lp(-1, dp(26)));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        AmikiOrbView orb = new AmikiOrbView(this);
        orb.setTag("amiki_dashboard_orb");
        orb.setPalette(PURPLE, CYAN, TEXT, MUTED);
        orb.setState(resolveOrbState());
        statusRow.addView(orb, lp(dp(54), dp(54)));

        TextView orbStatus = t(orbStateLabel(), 12, MUTED);
        orbStatus.setTag("amiki_orb_status");
        orbStatus.setGravity(Gravity.CENTER_VERTICAL);
        orbStatus.setPadding(dp(8), 0, 0, 0);
        orbStatus.setContentDescription("Amiki status: " + orbStateLabel());
        statusRow.addView(orbStatus, new LinearLayout.LayoutParams(0, dp(50), 1));
        identity.addView(statusRow, lp(-1, dp(58)));

        TextView motto = t("Learn, Adapt, Adjust\nand Conquer", 12, TEXT);
        motto.setGravity(Gravity.CENTER_VERTICAL);
        identity.addView(motto, lp(-1, dp(42)));
        hero.addView(identity, new LinearLayout.LayoutParams(0, dp(148), 1));

        FrameLayout portraitFrame = new FrameLayout(this);
        portraitFrame.setBackground(bg(CARD_2, 17));
        portraitFrame.setClipToOutline(true);
        ImageView portrait = new ImageView(this);
        portrait.setImageResource(R.drawable.amiki_anime);
        portrait.setScaleType(ImageView.ScaleType.CENTER_CROP);
        portrait.setContentDescription("Amiki, Midnight Scholar gothic angel portrait");
        portraitFrame.addView(portrait, new FrameLayout.LayoutParams(-1, -1));
        AmikiAtmosphereView atmosphere = new AmikiAtmosphereView(this);
        atmosphere.setTag("amiki_dashboard_atmosphere");
        atmosphere.setState(resolveOrbState());
        portraitFrame.addView(atmosphere, new FrameLayout.LayoutParams(-1, -1));
        hero.addView(portraitFrame, lp(dp(112), dp(148)));
        body.addView(hero, lp(-1, -2));

        body.addView(sectionTitle("Memory Snapshot"));
        LinearLayout memoryCard = dashboardCard(CYAN);
        body.addView(memoryCard, lp(-1, -2));

        body.addView(sectionTitle("Quick Actions"));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        addDashboardActionRow(actions, "⌘", "Code Runner", "Code Runner",
                "▤", "Notes", "Notes");
        addDashboardActionRow(actions, "▦", "Calculator", "Calculator",
                "◎", "Web Search", "Web Search");
        body.addView(actions, lp(-1, -2));

        body.addView(sectionTitle("Today's Overview"));
        TextView overviewHint = t("Live snapshot from Amiki's backend", 11, MUTED);
        body.addView(overviewHint, lp(-1, dp(22)));
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        addStat(stats, "…", "Backend");
        addStat(stats, "…", "Memories");
        addStat(stats, "…", "Capabilities");
        body.addView(stats, lp(-1, dp(94)));

        loadDashboardLive(memoryCard, stats);
        content.addView(body, lp(-1, -2));
    }

    private LinearLayout dashboardCard(int strokeColor) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{CARD, PANEL});
        shape.setCornerRadius(dp(18));
        shape.setStroke(dp(1), (strokeColor & 0x00FFFFFF) | 0x99000000);
        panel.setBackground(shape);
        return panel;
    }

    private void addDashboardActionRow(LinearLayout parent,
                                      String iconA, String labelA, String actionA,
                                      String iconB, String labelB, String actionB) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout first = dashboardActionTile(iconA, labelA, actionA);
        LinearLayout second = dashboardActionTile(iconB, labelB, actionB);
        row.addView(first, new LinearLayout.LayoutParams(0, dp(62), 1));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(62), 1);
        secondParams.setMargins(dp(8), 0, 0, 0);
        row.addView(second, secondParams);
        LinearLayout.LayoutParams rowParams = lp(-1, dp(69));
        parent.addView(row, rowParams);
    }

    private LinearLayout dashboardActionTile(String glyph, String label, String action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(12), dp(8), dp(8), dp(8));
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{CARD, 0xFF151B35});
        shape.setCornerRadius(dp(15));
        shape.setStroke(dp(1), 0x665F50C8);
        tile.setBackground(shape);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(label);
        TextView icon = t(glyph, 22, PURPLE);
        icon.setGravity(Gravity.CENTER);
        tile.addView(icon, lp(dp(34), dp(42)));
        TextView title = t(label, 13, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(7), 0, 0, 0);
        tile.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        tile.setOnClickListener(v -> toolAction(action));
        return tile;
    }

    private void renderDashboardMemory(LinearLayout memoryCard, JSONArray memories) {
        memoryCard.removeAllViews();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = t("✿  LIVE MEMORY", 13, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(28), 1));
        TextView viewAll = t("VIEW ALL  ›", 10, PURPLE);
        viewAll.setTypeface(null, Typeface.BOLD);
        viewAll.setGravity(Gravity.CENTER);
        viewAll.setOnClickListener(v -> showMemory());
        heading.addView(viewAll, lp(dp(76), dp(28)));
        memoryCard.addView(heading);

        if (memories == null || memories.length() == 0) {
            memoryCard.addView(t("No stored memories yet.", 13, MUTED));
            memoryCard.addView(t("As Amiki learns, approved memories will appear here.", 11, MUTED));
            return;
        }

        int shown = Math.min(memories.length(), 3);
        for (int i = 0; i < shown; i++) {
            JSONObject memory = memories.optJSONObject(i);
            if (memory == null) continue;
            String key = memory.optString("key", "Memory");
            String value = memory.optString("value", "").trim();
            if (value.length() > 84) value = value.substring(0, 81) + "…";
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(6), 0, dp(5));
            TextView memoryKey = t(key, 12, TEXT);
            memoryKey.setTypeface(null, Typeface.BOLD);
            item.addView(memoryKey, lp(-1, -2));
            item.addView(t(value.isEmpty() ? "Stored memory" : value, 11, MUTED),
                    lp(-1, -2));
            memoryCard.addView(item, lp(-1, -2));
            if (i < shown - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0x334DE1C1);
                memoryCard.addView(divider, lp(-1, dp(1)));
            }
        }
        if (memories.length() > shown) {
            memoryCard.addView(t("+" + (memories.length() - shown) + " more memories", 10, PURPLE));
        }
    }


    private TextView sectionTitle(String s){TextView v=t(s,15,TEXT);v.setTypeface(null,Typeface.BOLD);v.setPadding(0,dp(18),0,dp(8));return v;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(16),dp(13),dp(16),dp(13));accentCard(c,currentAccent);return c;}
    private void addStat(LinearLayout row,String a,String b){LinearLayout c=card();accentCard(c,metricAccent(b));c.setGravity(Gravity.CENTER);c.addView(t(a,20,TEXT));TextView x=t(b,10,MUTED);x.setGravity(Gravity.CENTER);c.addView(x);row.addView(c,new LinearLayout.LayoutParams(0,-1,1));}
    private void addToolRow(LinearLayout parent,String a,String b){LinearLayout row=new LinearLayout(this);row.setPadding(0,0,0,dp(7)); TextView x=t(a,13,TEXT);x.setGravity(Gravity.CENTER_VERTICAL);x.setPadding(dp(14),0,dp(8),0);x.setBackground(bg(CARD,14));applyToolAccent(x,a); TextView y=t(b,13,TEXT);y.setGravity(Gravity.CENTER_VERTICAL);y.setPadding(dp(14),0,dp(8),0);y.setBackground(bg(CARD,14));applyToolAccent(y,b); x.setOnClickListener(v->toolAction(a)); y.setOnClickListener(v->toolAction(b)); row.addView(x,new LinearLayout.LayoutParams(0,dp(50),1)); LinearLayout.LayoutParams yp=new LinearLayout.LayoutParams(0,dp(50),1);yp.setMargins(dp(7),0,0,0);row.addView(y,yp);parent.addView(row);}
    private void applyToolAccent(TextView view,String label){int color=toolAccent(label);android.graphics.drawable.ColorDrawable marker=new android.graphics.drawable.ColorDrawable(color);marker.setBounds(0,0,dp(3),dp(22));view.setCompoundDrawables(marker,null,null,null);view.setCompoundDrawablePadding(dp(8));}
    private int toolAccent(String label) {
        String value = label.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("web")) return Color.rgb(14, 165, 233);          // web search: sky
        if (value.contains("research") || value.contains("browser")) return Color.rgb(34, 211, 238); // research: cyan
        if (value.contains("backend")) return Color.rgb(99, 102, 241);      // backend: indigo
        if (value.contains("device")) return Color.rgb(52, 211, 153);       // device: emerald
        if (value.contains("security")) return Color.rgb(245, 158, 11);     // security: amber
        if (value.contains("diagnostic")) return Color.rgb(251, 113, 133);  // diagnostics: rose
        if (value.contains("system")) return Color.rgb(249, 115, 22);       // system: orange
        if (value.contains("code")) return Color.rgb(167, 110, 255);        // code: violet
        if (value.contains("android")) return Color.rgb(163, 230, 53);      // android: lime
        if (value.contains("project")) return Color.rgb(217, 70, 239);      // project: fuchsia
        if (value.contains("amiki")) return Color.rgb(244, 114, 182);       // Amiki: pink
        if (value.contains("notes")) return Color.rgb(45, 190, 170);        // notes: jade
        if (value.contains("calculator")) return Color.rgb(255, 205, 86);   // calculator: gold
        return Color.rgb(129, 140, 248);                                    // fallback: indigo
    }

    private void showChat(){
        boolean startRemoteHistoryLoad = false;
        String remoteHistoryConversationId = activeConversationId;
        if (!conversationTranscriptCacheWritable
                && !conversationHistoryLoading
                && !conversationRemoteHydrationAttempted
                && isValidConversationId(activeConversationId)) {
            conversationHistoryLoading = true;
            conversationRemoteHydrationAttempted = true;
            startRemoteHistoryLoad = true;
        }
        persistConversationHistory();
        if(!chatRequestInFlight){
            View existingInput=content.findViewWithTag("amiki_input");
            if(existingInput instanceof EditText) chatDraft=((EditText)existingInput).getText().toString();
        }
        clear("Chat");
        LinearLayout body=lpView(); body.setPadding(dp(14),dp(10),dp(14),dp(10));
        LinearLayout statusRow=new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        AmikiOrbView chatOrb=new AmikiOrbView(this); chatOrb.setTag("amiki_chat_orb"); chatOrb.setPalette(PURPLE,CYAN,TEXT,MUTED); chatOrb.setState(resolveOrbState());
        statusRow.addView(chatOrb,lp(dp(44),dp(44)));
        TextView hint=t("Your AI companion. Your memory. Your edge.\n"+orbStateLabel(),12,MUTED);
        hint.setTag("amiki_chat_orb_label"); hint.setGravity(Gravity.CENTER_VERTICAL); hint.setContentDescription("Amiki status: "+orbStateLabel());
        LinearLayout.LayoutParams hintParams=new LinearLayout.LayoutParams(0,dp(44),1); hintParams.setMargins(dp(10),0,0,0);
        statusRow.addView(hint,hintParams);
        body.addView(statusRow,lp(-1,dp(46)));

        LinearLayout conversationControls = new LinearLayout(this);
        conversationControls.setGravity(Gravity.CENTER_VERTICAL);
        conversationControls.setPadding(0, dp(4), 0, dp(8));
        TextView conversationName = t("✧  " + activeConversationTitle(), 12, TEXT);
        conversationName.setSingleLine(true);
        conversationName.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        conversationControls.addView(conversationName, nameParams);
        TextView historyButton = t("HISTORY", 10, CYAN);
        historyButton.setGravity(Gravity.CENTER);
        historyButton.setBackground(bg(CARD, 11));
        historyButton.setClickable(true);
        historyButton.setEnabled(!chatRequestInFlight && !conversationHistoryLoading);
        historyButton.setContentDescription("Open conversation history");
        historyButton.setOnClickListener(v -> showConversationPicker());
        LinearLayout.LayoutParams historyParams = lp(dp(76), dp(38));
        historyParams.setMargins(dp(4), 0, dp(4), 0);
        conversationControls.addView(historyButton, historyParams);
        TextView newChatButton = t("＋ NEW", 10, TEXT);
        newChatButton.setGravity(Gravity.CENTER);
        newChatButton.setBackground(bg(CARD_2, 11));
        newChatButton.setClickable(true);
        newChatButton.setEnabled(!chatRequestInFlight && !conversationHistoryLoading);
        newChatButton.setContentDescription("Start a new conversation");
        newChatButton.setOnClickListener(v -> createNewConversation());
        LinearLayout.LayoutParams newChatParams = lp(dp(68), dp(38));
        newChatParams.setMargins(dp(4), 0, 0, 0);
        conversationControls.addView(newChatButton, newChatParams);
        body.addView(conversationControls, lp(-1, dp(46)));

        LinearLayout conversation=new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);

        if(chatHistory.isEmpty()){
            TextView empty=t("🌸  Start a conversation with Amiki.",15,MUTED);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(15),dp(28),dp(15),dp(28));
            empty.setBackground(bg(CARD,17)); conversation.addView(empty,lp(-1,dp(88)));
        } else {
            int firstVisibleMessage=Math.max(0,chatHistory.size()-MAX_RENDERED_CHAT_MESSAGES);
            if(firstVisibleMessage>0){
                TextView older=t("Showing the most recent "+MAX_RENDERED_CHAT_MESSAGES
                        +" messages. "+firstVisibleMessage+" older messages remain saved.",11,MUTED);
                older.setPadding(dp(4),dp(4),dp(4),dp(10));
                conversation.addView(older,lp(-1,-2));
            }
            for(int i=firstVisibleMessage;i<chatHistory.size();i++){
                String[] item=chatHistory.get(i);
                if(item==null || item.length<2) continue;
                boolean assistant="assistant".equals(item[0]);
                String fullMessage=item[1]==null?"":item[1];
                String renderMessage=fullMessage.length()>MAX_RENDERED_CHAT_MESSAGE_CHARS
                        ?fullMessage.substring(0,MAX_RENDERED_CHAT_MESSAGE_CHARS)
                                +"\n\n[Display shortened to keep Chat responsive. The saved message is unchanged.]"
                        :fullMessage;
                LinearLayout bubble=card();
                TextView speaker=t(assistant?"🌸 Amiki":"You",11,assistant?PURPLE:CYAN);
                speaker.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
                bubble.addView(speaker,lp(-1,dp(24)));
                if(assistant && "Thinking...".equals(fullMessage)){
                    TextView pending=t("Thinking…",14,MUTED);
                    bubble.addView(pending,lp(-1,-2));
                } else {
                    if (assistant) {
                        // Render assistant Markdown as native views while keeping user messages plain.
                        // If a renderer edge case occurs, keep Chat usable with selectable plain text.
                        try {
                            LinearLayout rendered = MarkdownMessageRenderer.render(
                                    this, renderMessage, TEXT, MUTED, CARD, PURPLE);
                            bubble.addView(rendered, lp(-1, -2));
                        } catch (RuntimeException | StackOverflowError renderError) {
                            TextView fallback = t(renderMessage, 14, TEXT);
                            fallback.setTextIsSelectable(true);
                            fallback.setLongClickable(true);
                            bubble.addView(fallback, lp(-1, -2));
                        }
                    } else {
                        TextView message = t(renderMessage, 14, TEXT);
                        message.setTextIsSelectable(true);
                        message.setLongClickable(true);
                        bubble.addView(message, lp(-1, -2));
                    }
                }
                if(assistant && !"Thinking...".equals(fullMessage)){
                    TextView copy=t("COPY RESPONSE",11,MUTED);
                    copy.setGravity(Gravity.CENTER);
                    copy.setPadding(0,dp(8),0,0);
                    copy.setOnClickListener(v -> {
                        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                        if(cm!=null){cm.setPrimaryClip(ClipData.newPlainText("Amiki response",fullMessage)); Toast.makeText(this,"Response copied.",Toast.LENGTH_SHORT).show();}
                    });
                    bubble.addView(copy,lp(-1,dp(34)));
                    boolean voiceLoading = fullMessage.equals(voiceRequestText);
                    boolean voicePlaying = elevenLabsSpeaking && fullMessage.equals(voicePlaybackText);
                    Button playVoice = new Button(this);
                    playVoice.setText(voicePlaying ? "■  STOP VOICE" : voiceLoading ? "…  GENERATING VOICE" : "🔊  PLAY VOICE");
                    playVoice.setTextSize(11); playVoice.setAllCaps(false); playVoice.setTextColor(TEXT);
                    playVoice.setContentDescription(voicePlaying ? "Stop Amiki voice playback" : voiceLoading ? "Generating Amiki voice" : "Play this response using Amiki voice");
                    playVoice.setBackground(bg(CARD_2, 12));
                    playVoice.setEnabled(voiceLoading || voicePlaying || voiceRequestText == null);
                    playVoice.setOnClickListener(v -> { if (voicePlaying) stopElevenLabsPlayback(); else if (!voiceLoading) requestElevenLabsPlayback(fullMessage); });
                    LinearLayout.LayoutParams voiceButtonParams = lp(-1, dp(38)); voiceButtonParams.setMargins(0, dp(4), 0, 0);
                    bubble.addView(playVoice, voiceButtonParams);
                }
                LinearLayout.LayoutParams bp=lp(-1,-2);
                bp.setMargins(0,0,0,dp(9)); conversation.addView(bubble,bp);
            }
        }
        body.addView(conversation,lp(-1,-2));

        TextView quick=t("✦  Smart actions",13,PURPLE);
        quick.setPadding(0,dp(20),0,dp(8)); body.addView(quick,lp(-1,dp(45)));

        LinearLayout actions=new LinearLayout(this);
        addAction(actions,"Summarize","Summarize the current conversation clearly.");
        addAction(actions,"Explain","Explain the current topic step by step.");
        addAction(actions,"Analyze","Analyze the current problem and identify key issues.");
        addAction(actions,"Study","Turn the current topic into a short lesson with practice questions.");
        body.addView(actions,lp(-1,dp(55)));

        LinearLayout mediaRow=new LinearLayout(this);
        mediaRow.setGravity(Gravity.CENTER_VERTICAL); mediaRow.setPadding(0,dp(4),0,dp(4));

        Button voice=new Button(this); voice.setText(voiceInputInFlight ? "🎙…" : "🎙"); voice.setTextSize(18); voice.setAllCaps(false);
        voice.setTag("amiki_voice");
        voice.setContentDescription(voiceInputInFlight ? "Cancel voice input" : "Voice input"); voice.setBackground(bg(CARD,16)); voice.setEnabled(!chatRequestInFlight && !conversationHistoryLoading); voice.setOnClickListener(v -> {
            if(voiceInputInFlight){
                try{ finishActivity(VOICE_INPUT); }catch(Exception ignored){}
                voiceInputInFlight = false;
                showChat();
            } else {
                startVoiceInput();
            }
        });

        Button attach=new Button(this); attach.setText("📎"); attach.setTextSize(18); attach.setAllCaps(false);
        attach.setContentDescription("Attach file"); attach.setBackground(bg(CARD,16)); attach.setEnabled(!chatRequestInFlight && !conversationHistoryLoading); attach.setOnClickListener(v -> pickAttachment());

        attachmentBadge=t(ttsSpeaking?"🔊 Speaking…":(selectedAttachments.isEmpty()?"No attachment":selectedAttachments.size()+" attachment"+(selectedAttachments.size()==1?"":"s")+" selected"),11,MUTED); attachmentBadge.setGravity(Gravity.CENTER_VERTICAL); attachmentBadge.setPadding(dp(10),0,0,0); attachmentBadge.setTag("amiki_tts_status"); if(ttsSpeaking){ attachmentBadge.setClickable(true); attachmentBadge.setOnClickListener(v -> { if(amikiTts!=null) amikiTts.stop(); ttsSpeaking=false; showChat(); }); }
        mediaRow.addView(voice,lp(dp(50),dp(48))); mediaRow.addView(attach,lp(dp(50),dp(48)));
        mediaRow.addView(attachmentBadge,new LinearLayout.LayoutParams(0,dp(48),1)); body.addView(mediaRow,lp(-1,dp(52)));

        EditText input=new EditText(this); input.setTag("amiki_input"); input.setHint(conversationHistoryLoading ? "Loading saved conversation..." : "Message Amiki...");
        input.setHintTextColor(MUTED); input.setTextColor(TEXT); input.setTextSize(15);
        input.setPadding(dp(15),dp(12),dp(15),dp(12)); input.setEnabled(!chatRequestInFlight && !conversationHistoryLoading);
        input.setBackground(bg(CARD_2,20)); input.setText(chatDraft);
        input.setMinLines(1); input.setMaxLines(6); input.setSingleLine(false);
        input.setHorizontallyScrolling(false); input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setSelection(input.length());
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!chatRequestInFlight) chatDraft = s.toString();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        body.addView(input,lp(-1,-2));

        Button send=new Button(this); send.setTag("amiki_send"); send.setText("➤  Send");
        send.setEnabled(!chatRequestInFlight && !conversationHistoryLoading); send.setTextColor(Color.WHITE);
        send.setTextSize(14); send.setAllCaps(false); send.setBackground(bg(PURPLE,18));
        LinearLayout.LayoutParams sp=lp(-1,dp(52)); sp.setMargins(0,dp(8),0,dp(12)); body.addView(send,sp);
        input.setOnEditorActionListener((v, actionId, event) -> { if(actionId == EditorInfo.IME_ACTION_SEND){ send.performClick(); return true; } return false; });
        send.setOnClickListener(v -> {
            if(chatRequestInFlight || conversationHistoryLoading) return;
            String m=input.getText().toString().trim();
            if(!m.isEmpty() || !selectedAttachments.isEmpty()){
                ArrayList<Uri> attachments=new ArrayList<>(selectedAttachments);
                chatHistory.add(new String[]{"user",m.isEmpty()?"[Attachment]":m});
                chatHistory.add(new String[]{"assistant","Thinking..."});
                String conversationIdForRequest = activeConversationId;
                rememberConversation(
                        conversationIdForRequest,
                        m.isEmpty() ? null : m,
                        System.currentTimeMillis());
                persistConversationHistory();
                int responseIndex=chatHistory.size()-1;
                chatDraft = "";
                input.setText("");
                chatRequestInFlight = true;
                showChat();
                View sendView=content.findViewWithTag("amiki_send");
                if(sendView instanceof Button) ((Button)sendView).setEnabled(false);
                chat(
                        m,
                        responseIndex,
                        attachments,
                        UUID.randomUUID().toString(),
                        conversationIdForRequest);
            }
        });
        content.addView(body,lp(-1,-2));
        ViewParent parent = content.getParent();
        if (parent instanceof ScrollView) {
            ((ScrollView) parent).post(() -> ((ScrollView) parent).fullScroll(View.FOCUS_DOWN));
        }
        if (startRemoteHistoryLoad) {
            loadRemoteConversationTranscript(remoteHistoryConversationId);
        }
    }


    private void requestElevenLabsPlayback(String spokenText) {
        if (spokenText == null || spokenText.trim().isEmpty() || voiceRequestText != null) return;
        if (elevenLabsPlayer != null) stopElevenLabsPlayback();
        if (amikiTts != null && ttsSpeaking) amikiTts.stop();
        ttsSpeaking = false; elevenLabsSpeaking = false; voiceRequestText = spokenText;
        if (active == 0) showChat();
        new Thread(() -> {
            HttpURLConnection connection = null; File audioFile = null;
            try {
                connection = ApiConnection.open(this, ApiEndpoints.VOICE);
                connection.setRequestMethod("POST"); connection.setConnectTimeout(10000); connection.setReadTimeout(60000); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                JSONObject request = new JSONObject(); request.put("text", spokenText);
                try (OutputStream output = connection.getOutputStream()) { output.write(request.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = connection.getResponseCode(); String contentType = connection.getContentType();
                if (code != HttpURLConnection.HTTP_OK || contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) throw new Exception("Voice generation failed (HTTP " + code + ").");
                audioFile = File.createTempFile("amiki-voice-", ".mp3", getCacheDir());
                long totalBytes = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(audioFile)) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) { totalBytes += count; if (totalBytes > 15L * 1024L * 1024L) throw new Exception("Generated audio exceeded the safe size limit."); output.write(buffer, 0, count); }
                }
                if (totalBytes == 0) throw new Exception("Voice service returned empty audio.");
                final File readyAudio = audioFile;
                handler.post(() -> {
                    if (isFinishing() || isDestroyed() || !spokenText.equals(voiceRequestText)) { readyAudio.delete(); return; }
                    voiceRequestText = null; startElevenLabsPlayback(readyAudio, spokenText);
                });
            } catch (Exception error) {
                if (audioFile != null) audioFile.delete();
                handler.post(() -> {
                    if (spokenText.equals(voiceRequestText)) voiceRequestText = null;
                    if (active == 0 && !isFinishing() && !isDestroyed()) showChat();
                    Toast.makeText(this, "Voice playback failed. Text chat is still available.", Toast.LENGTH_SHORT).show();
                });
            } finally { if (connection != null) connection.disconnect(); }
        }).start();
    }

    private void startElevenLabsPlayback(File audioFile, String spokenText) {
        releaseElevenLabsPlayer(false); elevenLabsAudioFile = audioFile; voicePlaybackText = spokenText;
        try {
            MediaPlayer player = new MediaPlayer(); elevenLabsPlayer = player; player.setDataSource(audioFile.getAbsolutePath());
            player.setOnPreparedListener(prepared -> {
                if (isFinishing() || isDestroyed()) { releaseElevenLabsPlayer(true); return; }
                elevenLabsSpeaking = true; ttsSpeaking = true; prepared.start(); if (active == 0) showChat();
            });
            player.setOnCompletionListener(completed -> {
                releaseElevenLabsPlayer(true); elevenLabsSpeaking = false; ttsSpeaking = false; voicePlaybackText = "";
                if (active == 0 && !isFinishing() && !isDestroyed()) showChat();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                releaseElevenLabsPlayer(true); elevenLabsSpeaking = false; ttsSpeaking = false; voicePlaybackText = "";
                if (active == 0 && !isFinishing() && !isDestroyed()) showChat();
                Toast.makeText(this, "Audio could not be played. Text chat is still available.", Toast.LENGTH_SHORT).show(); return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            releaseElevenLabsPlayer(true); elevenLabsSpeaking = false; ttsSpeaking = false; voicePlaybackText = "";
            if (active == 0 && !isFinishing() && !isDestroyed()) showChat();
            Toast.makeText(this, "Audio could not be played. Text chat is still available.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopElevenLabsPlayback() {
        releaseElevenLabsPlayer(true); elevenLabsSpeaking = false; ttsSpeaking = false; voicePlaybackText = "";
        if (active == 0 && !isFinishing() && !isDestroyed()) showChat();
    }

    private void releaseElevenLabsPlayer(boolean deleteAudio) {
        MediaPlayer player = elevenLabsPlayer; elevenLabsPlayer = null;
        if (player != null) {
            try { player.setOnPreparedListener(null); } catch (Exception ignored) {}
            try { player.setOnCompletionListener(null); } catch (Exception ignored) {}
            try { player.setOnErrorListener(null); } catch (Exception ignored) {}
            try { player.stop(); } catch (Exception ignored) {}
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
        }
        File audio = elevenLabsAudioFile; elevenLabsAudioFile = null;
        if (deleteAudio && audio != null) audio.delete();
    }

    private void addAction(LinearLayout row,String label,String prompt){
        TextView v=t("▣  "+label,11,TEXT);
        v.setGravity(Gravity.CENTER);
        v.setBackground(bg(CARD,12));
        v.setClickable(true);
        v.setOnClickListener(view -> {
            View inputView=content.findViewWithTag("amiki_input");
            View sendView=content.findViewWithTag("amiki_send");
            if(inputView instanceof EditText){
                EditText input=(EditText)inputView;
                input.setText(prompt);
                input.setSelection(input.length());
                if(sendView instanceof Button){
                    sendView.performClick();
                } else {
                    input.requestFocus();
                    InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                    if(imm!=null) imm.showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
        LinearLayout.LayoutParams p=lp(0,dp(48));
        p.weight=1;
        p.setMargins(2,2,2,2);
        row.addView(v,p);
    }

    private void ensureConversationExists(String conversationId) throws Exception {
        String metadataUrl = ApiEndpoints.conversation(conversationId);
        HttpURLConnection lookup = null;
        try {
            lookup = ApiConnection.open(this, metadataUrl);
            lookup.setRequestMethod("GET");
            lookup.setConnectTimeout(10000);
            lookup.setReadTimeout(15000);
            int code = lookup.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) return;
            if (code != HttpURLConnection.HTTP_NOT_FOUND) {
                throw new Exception("Conversation lookup failed (HTTP " + code + ").");
            }
        } finally {
            if (lookup != null) lookup.disconnect();
        }

        HttpURLConnection create = null;
        try {
            create = ApiConnection.open(this, ApiEndpoints.CONVERSATIONS);
            create.setRequestMethod("POST");
            create.setConnectTimeout(10000);
            create.setReadTimeout(15000);
            create.setDoOutput(true);
            create.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            JSONObject request = new JSONObject();
            request.put("conversation_id", conversationId);
            request.put("title", "New Chat");
            try (OutputStream output = create.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = create.getResponseCode();
            if (code == HttpURLConnection.HTTP_CREATED || code == HttpURLConnection.HTTP_CONFLICT) return;
            throw new Exception("Conversation registration failed (HTTP " + code + ").");
        } finally {
            if (create != null) create.disconnect();
        }
    }

    private void chat(
            String message,
            int responseIndex,
            ArrayList<Uri> attachments,
            String requestId,
            String conversationId){
        if (deviceActionExecutor == null) deviceActionExecutor = new DeviceActionExecutor(this);
        boolean handled = attachments.isEmpty() && deviceActionExecutor.tryExecute(message, new DeviceActionExecutor.Callback() {
            @Override public void onResult(String result) {
                handler.post(() -> {
                    if(responseIndex<chatHistory.size()) chatHistory.get(responseIndex)[1]=result;
                    chatRequestInFlight = false;
                    online.setText("● ONLINE"); online.setTextColor(Color.rgb(100,230,130));
                    showChat();
                });
            }
            @Override public void onError(String error) {
                handler.post(() -> {
                    if(responseIndex<chatHistory.size()) chatHistory.get(responseIndex)[1]="I couldn't complete that device action. Check Diagnostics.";
                    chatRequestInFlight = false;
                    online.setText("● ONLINE"); online.setTextColor(Color.rgb(100,230,130));
                    showChat();
                });
            }
        });
        if (handled) return;

        online.setText("● THINKING"); online.setTextColor(Color.rgb(190,120,255));
        new Thread(() -> {
            HttpURLConnection c=null;
            try{
                ensureConversationExists(conversationId);
                c=ApiConnection.open(this, API);
                c.setRequestMethod("POST"); c.setConnectTimeout(10000); c.setReadTimeout(270000); c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                JSONObject req=new JSONObject();
                req.put("message",message);
                req.put("request_id",requestId);
                req.put("conversation_id",conversationId);
                if (settingsPrefs.getBoolean("share_location_for_safety", false)
                        && LocationContextProvider.shouldAttachLocationContext(message)) {
                    JSONObject location = LocationContextProvider.getFreshLocationContext(this);
                    if (location != null) req.put("location_context", location);
                }
                String responseBodyText;
                if(!attachments.isEmpty()){
                    responseBodyText=sendMultipartRequest(
                            message.isEmpty()?"Please inspect the attached file.":message,
                            attachments,
                            requestId,
                            conversationId);
                    JSONObject response=new JSONObject(responseBodyText);
                    String answer=response.optString("response","No response returned.");
                    handler.post(() -> {
                        if(responseIndex<chatHistory.size()) chatHistory.get(responseIndex)[1]=answer;
                        chatRequestInFlight = false;
                        selectedAttachments.clear();
                        online.setText("● ONLINE"); online.setTextColor(Color.rgb(100,230,130));
                        if(settingsPrefs.getBoolean("amiki_tts", false) && ttsReady && amikiTts!=null) amikiTts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "amiki_response");
                        showChat();
                    });
                    return;
                }
                try(OutputStream o=c.getOutputStream()){o.write(req.toString().getBytes(StandardCharsets.UTF_8));}
                int code=c.getResponseCode(); lastChatHttpCode=code; lastChatTime=System.currentTimeMillis();
                StringBuilder b=new StringBuilder();
                if(code>=200&&code<300){
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                        String line; while((line=r.readLine())!=null)b.append(line);
                    }
                }else{
                    java.io.InputStream es=c.getErrorStream();
                    if(es!=null)try(BufferedReader r=new BufferedReader(new InputStreamReader(es,StandardCharsets.UTF_8))){
                        String line; while((line=r.readLine())!=null)b.append(line);
                    }
                    throw new Exception("HTTP "+code+(b.length()>0?": "+b:""));
                }
                String answer=new JSONObject(b.toString()).optString("response","No response returned.");
                handler.post(() -> {
                    if(responseIndex<chatHistory.size()) chatHistory.get(responseIndex)[1]=answer;
                    chatRequestInFlight = false;
                    online.setText("● ONLINE"); online.setTextColor(Color.rgb(100,230,130));
                    if(settingsPrefs.getBoolean("amiki_tts", false) && ttsReady && amikiTts!=null) amikiTts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "amiki_response");
                    showChat();
                });
            }catch(Exception e){
                lastChatError=e.getMessage()==null?e.toString():e.getMessage();
                lastChatTime=System.currentTimeMillis();
                handler.post(() -> {
                    if(responseIndex<chatHistory.size()) {
                        if (!attachments.isEmpty()) {
                            String detail = e.getMessage() == null ? "Check the file type, size, and backend diagnostics." : e.getMessage();
                            if (detail.length() > 240) detail = detail.substring(0, 240) + "…";
                            chatHistory.get(responseIndex)[1] = "Attachment upload failed: " + detail;
                        } else {
                            chatHistory.get(responseIndex)[1] = "I couldn't complete that request. Check Diagnostics.";
                        }
                    }
                    persistConversationHistory();
                    chatRequestInFlight = false;
                    // A failed request does not prove that the backend is offline.
                    // Re-check health so the indicator reflects the actual runtime state.
                    checkHealth();
                    showChat();
                });
            }finally{if(c!=null)c.disconnect();}
        }).start();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (deviceActionExecutor != null) {
            deviceActionExecutor.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        if (requestCode == LOCATION_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && (grantResults[0] == PackageManager.PERMISSION_GRANTED
                    || grantResults.length > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED);
            settingsPrefs.edit().putBoolean("share_location_for_safety", granted).apply();
            showSettings();
            return;
        }
        if (requestCode == VOICE_INPUT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                Toast.makeText(this, "Microphone permission is required for voice input.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override protected void onDestroy(){ persistConversationHistory(); ttsSpeaking=false; elevenLabsSpeaking=false; releaseElevenLabsPlayer(true); if(amikiTts!=null){amikiTts.stop();amikiTts.shutdown();} super.onDestroy(); }

    private void restoreConversationHistory() {
        SharedPreferences prefs = getSharedPreferences(CONVERSATION_HISTORY_PREFERENCES, MODE_PRIVATE);

        // The previous single-session transcript is deliberately left untouched.
        // A user-directed import can be added later; it must never be silently
        // assigned to the first newly created conversation.
        try {
            JSONArray savedIndex = new JSONArray(prefs.getString(CONVERSATION_INDEX_KEY, "[]"));
            for (int i = 0; i < savedIndex.length() && conversationIndex.size() < MAX_CACHED_CONVERSATIONS; i++) {
                JSONObject row = savedIndex.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("conversation_id", "");
                if (!isValidConversationId(id) || findConversationInfo(id) != null) continue;
                String title = row.optString("title", "New Chat");
                long updated = row.optLong("updated_at_millis", 0L);
                conversationIndex.add(new ConversationInfo(id, boundedConversationTitle(title), updated));
            }
        } catch (Exception ignored) {
            // Keep the saved preference intact; rebuild only the in-memory index.
            conversationIndex.clear();
        }

        String savedActiveId = prefs.getString(ACTIVE_CONVERSATION_ID_KEY, "");
        boolean createdLocalConversation = false;
        if (!isValidConversationId(savedActiveId) || findConversationInfo(savedActiveId) == null) {
            if (conversationIndex.isEmpty()) {
                savedActiveId = UUID.randomUUID().toString();
                conversationIndex.add(new ConversationInfo(savedActiveId, "New Chat", System.currentTimeMillis()));
                createdLocalConversation = true;
            } else {
                savedActiveId = conversationIndex.get(0).id;
            }
        }
        activeConversationId = savedActiveId;
        conversationTranscriptCacheWritable = createdLocalConversation
                || prefs.contains(CONVERSATION_TRANSCRIPT_PREFIX + activeConversationId);
        conversationRemoteHydrationAttempted = conversationTranscriptCacheWritable;
        loadLocalConversationTranscript(prefs, activeConversationId);

        try {
            JSONObject savedAgents = new JSONObject(prefs.getString("direct_agent_history", "{}"));
            java.util.Iterator<String> keys = savedAgents.keys();
            while (keys.hasNext()) {
                String agent = keys.next();
                JSONArray rows = savedAgents.optJSONArray(agent);
                if (rows == null) continue;
                ArrayList<String[]> history = new ArrayList<>();
                int start = Math.max(0, rows.length() - MAX_PERSISTED_AGENT_MESSAGES);
                for (int i = start; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    String role = row.optString("role", "");
                    if (!"user".equals(role) && !"assistant".equals(role)) continue;
                    history.add(new String[]{role, boundedPersistedText(row.optString("text", ""))});
                }
                directAgentHistory.put(agent, history);
            }
        } catch (Exception ignored) {
            directAgentHistory.clear();
        }
    }

    private boolean isValidConversationId(String value) {
        try {
            ApiEndpoints.conversation(value);
            return true;
        } catch (IllegalArgumentException invalidId) {
            return false;
        }
    }

    private ConversationInfo findConversationInfo(String id) {
        if (id == null) return null;
        for (ConversationInfo item : conversationIndex) {
            if (id.equals(item.id)) return item;
        }
        return null;
    }

    private String boundedConversationTitle(String value) {
        if (value == null) return "New Chat";
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return "New Chat";
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 79) + "…";
    }

    private void rememberConversation(String id, String title, long updatedAtMillis) {
        ConversationInfo item = findConversationInfo(id);
        if (item == null) {
            item = new ConversationInfo(id, boundedConversationTitle(title), updatedAtMillis);
        } else {
            conversationIndex.remove(item);
            if (title != null
                    && !title.trim().isEmpty()
                    && "New Chat".equals(item.title)) {
                item.title = boundedConversationTitle(title);
            }
            item.updatedAtMillis = Math.max(item.updatedAtMillis, updatedAtMillis);
        }
        conversationIndex.add(0, item);
        while (conversationIndex.size() > MAX_CACHED_CONVERSATIONS) {
            ConversationInfo evicted = conversationIndex.remove(conversationIndex.size() - 1);
            getSharedPreferences(CONVERSATION_HISTORY_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .remove(CONVERSATION_TRANSCRIPT_PREFIX + evicted.id)
                    .apply();
        }
    }

    private void loadLocalConversationTranscript(SharedPreferences prefs, String conversationId) {
        chatHistory.clear();
        try {
            JSONArray savedChat = new JSONArray(
                    prefs.getString(CONVERSATION_TRANSCRIPT_PREFIX + conversationId, "[]"));
            int start = Math.max(0, savedChat.length() - MAX_CACHED_CONVERSATION_MESSAGES);
            for (int i = start; i < savedChat.length(); i++) {
                JSONObject row = savedChat.optJSONObject(i);
                if (row == null) continue;
                String role = row.optString("role", "");
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                chatHistory.add(new String[]{role, boundedConversationText(row.optString("text", ""))});
            }
        } catch (Exception ignored) {
            // A damaged transcript is isolated to this conversation.
            chatHistory.clear();
        }
    }

    private String activeConversationTitle() {
        ConversationInfo info = findConversationInfo(activeConversationId);
        return info == null ? "New Chat" : info.title;
    }

    private void createNewConversation() {
        if (chatRequestInFlight) {
            Toast.makeText(this, "Wait for the current reply before switching chats.", Toast.LENGTH_SHORT).show();
            return;
        }
        persistConversationHistory();
        activeConversationId = UUID.randomUUID().toString();
        conversationHistoryLoading = false;
        conversationTranscriptCacheWritable = true;
        conversationRemoteHydrationAttempted = true;
        rememberConversation(activeConversationId, "New Chat", System.currentTimeMillis());
        chatHistory.clear();
        selectedAttachments.clear();
        chatDraft = "";
        persistConversationHistory();
        showChat();
    }

    private void selectConversation(String conversationId) {
        if (chatRequestInFlight || conversationHistoryLoading || !isValidConversationId(conversationId)) return;
        persistConversationHistory();
        SharedPreferences prefs = getSharedPreferences(CONVERSATION_HISTORY_PREFERENCES, MODE_PRIVATE);
        boolean hasLocalTranscript = prefs.contains(CONVERSATION_TRANSCRIPT_PREFIX + conversationId);
        activeConversationId = conversationId;
        conversationHistoryLoading = !hasLocalTranscript;
        conversationTranscriptCacheWritable = hasLocalTranscript;
        conversationRemoteHydrationAttempted = true;
        loadLocalConversationTranscript(prefs, conversationId);
        selectedAttachments.clear();
        chatDraft = "";
        rememberConversation(conversationId, null, System.currentTimeMillis());
        persistConversationHistory();
        showChat();

        // A remote-only conversation is bootstrapped from Core. Existing local
        // transcripts remain intact because Android device-local actions are
        // not yet mirrored to the Core transcript store.
        if (!hasLocalTranscript) {
            loadRemoteConversationTranscript(conversationId);
        }
    }

    private void showConversationPicker() {
        if (chatRequestInFlight || conversationHistoryLoading) {
            Toast.makeText(this, "Wait for the current chat to finish loading or replying.", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            ArrayList<ConversationInfo> remote = new ArrayList<>();
            try {
                JSONObject payload = new JSONObject(getJson(ApiEndpoints.CONVERSATIONS + "?limit=100"));
                JSONArray rows = payload.optJSONArray("conversations");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        String id = row.optString("conversation_id", "");
                        if (!isValidConversationId(id)) continue;
                        remote.add(new ConversationInfo(
                                id,
                                boundedConversationTitle(row.optString("title", "New Chat")),
                                System.currentTimeMillis() - i));
                    }
                }
            } catch (Exception ignored) {
                // The picker remains usable from the local index while offline.
            }
            handler.post(() -> {
                for (int i = remote.size() - 1; i >= 0; i--) {
                    ConversationInfo item = remote.get(i);
                    rememberConversation(item.id, item.title, item.updatedAtMillis);
                }
                persistConversationHistory();

                String[] labels = new String[conversationIndex.size()];
                for (int i = 0; i < conversationIndex.size(); i++) {
                    ConversationInfo item = conversationIndex.get(i);
                    labels[i] = (item.id.equals(activeConversationId) ? "●  " : "")
                            + item.title;
                }
                SharedPreferences prefs = getSharedPreferences(CONVERSATION_HISTORY_PREFERENCES, MODE_PRIVATE);
                boolean hasLegacyTranscript = prefs.contains("chat_history")
                        && !"[]".equals(prefs.getString("chat_history", "[]").trim());
                AlertDialog.Builder picker = new AlertDialog.Builder(this)
                        .setTitle("Conversation history")
                        .setItems(labels, (dialog, which) -> {
                            if (which >= 0 && which < conversationIndex.size()) {
                                selectConversation(conversationIndex.get(which).id);
                            }
                        })
                        .setPositiveButton("New chat", (dialog, which) -> createNewConversation())
                        .setNegativeButton("Close", null);
                if (hasLegacyTranscript) {
                    picker.setMessage(
                            "Your earlier single-session Chat transcript remains saved separately on this device. "
                                    + "It has not been imported into a new conversation.");
                }
                picker.show();
            });
        }).start();
    }

    private void loadRemoteConversationTranscript(String conversationId) {
        new Thread(() -> {
            ArrayList<String[]> latest = new ArrayList<>();
            boolean remoteLoaded = false;
            try {
                String url = ApiEndpoints.conversationMessages(conversationId) + "?limit=100";
                JSONObject page = new JSONObject(getJson(url));
                remoteLoaded = true;
                appendRemoteMessages(latest, page.optJSONArray("messages"));

                if (page.optBoolean("has_more", false)
                        && latest.size() < MAX_CACHED_CONVERSATION_MESSAGES) {
                    int cursor = page.optInt("next_before_sequence", 0);
                    if (cursor > 0) {
                        JSONObject older = new JSONObject(getJson(
                                ApiEndpoints.conversationMessages(conversationId)
                                        + "?limit=100&before_sequence=" + cursor));
                        ArrayList<String[]> olderMessages = new ArrayList<>();
                        appendRemoteMessages(olderMessages, older.optJSONArray("messages"));
                        olderMessages.addAll(latest);
                        latest = olderMessages;
                    }
                }
            } catch (Exception ignored) {
                // The local transcript remains the offline fallback.
            }

            ArrayList<String[]> restored = latest;
            boolean loaded = remoteLoaded;
            handler.post(() -> {
                if (!conversationId.equals(activeConversationId)) return;
                if (loaded) {
                    if (!restored.isEmpty()) {
                        chatHistory.clear();
                        int start = Math.max(0, restored.size() - MAX_CACHED_CONVERSATION_MESSAGES);
                        for (int i = start; i < restored.size(); i++) {
                            chatHistory.add(restored.get(i));
                        }
                    }
                    conversationTranscriptCacheWritable = true;
                }
                conversationHistoryLoading = false;
                persistConversationHistory();
                if (active == 0 && !isFinishing() && !isDestroyed()) showChat();
            });
        }).start();
    }

    private void appendRemoteMessages(ArrayList<String[]> target, JSONArray messages) {
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null) continue;
            String role = row.optString("role", "");
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            target.add(new String[]{role, boundedConversationText(row.optString("text", ""))});
        }
    }

    private String boundedPersistedText(String value) {
        if (value == null) return "";
        return value.length() <= MAX_PERSISTED_MESSAGE_CHARS
                ? value : value.substring(0, MAX_PERSISTED_MESSAGE_CHARS) + "\n[Saved transcript truncated]";
    }

    private String boundedConversationText(String value) {
        if (value == null) return "";
        return value.length() <= MAX_CACHED_CONVERSATION_MESSAGE_CHARS
                ? value : value.substring(0, MAX_CACHED_CONVERSATION_MESSAGE_CHARS)
                        + "\n[Conversation cache shortened; full transcript remains on Core]";
    }

    private void persistConversationHistory() {
        SharedPreferences prefs = getSharedPreferences(CONVERSATION_HISTORY_PREFERENCES, MODE_PRIVATE);
        try {
            if (!isValidConversationId(activeConversationId)) return;
            while (chatHistory.size() > MAX_CACHED_CONVERSATION_MESSAGES) chatHistory.remove(0);
            JSONArray savedChat = new JSONArray();
            for (String[] row : chatHistory) {
                if (row == null || row.length < 2) continue;
                JSONObject item = new JSONObject();
                item.put("role", row[0]);
                item.put("text", boundedConversationText(row[1]));
                savedChat.put(item);
            }

            JSONArray savedIndex = new JSONArray();
            for (ConversationInfo conversation : conversationIndex) {
                JSONObject item = new JSONObject();
                item.put("conversation_id", conversation.id);
                item.put("title", boundedConversationTitle(conversation.title));
                item.put("updated_at_millis", conversation.updatedAtMillis);
                savedIndex.put(item);
            }

            JSONObject savedAgents = new JSONObject();
            for (Map.Entry<String, ArrayList<String[]>> entry : directAgentHistory.entrySet()) {
                ArrayList<String[]> history = entry.getValue();
                while (history.size() > MAX_PERSISTED_AGENT_MESSAGES) history.remove(0);
                JSONArray rows = new JSONArray();
                for (String[] row : history) {
                    if (row == null || row.length < 2) continue;
                    JSONObject item = new JSONObject();
                    item.put("role", row[0]);
                    item.put("text", boundedPersistedText(row[1]));
                    rows.put(item);
                }
                savedAgents.put(entry.getKey(), rows);
            }
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(CONVERSATION_INDEX_KEY, savedIndex.toString())
                    .putString(ACTIVE_CONVERSATION_ID_KEY, activeConversationId)
                    .putString("direct_agent_history", savedAgents.toString());
            if (conversationTranscriptCacheWritable) {
                editor.putString(
                        CONVERSATION_TRANSCRIPT_PREFIX + activeConversationId,
                        savedChat.toString());
            }
            editor.apply();
        } catch (Exception ignored) {
            // Transcript persistence is best-effort; never block chat interaction.
        }
    }

    private void showMemory(){
        clear("Memory");
        LinearLayout body=lpView();
        body.addView(t("Memory Snapshot",18,TEXT));
        body.addView(t("What Amiki is allowed to remember about you and your projects.",13,MUTED));

        LinearLayout controls=card();
        controls.addView(t("MEMORY CONTROLS",11,PURPLE));
        controls.addView(t("These actions permanently clear the selected memory store.",12,MUTED));
        addMemoryAction(controls,"Clear Legacy Session Context","Remove the old global prompt context only.",CURRENT_SESSION_MEMORY,
                "Clear the legacy global session context? Saved conversations are not affected.");
        addMemoryAction(controls,"Clear Persistent Memory","Remove all long-term memory facts.",PERSISTENT_MEMORY,
                "Clear all persistent memory? This cannot be undone.");
        LinearLayout.LayoutParams cp=lp(-1,-2); cp.setMargins(0,dp(12),0,dp(4)); body.addView(controls,cp);

        new Thread(()->{
            try{
                String raw=getJson(MEMORY);
                JSONArray a=new JSONObject(raw).optJSONArray("memories");
                handler.post(()->{
                    if(a==null||a.length()==0) body.addView(t("No memories stored yet.",14,MUTED));
                    else for(int i=0;i<a.length();i++){
                        JSONObject m=a.optJSONObject(i);
                        LinearLayout x=card();
                        x.addView(t("✿  "+m.optString("category","MEMORY"),11,Color.rgb(72,205,190)));
                        x.addView(t(m.optString("key","Memory"),15,TEXT));
                        x.addView(t(m.optString("value",""),13,MUTED));
                        LinearLayout.LayoutParams p=lp(-1,-2); p.setMargins(0,dp(10),0,0); body.addView(x,p);
                    }
                });
            }catch(Exception e){
                handler.post(()->body.addView(t("Unable to load memories. Check Diagnostics.",14,MUTED)));
            }
        }).start();
        content.addView(body,lp(-1,-2));
    }

    private void addMemoryAction(LinearLayout parent,String title,String detail,String endpoint,String confirmation){
        LinearLayout row=card();
        row.setClickable(true);
        row.setOnClickListener(v -> confirmMemoryClear(title,endpoint,confirmation));
        row.addView(t(title+"  ›",14,TEXT));
        row.addView(t(detail,11,MUTED));
        LinearLayout.LayoutParams p=lp(-1,-2); p.setMargins(0,dp(8),0,0); parent.addView(row,p);
    }

    private void confirmMemoryClear(String title,String endpoint,String message){
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Clear", (dialog,which) -> deleteMemory(endpoint,title))
                .show();
    }

    private void deleteMemory(String endpoint,String title){
        new Thread(()->{
            HttpURLConnection c=null;
            try{
                c=ApiConnection.open(this, endpoint);
                c.setRequestMethod("DELETE");
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                int code=c.getResponseCode();
                if(code<200||code>=300) throw new Exception("HTTP "+code);
                handler.post(()->{
                    Toast.makeText(this,title+" cleared.",Toast.LENGTH_SHORT).show();
                    showMemory();
                });
            }catch(Exception e){
                handler.post(()->Toast.makeText(this,"Could not clear "+title.toLowerCase()+". Check Diagnostics.",Toast.LENGTH_SHORT).show());
            }finally{if(c!=null)c.disconnect();}
        }).start();
    }

    private String greetingText(){
        int hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if(hour<12) return "Good morning, Chimz 👋";
        if(hour<18) return "Good afternoon, Chimz 👋";
        return "Good evening, Chimz 👋";
    }

    private String runtimeState(JSONObject sys){
        JSONObject runtime=sys.optJSONObject("runtime");
        return runtime==null ? "UNKNOWN" : runtime.optString("state","UNKNOWN").toUpperCase();
    }

    private void showResources(){
        clear("Study Resources");
        LinearLayout body=lpView();
        body.addView(t("📚  Study Resources",18,TEXT));
        body.addView(t("Start a focused resource session with Amiki. These actions open real study workflows.",13,MUTED));
        addLargeCard(body,"▤  MAT 1100","Sets, logic & analysis",v -> openStudyActivity("lesson","MAT 1100","MAT 1100"));
        addLargeCard(body,"△  Chemistry","Next subject",v -> openStudyActivity("lesson","CHEMISTRY","Chemistry"));
        addLargeCard(body,"⌘  Python","Software Engineering",v -> openStudyActivity("lesson","PYTHON","Python for Software Engineering"));
        addLargeCard(body,"🔎  Research","Find current sources",v -> openStudyActivity("resources","",""));
        content.addView(body,lp(-1,-2));
    }

    private void showAndroidBuild(){
        clear("Android App");
        LinearLayout body=lpView();
        body.addView(t("▣  Android App",18,TEXT));
        body.addView(t("Current installed Amiki build information from this Android package.",13,MUTED));
        LinearLayout info=card();
        try{
            android.content.pm.PackageInfo pi=getPackageManager().getPackageInfo(getPackageName(),0);
            long versionCode=android.os.Build.VERSION.SDK_INT>=28 ? pi.getLongVersionCode() : pi.versionCode;
            info.addView(t("PACKAGE",11,PURPLE));
            info.addView(t(getPackageName(),14,TEXT));
            info.addView(t("VERSION",11,PURPLE));
            info.addView(t(pi.versionName+" ("+versionCode+")",15,TEXT));
            info.addView(t("ANDROID",11,PURPLE));
            info.addView(t("API "+android.os.Build.VERSION.SDK_INT+" • "+android.os.Build.VERSION.RELEASE,14,TEXT));
            info.addView(t("BUILD STATE",11,PURPLE));
            info.addView(t("Installed and running",14,Color.rgb(100,230,130)));
        }catch(Exception e){
            info.addView(t("Unable to read installed build information.",14,MUTED));
        }
        body.addView(info,lp(-1,-2));
        addLargeCard(body,"↻  Open Amiki","Return to chat");
        content.addView(body,lp(-1,-2));
    }

    private void showStudy(){
        clear("Study Hub");
        LinearLayout body=lpView();
        body.addView(t("YOUR PERSONAL LEARNING SPACE",11,PURPLE));
        body.addView(t("Learn something. Practise it. Track what you actually did.",13,MUTED));
        body.addView(t("Loading your academic profile…",12,MUTED));
        content.addView(body,lp(-1,-2));
        loadAcademicState(body);
    }

    private void showAddCourseDialog(LinearLayout body){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(8),0,dp(8),0);
        EditText code=new EditText(this);code.setHint("Course code (e.g. MAT 1100)");code.setSingleLine(true);form.addView(code,lp(-1,dp(52)));
        EditText name=new EditText(this);name.setHint("Course name");name.setSingleLine(true);form.addView(name,lp(-1,dp(52)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Add course").setMessage("Record a course you have explicitly confirmed.").setView(form).setNegativeButton("CANCEL",null).setPositiveButton("ADD",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            String courseCode=code.getText().toString().trim(),courseName=name.getText().toString().trim();
            if(courseCode.isEmpty()){code.setError("Enter the course code.");return;}
            if(courseName.isEmpty()){name.setError("Enter the course name.");return;}
            JSONObject request=new JSONObject();
            try{request.put("code",courseCode);request.put("name",courseName);}catch(Exception ignored){return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            new Thread(()->{
                try{postJson(ACADEMIC+"/courses",request);handler.post(()->{dialog.dismiss();loadAcademicState(body);});}
                catch(Exception e){handler.post(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);Toast.makeText(this,"Course could not be recorded. Check Diagnostics.",Toast.LENGTH_SHORT).show();});}
            }).start();
        }));
        dialog.show();
    }

    private void addCourseButton(LinearLayout body){ Button add=new Button(this);add.setText("＋  ADD COURSE");add.setTextColor(TEXT);add.setOnClickListener(v->showAddCourseDialog(body));body.addView(add,lp(-1,dp(46))); }
    private void showAddStudyRecordDialog(LinearLayout body,String courseCode){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(8),0,dp(8),0);
        EditText topic=new EditText(this);topic.setHint("Topic studied");topic.setSingleLine(true);form.addView(topic,lp(-1,dp(52)));
        EditText activity=new EditText(this);activity.setHint("What you did");activity.setSingleLine(true);form.addView(activity,lp(-1,dp(52)));
        Button status=new Button(this);status.setText("STATUS: COMPLETED");status.setAllCaps(false);status.setOnClickListener(v->{String current=status.getTag()==null?"completed":String.valueOf(status.getTag());String next="completed".equals(current)?"in_progress":"in_progress".equals(current)?"planned":"completed";status.setTag(next);status.setText("STATUS: "+next.replace('_',' ').toUpperCase(Locale.ROOT));});status.setTag("completed");form.addView(status,lp(-1,dp(46)));
        EditText notes=new EditText(this);notes.setHint("Notes (optional)");notes.setSingleLine(false);form.addView(notes,lp(-1,dp(70)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Record study").setMessage("Record an activity and its current status for this course.").setView(form).setNegativeButton("CANCEL",null).setPositiveButton("SAVE",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            String topicValue=topic.getText().toString().trim(),activityValue=activity.getText().toString().trim(),notesValue=notes.getText().toString().trim();
            if(topicValue.isEmpty()){topic.setError("Enter the topic.");return;}
            if(activityValue.isEmpty()){activity.setError("Enter what you did.");return;}
            String statusValue=status.getTag()==null?"completed":String.valueOf(status.getTag());
            JSONObject request=new JSONObject();
            try{request.put("topic",topicValue);request.put("activity",activityValue);request.put("status",statusValue);request.put("notes",notesValue);}catch(Exception ignored){return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            new Thread(()->{
                try{postJson(ACADEMIC+"/courses/"+java.net.URLEncoder.encode(courseCode,"UTF-8")+"/study-records",request);handler.post(()->{dialog.dismiss();loadAcademicState(body);});}
                catch(Exception e){handler.post(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);Toast.makeText(this,"Study record could not be saved. Check Diagnostics.",Toast.LENGTH_SHORT).show();});}
            }).start();
        }));
        dialog.show();
    }


    private void showEditCourseDialog(LinearLayout body,String courseCode,String currentName,JSONObject currentMetadata){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(8),0,dp(8),0);
        EditText name=new EditText(this);name.setHint("Course name");name.setSingleLine(true);name.setText(currentName);name.setSelection(name.length());form.addView(name,lp(-1,dp(52)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit course").setMessage("Update only recorded course information.").setView(form).setNegativeButton("CANCEL",null).setPositiveButton("SAVE",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            String courseName=name.getText().toString().trim();
            if(courseName.isEmpty()){name.setError("Enter the course name.");return;}
            JSONObject request=new JSONObject();
            try{request.put("name",courseName);request.put("metadata",new JSONObject(currentMetadata.toString()));}catch(Exception ignored){return;}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            new Thread(()->{
                try{putJson(ACADEMIC+"/courses/"+java.net.URLEncoder.encode(courseCode,"UTF-8"),request);handler.post(()->{dialog.dismiss();loadAcademicState(body);});}
                catch(Exception e){handler.post(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);Toast.makeText(this,"Course could not be updated. Check Diagnostics.",Toast.LENGTH_SHORT).show();});}
            }).start();
        }));
        dialog.show();
    }
    private void loadAcademicState(LinearLayout body){new Thread(()->{try{JSONObject academic=new JSONObject(getJson(ACADEMIC));handler.post(()->renderAcademicState(body,academic));}catch(Exception e){handler.post(()->{body.removeAllViews();body.addView(t("University / Study",19,TEXT));body.addView(t("Live academic state is unavailable. Check Diagnostics.",13,MUTED));addDirectAgentComposer(body,new String[]{"research","tutor"},"Ask Research or Tutor directly...");});}}).start();}
    private void renderAcademicState(LinearLayout body,JSONObject academic){
        body.removeAllViews();
        String institution=academic.optString("institution","").trim();
        String program=academic.optString("program","").trim();
        int duration=academic.optInt("duration_years",0);
        int year=academic.optInt("current_year",0);
        int semester=academic.optInt("current_semester",0);
        String session=academic.optString("current_session","").trim();
        JSONArray courses=academic.optJSONArray("courses");
        int courseCount=courses==null?0:courses.length();
        int recordCount=0;
        int completedCount=0;
        for(int i=0;i<courseCount;i++){
            JSONObject course=courses.optJSONObject(i);
            if(course==null)continue;
            JSONObject metadata=course.optJSONObject("metadata");
            JSONArray records=metadata==null?null:metadata.optJSONArray("study_records");
            if(records==null)continue;
            recordCount+=records.length();
            for(int j=0;j<records.length();j++){
                JSONObject record=records.optJSONObject(j);
                if(record!=null&&"completed".equalsIgnoreCase(record.optString("status","")))completedCount++;
            }
        }

        LinearLayout hero=card();
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16),dp(16),dp(16),dp(16));
        hero.addView(t("🎓  YOUR ACADEMIC PROFILE",11,PURPLE));
        hero.addView(t(institution.isEmpty()?"Institution not set":institution,17,TEXT));
        hero.addView(t(program.isEmpty()?"Programme not set":program,13,MUTED));
        String period=(year>0?"Year "+year:"Year not set")+"  •  "+(semester>0?"Semester "+semester:"Semester not set");
        hero.addView(t(period,13,TEXT));
        if(!session.isEmpty())hero.addView(t("Academic session: "+session,12,MUTED));
        if(duration>0)hero.addView(t("Programme duration: "+duration+" years",12,MUTED));
        body.addView(hero,lp(-1,-2));

        body.addView(sectionTitle("Your study at a glance"));
        LinearLayout stats=new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.addView(studyStat("COURSES",String.valueOf(courseCount)),new LinearLayout.LayoutParams(0,dp(76),1));
        stats.addView(studyStat("ACTIVITIES",String.valueOf(recordCount)),new LinearLayout.LayoutParams(0,dp(76),1));
        stats.addView(studyStat("COMPLETED",String.valueOf(completedCount)),new LinearLayout.LayoutParams(0,dp(76),1));
        body.addView(stats,lp(-1,dp(82)));
        body.addView(t("These counts come from your saved records. No guessed progress percentages.",11,MUTED));

        body.addView(sectionTitle("Start learning"));
        addLargeCard(body,"▶  STUDY WITH AMIKI","Lesson + worked examples",v->openStudyActivity("lesson","",""));
        addLargeCard(body,"◇  PRACTICE & QUIZ","Test what you understand",v->openStudyActivity("quiz","",""));
        addLargeCard(body,"▤  BUILD A STUDY PLAN","Plan around my real courses",v->openStudyActivity("plan","",""));
        addLargeCard(body,"⌕  FIND STUDY RESOURCES","Evidence-based materials",v->openStudyActivity("resources","",""));

        body.addView(sectionTitle("My courses"));
        addCourseButton(body);
        if(courses==null||courses.length()==0){
            LinearLayout empty=card();
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.addView(t("Your course list is empty",15,TEXT));
            empty.addView(t("Add the courses you have confirmed. The Study Hub will build around your real course list rather than assuming a curriculum.",12,MUTED));
            body.addView(empty,lp(-1,-2));
        }else{
            for(int i=0;i<courses.length();i++){
                JSONObject course=courses.optJSONObject(i);
                if(course==null)continue;
                String name=course.optString("name",course.optString("title","Unnamed course"));
                String code=course.optString("code","");
                String detail=code.isEmpty()?"Course code not set":"COURSE CODE  •  "+code;
                JSONObject metadata=course.optJSONObject("metadata");
                final JSONObject courseMetadata=metadata==null?new JSONObject():metadata;
                JSONArray studyRecords=courseMetadata.optJSONArray("study_records");
                int studyCount=studyRecords==null?0:studyRecords.length();
                int courseCompleted=0;
                if(studyRecords!=null){
                    for(int j=0;j<studyRecords.length();j++){
                        JSONObject record=studyRecords.optJSONObject(j);
                        if(record!=null&&"completed".equalsIgnoreCase(record.optString("status","")))courseCompleted++;
                    }
                }
                LinearLayout courseCard=card();
                courseCard.setOrientation(LinearLayout.VERTICAL);
                courseCard.setPadding(dp(14),dp(14),dp(14),dp(14));
                courseCard.addView(t("📘  "+name,16,TEXT));
                courseCard.addView(t(detail,11,PURPLE));
                courseCard.addView(t(studyCount+" recorded activities  •  "+courseCompleted+" completed",12,MUTED));
                if(studyRecords!=null&&studyRecords.length()>0){
                    JSONObject latest=studyRecords.optJSONObject(studyRecords.length()-1);
                    if(latest!=null){
                        String topic=latest.optString("topic","").trim();
                        String status=latest.optString("status","recorded").replace('_',' ');
                        if(!topic.isEmpty())courseCard.addView(t("Last saved topic: "+topic+"  ·  "+status,12,TEXT));
                    }
                }
                LinearLayout.LayoutParams cp=lp(-1,-2);
                cp.setMargins(0,dp(7),0,dp(4));
                body.addView(courseCard,cp);
                final String courseCode=code;
                final String courseName=name;
                addLargeCard(body,"▶  STUDY THIS COURSE","Tutor + examples",v->openStudyActivity("lesson",courseCode,courseName));
                addLargeCard(body,"＋  RECORD STUDY","Save a real activity",v->showAddStudyRecordDialog(body,courseCode));
                addLargeCard(body,"✎  EDIT COURSE","Update course details",v->showEditCourseDialog(body,courseCode,courseName,courseMetadata));
            }
        }

        body.addView(sectionTitle("Ask your academic agents"));
        addDirectAgentComposer(body,new String[]{"research","tutor"},"Ask Research or Tutor about a course...");
    }

    private void openStudyActivity(String mode,String courseCode,String courseName){
        final String studyConversationId = "study-" + mode + "-" + java.util.UUID.randomUUID();
        String title="lesson".equals(mode)?"Study Session":"quiz".equals(mode)?"Practice & Quiz":"plan".equals(mode)?"Study Plan":"Study Resources";
        clear("Study • "+title);
        LinearLayout body=lpView();
        Button back=new Button(this);
        back.setText("←  BACK TO STUDY HUB");
        back.setAllCaps(false);
        back.setTextColor(TEXT);
        back.setOnClickListener(v->showStudy());
        body.addView(back,lp(-1,dp(44)));
        body.addView(t("STUDY WORKSPACE",11,PURPLE));
        body.addView(t(title,21,TEXT));
        body.addView(t("This session stays inside Study. Chat, Analysis and Projects remain separate screens.",12,MUTED));
        if(!courseName.isEmpty())body.addView(t("COURSE  •  "+courseName+(courseCode.isEmpty()?"":" ("+courseCode+")"),13,PURPLE));
        LinearLayout form=card();
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(14),dp(14),dp(14),dp(14));
        EditText topic=new EditText(this);
        topic.setHint("Course topic or learning goal");
        topic.setSingleLine(false);
        topic.setMinLines(2);
        topic.setTextColor(TEXT);
        topic.setHintTextColor(MUTED);
        topic.setBackground(bg(CARD_2,10));
        form.addView(topic,lp(-1,dp(78)));
        EditText context=new EditText(this);
        context.setHint("Optional: deadline, difficulty, or what you already know");
        context.setSingleLine(false);
        context.setMinLines(2);
        context.setTextColor(TEXT);
        context.setHintTextColor(MUTED);
        context.setBackground(bg(CARD_2,10));
        form.addView(context,lp(-1,dp(78)));
        LinearLayout answerArea=new LinearLayout(this);
        answerArea.setOrientation(LinearLayout.VERTICAL);
        final String[] latestStudyAnswer=new String[]{""};
        Button begin=new Button(this);
        begin.setText("START "+title.toUpperCase(Locale.ROOT));
        begin.setTextColor(TEXT);
        begin.setOnClickListener(v->{
            String topicValue=topic.getText().toString().trim();
            String contextValue=context.getText().toString().trim();
            if(topicValue.isEmpty()&&!"plan".equals(mode)){
                topic.setError("Enter a course topic or learning goal.");
                return;
            }
            String prompt=buildStudyPrompt(mode,courseCode,courseName,topicValue,contextValue);
            begin.setEnabled(false);
            begin.setText("WORKING…");
            answerArea.removeAllViews();
            answerArea.addView(t("Amiki is preparing your "+title.toLowerCase(Locale.ROOT)+"…",13,MUTED));
            runStudyRequest(mode,prompt,answerArea,begin,title,latestStudyAnswer,studyConversationId);
        });
        form.addView(begin,lp(-1,dp(48)));
        body.addView(form,lp(-1,-2));
        body.addView(sectionTitle("YOUR SESSION"));
        body.addView(answerArea,lp(-1,-2));
        content.addView(body,lp(-1,-2));
    }

    private String buildStudyPrompt(String mode,String courseCode,String courseName,String topic,String extra){
        String course=courseName.isEmpty()?"":("Course: "+courseName+(courseCode.isEmpty()?"":" ("+courseCode+"). ")+" ");
        String details=extra.isEmpty()?"":"Additional context from student: "+extra+"\n";
        if("lesson".equals(mode))return "You are the tutor inside Amiki's dedicated Study screen, not the general Chat. "+course+"Topic/goal: "+topic+"\n"+details+"Teach step by step in a structured lesson: learning objectives, prerequisite check, clear explanation, worked example(s), a short understanding check, then practice questions. Ask a clarifying question only if genuinely necessary. Never invent an official syllabus.";
        if("quiz".equals(mode))return "You are the quiz tutor inside Amiki's dedicated Study screen. "+course+"Topic: "+topic+"\n"+details+"Start a short diagnostic quiz. Ask ONE question only, wait for the student's answer, then mark it, explain the reasoning, and ask the next question. Do not reveal all answers upfront. Do not claim mastery without evidence.";
        if("plan".equals(mode))return "You are the study planner inside Amiki's dedicated Study screen. "+course+"Student goal/topic (if known): "+(topic.isEmpty()?"Not specified":topic)+"\n"+details+"Create a realistic plan only from information supplied. Ask for missing essential information such as deadlines or available time; do not invent courses, dates, or syllabus requirements.";
        return "You are the study-resource guide inside Amiki's dedicated Study screen. "+course+"Topic: "+topic+"\n"+details+"Suggest reliable resource types and well-established sources where appropriate. Clearly distinguish suggestions from resources actually retrieved or verified. Never claim to have browsed or checked a link unless a real search was performed.";
    }

    private void addStudyResponseCard(LinearLayout parent,String title,String output){
        LinearLayout responseCard=card();
        responseCard.setOrientation(LinearLayout.VERTICAL);
        responseCard.setPadding(dp(14),dp(14),dp(14),dp(14));
        responseCard.addView(t(title.toUpperCase(Locale.ROOT)+" • AMIKI",11,PURPLE));
        TextView responseText=t(output,14,TEXT);
        responseText.setTextIsSelectable(true);
        responseCard.addView(responseText,lp(-1,-2));
        LinearLayout.LayoutParams p=lp(-1,-2);
        p.setMargins(0,dp(6),0,dp(6));
        parent.addView(responseCard,p);
    }

    private void runStudyFollowUp(String mode,String title,String[] latestStudyAnswer,String studentMessage,LinearLayout answerArea,EditText input,Button send,String studyConversationId){
        String agent=("plan".equals(mode)||"resources".equals(mode))?"research":"tutor";
        String prompt="Continue the existing interaction inside Amiki's dedicated Study screen. Do not redirect to general Chat. Activity: "+title+". Previous Amiki response:\n"+latestStudyAnswer[0]+"\nStudent's answer or follow-up: "+studentMessage+"\nRespond to the student's latest message, preserve the learning context, and continue the lesson/quiz/plan/resource session. For quizzes, mark the student's answer before asking exactly one next question.";
        new Thread(()->{
            try{
                JSONObject request=new JSONObject();
                request.put("agent",agent);
                request.put("input",prompt);
                request.put("session_id",studyConversationId);
                request.put("conversation_id",studyConversationId);
                JSONObject response=postJson(DIRECT_AGENT,request);
                JSONArray results=response.optJSONArray("results");
                StringBuilder answer=new StringBuilder();
                if(results!=null){
                    for(int i=0;i<results.length();i++){
                        JSONObject item=results.optJSONObject(i);
                        if(item!=null){
                            String value=extractDirectResultText(item);
                            if(!value.isEmpty())answer.append(value).append("\n\n");
                        }
                    }
                }
                if(answer.length()==0)answer.append("No follow-up response was returned. Please try again.");
                final String output=answer.toString().trim();
                handler.post(()->{
                    addStudyResponseCard(answerArea,title,output);
                    latestStudyAnswer[0]=latestStudyAnswer[0]+"\n\nStudent: "+studentMessage+"\n\nAmiki: "+output;
                    input.setText("");
                    send.setEnabled(true);
                    send.setText("CONTINUE THIS STUDY SESSION");
                });
            }catch(Exception e){
                handler.post(()->{
                    send.setEnabled(true);
                    send.setText("RETRY FOLLOW-UP");
                    Toast.makeText(this,"Study follow-up failed. Your current session remains on screen.",Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void runStudyRequest(String mode,String prompt,LinearLayout answerArea,Button begin,String title,String[] latestStudyAnswer,String studyConversationId){
        new Thread(()->{
            try{
                JSONObject request=new JSONObject();
                request.put("agent","plan".equals(mode)||"resources".equals(mode)?"research":"tutor");
                request.put("input",prompt);
                request.put("session_id",studyConversationId);
                request.put("conversation_id",studyConversationId);
                JSONObject response=postJson(DIRECT_AGENT,request);
                JSONArray results=response.optJSONArray("results");
                StringBuilder answer=new StringBuilder();
                if(results!=null){
                    for(int i=0;i<results.length();i++){
                        JSONObject item=results.optJSONObject(i);
                        if(item!=null){
                            String value=extractDirectResultText(item);
                            if(!value.isEmpty())answer.append(value).append("\n\n");
                        }
                    }
                }
                if(answer.length()==0)answer.append("The agent returned no lesson content. Try again or check Diagnostics.");
                final String output=answer.toString().trim();
                handler.post(()->{
                    answerArea.removeAllViews();
                    latestStudyAnswer[0]=output;
                    addStudyResponseCard(answerArea,title,output);
                    EditText followUp=new EditText(this);
                    followUp.setHint("Ask a question or enter your answer here…");
                    followUp.setTextColor(TEXT);
                    followUp.setHintTextColor(MUTED);
                    followUp.setMinLines(2);
                    followUp.setGravity(Gravity.TOP|Gravity.START);
                    followUp.setBackground(bg(CARD_2,10));
                    answerArea.addView(followUp,lp(-1,dp(76)));
                    Button continueButton=new Button(this);
                    continueButton.setText("CONTINUE THIS STUDY SESSION");
                    continueButton.setTextColor(TEXT);
                    continueButton.setOnClickListener(v->{
                        String message=followUp.getText().toString().trim();
                        if(message.isEmpty()){followUp.setError("Enter your answer or follow-up question.");return;}
                        continueButton.setEnabled(false);
                        continueButton.setText("THINKING…");
                        runStudyFollowUp(mode,title,latestStudyAnswer,message,answerArea,followUp,continueButton,studyConversationId);
                    });
                    answerArea.addView(continueButton,lp(-1,dp(46)));
                    begin.setEnabled(true);
                    begin.setText("START A NEW "+title.toUpperCase(Locale.ROOT));
                });
            }catch(Exception e){
                handler.post(()->{
                    answerArea.removeAllViews();
                    answerArea.addView(t("Study request failed. Your session remains here; check backend health in Diagnostics and retry.",13,MUTED));
                    begin.setEnabled(true);
                    begin.setText("RETRY "+title.toUpperCase(Locale.ROOT));
                });
            }
        }).start();
    }

    private LinearLayout studyStat(String label,String value){
        LinearLayout stat=card();
        stat.setOrientation(LinearLayout.VERTICAL);
        stat.setGravity(Gravity.CENTER);
        stat.setPadding(dp(4),dp(8),dp(4),dp(8));
        stat.addView(t(value,20,TEXT));
        stat.addView(t(label,9,MUTED));
        return stat;
    }

    private void addLargeCard(LinearLayout b,String a,String d){LinearLayout c=card();accentCard(c,toolAccent(a));c.setOrientation(LinearLayout.HORIZONTAL);c.setClickable(true);c.addView(t(a,14,TEXT),new LinearLayout.LayoutParams(0,dp(60),1));c.addView(t(d,11,MUTED),new LinearLayout.LayoutParams(dp(125),dp(60)));c.setOnClickListener(v->toolAction(a));LinearLayout.LayoutParams p=lp(-1,dp(72));p.setMargins(0,dp(7),0,0);b.addView(c,p);}
    private void addLargeCard(LinearLayout b,String a,String d,View.OnClickListener listener){
        LinearLayout c=card(); accentCard(c,toolAccent(a)); c.setOrientation(LinearLayout.HORIZONTAL); c.setClickable(true);
        c.addView(t(a,14,TEXT),new LinearLayout.LayoutParams(0,dp(60),1));
        c.addView(t(d,11,MUTED),new LinearLayout.LayoutParams(dp(125),dp(60)));
        c.setOnClickListener(listener);
        LinearLayout.LayoutParams p=lp(-1,dp(72)); p.setMargins(0,dp(7),0,0); b.addView(c,p);
    }

    private void showProjects(){clear("Projects");LinearLayout body=lpView();body.addView(t("Amiki V1",19,TEXT));body.addView(t("Live project state from the connected backend.",13,MUTED));addDirectAgentComposer(body,new String[]{"engineering"},"Ask Engineering directly about the project...");addInteractiveProjectCard(body,"🌸  Amiki V1","Project overview",v -> showProjectOverview());addInteractiveProjectCard(body,"◉  Backend API","Check health",v -> showBackendStatus());addLargeCard(body,"▣  Android App","Current build");addInteractiveProjectCard(body,"◇  Orchestrator","Inspect state",v -> showAnalysis());addInteractiveProjectCard(body,"◌  Diagnostics","Run checks",v -> showDiagnostics());body.addView(sectionTitle("Live State"));
        LinearLayout live=card();
        addMetric(live,"◉","Runtime","LOADING","Backend system state");
        addMetric(live,"◇","Capability","LOADING","Active capability");
        addMetric(live,"⚡","Health","LOADING","Backend health");
        body.addView(live,lp(-1,-2));content.addView(body,lp(-1,-2));
        loadProjectState(live);}

    private LinearLayout buildDrawer() {
        LinearLayout drawerRoot = new LinearLayout(this);
        drawerRoot.setOrientation(LinearLayout.VERTICAL);
        drawerRoot.setBackgroundColor(PANEL);

        // The menu can contain more entries than fit on shorter screens.
        // Keep every destination reachable by scrolling instead of clipping
        // the final rows (including Settings) below the display.
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setFillViewport(true);
        menuScroll.setClipToPadding(false);
        menuScroll.setVerticalScrollBarEnabled(true);

        LinearLayout menuContent = new LinearLayout(this);
        menuContent.setOrientation(LinearLayout.VERTICAL);
        menuContent.setPadding(dp(14), dp(20), dp(14), dp(20));
        menuContent.setClipToPadding(false);

        TextView id = t("🌸  AMIKI\\nChimz\\nDigital companion", 17, TEXT);
        id.setTypeface(null, Typeface.BOLD);
        menuContent.addView(id, lp(-1, dp(92)));

        String[] main = {
            "Chat", "Dashboard", "Memory", "University", "Projects",
            "Analysis", "Database", "Tools", "Cloud", "Device", "Settings"
        };
        for (String s : main) {
            TextView item = t(s, 14, TEXT);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, 0, 0);
            item.setBackground(bg(CARD, 13));
            item.setOnClickListener(x -> {
                closeDrawer();
                if (s.equals("Chat")) showChat();
                else if (s.equals("Dashboard")) showDashboard();
                else if (s.equals("Memory")) showMemory();
                else if (s.equals("University")) showStudy();
                else if (s.equals("Projects")) showProjects();
                else if (s.equals("Tools")) showTools();
                else if (s.equals("Cloud")) showCloud();
                else if (s.equals("Device")) showDevice();
                else if (s.equals("Settings")) showSettings();
                else if (s.equals("Analysis")) showAnalysis();
                else if (s.equals("Database")) showDatabase();
            });
            LinearLayout.LayoutParams params = lp(-1, dp(52));
            params.setMargins(0, 0, 0, dp(7));
            menuContent.addView(item, params);
        }

        menuScroll.addView(menuContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        drawerRoot.addView(menuScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return drawerRoot;
    }
    private void openDrawer(){scrim.setVisibility(View.VISIBLE);drawer.setVisibility(View.VISIBLE);}private void closeDrawer(){scrim.setVisibility(View.GONE);drawer.setVisibility(View.GONE);}

    private void showTools(){clear("Tools");LinearLayout body=lpView();body.addView(t("Connected Tools",18,TEXT));body.addView(t("Only tools with a real action are presented as active.",13,MUTED));addLargeCard(body,"⌘  Engineering Chat","Open engineering chat");addLargeCard(body,"◎  Web Search","Open live browser");addLargeCard(body,"▦  Calculator","Calculate with Amiki");addLargeCard(body,"▤  Notes","Capture a note with Amiki");addLargeCard(body,"▣  Image Analysis","Open attachment UI");addLargeCard(body,"◉  Device Control","Open Android device actions");addLargeCard(body,"☁  Amiki Cloud Command Centre","Inspect Cloud operations and control autonomy");addLargeCard(body,"🛡  Security Agent","Run a read-only security audit");body.addView(sectionTitle("System"));addLargeCard(body,"▱  File Manager","Open workspace UI");addLargeCard(body,"▥  Diagnostics","Run live checks");content.addView(body,lp(-1,-2));}
    private void showCloud() {
        active = 4;
        clear("☁  Amiki Cloud");
        LinearLayout body = lpView();
        body.setPadding(dp(14), dp(8), dp(14), dp(22));
        body.addView(t("LOCAL CLOUD CONTROL PLANE", 11, CYAN));
        body.addView(t(
                "Cloud operations are routed through Amiki's backend. The app never stores or sends the Cloud bearer token.",
                13, MUTED
        ));

        LinearLayout connection = card();
        accentCard(connection, CYAN);
        connection.addView(t("CLOUD CONNECTION", 11, CYAN));
        TextView connectionStatus = t("Checking Cloud readiness…", 14, TEXT);
        connectionStatus.setTag("amiki_cloud_status");
        connectionStatus.setPadding(0, dp(8), 0, 0);
        connection.addView(connectionStatus);
        body.addView(connection, lp(-1, -2));

        LinearLayout jobsList = new LinearLayout(this);
        jobsList.setOrientation(LinearLayout.VERTICAL);
        jobsList.setTag("amiki_cloud_jobs");
        jobsList.addView(t("Loading recent jobs…", 13, MUTED));

        Button refresh = new Button(this);
        refresh.setText("↻  REFRESH STATUS & JOBS");
        refresh.setAllCaps(false);
        refresh.setTextColor(TEXT);
        refresh.setBackground(bg(CARD_2, 12));
        refresh.setOnClickListener(v -> refreshCloud(connectionStatus, jobsList));
        LinearLayout.LayoutParams refreshParams = lp(-1, dp(48));
        refreshParams.setMargins(0, dp(10), 0, 0);
        body.addView(refresh, refreshParams);

        body.addView(sectionTitle("SIMULATED JOBS"));
        body.addView(t(
                "These are deterministic echo simulations for checking the Amiki-to-Cloud request path and persisted job state. They do not run commands or verify external infrastructure.",
                12, MUTED
        ));
        Button healthJob = new Button(this);
        healthJob.setText("Run connectivity echo test");
        healthJob.setAllCaps(false);
        healthJob.setTextColor(TEXT);
        healthJob.setBackground(bg(PURPLE, 12));
        healthJob.setOnClickListener(v -> submitCloudJob("connectivity round-trip", connectionStatus, jobsList));
        LinearLayout.LayoutParams jobParams = lp(-1, dp(48));
        jobParams.setMargins(0, dp(8), 0, 0);
        body.addView(healthJob, jobParams);

        Button metadataJob = new Button(this);
        metadataJob.setText("Run persistence echo test");
        metadataJob.setAllCaps(false);
        metadataJob.setTextColor(TEXT);
        metadataJob.setBackground(bg(CARD_2, 12));
        metadataJob.setOnClickListener(v -> submitCloudJob("persistence round-trip", connectionStatus, jobsList));
        jobParams = lp(-1, dp(48));
        jobParams.setMargins(0, dp(8), 0, 0);
        body.addView(metadataJob, jobParams);

        body.addView(sectionTitle("RECENT JOBS"));
        body.addView(jobsList, lp(-1, -2));
        content.addView(body, lp(-1, -2));
        refreshCloud(connectionStatus, jobsList);
    }

    private void refreshCloud(TextView statusView, LinearLayout jobsView) {
        statusView.setText("Checking Cloud readiness…");
        new Thread(() -> {
            JSONObject state = null;
            JSONObject listing = null;
            String failure = null;
            try {
                state = new JSONObject(getJson(ApiEndpoints.CLOUD_STATUS));
                JSONObject health = state.optJSONObject("health");
                JSONObject readiness = state.optJSONObject("readiness");
                boolean cloudHealthy = health != null
                        && "ok".equals(health.optString("status"))
                        && readiness != null
                        && "ready".equals(readiness.optString("status"));
                if (cloudHealthy && state.optBoolean("jobs_authorized", false)) {
                    listing = new JSONObject(getJson(ApiEndpoints.CLOUD_JOBS));
                }
            } catch (Exception error) {
                failure = "Cloud unavailable. Check the Cloud process and backend environment.";
            }
            final JSONObject resolvedState = state;
            final JSONObject resolvedJobs = listing;
            final String resolvedFailure = failure;
            handler.post(() -> {
                if (resolvedState == null) {
                    statusView.setText(resolvedFailure == null ? "Cloud unavailable." : resolvedFailure);
                    jobsView.removeAllViews();
                    jobsView.addView(t("No job list is available until Cloud is ready.", 13, MUTED));
                    return;
                }
                JSONObject health = resolvedState.optJSONObject("health");
                JSONObject readiness = resolvedState.optJSONObject("readiness");
                boolean cloudHealthy = health != null
                        && "ok".equals(health.optString("status"))
                        && readiness != null
                        && "ready".equals(readiness.optString("status"));
                boolean jobsAuthorized = resolvedState.optBoolean("jobs_authorized", false);
                if (cloudHealthy && jobsAuthorized) {
                    statusView.setText("CONNECTED • Cloud health/readiness passed; backend job authorization is configured.");
                } else if (cloudHealthy) {
                    statusView.setText("CLOUD READY • Job access is disabled. Configure AMIKI_CLOUD_API_TOKEN in both local service environments.");
                } else {
                    statusView.setText("UNAVAILABLE • Cloud health or readiness has not passed.");
                }
                jobsView.removeAllViews();
                if (resolvedJobs == null) {
                    jobsView.addView(t(
                            "Jobs are available after Cloud reports ready and the backend token matches.",
                            13, MUTED
                    ));
                    return;
                }
                JSONArray items = resolvedJobs.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    jobsView.addView(t("No Cloud jobs recorded yet.", 13, MUTED));
                    return;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject job = items.optJSONObject(i);
                    if (job == null) continue;
                    LinearLayout jobCard = card();
                    accentCard(jobCard, "succeeded".equals(job.optString("status"))
                            ? Color.rgb(52, 211, 153) : currentAccent);
                    jobCard.addView(t(
                            job.optString("type", "Cloud job").replace('_', ' ').toUpperCase(Locale.ROOT),
                            12, CYAN
                    ));
                    jobCard.addView(t(
                            job.optString("status", "unknown").toUpperCase(Locale.ROOT),
                            15, TEXT
                    ));
                    String id = job.optString("id", "");
                    if (!id.isEmpty()) {
                        jobCard.addView(t("ID  " + id.substring(0, Math.min(12, id.length())), 10, MUTED));
                    }
                    String resultText = job.isNull("result") ? "" : job.optString("result", "");
                    if (!resultText.isEmpty()) {
                        jobCard.addView(t(resultText, 11, MUTED));
                    }
                    LinearLayout.LayoutParams jobCardParams = lp(-1, -2);
                    jobCardParams.setMargins(0, 0, 0, dp(8));
                    jobsView.addView(jobCard, jobCardParams);
                }
            });
        }).start();
    }

    private void submitCloudJob(String jobType, TextView statusView, LinearLayout jobsView) {
        statusView.setText("Submitting test job to the Cloud background queue…");
        new Thread(() -> {
            String terminalStatus = "timeout";
            try {
                JSONObject request = new JSONObject();
                request.put("message", "Amiki Cloud background-worker test: " + jobType);
                JSONObject created = postJson(ApiEndpoints.CLOUD_JOBS, request);
                String jobId = created.optString("id", "");
                if (jobId.isEmpty()) throw new Exception("Missing Cloud job ID.");
                for (int attempt = 0; attempt < 40; attempt++) {
                    JSONObject current = new JSONObject(getJson(ApiEndpoints.CLOUD_JOBS + "/" + jobId));
                    String state = current.optString("status", "unknown");
                    if ("succeeded".equals(state) || "failed".equals(state) || "cancelled".equals(state)) {
                        terminalStatus = state;
                        break;
                    }
                    Thread.sleep(250L);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                terminalStatus = "interrupted";
            } catch (Exception ignored) {
                terminalStatus = "error";
            }
            final String resultStatus = terminalStatus;
            handler.post(() -> {
                String message = "succeeded".equals(resultStatus)
                        ? "Cloud background worker claimed and completed the test job."
                        : "Background-worker test: " + resultStatus + ". Check worker/autonomy status in Settings → Amiki Cloud.";
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                refreshCloud(statusView, jobsView);
            });
        }, "amiki-cloud-worker-smoke-test").start();
    }

    private void submitCloudGoalPlan(
            EditText titleInput,
            EditText objectiveInput,
            TextView statusView,
            LinearLayout goalsView
    ) {
        if (!titleInput.isEnabled() || !objectiveInput.isEnabled()) return;
        String title = titleInput.getText().toString().trim();
        String objective = objectiveInput.getText().toString().trim();
        if (title.isEmpty()) {
            titleInput.setError("Enter a goal title.");
            titleInput.requestFocus();
            return;
        }
        if (objective.isEmpty()) {
            objectiveInput.setError("Describe the outcome you want Amiki to achieve.");
            objectiveInput.requestFocus();
            return;
        }

        titleInput.setEnabled(false);
        objectiveInput.setEnabled(false);
        statusView.setText("Amiki Core is generating and saving the goal plan…");
        final String requestFingerprint = CloudGoalIdempotencyKey.fingerprint(title, objective);
        new Thread(() -> {
            boolean success = false;
            int taskCount = 0;
            String pendingKey = null;
            try {
                pendingKey = getOrCreateCloudGoalIdempotencyKey(requestFingerprint);
                JSONObject request = new JSONObject();
                request.put("title", title);
                request.put("objective", objective);
                Map<String, String> headers = new HashMap<>();
                headers.put("Idempotency-Key", pendingKey);
                JSONObject created = postJson(ApiEndpoints.CLOUD_GOALS_PLAN, request, headers);
                JSONArray tasks = created.optJSONArray("tasks");
                if (created.optString("id", "").isEmpty()
                        || tasks == null
                        || tasks.length() < 1
                        || tasks.length() > 20) {
                    throw new Exception("Invalid planned goal response.");
                }
                taskCount = tasks.length();
                success = true;
            } catch (Exception ignored) {
                // Keep provider and upstream internals out of the Android UI.
            }
            final boolean saved = success;
            final int plannedTasks = taskCount;
            final String completedKey = pendingKey;
            handler.post(() -> {
                titleInput.setEnabled(true);
                objectiveInput.setEnabled(true);
                if (saved) {
                    if (completedKey != null) {
                        clearCloudGoalIdempotencyKey(requestFingerprint, completedKey);
                    }
                    titleInput.setText("");
                    objectiveInput.setText("");
                    Toast.makeText(this,
                            "Goal saved with " + plannedTasks + " planned task(s).",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            "Goal planning failed. Retry the same goal to safely resume the original submission.",
                            Toast.LENGTH_LONG).show();
                }
                refreshCloudGoals(statusView, goalsView);
            });
        }, "amiki-cloud-goal-plan").start();
    }

    private String getOrCreateCloudGoalIdempotencyKey(String fingerprint) {
        return CloudGoalIdempotencyKey.getOrCreate(cloudGoalIdempotencyStore(), fingerprint);
    }

    private void clearCloudGoalIdempotencyKey(String fingerprint, String key) {
        CloudGoalIdempotencyKey.clearIfMatches(cloudGoalIdempotencyStore(), fingerprint, key);
    }

    private CloudGoalIdempotencyKey.Store cloudGoalIdempotencyStore() {
        SharedPreferences preferences = getSharedPreferences(
                "amiki_cloud_goal_submission",
                MODE_PRIVATE
        );
        return new CloudGoalIdempotencyKey.Store() {
            @Override
            public String fingerprint() {
                return preferences.getString("fingerprint", null);
            }

            @Override
            public String key() {
                return preferences.getString("key", null);
            }

            @Override
            public boolean save(String fingerprint, String key) {
                return preferences.edit()
                        .putString("fingerprint", fingerprint)
                        .putString("key", key)
                        .commit();
            }

            @Override
            public boolean clear() {
                return preferences.edit()
                        .remove("fingerprint")
                        .remove("key")
                        .commit();
            }
        };
    }

    private void refreshCloudGoals(TextView statusView, LinearLayout goalsView) {
        statusView.setText("Loading durable goals…");
        new Thread(() -> {
            JSONObject listing = null;
            try {
                listing = new JSONObject(getJson(ApiEndpoints.CLOUD_GOALS + "?limit=20"));
            } catch (Exception ignored) {
                // Cloud authorization and availability are reported with a stable UI message.
            }
            final JSONObject result = listing;
            handler.post(() -> {
                goalsView.removeAllViews();
                if (result == null) {
                    statusView.setText("Goals unavailable. Check Core, Cloud readiness and owner authorization.");
                    goalsView.addView(t("No goal data is available until the authenticated Cloud proxy responds.", 12, MUTED));
                    return;
                }

                JSONArray items = result.optJSONArray("items");
                if (items == null) {
                    statusView.setText("Goal response is invalid.");
                    goalsView.addView(t("The Cloud proxy returned no goal list.", 12, MUTED));
                    return;
                }
                statusView.setText(items.length() + " durable goal(s) loaded.");
                if (items.length() == 0) {
                    goalsView.addView(t("No goals saved yet. Describe an outcome above to create the first plan.", 12, MUTED));
                    return;
                }

                for (int i = 0; i < Math.min(items.length(), 20); i++) {
                    JSONObject goal = items.optJSONObject(i);
                    if (goal == null) continue;
                    String goalId = goal.optString("id", "");
                    String goalStatus = goal.optString("status", "unknown");
                    LinearLayout goalCard = card();
                    int accent = "active".equals(goalStatus)
                            ? Color.rgb(52, 211, 153)
                            : "paused".equals(goalStatus) ? Color.rgb(251, 191, 36) : currentAccent;
                    accentCard(goalCard, accent);
                    goalCard.addView(t(goal.optString("title", "Untitled goal"), 14, TEXT));
                    goalCard.addView(t(goalStatus.toUpperCase(Locale.ROOT), 10, CYAN));
                    String objective = goal.optString("objective", "");
                    if (objective.length() > 180) {
                        objective = objective.substring(0, 177) + "…";
                    }
                    if (!objective.isEmpty()) goalCard.addView(t(objective, 11, MUTED));

                    boolean validId = true;
                    try {
                        ApiEndpoints.cloudGoalAction(goalId, "pause");
                    } catch (IllegalArgumentException invalidId) {
                        validId = false;
                    }
                    if (validId && "active".equals(goalStatus)) {
                        addSettingsActionButton(goalCard, "Pause Goal",
                                "Stop new claims and revoke active leases",
                                v -> confirmCloudGoalAction(goalId, "pause", statusView, goalsView));
                        addSettingsActionButton(goalCard, "Cancel Goal",
                                "Terminally cancel remaining tasks",
                                v -> confirmCloudGoalAction(goalId, "cancel", statusView, goalsView));
                    } else if (validId && "paused".equals(goalStatus)) {
                        addSettingsActionButton(goalCard, "Resume Goal",
                                "Reactivate after any remaining lease grace",
                                v -> confirmCloudGoalAction(goalId, "resume", statusView, goalsView));
                        addSettingsActionButton(goalCard, "Cancel Goal",
                                "Terminally cancel remaining tasks",
                                v -> confirmCloudGoalAction(goalId, "cancel", statusView, goalsView));
                    } else if ("completed".equals(goalStatus) || "cancelled".equals(goalStatus)) {
                        goalCard.addView(t("TERMINAL  •  No further lifecycle actions", 10, MUTED));
                    } else if (!validId) {
                        goalCard.addView(t("Invalid goal identifier. Controls disabled.", 10, MUTED));
                    }

                    LinearLayout.LayoutParams params = lp(-1, -2);
                    params.setMargins(0, 0, 0, dp(8));
                    goalsView.addView(goalCard, params);
                }
            });
        }, "amiki-cloud-goals-refresh").start();
    }

    private void confirmCloudGoalAction(
            String goalId,
            String action,
            TextView statusView,
            LinearLayout goalsView
    ) {
        String title;
        String message;
        String positive;
        if ("pause".equals(action)) {
            title = "Pause this goal?";
            message = "New task claims will stop and active specialist leases will be revoked. A 120-second grace period prevents overlapping retries.";
            positive = "Pause";
        } else if ("resume".equals(action)) {
            title = "Resume this goal?";
            message = "The goal becomes active. Any remaining lease grace is preserved, and the global emergency stop still blocks execution.";
            positive = "Resume";
        } else if ("cancel".equals(action)) {
            title = "Cancel this goal permanently?";
            message = "Pending and in-progress tasks will be marked cancelled. This goal cannot be resumed.";
            positive = "Cancel goal";
        } else {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Keep goal", null)
                .setPositiveButton(positive, (dialog, which) ->
                        changeCloudGoalStatus(goalId, action, statusView, goalsView))
                .show();
    }

    private void changeCloudGoalStatus(
            String goalId,
            String action,
            TextView statusView,
            LinearLayout goalsView
    ) {
        new Thread(() -> {
            boolean success = false;
            try {
                postJson(ApiEndpoints.cloudGoalAction(goalId, action), new JSONObject());
                success = true;
            } catch (Exception ignored) {
                // The Cloud API returns stable transition errors; never show private details here.
            }
            final boolean changed = success;
            handler.post(() -> {
                String label = "pause".equals(action) ? "paused"
                        : "resume".equals(action) ? "resumed" : "cancelled";
                Toast.makeText(this,
                        changed ? "Goal " + label + "." : "Goal control failed. Refresh and check Cloud authorization.",
                        Toast.LENGTH_LONG).show();
                refreshCloudGoals(statusView, goalsView);
            });
        }, "amiki-cloud-goal-" + action).start();
    }

    private void showSettings() {
        active = 4;
        clear("⚙  Settings");
        LinearLayout body = lpView();
        body.setPadding(dp(14), dp(8), dp(14), dp(22));
        body.addView(t("Loading settings from the connected backend…", 12, MUTED));
        content.addView(body, lp(-1, -2));
        loadSettings((settings, error) -> renderSettingsOverview(body, settings, error));
    }

    private void addSettingSwitch(LinearLayout parent,String title,String detail,String key,boolean def,CompoundButton.OnCheckedChangeListener extra){
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(8),dp(10),dp(8)); accentCard(row,currentAccent);
        LinearLayout textBox=new LinearLayout(this); textBox.setOrientation(LinearLayout.VERTICAL); textBox.setGravity(Gravity.CENTER_VERTICAL);
        textBox.addView(t(title,14,TEXT)); textBox.addView(t(detail,10,MUTED));
        boolean enabled=settingsPrefs.getBoolean(key,def);
        TextView state=t(enabled?"ON":"OFF",10,enabled?Color.rgb(100,230,130):MUTED); state.setGravity(Gravity.CENTER);
        Switch sw=new Switch(this); sw.setChecked(enabled);
        sw.setOnCheckedChangeListener((button,checked)->{
            settingsPrefs.edit().putBoolean(key,checked).apply();
            state.setText(checked?"ON":"OFF"); state.setTextColor(checked?Color.rgb(100,230,130):MUTED);
            if(extra!=null)extra.onCheckedChanged(button,checked);
        });
        row.addView(textBox,new LinearLayout.LayoutParams(0,dp(48),1));
        row.addView(state,new LinearLayout.LayoutParams(dp(34),dp(40)));
        row.addView(sw,new LinearLayout.LayoutParams(dp(50),dp(40)));
        LinearLayout.LayoutParams p=lp(-1,dp(66)); p.setMargins(0,dp(6),0,0); parent.addView(row,p);
    }


    private interface SettingsLoadCallback {
        void onComplete(JSONObject settings, String error);
    }

    private void loadSettings(SettingsLoadCallback callback) {
        new Thread(() -> {
            JSONObject settings = null;
            String error = null;
            try {
                settings = new JSONObject(getJson(ApiEndpoints.SETTINGS));
            } catch (Exception failure) {
                error = failure.getMessage() == null ? "Backend settings are unavailable." : failure.getMessage();
            }
            JSONObject result = settings;
            String failureMessage = error;
            handler.post(() -> callback.onComplete(result, failureMessage));
        }).start();
    }

    private void renderSettingsOverview(LinearLayout body, JSONObject settings, String error) {
        body.removeAllViews();
        JSONObject profile = settings == null ? null : settings.optJSONObject("profile");
        JSONObject model = settings == null ? null : settings.optJSONObject("model");
        JSONObject memory = settings == null ? null : settings.optJSONObject("memory");

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setPadding(dp(15), dp(15), dp(13), dp(15));
        accentCard(identity, Color.rgb(242,184,75));

        TextView avatar = t("✦", 30, PURPLE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(bg(Color.rgb(35, 12, 91), 40));
        identity.addView(avatar, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout identityText = new LinearLayout(this);
        identityText.setOrientation(LinearLayout.VERTICAL);
        identityText.setPadding(dp(13), 0, dp(4), 0);
        String assistantName = profile == null ? "" : profile.optString("assistant_name", "");
        String userName = profile == null ? "" : profile.optString("user_name", "");
        String role = profile == null ? "" : profile.optString("role", "");
        TextView assistant = t(assistantName.isEmpty() ? "Backend unavailable" : assistantName, 17, TEXT);
        assistant.setTypeface(null, Typeface.BOLD);
        identityText.addView(assistant);
        if (!userName.isEmpty()) identityText.addView(t(userName, 15, TEXT));
        if (!role.isEmpty()) identityText.addView(t(role, 11, MUTED));
        if (profile == null) identityText.addView(t("Connect to the Amiki backend to load your profile.", 11, MUTED));
        identity.addView(identityText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = t("›", 30, PURPLE);
        arrow.setGravity(Gravity.CENTER);
        identity.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(54)));
        identity.setClickable(true);
        identity.setFocusable(true);
        identity.setOnClickListener(v -> showProfileSettings());
        LinearLayout.LayoutParams identityParams = lp(-1, -2);
        identityParams.setMargins(0, 0, 0, dp(10));
        body.addView(identity, identityParams);

        if (settings == null) {
            LinearLayout warning = card();
            warning.addView(t("BACKEND CONNECTION", 11, PURPLE));
            warning.addView(t("The settings API did not respond. The screen is not using invented model, memory, or profile values.", 12, MUTED));
            if (error != null && !error.isEmpty()) warning.addView(t(error, 11, MUTED));
            addSettingsActionButton(warning, "Retry", "Reload settings from the backend", v -> showSettings());
            LinearLayout.LayoutParams warningParams = lp(-1, -2);
            warningParams.setMargins(0, 0, 0, dp(10));
            body.addView(warning, warningParams);
        }

        addSettingsRow(body, "☼", "Appearance", "Theme & display", v -> showAppearanceSettings());
        String modelSummary = model == null
                ? "Live model details unavailable"
                : formatSettingLabel(model.optString("provider", "")) + "  •  "
                    + formatSettingLabel(model.optString("name", ""));
        addSettingsRow(body, "✿", "Model Settings", modelSummary, v -> showModelSettings());
        String memorySummary = memory == null
                ? "Retention & limits"
                : (memory.optBoolean("enabled") ? "Conversation memory on" : "Conversation memory off")
                    + "  •  " + memory.optInt("max_messages", 0) + " message limit";
        addSettingsRow(body, "▤", "Memory Settings", memorySummary, v -> showMemorySettings());
        addSettingsRow(body, "☁", "Amiki Cloud", "Local control-plane health & readiness", v -> showCloudSettings());
        addSettingsRow(body, "🧠", "Learning Review", "Review proposed knowledge and correction experiences", v -> startActivity(new Intent(this, LearningReviewActivity.class)));
        addSettingsRow(body, "🛡", "Cipher Console", "Read-only defensive status and registered-root inspection", v -> startActivity(new Intent(this, CipherConsoleActivity.class)));
        addSettingsRow(body, "⬡", "Privacy & Security", "Permissions & data", v -> showPrivacySettings());
        String version = profile == null ? "" : profile.optString("version", "");
        addSettingsRow(body, "◉", "About " + (assistantName.isEmpty() ? "Amiki" : assistantName),
                version.isEmpty() ? "Build and backend information" : "Version " + version,
                v -> showAboutSettings());

        Button logout = new Button(this);
        logout.setText("Log Out");
        logout.setTextColor(TEXT);
        logout.setTextSize(16);
        logout.setAllCaps(false);
        logout.setTypeface(null, Typeface.BOLD);
        logout.setBackground(bg(PURPLE, 20));
        logout.setOnClickListener(v -> confirmSettingsExit());
        LinearLayout.LayoutParams logoutParams = lp(-1, dp(58));
        logoutParams.setMargins(dp(4), dp(18), dp(4), dp(6));
        body.addView(logout, logoutParams);
    }

    private void addSettingsRow(LinearLayout parent, String glyph, String title, String summary,
                                View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(10), dp(12), dp(10));
        row.setBackground(bg(CARD, 17));
        row.setClickable(true);
        row.setFocusable(true);

        TextView icon = t(glyph, 25, PURPLE);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(bg(Color.rgb(24, 15, 58), 15));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(12), 0, dp(4), 0);
        TextView heading = t(title, 15, TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        labels.addView(heading);
        TextView detail = t(summary, 11, MUTED);
        detail.setMaxLines(2);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView arrow = t("›", 28, MUTED);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(42)));
        row.setOnClickListener(listener);
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.setMargins(0, 0, 0, dp(7));
        parent.addView(row, params);
    }

    private void addSettingsBackLink(LinearLayout body) {
        TextView back = t("‹  Settings", 12, PURPLE);
        back.setTypeface(null, Typeface.BOLD);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(0, 0, 0, dp(10));
        back.setClickable(true);
        back.setOnClickListener(v -> showSettings());
        body.addView(back, lp(-1, dp(32)));
    }

    private void addSettingsSection(LinearLayout parent, String text) {
        TextView title = t(text.toUpperCase(Locale.ROOT), 10, PURPLE);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(2), dp(16), 0, dp(6));
        parent.addView(title, lp(-1, -2));
    }

    private void addSettingsValueCard(LinearLayout parent, String label, String value, String detail) {
        LinearLayout valueCard = card();
        valueCard.addView(t(label.toUpperCase(Locale.ROOT), 10, PURPLE));
        TextView mainValue = t(value == null || value.isEmpty() ? "—" : value, 15, TEXT);
        mainValue.setTypeface(null, Typeface.BOLD);
        mainValue.setPadding(0, dp(4), 0, 0);
        valueCard.addView(mainValue);
        if (detail != null && !detail.isEmpty()) {
            TextView description = t(detail, 11, MUTED);
            description.setPadding(0, dp(3), 0, 0);
            valueCard.addView(description);
        }
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.setMargins(0, dp(5), 0, 0);
        parent.addView(valueCard, params);
    }

    private void addSettingsActionButton(LinearLayout parent, String label, String detail,
                                         View.OnClickListener listener) {
        LinearLayout action = card();
        action.setOrientation(LinearLayout.HORIZONTAL);
        action.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = t(label, 14, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        action.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView subtitle = t(detail, 10, MUTED);
        subtitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        subtitle.setMaxLines(2);
        action.addView(subtitle, new LinearLayout.LayoutParams(dp(145), dp(42)));
        action.setClickable(true);
        action.setFocusable(true);
        action.setOnClickListener(listener);
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.setMargins(0, dp(6), 0, 0);
        parent.addView(action, params);
    }

    private String formatSettingLabel(String value) {
        if (value == null || value.trim().isEmpty()) return "Unavailable";
        String normalized = value.trim().replace('-', ' ').replace('_', ' ').replace('/', ' ');
        String[] words = normalized.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.toString();
    }

    private void showCloudSettings() {
        clear("☁  Amiki Cloud Command Centre");
        LinearLayout body = lpView();
        body.setPadding(dp(14), dp(8), dp(14), dp(24));
        content.addView(body, lp(-1, -2));
        addSettingsBackLink(body);

        body.addView(t("ACCC  /  AMIKI CLOUD COMMAND CENTRE", 11, CYAN));
        body.addView(t("One place to inspect Cloud operations, control autonomy and verify job execution.",
                13, MUTED));
        addSettingsValueCard(body, "LOCAL CONTROL PLANE", "127.0.0.1:8001",
                "Android → Amiki Core → authenticated loopback Cloud. Cloud credentials remain on the backend.");

        TextView cloudState = t("Checking Cloud health and readiness…", 13, TEXT);
        TextView workerState = t("Checking background worker…", 13, MUTED);
        TextView autonomyState = t("Checking global execution state…", 13, MUTED);
        TextView monitorState = t("Checking operational monitor…", 13, MUTED);

        addSettingsSection(body, "01  /  INFRASTRUCTURE");
        body.addView(cloudState, lp(-1, -2));
        body.addView(workerState, lp(-1, -2));

        LinearLayout eventsList = new LinearLayout(this);
        eventsList.setOrientation(LinearLayout.VERTICAL);
        eventsList.addView(t("Loading open monitor events…", 12, MUTED));

        addSettingsSection(body, "02  /  AUTONOMY CONTROL");
        body.addView(autonomyState, lp(-1, -2));
        addSettingsActionButton(body, "Emergency Stop",
                "Stop new Cloud execution and revoke active specialist leases",
                v -> confirmCloudAutonomyChange(false, cloudState, workerState,
                        autonomyState, monitorState, eventsList));
        addSettingsActionButton(body, "Resume Autonomy",
                "Allow eligible Cloud jobs and specialist tasks to execute again",
                v -> confirmCloudAutonomyChange(true, cloudState, workerState,
                        autonomyState, monitorState, eventsList));

        addSettingsSection(body, "03  /  JOB COMMAND");
        body.addView(t("Inspect persisted jobs and run a bounded echo job to verify the Core-to-Cloud execution path.",
                12, MUTED));
        TextView jobStatus = t("Loading recent Cloud jobs…", 12, MUTED);
        body.addView(jobStatus, lp(-1, -2));
        LinearLayout jobsList = new LinearLayout(this);
        jobsList.setOrientation(LinearLayout.VERTICAL);
        body.addView(jobsList, lp(-1, -2));
        addSettingsActionButton(body, "Run Safe Connectivity Job",
                "Submit an allowlisted echo task and inspect its persisted result",
                v -> submitCloudJob("ACCC connectivity verification", jobStatus, jobsList));

        addSettingsSection(body, "04  /  GOAL PLANNING & CONTROL");
        body.addView(t(
                "Give Amiki an outcome. Core will generate a bounded specialist plan and save it to Cloud.",
                12, MUTED
        ));

        EditText goalTitle = new EditText(this);
        goalTitle.setSingleLine(true);
        goalTitle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        goalTitle.setTextColor(TEXT);
        goalTitle.setHintTextColor(MUTED);
        goalTitle.setTextSize(13);
        goalTitle.setHint("Goal title (max 160 characters)");
        goalTitle.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(160)
        });
        goalTitle.setPadding(dp(12), dp(8), dp(12), dp(8));
        goalTitle.setBackground(bg(CARD_2, 12));
        LinearLayout.LayoutParams goalTitleParams = lp(-1, dp(48));
        goalTitleParams.setMargins(0, dp(6), 0, 0);
        body.addView(goalTitle, goalTitleParams);

        EditText goalObjective = new EditText(this);
        goalObjective.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        goalObjective.setSingleLine(false);
        goalObjective.setMinLines(3);
        goalObjective.setMaxLines(6);
        goalObjective.setGravity(Gravity.TOP | Gravity.START);
        goalObjective.setTextColor(TEXT);
        goalObjective.setHintTextColor(MUTED);
        goalObjective.setTextSize(13);
        goalObjective.setHint("Describe the outcome and constraints (max 2,000 characters).");
        goalObjective.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(2000)
        });
        goalObjective.setPadding(dp(12), dp(10), dp(12), dp(10));
        goalObjective.setBackground(bg(CARD_2, 12));
        LinearLayout.LayoutParams goalObjectiveParams = lp(-1, dp(112));
        goalObjectiveParams.setMargins(0, dp(7), 0, 0);
        body.addView(goalObjective, goalObjectiveParams);

        TextView goalsStatus = t("Loading durable goals…", 12, MUTED);
        LinearLayout goalsList = new LinearLayout(this);
        goalsList.setOrientation(LinearLayout.VERTICAL);
        addSettingsActionButton(body, "Generate & Save Goal Plan",
                "Amiki Core creates the bounded task plan; Cloud stores it",
                v -> submitCloudGoalPlan(goalTitle, goalObjective, goalsStatus, goalsList));
        addSettingsActionButton(body, "Refresh Goals",
                "Reload saved goals and their lifecycle controls",
                v -> refreshCloudGoals(goalsStatus, goalsList));
        body.addView(goalsStatus, lp(-1, -2));
        body.addView(goalsList, lp(-1, -2));

        addSettingsSection(body, "05  /  OPERATIONAL MONITOR");
        body.addView(monitorState, lp(-1, -2));
        body.addView(eventsList, lp(-1, -2));

        addSettingsActionButton(body, "Refresh Command Centre",
                "Reload infrastructure, worker, autonomy, jobs, goals and monitor events",
                v -> {
                    refreshCloudControlPanel(cloudState, workerState, autonomyState,
                            monitorState, eventsList);
                    refreshCloud(jobStatus, jobsList);
                    refreshCloudGoals(goalsStatus, goalsList);
                });

        refreshCloudControlPanel(cloudState, workerState, autonomyState, monitorState, eventsList);
        refreshCloud(jobStatus, jobsList);
        refreshCloudGoals(goalsStatus, goalsList);
    }

    private void refreshCloudControlPanel(
            TextView cloudState,
            TextView workerState,
            TextView autonomyState,
            TextView monitorState,
            LinearLayout eventsList
    ) {
        cloudState.setText("Checking Cloud health and readiness…");
        workerState.setText("Checking background worker…");
        autonomyState.setText("Checking global execution state…");
        monitorState.setText("Checking operational monitor…");
        new Thread(() -> {
            JSONObject cloud = null;
            JSONObject autonomy = null;
            JSONObject monitor = null;
            JSONObject events = null;
            try { cloud = new JSONObject(getJson(ApiEndpoints.CLOUD_STATUS)); } catch (Exception ignored) {}
            try { autonomy = new JSONObject(getJson(ApiEndpoints.CLOUD_AUTONOMY_STATUS)); } catch (Exception ignored) {}
            try { monitor = new JSONObject(getJson(ApiEndpoints.CLOUD_MONITOR_STATUS)); } catch (Exception ignored) {}
            try {
                events = new JSONObject(getJson(ApiEndpoints.CLOUD_MONITOR_EVENTS + "?state=open&limit=20"));
            } catch (Exception ignored) {}
            final JSONObject cloudResult = cloud;
            final JSONObject autonomyResult = autonomy;
            final JSONObject monitorResult = monitor;
            final JSONObject eventsResult = events;
            handler.post(() -> {
                if (cloudResult == null) {
                    cloudState.setText("Cloud health unavailable. Check the Cloud process and Core proxy.");
                    cloudState.setTextColor(Color.rgb(255, 100, 100));
                    workerState.setText("Background worker: unavailable");
                } else {
                    JSONObject health = cloudResult.optJSONObject("health");
                    JSONObject readiness = cloudResult.optJSONObject("readiness");
                    String healthValue = health == null ? "unknown" : health.optString("status", "unknown");
                    String readyValue = readiness == null ? "unknown" : readiness.optString("status", "unknown");
                    boolean authorized = cloudResult.optBoolean("jobs_authorized", false);
                    boolean healthy = "ok".equals(healthValue) && "ready".equals(readyValue);
                    cloudState.setText("Health: " + healthValue + "  •  Readiness: " + readyValue
                            + "\nAuthenticated Cloud access: " + (authorized ? "ready" : "not configured/authorized"));
                    cloudState.setTextColor(healthy && authorized ? Color.rgb(100, 230, 130)
                            : healthy ? Color.rgb(255, 190, 80) : Color.rgb(255, 100, 100));
                    JSONObject worker = cloudResult.optJSONObject("worker");
                    if (worker == null) {
                        workerState.setText("Background worker: unavailable");
                    } else {
                        workerState.setText("Cloud background worker: "
                                + (worker.optBoolean("running") ? "RUNNING" : "STOPPED")
                                + "  •  Enabled: " + worker.optBoolean("enabled")
                                + "\nQueued: " + worker.optInt("queued_jobs", -1)
                                + "  •  Running jobs: " + worker.optInt("running_jobs", -1));
                    }
                }
                if (autonomyResult == null) {
                    autonomyState.setText("Global autonomy state unavailable. Owner token or Cloud proxy may be missing.");
                    autonomyState.setTextColor(Color.rgb(255, 170, 100));
                } else {
                    boolean enabled = autonomyResult.optBoolean("enabled", false);
                    autonomyState.setText("Global execution: " + (enabled ? "ENABLED" : "STOPPED")
                            + "\nThis switch governs Cloud job execution and specialist task claims.");
                    autonomyState.setTextColor(enabled ? Color.rgb(100, 230, 130) : Color.rgb(255, 190, 80));
                }
                if (monitorResult == null) {
                    monitorState.setText("Monitor status unavailable.");
                } else {
                    JSONObject counts = monitorResult.optJSONObject("counts");
                    monitorState.setText("Monitor: " + (monitorResult.optBoolean("running") ? "RUNNING" : "STOPPED")
                            + "  •  Open: " + (counts == null ? "?" : counts.optInt("open"))
                            + "  •  Acknowledged: " + (counts == null ? "?" : counts.optInt("acknowledged"))
                            + "  •  Resolved: " + (counts == null ? "?" : counts.optInt("resolved")));
                }
                renderCloudMonitorEvents(eventsList, eventsResult, cloudState, workerState,
                        autonomyState, monitorState, eventsList);
            });
        }, "amiki-cloud-control-refresh").start();
    }

    private void renderCloudMonitorEvents(
            LinearLayout eventsList,
            JSONObject events,
            TextView cloudState,
            TextView workerState,
            TextView autonomyState,
            TextView monitorState,
            LinearLayout refreshTarget
    ) {
        eventsList.removeAllViews();
        if (events == null) {
            eventsList.addView(t("Open events unavailable. Refresh after checking Cloud authorization.", 12, MUTED));
            return;
        }
        JSONArray items = events.optJSONArray("items");
        if (items == null || items.length() == 0) {
            eventsList.addView(t("No open monitor events. ✦", 12, MUTED));
            return;
        }
        for (int i = 0; i < Math.min(items.length(), 20); i++) {
            JSONObject event = items.optJSONObject(i);
            if (event == null) continue;
            int eventId = event.optInt("id", 0);
            LinearLayout eventCard = card();
            accentCard(eventCard, "critical".equalsIgnoreCase(event.optString("severity"))
                    ? Color.rgb(255, 100, 100) : currentAccent);
            eventCard.addView(t(event.optString("severity", "unknown").toUpperCase(Locale.ROOT)
                    + "  •  " + event.optString("event_type", "monitor event"), 11, CYAN));
            eventCard.addView(t(event.optString("summary", "No summary supplied."), 12, TEXT));
            eventCard.addView(t("Event #" + eventId + "  •  Occurrences: "
                    + event.optInt("occurrence_count", 1), 10, MUTED));
            if (eventId > 0) {
                addSettingsActionButton(eventCard, "Acknowledge Event",
                        "Record that this event has been reviewed",
                        v -> new AlertDialog.Builder(this)
                                .setTitle("Acknowledge monitor event?")
                                .setMessage("This records review of event #" + eventId + ". It does not resolve the event or disable monitoring.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Acknowledge", (dialog, which) -> acknowledgeCloudEvent(
                                        eventId, cloudState, workerState, autonomyState, monitorState, refreshTarget))
                                .show());
            }
            LinearLayout.LayoutParams params = lp(-1, -2);
            params.setMargins(0, 0, 0, dp(8));
            eventsList.addView(eventCard, params);
        }
    }

    private void confirmCloudAutonomyChange(
            boolean enable,
            TextView cloudState,
            TextView workerState,
            TextView autonomyState,
            TextView monitorState,
            LinearLayout eventsList
    ) {
        String action = enable ? "Resume Cloud execution?" : "Emergency-stop Cloud execution?";
        String detail = enable
                ? "Eligible queued jobs and specialist tasks will be allowed to run again."
                : "New execution will stop and currently issued specialist task leases will be revoked.";
        new AlertDialog.Builder(this)
                .setTitle(action)
                .setMessage(detail + "\n\nThis changes the Cloud-wide execution state.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(enable ? "Resume" : "Stop", (dialog, which) ->
                        changeCloudAutonomy(enable, cloudState, workerState, autonomyState, monitorState, eventsList))
                .show();
    }

    private void changeCloudAutonomy(
            boolean enable,
            TextView cloudState,
            TextView workerState,
            TextView autonomyState,
            TextView monitorState,
            LinearLayout eventsList
    ) {
        new Thread(() -> {
            boolean success = false;
            try {
                postJson(enable ? ApiEndpoints.CLOUD_AUTONOMY_RESUME : ApiEndpoints.CLOUD_AUTONOMY_STOP,
                        new JSONObject());
                success = true;
            } catch (Exception ignored) {}
            final boolean changed = success;
            handler.post(() -> {
                Toast.makeText(this, changed
                        ? (enable ? "Cloud autonomy resumed." : "Cloud emergency stop engaged.")
                        : "Cloud control request failed. Check owner authorization and service health.",
                        Toast.LENGTH_LONG).show();
                refreshCloudControlPanel(cloudState, workerState, autonomyState, monitorState, eventsList);
            });
        }, "amiki-cloud-autonomy-control").start();
    }

    private void acknowledgeCloudEvent(
            int eventId,
            TextView cloudState,
            TextView workerState,
            TextView autonomyState,
            TextView monitorState,
            LinearLayout eventsList
    ) {
        new Thread(() -> {
            boolean success = false;
            try {
                postJson(ApiEndpoints.CLOUD_MONITOR_EVENTS + "/" + eventId + "/acknowledge", new JSONObject());
                success = true;
            } catch (Exception ignored) {}
            final boolean acknowledged = success;
            handler.post(() -> {
                Toast.makeText(this, acknowledged ? "Monitor event acknowledged." : "Event acknowledgement failed.",
                        Toast.LENGTH_LONG).show();
                refreshCloudControlPanel(cloudState, workerState, autonomyState, monitorState, eventsList);
            });
        }, "amiki-cloud-event-acknowledge").start();
    }

    private void showProfileSettings() {
        clear("Profile");
        LinearLayout body = lpView();
        addSettingsBackLink(body);
        body.addView(t("Profile and companion identity are loaded from the connected Amiki configuration.", 12, MUTED));
        content.addView(body, lp(-1, -2));
        loadSettings((settings, error) -> {
            body.removeAllViews();
            addSettingsBackLink(body);
            JSONObject profile = settings == null ? null : settings.optJSONObject("profile");
            if (profile == null) {
                body.addView(t("Profile data is unavailable from the backend.", 13, MUTED));
                addSettingsActionButton(body, "Retry", "Reload backend profile", v -> showProfileSettings());
                return;
            }
            addSettingsValueCard(body, "Assistant", profile.optString("assistant_name", ""), "Name from backend configuration");
            addSettingsValueCard(body, "User", profile.optString("user_name", ""), "Display name from backend configuration");
            addSettingsValueCard(body, "Role", profile.optString("role", ""), "Configured assistant role");
            addSettingsValueCard(body, "Purpose", profile.optString("purpose", ""), "Configured companion purpose");
            addSettingsValueCard(body, "Backend version", profile.optString("version", ""), "Version reported by backend configuration");
            addSettingsActionButton(body, "Refresh Profile", "Fetch current values", v -> showProfileSettings());
        });
    }

    private void showAppearanceSettings() {
        clear("Appearance");
        LinearLayout body = lpView();
        addSettingsBackLink(body);
        body.addView(t("Theme & display", 17, TEXT));
        body.addView(t("Choose Amiki's appearance. Your choice is saved on this device; System uses Android dynamic colours where available.", 12, MUTED));
        addSettingsSection(body, "Appearance");
        String selectedMode = settingsPrefs.getString(AmikiTheme.PREF_KEY, AmikiTheme.MODE_DARK);
        addThemeOption(body, "Midnight Scholar", "Dark · navy, violet and teal", AmikiTheme.MODE_DARK, selectedMode);
        addThemeOption(body, "Calm Focus", "Light · warm paper and sage", AmikiTheme.MODE_LIGHT, selectedMode);
        addThemeOption(body, "System", "Follow device appearance; dynamic colours on Android 12+", AmikiTheme.MODE_SYSTEM, selectedMode);
        addSettingsSection(body, "Text size");
        float selectedScale = settingsPrefs.getFloat("font_scale", 1.0f);
        addTextScaleOption(body, "Compact", 0.90f, selectedScale);
        addTextScaleOption(body, "Default", 1.00f, selectedScale);
        addTextScaleOption(body, "Large", 1.10f, selectedScale);
        addSettingsSection(body, "Display behavior");
        addSettingSwitch(body, "Keep screen awake",
                "Prevent the display from sleeping while Amiki is open.",
                "keep_screen_awake", false,
                (button, checked) -> {
                    if (checked) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
        addSettingSwitch(body, "Read replies aloud",
                "Use the device speech engine for new Amiki replies.",
                "amiki_tts", false,
                (button, checked) -> {
                    if (!checked) {
                        ttsSpeaking = false;
                        if (amikiTts != null) amikiTts.stop();
                    }
                });
        content.addView(body, lp(-1, -2));
    }

    private void addThemeOption(LinearLayout parent, String title, String detail,
                                String mode, String selectedMode) {
        boolean selected = mode.equals(selectedMode);
        LinearLayout option = new LinearLayout(this);
        option.setOrientation(LinearLayout.VERTICAL);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(dp(13), dp(10), dp(12), dp(10));
        option.setBackground(bg(selected ? (isLightPalette() ? 0xFFEAE7F4 : 0xFF303052) : CARD, 14));
        TextView heading = t((selected ? "✓  " : "") + title, 14, selected ? PURPLE : TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        option.addView(heading, lp(-1, -2));
        TextView description = t(detail, 11, MUTED);
        description.setPadding(0, dp(3), 0, 0);
        option.addView(description, lp(-1, -2));
        option.setClickable(true);
        option.setFocusable(true);
        option.setContentDescription(title + ". " + detail + (selected ? ". Selected" : ". Select theme"));
        option.setOnClickListener(v -> {
            settingsPrefs.edit().putString(AmikiTheme.PREF_KEY, mode).apply();
            recreate();
        });
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.setMargins(0, dp(4), 0, dp(2));
        parent.addView(option, params);
    }

    private boolean isLightPalette() {
        return (BG & 0x00FFFFFF) == 0x00F6F4EF;
    }

    private void addTextScaleOption(LinearLayout parent, String label, float scale, float selectedScale) {
        boolean selected = Math.abs(scale - selectedScale) < 0.01f;
        LinearLayout option = new LinearLayout(this);
        option.setOrientation(LinearLayout.HORIZONTAL);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(dp(13), dp(5), dp(12), dp(5));
        option.setBackground(bg(selected ? Color.rgb(32, 15, 75) : CARD, 14));
        TextView title = t(label, 14, TEXT);
        option.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView status = t(selected ? "✓  Selected" : "Select", 11, selected ? PURPLE : MUTED);
        option.addView(status);
        option.setClickable(true);
        option.setFocusable(true);
        option.setOnClickListener(v -> {
            settingsPrefs.edit().putFloat("font_scale", scale).apply();
            showAppearanceSettings();
        });
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.setMargins(0, dp(4), 0, dp(2));
        parent.addView(option, params);
    }

    private void showModelSettings() {
        clear("Model Settings");
        LinearLayout body = lpView();
        addSettingsBackLink(body);
        body.addView(t("Loading live model configuration…", 12, MUTED));
        content.addView(body, lp(-1, -2));
        loadSettings((settings, error) -> renderModelSettings(body, settings, error));
    }

    private void renderModelSettings(LinearLayout body, JSONObject settings, String error) {
        body.removeAllViews();
        addSettingsBackLink(body);
        JSONObject model = settings == null ? null : settings.optJSONObject("model");
        if (model == null) {
            body.addView(t("Model settings could not be loaded from the backend.", 13, MUTED));
            if (error != null && !error.isEmpty()) body.addView(t(error, 11, MUTED));
            addSettingsActionButton(body, "Retry", "Reload backend model settings", v -> showModelSettings());
            return;
        }

        String modelId = model.optString("name", "");
        String provider = model.optString("provider", "");
        double temperature = model.optDouble("temperature", Double.NaN);
        addSettingsValueCard(body, "Configured provider", formatSettingLabel(provider),
                "Provider identifier read from backend config");
        addSettingsValueCard(body, "Active model", formatSettingLabel(modelId),
                "Model identifier: " + (modelId.isEmpty() ? "Unavailable" : modelId));
        addSettingsValueCard(body, "Temperature",
                Double.isNaN(temperature) ? "Unavailable" : String.format(Locale.ROOT, "%.2f", temperature),
                "Generation temperature. Valid range: 0.00–2.00");

        addSettingsSection(body, "Provider readiness");
        JSONObject providers = settings.optJSONObject("providers");
        if (providers != null) {
            java.util.Iterator<String> keys = providers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject status = providers.optJSONObject(key);
                boolean configured = status != null && status.optBoolean("configured", false);
                addSettingsValueCard(body, formatSettingLabel(key),
                        configured ? "Configured" : "Not configured",
                        "Credential status only; secret values are never returned to the app");
            }
        }

        addSettingsSection(body, "Actions");
        addSettingsActionButton(body, "Change Model ID", "Apply to the running backend",
                v -> showModelEditDialog(modelId));
        addSettingsActionButton(body, "Adjust Temperature", "Choose a value from 0 to 2",
                v -> showTemperatureEditDialog(temperature));
    }

    private void showModelEditDialog(String currentModel) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Model identifier from your provider");
        input.setText(currentModel);
        input.setSelection(input.length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Change Model ID")
                .setMessage("The backend validates and persists the identifier. Use a model supported by the configured provider.")
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
                input.setError("Enter a valid model identifier (1–128 letters, digits, . _ / or -).");
                return;
            }
            JSONObject request = new JSONObject();
            try {
                request.put("model", value);
            } catch (Exception impossible) {
                input.setError("The model identifier could not be prepared.");
                return;
            }
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setEnabled(false);
            new Thread(() -> {
                try {
                    putJson(ApiEndpoints.MODEL_SETTINGS, request);
                    handler.post(() -> {
                        dialog.dismiss();
                        Toast.makeText(this, "Model identifier saved to the backend.", Toast.LENGTH_SHORT).show();
                        showModelSettings();
                    });
                } catch (Exception failure) {
                    handler.post(() -> {
                        save.setEnabled(true);
                        Toast.makeText(this, "Model settings were not saved: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }));
        dialog.show();
    }

    private void showTemperatureEditDialog(double currentTemperature) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (!Double.isNaN(currentTemperature)) input.setText(String.valueOf(currentTemperature));
        input.setHint("0.0 to 2.0");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Adjust Temperature")
                .setMessage("Lower values are more deterministic. The backend accepts values from 0.0 through 2.0.")
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double value;
            try {
                value = Double.parseDouble(input.getText().toString().trim());
            } catch (NumberFormatException invalid) {
                input.setError("Enter a number between 0 and 2.");
                return;
            }
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0 || value > 2.0) {
                input.setError("Enter a number between 0 and 2.");
                return;
            }
            JSONObject request = new JSONObject();
            try {
                request.put("temperature", value);
            } catch (Exception impossible) {
                input.setError("The temperature could not be prepared.");
                return;
            }
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setEnabled(false);
            new Thread(() -> {
                try {
                    putJson(ApiEndpoints.MODEL_SETTINGS, request);
                    handler.post(() -> {
                        dialog.dismiss();
                        Toast.makeText(this, "Temperature saved to the backend.", Toast.LENGTH_SHORT).show();
                        showModelSettings();
                    });
                } catch (Exception failure) {
                    handler.post(() -> {
                        save.setEnabled(true);
                        Toast.makeText(this, "Model settings were not saved: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }));
        dialog.show();
    }

    private void showMemorySettings() {
        clear("Memory Settings");
        LinearLayout body = lpView();
        addSettingsBackLink(body);
        body.addView(t("Loading retention, limits and live storage counts…", 12, MUTED));
        content.addView(body, lp(-1, -2));
        new Thread(() -> {
            JSONObject settings = null;
            JSONObject database = null;
            String error = null;
            try {
                settings = new JSONObject(getJson(ApiEndpoints.SETTINGS));
                database = new JSONObject(getJson(DATABASE));
            } catch (Exception failure) {
                error = failure.getMessage() == null ? "Memory settings are unavailable." : failure.getMessage();
            }
            JSONObject loadedSettings = settings;
            JSONObject loadedDatabase = database;
            String failureMessage = error;
            handler.post(() -> renderMemorySettings(body, loadedSettings, loadedDatabase, failureMessage));
        }).start();
    }

    private void renderMemorySettings(LinearLayout body, JSONObject settings, JSONObject database, String error) {
        body.removeAllViews();
        addSettingsBackLink(body);
        JSONObject memory = settings == null ? null : settings.optJSONObject("memory");
        if (memory == null) {
            body.addView(t("Retention settings could not be loaded from the backend.", 13, MUTED));
            if (error != null && !error.isEmpty()) body.addView(t(error, 11, MUTED));
            addSettingsActionButton(body, "Retry", "Reload backend memory settings", v -> showMemorySettings());
            return;
        }

        boolean enabled = memory.optBoolean("enabled", true);
        addMemoryEnabledSwitch(body, enabled);
        addSettingsValueCard(body, "Conversation retention limit",
                String.valueOf(memory.optInt("max_messages", 0)) + " messages",
                "Maximum current-session messages retained by the backend");
        JSONObject persistent = database == null ? null : database.optJSONObject("persistent_memory");
        JSONObject currentSession = database == null ? null : database.optJSONObject("current_session");
        addSettingsValueCard(body, "Current session",
                currentSession == null ? "Unavailable" : String.valueOf(currentSession.optInt("message_count", 0)) + " messages",
                currentSession == null ? "Database status did not provide this count." : currentSession.optString("status", "Live storage status"));
        addSettingsValueCard(body, "Persistent memory",
                persistent == null ? "Unavailable" : String.valueOf(persistent.optInt("count", 0)) + " stored facts",
                persistent == null ? "Database status did not provide this count." : persistent.optString("status", "Live storage status"));

        addSettingsSection(body, "Retention");
        addSettingsActionButton(body, "Change Legacy Context Limit", "Set the legacy global prompt-context limit from 1 to 200 messages",
                v -> showRetentionEditDialog(memory.optInt("max_messages", 20)));
        addSettingsSection(body, "Data controls");
        addSettingsActionButton(body, "Clear Legacy Session Context", "Delete only the old global prompt context; saved conversations remain intact",
                v -> confirmClearMemory("Legacy Session Context", ApiEndpoints.MEMORY + "/current-session", true));
        addSettingsActionButton(body, "Clear Persistent Memory", "Permanently delete stored long-term facts",
                v -> confirmClearMemory("Persistent Memory", ApiEndpoints.MEMORY + "/persistent", false));
        if (database == null) {
            body.addView(t("Live database status is unavailable; retry to refresh the counts.", 11, MUTED));
            addSettingsActionButton(body, "Refresh Storage Status", "Retry live database request", v -> showMemorySettings());
        }
    }

    private void addMemoryEnabledSwitch(LinearLayout parent, boolean enabled) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(8), dp(10), dp(8));
        row.setBackground(bg(CARD, 15));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(t("Conversation Memory", 14, TEXT));
        labels.addView(t("Legacy session context setting. New chat transcripts are stored separately.", 10, MUTED));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(enabled);
        row.addView(toggle);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            button.setEnabled(false);
            JSONObject request = new JSONObject();
            try {
                request.put("enabled", checked);
            } catch (Exception impossible) {
                Toast.makeText(this, "Memory setting could not be prepared.", Toast.LENGTH_SHORT).show();
                showMemorySettings();
                return;
            }
            new Thread(() -> {
                try {
                    putJson(ApiEndpoints.MEMORY_SETTINGS, request);
                    handler.post(() -> {
                        Toast.makeText(this, "Conversation memory updated.", Toast.LENGTH_SHORT).show();
                        showMemorySettings();
                    });
                } catch (Exception failure) {
                    handler.post(() -> {
                        Toast.makeText(this, "Memory setting was not saved: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                        showMemorySettings();
                    });
                }
            }).start();
        });
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.setMargins(0, dp(5), 0, dp(4));
        parent.addView(row, params);
    }

    private void showRetentionEditDialog(int currentLimit) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(currentLimit));
        input.setSelection(input.length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Legacy Session Context Retention")
                .setMessage("Choose how many legacy global prompt-context messages the backend retains (1–200). New conversation transcripts are stored separately.")
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int limit;
            try {
                limit = Integer.parseInt(input.getText().toString().trim());
            } catch (NumberFormatException invalid) {
                input.setError("Enter a whole number from 1 to 200.");
                return;
            }
            if (limit < 1 || limit > 200) {
                input.setError("Enter a whole number from 1 to 200.");
                return;
            }
            JSONObject request = new JSONObject();
            try {
                request.put("max_messages", limit);
            } catch (Exception impossible) {
                input.setError("The retention limit could not be prepared.");
                return;
            }
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setEnabled(false);
            new Thread(() -> {
                try {
                    putJson(ApiEndpoints.MEMORY_SETTINGS, request);
                    handler.post(() -> {
                        dialog.dismiss();
                        Toast.makeText(this, "Retention limit saved to the backend.", Toast.LENGTH_SHORT).show();
                        showMemorySettings();
                    });
                } catch (Exception failure) {
                    handler.post(() -> {
                        save.setEnabled(true);
                        Toast.makeText(this, "Retention limit was not saved: " + failure.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }));
        dialog.show();
    }

    private void confirmClearMemory(String title, String endpoint, boolean clearLocalTranscript) {
        new AlertDialog.Builder(this)
                .setTitle("Clear " + title + "?")
                .setMessage(clearLocalTranscript
                        ? "This clears only the legacy backend session context. Your saved conversations and their transcripts remain intact."
                        : "This permanently deletes all stored long-term memory facts from the backend. Conversation transcripts are stored separately.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CLEAR", (dialog, which) -> new Thread(() -> {
                    HttpURLConnection connection = null;
                    try {
                        connection = ApiConnection.open(this, endpoint);
                        connection.setRequestMethod("DELETE");
                        connection.setConnectTimeout(10000);
                        connection.setReadTimeout(15000);
                        int code = connection.getResponseCode();
                        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                        handler.post(() -> {
                            Toast.makeText(this, title + " cleared.", Toast.LENGTH_SHORT).show();
                            showMemorySettings();
                        });
                    } catch (Exception failure) {
                        handler.post(() -> Toast.makeText(this, "Could not clear " + title + ": " + failure.getMessage(), Toast.LENGTH_LONG).show());
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                }).start())
                .show();
    }

    private void showPrivacySettings() {
        clear("Privacy & Security");
        LinearLayout body = lpView();
        addSettingsBackLink(body);
        body.addView(t("Loading live permissions and backend security state…", 12, MUTED));
        content.addView(body, lp(-1, -2));
        new Thread(() -> {
            JSONObject settings = null;
            JSONObject system = null;
            String error = null;
            try {
                settings = new JSONObject(getJson(ApiEndpoints.SETTINGS));
                system = new JSONObject(getJson(SYSTEM_STATE));
            } catch (Exception failure) {
                error = failure.getMessage() == null ? "Security status is unavailable." : failure.getMessage();
            }
            JSONObject loadedSettings = settings;
            JSONObject loadedSystem = system;
            String failureMessage = error;
            handler.post(() -> renderPrivacySettings(body, loadedSettings, loadedSystem, failureMessage));
        }).start();
    }

    private void renderPrivacySettings(LinearLayout body, JSONObject settings, JSONObject system, String error) {
        body.removeAllViews();
        addSettingsBackLink(body);
        if (settings == null || system == null) {
            body.addView(t("Security information could not be verified from the backend.", 13, MUTED));
            if (error != null && !error.isEmpty()) body.addView(t(error, 11, MUTED));
            addSettingsSection(body, "Owner authentication");
            addSettingsActionButton(body, "Owner Security",
                    "Enroll this device and create a UOSA owner session",
                    v -> startActivity(new Intent(this, OwnerSecurityActivity.class)));
            addSettingsActionButton(body, "Retry", "Reload privacy and security state", v -> showPrivacySettings());
            return;
        }

        JSONObject runtime = system.optJSONObject("runtime");
        JSONObject device = system.optJSONObject("device");
        JSONObject providers = settings.optJSONObject("providers");
        addSettingsSection(body, "Owner authentication");
        addSettingsActionButton(body, "Owner Security", "Inspect strong biometric capability and device signing-key status",
                v -> startActivity(new Intent(this, OwnerSecurityActivity.class)));
        addSettingsSection(body, "Connection");
        addSettingsValueCard(body, "Amiki API",
                runtime == null ? "Unknown" : formatSettingLabel(runtime.optString("api_status", "unknown")),
                "Status reported by the live system-state endpoint");
        addSettingsValueCard(body, "Device bridge",
                device == null ? "Unknown" : formatSettingLabel(device.optString("status", "unknown")),
                "Backend-reported availability; Android actions may still require confirmation");
        addSettingsSection(body, "Permissions");
        boolean microphoneGranted = android.os.Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        addSettingsValueCard(body, "Microphone",
                microphoneGranted ? "Granted" : "Not granted",
                "Used when you explicitly start voice input");
        boolean locationGranted = LocationContextProvider.hasPermission(this);
        addSettingsValueCard(body, "Location permission",
                locationGranted ? "Granted" : "Not granted",
                "Used when you opt in to share location for direct location questions or safety assistance");
        addSettingSwitch(body, "Share device location with Amiki",
                "Attach a fresh, approximate location when you ask where you are or request urgent safety help",
                "share_location_for_safety", false, (button, checked) -> {
                    if (checked && !LocationContextProvider.hasPermission(this)) {
                        settingsPrefs.edit().putBoolean("share_location_for_safety", false).apply();
                        requestPermissions(new String[] {
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        }, LOCATION_PERMISSION);
                    }
                });
        addSettingsValueCard(body, "Android permission handling", "System-controlled",
                "Amiki requests Android runtime permission only when a feature needs it.");
        addSettingsSection(body, "AI data routing");
        if (providers != null) {
            java.util.Iterator<String> keys = providers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject status = providers.optJSONObject(key);
                boolean configured = status != null && status.optBoolean("configured", false);
                addSettingsValueCard(body, formatSettingLabel(key),
                        configured ? "Configured" : "Not configured",
                        "Credential state only; no secret is displayed.");
            }
        }
        addSettingsValueCard(body, "Prompt privacy", "Review before sending sensitive data",
                "Chat prompts and attachments may be sent to the configured AI provider to generate a response.");
        addSettingsActionButton(body, "Manage Memory", "View retention and data deletion controls",
                v -> showMemorySettings());
        addSettingsActionButton(body, "Run Diagnostics", "Inspect live backend health and configuration",
                v -> showDiagnostics());
    }

    private void showAboutSettings() {
        clear("About Amiki");
        LinearLayout body = lpView();
        addSettingsBackLink(body);
        body.addView(t("Loading version and installation information…", 12, MUTED));
        content.addView(body, lp(-1, -2));
        loadSettings((settings, error) -> {
            body.removeAllViews();
            addSettingsBackLink(body);
            JSONObject profile = settings == null ? null : settings.optJSONObject("profile");
            if (profile == null) {
                body.addView(t("Backend version information is unavailable.", 13, MUTED));
                if (error != null && !error.isEmpty()) body.addView(t(error, 11, MUTED));
                addSettingsActionButton(body, "Retry", "Reload version information", v -> showAboutSettings());
            } else {
                addSettingsValueCard(body, "Assistant", profile.optString("assistant_name", ""),
                        profile.optString("role", ""));
                addSettingsValueCard(body, "Backend version", profile.optString("version", ""),
                        "Reported by the live backend configuration");
            }

            try {
                android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                long versionCode = android.os.Build.VERSION.SDK_INT >= 28
                        ? info.getLongVersionCode() : info.versionCode;
                addSettingsSection(body, "Android installation");
                addSettingsValueCard(body, "Package", getPackageName(), "Identifier read from PackageManager");
                addSettingsValueCard(body, "App build", String.valueOf(info.versionName) + " (" + versionCode + ")",
                        "Version installed on this device");
                addSettingsValueCard(body, "Android version",
                        android.os.Build.VERSION.RELEASE + " • API " + android.os.Build.VERSION.SDK_INT,
                        "Runtime information from Android");
            } catch (Exception failure) {
                addSettingsValueCard(body, "Android installation", "Unavailable",
                        "PackageManager did not return version details");
            }
            addSettingsActionButton(body, "Open Diagnostics", "Inspect the live backend", v -> showDiagnostics());
        });
    }

    private void confirmSettingsExit() {
        new AlertDialog.Builder(this)
                .setTitle("Log out?")
                .setMessage("This installation uses a local companion session rather than account sign-in. Exiting closes Amiki; backend settings and memories are not deleted.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("EXIT APP", (dialog, which) -> {
                    chatDraft = "";
                    selectedAttachments.clear();
                    finishAffinity();
                })
                .show();
    }

    private void showSecurityAgent(){
        clear("Security Agent");
        LinearLayout body=lpView();
        body.addView(t("🛡  Security Agent",18,TEXT));
        body.addView(t(
                "Read-only security review of registered agent/tool boundaries and bounded source patterns. Findings are advisory; a clean scan is not proof of security.",
                13,MUTED
        ));
        body.addView(t(
                "Start with: Audit Amiki's security posture. The agent does not execute device actions or modify files.",
                12,MUTED
        ));
        addDirectAgentComposer(
                body,
                new String[]{"security"},
                "Ask Security Agent to audit Amiki..."
        );
        content.addView(body,lp(-1,-2));
    }

    private void showDevice(){
        clear("Device");
        LinearLayout body=lpView();
        body.addView(t("📱  Device Control",18,TEXT));
        body.addView(t("Bounded Android actions. Amiki plans the action; Android performs it.",13,MUTED));

        LinearLayout capabilityCard=card();
        TextView capabilityHeading=t("UI-INDEPENDENT DEVICE ADAPTER • CHECKING",14,TEXT);
        capabilityHeading.setTypeface(null,Typeface.BOLD);
        TextView capabilityDetails=t("Reading detected Termux:API command availability…",12,MUTED);
        TextView capabilityCaveat=t("Command detection does not prove Android permissions or successful device execution.",11,MUTED);
        capabilityCard.addView(capabilityHeading);
        capabilityCard.addView(capabilityDetails);
        capabilityCard.addView(capabilityCaveat);
        LinearLayout.LayoutParams capabilityParams=lp(-1,-2);
        capabilityParams.setMargins(0,dp(10),0,dp(10));
        body.addView(capabilityCard,capabilityParams);

        new Thread(()->{
            try{
                JSONObject capabilities=new JSONObject(getJson(ApiEndpoints.DEVICE_CAPABILITIES));
                JSONObject readOnly=capabilities.optJSONObject("backend_read_only_execution");
                JSONObject readOnlyActions=readOnly==null?null:readOnly.optJSONObject("actions");
                JSONObject approvedActions=capabilities.optJSONObject("backend_approved_execution");
                String readOnlyNames=availableDeviceActions(readOnlyActions);
                String approvedNames=availableDeviceActions(approvedActions);
                boolean confirmation=capabilities.optBoolean("device_confirmation_available",false);
                String headline=confirmation
                        ? "CONFIRMATION PROMPT AVAILABLE"
                        : "CONFIRMATION PROMPT UNAVAILABLE";
                String details="Read-only: "+readOnlyNames+"\nConfirmed actions: "+approvedNames;
                String caveat=confirmation
                        ? "Each interactive action still requires explicit Termux confirmation. Availability is not an execution test."
                        : "Interactive actions are disabled until Termux:API confirmation is available. Read-only status is shown separately.";
                handler.post(()->{
                    capabilityHeading.setText(headline);
                    capabilityHeading.setTextColor(confirmation?Color.rgb(100,230,130):Color.rgb(255,170,80));
                    capabilityDetails.setText(details);
                    capabilityCaveat.setText(caveat);
                });
            }catch(Exception ignored){
                handler.post(()->{
                    capabilityHeading.setText("DEVICE CAPABILITIES UNAVAILABLE");
                    capabilityHeading.setTextColor(Color.rgb(255,100,100));
                    capabilityDetails.setText("The backend capability endpoint could not be reached. Open Diagnostics for safe setup guidance.");
                    capabilityCaveat.setText("No device capability or execution success is assumed.");
                });
            }
        }).start();

        addLargeCard(body,"📦  Installed Apps","Discover and open apps",v ->
                startActivity(new Intent(this, InstalledAppsActivity.class)));
        addLargeCard(body,"♿  Accessibility Operator Setup","Explicitly enable Amiki in Android Accessibility settings",v ->
                showAccessibilityOperatorSetup());
        addLargeCard(body,"◉  Arm One-Task UI Observation","Observe foreground app UI only while a task is armed",v ->
                armAccessibilityObservation());
        addLargeCard(body,"■  STOP UI Observation","Immediately stop and clear the in-memory snapshot",v -> {
            AccessibilityOperatorService.stopObservation();
            Toast.makeText(this,"UI observation stopped.",Toast.LENGTH_SHORT).show();
        });
        addLargeCard(body,"🌐  Open Browser","Ask the device agent to open a safe URL");
        addLargeCard(body,"🔊  Speak Test","Use Amiki TTS");
        addLargeCard(body,"↗  Share Test","Open Android share sheet");
        addLargeCard(body,"🔋  Battery State","View live battery telemetry",v -> showBatteryState());
        addLargeCard(body,"⚙  Device State","Read Android version and API");
        addLargeCard(body,"⚙  Android Settings","Open device settings");
        content.addView(body,lp(-1,-2));
    }

    private boolean isAccessibilityOperatorEnabled() {
        String enabled = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String serviceName = AccessibilityOperatorService.class.getName().toLowerCase(Locale.ROOT);
        return enabled.toLowerCase(Locale.ROOT).contains(serviceName);
    }

    private void showAccessibilityOperatorSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Enable Amiki UI Operator")
                .setMessage("Amiki needs your explicit permission to inspect the foreground app's accessible interface during a task you start. In this observation-only stage it does not tap, type, scroll, monitor continuously, save screen content, or upload it. You can disable the service at any time in Android Accessibility settings.\n\nOnly enable this if you understand and want this capability.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open Android Settings", (dialog, which) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception failure) {
                        Toast.makeText(this, "Android Accessibility settings could not be opened.", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void armAccessibilityObservation() {
        if (!isAccessibilityOperatorEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Accessibility permission required")
                    .setMessage("Enable Amiki UI Operator in Android Accessibility settings first. Observation remains off until you explicitly arm a task here.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (dialog, which) -> showAccessibilityOperatorSetup())
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Arm one-task observation?")
                .setMessage("Amiki will temporarily inspect the foreground app's accessible UI after you leave this screen. No taps or other actions will be performed. Press STOP in Amiki to end observation. The captured snapshot stays in memory only.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Arm observation", (dialog, which) -> {
                    AccessibilityOperatorService.armObservation(
                            new DeviceTaskController(UUID.randomUUID().toString()));
                    Toast.makeText(this, "One-task UI observation armed. Open the target app, then return to Amiki and press STOP.", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void showBatteryState(){
        clear("Battery State");
        LinearLayout body=lpView();
        body.addView(t("🔋  Battery Telemetry",19,TEXT));
        body.addView(t("Live, read-only readings from Termux:API. Values refresh on demand.",12,MUTED));

        LinearLayout readings=card();
        readings.setOrientation(LinearLayout.VERTICAL);
        readings.addView(t("Reading battery sensors…",13,MUTED));
        body.addView(readings,lp(-1,-2));
        addLargeCard(body,"↻  Refresh Battery","Query current readings",v -> showBatteryState());
        content.addView(body,lp(-1,-2));

        new Thread(()->{
            try{
                JSONObject response=new JSONObject(getJson(ApiEndpoints.DEVICE_STATE));
                JSONObject battery=response.optJSONObject("battery");
                String responseStatus=response.optString("status","unknown");
                String reason=response.optString("reason","Battery readings are unavailable.");
                handler.post(()->{
                    readings.removeAllViews();
                    if(battery==null){
                        readings.addView(t("Battery telemetry unavailable",15,TEXT));
                        readings.addView(t(reason,12,MUTED));
                        readings.addView(t("Backend status: "+responseStatus,11,MUTED));
                        return;
                    }
                    int percent=battery.optInt("percentage",-1);
                    readings.addView(t(percent>=0?percent+"%":"Battery percentage unavailable",27,TEXT));
                    readings.addView(t("CHARGE LEVEL",10,CYAN));
                    addBatteryDetail(readings,"Status",batteryDisplay(battery,"status"));
                    addBatteryDetail(readings,"Connection",batteryDisplay(battery,"plugged"));
                    addBatteryDetail(readings,"Health",batteryDisplay(battery,"health"));
                    addBatteryDetail(readings,"Technology",batteryDisplay(battery,"technology"));
                    addBatteryDetail(readings,"Battery present",battery.has("present")&&!battery.isNull("present")?(battery.optBoolean("present")?"Yes":"No"):"Unavailable");
                    addBatteryDetail(readings,"Temperature",battery.has("temperature_c")&&!battery.isNull("temperature_c")?String.format(Locale.ROOT,"%.1f °C",battery.optDouble("temperature_c")):"Unavailable");
                    addBatteryDetail(readings,"Voltage",battery.has("voltage_mv")&&!battery.isNull("voltage_mv")?battery.optInt("voltage_mv")+" mV":"Unavailable");
                    addBatteryDetail(readings,"Current",battery.has("current_ua")&&!battery.isNull("current_ua")?String.format(Locale.ROOT,"%.1f mA",battery.optDouble("current_ua")/1000.0):"Unavailable");
                    addBatteryDetail(readings,"Average current",battery.has("current_average_ua")&&!battery.isNull("current_average_ua")?String.format(Locale.ROOT,"%.1f mA",battery.optDouble("current_average_ua")/1000.0):"Unavailable");
                    addBatteryDetail(readings,"Charge counter",battery.has("charge_counter_uah")&&!battery.isNull("charge_counter_uah")?String.format(Locale.ROOT,"%.1f mAh",battery.optDouble("charge_counter_uah")/1000.0):"Unavailable");
                    readings.addView(t("Read from the backend on request; not a continuous monitor.",10,MUTED));
                });
            }catch(Exception ignored){
                handler.post(()->{
                    readings.removeAllViews();
                    readings.addView(t("Battery telemetry could not be loaded.",15,TEXT));
                    readings.addView(t("Check that the Amiki backend is running and Termux:API is available.",12,MUTED));
                });
            }
        }).start();
    }

    private String batteryDisplay(JSONObject battery,String key){
        if(!battery.has(key)||battery.isNull(key))return "Unavailable";
        String value=battery.optString(key,"").trim();
        return value.isEmpty()?"Unavailable":value;
    }

    private void addBatteryDetail(LinearLayout parent,String label,String value){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(7),0,dp(7));
        TextView labelView=t(label,12,MUTED);
        TextView valueView=t(value,13,TEXT);
        valueView.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        valueView.setMaxWidth(dp(175));
        row.addView(labelView,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(valueView,new LinearLayout.LayoutParams(0,-2,1));
        parent.addView(row,lp(-1,-2));
        View divider=new View(this);
        divider.setBackgroundColor(CARD_2);
        parent.addView(divider,lp(-1,dp(1)));
    }

    private String availableDeviceActions(JSONObject actions){
        if(actions==null)return "unavailable";
        StringBuilder names=new StringBuilder();
        java.util.Iterator<String> keys=actions.keys();
        while(keys.hasNext()){
            String action=keys.next();
            JSONObject details=actions.optJSONObject(action);
            if(details==null||!details.optBoolean("available",false))continue;
            if(names.length()>0)names.append(", ");
            names.append(action.replace('_',' '));
        }
        return names.length()==0?"none detected":names.toString();
    }

    private void showInfo(String title,String desc){clear(title);LinearLayout b=lpView();b.addView(t(desc,15,MUTED));addLargeCard(b,"Coming next","Connected to the live backend");content.addView(b,lp(-1,-2));}

    private static final String NOTES_PREFERENCES = "amiki_quick_notes";
    private static final String NOTES_KEY = "notes_json";

    private JSONArray readSavedNotes() {
        try {
            return new JSONArray(getSharedPreferences(NOTES_PREFERENCES, MODE_PRIVATE)
                    .getString(NOTES_KEY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void writeSavedNotes(JSONArray notes) {
        getSharedPreferences(NOTES_PREFERENCES, MODE_PRIVATE).edit()
                .putString(NOTES_KEY, notes.toString()).apply();
    }

    private void showNotes() {
        clear("Notes");
        LinearLayout body = lpView();
        body.addView(t("Your private notebook", 18, TEXT));
        body.addView(t("Notes stay on this device and are separate from Chat.", 12, MUTED));
        EditText title = new EditText(this);
        title.setSingleLine(true);
        title.setHint("Note title");
        title.setTextColor(TEXT);
        title.setHintTextColor(MUTED);
        title.setPadding(dp(12), dp(8), dp(12), dp(8));
        title.setBackground(bg(CARD_2, 12));
        LinearLayout.LayoutParams tp = lp(-1, dp(52));
        tp.setMargins(0, dp(12), 0, dp(8));
        body.addView(title, tp);

        EditText contentInput = new EditText(this);
        contentInput.setHint("Write your note here…");
        contentInput.setTextColor(TEXT);
        contentInput.setHintTextColor(MUTED);
        contentInput.setGravity(Gravity.TOP | Gravity.START);
        contentInput.setMinLines(5);
        contentInput.setMaxLines(12);
        contentInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        contentInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        contentInput.setBackground(bg(CARD_2, 12));
        body.addView(contentInput, lp(-1, -2));

        final String[] selectedId = {""};
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(this);
        save.setText("Save note");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setBackground(bg(PURPLE, 14));
        Button fresh = new Button(this);
        fresh.setText("New");
        fresh.setAllCaps(false);
        fresh.setBackground(bg(CARD, 14));
        Button delete = new Button(this);
        delete.setText("Delete");
        delete.setAllCaps(false);
        delete.setBackground(bg(CARD, 14));
        controls.addView(save, new LinearLayout.LayoutParams(0, dp(48), 2));
        controls.addView(fresh, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(delete, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams controlsParams = lp(-1, dp(52));
        controlsParams.setMargins(0, dp(8), 0, dp(12));
        body.addView(controls, controlsParams);

        TextView status = t("Saved on this device", 11, MUTED);
        body.addView(status, lp(-1, dp(24)));
        body.addView(sectionTitle("Saved notes"));

        Runnable refreshList = () -> {
            View existing = body.findViewWithTag("amiki_notes_list");
            if (existing != null) body.removeView(existing);
            LinearLayout list = new LinearLayout(this);
            list.setTag("amiki_notes_list");
            list.setOrientation(LinearLayout.VERTICAL);
            JSONArray notes = readSavedNotes();
            if (notes.length() == 0) {
                list.addView(t("No notes yet. Write one above and tap Save note.", 13, MUTED));
            }
            for (int i = 0; i < notes.length(); i++) {
                JSONObject note = notes.optJSONObject(i);
                if (note == null) continue;
                String id = note.optString("id", "");
                String savedTitle = note.optString("title", "Untitled note");
                String savedBody = note.optString("body", "");
                LinearLayout item = card();
                item.setClickable(true);
                item.setContentDescription("Open note " + savedTitle);
                item.addView(t(savedTitle, 14, TEXT));
                String preview = savedBody.replace('\n', ' ').trim();
                if (preview.length() > 110) preview = preview.substring(0, 110) + "…";
                item.addView(t(preview, 12, MUTED));
                item.setOnClickListener(v -> {
                    selectedId[0] = id;
                    title.setText(savedTitle);
                    contentInput.setText(savedBody);
                    status.setText("Editing saved note");
                });
                LinearLayout.LayoutParams itemParams = lp(-1, -2);
                itemParams.setMargins(0, 0, 0, dp(8));
                list.addView(item, itemParams);
            }
            body.addView(list, lp(-1, -2));
        };

        save.setOnClickListener(v -> {
            String noteTitle = title.getText().toString().trim();
            String noteBody = contentInput.getText().toString().trim();
            if (noteTitle.isEmpty() && noteBody.isEmpty()) {
                Toast.makeText(this, "Write something before saving.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (noteTitle.isEmpty()) noteTitle = "Untitled note";
            JSONArray existing = readSavedNotes();
            JSONArray updated = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < existing.length(); i++) {
                JSONObject note = existing.optJSONObject(i);
                if (note == null) continue;
                if (!selectedId[0].isEmpty() && selectedId[0].equals(note.optString("id"))) {
                    try {
                        JSONObject edited = new JSONObject();
                        edited.put("id", selectedId[0]);
                        edited.put("title", noteTitle);
                        edited.put("body", noteBody);
                        edited.put("updated_at", System.currentTimeMillis());
                        updated.put(edited);
                        replaced = true;
                    } catch (Exception ignored) {}
                } else {
                    updated.put(note);
                }
            }
            if (!replaced) {
                try {
                    JSONObject created = new JSONObject();
                    selectedId[0] = UUID.randomUUID().toString();
                    created.put("id", selectedId[0]);
                    created.put("title", noteTitle);
                    created.put("body", noteBody);
                    created.put("updated_at", System.currentTimeMillis());
                    updated.put(created);
                } catch (Exception ignored) {}
            }
            writeSavedNotes(updated);
            title.setText(noteTitle);
            status.setText("Saved ✓");
            refreshList.run();
            Toast.makeText(this, "Note saved on this device.", Toast.LENGTH_SHORT).show();
        });
        fresh.setOnClickListener(v -> {
            selectedId[0] = "";
            title.setText("");
            contentInput.setText("");
            status.setText("New note");
            title.requestFocus();
        });
        delete.setOnClickListener(v -> {
            if (selectedId[0].isEmpty()) {
                Toast.makeText(this, "Choose a saved note to delete.", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray existing = readSavedNotes();
            JSONArray updated = new JSONArray();
            for (int i = 0; i < existing.length(); i++) {
                JSONObject note = existing.optJSONObject(i);
                if (note != null && !selectedId[0].equals(note.optString("id"))) updated.put(note);
            }
            writeSavedNotes(updated);
            selectedId[0] = "";
            title.setText("");
            contentInput.setText("");
            status.setText("Note deleted");
            refreshList.run();
        });
        refreshList.run();
        content.addView(body, lp(-1, -2));
    }

    private void showCalculator() {
        clear("Calculator");
        LinearLayout body = lpView();
        body.addView(t("A standalone calculator", 18, TEXT));
        body.addView(t("Calculations run locally on your device.", 12, MUTED));
        TextView display = t("0", 30, TEXT);
        display.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        display.setPadding(dp(14), dp(12), dp(14), dp(12));
        display.setBackground(bg(CARD_2, 16));
        display.setMinHeight(dp(82));
        LinearLayout.LayoutParams displayParams = lp(-1, -2);
        displayParams.setMargins(0, dp(14), 0, dp(12));
        body.addView(display, displayParams);
        String[] rows = {
                "C", "⌫", "(", ")",
                "7", "8", "9", "÷",
                "4", "5", "6", "×",
                "1", "2", "3", "−",
                "0", ".", "=", "+"
        };
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        final String[] expression = {""};
        for (int rowIndex = 0; rowIndex < 5; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 4; col++) {
                String key = rows[rowIndex * 4 + col];
                TextView keyView = t(key, 20, TEXT);
                keyView.setGravity(Gravity.CENTER);
                keyView.setBackground(bg("=".equals(key) ? PURPLE : CARD, 14));
                keyView.setClickable(true);
                keyView.setContentDescription("Calculator " + key);
                LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, dp(58), 1);
                keyParams.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(keyView, keyParams);
                keyView.setOnClickListener(v -> {
                    if ("C".equals(key)) {
                        expression[0] = "";
                    } else if ("⌫".equals(key)) {
                        if (!expression[0].isEmpty()) expression[0] = expression[0].substring(0, expression[0].length() - 1);
                    } else if ("=".equals(key)) {
                        try {
                            double result = new CalculatorParser(expression[0].replace('×', '*').replace('÷', '/').replace('−', '-')).parse();
                            if (!Double.isFinite(result)) throw new ArithmeticException("Result is not finite");
                            if (Math.abs(result) < 1e-12) result = 0;
                            String rendered = result == Math.rint(result) && Math.abs(result) < 1e15
                                    ? String.format(Locale.US, "%.0f", result)
                                    : String.format(Locale.US, "%.10f", result).replaceAll("0+$", "").replaceAll("\\.$", "");
                            expression[0] = rendered;
                        } catch (Exception ex) {
                            Toast.makeText(this, "Invalid expression. Check the operators and brackets.", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        expression[0] += key;
                    }
                    display.setText(expression[0].isEmpty() ? "0" : expression[0]);
                });
            }
            grid.addView(row, lp(-1, dp(64)));
        }
        body.addView(grid, lp(-1, -2));
        TextView clearHint = t("Supports +  −  ×  ÷  decimals and parentheses.", 11, MUTED);
        clearHint.setPadding(0, dp(8), 0, 0);
        body.addView(clearHint, lp(-1, dp(28)));
        content.addView(body, lp(-1, -2));
    }

    private static final class CalculatorParser {
        private final String input;
        private int position = 0;

        CalculatorParser(String input) { this.input = input == null ? "" : input; }

        double parse() {
            double value = expression();
            whitespace();
            if (position != input.length()) throw new IllegalArgumentException("Unexpected token");
            return value;
        }

        private double expression() {
            double value = term();
            while (true) {
                whitespace();
                if (take('+')) value += term();
                else if (take('-')) value -= term();
                else return value;
            }
        }

        private double term() {
            double value = factor();
            while (true) {
                whitespace();
                if (take('*')) value *= factor();
                else if (take('/')) {
                    double divisor = factor();
                    if (divisor == 0) throw new ArithmeticException("Division by zero");
                    value /= divisor;
                } else return value;
            }
        }

        private double factor() {
            whitespace();
            if (take('+')) return factor();
            if (take('-')) return -factor();
            if (take('(')) {
                double value = expression();
                whitespace();
                if (!take(')')) throw new IllegalArgumentException("Missing closing bracket");
                return value;
            }
            int start = position;
            boolean decimal = false;
            while (position < input.length()) {
                char c = input.charAt(position);
                if (Character.isDigit(c)) position++;
                else if (c == '.' && !decimal) { decimal = true; position++; }
                else break;
            }
            if (start == position || (position == start + 1 && input.charAt(start) == '.')) {
                throw new IllegalArgumentException("Expected number");
            }
            return Double.parseDouble(input.substring(start, position));
        }

        private boolean take(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void whitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
        }
    }

    private void toolAction(String label){
        String s=label.toLowerCase(Locale.ROOT);
        // Actions are matched by explicit known labels until the card model is
        // migrated to typed action IDs. These two labels were visible but fell
        // through to the generic unavailable-action message.
        if(s.contains("open amiki")) showChat();
        else if(s.contains("live browser")) startActivity(new Intent(this,BrowserActivity.class));
        else if(s.contains("engineering chat") || s.contains("workspace") || s.contains("file manager")) startActivity(new Intent(this,WorkspaceActivity.class));
        else if(s.contains("web search")) startActivity(new Intent(this,BrowserActivity.class));
        else if(s.contains("device control")) showDevice();
        else if(s.contains("security agent") || s.contains("security audit")) showSecurityAgent();
        else if(s.contains("open browser")) { try { JSONObject a=new JSONObject(); a.put("url","https://www.google.com"); requestDeviceAction("open_url",a); } catch(Exception ignored) {} }
        else if(s.contains("speak test")) { try { JSONObject a=new JSONObject(); a.put("text","Hello Chimz. Device integration is online."); requestDeviceAction("speak",a); } catch(Exception ignored) {} }
        else if(s.contains("share test")) { try { JSONObject a=new JSONObject(); a.put("text","Hello from Amiki device integration."); requestDeviceAction("share_text",a); } catch(Exception ignored) {} }
        else if(s.contains("battery state")) requestDeviceAction("battery_state",new JSONObject());
        else if(s.contains("device state")) requestDeviceAction("device_state",new JSONObject());
        else if(s.contains("android settings")) { try { JSONObject a=new JSONObject(); a.put("package","com.android.settings"); requestDeviceAction("open_app",a); } catch(Exception ignored) {} }
        else if(s.contains("diagnostic")) showDiagnostics();
        else if(s.contains("database")) showDatabase();
        else if(s.contains("settings")) showSettings();
        else if(s.contains("image")) showToolWorkspace("Image Analysis","🖼","Choose an image to attach to your next Amiki message.");
        else if(s.contains("file")) showToolWorkspace("File Manager","📁","Choose a file to attach to your next Amiki message.");
        else if(s.contains("code runner")) startActivity(new Intent(this,WorkspaceActivity.class));
        else if(s.contains("calculator")) showCalculator();
        else if(s.contains("notes")) showNotes();
        else if(s.contains("memory")) showMemory();
        else if(s.contains("resources")) showResources();
        else if(s.contains("android app") || s.contains("current build")) showAndroidBuild();
        else if(s.contains("study plan")) openStudyActivity("plan","","");
        else if(s.contains("mat 1100")) openStudyActivity("lesson","MAT 1100","MAT 1100");
        else if(s.contains("chemistry")) openStudyActivity("lesson","CHEMISTRY","Chemistry");
        else if(s.equals("study")) openStudyActivity("lesson","","");
        else if(s.contains("amiki v1")) showChat();
        else if(s.contains("amiki cloud") || s.contains("cloud control")) showCloud();
        else if(s.contains("backend")) checkHealth();
        else if(s.contains("orchestrator")||s.contains("state")) showAnalysis();
        else Toast.makeText(this,"This action is not available yet.",Toast.LENGTH_SHORT).show();
    }

    private void showToolWorkspace(String title,String icon,String description){
        clear(title);
        LinearLayout body=lpView();
        body.addView(t(icon+"  "+title,18,TEXT));
        body.addView(t(description,13,MUTED));
        LinearLayout action=card();
        action.addView(t("SELECT FILE",13,TEXT));
        action.setOnClickListener(v->pickAttachment());
        body.addView(action,lp(-1,dp(58)));
        attachmentWorkspaceStatus=t(selectedAttachments.isEmpty()?"No file selected yet.":"📎 "+selectedAttachments.size()+" attachment"+(selectedAttachments.size()==1?"":"s")+" staged for your next message.",12,MUTED);
        attachmentWorkspaceStatus.setPadding(dp(4),dp(10),dp(4),dp(10));
        body.addView(attachmentWorkspaceStatus,lp(-1,-2));
        LinearLayout info=card();
        info.addView(t("V1 WORKSPACE",12,PURPLE));
        info.addView(t("This tool opens in the current Android shell. It will not launch the legacy prototype.",13,MUTED));
        body.addView(info,lp(-1,-2));
        content.addView(body,lp(-1,-2));
    }

    private void showChatWithPrompt(String prompt){
        showChat();
        handler.postDelayed(()->{
            View v=content.findViewWithTag("amiki_input");
            if(v instanceof EditText){((EditText)v).setText(prompt);((EditText)v).setSelection(((EditText)v).length());}
        },80);
    }

    private void loadDashboardLive(LinearLayout memoryCard, LinearLayout stats) {
        new Thread(() -> {
            try {
                JSONObject memoryResponse = new JSONObject(getJson(MEMORY));
                JSONArray memories = memoryResponse.optJSONArray("memories");
                JSONObject capabilities = new JSONObject(getJson(CAPABILITIES));
                int enabledCapabilities = 0;
                java.util.Iterator<String> keys = capabilities.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (capabilities.optBoolean(key, false)) enabledCapabilities++;
                }
                boolean onlineNow = getCode(HEALTH) == 200;
                int memoryCount = memories == null ? 0 : memories.length();
                final int capabilityCount = enabledCapabilities;
                handler.post(() -> {
                    renderDashboardMemory(memoryCard, memories);
                    setStat(stats, 0, onlineNow ? "ONLINE" : "OFFLINE", "Backend");
                    setStat(stats, 1, String.valueOf(memoryCount), "Memories");
                    setStat(stats, 2, String.valueOf(capabilityCount), "Capabilities");
                });
            } catch (Exception error) {
                handler.post(() -> {
                    renderDashboardMemory(memoryCard, null);
                    memoryCard.addView(t("Memory service unavailable. Check Diagnostics.", 11, MUTED));
                    setStat(stats, 0, "?", "Backend");
                    setStat(stats, 1, "?", "Memories");
                    setStat(stats, 2, "?", "Capabilities");
                });
            }
        }).start();
    }

    private void setStat(LinearLayout row,int index,String value,String label){
        if(index<0||index>=row.getChildCount())return;
        LinearLayout cell=(LinearLayout)row.getChildAt(index); if(cell.getChildCount()>0 && cell.getChildAt(0) instanceof TextView)((TextView)cell.getChildAt(0)).setText(value);
        if(cell.getChildCount()>1 && cell.getChildAt(1) instanceof TextView)((TextView)cell.getChildAt(1)).setText(label);
    }

    private int getCode(String endpoint)throws Exception{
        HttpURLConnection c=null; try{c=ApiConnection.open(this, endpoint);c.setRequestMethod("GET");c.setConnectTimeout(3000);c.setReadTimeout(5000);return c.getResponseCode();}finally{if(c!=null)c.disconnect();}
    }

    private String getJson(String endpoint)throws Exception{
        HttpURLConnection c=null;
        try {
            c=ApiConnection.open(this, endpoint);
            c.setRequestMethod("GET");
            c.setConnectTimeout(3000);
            c.setReadTimeout(7000);
            int status=c.getResponseCode();
            InputStream stream=status>=200&&status<300?c.getInputStream():c.getErrorStream();
            StringBuilder body=new StringBuilder();
            if(stream!=null){
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
                    String line;
                    while((line=reader.readLine())!=null){
                        body.append(line);
                        if(body.length()>256*1024) throw new java.io.IOException("Core API response exceeded the safe size limit.");
                    }
                }
            }
            if(status!=200) throw new java.io.IOException(apiHttpError(status,body.toString()));
            return body.toString();
        } finally {
            if(c!=null)c.disconnect();
        }
    }

    private String apiHttpError(int status,String response){
        String detail="request rejected";
        try { detail=new JSONObject(response).optString("detail",detail); }
        catch(Exception ignored) {}
        return "HTTP "+status+": "+detail;
    }

    private String apiFailureLabel(Exception failure){
        String message=failure.getMessage()==null?"":failure.getMessage();
        if(message.contains("HTTP 401")) return "AUTH REQUIRED";
        if(message.contains("HTTP 403")) return "ACCESS DENIED";
        if(message.contains("HTTP 429")) return "RATE LIMITED";
        if(message.matches("(?s).*HTTP 5[0-9]{2}.*")) return "CORE ERROR";
        return "UNAVAILABLE";
    }

    private void fetchText(String endpoint,LinearLayout body,TextView target,String fallback){
        new Thread(()->{try{String raw=getJson(endpoint);JSONObject o=new JSONObject(raw);StringBuilder s=new StringBuilder();java.util.Iterator<String> it=o.keys();while(it.hasNext()){String k=it.next();if(s.length()>0)s.append("\n");s.append(k).append(": ").append(String.valueOf(o.opt(k)));}handler.post(()->target.setText(s.toString()));}catch(Exception e){handler.post(()->target.setText(fallback));}}).start();
    }

    private void showProjectOverview(){
        clear("Project Overview");
        LinearLayout body=lpView();
        body.addView(t("🌸  Amiki V1",20,TEXT));
        body.addView(t("Amiki is a personal AI companion with distinct Chat, Study, Analysis and Projects workspaces.",13,MUTED));
        LinearLayout identity=card();
        identity.addView(t("PROJECT BOUNDARIES",11,PURPLE));
        identity.addView(t("Chat • General conversation",14,TEXT));
        identity.addView(t("Study • Lessons, quizzes and study plans",14,TEXT));
        identity.addView(t("Analysis • Direct analysis agent and live runtime metrics",14,TEXT));
        identity.addView(t("Projects • Engineering agent, project status and development workflows",14,TEXT));
        body.addView(identity,lp(-1,-2));
        addInteractiveProjectCard(body,"←  Back to Projects","Return to project workspace",v -> showProjects());
        addInteractiveProjectCard(body,"◇  Open Analysis","Separate analysis workspace",v -> showAnalysis());
        addInteractiveProjectCard(body,"◉  Backend API","Check live backend health",v -> showBackendStatus());
        addInteractiveProjectCard(body,"▣  Android App","Current build details",v -> showAndroidBuild());
        content.addView(body,lp(-1,-2));
    }

    private void showAnalysis(){
        clear("Analysis");
        LinearLayout body = lpView();
        body.setPadding(dp(14), dp(8), dp(14), dp(20));

        LinearLayout toolsHeader = new LinearLayout(this);
        toolsHeader.setOrientation(LinearLayout.HORIZONTAL);
        toolsHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView toolsTitle = sectionTitle("Analysis Tools");
        toolsTitle.setPadding(0, dp(10), 0, dp(8));
        toolsHeader.addView(toolsTitle, new LinearLayout.LayoutParams(0, dp(42), 1));

        TextView viewAll = t("View all  ›", 13, PURPLE);
        viewAll.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        viewAll.setPadding(dp(6), 0, dp(2), 0);
        viewAll.setFocusable(true);
        viewAll.setOnClickListener(v -> showAnalysisActivity());
        toolsHeader.addView(viewAll, lp(dp(82), dp(42)));
        body.addView(toolsHeader, lp(-1, dp(44)));

        LinearLayout firstToolRow = new LinearLayout(this);
        firstToolRow.setOrientation(LinearLayout.HORIZONTAL);
        addAnalysisToolCard(firstToolRow, "</>", "Code Analysis",
                "Review & understand", "code_analysis",
                "Paste code or describe what you want reviewed.", true);
        addAnalysisToolCard(firstToolRow, "▥", "Data Analysis",
                "Process & visualize", "data_analysis",
                "Describe the dataset, its columns, and the question to answer.", false);
        body.addView(firstToolRow, lp(-1, dp(132)));

        LinearLayout secondToolRow = new LinearLayout(this);
        secondToolRow.setOrientation(LinearLayout.HORIZONTAL);
        secondToolRow.setPadding(0, dp(10), 0, 0);
        addAnalysisToolCard(secondToolRow, "▣", "System Analysis",
                "Logs & performance", "system_analysis",
                "Describe the system, symptoms, logs, or performance issue.", true);
        addAnalysisToolCard(secondToolRow, "◉", "Reasoning",
                "Deep thinking", "reasoning",
                "State the problem, constraints, and what a useful answer should establish.", false);
        body.addView(secondToolRow, lp(-1, dp(142)));

        body.addView(sectionTitle("Recent Activity"));
        LinearLayout recentActivity = card();
        recentActivity.setPadding(dp(10), dp(2), dp(10), dp(2));
        body.addView(recentActivity, lp(-1, -2));
        content.addView(body, lp(-1, -2));

        // Activity is sourced only from the backend event store; an empty store
        // is shown as empty rather than populated with illustrative records.
        loadAnalysisEvents(recentActivity, 20, 3);
    }

    private void addAnalysisToolCard(
            LinearLayout row,
            String icon,
            String title,
            String subtitle,
            String taskType,
            String hint,
            boolean addRightGap
    ) {
        LinearLayout tool = card();
        int accent = analysisAccent(taskType);
        accentCard(tool, accent);
        tool.setPadding(dp(14), dp(12), dp(10), dp(10));
        tool.setClickable(true);
        tool.setFocusable(true);
        tool.setContentDescription(title + ". " + subtitle);
        TextView symbol = t(icon, 25, accent);
        symbol.setTypeface(null, Typeface.BOLD);
        symbol.setGravity(Gravity.CENTER_VERTICAL);
        tool.addView(symbol, lp(-1, dp(34)));

        TextView label = t(title, 14, TEXT);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, dp(5), 0, dp(2));
        tool.addView(label, lp(-1, dp(25)));

        TextView description = t(subtitle, 11, MUTED);
        description.setMaxLines(2);
        tool.addView(description, lp(-1, -2));
        tool.setOnClickListener(v -> showAnalysisTask(taskType, title, subtitle, hint));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(132), 1);
        if (addRightGap) params.setMargins(0, 0, dp(10), 0);
        row.addView(tool, params);
    }

    private int analysisAccent(String taskType) {
        if ("code_analysis".equals(taskType)) return Color.rgb(167, 110, 255); // code: violet
        if ("data_analysis".equals(taskType)) return Color.rgb(34, 211, 238);  // data: cyan
        if ("system_analysis".equals(taskType)) return Color.rgb(251, 113, 133); // system: rose
        return Color.rgb(111, 126, 255);                                       // reasoning: periwinkle
    }

    private void showAnalysisTask(String taskType, String title, String subtitle, String hint) {
        clear(title);
        LinearLayout body = lpView();
        body.setPadding(dp(14), dp(8), dp(14), dp(20));

        TextView back = t("←  Analysis Tools", 13, PURPLE);
        back.setPadding(dp(2), dp(4), 0, dp(10));
        back.setFocusable(true);
        back.setOnClickListener(v -> showAnalysis());
        body.addView(back, lp(-1, dp(36)));

        TextView description = t(subtitle + " • Direct connection to Amiki's Analysis agent.", 12, MUTED);
        description.setPadding(0, 0, 0, dp(12));
        body.addView(description, lp(-1, -2));

        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setMaxLines(10);
        input.setMaxHeight(dp(280));
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(bg(CARD_2, 12));
        body.addView(input, lp(-1, -2));

        Button run = new Button(this);
        run.setText("RUN ANALYSIS");
        run.setTextColor(TEXT);
        run.setBackground(bg(PURPLE, 12));
        LinearLayout.LayoutParams runParams = lp(-1, dp(48));
        runParams.setMargins(0, dp(10), 0, 0);
        body.addView(run, runParams);

        TextView status = t("", 11, MUTED);
        status.setPadding(0, dp(8), 0, dp(4));
        body.addView(status, lp(-1, -2));

        LinearLayout resultCard = card();
        resultCard.setVisibility(View.GONE);
        TextView resultHeading = t("ANALYSIS RESULT", 10, PURPLE);
        resultHeading.setTypeface(null, Typeface.BOLD);
        resultCard.addView(resultHeading);
        TextView result = t("", 13, TEXT);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(8), 0, 0);
        resultCard.addView(result, lp(-1, -2));
        LinearLayout.LayoutParams resultParams = lp(-1, -2);
        resultParams.setMargins(0, dp(8), 0, 0);
        body.addView(resultCard, resultParams);

        content.addView(body, lp(-1, -2));
        run.setOnClickListener(v -> {
            String task = input.getText().toString().trim();
            if (task.isEmpty()) {
                input.setError("Enter the material or question to analyze.");
                input.requestFocus();
                return;
            }

            run.setEnabled(false);
            run.setText("ANALYZING…");
            status.setText("RUNNING • Sending task to the backend Analysis agent");
            resultCard.setVisibility(View.VISIBLE);
            result.setText("Waiting for the backend response…");

            new Thread(() -> {
                String answerText;
                boolean succeeded = false;
                try {
                    JSONObject request = new JSONObject();
                    request.put("agent", "analysis");
                    request.put("input", task);
                    request.put("session_id", "android-analysis");
                    request.put("conversation_id", "android-analysis-" + taskType);

                    JSONObject context = new JSONObject();
                    context.put("task_type", taskType);
                    request.put("context", context);

                    JSONObject response = postJson(DIRECT_AGENT, request);
                    JSONArray results = response.optJSONArray("results");
                    StringBuilder answer = new StringBuilder();
                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject item = results.optJSONObject(i);
                            if (item != null) {
                                String value = extractDirectResultText(item);
                                if (!value.isEmpty()) answer.append(value).append("\n\n");
                            }
                        }
                    }
                    if (answer.length() == 0) {
                        answer.append("The backend completed ")
                                .append(response.optInt("completed_steps", 0))
                                .append(" of ")
                                .append(response.optInt("planned_steps", 0))
                                .append(" steps but returned no displayable result.");
                    }
                    answerText = answer.toString().trim();
                    succeeded = true;
                } catch (Exception failure) {
                    String reason = failure.getMessage();
                    answerText = "Analysis failed. The backend did not return a successful result."
                            + (reason == null || reason.trim().isEmpty() ? "" : "\n\nDetails: " + reason);
                }

                final String displayedAnswer = answerText;
                final boolean completed = succeeded;
                handler.post(() -> {
                    result.setText(displayedAnswer);
                    status.setText(completed
                            ? "COMPLETE • Response received from the backend"
                            : "FAILED • Check the backend connection and retry");
                    run.setEnabled(true);
                    run.setText(completed ? "RUN AGAIN" : "RETRY ANALYSIS");
                });
            }).start();
        });
    }

    private void showAnalysisActivity() {
        clear("Recent Activity");
        LinearLayout body = lpView();
        body.setPadding(dp(14), dp(8), dp(14), dp(20));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = t("←  Analysis", 13, PURPLE);
        back.setFocusable(true);
        back.setOnClickListener(v -> showAnalysis());
        toolbar.addView(back, new LinearLayout.LayoutParams(0, dp(42), 1));

        TextView refresh = t("Refresh  ↻", 12, PURPLE);
        refresh.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        refresh.setFocusable(true);
        refresh.setOnClickListener(v -> loadAnalysisEvents(eventsCardForRefresh[0], 100, 100));
        toolbar.addView(refresh, lp(dp(94), dp(42)));
        body.addView(toolbar, lp(-1, dp(42)));

        LinearLayout eventList = card();
        eventList.setPadding(dp(10), dp(2), dp(10), dp(2));
        body.addView(eventList, lp(-1, -2));
        content.addView(body, lp(-1, -2));
        eventsCardForRefresh[0] = eventList;
        loadAnalysisEvents(eventList, 100, 100);
    }

    private final LinearLayout[] eventsCardForRefresh = new LinearLayout[1];

    private void loadAnalysisEvents(LinearLayout parent, int limit, int maxRows) {
        parent.removeAllViews();
        parent.addView(t("Loading activity from Amiki…", 12, MUTED));

        new Thread(() -> {
            JSONArray events = null;
            String error = null;
            try {
                JSONObject response = new JSONObject(getJson(
                        ApiEndpoints.OBSERVABILITY_EVENTS + "?limit=" + limit));
                events = response.optJSONArray("events");
                if (events == null) throw new Exception("The backend response did not include an events list.");
            } catch (Exception failure) {
                error = failure.getMessage();
                if (error == null || error.trim().isEmpty()) error = "The observability endpoint could not be reached.";
            }

            final JSONArray loadedEvents = events;
            final String loadError = error;
            handler.post(() -> {
                parent.removeAllViews();
                if (loadError != null) {
                    parent.addView(t("Activity could not be loaded from the backend.", 12, MUTED));
                    TextView detail = t(loadError, 10, MUTED);
                    detail.setPadding(0, dp(4), 0, dp(6));
                    parent.addView(detail);
                    TextView retry = t("Retry", 12, PURPLE);
                    retry.setPadding(0, dp(5), 0, dp(8));
                    retry.setFocusable(true);
                    retry.setOnClickListener(v -> loadAnalysisEvents(parent, limit, maxRows));
                    parent.addView(retry);
                    return;
                }

                if (loadedEvents.length() == 0) {
                    parent.addView(t("No recent activity has been recorded by the backend yet.", 12, MUTED));
                    return;
                }

                int visibleCount = maxRows < 0 ? loadedEvents.length() : Math.min(maxRows, loadedEvents.length());
                for (int i = 0; i < visibleCount; i++) {
                    JSONObject event = loadedEvents.optJSONObject(i);
                    if (event == null) continue;
                    addAnalysisEventRow(parent, event);
                    if (i < visibleCount - 1) {
                        View divider = new View(this);
                        divider.setBackgroundColor(Color.rgb(29, 35, 59));
                        LinearLayout.LayoutParams dividerParams = lp(-1, dp(1));
                        dividerParams.setMargins(dp(42), 0, 0, 0);
                        parent.addView(divider, dividerParams);
                    }
                }
            });
        }).start();
    }

    private void addAnalysisEventRow(LinearLayout parent, JSONObject event) {
        String eventType = event.optString("event_type", "UNKNOWN").toUpperCase(Locale.ROOT);
        String severity = event.optString("severity", "INFO").toUpperCase(Locale.ROOT);
        String summary = event.optString("summary", "").trim();
        if (summary.isEmpty()) summary = "No summary was provided by the backend.";
        final String eventSummary = summary;

        int accent = Color.rgb(177, 87, 255);
        int badgeBackground = Color.rgb(37, 19, 65);
        String glyph = "◈";
        if (severity.equals("ERROR") || severity.equals("CRITICAL")
                || eventType.contains("FAIL") || eventType.contains("UNREACHABLE")) {
            accent = Color.rgb(255, 91, 119);
            badgeBackground = Color.rgb(62, 20, 39);
            glyph = "!";
        } else if (eventType.contains("SUCCESS") || eventType.contains("RECOVER")
                || eventType.contains("REACHABLE") || severity.equals("RECOVERY")) {
            accent = Color.rgb(100, 220, 163);
            badgeBackground = Color.rgb(15, 49, 47);
            glyph = "✓";
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(3), dp(10), dp(2), dp(10));

        TextView badge = t(glyph, 15, accent);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(bg(badgeBackground, 22));
        row.addView(badge, lp(dp(34), dp(34)));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setPadding(dp(10), 0, dp(5), 0);
        TextView headline = t(eventSummary, 12, TEXT);
        headline.setMaxLines(2);
        headline.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBlock.addView(headline, lp(-1, -2));

        TextView details = t(formatSettingLabel(eventType) + " • "
                + formatSettingLabel(event.optString("component", event.optString("source", "backend"))),
                10, MUTED);
        details.setMaxLines(1);
        details.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.setPadding(0, dp(3), 0, 0);
        textBlock.addView(details, lp(-1, -2));
        row.addView(textBlock, new LinearLayout.LayoutParams(0, -2, 1));

        TextView time = t(formatAnalysisEventTime(event.optString("observed_at", "")), 9, MUTED);
        time.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        time.setMaxLines(2);
        time.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(time, lp(dp(68), dp(36)));

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            StringBuilder detailText = new StringBuilder();
            detailText.append(eventSummary)
                    .append("\n\nType: ").append(eventType)
                    .append("\nSeverity: ").append(severity)
                    .append("\nStatus: ").append(event.optString("status", "unknown"))
                    .append("\nSource: ").append(event.optString("source", "unknown"))
                    .append("\nComponent: ").append(event.optString("component", "unknown"))
                    .append("\nObserved: ").append(event.optString("observed_at", "unknown"));
            new AlertDialog.Builder(this)
                    .setTitle(formatSettingLabel(eventType))
                    .setMessage(detailText.toString())
                    .setPositiveButton("CLOSE", null)
                    .show();
        });
        parent.addView(row, lp(-1, -2));
    }

    private String formatAnalysisEventTime(String value) {
        if (value == null || value.trim().isEmpty()) return "Time unknown";
        String stamp = value.trim();
        try {
            int zoneIndex = -1;
            for (int i = 19; i < stamp.length(); i++) {
                char current = stamp.charAt(i);
                if (current == 'Z' || current == '+' || current == '-') {
                    zoneIndex = i;
                    break;
                }
            }
            if (zoneIndex < 0 || stamp.length() < 19) {
                return stamp.length() >= 16 ? stamp.substring(11, 16) : stamp;
            }

            String zone = stamp.charAt(zoneIndex) == 'Z' ? "+00:00" : stamp.substring(zoneIndex);
            String normalized = stamp.substring(0, 19) + zone;
            java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            parser.setLenient(false);
            java.util.Date observed = parser.parse(normalized);
            if (observed == null) return stamp.substring(11, 16);

            java.text.SimpleDateFormat dayFormat = new java.text.SimpleDateFormat("yyyyMMdd", Locale.getDefault());
            String observedDay = dayFormat.format(observed);
            String today = dayFormat.format(new java.util.Date());
            if (observedDay.equals(today)) {
                return new java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(observed);
            }
            Calendar yesterday = Calendar.getInstance();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);
            if (observedDay.equals(dayFormat.format(yesterday.getTime()))) return "Yesterday";
            return new java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(observed);
        } catch (Exception ignored) {
            return stamp.length() >= 16 ? stamp.substring(11, 16) : stamp;
        }
    }

    private void addDirectAgentComposer(
            LinearLayout parent,
            String[] agents,
            String hint
    ) {
        LinearLayout box = card();
        box.addView(t("DIRECT AGENT",11,PURPLE));
        final String[] selected = new String[]{agents[0]};
        final TextView[] statusRef = new TextView[1];
        final Button[] sendRef = new Button[1];
        final EditText[] inputRef = new EditText[1];
        LinearLayout historyView = new LinearLayout(this);
        historyView.setOrientation(LinearLayout.VERTICAL);
        if (agents.length > 1) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String agent : agents) {
                Button button = new Button(this);
                button.setText(agent.toUpperCase());
                button.setTextColor(TEXT);
                button.setTextSize(11);
                button.setBackground(bg(agent.equals(selected[0]) ? PURPLE : CARD_2, 10));
                button.setOnClickListener(v -> {
                    String previousAgent = selected[0];
                    String previousDraft = inputRef[0].getText().toString();
                    directAgentDrafts.put(previousAgent, previousDraft);
                    selected[0] = agent;
                    for (int i = 0; i < row.getChildCount(); i++) {
                        Button b = (Button) row.getChildAt(i);
                        b.setBackground(bg(b.getText().toString().equals(agent.toUpperCase()) ? PURPLE : CARD_2, 10));
                    }
                    renderDirectHistory(agent, historyView);
                    inputRef[0].setText(directAgentDrafts.getOrDefault(agent, ""));
                    inputRef[0].setSelection(inputRef[0].length());
                    if (statusRef[0] != null) statusRef[0].setText(agent.toUpperCase() + " AGENT • DIRECT");
                    if (sendRef[0] != null) sendRef[0].setText("SEND • " + agent.toUpperCase());
                });
                row.addView(button, new LinearLayout.LayoutParams(0, dp(42), 1));
            }
            box.addView(row, lp(-1, dp(46)));
        }
        box.addView(historyView, lp(-1, -2));
        renderDirectHistory(selected[0], historyView);

        EditText input = new EditText(this);
        input.setTag("direct_agent_input");
        input.setHint(hint);
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(14);
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setMaxHeight(dp(220));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(bg(CARD_2, 12));
        input.setText(directAgentDrafts.getOrDefault(agents[0], ""));
        input.setSelection(input.length());
        inputRef[0] = input;
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                directAgentDrafts.put(selected[0], s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        box.addView(input, lp(-1, -2)); // Wrap content so the composer grows with multiline drafts.

        TextView status = t(agents[0].toUpperCase() + " AGENT • DIRECT",11,MUTED);
        box.addView(status);
        statusRef[0] = status;

        Button send = new Button(this);
        send.setText("SEND • " + agents[0].toUpperCase());
        send.setTextColor(TEXT);
        sendRef[0] = send;
        send.setOnClickListener(v -> {
            String message = input.getText().toString().trim();
            if (message.isEmpty()) {
                input.setError("Enter a request first.");
                return;
            }
            directAgentDrafts.put(selected[0], "");
            sendDirectAgent(selected[0], message, historyView, send, status, selected);
            input.setText("");
        });
        box.addView(send, lp(-1, dp(46)));

        parent.addView(box, lp(-1, -2));
    }

    private void sendDirectAgent(
            String agent,
            String message,
            LinearLayout historyView,
            Button send,
            TextView status,
            String[] selectedAgentRef
    ) {
        send.setEnabled(false);
        send.setText(agent.toUpperCase() + " • RUNNING");
        status.setText(agent.toUpperCase() + " AGENT • RUNNING");
        ArrayList<String[]> history = directAgentHistory.get(agent);
        if (history == null) {
            history = new ArrayList<>();
            directAgentHistory.put(agent, history);
        }
        history.add(new String[]{"user", message});
        persistConversationHistory();
        renderDirectHistory(agent, historyView);
        new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("agent", agent);
                request.put("input", message);
                request.put("session_id", "android-direct");
                request.put("conversation_id", "mobile-shell-" + agent);
                JSONObject response = postJson(DIRECT_AGENT, request);
                JSONArray results = response.optJSONArray("results");
                StringBuilder answer = new StringBuilder();
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item != null) {
                            String value = "security".equals(agent)
                                    ? SecurityReportFormatter.format(item)
                                    : extractDirectResultText(item);
                            if (!value.isEmpty()) answer.append(value).append("\n\n");
                        }
                    }
                }
                if (answer.length() == 0) {
                    answer.append("Completed ")
                            .append(response.optInt("completed_steps", 0))
                            .append(" / ")
                            .append(response.optInt("planned_steps", 0))
                            .append(" steps.");
                }
                final String finalAnswer = answer.toString().trim();
                handler.post(() -> {
                    ArrayList<String[]> currentHistory = directAgentHistory.get(agent);
                    if (currentHistory == null) {
                        currentHistory = new ArrayList<>();
                        directAgentHistory.put(agent, currentHistory);
                    }
                    currentHistory.add(new String[]{"assistant", finalAnswer});
                    persistConversationHistory();
                    if (selectedAgentRef == null || agent.equals(selectedAgentRef[0])) {
                        renderDirectHistory(agent, historyView);
                        status.setText(agent.toUpperCase() + " AGENT • READY");
                        send.setText("SEND • " + agent.toUpperCase());
                        send.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    ArrayList<String[]> currentHistory = directAgentHistory.get(agent);
                    if (currentHistory == null) {
                        currentHistory = new ArrayList<>();
                        directAgentHistory.put(agent, currentHistory);
                    }
                    currentHistory.add(new String[]{"assistant", "This agent is temporarily unavailable. Please retry."});
                    persistConversationHistory();
                    if (selectedAgentRef == null || agent.equals(selectedAgentRef[0])) {
                        renderDirectHistory(agent, historyView);
                        status.setText(agent.toUpperCase() + " AGENT • UNAVAILABLE");
                        send.setText("RETRY");
                        send.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private void renderDirectHistory(String agent, LinearLayout historyView) {
        historyView.removeAllViews();
        ArrayList<String[]> history = directAgentHistory.get(agent);
        if (history == null || history.isEmpty()) return;
        for (String[] item : history) {
            boolean user = "user".equals(item[0]);
            LinearLayout bubble = card();
            TextView message = t((user ? "You\n" : agent.toUpperCase() + " AGENT • DIRECT\n") + item[1], 13, TEXT);
            message.setTextIsSelectable(true);
            bubble.addView(message, lp(-1, -2));
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.setMargins(0, dp(7), 0, 0);
            historyView.addView(bubble, p);
        }
    }

    private boolean isTransportOnlySuccessMessage(String message) {
        return message != null && message.trim().matches("(?i)HTTPS?\\s+[0-9]{3}\\s+SUCCESS");
    }

    private String extractDirectResultText(JSONObject item) {
        JSONObject data = item.optJSONObject("data");
        if (data != null) {
            // Direct engineering tools wrap their original result in a shared
            // {status, message, data} envelope. Unwrap it so the UI renders the
            // actual inspection/file result instead of a transport/status stub.
            JSONObject nestedData = data.optJSONObject("data");
            String wrapperMessage = data.optString("message", "").trim();
            boolean transportOnly = wrapperMessage.matches("(?i)HTTPS?\\s+[0-9]{3}\\s+SUCCESS");
            if (nestedData != null && (wrapperMessage.isEmpty()
                    || "null".equalsIgnoreCase(wrapperMessage) || transportOnly)) {
                String nested = extractDirectResultText(nestedData);
                if (nested.isEmpty()) nested = formatDirectAgentData(nestedData);
                if (!nested.isEmpty()) return nested;
            }

            String message = data.optString("message", "");
            String instruction = data.optString("instruction", "");
            if (!message.isEmpty() && !"null".equalsIgnoreCase(message)
                    && !isTransportOnlySuccessMessage(message)) {
                if (!instruction.isEmpty() && !"null".equalsIgnoreCase(instruction)) {
                    return instruction + "\n\n" + message;
                }
                return message;
            }

            JSONObject evidence = data.optJSONObject("evidence");
            if (evidence != null) {
                StringBuilder out = new StringBuilder();
                JSONArray facts = evidence.optJSONArray("facts");
                if (facts != null) {
                    for (int i = 0; i < facts.length(); i++) {
                        String fact = facts.optString(i, "");
                        if (!fact.isEmpty()) out.append("• ").append(fact).append("\n");
                    }
                }
                JSONArray sources = evidence.optJSONArray("sources");
                if (sources != null && sources.length() > 0) {
                    out.append("\nSources:\n");
                    for (int i = 0; i < sources.length(); i++) {
                        JSONObject source = sources.optJSONObject(i);
                        if (source != null) {
                            String title = source.optString("title", "Source");
                            String url = source.optString("url", "");
                            out.append("• ").append(title);
                            if (!url.isEmpty()) out.append("\n  ").append(url);
                            out.append("\n");
                        }
                    }
                }
                if (out.length() > 0) return out.toString().trim();
            }

            String structured = formatDirectAgentData(data);
            if (!structured.isEmpty()) return structured;
        }

        String message = item.optString("message", "");
        if (!message.isEmpty() && !"null".equalsIgnoreCase(message)
                && !isTransportOnlySuccessMessage(message)) return message;
        String summary = item.optString("summary", "");
        if (!summary.isEmpty() && !"null".equalsIgnoreCase(summary)) return summary;
        String detail = item.optString("detail", "");
        if (!detail.isEmpty() && !"null".equalsIgnoreCase(detail)) return detail;
        String contentValue = item.optString("content", "");
        if (!contentValue.isEmpty() && !"null".equalsIgnoreCase(contentValue)) return contentValue;
        return "";
    }

    private String formatDirectAgentData(JSONObject data) {
        StringBuilder out = new StringBuilder();
        appendDirectField(out, data, "summary", "Summary");
        appendDirectField(out, data, "error", "Error");
        appendDirectField(out, data, "detail", "Details");
        appendDirectField(out, data, "status", "Status");
        appendDirectField(out, data, "type", "Type");
        appendDirectField(out, data, "path", "Path");
        appendDirectField(out, data, "safe", "Safe");

        appendDirectArray(out, data.optJSONArray("entries"), "Entries");
        appendDirectArray(out, data.optJSONArray("checks"), "Checks");
        appendDirectArray(out, data.optJSONArray("findings"), "Findings");

        JSONObject nestedEvidence = data.optJSONObject("evidence");
        if (nestedEvidence != null) {
            appendDirectArray(out, nestedEvidence.optJSONArray("checks"), "Checks");
            appendDirectArray(out, nestedEvidence.optJSONArray("findings"), "Findings");
            appendDirectField(out, nestedEvidence, "source", "Evidence source");
        }

        String contentValue = data.optString("content", "");
        if (!contentValue.isEmpty() && !"null".equalsIgnoreCase(contentValue)) {
            if (out.length() > 0) out.append("\n");
            out.append(contentValue);
        }
        return out.toString().trim();
    }

    private void appendDirectField(StringBuilder out, JSONObject data, String key, String label) {
        if (!data.has(key)) return;
        Object value = data.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) return;
        if (out.length() > 0) out.append("\n");
        out.append(label).append(": ").append(String.valueOf(value));
    }

    private void appendDirectArray(StringBuilder out, JSONArray values, String label) {
        if (values == null || values.length() == 0) return;
        if (out.length() > 0) out.append("\n");
        out.append(label).append(":\n");
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            if (value == null || JSONObject.NULL.equals(value)) continue;
            out.append("• ");
            if (value instanceof JSONObject) {
                out.append(value.toString());
            } else {
                out.append(String.valueOf(value));
            }
            out.append("\n");
        }
    }

    private JSONObject postJson(String endpoint, JSONObject request) throws Exception {
        return postJson(endpoint, request, null);
    }

    private JSONObject postJson(
            String endpoint,
            JSONObject request,
            Map<String, String> additionalHeaders
    ) throws Exception {
        HttpURLConnection c = ApiConnection.open(this, endpoint);
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(75000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            if (additionalHeaders != null) {
                for (Map.Entry<String, String> header : additionalHeaders.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        c.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }
            try (OutputStream out=c.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code=c.getResponseCode();
            if(code<200||code>=300) throw new Exception("HTTP "+code);
            StringBuilder raw=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                String line; while((line=reader.readLine())!=null) raw.append(line);
            }
            return new JSONObject(raw.toString());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject putJson(String endpoint, JSONObject request) throws Exception {
        HttpURLConnection c = ApiConnection.open(this, endpoint);
        try {
            c.setRequestMethod("PUT");
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            try (OutputStream out=c.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code=c.getResponseCode();
            if(code<200||code>=300) throw new Exception("HTTP "+code);
            StringBuilder raw=new StringBuilder();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=reader.readLine())!=null)raw.append(line);
            }
            return new JSONObject(raw.toString());
        } finally { c.disconnect(); }
    }

    private void showDatabase(){
        clear("Database"); LinearLayout b=lpView();
        b.addView(t("🗄  Amiki Database",18,TEXT));
        b.addView(t("READ-ONLY • Live storage overview",13,MUTED));
        LinearLayout metrics=new LinearLayout(this); metrics.setOrientation(LinearLayout.VERTICAL);
        addMetric(metrics,"🧠","Persistent Memory","Loading…","Stored memory records");
        addMetric(metrics,"💬","Current Session","Loading…","Session messages");
        addMetric(metrics,"📎","Attachments","Loading…","Attachment storage");
        b.addView(metrics,lp(-1,-2)); content.addView(b,lp(-1,-2));
        new Thread(()->{try{JSONObject o=new JSONObject(getJson(DATABASE));JSONObject m=o.optJSONObject("persistent_memory");JSONObject s=o.optJSONObject("current_session");JSONObject a=o.optJSONObject("attachments");handler.post(()->{setMetric(metrics,0,m==null?"UNKNOWN":(m.optBoolean("enabled")?"ENABLED":"DISABLED"),m==null?"Unavailable":m.optInt("count")+" memories");setMetric(metrics,1,s==null?"UNKNOWN":(s.optBoolean("active")?"ACTIVE":"INACTIVE"),s==null?"Unavailable":s.optInt("message_count")+" messages");setMetric(metrics,2,a==null?"UNKNOWN":(a.optBoolean("available")?"AVAILABLE":"UNAVAILABLE"),a==null?"Unavailable":a.optString("status","Storage state"));});}catch(Exception e){handler.post(()->{setMetric(metrics,0,"UNAVAILABLE","Open Diagnostics");setMetric(metrics,1,"UNAVAILABLE","Open Diagnostics");setMetric(metrics,2,"UNAVAILABLE","Open Diagnostics");});}}).start();
    }
    private void addMetric(LinearLayout parent,String icon,String title,String value,String detail){LinearLayout c=card();accentCard(c,metricAccent(title));c.addView(t(icon+"  "+title,13,metricAccent(title)));TextView v=t(value,21,TEXT);v.setTypeface(null,Typeface.BOLD);v.setPadding(0,dp(7),0,dp(2));c.addView(v);c.addView(t(detail,11,MUTED));LinearLayout.LayoutParams p=lp(-1,-2);p.setMargins(0,dp(8),0,0);parent.addView(c,p);}
    private int metricAccent(String title) {
        String value = title.toLowerCase(Locale.ROOT);
        if (value.contains("memory")) return Color.rgb(45, 190, 170);
        if (value.contains("session")) return Color.rgb(111, 126, 255);
        if (value.contains("attachment")) return Color.rgb(217, 70, 239);
        if (value.contains("runtime")) return Color.rgb(249, 115, 22);
        if (value.contains("backend")) return Color.rgb(99, 102, 241);
        if (value.contains("capability")) return Color.rgb(34, 211, 238);
        if (value.contains("health")) return Color.rgb(52, 211, 153);
        return currentAccent;
    }
    private void setMetric(LinearLayout parent,int index,String value,String detail){if(index>=parent.getChildCount())return;LinearLayout c=(LinearLayout)parent.getChildAt(index);if(c.getChildCount()>1)((TextView)c.getChildAt(1)).setText(value);if(c.getChildCount()>2)((TextView)c.getChildAt(2)).setText(detail);}
    private void showDiagnostics(){
        // Use the canonical Sentinel-backed diagnostics screen without replacing
        // the active MobileShell UI or launcher architecture.
        final String eventsEndpoint = ApiEndpoints.OBSERVABILITY_EVENTS;
        final String permanentDiagnosticsEndpoint = ApiEndpoints.OBSERVABILITY_DIAGNOSTICS;
        final String observabilityStatusEndpoint = ApiEndpoints.OBSERVABILITY_STATUS;

        View existing = root.findViewWithTag("amiki_diagnostics_overlay");
        if (existing != null) root.removeView(existing);

        DiagnosticsScreen diagnostics = new DiagnosticsScreen(
                this,
                new DiagnosticsScreen.Callbacks() {
                    @Override public void onBack() {
                        View overlay = root.findViewWithTag("amiki_diagnostics_overlay");
                        if (overlay != null) root.removeView(overlay);
                        showProjects();
                    }

                    @Override public void onMenu() {
                        openDrawer();
                    }

                    @Override public void onDirectInspection(
                            String message,
                            LinearLayout content,
                            ScrollView scroll,
                            Button send,
                            TextView status
                    ) {
                        sendDirectAgent(
                                "inspection",
                                message,
                                content,
                                send,
                                status,
                                null
                        );
                        status.setText("INSPECTION AGENT • RUNNING");
                    }
                },
                DIAGNOSTICS,
                eventsEndpoint,
                permanentDiagnosticsEndpoint,
                observabilityStatusEndpoint
        );

        LinearLayout view = diagnostics.build();
        view.setTag("amiki_diagnostics_overlay");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
        // Keep the existing scrim and drawer above the diagnostics screen.
        root.addView(view, 1, params);
    }

    private void showBackendStatus(){
        clear("Backend API");
        LinearLayout b=lpView();
        b.addView(t("◉  Backend API",18,TEXT));
        b.addView(t("Health is public. Protected endpoints require an active UOSA owner session. HTTP 401/403 means authorization is missing or insufficient, not that Core is offline.",13,MUTED));
        LinearLayout metrics=new LinearLayout(this);
        metrics.setOrientation(LinearLayout.VERTICAL);
        addMetric(metrics,"●","Health","CHECKING","/api/v1/health");
        addMetric(metrics,"◇","Capabilities","CHECKING","/api/v1/capabilities");
        addMetric(metrics,"⚡","System State","CHECKING","/api/v1/system-state");
        b.addView(metrics,lp(-1,-2));
        content.addView(b,lp(-1,-2));
        new Thread(()->{
            int health=0;
            String healthLabel="UNAVAILABLE";
            String capabilityLabel="UNKNOWN";
            String systemLabel="UNKNOWN";
            try {
                health=getCode(HEALTH);
                healthLabel=health==200?"ONLINE":"HTTP "+health;
            } catch(Exception failure) {
                healthLabel="UNREACHABLE";
            }
            try {
                JSONObject caps=new JSONObject(getJson(CAPABILITIES));
                int enabled=0;
                java.util.Iterator<String> it=caps.keys();
                while(it.hasNext()) if(caps.optBoolean(it.next(),false)) enabled++;
                capabilityLabel=enabled+" ENABLED";
            } catch(Exception failure) {
                capabilityLabel=apiFailureLabel(failure);
            }
            try {
                JSONObject sys=new JSONObject(getJson(SYSTEM_STATE));
                systemLabel=runtimeState(sys);
            } catch(Exception failure) {
                systemLabel=apiFailureLabel(failure);
            }
            final String h=healthLabel, cp=capabilityLabel, st=systemLabel;
            final int healthCode=health;
            handler.post(()->{
                setMetric(metrics,0,h,"Backend health");
                setMetric(metrics,1,cp,"Capability registry");
                setMetric(metrics,2,st,"System state");
                online.setText("● "+h);
                online.setTextColor(healthCode==200?Color.rgb(100,230,130):Color.rgb(255,100,100));
            });
        }).start();
    }

    private void loadProjectState(LinearLayout metrics){
        new Thread(()->{
            int health=0;
            String stateLabel="UNAVAILABLE";
            String capabilityLabel="—";
            try {
                health=getCode(HEALTH);
            } catch(Exception ignored) {
                health=0;
            }
            try {
                JSONObject sys=new JSONObject(getJson(SYSTEM_STATE));
                stateLabel=runtimeState(sys);
                capabilityLabel=sys.optString("active_capability",sys.optString("capability","NONE")).toUpperCase();
            } catch(Exception failure) {
                stateLabel=apiFailureLabel(failure);
            }
            final String fs=stateLabel, fc=capabilityLabel, fh=health==200?"ONLINE":(health==0?"UNREACHABLE":"HTTP "+health);
            handler.post(()->{
                setMetric(metrics,0,fs,"Backend system state");
                setMetric(metrics,1,fc,"Active capability");
                setMetric(metrics,2,fh,"Backend health");
            });
        }).start();
    }

    private void addInteractiveProjectCard(LinearLayout b,String title,String detail,View.OnClickListener listener){
        LinearLayout c=card(); accentCard(c,toolAccent(title)); c.setOrientation(LinearLayout.HORIZONTAL); c.setClickable(true); c.setOnClickListener(listener);
        c.addView(t(title,14,TEXT),new LinearLayout.LayoutParams(0,dp(60),1));
        TextView d=t(detail+"  ›",11,MUTED); d.setGravity(Gravity.CENTER_VERTICAL); c.addView(d,new LinearLayout.LayoutParams(dp(125),dp(60)));
        LinearLayout.LayoutParams p=lp(-1,dp(72)); p.setMargins(0,dp(7),0,0); b.addView(c,p);
    }


    public void speakDeviceText(String text, DeviceBridge.Callback callback) {
        handler.post(() -> {
            if (!ttsReady || amikiTts == null) {
                callback.onResult("Amiki TTS is not ready.");
                return;
            }
            int result = amikiTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "amiki-device");
            callback.onResult(result == TextToSpeech.SUCCESS ? "Amiki spoke the requested text." : "Amiki could not speak the requested text.");
        });
    }

    /** Uses the system picker; does not scan the address book or send messages. */
    public void executeWhatsAppCompose(String contactName, String message, DeviceBridge.Callback callback) {
        if (contactName == null || contactName.trim().isEmpty() || message == null || message.trim().isEmpty()) {
            callback.onResult("A contact and message are required. Nothing was opened."); return;
        }
        if (pendingWhatsAppCallback != null) { callback.onResult("Another contact selection is already in progress."); return; }
        pendingWhatsAppMessage = message.trim();
        pendingWhatsAppCallback = callback;
        Intent picker = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        if (picker.resolveActivity(getPackageManager()) == null) {
            pendingWhatsAppMessage = null; pendingWhatsAppCallback = null;
            callback.onResult("No compatible Android contact picker is available. No message was prepared."); return;
        }
        try { startActivityForResult(picker, PICK_WHATSAPP_CONTACT); }
        catch (Exception e) { pendingWhatsAppMessage = null; pendingWhatsAppCallback = null; callback.onResult("The contact picker could not be opened. No message was prepared."); }
    }
    public void executeDeviceContactCall(String name, DeviceBridge.Callback callback) {
        if (deviceActionExecutor == null) deviceActionExecutor = new DeviceActionExecutor(this);
        boolean handled = deviceActionExecutor.tryExecute("call " + name, new DeviceActionExecutor.Callback() {
            @Override public void onResult(String message) {
                callback.onResult(message);
            }
            @Override public void onError(String message) {
                callback.onResult(message);
            }
        });
        if (!handled) callback.onResult("The contact action could not be interpreted safely. No call was placed.");
    }

    private void requestDeviceAction(String action, JSONObject arguments) {
        if (deviceBridge == null) deviceBridge = new DeviceBridge(this);
        deviceBridge.request(action, arguments, message -> handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show()));
    }

    private void startVoiceInput(){
        if(chatRequestInFlight || voiceInputInFlight) return;
        if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},VOICE_INPUT); return;
        }
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak to Amiki");
        try{
            voiceInputInFlight = true;
            View voice = content.findViewWithTag("amiki_voice");
            if(voice instanceof Button){ Button button = (Button)voice; button.setText("🎙…"); button.setEnabled(false); }
            refreshOrbStatus();
            startActivityForResult(i,VOICE_INPUT);
        }catch(Exception e){
            voiceInputInFlight = false;
            refreshOrbStatus();
            Toast.makeText(this,"Voice input is unavailable on this device.",Toast.LENGTH_SHORT).show();
        }
    }
    private void pickAttachment(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_ATTACHMENT_MIME_TYPES);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        startActivityForResult(i,PICK_ATTACHMENT);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==VOICE_INPUT) voiceInputInFlight = false;
        if (requestCode == PICK_WHATSAPP_CONTACT) { handleWhatsAppContactResult(resultCode, data); return; }
        if(resultCode!=RESULT_OK||data==null){
            if(requestCode==VOICE_INPUT) showChat();
            return;
        }
        if(requestCode==VOICE_INPUT){
            ArrayList<String> results=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(results!=null&&!results.isEmpty()){View v=content.findViewWithTag("amiki_input");if(v instanceof EditText){EditText in=(EditText)v;in.setText(results.get(0));in.setSelection(in.length());}} 
        } else if(requestCode==PICK_ATTACHMENT){
            ArrayList<Uri> candidates = new ArrayList<>();
            if(data.getClipData()!=null){
                for(int i=0;i<data.getClipData().getItemCount();i++) candidates.add(data.getClipData().getItemAt(i).getUri());
            } else if(data.getData()!=null) {
                candidates.add(data.getData());
            }
            if(candidates.size()>MAX_ATTACHMENTS){
                Toast.makeText(this,"Choose no more than "+MAX_ATTACHMENTS+" files per message.",Toast.LENGTH_LONG).show();
                candidates.clear();
            } else {
                long totalBytes=0L;
                for(Uri uri:candidates){
                    if(!isSupportedAttachment(uri)){
                        Toast.makeText(this,"Unsupported file type. Choose an image, text file, PDF, or DOCX document.",Toast.LENGTH_LONG).show();
                        candidates.clear();
                        break;
                    }
                    long size=getAttachmentSizeBytes(uri);
                    if(size>MAX_ATTACHMENT_BYTES){
                        Toast.makeText(this,"Each attachment must be 20 MiB or smaller.",Toast.LENGTH_LONG).show();
                        candidates.clear();
                        break;
                    }
                    if(size>=0) totalBytes+=size;
                    if(totalBytes>MAX_TOTAL_ATTACHMENT_BYTES){
                        Toast.makeText(this,"Combined attachments must not exceed 40 MiB.",Toast.LENGTH_LONG).show();
                        candidates.clear();
                        break;
                    }
                }
            }
            selectedAttachments.clear();
            selectedAttachments.addAll(candidates);
            for(Uri uri:selectedAttachments){
                try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            }
            String attachmentStatus=selectedAttachments.isEmpty()?"No file selected yet.":"📎 "+selectedAttachments.size()+" attachment"+(selectedAttachments.size()==1?"":"s")+" staged for your next message.";
            if(attachmentBadge!=null) attachmentBadge.setText(selectedAttachments.isEmpty()?"No attachment":selectedAttachments.size()==1?"📎 1 attachment selected":"📎 "+selectedAttachments.size()+" attachments selected");
            if(attachmentWorkspaceStatus!=null) attachmentWorkspaceStatus.setText(attachmentStatus);
        }
    }

    private void handleWhatsAppContactResult(int resultCode, Intent data) {
        DeviceBridge.Callback callback = pendingWhatsAppCallback;
        String message = pendingWhatsAppMessage;
        pendingWhatsAppCallback = null; pendingWhatsAppMessage = null;
        if (callback == null) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { callback.onResult("Contact selection cancelled. No message was prepared or sent."); return; }
        String displayName = "Selected contact"; String phoneNumber = "";
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(data.getData(), new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex);
                if (numberIndex >= 0) phoneNumber = cursor.getString(numberIndex);
            }
        } catch (Exception e) { callback.onResult("The selected contact details could not be read. No message was prepared."); return; }
        finally { if (cursor != null) cursor.close(); }
        final String selectedName = displayName == null || displayName.trim().isEmpty() ? "Selected contact" : displayName.trim();
        final String selectedNumber = phoneNumber == null ? "" : phoneNumber.trim();
        if (selectedNumber.isEmpty()) { callback.onResult("The selected contact has no readable phone number. No message was prepared."); return; }
        new AlertDialog.Builder(this).setTitle("Confirm WhatsApp message")
            .setMessage("Recipient: " + selectedName + "\nPhone: " + selectedNumber + "\n\nMessage:\n" + message + "\n\nAmiki will open WhatsApp with this text. You will still need to press Send.")
            .setNegativeButton("Cancel", (dialog, which) -> callback.onResult("Message cancelled. Nothing was sent."))
            .setPositiveButton("Continue", (dialog, which) -> new CrossAppIntentGateway(this).openWhatsAppCompose(selectedNumber, message, callback::onResult))
            .setOnCancelListener(dialog -> callback.onResult("Message cancelled. Nothing was sent."))
            .show();
    }
    private boolean isSupportedAttachment(Uri uri){
        String mime=getContentResolver().getType(uri);
        for(String allowed:SUPPORTED_ATTACHMENT_MIME_TYPES) if(allowed.equalsIgnoreCase(mime)) return true;
        String name=getAttachmentFileName(uri).toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg")||name.endsWith(".jpeg")||name.endsWith(".png")||
               name.endsWith(".webp")||name.endsWith(".txt")||name.endsWith(".pdf")||
               name.endsWith(".docx");
    }

    private long getAttachmentSizeBytes(Uri uri){
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){
                int column=cursor.getColumnIndex(OpenableColumns.SIZE);
                if(column>=0&&!cursor.isNull(column)) return cursor.getLong(column);
            }
        }catch(Exception ignored){}
        return -1L;
    }

    private String sendMultipartRequest(
            String message,
            ArrayList<Uri> attachments,
            String requestId,
            String conversationId) throws Exception {
        String boundary="----AmikiBoundary"+System.currentTimeMillis();
        HttpURLConnection connection=ApiConnection.open(this, API+"/attachments");
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(270000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            try(OutputStream output=connection.getOutputStream()){
                String nl="\r\n";
                output.write(("--"+boundary+nl+"Content-Disposition: form-data; name=\"message\""+nl+nl+message+nl).getBytes(StandardCharsets.UTF_8));
                output.write(("--"+boundary+nl+"Content-Disposition: form-data; name=\"request_id\""+nl+nl+requestId+nl).getBytes(StandardCharsets.UTF_8));
                output.write(("--"+boundary+nl+"Content-Disposition: form-data; name=\"conversation_id\""+nl+nl+conversationId+nl).getBytes(StandardCharsets.UTF_8));
                long totalBytes=0L;
                for(Uri uri:attachments){
                    String filename=getAttachmentFileName(uri).replace("\\","_").replace("\"","_").replace("\r","_").replace("\n","_");
                    String mimeType=getAttachmentMimeType(uri);
                    long remainingTotal=MAX_TOTAL_ATTACHMENT_BYTES-totalBytes;
                    if(remainingTotal<=0) throw new java.io.IOException("Combined attachments must not exceed 40 MiB.");
                    long copied=copyUriToStream(uri,output,Math.min(MAX_ATTACHMENT_BYTES,remainingTotal));
                    totalBytes+=copied;
                    output.write(nl.getBytes(StandardCharsets.UTF_8));
                    // Prevent a provider that reports an unknown size from bypassing limits.
                    long reportedSize=getAttachmentSizeBytes(uri);
                    if(reportedSize>MAX_ATTACHMENT_BYTES || totalBytes>MAX_TOTAL_ATTACHMENT_BYTES)
                        throw new java.io.IOException("Attachment size limit exceeded.");
                    if(mimeType.equals("application/octet-stream"))
                        throw new java.io.IOException("Unsupported attachment type. Choose an image, text file, PDF, or DOCX document.");
                }
                output.write(("--"+boundary+"--"+nl).getBytes(StandardCharsets.UTF_8));
            }
            int code=connection.getResponseCode();
            java.io.InputStream responseStream=code>=200&&code<300?connection.getInputStream():connection.getErrorStream();
            StringBuilder raw=new StringBuilder();
            if(responseStream!=null){
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(responseStream,StandardCharsets.UTF_8))){
                    String line; while((line=reader.readLine())!=null){if(raw.length()<65536)raw.append(line);}
                }
            }
            if(code<200||code>=300){
                String detail=raw.toString();
                try{JSONObject error=new JSONObject(detail);detail=error.optString("detail",detail);}catch(Exception ignored){}
                throw new Exception("HTTP "+code+(detail.isEmpty()?"":": "+detail));
            }
            return raw.toString();
        } finally {
            connection.disconnect();
        }
    }

    private long copyUriToStream(Uri uri,OutputStream output,long maximumBytes) throws java.io.IOException {
        try(java.io.InputStream input=getContentResolver().openInputStream(uri)){
            if(input==null) throw new java.io.IOException("Unable to open attachment.");
            byte[] buffer=new byte[8192]; int read; long total=0L;
            while((read=input.read(buffer))!=-1){
                total+=read;
                if(total>maximumBytes) throw new java.io.IOException("Attachment size limit exceeded.");
                output.write(buffer,0,read);
            }
            return total;
        }
    }

    private String getAttachmentMimeType(Uri uri){
        String mime=getContentResolver().getType(uri);
        if(mime!=null){
            for(String allowed:SUPPORTED_ATTACHMENT_MIME_TYPES) if(allowed.equalsIgnoreCase(mime)) return allowed;
        }
        String name=getAttachmentFileName(uri).toLowerCase(Locale.ROOT);
        if(name.endsWith(".jpg")||name.endsWith(".jpeg")) return "image/jpeg";
        if(name.endsWith(".png")) return "image/png";
        if(name.endsWith(".webp")) return "image/webp";
        if(name.endsWith(".txt")) return "text/plain";
        if(name.endsWith(".pdf")) return "application/pdf";
        if(name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    private String getAttachmentFileName(Uri uri){
        Cursor cursor=getContentResolver().query(uri,null,null,null,null);
        if(cursor!=null){
            try{
                int index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(index>=0 && cursor.moveToFirst()) return cursor.getString(index);
            }finally{cursor.close();}
        }
        return "attachment";
    }

    private void checkHealth(){new Thread(()->{try{HttpURLConnection c=ApiConnection.open(this, HEALTH);c.setRequestMethod("GET");c.setConnectTimeout(2000);c.setReadTimeout(2000);boolean ok=c.getResponseCode()==200;handler.post(()->{backendHealthKnown=true;backendOnline=ok;online.setText(ok?"● ONLINE":"● OFFLINE");online.setTextColor(ok?Color.rgb(100,230,130):Color.rgb(255,100,100));if(active==0)refreshOrbStatus();});}catch(Exception e){handler.post(()->{backendHealthKnown=true;backendOnline=false;online.setText("● OFFLINE");online.setTextColor(Color.rgb(255,100,100));if(active==0)refreshOrbStatus();});}}).start();}

    private void refreshOrbStatus() {
        View dashboardAtmosphere = content.findViewWithTag("amiki_dashboard_atmosphere");
        if (dashboardAtmosphere instanceof AmikiAtmosphereView) {
            ((AmikiAtmosphereView) dashboardAtmosphere).setState(resolveOrbState());
        }
        View dashboardOrb = content.findViewWithTag("amiki_dashboard_orb");
        if (dashboardOrb instanceof AmikiOrbView) ((AmikiOrbView) dashboardOrb).setState(resolveOrbState());
        View chatOrb = content.findViewWithTag("amiki_chat_orb");
        if (chatOrb instanceof AmikiOrbView) ((AmikiOrbView) chatOrb).setState(resolveOrbState());
        View status = content.findViewWithTag("amiki_orb_status");
        if (status instanceof TextView) {
            ((TextView) status).setText(orbStateLabel());
            ((TextView) status).setContentDescription("Amiki status: " + orbStateLabel());
        }
        View chatLabel = content.findViewWithTag("amiki_chat_orb_label");
        if (chatLabel instanceof TextView) {
            ((TextView) chatLabel).setText("Your AI companion. Your memory. Your edge.\n" + orbStateLabel());
            ((TextView) chatLabel).setContentDescription("Amiki status: " + orbStateLabel());
        }
    }

    @Override public void onBackPressed(){if(drawer.getVisibility()==View.VISIBLE){closeDrawer();return;}super.onBackPressed();}
}