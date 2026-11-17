package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Read-only diagnostics plus explicit Inspection Agent and diagnostic-detail interactions. */
public class DiagnosticsScreen {
    public interface Callbacks {
        void onBack();
        void onMenu();
        void onDirectInspection(String message, LinearLayout content, ScrollView scroll, Button send, TextView status);
    }

    private static final int BG = Color.rgb(5, 7, 18);
    private static final int PANEL = Color.rgb(12, 15, 30);
    private static final int CARD = Color.rgb(17, 21, 40);
    private static final int TEXT = Color.rgb(245, 245, 250);
    private static final int MUTED = Color.rgb(165, 165, 185);
    private static final int GOOD = Color.rgb(100, 230, 130);
    private static final int WARN = Color.rgb(240, 190, 80);
    private static final int PURPLE = Color.rgb(139, 70, 255);

    private final Activity activity;
    private final Callbacks callbacks;
    private final String endpoint;
    private final String eventsEndpoint;
    private final String permanentDiagnosticsEndpoint;
    private final String permanentDiagnosticDetailEndpoint;
    private final String observabilityStatusEndpoint;
    private LinearLayout checks;
    private TextView overall;
    private TextView lastChecked;
    private final Map<String, LinearLayout> groupBodies = new LinkedHashMap<>();
    private final Map<String, TextView> groupHeaders = new LinkedHashMap<>();
    private int unsyncedEvents = 0;
    private String latestSummary = "Diagnostics have not completed yet.";
    private String latestCheckedAt = "Not checked yet.";
    private JSONObject latestRuntime, latestEventsResponse, latestPermanent, latestObservability;
    private JSONArray latestEvents;
    private LinearLayout eventList;
    private String eventSeverityFilter = "ALL";

    /* Kept at six arguments so the existing MobileShell integration remains source-compatible. */
    public DiagnosticsScreen(Activity activity, Callbacks callbacks, String endpoint, String eventsEndpoint,
                             String permanentDiagnosticsEndpoint, String observabilityStatusEndpoint) {
        this.activity = activity;
        this.callbacks = callbacks;
        this.endpoint = endpoint;
        this.eventsEndpoint = eventsEndpoint;
        this.permanentDiagnosticsEndpoint = permanentDiagnosticsEndpoint;
        this.permanentDiagnosticDetailEndpoint = permanentDiagnosticsEndpoint;
        this.observabilityStatusEndpoint = observabilityStatusEndpoint;
    }

