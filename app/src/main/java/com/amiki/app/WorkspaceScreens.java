package com.amiki.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class WorkspaceScreens {

    public interface NavigationCallbacks {
        void onBack();
        void onMenu();
        void onDatabaseRefresh(
                LinearLayout content,
                TextView status
        );
        void onWorkspaceMessage(
                String message,
                LinearLayout content,
                ScrollView scroll,
                Button send,
                TextView status
        );
        void onDirectAgentMessage(
                String agent,
                String message,
                LinearLayout content,
                ScrollView scroll,
                Button send,
                TextView status
        );
    }

    private final Activity activity;
    private final NavigationCallbacks callbacks;

    private static final int BG = Color.rgb(5, 7, 18);
    private static final int PANEL = Color.rgb(12, 15, 30);
    private static final int CARD = Color.rgb(17, 21, 40);
    private static final int PURPLE = Color.rgb(139, 70, 255);
    private static final int TEXT = Color.rgb(245, 245, 250);
    private static final int MUTED = Color.rgb(165, 165, 185);

    public WorkspaceScreens(
            Activity activity,
            NavigationCallbacks callbacks
    ) {
        this.activity = activity;
        this.callbacks = callbacks;
    }

    public LinearLayout buildSectionScreen(
            String titleText,
            String subtitleText
    ) {
        LinearLayout section = new LinearLayout(activity);
        android.util.Log.d("AMIKI_UI", "BUILD SECTION: " + titleText);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(BG);

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

        TextView title = text(titleText, 20, TEXT);
        title.setTypeface(null, 1);

        header.addView(
                back,
                new LinearLayout.LayoutParams(
                        dp(80),
                        dp(48)
                )
        );

        header.addView(
                title,
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1
                )
        );

        Button menu = new Button(activity);
        menu.setText("☰");
        menu.setTextColor(TEXT);
        menu.setTextSize(22);
        menu.setAllCaps(false);
        menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setOnClickListener(v -> callbacks.onMenu());

        header.addView(
                menu,
                new LinearLayout.LayoutParams(
                        dp(48),
                        dp(48)
                )
        );

        section.addView(header);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                dp(20),
                dp(20),
                dp(20),
                dp(30)
        );

        TextView subtitle = text(subtitleText, 14, MUTED);
        content.addView(subtitle);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable workspaceCardBackground = roundRect(CARD, 16);
        workspaceCardBackground.setStroke(dp(1), sectionAccent(titleText));
        card.setBackground(workspaceCardBackground);

        String cardTitle;
        String cardBody;

        if (titleText.equals("UNIVERSITY")) {
            cardTitle = "🎓 Academic Workspace";
            cardBody = "Ask Amiki about courses, assignments, lectures, study plans, or university work.";
        } else if (titleText.equals("PROJECTS")) {
            cardTitle = "🛠 Development Workspace";
            cardBody = "Inspect workspace files, read project code, run safe Python scripts, or ask Amiki about your development work.";
        } else if (titleText.equals("ANALYSIS")) {
            cardTitle = "🧠 Reasoning Workspace";
            cardBody = "Structured analysis, comparisons and problem solving will live here.";
        } else if (titleText.equals("DATABASE")) {
            cardTitle = "🗄 Data Workspace";
            cardBody = "Memory, stored information, and system data will be surfaced here as their APIs are connected.";
        } else {
            cardTitle = "⚙ Amiki Configuration";
            cardBody = "Amiki's connection, appearance, behavior, and runtime preferences will be managed here.";
        }

        TextView cardHeading = text(cardTitle, 17, TEXT);
        cardHeading.setTypeface(null, 1);

        TextView cardText = text(cardBody, 14, MUTED);
        cardText.setPadding(0, dp(10), 0, 0);

        card.addView(cardHeading);
        card.addView(cardText);

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        cardParams.setMargins(0, dp(20), 0, 0);
        content.addView(card, cardParams);
        if (titleText.equals("UNIVERSITY") || titleText.equals("PROJECTS") || titleText.equals("ANALYSIS")) {
            TextView directLabel = text(
                    titleText.equals("UNIVERSITY")
                            ? "DIRECT AGENTS  •  RESEARCH + TUTOR"
                            : titleText.equals("PROJECTS")
                                ? "DIRECT AGENT  •  ENGINEERING"
                                : "DIRECT AGENT  •  ANALYSIS",
                    11,
                    PURPLE
            );
            directLabel.setTypeface(null, Typeface.BOLD);
            directLabel.setPadding(0, dp(20), 0, dp(4));
            content.addView(directLabel);

            final String[] selectedAgent = new String[]{
                    titleText.equals("UNIVERSITY")
                            ? "research"
                            : titleText.equals("PROJECTS")
                                ? "engineering"
                                : "analysis"
            };
            if (titleText.equals("UNIVERSITY")) {
                LinearLayout agentRow = new LinearLayout(activity);
                agentRow.setOrientation(LinearLayout.HORIZONTAL);
                Button research = new Button(activity);
                Button tutor = new Button(activity);
                research.setText("RESEARCH");
                tutor.setText("TUTOR");
                research.setTextColor(TEXT);
                tutor.setTextColor(TEXT);
                research.setBackground(roundRect(PURPLE, 10));
                tutor.setBackground(roundRect(CARD, 10));
                research.setOnClickListener(v -> {
                    selectedAgent[0] = "research";
                    research.setBackground(roundRect(PURPLE, 10));
                    tutor.setBackground(roundRect(CARD, 10));
                });
                tutor.setOnClickListener(v -> {
                    selectedAgent[0] = "tutor";
                    tutor.setBackground(roundRect(PURPLE, 10));
                    research.setBackground(roundRect(CARD, 10));
                });
                agentRow.addView(research, new LinearLayout.LayoutParams(0, dp(44), 1f));
                agentRow.addView(tutor, new LinearLayout.LayoutParams(0, dp(44), 1f));
                content.addView(agentRow);
            }

            EditText input = new EditText(activity);
            input.setHint(
                    titleText.equals("PROJECTS")
                            ? "Ask Amiki about a project or workspace..."
                            : titleText.equals("ANALYSIS")
                                ? "Ask Amiki an analysis question..."
                                : "Ask Amiki a university question..."
            );
            input.setHintTextColor(MUTED);
            input.setTextColor(TEXT);
            input.setTextSize(15);
            input.setSingleLine(false);
            input.setMinLines(3);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setPadding(dp(14), dp(12), dp(14), dp(12));
            input.setBackground(roundRect(CARD, 12));

            LinearLayout.LayoutParams inputParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
            inputParams.setMargins(0, dp(20), 0, 0);
            content.addView(input, inputParams);

            Button send = new Button(activity);
            send.setText("SEND");
            send.setTextColor(TEXT);
            send.setTextSize(13);
            send.setAllCaps(false);
            send.setBackground(roundRect(PURPLE, 12));

            LinearLayout.LayoutParams sendParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(48)
                    );
            sendParams.setMargins(0, dp(12), 0, 0);
            content.addView(send, sendParams);

            if (titleText.equals("PROJECTS")) {
                TextView actions = text(
                        "Examples: \"show workspace\" • \"read <workspace-file>\" • \"run test_execute_sample.py\"",
                        12,
                        MUTED
                );
                actions.setPadding(0, dp(12), 0, 0);
                content.addView(actions);
            }

            TextView status = text("READY", 13, MUTED);
            status.setPadding(0, dp(16), 0, 0);
            content.addView(status);

            send.setOnClickListener(v -> {
                String message = input.getText().toString().trim();

                if (message.isEmpty()) {
                    input.setError(
                            titleText.equals("PROJECTS")
                                    ? "Enter a project or workspace request first."
                                    : titleText.equals("ANALYSIS")
                                        ? "Enter an analysis request first."
                                        : "Enter a university question first."
                    );
                    return;
                }

                callbacks.onDirectAgentMessage(
                        selectedAgent[0],
                        message,
                        content,
                        scroll,
                        send,
                        status
                );

                input.setText("");
            });
        } else {


        TextView status = text(
                titleText.equals("DATABASE")
                            ? "Database workspace will activate as its APIs are connected."
                            : "Settings controls will activate as their configuration APIs are connected.",
                13,
                MUTED
        );

        status.setPadding(0, dp(24), 0, 0);
        content.addView(status);
        }

        scroll.addView(
                content,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        section.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        return section;
    }

    public LinearLayout buildDatabaseScreen() {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView back = text("‹", 32, TEXT);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> callbacks.onBack());

        header.addView(
                back,
                new LinearLayout.LayoutParams(dp(48), dp(48))
        );

        TextView title = text("DATABASE", 18, TEXT);
        title.setTypeface(null, Typeface.BOLD);

        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                );

        titleParams.setMargins(dp(12), 0, 0, 0);
        header.addView(title, titleParams);

        TextView menu = text("☰", 24, TEXT);
        menu.setGravity(Gravity.CENTER);
        menu.setOnClickListener(v -> callbacks.onMenu());

        header.addView(
                menu,
                new LinearLayout.LayoutParams(dp(48), dp(48))
        );

        section.addView(header);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                dp(20),
                dp(20),
                dp(20),
                dp(30)
        );

        TextView subtitle = text(
                "Amiki data and storage",
                14,
                MUTED
        );
        content.addView(subtitle);

        LinearLayout dataContainer = new LinearLayout(activity);
        dataContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams dataParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        dataParams.setMargins(0, dp(20), 0, 0);
        content.addView(dataContainer, dataParams);

        TextView status = text(
                "LOADING DATABASE STATUS...",
                13,
                MUTED
        );
        status.setPadding(0, dp(16), 0, 0);

        Button refresh = new Button(activity);
        refresh.setText("REFRESH");
        refresh.setTextColor(TEXT);
        refresh.setBackground(roundRect(PURPLE, 12));
        refresh.setOnClickListener(
                v -> callbacks.onDatabaseRefresh(
                        dataContainer,
                        status
                )
        );

        LinearLayout.LayoutParams refreshParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                );

        refreshParams.setMargins(0, dp(16), 0, 0);
        content.addView(refresh, refreshParams);

        content.addView(status);

        scroll.addView(
                content,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        section.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        callbacks.onDatabaseRefresh(dataContainer, status);

        return section;
    }

    private int sectionAccent(String title) {
        if ("UNIVERSITY".equalsIgnoreCase(title)) return Color.rgb(20, 184, 166);  // teal
        if ("PROJECTS".equalsIgnoreCase(title)) return Color.rgb(217, 70, 239);    // fuchsia
        if ("ANALYSIS".equalsIgnoreCase(title)) return Color.rgb(111, 126, 255);   // periwinkle
        if ("DATABASE".equalsIgnoreCase(title)) return Color.rgb(56, 189, 248);    // cyan
        if ("SETTINGS".equalsIgnoreCase(title)) return Color.rgb(245, 158, 11);    // amber
        if ("DEVICE".equalsIgnoreCase(title)) return Color.rgb(52, 211, 153);      // emerald
        if ("SECURITY".equalsIgnoreCase(title)) return Color.rgb(251, 113, 133);   // rose
        return PURPLE;
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
        return (int) (
                value * activity.getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }
}
