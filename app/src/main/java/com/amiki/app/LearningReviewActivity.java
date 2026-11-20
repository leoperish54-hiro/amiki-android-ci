package com.amiki.app;

import androidx.fragment.app.FragmentActivity;
import android.content.Intent;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.concurrent.Executor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LearningReviewActivity extends FragmentActivity {
    private static final int BG = 0xFF0E1224;
    private static final int PANEL = 0xFF171C33;
    private static final int CARD = 0xFF1D2340;
    private static final int PURPLE = 0xFF8B7CFF;
    private static final int CYAN = 0xFF4DE1C1;
    private static final int TEXT = 0xFFE8EAF6;
    private static final int MUTED = 0xFFADB4D0;
    private static final String UOSA_KEY_ALIAS = "amiki.uosa.owner-signing.v1";
    private static final int UOSA_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    private Executor executor;
    private LinearLayout rootContent;
    private LinearLayout queueContent;
    private TextView statusView;
    private EditText noteTopicInput;
    private EditText noteContentInput;
    private TextView intakeStatusView;
    private Button submitNoteButton;
    private Button runConsolidationButton;
    private String selectedKind = "all";
    private boolean requestInFlight;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        executor = ContextCompat.getMainExecutor(this);
        buildScreen();
        refreshQueue();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(7), dp(14), dp(7));
        header.setBackgroundColor(PANEL);

        Button back = new Button(this);
        back.setText("‹ BACK");
        back.setAllCaps(false);
        back.setTextColor(TEXT);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(88), dp(48)));

        TextView title = text("LEARNING REVIEW", 17, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(62)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        rootContent = new LinearLayout(this);
        rootContent.setOrientation(LinearLayout.VERTICAL);
        rootContent.setPadding(dp(16), dp(14), dp(16), dp(28));

        TextView intro = text(
                "Review what Amiki has proposed learning. Nothing becomes approved knowledge until you explicitly approve it.",
                13, MUTED);
        rootContent.addView(intro, params(-1, -2));

        LinearLayout accessCard = card();
        accessCard.addView(text("UOSA OWNER ACCESS", 11, CYAN));
        statusView = text("Checking active UOSA session…", 12, MUTED);
        statusView.setPadding(0, dp(5), 0, dp(8));
        accessCard.addView(statusView, params(-1, -2));
        Button ownerSecurity = actionButton("OPEN OWNER SECURITY", PURPLE);
        ownerSecurity.setOnClickListener(v ->
                startActivity(new Intent(this, OwnerSecurityActivity.class)));
        accessCard.addView(ownerSecurity, params(-1, dp(44)));
        addCard(rootContent, accessCard);

        TextView setupNote = text(
                "Review-queue reads use a short-lived UOSA L1 session. Approving or rejecting a candidate requires a fresh biometric L2 authorization bound to that exact action. The retired owner bearer token is not accepted.",
                11, MUTED);
        setupNote.setPadding(dp(2), dp(2), dp(2), dp(12));
        rootContent.addView(setupNote, params(-1, -2));

        LinearLayout intakeCard = card();
        intakeCard.addView(text("CAPTURE OWNER KNOWLEDGE", 11, CYAN), params(-1, -2));
        TextView intakeHelp = text(
                "Save a complete note directly to ALE. Amiki keeps the original content as an experience; it is not approved knowledge.",
                12, MUTED);
        intakeHelp.setPadding(0, dp(5), 0, dp(8));
        intakeCard.addView(intakeHelp, params(-1, -2));

        noteTopicInput = learningInput("Topic or subject (max 200 characters)", 200, false);
        LinearLayout.LayoutParams topicParams = params(-1, dp(50));
        topicParams.topMargin = dp(4);
        intakeCard.addView(noteTopicInput, topicParams);

        noteContentInput = learningInput("Write the full note or explanation (max 16,000 characters)", 16000, true);
        noteContentInput.setMinLines(5);
        noteContentInput.setMaxLines(12);
        noteContentInput.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams contentParams = params(-1, dp(150));
        contentParams.topMargin = dp(9);
        intakeCard.addView(noteContentInput, contentParams);

        submitNoteButton = actionButton("SAVE OWNER NOTE", PURPLE);
        submitNoteButton.setOnClickListener(v -> submitLearningNote());
        LinearLayout.LayoutParams submitParams = params(-1, dp(46));
        submitParams.topMargin = dp(10);
        intakeCard.addView(submitNoteButton, submitParams);
        intakeStatusView = text(
                "Saved notes wait for an explicit consolidation run before entering the review queue.",
                11, MUTED);
        intakeStatusView.setPadding(0, dp(8), 0, 0);
        intakeCard.addView(intakeStatusView, params(-1, -2));
        addCard(rootContent, intakeCard);

        LinearLayout consolidationCard = card();
        consolidationCard.addView(text("BOUNDED CONSOLIDATION", 11, CYAN), params(-1, -2));
        TextView consolidationHelp = text(
                "Each run processes at most 10 owner notes and creates pending knowledge candidates. It never approves candidates or updates model weights.",
                12, MUTED);
        consolidationHelp.setPadding(0, dp(5), 0, dp(9));
        consolidationCard.addView(consolidationHelp, params(-1, -2));
        runConsolidationButton = actionButton("RUN CONSOLIDATION (MAX 10)", CARD);
        runConsolidationButton.setOnClickListener(v -> confirmConsolidationRun());
        consolidationCard.addView(runConsolidationButton, params(-1, dp(46)));
        addCard(rootContent, consolidationCard);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        addFilter(filters, "ALL", "all");
        addFilter(filters, "KNOWLEDGE", "knowledge");
        addFilter(filters, "CORRECTIONS", "experience");
        rootContent.addView(filters, params(-1, dp(44)));

        Button refresh = actionButton("↻  REFRESH REVIEW QUEUE", CARD);
        refresh.setOnClickListener(v -> refreshQueue());
        LinearLayout.LayoutParams refreshParams = params(-1, dp(46));
        refreshParams.topMargin = dp(8);
        rootContent.addView(refresh, refreshParams);

        TextView queueHeading = text("PENDING CANDIDATES", 11, PURPLE);
        queueHeading.setTypeface(null, Typeface.BOLD);
        queueHeading.setPadding(dp(2), dp(18), 0, dp(8));
        rootContent.addView(queueHeading, params(-1, -2));

        queueContent = new LinearLayout(this);
        queueContent.setOrientation(LinearLayout.VERTICAL);
        queueContent.addView(text("Loading the owner review queue…", 13, MUTED));
        rootContent.addView(queueContent, params(-1, -2));

        scroll.addView(rootContent, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateTokenStatus();
        updateFilterStyles(filters);
    }

    private void addFilter(LinearLayout parent, String label, String kind) {
        Button button = actionButton(label, CARD);
        button.setTag(kind);
        button.setTextSize(10);
        button.setOnClickListener(v -> {
            selectedKind = kind;
            updateFilterStyles(parent);
            refreshQueue();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
        if (parent.getChildCount() > 0) p.setMargins(dp(5), 0, 0, 0);
        parent.addView(button, p);
    }

    private void updateFilterStyles(LinearLayout filters) {
        for (int i = 0; i < filters.getChildCount(); i++) {
            Button button = (Button) filters.getChildAt(i);
            boolean selected = selectedKind.equals(String.valueOf(button.getTag()));
            button.setBackground(roundRect(selected ? PURPLE : CARD, 10));
            button.setTextColor(TEXT);
        }
    }

    private void updateTokenStatus() {
        boolean active = OwnerSessionVault.hasPrivilegedToken(this);
        statusView.setText(active
                ? "An encrypted UOSA L1 session is available on this device."
                : "No active UOSA session. Enroll this device and create a fresh L1 session in Owner Security.");
    }

    private EditText learningInput(String hint, int maxLength, boolean multiline) {
        EditText input = new EditText(this);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(14);
        input.setHint(hint);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(roundRect(PANEL, 10));
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(maxLength) });
        if (multiline) {
            input.setSingleLine(false);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return input;
    }

    private boolean requirePrivilegedLearningSession() {
        try {
            String token = OwnerSessionVault.readPrivilegedToken(this);
            if (token != null && !token.trim().isEmpty()) return true;
        } catch (Exception failure) {
            OwnerSessionVault.clearPrivileged(this);
        }
        updateTokenStatus();
        intakeStatusView.setText(
                "No usable UOSA privileged session. Open Owner Security and create a fresh 15-minute session.");
        return false;
    }

    private void setLearningActionsBusy(boolean busy) {
        if (submitNoteButton != null) submitNoteButton.setEnabled(!busy);
        if (runConsolidationButton != null) runConsolidationButton.setEnabled(!busy);
    }

    private void submitLearningNote() {
        final String topic = noteTopicInput.getText().toString();
        final String content = noteContentInput.getText().toString();
        if (topic.trim().isEmpty()) {
            noteTopicInput.setError("Enter a topic.");
            return;
        }
        if (content.trim().isEmpty()) {
            noteContentInput.setError("Enter the note content.");
            return;
        }
        if (topic.length() > 200 || content.length() > 16000) {
            intakeStatusView.setText("The topic or note exceeds the backend limit.");
            return;
        }
        if (requestInFlight || !requirePrivilegedLearningSession()) return;

        final JSONObject payload = new JSONObject();
        try {
            payload.put("topic", topic);
            payload.put("content", content);
        } catch (Exception failure) {
            intakeStatusView.setText("Could not prepare the learning note.");
            return;
        }

        requestInFlight = true;
        setLearningActionsBusy(true);
        intakeStatusView.setText("Saving the complete note as an unapproved ALE experience…");
        new Thread(() -> {
            String experienceId = null;
            int responseCode = 0;
            String failureMessage = null;
            try {
                JSONObject response = postPrivilegedJson(ApiEndpoints.LEARNING_NOTES, payload);
                String recordedExperienceId = response.optString("experience_id", "");
                if (!"recorded".equals(response.optString("status"))
                        || recordedExperienceId.trim().isEmpty()
                        || response.optBoolean("auto_approved", true)) {
                    throw new IllegalStateException(
                            "Core did not confirm review-only note storage. Check the review queue before retrying.");
                }
                experienceId = recordedExperienceId;
            } catch (LearningApiException failure) {
                responseCode = failure.statusCode;
                failureMessage = failure.detail;
            } catch (Exception failure) {
                failureMessage = safeMessage(failure);
            }

            final String savedId = experienceId;
            final int code = responseCode;
            final String detail = failureMessage;
            runOnUiThread(() -> {
                requestInFlight = false;
                setLearningActionsBusy(false);
                if (isFinishing()) return;
                if (savedId != null) {
                    noteTopicInput.setText("");
                    noteContentInput.setText("");
                    noteContentInput.clearFocus();
                    intakeStatusView.setText(
                            "Saved as experience " + savedId
                                    + ". It remains unapproved until you run consolidation and review the candidate.");
                } else {
                    intakeStatusView.setText(learningRequestFailure(code, detail));
                }
            });
        }, "amiki-ale-owner-note").start();
    }

    private void confirmConsolidationRun() {
        new AlertDialog.Builder(this)
                .setTitle("Run ALE consolidation?")
                .setMessage(
                        "Amiki will process at most 10 recorded owner notes. It will preserve each original note and create pending knowledge candidates. Nothing will be auto-approved; every candidate still requires your separate review and L2 biometric approval.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("RUN CYCLE", (dialog, which) -> runConsolidationCycle())
                .show();
    }

    private void runConsolidationCycle() {
        if (requestInFlight || !requirePrivilegedLearningSession()) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("max_items", 10);
        } catch (Exception failure) {
            intakeStatusView.setText("Could not prepare the consolidation request.");
            return;
        }

        requestInFlight = true;
        setLearningActionsBusy(true);
        intakeStatusView.setText("Running one bounded consolidation cycle…");
        new Thread(() -> {
            String summary = null;
            int responseCode = 0;
            String failureMessage = null;
            try {
                JSONObject response = postPrivilegedJson(
                        ApiEndpoints.LEARNING_CONSOLIDATION_RUN, payload);
                if (response.optBoolean("auto_approved", true)) {
                    throw new IllegalStateException(
                            "Core did not confirm that consolidation remained review-only.");
                }
                String cycleStatus = response.optString("status", "unknown");
                summary = "Cycle " + cycleStatus
                        + ": processed " + response.optInt("processed_count", 0)
                        + ", created " + response.optInt("created_count", 0)
                        + ", queued for review " + response.optInt("queued_count", 0)
                        + ", remaining " + response.optInt("remaining_count", 0)
                        + ". Auto-approved: no.";
                if (response.optBoolean("has_more", false)) {
                    summary += " More notes remain for a later run.";
                }
            } catch (LearningApiException failure) {
                responseCode = failure.statusCode;
                failureMessage = failure.detail;
            } catch (Exception failure) {
                failureMessage = safeMessage(failure);
            }

            final String cycleSummary = summary;
            final int code = responseCode;
            final String detail = failureMessage;
            runOnUiThread(() -> {
                requestInFlight = false;
                setLearningActionsBusy(false);
                if (isFinishing()) return;
                if (cycleSummary != null) {
                    intakeStatusView.setText(cycleSummary);
                    refreshQueue();
                } else {
                    intakeStatusView.setText(learningRequestFailure(code, detail));
                }
            });
        }, "amiki-ale-consolidation").start();
    }

    private JSONObject postPrivilegedJson(String endpoint, JSONObject payload) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = ApiConnection.open(this, new URL(endpoint));
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] requestBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseBody = stream == null ? "" : readBody(stream);
            if (status < 200 || status >= 300) {
                throw new LearningApiException(status, safeApiDetail(responseBody));
            }
            return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String learningRequestFailure(int statusCode, String detail) {
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            updateTokenStatus();
            return "The UOSA privileged session is missing or expired. Open Owner Security and create a fresh session.";
        }
        if (statusCode == HttpURLConnection.HTTP_FORBIDDEN) {
            return "This privileged session lacks ale.knowledge.submit. Open Owner Security and create a fresh session with the updated ALE scope.";
        }
        if (statusCode == HttpURLConnection.HTTP_CONFLICT) {
            return "ALE could not store the note because its learning store is at capacity. " + safeDetailText(detail);
        }
        if (statusCode != 0) {
            return "ALE request rejected (HTTP " + statusCode + "). " + safeDetailText(detail);
        }
        return "Could not complete the ALE request. Check that Core is running and the local privileged session is available."
                + (detail == null || detail.isEmpty() ? "" : " " + safeDetailText(detail));
    }

    private String safeDetailText(String detail) {
        return detail == null || detail.trim().isEmpty() ? "Refresh and retry." : detail;
    }

    private static final class LearningApiException extends Exception {
        final int statusCode;
        final String detail;

        LearningApiException(int statusCode, String detail) {
            super("Learning API returned HTTP " + statusCode + ".");
            this.statusCode = statusCode;
            this.detail = detail;
        }
    }

    private void refreshQueue() {
        if (requestInFlight) return;
        final String requestedKind = selectedKind;
        final String token;
        try {
            token = OwnerSessionVault.readPrivilegedToken(this);
        } catch (Exception failure) {
            OwnerSessionVault.clearPrivileged(this);
            updateTokenStatus();
            showQueueMessage("The stored UOSA session could not be unlocked. Create a fresh session in Owner Security.");
            return;
        }
        if (token == null || token.trim().isEmpty()) {
            updateTokenStatus();
            showQueueMessage("Create a fresh UOSA L1 session in Owner Security to load pending learning candidates.");
            return;
        }

        requestInFlight = true;
        showQueueMessage("Loading pending candidates…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            JSONArray items = null;
            int statusCode = 0;
            try {
                URL url = new URL(ApiEndpoints.LEARNING_REVIEW_QUEUE
                        + "?kind=" + requestedKind + "&limit=100");
                connection = ApiConnection.open(this, url);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/json");
                statusCode = connection.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject response = new JSONObject(readBody(connection.getInputStream()));
                    if (!"ok".equals(response.optString("status"))) {
                        throw new Exception("Unexpected review queue response.");
                    }
                    items = response.optJSONArray("items");
                    if (items == null) throw new Exception("Review queue response did not include items.");
                }
            } catch (Exception ignored) {
                // Do not log session credentials or response headers.
            } finally {
                if (connection != null) connection.disconnect();
            }

            final JSONArray loadedItems = items;
            final int responseCode = statusCode;
            runOnUiThread(() -> {
                requestInFlight = false;
                if (isFinishing()) return;
                if (!requestedKind.equals(selectedKind)) {
                    refreshQueue();
                    return;
                }
                updateTokenStatus();
                if (loadedItems == null) {
                    if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        showQueueMessage("UOSA session expired or missing (HTTP 401). Open Owner Security and create a fresh L1 session.");
                    } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                        showQueueMessage("This UOSA session lacks the ale.review.read scope (HTTP 403). Create a session with the Android app scope profile.");
                    } else if (responseCode == 0) {
                        showQueueMessage("Could not reach the local Amiki API. Check that Core is running.");
                    } else {
                        showQueueMessage("Review queue request failed (HTTP " + responseCode + ").");
                    }
                    return;
                }
                renderQueue(loadedItems);
            });
        }, "amiki-learning-review-read").start();
    }

    private void renderQueue(JSONArray rawItems) {
        queueContent.removeAllViews();
        List<LearningReviewItem> items = new ArrayList<>();
        for (int i = 0; i < rawItems.length(); i++) {
            LearningReviewItem item = LearningReviewItem.fromJson(rawItems.optJSONObject(i));
            if (item != null) items.add(item);
        }
        if (items.isEmpty()) {
            queueContent.addView(text("No pending candidates in this review filter.", 13, MUTED));
            return;
        }
        queueContent.addView(text(items.size() + " pending item" + (items.size() == 1 ? "" : "s")
                + " returned by Amiki.", 11, MUTED));
        for (LearningReviewItem item : items) addReviewCard(item);
    }

    private void addReviewCard(LearningReviewItem item) {
        LinearLayout reviewCard = card();
        reviewCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        reviewCard.setBackground(roundRect(CARD, 15));

        String label = item.isCorrectionExperience() ? "CORRECTION EXPERIENCE" : "KNOWLEDGE CANDIDATE";
        TextView category = text(label, 10, item.canApprove() ? CYAN : PURPLE);
        category.setTypeface(null, Typeface.BOLD);
        reviewCard.addView(category, params(-1, -2));

        TextView heading = text(item.title, 16, TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, dp(6), 0, dp(5));
        reviewCard.addView(heading, params(-1, -2));

        TextView content = text(item.content.isEmpty() ? "No content supplied." : item.content, 13, TEXT);
        content.setTextIsSelectable(true);
        content.setPadding(0, 0, 0, dp(8));
        reviewCard.addView(content, params(-1, -2));

        addDetail(reviewCard, "Source", item.source.isEmpty() ? "Not supplied" : item.source);
        if (item.confidence != null) {
            addDetail(reviewCard, "Ingestion confidence",
                    String.format(Locale.ROOT, "%.0f%% (not a truth score)", item.confidence * 100.0));
        }
        if (!item.createdAt.isEmpty()) addDetail(reviewCard, "Created", item.createdAt);

        if (!item.evidence.isEmpty()) {
            reviewCard.addView(text("EVIDENCE & PROVENANCE", 10, CYAN), params(-1, -2));
            for (String evidence : item.evidence) {
                TextView evidenceView = text("• " + evidence, 11, MUTED);
                evidenceView.setAutoLinkMask(Linkify.WEB_URLS);
                evidenceView.setMovementMethod(LinkMovementMethod.getInstance());
                evidenceView.setTextIsSelectable(true);
                evidenceView.setPadding(0, dp(4), 0, dp(2));
                reviewCard.addView(evidenceView, params(-1, -2));
            }
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (item.canApprove()) {
            Button approve = actionButton("APPROVE", PURPLE);
            approve.setOnClickListener(v -> confirmDecision(item, true));
            actions.addView(approve, new LinearLayout.LayoutParams(0, dp(44), 1));
        } else {
            TextView restriction = text("Correction signals cannot be promoted to knowledge.", 10, MUTED);
            restriction.setGravity(Gravity.CENTER_VERTICAL);
            actions.addView(restriction, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        Button reject = actionButton("REJECT", CARD);
        reject.setOnClickListener(v -> confirmDecision(item, false));
        LinearLayout.LayoutParams rejectParams = new LinearLayout.LayoutParams(
                item.canApprove() ? 0 : -1, dp(44), item.canApprove() ? 1 : 0);
        if (item.canApprove()) rejectParams.setMargins(dp(7), 0, 0, 0);
        actions.addView(reject, rejectParams);
        LinearLayout.LayoutParams actionParams = params(-1, dp(44));
        actionParams.topMargin = dp(12);
        reviewCard.addView(actions, actionParams);
        addCard(queueContent, reviewCard);
    }

    private void confirmDecision(LearningReviewItem item, boolean approve) {
        String action = approve ? "approve" : "reject";
        String message = approve
                ? "Approve this knowledge candidate? It will become eligible for ALE retrieval. This does not verify the source or update model weights."
                : "Reject this pending learning item? The decision cannot be repeated.";
        new AlertDialog.Builder(this)
                .setTitle(approve ? "Approve knowledge?" : "Reject learning item?")
                .setMessage(message)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton(approve ? "APPROVE" : "REJECT",
                        (dialog, which) -> submitDecision(item, action))
                .show();
    }

    private void submitDecision(LearningReviewItem item, String action) {
        final String baseToken;
        try {
            baseToken = OwnerSessionVault.readPrivilegedToken(this);
        } catch (Exception failure) {
            OwnerSessionVault.clearPrivileged(this);
            updateTokenStatus();
            showQueueMessage("The UOSA session could not be unlocked. Create a fresh L1 session.");
            return;
        }
        if (baseToken == null || baseToken.isEmpty()) {
            showQueueMessage("Create a fresh UOSA L1 session before reviewing candidates.");
            return;
        }
        if (requestInFlight) return;

        final String scope = "approve".equals(action)
                ? "ale.review.approve" : "ale.review.reject";
        final String endpoint = ApiEndpoints.LEARNING_REVIEW_QUEUE + "/" + item.id + "/" + action;
        final String transactionHash;
        try {
            transactionHash = transactionHash("POST", new URL(endpoint).getPath(), "{}");
        } catch (Exception failure) {
            showQueueMessage("Could not prepare the transaction-bound authorization.");
            return;
        }

        requestInFlight = true;
        showQueueMessage("Requesting fresh biometric authorization for this exact decision…");
        JSONObject elevation = new JSONObject();
        try {
            elevation.put("auth_level", "L2");
            elevation.put("scope", scope);
            elevation.put("transaction_hash", transactionHash);
        } catch (Exception failure) {
            requestInFlight = false;
            showQueueMessage("Could not prepare the UOSA elevation request.");
            return;
        }

        new Thread(() -> {
            try {
                JSONObject challenge = postJson(
                        ApiEndpoints.UOSA_ELEVATION_CHALLENGES, elevation, baseToken);
                String message = challenge.getString("message");
                String challengeId = challenge.getString("challenge_id");
                runOnUiThread(() -> authenticateAndSign(
                        message,
                        "Confirm " + action + " for this exact learning candidate",
                        signature -> completeElevatedDecision(endpoint, challengeId, signature, action)
                ));
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    requestInFlight = false;
                    showQueueMessage("Could not request UOSA elevation: " + safeMessage(failure));
                });
            }
        }, "amiki-learning-review-elevation").start();
    }

    private void completeElevatedDecision(
            String endpoint, String challengeId, String signature, String action) {
        new Thread(() -> {
            int statusCode = 0;
            String failureMessage = null;
            try {
                JSONObject completion = new JSONObject();
                completion.put("challenge_id", challengeId);
                completion.put("signature", signature);
                JSONObject elevated = postJson(
                        ApiEndpoints.UOSA_ELEVATED_SESSIONS, completion, null);
                String elevatedToken = elevated.getString("access_token");

                HttpURLConnection connection = null;
                try {
                    connection = ApiConnection.openWithBearer(endpoint, elevatedToken);
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(7000);
                    connection.setReadTimeout(12000);
                    statusCode = connection.getResponseCode();
                    InputStream stream = statusCode >= 200 && statusCode < 300
                            ? connection.getInputStream() : connection.getErrorStream();
                    if (stream != null) {
                        String response = readBody(stream);
                        if (statusCode < 200 || statusCode >= 300) {
                            failureMessage = safeApiDetail(response);
                        }
                    }
                } finally {
                    if (connection != null) connection.disconnect();
                }
            } catch (Exception failure) {
                failureMessage = safeMessage(failure);
            }

            final int responseCode = statusCode;
            final String detail = failureMessage;
            runOnUiThread(() -> {
                requestInFlight = false;
                if (responseCode >= 200 && responseCode < 300) {
                    Toast.makeText(this,
                            "approve".equals(action) ? "Learning item approved." : "Learning item rejected.",
                            Toast.LENGTH_SHORT).show();
                    refreshQueue();
                } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    showQueueMessage("The L2 authorization expired or was rejected. Create a fresh L1 session and retry.");
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    showQueueMessage("The one-use L2 authorization did not match this exact action. It has been consumed; request a new biometric approval.");
                } else {
                    showQueueMessage("Could not " + action + " this item (HTTP "
                            + (responseCode == 0 ? "connection failure" : responseCode) + "). "
                            + (detail == null ? "Refresh and retry." : detail));
                }
            });
        }, "amiki-learning-review-decision").start();
    }

    private JSONObject postJson(String endpoint, JSONObject body, String bearerToken) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = ApiConnection.openWithBearer(endpoint, bearerToken);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(12000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readBody(stream);
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Backend returned HTTP " + status + ": " + safeApiDetail(response));
            }
            return new JSONObject(response);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private interface SignedChallengeCallback {
        void onSigned(String signatureBase64);
    }

    private void authenticateAndSign(
            String message, String promptSubtitle, SignedChallengeCallback callback) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            String activeAlias = getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                    .getString("owner_key_alias", UOSA_KEY_ALIAS);
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(activeAlias, null);
            if (privateKey == null) throw new IllegalStateException("The enrolled owner signing key is unavailable.");
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            BiometricPrompt prompt = new BiometricPrompt(this, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                @NonNull BiometricPrompt.AuthenticationResult authenticationResult) {
                            Signature unlocked = authenticationResult.getCryptoObject() == null
                                    ? null : authenticationResult.getCryptoObject().getSignature();
                            if (unlocked == null) {
                                requestInFlight = false;
                                showQueueMessage("Biometric verification returned no signing operation.");
                                return;
                            }
                            try {
                                unlocked.update(message.getBytes(StandardCharsets.UTF_8));
                                byte[] signed = unlocked.sign();
                                callback.onSigned(Base64.encodeToString(signed, Base64.NO_WRAP));
                            } catch (Exception failure) {
                                requestInFlight = false;
                                showQueueMessage("Could not sign the UOSA challenge: " + safeMessage(failure));
                            }
                        }

                        @Override
                        public void onAuthenticationError(int code, @NonNull CharSequence message) {
                            requestInFlight = false;
                            showQueueMessage("Biometric authorization cancelled or failed: " + message);
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            statusView.setText("Biometric not recognized. Try again or cancel.");
                        }
                    });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Verify owner presence")
                    .setSubtitle(promptSubtitle)
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(UOSA_AUTHENTICATORS)
                    .build();
            prompt.authenticate(info, new BiometricPrompt.CryptoObject(signature));
        } catch (Exception failure) {
            requestInFlight = false;
            showQueueMessage("Could not prepare biometric signing: " + safeMessage(failure));
        }
    }

    private String transactionHash(String method, String path, String canonicalBody) throws Exception {
        String material = method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + canonicalBody;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private String safeApiDetail(String response) {
        try {
            return new JSONObject(response).optString("detail", "request rejected");
        } catch (Exception ignored) {
            return "request rejected";
        }
    }

    private String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private void showQueueMessage(String message) {
        if (queueContent == null) return;
        queueContent.removeAllViews();
        TextView state = text(message, 13, MUTED);
        state.setPadding(dp(4), dp(8), dp(4), dp(8));
        queueContent.addView(state, params(-1, -2));
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("uosa") || normalized.contains("owner security")
                || normalized.contains("session")) {
            Button setup = actionButton("OPEN OWNER SECURITY", PURPLE);
            setup.setOnClickListener(v ->
                    startActivity(new Intent(this, OwnerSecurityActivity.class)));
            queueContent.addView(setup, params(-1, dp(44)));
        }
    }

    private void addDetail(LinearLayout parent, String label, String value) {
        TextView detail = text(label.toUpperCase(Locale.ROOT) + "  •  " + value, 11, MUTED);
        detail.setTextIsSelectable(true);
        detail.setAutoLinkMask(Linkify.WEB_URLS);
        detail.setMovementMethod(LinkMovementMethod.getInstance());
        detail.setPadding(0, dp(3), 0, dp(3));
        parent.addView(detail, params(-1, -2));
    }

    private String readBody(InputStream input) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
                if (body.length() > 256 * 1024) throw new Exception("Response exceeded safe display limit.");
            }
        }
        return body.toString();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        card.setBackground(roundRect(CARD, 14));
        return card;
    }

    private void addCard(LinearLayout parent, View child) {
        LinearLayout.LayoutParams p = params(-1, -2);
        p.setMargins(0, 0, 0, dp(9));
        parent.addView(child, p);
    }

    private Button actionButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setBackground(roundRect(color, 11));
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
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

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }
}