    public LinearLayout build() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.setBackgroundColor(PANEL);
        Button back = new Button(activity);
        back.setText("‹ BACK"); back.setAllCaps(false); back.setTextColor(TEXT);
        back.setOnClickListener(v -> callbacks.onBack());
        TextView title = text("DIAGNOSTICS", 20, TEXT); title.setTypeface(null, Typeface.BOLD);
        TextView menu = text("☰", 24, TEXT); menu.setGravity(Gravity.CENTER); menu.setOnClickListener(v -> callbacks.onMenu());
        header.addView(back, new LinearLayout.LayoutParams(dp(80), dp(48)));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(28));

        LinearLayout summary = card();
        summary.setPadding(dp(18), dp(18), dp(18), dp(16));
        TextView eyebrow = text("AMIKI  /  SYSTEM HEALTH", 11, MUTED);
        eyebrow.setTypeface(null, Typeface.BOLD);
        summary.addView(eyebrow);
        overall = text("CHECKING DIAGNOSTICS…", 22, TEXT);
        overall.setTypeface(null, Typeface.BOLD);
        overall.setPadding(0, dp(8), 0, dp(4));
        summary.addView(overall);
        summary.addView(text("Status is calculated from returned evidence. Unavailable checks remain unknown.", 13, MUTED));
        lastChecked = text("Last checked: Not checked yet.", 12, MUTED);
        lastChecked.setPadding(0, dp(10), 0, 0);
        summary.addView(lastChecked);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(activity);
        refresh.setText("↻  Refresh"); refresh.setAllCaps(false); refresh.setTextColor(TEXT);
        refresh.setBackground(roundRect(PURPLE, 12));
        refresh.setOnClickListener(v -> load());
        Button share = new Button(activity);
        share.setText("Share report"); share.setAllCaps(false); share.setTextColor(TEXT);
        share.setBackground(roundRect(CARD, 12));
        share.setOnClickListener(v -> shareReport());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        actionParams.setMargins(0, dp(12), dp(6), 0);
        actions.addView(refresh, actionParams);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        shareParams.setMargins(dp(6), dp(12), 0, 0);
        actions.addView(share, shareParams);
        summary.addView(actions);
        Button copy = new Button(activity);
        copy.setText("Copy diagnostic report"); copy.setAllCaps(false); copy.setTextColor(TEXT);
        copy.setBackground(roundRect(CARD, 12)); copy.setOnClickListener(v -> copyReport());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-1, dp(42));
        copyParams.setMargins(0, dp(8), 0, 0); summary.addView(copy, copyParams);
        content.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        checks = new LinearLayout(activity);
        checks.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams checksParams = new LinearLayout.LayoutParams(-1, -2);
        checksParams.setMargins(0, dp(14), 0, 0);
        content.addView(checks, checksParams);

        TextView directLabel = text("DIRECT AGENT  •  INSPECTION", 11, PURPLE);
        directLabel.setTypeface(null, Typeface.BOLD); directLabel.setPadding(0, dp(20), 0, dp(4));
        content.addView(directLabel);
        EditText directInput = new EditText(activity);
        directInput.setHint("Ask the Inspection Agent to inspect runtime evidence...");
        android.content.SharedPreferences diagnosticPrefs = activity.getSharedPreferences("amiki_diagnostics", Activity.MODE_PRIVATE);
        directInput.setText(diagnosticPrefs.getString("inspection_draft", ""));
        directInput.setSelection(directInput.length());
        directInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { diagnosticPrefs.edit().putString("inspection_draft", s.toString()).apply(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        directInput.setHintTextColor(MUTED); directInput.setTextColor(TEXT); directInput.setTextSize(15);
        directInput.setMinLines(3); directInput.setGravity(Gravity.TOP | Gravity.START);
        directInput.setPadding(dp(14), dp(12), dp(14), dp(12)); directInput.setBackground(roundRect(CARD, 12));
        content.addView(directInput);
        Button directSend = new Button(activity); directSend.setText("INSPECT"); directSend.setAllCaps(false);
        directSend.setTextColor(TEXT); directSend.setBackground(roundRect(PURPLE, 12));
        content.addView(directSend, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView directStatus = text("INSPECTION AGENT • READY", 13, MUTED);
        directStatus.setPadding(0, dp(10), 0, dp(8)); content.addView(directStatus);
        directSend.setOnClickListener(v -> {
            String message = directInput.getText().toString().trim();
            if (message.isEmpty()) { directInput.setError("Enter an inspection request first."); return; }
            callbacks.onDirectInspection(message, content, scroll, directSend, directStatus);
            diagnosticPrefs.edit().remove("inspection_draft").apply();
            directInput.setText("");
        });

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        load();
        return root;
    }

    private void load() {
        overall.setText("Checking system evidence…");
        checks.removeAllViews();
        groupBodies.clear();
        groupHeaders.clear();
        SentinelJournal journal = new SentinelJournal(activity);
        unsyncedEvents = journal.unsyncedCount();
        addSentinelLocalSection(journal);
        new Thread(() -> {
            JSONObject runtime = fetchJson(endpoint);
            JSONObject events = fetchJson(eventsEndpoint);
            JSONObject permanent = fetchJson(permanentDiagnosticsEndpoint);
            JSONObject status = fetchJson(observabilityStatusEndpoint);
            activity.runOnUiThread(() -> {
                latestRuntime = runtime; latestEventsResponse = events; latestPermanent = permanent; latestObservability = status;
                latestEvents = events == null ? null : events.optJSONArray("events");
                renderRuntime(runtime);
                renderObservabilityStatus(status);
                renderEvents(events);
                renderPermanentDiagnostics(permanent);
                updateOverall(runtime, events, permanent, status);
            });
        }).start();
    }

    private void updateOverall(JSONObject runtime, JSONObject events, JSONObject permanent, JSONObject status) {
        int healthy = 0, warnings = 0, failed = 0, unknown = 0;
        JSONArray runtimeChecks = runtime == null ? null : runtime.optJSONArray("checks");
        if (runtimeChecks == null) {
            unknown++;
        } else {
            for (int i = 0; i < runtimeChecks.length(); i++) {
                JSONObject check = runtimeChecks.optJSONObject(i);
                if (check == null) { unknown++; continue; }
                String state = check.optString("status", "unknown").trim().toLowerCase(Locale.ROOT);
                if (state.equals("ok") || state.equals("green") || state.equals("healthy") || state.equals("success")) healthy++;
                else if (state.equals("warning") || state.equals("warn") || state.equals("degraded")) warnings++;
                else if (state.equals("failed") || state.equals("error") || state.equals("red") || state.equals("unhealthy")) failed++;
                else unknown++;
            }
        }
        if (events == null) unknown++;
        if (permanent == null) unknown++;
        if (status == null) unknown++;
        if (unsyncedEvents > 0) unknown++;
        String headline = failed > 0 ? "Attention required" : warnings > 0 ? "Warnings detected" : unknown > 0 ? "Partially verified" : "Checks passed";
        overall.setText(headline);
        overall.setTextColor(failed > 0 ? Color.rgb(255, 105, 115) : warnings > 0 ? WARN : unknown > 0 ? Color.rgb(100, 180, 255) : GOOD);
        latestCheckedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        lastChecked.setText("Last checked: " + latestCheckedAt + "  •  " + healthy + " healthy  ·  " + warnings + " warnings  ·  " + failed + " failed  ·  " + unknown + " unknown");
        latestSummary = buildSafeReport(headline, healthy, warnings, failed, unknown);
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (clipboard == null) { Toast.makeText(activity, "Clipboard is unavailable.", Toast.LENGTH_SHORT).show(); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("Amiki diagnostics report", latestSummary));
        Toast.makeText(activity, "Diagnostic report copied.", Toast.LENGTH_SHORT).show();
    }

    private String buildSafeReport(String headline, int healthy, int warnings, int failed, int unknown) {
        StringBuilder out = new StringBuilder();
        out.append("AMIKI DIAGNOSTICS REPORT\nGenerated: ").append(latestCheckedAt)
                .append("\nOverall: ").append(headline).append("\nHealthy: ").append(healthy)
                .append(" | Warnings: ").append(warnings).append(" | Failed: ").append(failed).append(" | Unknown: ").append(unknown)
                .append("\nUnsynced local Sentinel events: ").append(unsyncedEvents).append("\n\n");
        JSONArray checks = latestRuntime == null ? null : latestRuntime.optJSONArray("checks");
        out.append("RUNTIME CHECKS\n");
        if (checks == null) out.append("Runtime checks: unavailable\n");
        else for (int i=0;i<checks.length();i++) { JSONObject x=checks.optJSONObject(i); if(x!=null) out.append("- ").append(safeText(humanize(x.optString("name","Unknown")),80)).append(": ").append(safeText(x.optString("status","unknown"),24)).append('\n'); }
        out.append("\nRECENT BACKEND EVENTS (up to 8)\n");
        if(latestEvents==null) out.append("Events: unavailable\n");
        else for(int i=Math.max(0,latestEvents.length()-1);i>=Math.max(0,latestEvents.length()-8);i--){JSONObject x=latestEvents.optJSONObject(i);if(x!=null)out.append("- ").append(safeText(x.optString("severity","INFO"),20)).append(" | ").append(safeText(humanize(x.optString("event_type","Unknown")),80)).append(" | ").append(safeText(x.optString("summary","No summary"),160)).append('\n');}
        out.append("\nPERMANENT DIAGNOSTICS (up to 8)\n");
        JSONArray records=latestPermanent==null?null:latestPermanent.optJSONArray("diagnostics");
        if(records==null)out.append("Permanent diagnostics: unavailable\n");
        else for(int i=Math.max(0,records.length()-1);i>=Math.max(0,records.length()-8);i--){JSONObject x=records.optJSONObject(i);if(x!=null)out.append("- ").append(safeText(x.optString("failure_signature","Unknown"),100)).append(" | ").append(safeText(x.optString("status","unknown"),24)).append('\n');}
        out.append("\nOBSERVABILITY\n");
        if(latestObservability==null)out.append("Observability status: unavailable\\n");
        else out.append("Operational events: ").append(observabilityValue(latestObservability, "operational_events")).append("\nDiagnostic records: ").append(observabilityValue(latestObservability, "diagnostic_records")).append("\nRetention days: ").append(observabilityValue(latestObservability, "retention_days")).append('\n');
        out.append("\nNote: raw payloads, headers, credentials, and detailed free-text diagnostics are omitted.");
        return out.toString();
    }

    private String observabilityValue(JSONObject source, String key) {
        if (source == null || !source.has(key) || source.isNull(key)) return "Unknown";
        Object value = source.opt(key);
        return value == null || JSONObject.NULL.equals(value) ? "Unknown" : String.valueOf(value);
    }

    private String safeText(String value, int maxLength) {
        if(value==null || value.trim().isEmpty() || "null".equalsIgnoreCase(value)) return "Unknown";
        String safe=value.replaceAll("(?i)(token|password|secret|authorization|api[_ -]?key)[[:space:]]*[:=][[:space:]]*[^,;[:space:]]+","$1=[REDACTED]").replace('\r',' ').replace('\n',' ').replace('\t',' ').trim();
        return safe.length()>maxLength?safe.substring(0,maxLength)+"…":safe;
    }

    private void shareReport() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Amiki diagnostics report");
        intent.putExtra(Intent.EXTRA_TEXT, latestSummary);
        try { activity.startActivity(Intent.createChooser(intent, "Share diagnostics report")); }
        catch (Exception e) { Toast.makeText(activity, "Sharing is unavailable on this device.", Toast.LENGTH_SHORT).show(); }
    }

    private void addSentinelLocalSection(SentinelJournal journal) {
        List<JSONObject> events = journal.readRecent(8);
        JSONObject latest = events.isEmpty() ? null : events.get(events.size() - 1);
        LinearLayout item = card();
        item.addView(text("Sentinel · Local journal", 16, TEXT));
        TextView state = text(unsyncedEvents > 0 ? "SYNC PENDING" : "LOCAL JOURNAL", 13, unsyncedEvents > 0 ? WARN : MUTED);
        state.setTypeface(null, Typeface.BOLD); item.addView(state);
        item.addView(text("Unsynced local events: " + unsyncedEvents, 13, MUTED));
        item.addView(text(latest == null ? "The Sentinel has not recorded a local event yet." : latest.optString("summary", "No summary available."), 13, MUTED));
        addCard("Device & data", item);
    }

    private void renderRuntime(JSONObject result) {
        if (result == null) { addSimpleCard("BACKEND RUNTIME", "UNAVAILABLE", "Backend diagnostics could not be reached. Local Sentinel evidence remains available.", WARN); return; }
        String status = result.optString("status", "unknown").toUpperCase();
        addSimpleCard("BACKEND RUNTIME", status, "On-demand runtime diagnostics from the backend.", "GREEN".equals(status) ? GOOD : WARN);
        JSONArray items = result.optJSONArray("checks"); if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String checkStatus = item.optString("status", "unknown").toUpperCase();
            LinearLayout card = card();
            TextView heading = text(humanize(item.optString("name", "unknown")), 15, TEXT);
            heading.setTypeface(null, Typeface.BOLD);
            card.addView(heading);
            int chipColor = statusColor(checkStatus);
            TextView chip = text(checkStatus, 11, chipColor);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(9), dp(4), dp(9), dp(4));
            chip.setBackground(roundRect(Color.argb(34, Color.red(chipColor), Color.green(chipColor), Color.blue(chipColor)), 20));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
            chipParams.setMargins(0, dp(7), 0, dp(3));
            card.addView(chip, chipParams);
            String safeDetail = item.optString("detail", "").trim();
            if (safeDetail.isEmpty() || "null".equalsIgnoreCase(safeDetail)) {
                safeDetail = "No safe diagnostic detail was provided.";
            }
            card.addView(text(safeDetail, 13, MUTED));
            addCard(card);
        }
    }

    private void renderObservabilityStatus(JSONObject result) {
        if (result == null) { addSimpleCard("OBSERVABILITY", "UNAVAILABLE", "The backend observability store could not be reached.", WARN); return; }
        String operationalEvents = observabilityValue(result, "operational_events");
        String diagnosticRecords = observabilityValue(result, "diagnostic_records");
        String retentionDays = observabilityValue(result, "retention_days");
        boolean complete = !"Unknown".equals(operationalEvents) && !"Unknown".equals(diagnosticRecords)
                && !"Unknown".equals(retentionDays);
        String body = "Operational events: " + operationalEvents
                + "  •  Permanent diagnostics: " + diagnosticRecords
                + "  •  Retention: " + retentionDays + ("Unknown".equals(retentionDays) ? "" : " days");
        addSimpleCard("OBSERVABILITY", complete ? "ACTIVE" : "PARTIALLY VERIFIED", body, complete ? GOOD : WARN);
    }

    private void renderEvents(JSONObject result) {
        if(result==null){addSimpleCard("RECENT BACKEND EVENTS","UNAVAILABLE","No backend event stream available right now.",WARN);return;}
        JSONArray events=result.optJSONArray("events");
        if(events==null||events.length()==0){addSimpleCard("RECENT BACKEND EVENTS","EMPTY","No recent operational events were returned.",MUTED);return;}
        LinearLayout panel=card(); TextView heading=text("Recent backend events",15,TEXT);heading.setTypeface(null,Typeface.BOLD);panel.addView(heading);
        panel.addView(text(events.length()+" returned · sanitized operational events",12,MUTED));
        LinearLayout filters=new LinearLayout(activity);filters.setOrientation(LinearLayout.HORIZONTAL);filters.setPadding(0,dp(8),0,dp(8));
        String[] choices={"ALL","ERROR","WARNING","INFO"};
        for(String choice:choices){Button b=new Button(activity);b.setText(choice);b.setAllCaps(false);b.setTextSize(10);b.setTextColor(TEXT);b.setPadding(dp(4),0,dp(4),0);b.setMinWidth(0);b.setBackground(roundRect(CARD,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(36),1f);p.setMargins(dp(2),0,dp(2),0);filters.addView(b,p);b.setOnClickListener(v->{eventSeverityFilter=choice;updateEventFilters(filters,events);populateEventList(events);});}
        panel.addView(filters);eventList=new LinearLayout(activity);eventList.setOrientation(LinearLayout.VERTICAL);panel.addView(eventList);
        updateEventFilters(filters,events);populateEventList(events);addCard("Monitoring & activity",panel);
    }

    private void updateEventFilters(LinearLayout filters,JSONArray events){
        for(int i=0;i<filters.getChildCount();i++){View child=filters.getChildAt(i);if(!(child instanceof Button))continue;Button b=(Button)child;String choice=b.getTag()==null?b.getText().toString().replaceAll(" \\\\d+$",""):b.getTag().toString();b.setTag(choice);int count=0;if("ALL".equals(choice))count=events.length();else for(int j=0;j<events.length();j++){JSONObject x=events.optJSONObject(j);if(x!=null&&severityMatches(x.optString("severity","INFO"),choice))count++;}b.setText(choice+" "+count);b.setBackground(roundRect(eventSeverityFilter.equals(choice)?PURPLE:CARD,18));}
    }

    private boolean severityMatches(String severity,String filter){String v=severity==null?"INFO":severity.trim().toUpperCase(Locale.ROOT);if("ERROR".equals(filter))return v.equals("ERROR")||v.equals("CRITICAL")||v.equals("SECURITY")||v.equals("FATAL");if("WARNING".equals(filter))return v.equals("WARNING")||v.equals("WARN");if("INFO".equals(filter))return v.equals("INFO")||v.equals("DEBUG")||v.equals("RECOVERY")||v.equals("NOTICE");return true;}

    private void populateEventList(JSONArray events){if(eventList==null)return;eventList.removeAllViews();int shown=0;for(int i=events.length()-1;i>=0&&shown<8;i--){JSONObject x=events.optJSONObject(i);if(x==null)continue;String severity=x.optString("severity","INFO").toUpperCase(Locale.ROOT);if(!"ALL".equals(eventSeverityFilter)&&!severityMatches(severity,eventSeverityFilter))continue;LinearLayout item=card();TextView title=text(humanize(x.optString("event_type","Unknown")),14,TEXT);title.setTypeface(null,Typeface.BOLD);item.addView(title);int c=statusColor(severity);TextView chip=text(severity,11,c);chip.setTypeface(null,Typeface.BOLD);chip.setPadding(dp(8),dp(3),dp(8),dp(3));chip.setBackground(roundRect(Color.argb(34,Color.red(c),Color.green(c),Color.blue(c)),20));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.setMargins(0,dp(5),0,dp(5));item.addView(chip,cp);item.addView(text(safeText(x.optString("summary","No summary."),220),13,MUTED));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(6));eventList.addView(item,p);shown++;}if(shown==0)eventList.addView(text("No events match this severity filter.",13,MUTED));}

    private void renderPermanentDiagnostics(JSONObject result) {
        if (result == null) { addSimpleCard("PERMANENT DIAGNOSTICS", "UNAVAILABLE", "Permanent diagnostic history is unavailable while the backend is offline.", WARN); return; }
        JSONArray records = result.optJSONArray("diagnostics");
        if (records == null || records.length() == 0) { addSimpleCard("PERMANENT DIAGNOSTICS", "EMPTY", "No promoted diagnostic records are currently stored.", MUTED); return; }
        addSimpleCard("PERMANENT DIAGNOSTICS", String.valueOf(records.length()), "Historical findings are bounded summaries. Tap a record to open its details.", TEXT);
        int start = Math.max(0, records.length() - 8);
        for (int i = records.length() - 1; i >= start; i--) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;

            String signature = record.optString("failure_signature", "UNKNOWN");
            String status = record.optString("status", "UNKNOWN").toUpperCase();
            JSONArray symptoms = record.optJSONArray("symptoms");
            String summary = (symptoms != null && symptoms.length() > 0)
                    ? symptoms.optString(0, "No summary.")
                    : "No summary.";

            LinearLayout card = card();
            TextView heading = text(signature + "  •  " + status, 14, TEXT);
            heading.setTypeface(null, Typeface.BOLD);
            card.addView(heading);
            card.addView(text(summary, 13, MUTED));

            TextView open = text("TAP TO OPEN DETAILS", 13, TEXT);
            open.setTypeface(null, Typeface.BOLD);
            open.setPadding(0, dp(8), 0, 0);
            card.addView(open);

            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> showDiagnosticDetail(signature));
            addCard(card);
        }
    }

    private void showDiagnosticDetail(String signature) {
        new Thread(() -> {
            JSONObject result = fetchJson(permanentDiagnosticDetailEndpoint + "/" + Uri.encode(signature));
            activity.runOnUiThread(() -> {
                if (result == null) { Toast.makeText(activity, "Diagnostic details are unavailable.", Toast.LENGTH_SHORT).show(); return; }
                JSONObject diagnostic = result.optJSONObject("diagnostic");
                if (diagnostic == null) { Toast.makeText(activity, "Diagnostic record was not returned.", Toast.LENGTH_SHORT).show(); return; }
                ScrollView scroll = new ScrollView(activity);
                scroll.setBackgroundColor(PANEL);
                LinearLayout detailContent = buildDiagnosticDetail(diagnostic);
                scroll.addView(detailContent);
                new AlertDialog.Builder(activity)
                        .setTitle("DIAGNOSTIC DETAIL")
                        .setView(scroll)
                        .setPositiveButton("CLOSE", null)
                        .show();
            });
        }).start();
    }

    private LinearLayout buildDiagnosticDetail(JSONObject r) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        content.setBackgroundColor(PANEL);

        addDetailSection(content, "WHAT HAPPENED");
        addDetailRow(content, "Failure signature", r.optString("failure_signature", "Unknown"));
        addDetailRow(content, "Category", humanize(r.optString("category", "Unknown")));
        addDetailRow(content, "Status", humanize(r.optString("status", "Unknown")));
        addDetailRow(content, "Occurrences", String.valueOf(r.optInt("occurrence_count", 0)));

        addDetailSection(content, "WHEN IT WAS DETECTED");
        addDetailRow(content, "First seen", r.optString("first_seen", "Unknown"));
        addDetailRow(content, "Last seen", r.optString("last_seen", "Unknown"));

        addDetailSection(content, "EVIDENCE");
        addDetailList(content, "Observed symptoms", r.optJSONArray("symptoms"));
        addDetailList(content, "Confirmed evidence", r.optJSONArray("confirmed_evidence"));

        addDetailSection(content, "POSSIBLE CAUSES");
        addDetailList(content, "Hypotheses", r.optJSONArray("hypotheses"));

        addDetailSection(content, "RESOLUTION");
        String resolution = r.optString("known_resolution", "");
        addDetailRow(content, "Known resolution", resolution.isEmpty() || "null".equalsIgnoreCase(resolution) ? "No resolution recorded." : resolution);

        addDetailSection(content, "SOURCE");
        addDetailRow(content, "Evidence source", humanize(r.optString("provenance", "Unknown")));
        addDetailList(content, "Related events", r.optJSONArray("related_event_ids"));

        return content;
    }

    private void addDetailSection(LinearLayout content, String title) {
        TextView heading = text(title, 13, PURPLE);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(dp(2), dp(14), dp(2), dp(6));
        content.addView(heading);
    }

    private void addDetailRow(LinearLayout content, String label, String value) {
        LinearLayout row = card();
        row.setPadding(dp(14), dp(11), dp(14), dp(11));

        TextView labelView = text(label, 12, MUTED);
        labelView.setTypeface(null, Typeface.BOLD);
        row.addView(labelView);

        TextView valueView = text(value == null || value.trim().isEmpty() ? "Not recorded." : value, 16, TEXT);
        valueView.setTextIsSelectable(true);
        valueView.setLineSpacing(dp(2), 1.0f);
        valueView.setPadding(0, dp(3), 0, 0);
        row.addView(valueView);

        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(1, dp(6));
        content.addView(new TextView(activity), spacer);
    }

    private void addDetailList(LinearLayout content, String label, JSONArray values) {
        LinearLayout row = card();
        row.setPadding(dp(14), dp(11), dp(14), dp(11));

        TextView labelView = text(label, 12, MUTED);
        labelView.setTypeface(null, Typeface.BOLD);
        row.addView(labelView);

        if (values == null || values.length() == 0) {
            row.addView(text("Nothing recorded.", 16, TEXT));
        } else {
            for (int i = 0; i < values.length(); i++) {
                String item = values.optString(i, "").trim();
                if (item.isEmpty()) continue;
                TextView itemView = text("• " + item, 16, TEXT);
                itemView.setTextIsSelectable(true);
                itemView.setLineSpacing(dp(2), 1.0f);
                itemView.setPadding(0, dp(5), 0, dp(2));
                row.addView(itemView);
            }
        }

        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(1, dp(6));
        content.addView(new TextView(activity), spacer);
    }

    private String humanize(String value) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value)) return "Unknown";
        String normalized = value.replace('_', ' ').replace('-', ' ').trim().toLowerCase();
        StringBuilder out = new StringBuilder();
        for (String word : normalized.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }


    private JSONObject fetchJson(String url) {
        HttpURLConnection connection = null;
        try {
            connection = ApiConnection.open(activity, url);
            connection.setRequestMethod("GET"); connection.setConnectTimeout(3500); connection.setReadTimeout(3500);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) body.append(line);
            }
            return new JSONObject(body.toString());
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private void addSimpleCard(String title, String state, String body, int stateColor) {
        LinearLayout item = card();
        TextView heading = text(humanize(title), 15, TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        item.addView(heading);
        TextView chip = text(state, 11, stateColor);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(9), dp(4), dp(9), dp(4));
        chip.setBackground(roundRect(Color.argb(34, Color.red(stateColor), Color.green(stateColor), Color.blue(stateColor)), 20));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.setMargins(0, dp(7), 0, dp(4));
        item.addView(chip, chipParams);
        item.addView(text(body, 13, MUTED));
        String group = title.contains("RUNTIME") || title.contains("API") || title.contains("CONFIGURATION") ? "Core services"
                : title.contains("PROVIDER") || title.contains("CAPABILIT") || title.contains("ROUTER") ? "AI & capabilities"
                : title.contains("SENTINEL") || title.contains("DEVICE") || title.contains("MEMORY") || title.contains("ATTACHMENT") ? "Device & data"
                : "Monitoring & activity";
        addCard(group, item);
    }

    private int statusColor(String status) {
        String value = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (value.equals("OK") || value.equals("HEALTHY") || value.equals("SUCCESS") || value.equals("GREEN") || value.equals("ACTIVE")) return GOOD;
        if (value.equals("FAILED") || value.equals("ERROR") || value.equals("RED") || value.equals("UNHEALTHY")) return Color.rgb(255, 105, 115);
        if (value.equals("WARNING") || value.equals("WARN") || value.equals("DEGRADED") || value.equals("PENDING") || value.equals("SYNC PENDING")) return WARN;
        return Color.rgb(100, 180, 255);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(15), dp(12), dp(15), dp(12));
        c.setBackground(roundRect(CARD, 16));
        return c;
    }

    private void addCard(LinearLayout item) {
        String label = "";
        if (item.getChildCount() > 0 && item.getChildAt(0) instanceof TextView) label = ((TextView)item.getChildAt(0)).getText().toString().toUpperCase(Locale.ROOT);
        String group = label.contains("RUNTIME") || label.contains("API HEALTH") || label.contains("CONFIGURATION") ? "Core services"
                : label.contains("PROVIDER") || label.contains("CAPABILIT") || label.contains("ROUTER") ? "AI & capabilities"
                : label.contains("SENTINEL") || label.contains("DEVICE") || label.contains("MEMORY") || label.contains("ATTACHMENT") ? "Device & data"
                : "Monitoring & activity";
        addCard(group, item);
    }

    private void addCard(String group, LinearLayout item) {
        LinearLayout body = groupBodies.get(group);
        if (body == null) {
            LinearLayout section = new LinearLayout(activity);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setPadding(0, dp(4), 0, dp(4));
            LinearLayout headingRow = new LinearLayout(activity);
            headingRow.setGravity(Gravity.CENTER_VERTICAL);
            headingRow.setPadding(dp(12), dp(12), dp(12), dp(12));
            headingRow.setBackground(roundRect(PANEL, 14));
            TextView heading = text(group, 16, TEXT);
            heading.setTypeface(null, Typeface.BOLD);
            headingRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView chevron = text("⌄", 22, MUTED);
            headingRow.addView(chevron);
            body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(8), dp(8), dp(8), 0);
            body.setVisibility(View.GONE);
            LinearLayout target = body;
            headingRow.setClickable(true);
            headingRow.setFocusable(true);
            headingRow.setOnClickListener(v -> {
                boolean expand = target.getVisibility() != View.VISIBLE;
                target.setVisibility(expand ? View.VISIBLE : View.GONE);
                chevron.setText(expand ? "⌃" : "⌄");
            });
            section.addView(headingRow);
            section.addView(body);
            LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2);
            sectionParams.setMargins(0, 0, 0, dp(8));
            checks.addView(section, sectionParams);
            groupBodies.put(group, body);
            groupHeaders.put(group, heading);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(7));
        body.addView(item, p);
    }
    private TextView text(String value, float size, int color) { TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    private GradientDrawable roundRect(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(float value) { return (int)(value * activity.getResources().getDisplayMetrics().density + .5f); }
}
