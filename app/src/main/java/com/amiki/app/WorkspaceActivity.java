package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class WorkspaceActivity extends Activity {
    private static final String DIRECT_AGENT = ApiEndpoints.DIRECT_AGENT;
    // Match the shared Midnight Scholar dark palette used by the main Amiki shell.
    private static final int BG = 0xFF0E1224;
    private static final int PANEL = 0xFF171C33;
    private static final int CARD = 0xFF1D2340;
    private static final int PURPLE = 0xFF8B7CFF;
    private static final int CYAN = 0xFF4DE1C1;
    private static final int TEXT = 0xFFE8EAF6;
    private static final int MUTED = 0xFFADB4D0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String activeProject = "Amiki Backend";
    private String activeProjectId = "amiki-backend";
    private JSONArray workspaceProjects = new JSONArray();
    private EditText requestInput;
    private Button sendButton;
    private TextView statusView;
    private TextView resultView;
    private LinearLayout projectChoices;
    private ScrollView pageScroll;
    private LinearLayout workspacePageHost;
    private Button[] workspaceNavButtons;
    private String workspacePath = ".";
    private LinearLayout workspaceEntries;
    private TextView workspacePathView;
    private TextView workspaceStatusView;
    private TextView workspaceFileTitle;
    private EditText workspaceFileContent;
    private Button workspaceSaveButton;
    private Button workspaceReviewButton;
    private Button workspaceAiProposalButton;
    private Button workspaceCheckButton;
    private Button workspaceRunButton;
    private TextView workspaceExecutionOutput;
    private EditText workspaceCommandInput;
    private Button workspaceCommandButton;
    private TextView workspaceCommandOutput;
    private LinearLayout recentOperationsContainer;
    private String workspaceFilePath = "";
    private String workspaceOriginalContent = "";
    private EditText workspaceSearchInput;
    private Button workspaceSearchButton;
    private LinearLayout workspaceSearchResults;
    private Button workspaceGitButton;
    private Button workspaceUnstagedDiffButton;
    private Button workspaceStagedDiffButton;
    private TextView workspaceGitOutput;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView label(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(PANEL);

        LinearLayout root = vertical();
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(16), dp(8));
        header.setBackgroundColor(PANEL);

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(24);
        back.setTextColor(TEXT);
        back.setAllCaps(false);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        header.addView(back, lp(dp(48), dp(48)));

        LinearLayout heading = vertical();
        TextView title = label("WORKSPACE", 18, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView eyebrow = label("MIDNIGHT SCHOLAR  •  DEVELOPMENT STUDIO", 9, CYAN);
        heading.addView(title);
        heading.addView(eyebrow);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        TextView mark = label("AMIKI  ◈", 11, CYAN);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(mark);
        root.addView(header, lp(-1, dp(68)));

        HorizontalScrollView navigationScroll = new HorizontalScrollView(this);
        navigationScroll.setHorizontalScrollBarEnabled(false);
        navigationScroll.setBackgroundColor(PANEL);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(12), 0, dp(12), dp(10));
        workspaceNavButtons = new Button[5];
        String[] destinations = {"OVERVIEW", "PROJECTS", "FILES", "ENGINEERING", "ACTIVITY"};
        for (int i = 0; i < destinations.length; i++) {
            final int destination = i;
            Button tab = new Button(this);
            tab.setText(destinations[i]);
            tab.setTextSize(10);
            tab.setAllCaps(false);
            tab.setTextColor(TEXT);
            tab.setMinWidth(0);
            tab.setPadding(dp(13), 0, dp(13), 0);
            tab.setBackground(rounded(i == 0 ? Color.rgb(47, 43, 82) : CARD, 18));
            tab.setOnClickListener(v -> switchWorkspaceSection(destination));
            LinearLayout.LayoutParams tabParams = lp(-2, dp(38));
            tabParams.setMargins(0, 0, dp(7), 0);
            navigation.addView(tab, tabParams);
            workspaceNavButtons[i] = tab;
        }
        navigationScroll.addView(navigation);
        root.addView(navigationScroll, lp(-1, dp(48)));

        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(false);
        workspacePageHost = vertical();
        workspacePageHost.setPadding(dp(18), dp(18), dp(18), dp(30));

        LinearLayout overviewPage = vertical();
        overviewPage.addView(sectionHeading("YOUR WORKSPACE"));
        TextView welcome = label("A quieter place to build ambitious things.", 23, TEXT);
        welcome.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        overviewPage.addView(welcome);
        TextView overviewSubtitle = label(
                "Projects, source files and engineering work — organised around what you are building.",
                13, MUTED);
        overviewSubtitle.setPadding(0, dp(7), 0, dp(14));
        overviewPage.addView(overviewSubtitle);

        LinearLayout activeCard = card();
        activeCard.addView(label("CURRENT PROJECT", 10, CYAN));
        TextView activeLabel = label(activeProject, 20, TEXT);
        activeLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        activeLabel.setTag("active_project_label");
        activeCard.addView(activeLabel);
        TextView activeDescription = label(
                "Your selected local project. Project context is passed explicitly to Engineering.",
                12, MUTED);
        activeDescription.setPadding(0, dp(5), 0, 0);
        activeCard.addView(activeDescription);
        overviewPage.addView(activeCard, lp(-1, -2));

        overviewPage.addView(sectionHeading("CONTINUE WORKING"));
        addWorkspaceShortcut(overviewPage, "⌘  Projects",
                "Switch projects, create a local workspace or search project files.", 1);
        addWorkspaceShortcut(overviewPage, "▤  Files & source",
                "Browse, inspect and edit approved project files.", 2);
        addWorkspaceShortcut(overviewPage, "✦  Engineering",
                "Ask the Engineering Agent to inspect, plan or debug.", 3);
        addWorkspaceShortcut(overviewPage, "⌁  Activity & Git",
                "Review recent Workspace actions and read-only Git state.", 4);
        TextView principle = label(
                "REAL DATA ONLY  •  No fabricated progress, agent counts or task metrics.",
                10, CYAN);
        principle.setPadding(0, dp(18), 0, 0);
        overviewPage.addView(principle);

        LinearLayout projectsPage = vertical();
        projectsPage.addView(sectionHeading("PROJECT REGISTRY"));
        TextView projectIntro = label(
                "Choose a project available to the local backend. Local projects stay on this device.",
                12, MUTED);
        projectIntro.setPadding(0, 0, 0, dp(10));
        projectsPage.addView(projectIntro);
        projectChoices = vertical();
        projectsPage.addView(projectChoices, lp(-1, -2));
        renderProjectChoices();

        Button createProjectButton = new Button(this);
        createProjectButton.setText("＋  NEW LOCAL PROJECT");
        createProjectButton.setTextColor(TEXT);
        createProjectButton.setAllCaps(false);
        createProjectButton.setBackground(rounded(Color.rgb(43, 57, 93), 14));
        createProjectButton.setOnClickListener(v -> promptNewWorkspaceProject());
        LinearLayout.LayoutParams createProjectParams = lp(-1, dp(50));
        createProjectParams.setMargins(0, dp(8), 0, dp(14));
        projectsPage.addView(createProjectButton, createProjectParams);
        projectsPage.addView(sectionHeading("PROJECT-WIDE SEARCH"));
        projectsPage.addView(buildWorkspaceSearch(), lp(-1, -2));

        LinearLayout filesPage = vertical();
        filesPage.addView(sectionHeading("SOURCE EXPLORER"));
        TextView filesIntro = label(
                "Browse the selected project. Changes remain approval-gated; sensitive paths stay protected.",
                12, MUTED);
        filesIntro.setPadding(0, 0, 0, dp(10));
        filesPage.addView(filesIntro);
        filesPage.addView(buildWorkspaceBrowser(), lp(-1, -2));

        LinearLayout engineeringPage = vertical();
        engineeringPage.addView(sectionHeading("ENGINEERING AGENT"));
        LinearLayout composer = card();
        composer.addView(label("DIRECT  •  PROJECT-AWARE REQUEST", 10, PURPLE));
        requestInput = new EditText(this);
        requestInput.setHint("Describe what you want to inspect, design or debug…");
        requestInput.setHintTextColor(MUTED);
        requestInput.setTextColor(TEXT);
        requestInput.setTextSize(14);
        requestInput.setMinLines(3);
        requestInput.setGravity(Gravity.TOP | Gravity.START);
        requestInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        requestInput.setBackground(rounded(Color.rgb(18, 23, 46), 12));
        LinearLayout.LayoutParams inputParams = lp(-1, -2);
        inputParams.setMargins(0, dp(10), 0, dp(9));
        composer.addView(requestInput, inputParams);

        sendButton = new Button(this);
        sendButton.setText("SEND TO ENGINEERING");
        sendButton.setTextColor(TEXT);
        sendButton.setAllCaps(false);
        sendButton.setBackground(rounded(PURPLE, 13));
        sendButton.setOnClickListener(v -> submitRequest());
        composer.addView(sendButton, lp(-1, dp(48)));

        statusView = label("READY  •  No request sent yet", 11, MUTED);
        statusView.setPadding(0, dp(10), 0, 0);
        composer.addView(statusView);
        engineeringPage.addView(composer, lp(-1, -2));

        engineeringPage.addView(sectionHeading("QUICK PROMPTS"));
        addQuickAction(engineeringPage, "⌕  Inspect project",
                "Inspect the selected project using accessible tools. Keep the inspection read-only and report access limits.");
        addQuickAction(engineeringPage, "⚒  Investigate a bug",
                "Investigate this bug using available evidence and readable source. Separate verified facts from hypotheses. Do not modify files.");
        addQuickAction(engineeringPage, "▤  Plan a change",
                "Prepare an implementation plan for the requested change. Name only files and tests that can be verified. Do not claim changes were applied.");
        addQuickAction(engineeringPage, "✓  Review tests & CI",
                "Identify the real tests and GitHub Actions workflow for this project. Do not claim tests or builds ran without evidence.");

        engineeringPage.addView(sectionHeading("LATEST RESPONSE"));
        resultView = label("Engineering responses will appear here.", 13, MUTED);
        resultView.setPadding(dp(14), dp(14), dp(14), dp(14));
        resultView.setBackground(rounded(CARD, 14));
        resultView.setTextIsSelectable(true);
        engineeringPage.addView(resultView, lp(-1, -2));

        LinearLayout activityPage = vertical();
        activityPage.addView(sectionHeading("REPOSITORY INSIGHT"));
        activityPage.addView(buildWorkspaceGitInspector(), lp(-1, -2));
        activityPage.addView(sectionHeading("RECENT WORKSPACE ACTIVITY"));
        recentOperationsContainer = vertical();
        activityPage.addView(recentOperationsContainer, lp(-1, -2));
        renderRecentOperations();
        LinearLayout capabilityNote = card();
        capabilityNote.addView(label("CONNECTED CAPABILITIES", 10, CYAN));
        TextView note = label(
                "The local backend supplies project roots and file operations. Sensitive paths are hidden. File changes require approval. Python execution is not sandboxed and requires separate approval. Git inspection is read-only. GitHub repository edits and APK builds remain separate workflows.",
                12, MUTED);
        note.setPadding(0, dp(7), 0, 0);
        capabilityNote.addView(note);
        LinearLayout.LayoutParams noteParams = lp(-1, -2);
        noteParams.setMargins(0, dp(12), 0, 0);
        activityPage.addView(capabilityNote, noteParams);

        workspacePageHost.addView(overviewPage, lp(-1, -2));
        workspacePageHost.addView(projectsPage, lp(-1, -2));
        workspacePageHost.addView(filesPage, lp(-1, -2));
        workspacePageHost.addView(engineeringPage, lp(-1, -2));
        workspacePageHost.addView(activityPage, lp(-1, -2));
        pageScroll.addView(workspacePageHost, new ViewGroup.LayoutParams(-1, -2));
        root.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        switchWorkspaceSection(0);
        loadWorkspaceTree(".");
        loadWorkspaceProjects();
    }

    private void switchWorkspaceSection(int destination) {
        if (workspacePageHost == null || destination < 0
                || destination >= workspacePageHost.getChildCount()) return;
        for (int i = 0; i < workspacePageHost.getChildCount(); i++) {
            workspacePageHost.getChildAt(i).setVisibility(i == destination ? View.VISIBLE : View.GONE);
        }
        if (workspaceNavButtons != null) {
            for (int i = 0; i < workspaceNavButtons.length; i++) {
                workspaceNavButtons[i].setBackground(rounded(
                        i == destination ? Color.rgb(47, 43, 82) : CARD, 18));
                workspaceNavButtons[i].setTextColor(i == destination ? CYAN : MUTED);
            }
        }
        pageScroll.scrollTo(0, 0);
    }

    private void addWorkspaceShortcut(LinearLayout parent, String title,
            String description, int destination) {
        LinearLayout item = card();
        item.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(title, 15, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        item.addView(heading);
        TextView detail = label(description, 12, MUTED);
        detail.setPadding(0, dp(4), 0, 0);
        item.addView(detail);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(v -> switchWorkspaceSection(destination));
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.setMargins(0, 0, 0, dp(8));
        parent.addView(item, params);
    }

    private LinearLayout buildWorkspaceSearch() {
        LinearLayout panel = card();
        panel.addView(label("SEARCH READABLE PROJECT FILES", 10, CYAN));
        workspaceSearchInput = new EditText(this);
        workspaceSearchInput.setSingleLine(true);
        workspaceSearchInput.setHint("Search code or text…");
        workspaceSearchInput.setHintTextColor(MUTED);
        workspaceSearchInput.setTextColor(TEXT);
        workspaceSearchInput.setTextSize(14);
        workspaceSearchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        workspaceSearchInput.setBackground(rounded(Color.rgb(18, 23, 46), 10));
        LinearLayout.LayoutParams inputParams = lp(-1, dp(48));
        inputParams.setMargins(0, dp(8), 0, dp(8));
        panel.addView(workspaceSearchInput, inputParams);

        workspaceSearchButton = new Button(this);
        workspaceSearchButton.setText("SEARCH ACTIVE PROJECT");
        workspaceSearchButton.setTextColor(TEXT);
        workspaceSearchButton.setAllCaps(false);
        workspaceSearchButton.setBackground(rounded(Color.rgb(43, 57, 93), 12));
        workspaceSearchButton.setOnClickListener(v -> searchWorkspaceProject());
        panel.addView(workspaceSearchButton, lp(-1, dp(44)));

        workspaceSearchResults = vertical();
        workspaceSearchResults.setPadding(0, dp(8), 0, 0);
        panel.addView(workspaceSearchResults, lp(-1, -2));
        workspaceSearchResults.addView(
                label("Search results will appear here. Sensitive paths and non-text files are excluded.", 12, MUTED),
                lp(-1, -2));
        return panel;
    }

    private void searchWorkspaceProject() {
        String query = workspaceSearchInput.getText().toString().trim();
        if (query.isEmpty()) {
            workspaceSearchInput.setError("Enter text to search for.");
            return;
        }
        final String projectId = activeProjectId;
        final String projectName = activeProject;
        workspaceSearchButton.setEnabled(false);
        workspaceSearchButton.setText("SEARCHING…");
        workspaceSearchResults.removeAllViews();
        workspaceSearchResults.addView(
                label("Searching " + projectName + "…", 12, MUTED), lp(-1, -2));

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String url = ApiEndpoints.workspace("/search?q=")
                        + URLEncoder.encode(query, "UTF-8")
                        + "&path=" + URLEncoder.encode(".", "UTF-8")
                        + "&project_id=" + URLEncoder.encode(projectId, "UTF-8");
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(20000);
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }
                JSONObject response = new JSONObject(raw.toString());
                if (!"success".equals(response.optString("status"))
                        || !projectId.equals(response.optString("project_id"))) {
                    throw new java.io.IOException("Search response did not match the active project.");
                }
                JSONArray results = response.optJSONArray("results");
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceSearchResults.removeAllViews();
                    int count = results == null ? 0 : results.length();
                    workspaceSearchResults.addView(label(
                            count + " MATCHES • " + response.optInt("scanned_files") + " FILES SCANNED"
                                    + (response.optBoolean("truncated") ? " • RESULTS LIMITED" : ""),
                            10, CYAN), lp(-1, -2));
                    if (count == 0) {
                        workspaceSearchResults.addView(
                                label("No matches in readable text files.", 12, MUTED), lp(-1, -2));
                    }
                    for (int i = 0; i < count; i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item == null) continue;
                        String path = item.optString("path", "");
                        int lineNumber = item.optInt("line", 0);
                        String snippet = item.optString("text", "");
                        TextView row = label(path + ":" + lineNumber + "\n" + snippet, 12, TEXT);
                        row.setPadding(dp(10), dp(9), dp(10), dp(9));
                        row.setBackground(rounded(CARD, 9));
                        row.setOnClickListener(v -> {
                            if (path.isEmpty()) return;
                            loadWorkspaceFile(path);
                        });
                        LinearLayout.LayoutParams rowParams = lp(-1, -2);
                        rowParams.setMargins(0, dp(5), 0, 0);
                        workspaceSearchResults.addView(row, rowParams);
                    }
                    if (response.optBoolean("truncated")) {
                        workspaceSearchResults.addView(label(
                                "Search stopped at a safety limit. Narrow the query or search a subdirectory.",
                                11, MUTED), lp(-1, -2));
                    }
                    recordWorkspaceOperation("Searched " + projectName + " for " + query);
                });
            } catch (Exception error) {
                String message = "SEARCH FAILED • " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage());
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceSearchResults.removeAllViews();
                    workspaceSearchResults.addView(label(message, 12, MUTED), lp(-1, -2));
                });
            } finally {
                if (connection != null) connection.disconnect();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceSearchButton.setEnabled(true);
                    workspaceSearchButton.setText("SEARCH ACTIVE PROJECT");
                });
            }
        }).start();
    }

    private LinearLayout buildWorkspaceGitInspector() {
        LinearLayout panel = card();
        panel.addView(label("BRANCH • WORKING TREE • DIFF SUMMARY", 10, CYAN));
        workspaceGitButton = new Button(this);
        workspaceGitButton.setText("INSPECT GIT STATUS");
        workspaceGitButton.setTextColor(TEXT);
        workspaceGitButton.setAllCaps(false);
        workspaceGitButton.setBackground(rounded(Color.rgb(43, 57, 93), 12));
        workspaceGitButton.setOnClickListener(v -> inspectWorkspaceGit());
        LinearLayout.LayoutParams buttonParams = lp(-1, dp(44));
        buttonParams.setMargins(0, dp(8), 0, dp(8));
        panel.addView(workspaceGitButton, buttonParams);
        LinearLayout diffButtons = new LinearLayout(this);
        diffButtons.setOrientation(LinearLayout.HORIZONTAL);
        workspaceUnstagedDiffButton = new Button(this);
        workspaceUnstagedDiffButton.setText("PREVIEW UNSTAGED");
        workspaceUnstagedDiffButton.setTextColor(TEXT);
        workspaceUnstagedDiffButton.setAllCaps(false);
        workspaceUnstagedDiffButton.setTextSize(10);
        workspaceUnstagedDiffButton.setBackground(rounded(Color.rgb(43, 57, 93), 10));
        workspaceUnstagedDiffButton.setOnClickListener(v -> previewWorkspaceGitDiff(false));
        diffButtons.addView(workspaceUnstagedDiffButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        workspaceStagedDiffButton = new Button(this);
        workspaceStagedDiffButton.setText("PREVIEW STAGED");
        workspaceStagedDiffButton.setTextColor(TEXT);
        workspaceStagedDiffButton.setAllCaps(false);
        workspaceStagedDiffButton.setTextSize(10);
        workspaceStagedDiffButton.setBackground(rounded(Color.rgb(43, 57, 93), 10));
        workspaceStagedDiffButton.setOnClickListener(v -> previewWorkspaceGitDiff(true));
        LinearLayout.LayoutParams stagedParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        stagedParams.setMargins(dp(6), 0, 0, 0);
        diffButtons.addView(workspaceStagedDiffButton, stagedParams);
        LinearLayout.LayoutParams diffParams = lp(-1, -2);
        diffParams.setMargins(0, 0, 0, dp(8));
        panel.addView(diffButtons, diffParams);
        workspaceGitOutput = label(
                "Git status and staged/unstaged diff summaries appear here. This inspector does not change repository state.",
                12, MUTED);
        workspaceGitOutput.setTextIsSelectable(true);
        workspaceGitOutput.setPadding(dp(10), dp(10), dp(10), dp(10));
        workspaceGitOutput.setBackground(rounded(Color.rgb(12, 16, 31), 10));
        panel.addView(workspaceGitOutput, lp(-1, -2));
        return panel;
    }

    private void previewWorkspaceGitDiff(boolean staged) {
        final String projectId = activeProjectId;
        final String projectName = activeProject;
        workspaceUnstagedDiffButton.setEnabled(false);
        workspaceStagedDiffButton.setEnabled(false);
        workspaceGitOutput.setText("Loading " + (staged ? "staged" : "unstaged")
                + " diff for " + projectName + "…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String url = ApiEndpoints.workspace("/git/diff?project_id=")
                        + URLEncoder.encode(projectId, "UTF-8")
                        + "&staged=" + (staged ? "true" : "false");
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }
                JSONObject response = new JSONObject(raw.toString());
                if (!"success".equals(response.optString("status"))
                        || !projectId.equals(response.optString("project_id"))
                        || response.optBoolean("staged") != staged) {
                    throw new java.io.IOException("Diff response did not match the selected project or mode.");
                }
                String diff = response.optString("diff", "");
                String output = response.optBoolean("git_available")
                        ? ((staged ? "STAGED" : "UNSTAGED") + " DIFF • "
                            + response.optInt("changed_files") + " FILES"
                            + (response.optInt("excluded_sensitive_files") > 0
                                ? " • " + response.optInt("excluded_sensitive_files") + " SENSITIVE PATHS EXCLUDED" : "")
                            + (response.optBoolean("truncated") ? " • OUTPUT TRUNCATED" : "")
                            + "\n\n" + (diff.isEmpty() ? "No safe diff content to display." : diff)
                            + "\n\nREAD ONLY • Preview is capped by the backend.")
                        : "GIT NOT AVAILABLE • " + response.optString("message", "No Git working tree.");
                final String rendered = output;
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceGitOutput.setText(rendered);
                    recordWorkspaceOperation("Previewed " + (staged ? "staged" : "unstaged")
                            + " diff for " + projectName);
                });
            } catch (Exception error) {
                String message = "DIFF PREVIEW FAILED • " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage());
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceGitOutput.setText(message);
                });
            } finally {
                if (connection != null) connection.disconnect();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceUnstagedDiffButton.setEnabled(true);
                    workspaceStagedDiffButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void inspectWorkspaceGit() {
        final String projectId = activeProjectId;
        final String projectName = activeProject;
        workspaceGitButton.setEnabled(false);
        workspaceGitButton.setText("INSPECTING…");
        workspaceGitOutput.setText("Reading Git metadata for " + projectName + "…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String url = ApiEndpoints.workspace("/git?project_id=")
                        + URLEncoder.encode(projectId, "UTF-8");
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }
                JSONObject response = new JSONObject(raw.toString());
                if (!"success".equals(response.optString("status"))
                        || !projectId.equals(response.optString("project_id"))) {
                    throw new java.io.IOException("Git response did not match the selected project.");
                }
                String rendered;
                if (!response.optBoolean("git_available")) {
                    rendered = "GIT NOT AVAILABLE FOR THIS PROJECT\n"
                            + response.optString("message", "No Git working tree was detected.");
                } else {
                    rendered = "PROJECT: " + projectName
                            + "\n\nBRANCH / WORKING TREE\n"
                            + response.optString("branch_status", "(empty)")
                            + "\n\nUNSTAGED DIFF SUMMARY\n"
                            + response.optString("unstaged_diff_stat", "(no unstaged changes)")
                            + "\n\nSTAGED DIFF SUMMARY\n"
                            + response.optString("staged_diff_stat", "(no staged changes)")
                            + "\n\nREAD ONLY • No files or Git refs were changed.";
                }
                final String output = rendered;
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceGitOutput.setText(output);
                    recordWorkspaceOperation("Inspected Git status for " + projectName);
                });
            } catch (Exception error) {
                String message = "GIT INSPECTION FAILED • " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage());
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceGitOutput.setText(message);
                });
            } finally {
                if (connection != null) connection.disconnect();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceGitButton.setEnabled(true);
                    workspaceGitButton.setText("INSPECT GIT STATUS");
                });
            }
        }).start();
    }

    private LinearLayout buildWorkspaceBrowser() {
        LinearLayout browser = vertical();
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        workspacePathView = label("workspace /", 11, CYAN);
        controls.addView(workspacePathView, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button up = new Button(this);
        up.setText("↑ UP");
        up.setTextColor(TEXT);
        up.setAllCaps(false);
        up.setTextSize(11);
        up.setOnClickListener(v -> loadWorkspaceTree(parentWorkspacePath(workspacePath)));
        controls.addView(up, lp(dp(66), dp(42)));

        Button newFile = new Button(this);
        newFile.setText("+ FILE");
        newFile.setTextColor(TEXT);
        newFile.setAllCaps(false);
        newFile.setTextSize(10);
        newFile.setOnClickListener(v -> promptWorkspaceFileCreation());
        controls.addView(newFile, lp(dp(76), dp(42)));

        Button refresh = new Button(this);
        refresh.setText("↻");
        refresh.setContentDescription("Refresh workspace directory");
        refresh.setTextColor(TEXT);
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> loadWorkspaceTree(workspacePath));
        controls.addView(refresh, lp(dp(50), dp(42)));
        browser.addView(controls);

        workspaceStatusView = label("CONNECTING TO LOCAL WORKSPACE…", 11, MUTED);
        workspaceStatusView.setPadding(0, dp(5), 0, dp(8));
        browser.addView(workspaceStatusView);

        workspaceEntries = vertical();
        browser.addView(workspaceEntries, lp(-1, -2));

        LinearLayout preview = card();
        workspaceFileTitle = label("FILE PREVIEW", 11, PURPLE);
        workspaceFileTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        preview.addView(workspaceFileTitle);
        workspaceFileContent = new EditText(this);
        workspaceFileContent.setHint("Select a text file to edit…");
        workspaceFileContent.setHintTextColor(MUTED);
        workspaceFileContent.setTextColor(TEXT);
        workspaceFileContent.setTextSize(12);
        workspaceFileContent.setGravity(Gravity.TOP | Gravity.START);
        workspaceFileContent.setMinLines(8);
        workspaceFileContent.setPadding(dp(10), dp(10), dp(10), dp(10));
        workspaceFileContent.setBackground(rounded(Color.rgb(18, 23, 46), 10));
        workspaceFileContent.setEnabled(false);
        workspaceFileContent.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                boolean changed = !workspaceFilePath.isEmpty()
                        && !value.toString().equals(workspaceOriginalContent);
                if (workspaceSaveButton != null) workspaceSaveButton.setEnabled(changed);
                if (workspaceReviewButton != null) workspaceReviewButton.setEnabled(changed);
                if (workspaceAiProposalButton != null) {
                    workspaceAiProposalButton.setEnabled(!workspaceFilePath.isEmpty() && !changed);
                }
                boolean runnable = !workspaceFilePath.isEmpty()
                        && workspaceFilePath.toLowerCase(java.util.Locale.ROOT).endsWith(".py")
                        && !changed;
                if (workspaceCheckButton != null) workspaceCheckButton.setEnabled(runnable);
                if (workspaceRunButton != null) workspaceRunButton.setEnabled(runnable);
                if (changed && workspaceStatusView != null) {
                    workspaceStatusView.setText("UNSAVED CHANGES • SAVE BEFORE CHECKING OR RUNNING");
                }
            }

            @Override public void afterTextChanged(Editable value) { }
        });
        preview.addView(workspaceFileContent, lp(-1, -2));

        workspaceSaveButton = new Button(this);
        workspaceSaveButton.setText("SAVE • APPROVAL REQUIRED");
        workspaceSaveButton.setTextColor(TEXT);
        workspaceSaveButton.setAllCaps(false);
        workspaceSaveButton.setEnabled(false);
        workspaceSaveButton.setBackground(rounded(PURPLE, 12));
        workspaceSaveButton.setOnClickListener(v -> confirmWorkspaceSave());
        LinearLayout.LayoutParams saveParams = lp(-1, dp(46));
        saveParams.setMargins(0, dp(8), 0, 0);
        preview.addView(workspaceSaveButton, saveParams);

        workspaceReviewButton = new Button(this);
        workspaceReviewButton.setText("REVIEW CHANGE • APPROVAL REQUIRED");
        workspaceReviewButton.setTextColor(TEXT);
        workspaceReviewButton.setAllCaps(false);
        workspaceReviewButton.setEnabled(false);
        workspaceReviewButton.setBackground(rounded(Color.rgb(43, 57, 93), 12));
        workspaceReviewButton.setOnClickListener(v -> promptWorkspaceChangeReview());
        LinearLayout.LayoutParams reviewParams = lp(-1, dp(46));
        reviewParams.setMargins(0, dp(8), 0, 0);
        preview.addView(workspaceReviewButton, reviewParams);

        workspaceAiProposalButton = new Button(this);
        workspaceAiProposalButton.setText("ASK ENGINEERING TO PROPOSE");
        workspaceAiProposalButton.setTextColor(TEXT);
        workspaceAiProposalButton.setAllCaps(false);
        workspaceAiProposalButton.setEnabled(false);
        workspaceAiProposalButton.setBackground(rounded(PURPLE, 12));
        workspaceAiProposalButton.setOnClickListener(v -> promptEngineeringProposal());
        LinearLayout.LayoutParams aiProposalParams = lp(-1, dp(46));
        aiProposalParams.setMargins(0, dp(8), 0, 0);
        preview.addView(workspaceAiProposalButton, aiProposalParams);

        preview.addView(label("BUILD & TEST CENTER", 10, CYAN));
        preview.addView(label("Syntax checking parses saved code without executing it.", 11, MUTED));
        preview.addView(label(
                "Project builds and CI test logs live in GitHub Actions. The shortcut opens workflow history; it does not trigger a build.",
                11, MUTED));
        Button ciButton = new Button(this);
        ciButton.setText("VIEW GITHUB ACTIONS");
        ciButton.setTextColor(TEXT);
        ciButton.setAllCaps(false);
        ciButton.setBackground(rounded(Color.rgb(43, 57, 93), 12));
        ciButton.setOnClickListener(v -> openProjectCi());
        LinearLayout.LayoutParams ciParams = lp(-1, dp(42));
        ciParams.setMargins(0, dp(8), 0, 0);
        preview.addView(ciButton, ciParams);

        workspaceCheckButton = new Button(this);
        workspaceCheckButton.setText("CHECK PYTHON SYNTAX");
        workspaceCheckButton.setTextColor(TEXT);
        workspaceCheckButton.setAllCaps(false);
        workspaceCheckButton.setEnabled(false);
        workspaceCheckButton.setBackground(rounded(Color.rgb(42, 91, 113), 12));
        workspaceCheckButton.setOnClickListener(v -> checkWorkspacePython());
        LinearLayout.LayoutParams checkParams = lp(-1, dp(44));
        checkParams.setMargins(0, dp(8), 0, 0);
        preview.addView(workspaceCheckButton, checkParams);

        workspaceRunButton = new Button(this);
        workspaceRunButton.setText("RUN PYTHON • APPROVAL REQUIRED");
        workspaceRunButton.setTextColor(TEXT);
        workspaceRunButton.setAllCaps(false);
        workspaceRunButton.setEnabled(false);
        workspaceRunButton.setBackground(rounded(CYAN, 12));
        workspaceRunButton.setOnClickListener(v -> confirmWorkspaceExecution());
        LinearLayout.LayoutParams runParams = lp(-1, dp(46));
        runParams.setMargins(0, dp(8), 0, 0);
        preview.addView(workspaceRunButton, runParams);

        workspaceExecutionOutput = label("Syntax-check and Python execution output will appear here.", 12, MUTED);
        workspaceExecutionOutput.setTextIsSelectable(true);
        workspaceExecutionOutput.setPadding(dp(10), dp(10), dp(10), dp(10));
        workspaceExecutionOutput.setBackground(rounded(Color.rgb(9, 12, 25), 10));
        LinearLayout.LayoutParams outputParams = lp(-1, -2);
        outputParams.setMargins(0, dp(8), 0, 0);
        preview.addView(workspaceExecutionOutput, outputParams);

        LinearLayout console = card();
        LinearLayout.LayoutParams consoleParams = lp(-1, -2);
        consoleParams.setMargins(0, dp(12), 0, 0);
        preview.addView(console, consoleParams);
        console.addView(label("SAFE COMMAND CONSOLE", 10, CYAN));
        console.addView(label(
                "Allowlisted only: pwd, ls [path], cat <path>, check <file.py>, test <test_file.py>, python <file.py>. No shell commands or chaining.",
                11, MUTED));
        workspaceCommandInput = new EditText(this);
        workspaceCommandInput.setSingleLine(true);
        workspaceCommandInput.setHint("ls src • cat src/main.py • check main.py • test test_main.py");
        workspaceCommandInput.setHintTextColor(MUTED);
        workspaceCommandInput.setTextColor(TEXT);
        workspaceCommandInput.setTextSize(12);
        workspaceCommandInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        workspaceCommandInput.setBackground(rounded(Color.rgb(18, 23, 46), 10));
        LinearLayout.LayoutParams commandInputParams = lp(-1, dp(46));
        commandInputParams.setMargins(0, dp(8), 0, 0);
        console.addView(workspaceCommandInput, commandInputParams);

        workspaceCommandButton = new Button(this);
        workspaceCommandButton.setText("RUN COMMAND");
        workspaceCommandButton.setTextColor(TEXT);
        workspaceCommandButton.setAllCaps(false);
        workspaceCommandButton.setBackground(rounded(Color.rgb(42, 91, 113), 12));
        workspaceCommandButton.setOnClickListener(v -> submitWorkspaceCommand());
        LinearLayout.LayoutParams commandButtonParams = lp(-1, dp(44));
        commandButtonParams.setMargins(0, dp(6), 0, 0);
        console.addView(workspaceCommandButton, commandButtonParams);

        Button testCurrentFileButton = new Button(this);
        testCurrentFileButton.setText("TEST CURRENT FILE");
        testCurrentFileButton.setTextColor(TEXT);
        testCurrentFileButton.setAllCaps(false);
        testCurrentFileButton.setBackground(rounded(Color.rgb(57, 65, 105), 12));
        testCurrentFileButton.setOnClickListener(v -> {
            String path = workspaceFilePath == null ? "" : workspaceFilePath.trim();
            if (!path.isEmpty()
                    && !workspaceFileContent.getText().toString().equals(workspaceOriginalContent)) {
                Toast.makeText(this, "Save the current file before running tests so the result matches the saved code.", Toast.LENGTH_LONG).show();
                return;
            }
            String filename = path.isEmpty() ? "" : path.substring(path.lastIndexOf('/') + 1);
            String lower = filename.toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(".py") || !(lower.startsWith("test_") || lower.endsWith("_test.py"))) {
                Toast.makeText(this, "Open a Python test file named test_*.py or *_test.py first.", Toast.LENGTH_LONG).show();
                return;
            }
            workspaceCommandInput.setText("test " + path);
            submitWorkspaceCommand();
        });
        LinearLayout.LayoutParams testButtonParams = lp(-1, dp(42));
        testButtonParams.setMargins(0, dp(6), 0, 0);
        console.addView(testCurrentFileButton, testButtonParams);

        workspaceCommandOutput = label("Command output will appear here.", 12, MUTED);
        workspaceCommandOutput.setTextIsSelectable(true);
        workspaceCommandOutput.setPadding(dp(10), dp(10), dp(10), dp(10));
        workspaceCommandOutput.setBackground(rounded(Color.rgb(8, 10, 22), 10));
        LinearLayout.LayoutParams commandOutputParams = lp(-1, -2);
        commandOutputParams.setMargins(0, dp(8), 0, 0);
        console.addView(workspaceCommandOutput, commandOutputParams);

        LinearLayout.LayoutParams previewParams = lp(-1, -2);
        previewParams.setMargins(0, dp(10), 0, 0);
        browser.addView(preview, previewParams);
        return browser;
    }


    private void promptWorkspaceFileCreation() {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("example.py or src/example.py");
        nameInput.setHintTextColor(MUTED);
        nameInput.setTextColor(TEXT);
        nameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        nameInput.setBackground(rounded(Color.rgb(18, 23, 46), 10));

        new AlertDialog.Builder(this)
                .setTitle("New workspace file")
                .setMessage("Enter a file path relative to the current folder: workspace / " + workspacePath)
                .setView(nameInput)
                .setNegativeButton("CANCEL", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("CONTINUE", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter a file name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    confirmWorkspaceFileCreation(childWorkspacePath(workspacePath, name));
                })
                .show();
    }

    private void confirmWorkspaceFileCreation(String path) {
        new AlertDialog.Builder(this)
                .setTitle("Approve new file?")
                .setMessage("Create an empty file at " + path + " inside project \"" + activeProject
                        + "\"? Existing files will not be overwritten.")
                .setNegativeButton("CANCEL", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("APPROVE & CREATE", (dialog, which) -> createWorkspaceFile(path))
                .show();
    }

    private void createWorkspaceFile(String path) {
        final String projectId = activeProjectId;
        workspaceStatusView.setText("CREATING APPROVED FILE…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("path", path);
                request.put("project_id", projectId);
                request.put("content", "");
                request.put("approved", true);

                connection = ApiConnection.open(this, ApiEndpoints.workspace("/new-file"));
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }
                JSONObject response = new JSONObject(raw.toString());
                if (!"success".equals(response.optString("status")) || !response.optBoolean("created")) {
                    throw new java.io.IOException("Backend did not confirm file creation.");
                }
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceStatusView.setText("FILE CREATED • " + response.optString("path", path));
                    recordWorkspaceOperation("Created " + path);
                    Toast.makeText(this, "Approved file created.", Toast.LENGTH_SHORT).show();
                    loadWorkspaceTree(workspacePath);
                });
            } catch (Exception error) {
                String message = "FILE CREATION FAILED • " + error.getMessage();
                handler.post(() -> workspaceStatusView.setText(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private String parentWorkspacePath(String current) {
        if (current == null || current.isEmpty() || ".".equals(current)) return ".";
        String normalized = current;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        return slash <= 0 ? "." : normalized.substring(0, slash);
    }

    private String childWorkspacePath(String parent, String name) {
        return ".".equals(parent) || parent == null || parent.isEmpty()
                ? name : parent + "/" + name;
    }

    private JSONObject requestWorkspaceJson(String endpoint, String path, String projectId) throws Exception {
        String encodedPath = URLEncoder.encode(path, "UTF-8");
        String encodedProject = URLEncoder.encode(projectId, "UTF-8");
        HttpURLConnection connection = null;
        try {
            URL url = new URL(ApiEndpoints.workspace(endpoint) + "?path=" + encodedPath + "&project_id=" + encodedProject);
            connection = ApiConnection.open(this, url);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(12000);
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder raw = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) raw.append(line);
                }
            }
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code + ": " + raw);
            }
            return new JSONObject(raw.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void loadWorkspaceTree(String requestedPath) {
        final String projectId = activeProjectId;
        if (workspaceStatusView != null) workspaceStatusView.setText("LOADING PROJECT DIRECTORY…");
        new Thread(() -> {
            try {
                JSONObject response = requestWorkspaceJson("tree", requestedPath, projectId);
                if (!"success".equals(response.optString("status"))) {
                    throw new java.io.IOException("Workspace returned an unexpected response.");
                }
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspacePath = response.optString("path", requestedPath);
                    workspacePathView.setText(activeProject + " / " + workspacePath);
                    workspaceEntries.removeAllViews();
                    JSONArray entries = response.optJSONArray("entries");
                    int count = entries == null ? 0 : entries.length();
                    if (entries != null) {
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject entry = entries.optJSONObject(i);
                            if (entry == null) continue;
                            String name = entry.optString("name", "unnamed");
                            String type = entry.optString("type", "file");
                            TextView row = label(("directory".equals(type) ? "▸  " : "▤  ") + name,
                                    13, TEXT);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(dp(12), 0, dp(10), 0);
                            row.setBackground(rounded(CARD, 10));
                            row.setOnClickListener(v -> {
                                String target = childWorkspacePath(workspacePath, name);
                                if ("directory".equals(type)) loadWorkspaceTree(target);
                                else openWorkspaceFile(target);
                            });
                            LinearLayout.LayoutParams rowParams = lp(-1, dp(42));
                            rowParams.setMargins(0, 0, 0, dp(5));
                            workspaceEntries.addView(row, rowParams);
                        }
                    }
                    String status = activeProject + " • " + count + " ENTRIES";
                    if (response.optBoolean("truncated", false)) status += " • LIST TRUNCATED";
                    workspaceStatusView.setText(status);
                });
            } catch (Exception error) {
                String message = "WORKSPACE UNAVAILABLE • " + error.getMessage();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceStatusView.setText(message);
                    workspaceEntries.removeAllViews();
                });
            }
        }).start();
    }

    private void openWorkspaceFile(String path) {
        boolean hasUnsavedChanges = !workspaceFilePath.isEmpty()
                && !workspaceFileContent.getText().toString().equals(workspaceOriginalContent);
        if (hasUnsavedChanges) {
            new AlertDialog.Builder(this)
                    .setTitle("Unsaved Workspace changes")
                    .setMessage("Your edits to " + workspaceFilePath
                            + " have not been saved. Opening another file will discard them.")
                    .setNegativeButton("KEEP EDITING", (dialog, which) -> dialog.dismiss())
                    .setPositiveButton("DISCARD & OPEN", (dialog, which) -> loadWorkspaceFile(path))
                    .show();
            return;
        }
        loadWorkspaceFile(path);
    }

    private void loadWorkspaceFile(String path) {
        final String projectId = activeProjectId;
        workspaceStatusView.setText("READING " + path + "…");
        workspaceFilePath = "";
        workspaceSaveButton.setEnabled(false);
        if (workspaceReviewButton != null) workspaceReviewButton.setEnabled(false);
        workspaceCheckButton.setEnabled(false);
        workspaceFileContent.setEnabled(false);
        workspaceRunButton.setEnabled(false);
        new Thread(() -> {
            try {
                JSONObject response = requestWorkspaceJson("file", path, projectId);
                String content = response.optString("content", "");
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceFilePath = path;
                    workspaceOriginalContent = content;
                    recordWorkspaceOperation("Opened " + path);
                    workspaceFileTitle.setText(response.optString("path", path) + " • EDITABLE");
                    workspaceFileContent.setText(content);
                    workspaceFileContent.setEnabled(true);
                    workspaceSaveButton.setEnabled(false);
                    boolean isPython = path.toLowerCase(java.util.Locale.ROOT).endsWith(".py");
                    workspaceCheckButton.setEnabled(isPython);
                    workspaceRunButton.setEnabled(isPython);
                    workspaceStatusView.setText("FILE OPEN • " + response.optInt("size", content.length()) + " BYTES • SAVE CHANGES BEFORE CHECKING OR RUNNING");
                });
            } catch (Exception error) {
                String message = "FILE READ FAILED • " + error.getMessage();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceFilePath = "";
                    workspaceSaveButton.setEnabled(false);
                    if (workspaceReviewButton != null) workspaceReviewButton.setEnabled(false);
                    workspaceCheckButton.setEnabled(false);
                    workspaceRunButton.setEnabled(false);
                    workspaceFileContent.setEnabled(false);
                    workspaceFileTitle.setText(path);
                    workspaceFileContent.setText(message);
                    workspaceStatusView.setText("FILE READ FAILED");
                });
            }
        }).start();
    }


    private void confirmWorkspaceSave() {
        if (workspaceFilePath.isEmpty()) {
            Toast.makeText(this, "Open a workspace text file first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String updated = workspaceFileContent.getText().toString();
        if (updated.equals(workspaceOriginalContent)) {
            workspaceSaveButton.setEnabled(false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Approve file change?")
                .setMessage("Save changes to " + workspaceFilePath + " inside project \"" + activeProject
                        + "\"? This writes the full file. Review the content before approving. "
                        + "Remote repositories are not modified.")
                .setNegativeButton("CANCEL", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("APPROVE & SAVE", (dialog, which) -> saveWorkspaceFile())
                .show();
    }

    private void promptWorkspaceChangeReview() {
        if (workspaceFilePath.isEmpty() || workspaceFileContent == null) {
            Toast.makeText(this, "Open a file and make a change first.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String path = workspaceFilePath;
        final String projectId = activeProjectId;
        final String expectedContent = workspaceOriginalContent;
        final String proposedContent = workspaceFileContent.getText().toString();
        if (proposedContent.equals(expectedContent)) {
            Toast.makeText(this, "There are no unsaved changes to review.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (proposedContent.getBytes(StandardCharsets.UTF_8).length > 50_000) {
            Toast.makeText(this, "Workspace files are limited to 50 KB.", Toast.LENGTH_LONG).show();
            return;
        }

        EditText goalInput = new EditText(this);
        goalInput.setSingleLine(false);
        goalInput.setMinLines(2);
        goalInput.setHint("What should this change accomplish?");
        goalInput.setTextColor(TEXT);
        goalInput.setHintTextColor(MUTED);
        goalInput.setText("Review my changes to " + path);
        LinearLayout wrapper = vertical();
        wrapper.setPadding(dp(18), dp(8), dp(18), 0);
        wrapper.addView(goalInput, lp(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("PREPARE CHANGE REVIEW")
                .setMessage("The current editor content will be compared with the saved project file. Nothing is written until you approve the diff.")
                .setView(wrapper)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("PREVIEW DIFF", (dialog, which) -> {
                    String goal = goalInput.getText().toString().trim();
                    if (goal.isEmpty()) {
                        Toast.makeText(this, "Describe the change first.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createWorkspaceChangeProposal(path, projectId, expectedContent, proposedContent, goal);
                })
                .show();
    }

    private void promptEngineeringProposal() {
        if (workspaceFilePath.isEmpty() || workspaceFileContent == null) {
            Toast.makeText(this, "Open a file first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!workspaceFileContent.getText().toString().equals(workspaceOriginalContent)) {
            Toast.makeText(this, "Save or discard unsaved edits before asking Engineering.", Toast.LENGTH_LONG).show();
            return;
        }
        final String path = workspaceFilePath;
        final String projectId = activeProjectId;
        final String expectedContent = workspaceOriginalContent;
        EditText goalInput = new EditText(this);
        goalInput.setSingleLine(false);
        goalInput.setMinLines(2);
        goalInput.setHint("Describe the change Engineering should make");
        goalInput.setTextColor(TEXT);
        goalInput.setHintTextColor(MUTED);
        LinearLayout wrapper = vertical();
        wrapper.setPadding(dp(18), dp(8), dp(18), 0);
        wrapper.addView(goalInput, lp(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("ASK ENGINEERING")
                .setMessage("Amiki will read this file and propose a complete replacement. It will not write anything until you review and approve the diff.")
                .setView(wrapper)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("GENERATE PROPOSAL", (dialog, which) -> {
                    String goal = goalInput.getText().toString().trim();
                    if (goal.isEmpty()) {
                        Toast.makeText(this, "Describe the change first.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    generateEngineeringProposal(path, projectId, expectedContent, goal);
                })
                .show();
    }

    private void generateEngineeringProposal(
            String path, String projectId, String expectedContent, String goal) {
        if (workspaceAiProposalButton != null) {
            workspaceAiProposalButton.setEnabled(false);
            workspaceAiProposalButton.setText("ENGINEERING IS WORKING…");
        }
        workspaceStatusView.setText("GENERATING STRUCTURED PROPOSAL • NO FILE WRITES");
        new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("path", path);
                request.put("project_id", projectId);
                request.put("expected_content", expectedContent);
                request.put("goal", goal);
                JSONObject response = postWorkspaceJson("change-proposals/generate", request);
                if (!"success".equals(response.optString("status"))
                        || !projectId.equals(response.optString("project_id"))
                        || !path.equals(response.optString("path"))
                        || response.optBoolean("applied")
                        || response.optString("proposal_id").isEmpty()) {
                    throw new java.io.IOException("Engineering returned a proposal for a different file or project.");
                }
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId) || !workspaceFilePath.equals(path)) return;
                    workspaceStatusView.setText("ENGINEERING PROPOSAL READY • REVIEW THE DIFF");
                    if (workspaceAiProposalButton != null) {
                        workspaceAiProposalButton.setText("ASK ENGINEERING TO PROPOSE");
                        workspaceAiProposalButton.setEnabled(
                                workspaceFileContent.getText().toString().equals(workspaceOriginalContent));
                    }
                    showWorkspaceChangeProposal(response, path, projectId, expectedContent);
                });
            } catch (Exception error) {
                String message = "ENGINEERING PROPOSAL FAILED • " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\\n" + error.getMessage());
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId) || !workspaceFilePath.equals(path)) return;
                    workspaceStatusView.setText(message);
                    if (workspaceAiProposalButton != null) {
                        workspaceAiProposalButton.setText("ASK ENGINEERING TO PROPOSE");
                        workspaceAiProposalButton.setEnabled(
                                workspaceFileContent.getText().toString().equals(workspaceOriginalContent));
                    }
                });
            }
        }).start();
    }

    private JSONObject postWorkspaceJson(String endpoint, JSONObject request) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = ApiConnection.open(this, ApiEndpoints.workspace(endpoint));
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(12000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder raw = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) raw.append(line);
                }
            }
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code + ": " + raw);
            }
            return new JSONObject(raw.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void createWorkspaceChangeProposal(
            String path, String projectId, String expectedContent, String proposedContent, String goal) {
        if (workspaceReviewButton != null) workspaceReviewButton.setEnabled(false);
        if (workspaceStatusView != null) workspaceStatusView.setText("PREPARING REVIEWABLE DIFF…");
        new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("path", path);
                request.put("project_id", projectId);
                request.put("expected_content", expectedContent);
                request.put("proposed_content", proposedContent);
                request.put("goal", goal);
                JSONObject response = postWorkspaceJson("change-proposals", request);
                if (!"success".equals(response.optString("status"))
                        || !projectId.equals(response.optString("project_id"))
                        || !path.equals(response.optString("path"))
                        || response.optBoolean("applied")) {
                    throw new java.io.IOException("Proposal response did not match the active file and project.");
                }
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId) || !workspaceFilePath.equals(path)) return;
                    workspaceStatusView.setText("CHANGE PROPOSED • REVIEW THE DIFF BEFORE APPLYING");
                    showWorkspaceChangeProposal(response, path, projectId, proposedContent);
                });
            } catch (Exception error) {
                String message = "CHANGE REVIEW FAILED • " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage());
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId) || !workspaceFilePath.equals(path)) return;
                    workspaceStatusView.setText(message);
                    if (workspaceReviewButton != null) {
                        workspaceReviewButton.setEnabled(
                                !workspaceFileContent.getText().toString().equals(workspaceOriginalContent));
                    }
                });
            }
        }).start();
    }

    private void showWorkspaceChangeProposal(
            JSONObject proposal, String path, String projectId, String editorSnapshot) {
        String diff = proposal.optString("diff", "");
        boolean diffTruncated = proposal.optBoolean("diff_truncated");
        String heading = "PROJECT: " + proposal.optString("project_name", activeProject)
                + "\nFILE: " + path
                + "\nGOAL: " + proposal.optString("goal", "")
                + "\nBASE SHA-256: " + proposal.optString("base_sha256", "")
                + (proposal.optBoolean("diff_truncated") ? "\n\nWARNING: DIFF TRUNCATED" : "")
                + "\n\n" + (diff.isEmpty() ? "No diff content returned." : diff)
                + "\n\nNO FILES HAVE BEEN CHANGED YET.";
        TextView preview = label(heading, 12, TEXT);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(preview);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("REVIEW PROPOSED CHANGE")
                .setView(scroll)
                .setNegativeButton("REJECT PROPOSAL", null)
                .setPositiveButton(diffTruncated ? "DIFF TRUNCATED" : "APPROVE & APPLY", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button reject = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            reject.setOnClickListener(v -> {
                reject.setEnabled(false);
                rejectWorkspaceChangeProposal(
                        proposal.optString("proposal_id"), path, projectId, dialog, reject);
            });
            Button approve = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            approve.setEnabled(!diffTruncated);
            approve.setOnClickListener(v -> {
                if (diffTruncated) return;
                approve.setEnabled(false);
                approveWorkspaceChangeProposal(
                        proposal.optString("proposal_id"), path, projectId, editorSnapshot, dialog, approve);
            });
        });
        dialog.show();
    }

    private void rejectWorkspaceChangeProposal(
            String proposalId, String path, String projectId,
            AlertDialog dialog, Button rejectButton) {
        rejectButton.setText("REJECTING…");
        new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("project_id", projectId);
                JSONObject response = postWorkspaceJson(
                        "change-proposals/" + URLEncoder.encode(proposalId, "UTF-8") + "/reject", request);
                if (!"success".equals(response.optString("status"))
                        || !response.optBoolean("rejected")
                        || response.optBoolean("applied")
                        || !projectId.equals(response.optString("project_id"))
                        || !path.equals(response.optString("path"))) {
                    throw new java.io.IOException("Backend did not confirm proposal rejection.");
                }
                handler.post(() -> {
                    if (activeProjectId.equals(projectId) && workspaceFilePath.equals(path)) {
                        workspaceStatusView.setText("PROPOSAL REJECTED • NO FILES CHANGED");
                        recordWorkspaceOperation("Rejected proposal for " + path);
                    }
                    dialog.dismiss();
                    Toast.makeText(this, "Proposal rejected. No files were changed.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                String message = "REJECTION FAILED • " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage());
                handler.post(() -> {
                    if (activeProjectId.equals(projectId) && workspaceFilePath.equals(path)) {
                        workspaceStatusView.setText(message);
                    }
                    rejectButton.setEnabled(true);
                    rejectButton.setText("RETRY REJECTION");
                });
            }
        }).start();
    }

    private void approveWorkspaceChangeProposal(
            String proposalId, String path, String projectId, String editorSnapshot,
            AlertDialog dialog, Button approveButton) {
        approveButton.setText("APPLYING…");
        new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("project_id", projectId);
                request.put("approved", true);
                JSONObject response = postWorkspaceJson(
                        "change-proposals/" + URLEncoder.encode(proposalId, "UTF-8") + "/approve", request);
                if (!"success".equals(response.optString("status"))
                        || !response.optBoolean("applied")
                        || !projectId.equals(response.optString("project_id"))
                        || !path.equals(response.optString("path"))) {
                    throw new java.io.IOException("Backend did not confirm the approved file change.");
                }
                JSONObject savedFile = requestWorkspaceJson("file", path, projectId);
                if (!"success".equals(savedFile.optString("status"))
                        || !projectId.equals(savedFile.optString("project_id"))
                        || !path.equals(savedFile.optString("path"))) {
                    throw new java.io.IOException("Approved file was written, but the saved version could not be reloaded.");
                }
                String appliedContent = savedFile.optString("content", "");
                JSONObject verification = response.optJSONObject("verification");
                String verificationStatus = verification == null
                        ? "unavailable" : verification.optString("status", "unavailable");
                String verificationMessage;
                if ("passed".equals(verificationStatus)) {
                    verificationMessage = "CONTENT READ-BACK VERIFIED • PYTHON SYNTAX PASSED • TESTS NOT RUN";
                } else if ("partial".equals(verificationStatus)) {
                    verificationMessage = "CONTENT READ-BACK VERIFIED • SYNTAX CHECK NOT AVAILABLE FOR THIS FILE TYPE";
                } else if ("failed".equals(verificationStatus)) {
                    verificationMessage = "WARNING • POST-WRITE VERIFICATION FAILED";
                } else {
                    verificationMessage = "VERIFICATION DETAILS UNAVAILABLE • CHECK THE SAVED FILE";
                }
                final String verificationSummary = verificationMessage;
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId) || !workspaceFilePath.equals(path)) {
                        dialog.dismiss();
                        return;
                    }
                    boolean editorStillMatchesSnapshot =
                            workspaceFileContent.getText().toString().equals(editorSnapshot);
                    workspaceOriginalContent = appliedContent;
                    if (editorStillMatchesSnapshot) {
                        workspaceFileContent.setText(appliedContent);
                        workspaceFileTitle.setText(path + " • REVIEWED & APPLIED");
                        workspaceStatusView.setText("APPROVED CHANGE APPLIED • " + path + "\\n" + verificationSummary);
                    } else {
                        workspaceStatusView.setText(
                                "APPROVED VERSION APPLIED • NEWER UNSAVED EDITS WERE PRESERVED IN THE EDITOR\\n"
                                        + verificationSummary);
                    }
                    boolean hasUnsavedEdits =
                            !workspaceFileContent.getText().toString().equals(workspaceOriginalContent);
                    workspaceSaveButton.setEnabled(hasUnsavedEdits);
                    workspaceReviewButton.setEnabled(hasUnsavedEdits);
                    workspaceAiProposalButton.setText("ASK ENGINEERING TO PROPOSE");
                    workspaceAiProposalButton.setEnabled(!hasUnsavedEdits);
                    recordWorkspaceOperation("Approved reviewed change for " + path);
                    dialog.dismiss();
                    Toast.makeText(this, verificationStatus.equals("passed")
                            ? "Change applied; Python syntax passed. Tests were not run."
                            : verificationStatus.equals("partial")
                            ? "Change applied; syntax was not checked for this file type."
                            : verificationStatus.equals("failed")
                            ? "Change applied, but verification reported a problem."
                            : "Change applied; verification details are unavailable.",
                            Toast.LENGTH_LONG).show();
                    loadWorkspaceTree(workspacePath);
                });
            } catch (Exception error) {
                String message = "APPROVAL FAILED • " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage());
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId) || !workspaceFilePath.equals(path)) return;
                    workspaceStatusView.setText(message);
                    approveButton.setEnabled(true);
                    approveButton.setText("RETRY APPROVAL");
                });
            }
        }).start();
    }

    private void saveWorkspaceFile() {
        final String path = workspaceFilePath;
        final String projectId = activeProjectId;
        final String expected = workspaceOriginalContent;
        final String updated = workspaceFileContent.getText().toString();
        if (updated.getBytes(StandardCharsets.UTF_8).length > 50_000) {
            workspaceStatusView.setText("SAVE BLOCKED • MAXIMUM FILE SIZE IS 50 KB");
            Toast.makeText(this, "Workspace files are limited to 50 KB.", Toast.LENGTH_LONG).show();
            return;
        }

        workspaceSaveButton.setEnabled(false);
        workspaceStatusView.setText("SAVING APPROVED CHANGE…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("path", path);
                request.put("expected_content", expected);
                request.put("content", updated);
                request.put("project_id", projectId);
                request.put("approved", true);

                connection = ApiConnection.open(this, ApiEndpoints.workspace("/file"));
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }
                JSONObject response = new JSONObject(raw.toString());
                if (!"success".equals(response.optString("status")) || !response.optBoolean("written")) {
                    throw new java.io.IOException("Backend did not confirm the file write.");
                }
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceOriginalContent = updated;
                    recordWorkspaceOperation("Saved " + path);
                    boolean isPython = path.toLowerCase(java.util.Locale.ROOT).endsWith(".py");
                    workspaceCheckButton.setEnabled(isPython);
                    workspaceRunButton.setEnabled(isPython);
                    workspaceFileTitle.setText(path + " • SAVED");
                    workspaceStatusView.setText("SAVE SUCCESS • " + response.optInt("size") + " BYTES");
                    Toast.makeText(this, "Approved workspace changes saved.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                String message = "SAVE FAILED • " + error.getMessage();
                handler.post(() -> workspaceStatusView.setText(message));
            } finally {
                if (connection != null) connection.disconnect();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceSaveButton.setEnabled(
                        !workspaceFilePath.isEmpty()
                                && !workspaceFileContent.getText().toString().equals(workspaceOriginalContent));
                });
            }
        }).start();
    }


    private void submitWorkspaceCommand() {
        String command = workspaceCommandInput.getText().toString().trim();
        if (command.isEmpty()) {
            workspaceCommandInput.setError("Enter a supported command.");
            return;
        }
        String operation = command.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if ("python".equals(operation) || "test".equals(operation)) {
            boolean testCommand = "test".equals(operation);
            new AlertDialog.Builder(this)
                    .setTitle(testCommand ? "Approve test execution?" : "Approve Python command?")
                    .setMessage(testCommand
                            ? "Run this test file in the selected project? Tests execute Python code with the backend process's operating-system permissions and are NOT sandboxed. Only run tests you trust. Timeout: 30 seconds."
                            : "Run this script in the selected project? Python code runs with the backend process's operating-system permissions and is NOT sandboxed. Only run code you trust. Timeout: 10 seconds.")
                    .setNegativeButton("CANCEL", (dialog, which) -> dialog.dismiss())
                    .setPositiveButton(testCommand ? "APPROVE & TEST" : "APPROVE & RUN",
                            (dialog, which) -> runWorkspaceCommand(command, true))
                    .show();
        } else {
            runWorkspaceCommand(command, false);
        }
    }

    private void runWorkspaceCommand(String command, boolean approved) {
        final String projectId = activeProjectId;
        workspaceCommandButton.setEnabled(false);
        workspaceCommandOutput.setText("Running command against " + projectId + "…");
        workspaceStatusView.setText("WORKSPACE COMMAND RUNNING…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("command", command);
                request.put("project_id", projectId);
                request.put("approved", approved);
                connection = ApiConnection.open(this, ApiEndpoints.workspace("/command"));
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(40000); // Test command timeout is 30 seconds.
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }

                JSONObject response = new JSONObject(raw.toString());
                int exitCode = response.optInt("exit_code", -1);
                String commandStatus = response.optString(
                        "execution_status", response.optString("check_status", exitCode == 0 ? "passed" : "failed"));
                StringBuilder output = new StringBuilder();
                output.append("project://").append(projectId).append(" $ ").append(command);
                output.append("\nStatus: ").append(commandStatus.toUpperCase(java.util.Locale.ROOT));
                output.append("\nExit code: ").append(exitCode);
                if (response.optBoolean("approved", false)) output.append("\nApproved: yes");
                if (response.optBoolean("output_truncated", false)) output.append("\n[Output truncated]");
                String content = response.optString("output", "");
                if (!content.isEmpty()) output.append("\n\n").append(content);
                final String rendered = output.toString();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceCommandOutput.setText(rendered);
                    recordWorkspaceOperation("Command " + command + " (exit " + exitCode + ")");
                    workspaceStatusView.setText("COMMAND " + (exitCode == 0 ? "SUCCESS" : "FAILED")
                            + " • EXIT " + exitCode);
                });
            } catch (Exception error) {
                String message = "COMMAND FAILED • " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage());
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceCommandOutput.setText(message);
                    workspaceStatusView.setText("WORKSPACE COMMAND FAILED");
                });
            } finally {
                if (connection != null) connection.disconnect();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceCommandButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void checkWorkspacePython() {
        if (workspaceFilePath.isEmpty()
                || !workspaceFilePath.toLowerCase(java.util.Locale.ROOT).endsWith(".py")) {
            Toast.makeText(this, "Open a Python (.py) file first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!workspaceFileContent.getText().toString().equals(workspaceOriginalContent)) {
            Toast.makeText(this, "Save your edits before checking syntax.", Toast.LENGTH_LONG).show();
            workspaceStatusView.setText("SYNTAX CHECK BLOCKED • SAVE UNSAVED EDITS FIRST");
            return;
        }

        final String path = workspaceFilePath;
        final String projectId = activeProjectId;
        workspaceCheckButton.setEnabled(false);
        workspaceStatusView.setText("CHECKING PYTHON SYNTAX • NO CODE EXECUTION…");
        workspaceExecutionOutput.setText("Parsing saved Python source…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("path", path);
                request.put("project_id", projectId);
                connection = ApiConnection.open(this, ApiEndpoints.workspace("/check"));
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }
                JSONObject response = new JSONObject(raw.toString());
                String checkStatus = response.optString("check_status", "unknown");
                StringBuilder output = new StringBuilder();
                output.append("SYNTAX: ").append(checkStatus.toUpperCase(java.util.Locale.ROOT));
                output.append("\nFile: ").append(path);
                output.append("\nExecuted: ").append(response.optBoolean("executed", true));
                output.append("\n").append(response.optString("message", ""));
                if (!response.optBoolean("syntax_valid", false)) {
                    output.append("\nLine: ").append(response.optInt("line", 0));
                    output.append("\nColumn: ").append(response.optInt("column", 0));
                }
                final String rendered = output.toString();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceExecutionOutput.setText(rendered);
                    recordWorkspaceOperation("Syntax check " + path + ": " + checkStatus);
                    String status = "SYNTAX " + checkStatus.toUpperCase(java.util.Locale.ROOT);
                    if (!response.optBoolean("syntax_valid", false)) {
                        status += " • LINE " + response.optInt("line", 0);
                    }
                    workspaceStatusView.setText(status);
                });
            } catch (Exception error) {
                String message = "SYNTAX CHECK FAILED • " + error.getMessage();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceExecutionOutput.setText(message);
                    workspaceStatusView.setText("SYNTAX CHECK FAILED");
                });
            } finally {
                if (connection != null) connection.disconnect();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceCheckButton.setEnabled(
                            !workspaceFilePath.isEmpty()
                                    && workspaceFilePath.toLowerCase(java.util.Locale.ROOT).endsWith(".py")
                                    && workspaceFileContent.getText().toString().equals(workspaceOriginalContent));
                });
            }
        }).start();
    }

    private void confirmWorkspaceExecution() {
        if (workspaceFilePath.isEmpty()
                || !workspaceFilePath.toLowerCase(java.util.Locale.ROOT).endsWith(".py")) {
            Toast.makeText(this, "Open a Python file first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!workspaceFileContent.getText().toString().equals(workspaceOriginalContent)) {
            Toast.makeText(this, "Save your changes before running this script.", Toast.LENGTH_LONG).show();
            return;
        }
        final String path = workspaceFilePath;
        new AlertDialog.Builder(this)
                .setTitle("Approve Python execution?")
                .setMessage("Run python " + path + " inside project \"" + activeProject + "\"? It has a 10-second timeout, "
                        + "but this is NOT a sandbox: the script runs with the backend process's operating-system permissions. "
                        + "Run only code you trust.")
                .setNegativeButton("CANCEL", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("APPROVE & RUN", (dialog, which) -> executeWorkspaceScript(path))
                .show();
    }

    private void executeWorkspaceScript(String path) {
        final String projectId = activeProjectId;
        workspaceRunButton.setEnabled(false);
        workspaceStatusView.setText("RUNNING APPROVED PYTHON SCRIPT…");
        workspaceExecutionOutput.setText("Waiting for script output…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("path", path);
                request.put("project_id", projectId);
                request.put("approved", true);

                connection = ApiConnection.open(this, ApiEndpoints.workspace("/execute"));
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }

                JSONObject response = new JSONObject(raw.toString());
                String executionStatus = response.optString("execution_status", "unknown");
                int exitCode = response.optInt("return_code", -1);
                StringBuilder output = new StringBuilder();
                output.append("Command: python ").append(path);
                output.append("\nResult: ").append(executionStatus.toUpperCase(java.util.Locale.ROOT));
                output.append("\nExit code: ").append(exitCode).append("\n");
                String stdout = response.optString("stdout", "");
                String stderr = response.optString("stderr", "");
                if (!stdout.isEmpty()) output.append("\nSTDOUT\n").append(stdout);
                if (!stderr.isEmpty()) output.append("\nSTDERR\n").append(stderr);
                if (response.optBoolean("output_truncated", false)) {
                    output.append("\n\n[Output truncated by backend]");
                }
                final String rendered = output.toString();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceExecutionOutput.setText(rendered);
                    recordWorkspaceOperation("Python run " + path + ": " + executionStatus);
                    workspaceStatusView.setText("PYTHON " + executionStatus.toUpperCase(java.util.Locale.ROOT)
                            + " • EXIT " + exitCode);
                });
            } catch (Exception error) {
                String message = "EXECUTION FAILED • " + error.getMessage();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceExecutionOutput.setText(message);
                    workspaceStatusView.setText("PYTHON EXECUTION FAILED");
                });
            } finally {
                if (connection != null) connection.disconnect();
                handler.post(() -> {
                    if (!activeProjectId.equals(projectId)) return;
                    workspaceRunButton.setEnabled(
                        !workspaceFilePath.isEmpty()
                                && workspaceFilePath.toLowerCase(java.util.Locale.ROOT).endsWith(".py")
                                && workspaceFileContent.getText().toString().equals(workspaceOriginalContent));
                });
            }
        }).start();
    }

    private TextView sectionHeading(String text) {
        TextView heading = label(text, 11, PURPLE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(20), 0, dp(8));
        return heading;
    }

    private LinearLayout card() {
        LinearLayout box = vertical();
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(rounded(CARD, 15));
        return box;
    }

    private void loadWorkspaceProjects() {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = ApiConnection.open(this, ApiEndpoints.workspace("/projects"));
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }
                JSONObject response = new JSONObject(raw.toString());
                JSONArray projects = response.optJSONArray("projects");
                if (!"success".equals(response.optString("status")) || projects == null) {
                    throw new java.io.IOException("Backend did not return a project registry.");
                }
                handler.post(() -> {
                    workspaceProjects = projects;
                    boolean currentAvailable = false;
                    String fallbackId = null;
                    String fallbackName = null;
                    for (int i = 0; i < workspaceProjects.length(); i++) {
                        JSONObject item = workspaceProjects.optJSONObject(i);
                        if (item == null || !item.optBoolean("available", false)) continue;
                        if (fallbackId == null) {
                            fallbackId = item.optString("id");
                            fallbackName = item.optString("name");
                        }
                        if (activeProjectId.equals(item.optString("id"))) currentAvailable = true;
                    }
                    renderProjectChoices();
                    if (!currentAvailable && fallbackId != null) {
                        applyWorkspaceProject(fallbackId, fallbackName);
                    }
                });
            } catch (Exception error) {
                handler.post(() -> {
                    workspaceProjects = new JSONArray();
                    renderProjectChoices();
                    workspaceStatusView.setText("PROJECT REGISTRY UNAVAILABLE • " + error.getMessage());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void promptNewWorkspaceProject() {
        boolean hasUnsavedChanges = !workspaceFilePath.isEmpty()
                && !workspaceFileContent.getText().toString().equals(workspaceOriginalContent);
        if (hasUnsavedChanges) {
            Toast.makeText(this, "Save or discard the current edits before creating a project.", Toast.LENGTH_LONG).show();
            return;
        }
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("e.g. Python Practice");
        nameInput.setHintTextColor(MUTED);
        nameInput.setTextColor(TEXT);
        nameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        nameInput.setBackground(rounded(Color.rgb(18, 23, 46), 10));

        new AlertDialog.Builder(this)
                .setTitle("Create local project")
                .setMessage("This creates a project inside Amiki Workspace on this device. It does not create a GitHub repository.")
                .setView(nameInput)
                .setNegativeButton("CANCEL", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("CONTINUE", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter a project name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final int[] selectedTemplate = {0};
                    String[] templates = {"Python starter", "Empty project"};
                    new AlertDialog.Builder(this)
                            .setTitle("Choose a project template")
                            .setSingleChoiceItems(templates, 0,
                                    (templateDialog, selectedIndex) -> selectedTemplate[0] = selectedIndex)
                            .setMessage("Creating a project requires approval. Existing projects will not be overwritten.")
                            .setNegativeButton("CANCEL", (templateDialog, cancelIndex) -> templateDialog.dismiss())
                            .setPositiveButton("APPROVE & CREATE", (templateDialog, approveIndex) ->
                                    createWorkspaceProject(name, selectedTemplate[0] == 0 ? "python" : "empty"))
                            .show();
                })
                .show();
    }

    private void createWorkspaceProject(String name, String template) {
        workspaceStatusView.setText("CREATING APPROVED LOCAL PROJECT…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("name", name);
                request.put("template", template);
                request.put("approved", true);
                connection = ApiConnection.open(this, ApiEndpoints.workspace("/projects"));
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(12000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code + ": " + raw);
                }
                JSONObject response = new JSONObject(raw.toString());
                if (!"success".equals(response.optString("status")) || !response.optBoolean("created")) {
                    throw new java.io.IOException("Backend did not confirm project creation.");
                }
                String projectId = response.getString("project_id");
                String projectName = response.getString("project_name");
                handler.post(() -> {
                    recordWorkspaceOperation("Created local project " + projectName);
                    Toast.makeText(this, "Project created.", Toast.LENGTH_SHORT).show();
                    applyWorkspaceProject(projectId, projectName);
                    loadWorkspaceProjects();
                    workspaceStatusView.setText("PROJECT CREATED • " + projectName);
                });
            } catch (Exception error) {
                String message = "PROJECT CREATION FAILED • " + error.getMessage();
                handler.post(() -> {
                    workspaceStatusView.setText(message);
                    recordWorkspaceOperation("Project creation failed");
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void selectWorkspaceProject(String projectId, String projectName) {
        if (projectId.equals(activeProjectId)) return;
        boolean hasUnsavedChanges = !workspaceFilePath.isEmpty()
                && !workspaceFileContent.getText().toString().equals(workspaceOriginalContent);
        if (hasUnsavedChanges) {
            new AlertDialog.Builder(this)
                    .setTitle("Unsaved Workspace changes")
                    .setMessage("Switching projects discards unsaved edits to " + workspaceFilePath + ".")
                    .setNegativeButton("KEEP EDITING", (dialog, which) -> dialog.dismiss())
                    .setPositiveButton("DISCARD & SWITCH", (dialog, which) -> applyWorkspaceProject(projectId, projectName))
                    .show();
            return;
        }
        applyWorkspaceProject(projectId, projectName);
    }

    private void applyWorkspaceProject(String projectId, String projectName) {
        activeProjectId = projectId;
        activeProject = projectName;
        recordWorkspaceOperation("Switched project to " + projectName);
        workspacePath = ".";
        workspaceFilePath = "";
        workspaceOriginalContent = "";
        workspaceFileTitle.setText("FILE PREVIEW");
        workspaceFileContent.setText("");
        workspaceFileContent.setEnabled(false);
        workspaceSaveButton.setEnabled(false);
        workspaceCheckButton.setEnabled(false);
        workspaceRunButton.setEnabled(false);
        workspaceCommandOutput.setText("Project switched • commands use " + projectId + ".");
        View active = findViewByTagSafe("active_project_label");
        if (active instanceof TextView) ((TextView) active).setText(activeProject);
        renderProjectChoices();
        loadWorkspaceTree(".");
    }

    private void recordWorkspaceOperation(String summary) {
        try {
            android.content.SharedPreferences preferences =
                    getSharedPreferences("workspace_operations", MODE_PRIVATE);
            JSONArray existing = new JSONArray(preferences.getString("items", "[]"));
            JSONArray updated = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("at", System.currentTimeMillis());
            item.put("project_id", activeProjectId);
            item.put("summary", summary);
            updated.put(item);
            for (int i = 0; i < existing.length() && i < 9; i++) {
                JSONObject previous = existing.optJSONObject(i);
                if (previous != null) updated.put(previous);
            }
            preferences.edit().putString("items", updated.toString()).apply();
            renderRecentOperations();
        } catch (Exception ignored) {
            // History is best-effort and must never block a workspace operation.
        }
    }

    private void renderRecentOperations() {
        if (recentOperationsContainer == null) return;
        recentOperationsContainer.removeAllViews();
        try {
            android.content.SharedPreferences preferences =
                    getSharedPreferences("workspace_operations", MODE_PRIVATE);
            JSONArray items = new JSONArray(preferences.getString("items", "[]"));
            if (items.length() == 0) {
                recentOperationsContainer.addView(
                        label("No Workspace operations recorded yet.", 12, MUTED), lp(-1, dp(36)));
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String time = android.text.format.DateFormat.format("MM-dd HH:mm", item.optLong("at")).toString();
                String project = item.optString("project_id", "workspace");
                String summary = item.optString("summary", "Workspace operation");
                TextView row = label(time + "  •  " + project + "\n" + summary, 11, MUTED);
                row.setPadding(dp(10), dp(7), dp(10), dp(7));
                row.setBackground(rounded(CARD, 9));
                LinearLayout.LayoutParams rowParams = lp(-1, -2);
                rowParams.setMargins(0, 0, 0, dp(5));
                recentOperationsContainer.addView(row, rowParams);
            }
        } catch (Exception ignored) {
            recentOperationsContainer.removeAllViews();
            recentOperationsContainer.addView(
                    label("Recent operation history is unavailable.", 12, MUTED), lp(-1, dp(36)));
        }
    }

    private void openProjectCi() {
        String url;
        if (activeProjectId.startsWith("local-")) {
            Toast.makeText(this,
                    "This local project has no GitHub Actions workflow yet. Publish it to a repository first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if ("amiki-backend".equals(activeProjectId)) {
            url = "https://github.com/leoperish54-hiro/Amiki-/actions";
        } else if ("amiki-android".equals(activeProjectId)) {
            url = "https://github.com/leoperish54-hiro/amiki-android/actions";
        } else if ("aethel-hawk".equals(activeProjectId)) {
            url = "https://github.com/leoperish54-hiro/Aethel-Hawk-Flight-Sim/actions";
        } else {
            Toast.makeText(this,
                    "This local project has no linked GitHub Actions workflow. Use local check/test commands or link a repository first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            Toast.makeText(this, "Could not open GitHub Actions.", Toast.LENGTH_LONG).show();
        }
    }

    private void renderProjectChoices() {
        projectChoices.removeAllViews();
        if (workspaceProjects.length() == 0) {
            TextView loading = label("Loading configured projects…", 12, MUTED);
            loading.setPadding(dp(12), dp(10), dp(12), dp(10));
            projectChoices.addView(loading, lp(-1, -2));
            return;
        }
        for (int i = 0; i < workspaceProjects.length(); i++) {
            JSONObject project = workspaceProjects.optJSONObject(i);
            if (project == null) continue;
            String id = project.optString("id", "");
            String name = project.optString("name", id);
            boolean available = project.optBoolean("available", false);
            boolean selected = id.equals(activeProjectId);
            String text = (selected ? "●  " : "○  ") + name + (available ? "" : " • NOT LOCAL");
            TextView choice = label(text, 13, available ? TEXT : MUTED);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setPadding(dp(13), 0, dp(10), 0);
            choice.setBackground(rounded(
                    selected ? Color.rgb(43, 25, 75) : CARD, 12));
            choice.setEnabled(available);
            if (available) choice.setOnClickListener(v -> selectWorkspaceProject(id, name));
            LinearLayout.LayoutParams params = lp(-1, dp(46));
            params.setMargins(0, 0, 0, dp(6));
            projectChoices.addView(choice, params);
        }
    }

    private View findViewByTagSafe(String tag) {
        return getWindow().getDecorView().findViewWithTag(tag);
    }

    private void addQuickAction(LinearLayout parent, String title, String prompt) {
        TextView action = label(title + "\nTap to prepare a request", 13, TEXT);
        action.setPadding(dp(14), dp(10), dp(14), dp(10));
        action.setBackground(rounded(CARD, 12));
        action.setOnClickListener(v -> {
            requestInput.setText(prompt);
            requestInput.setSelection(requestInput.length());
            requestInput.requestFocus();
            pageScroll.post(() -> pageScroll.smoothScrollTo(0, requestInput.getTop()));
        });
        LinearLayout.LayoutParams params = lp(-1, dp(58));
        params.setMargins(0, 0, 0, dp(7));
        parent.addView(action, params);
    }

    private void submitRequest() {
        String message = requestInput.getText().toString().trim();
        if (message.isEmpty()) {
            requestInput.setError("Enter an Engineering request first.");
            return;
        }

        final String projectName = activeProject;
        final String projectId = activeProjectId;
        sendButton.setEnabled(false);
        sendButton.setText("ENGINEERING • RUNNING");
        statusView.setText("SENDING REQUEST TO BACKEND…");
        resultView.setText("Waiting for the actual Engineering Agent response…");

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("agent", "engineering");
                request.put("input", message);
                request.put("session_id", "android-workspace");
                request.put("conversation_id", "workspace-" + projectId);
                JSONObject context = new JSONObject();
                context.put("project", projectName);
                context.put("project_id", projectId);
                context.put("goal", "Use only accessible, verified tools. State repository access limits honestly. Never claim edits, tests, or builds occurred without actual evidence.");
                request.put("context", context);

                String elevatedToken = UosaAgentAuthorization.obtainTokenBlocking(this, request);
                connection = ApiConnection.openWithBearer(DIRECT_AGENT, elevatedToken);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(45000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) raw.append(line);
                    }
                }

                String rendered;
                if (code >= 200 && code < 300) {
                    JSONObject response = new JSONObject(raw.toString());
                    rendered = renderAgentResponse(response);
                    String agentStatus = response.optString("status", "unknown").toUpperCase();
                    handler.post(() -> statusView.setText(
                            "ENGINEERING AGENT • " + agentStatus
                    ));
                } else {
                    rendered = "Backend returned HTTP " + code + "\n" + raw;
                    handler.post(() -> statusView.setText("REQUEST FAILED • HTTP " + code));
                }
                final String output = rendered;
                handler.post(() -> resultView.setText(output));
            } catch (Exception error) {
                String messageText = "Engineering request failed: " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "\n" + error.getMessage());
                handler.post(() -> {
                    statusView.setText("BACKEND REQUEST FAILED");
                    resultView.setText(messageText);
                });
            } finally {
                if (connection != null) connection.disconnect();
                handler.post(() -> {
                    sendButton.setEnabled(true);
                    sendButton.setText("SEND • ENGINEERING");
                });
            }
        }).start();
    }

    private String renderAgentResponse(JSONObject response) {
        StringBuilder out = new StringBuilder();
        String agentStatus = response.optString("status", "unknown");
        out.append("ENGINEERING AGENT • ")
                .append(agentStatus.equalsIgnoreCase("success") ? "Completed" : agentStatus)
                .append("\nProject: ").append(activeProject).append("\n");

        JSONArray results = response.optJSONArray("results");
        if (results == null || results.length() == 0) {
            out.append("\nThe agent completed the request but returned no result details.");
        } else {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) {
                    out.append("\n\n").append(String.valueOf(results.opt(i)));
                    continue;
                }
                JSONObject data = unwrapEngineeringResult(item);
                out.append("\n\n").append(formatEngineeringResult(data));
            }
        }

        JSONObject evidence = response.optJSONObject("evidence");
        if (evidence != null && evidence.length() > 0) {
            out.append("\n\nExecution evidence");
            appendResultField(out, evidence, "completed_steps", "Steps completed");
            appendResultField(out, evidence, "planned_steps", "Steps planned");
            appendResultField(out, evidence, "route", "Agent route");
            appendResultField(out, evidence, "status", "Execution status");
            if (out.toString().endsWith("Execution evidence")) {
                out.append("\n").append(evidence);
            }
        }
        return out.toString();
    }

    /**
     * Unwraps transport envelopes without mistaking HTTP success for the
     * Engineering Agent's actual result. Some workspace handlers return
     * nested {status, message, data} objects, so unwrap only transport-only
     * or empty envelopes and cap recursion to keep malformed responses safe.
     */
    private JSONObject unwrapEngineeringResult(JSONObject item) {
        JSONObject current = item;
        for (int depth = 0; depth < 5; depth++) {
            JSONObject nested = current.optJSONObject("data");
            if (nested == null) break;
            String message = current.optString("message", "").trim();
            boolean transportOnly = message.matches("(?i)HTTP\\s+[0-9]{3}\\s+SUCCESS");
            if (transportOnly || message.isEmpty() || "null".equalsIgnoreCase(message)) {
                current = nested;
            } else {
                break;
            }
        }
        return current;
    }

    private String formatEngineeringResult(JSONObject data) {
        StringBuilder out = new StringBuilder();
        String message = firstNonEmpty(data, "message", "summary", "error", "detail");
        if (!message.isEmpty()) {
            out.append(message);
        } else {
            String type = data.optString("type", "");
            if (!type.isEmpty()) {
                out.append(type.substring(0, 1).toUpperCase(java.util.Locale.ROOT))
                        .append(type.substring(1)).append(" result");
            } else {
                out.append("Result");
            }
        }

        appendResultField(out, data, "status", "Status");
        appendResultField(out, data, "path", "Path");
        appendResultField(out, data, "root", "Workspace root");
        appendResultField(out, data, "verified", "Verified");
        appendResultField(out, data, "checks", "Checks");
        appendResultField(out, data, "entries", "Entries");
        appendResultField(out, data, "files", "Files");
        appendResultField(out, data, "directories", "Directories");
        appendResultField(out, data, "findings", "Findings");
        appendResultField(out, data, "limitations", "Limitations");
        appendResultField(out, data, "summary", "Summary");
        appendResultField(out, data, "branch", "Branch");
        appendResultField(out, data, "commit", "Commit");
        appendResultField(out, data, "replacement_count", "Replacements");
        appendResultField(out, data, "return_code", "Exit code");
        appendResultField(out, data, "stdout", "Output");
        appendResultField(out, data, "stderr", "Errors");
        appendResultField(out, data, "diff", "Diff");
        appendResultField(out, data, "content", "Content");
        return out.toString();
    }

    private String firstNonEmpty(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty() && !value.equals("null")) return value;
        }
        return "";
    }

    private void appendResultField(StringBuilder out, JSONObject object, String key, String label) {
        if (!object.has(key) || object.isNull(key)) return;
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return;
        String rendered;
        if (value instanceof JSONObject) {
            try {
                rendered = ((JSONObject) value).toString(2);
            } catch (org.json.JSONException ignored) {
                rendered = value.toString();
            }
        } else if (value instanceof JSONArray) {
            try {
                rendered = ((JSONArray) value).toString(2);
            } catch (org.json.JSONException ignored) {
                rendered = value.toString();
            }
        } else {
            rendered = String.valueOf(value);
        }
        if (rendered.trim().isEmpty() || rendered.equals("null")) return;
        out.append("\n").append(label).append(": ").append(rendered);
    }
}
