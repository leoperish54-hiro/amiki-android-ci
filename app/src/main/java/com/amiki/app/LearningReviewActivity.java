package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LearningReviewActivity extends Activity {
    private static final int BG = 0xFF0E1224;
    private static final int PANEL = 0xFF171C33;
    private static final int CARD = 0xFF1D2340;
    private static final int PURPLE = 0xFF8B7CFF;
    private static final int CYAN = 0xFF4DE1C1;
    private static final int TEXT = 0xFFE8EAF6;
    private static final int MUTED = 0xFFADB4D0;

    private LinearLayout rootContent;
    private LinearLayout queueContent;
    private TextView statusView;
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

        LinearLayout tokenCard = card();
        tokenCard.addView(text("OWNER ACCESS", 11, CYAN));
        statusView = text("Checking secure owner token…", 12, MUTED);
        statusView.setPadding(0, dp(5), 0, dp(8));
        tokenCard.addView(statusView, params(-1, -2));

        LinearLayout tokenActions = new LinearLayout(this);
        tokenActions.setOrientation(LinearLayout.HORIZONTAL);
        Button tokenButton = actionButton("SET / CHANGE TOKEN", PURPLE);
        tokenButton.setOnClickListener(v -> showTokenDialog());
        tokenActions.addView(tokenButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button clearButton = actionButton("REMOVE", CARD);
        clearButton.setOnClickListener(v -> confirmRemoveToken());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(100), dp(44));
        clearParams.setMargins(dp(7), 0, 0, 0);
        tokenActions.addView(clearButton, clearParams);
        tokenCard.addView(tokenActions, params(-1, dp(44)));
        addCard(rootContent, tokenCard);

        TextView setupNote = text(
                "The backend must also have AMIKI_OWNER_REVIEW_TOKEN configured in its runtime environment. Generate a strong token locally; never commit it or paste it into chat.",
                11, MUTED);
        setupNote.setPadding(dp(2), dp(2), dp(2), dp(12));
        rootContent.addView(setupNote, params(-1, -2));

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
        boolean configured = OwnerTokenVault.hasToken(this);
        statusView.setText(configured
                ? "A token is stored encrypted with Android Keystore on this device."
                : "No owner token is stored on this device.");
    }

    private void showTokenDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSaveEnabled(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Paste locally generated owner token");
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Set owner review token")
                .setMessage("The token is encrypted using an Android Keystore AES-GCM key. It is not displayed after saving.")
                .setView(input)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE SECURELY", null)
                .create();
        dialog.setOnDismissListener(ignored ->
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        dialog.setOnShowListener(ignored -> {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String token = input.getText().toString();
                    if (token.trim().length() < 32 || token.length() > 4096) {
                        input.setError("Token must contain 32–4096 characters.");
                        return;
                    }
                    try {
                        OwnerTokenVault.saveToken(this, token);
                        input.setText("");
                        dialog.dismiss();
                        updateTokenStatus();
                        refreshQueue();
                        Toast.makeText(this, "Owner token stored securely.", Toast.LENGTH_SHORT).show();
                    } catch (Exception failure) {
                        input.setText("");
                        Toast.makeText(this, "Secure token storage failed. No token was saved.", Toast.LENGTH_LONG).show();
                    }
                });
        });
        dialog.show();
    }

    private void confirmRemoveToken() {
        new AlertDialog.Builder(this)
                .setTitle("Remove owner token?")
                .setMessage("This removes the encrypted token and its Android Keystore key from this device. It does not change the backend token.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("REMOVE", (dialog, which) -> {
                    OwnerTokenVault.clear(this);
                    updateTokenStatus();
                    showQueueMessage("Owner token removed. Add it again to access the review queue.");
                })
                .show();
    }

    private void refreshQueue() {
        if (requestInFlight) return;
        final String requestedKind = selectedKind;
        final String token;
        try {
            token = OwnerTokenVault.readToken(this);
        } catch (Exception failure) {
            OwnerTokenVault.clear(this);
            updateTokenStatus();
            showQueueMessage("The stored token could not be decrypted. Set it again to continue.");
            return;
        }
        if (token == null || token.trim().isEmpty()) {
            updateTokenStatus();
            showQueueMessage("Set the owner token to load pending learning candidates.");
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
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + token);
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
                // The UI reports a bounded status; credentials and response headers stay private.
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
                        showQueueMessage("Owner token rejected (HTTP 401). Change the token and retry.");
                    } else if (responseCode == HttpURLConnection.HTTP_UNAVAILABLE) {
                        showQueueMessage("Owner review is disabled on the backend (HTTP 503). Configure AMIKI_OWNER_REVIEW_TOKEN in the API process environment.");
                    } else if (responseCode == 0) {
                        showQueueMessage("Could not reach the local Amiki API. Check that the backend is running.");
                    } else {
                        showQueueMessage("Review queue request failed (HTTP " + responseCode + ").");
                    }
                    return;
                }
                renderQueue(loadedItems);
            });
        }).start();
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
        final String token;
        try {
            token = OwnerTokenVault.readToken(this);
        } catch (Exception failure) {
            OwnerTokenVault.clear(this);
            updateTokenStatus();
            showQueueMessage("The stored token is unavailable. Set it again.");
            return;
        }
        if (token == null || token.isEmpty()) {
            showQueueMessage("Set the owner token before reviewing candidates.");
            return;
        }
        if (requestInFlight) return;
        requestInFlight = true;
        showQueueMessage(("approve".equals(action) ? "Approving " : "Rejecting ") + "candidate…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            int statusCode = 0;
            try {
                String endpoint = ApiEndpoints.LEARNING_REVIEW_QUEUE + "/"
                        + item.id + "/" + action;
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + token);
                statusCode = connection.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    // Consume the small JSON response before closing the connection.
                    readBody(connection.getInputStream());
                }
            } catch (Exception ignored) {
                // Do not log or expose the owner credential.
            } finally {
                if (connection != null) connection.disconnect();
            }
            final int responseCode = statusCode;
            runOnUiThread(() -> {
                requestInFlight = false;
                if (responseCode >= 200 && responseCode < 300) {
                    Toast.makeText(this,
                            "approve".equals(action) ? "Learning item approved." : "Learning item rejected.",
                            Toast.LENGTH_SHORT).show();
                    refreshQueue();
                } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    showQueueMessage("Owner token rejected (HTTP 401). Change the token and retry.");
                } else {
                    showQueueMessage("Could not " + action + " this item (HTTP "
                            + (responseCode == 0 ? "connection failure" : responseCode) + "). Refresh and retry.");
                }
            });
        }).start();
    }

    private void showQueueMessage(String message) {
        if (queueContent == null) return;
        queueContent.removeAllViews();
        TextView state = text(message, 13, MUTED);
        state.setPadding(dp(4), dp(8), dp(4), dp(8));
        queueContent.addView(state, params(-1, -2));
        if (message.startsWith("Set the owner token")
                || message.startsWith("Owner token rejected")
                || message.startsWith("The stored token")) {
            Button setup = actionButton("CONFIGURE OWNER TOKEN", PURPLE);
            setup.setOnClickListener(v -> showTokenDialog());
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
