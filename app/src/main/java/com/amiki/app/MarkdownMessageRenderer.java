package com.amiki.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight Markdown display for older Android devices.
 *
 * Intentionally uses one TextView per assistant response rather than creating a view tree
 * for each Markdown block. Parsing is line-oriented and bounded; raw HTML is displayed as
 * text and never executed.
 */
public final class MarkdownMessageRenderer {
    private static final int MAX_INPUT_CHARS = 8000;
    private static final int MAX_OUTPUT_CHARS = 12000;
    private static final int DEFAULT_CODE_BG = 0xFF181D31;
    private static final int LINK_COLOR = 0xFF5BCDFF;
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s{0,6}[-*+]\\s+(.+)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s{0,6}(\\d{1,3})[.)]\\s+(.+)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]{1,500})\\]\\(([^)\\s]{1,1000})\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]{1,1000})`");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern ITALIC = Pattern.compile("(\\*|_)(?=\\S)(.+?)(?<=\\S)\\1");

    private MarkdownMessageRenderer() {}

    /** Pure bounded input helper, also covered by JVM unit tests. */
    static String boundedInput(String markdown) {
        if (markdown == null) return "";
        if (markdown.length() <= MAX_INPUT_CHARS) return markdown;
        return markdown.substring(0, MAX_INPUT_CHARS) + "\n\n[Response shortened for display. The saved message is unchanged.]";
    }

    public static LinearLayout render(Context context, String markdown, int textColor,
                                      int mutedColor, int codeBackground, int accentColor) {
        String source = boundedInput(markdown);
        SpannableStringBuilder output = new SpannableStringBuilder();
        boolean inCode = false;
        String[] lines = source.split("\\n", -1);
        for (int i = 0; i < lines.length && output.length() < MAX_OUTPUT_CHARS; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCode = !inCode;
                if (output.length() > 0) output.append('\n');
                continue;
            }
            if (output.length() > 0) output.append('\n');
            if (inCode) {
                int start = output.length();
                output.append(line);
                if (output.length() > start) {
                    output.setSpan(new TypefaceSpan("monospace"), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    output.setSpan(new BackgroundColorSpan(codeBackground == Color.TRANSPARENT ? DEFAULT_CODE_BG : codeBackground),
                            start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }
            if (trimmed.matches("^(---+|___+|\\*\\*\\*+)$")) {
                int start = output.length();
                output.append("────────────────────");
                output.setSpan(new ForegroundColorSpanCompat(mutedColor), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            Matcher ordered = ORDERED.matcher(line);
            int headingLevel = 0;
            String body = line;
            if (heading.matches()) {
                headingLevel = heading.group(1).length();
                body = heading.group(2);
            } else if (bullet.matches()) {
                body = "•  " + bullet.group(1);
            } else if (ordered.matches()) {
                body = ordered.group(1) + ".  " + ordered.group(2);
            } else if (trimmed.startsWith(">")) {
                body = "│  " + trimmed.replaceFirst("^>\\s?", "");
            }
            int start = output.length();
            appendInline(output, body, context, textColor, codeBackground, accentColor);
            int end = output.length();
            if (headingLevel > 0 && end > start) {
                output.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new RelativeSizeSpan(headingLevel == 1 ? 1.35f : headingLevel == 2 ? 1.2f : 1.08f),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (source.length() > MAX_INPUT_CHARS && output.length() < MAX_OUTPUT_CHARS) {
            output.append("\n\n[Response shortened for display.]");
        }

        TextView text = new TextView(context);
        text.setText(output);
        text.setTextColor(textColor);
        text.setTextSize(14);
        text.setLineSpacing(dp(context, 2), 1.0f);
        text.setLinksClickable(true);
        text.setMovementMethod(LinkMovementMethod.getInstance());
        text.setTextIsSelectable(true);
        text.setLongClickable(true);
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(0, dp(context, 2), 0, dp(context, 2));
        result.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return result;
    }

    private static void appendInline(SpannableStringBuilder out, String source, Context context,
                                     int textColor, int codeBackground, int accentColor) {
        int base = out.length();
        out.append(source);
        int limit = out.length();
        applyPattern(out, source, base, INLINE_CODE, match -> {
            out.setSpan(new TypefaceSpan("monospace"), base + match.start(), base + match.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(codeBackground == Color.TRANSPARENT ? DEFAULT_CODE_BG : codeBackground),
                    base + match.start(), base + match.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        });
        applyPattern(out, source, base, BOLD, match ->
                out.setSpan(new StyleSpan(Typeface.BOLD), base + match.start(2), base + match.end(2), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE));
        applyPattern(out, source, base, ITALIC, match ->
                out.setSpan(new StyleSpan(Typeface.ITALIC), base + match.start(2), base + match.end(2), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE));
        Matcher links = LINK.matcher(source);
        while (links.find()) {
            String destination = links.group(2);
            if (isSafeLink(destination)) {
                out.setSpan(new SafeLinkSpan(context, destination, accentColor), base + links.start(1), base + links.end(1),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.setSpan(new UnderlineSpan(), base + links.start(1), base + links.end(1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        // Avoid carrying the source or spans beyond the bounded rendered range.
        if (out.length() > base + MAX_OUTPUT_CHARS) out.delete(base + MAX_OUTPUT_CHARS, out.length());
    }

    private interface MatchAction { void apply(Matcher matcher); }

    private static void applyPattern(SpannableStringBuilder out, String source, int base,
                                     Pattern pattern, MatchAction action) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) action.apply(matcher);
    }

    private static boolean isSafeLink(String destination) {
        if (destination == null) return false;
        Uri uri = Uri.parse(destination.trim());
        String scheme = uri.getScheme();
        return scheme != null && ("https".equalsIgnoreCase(scheme)
                || "http".equalsIgnoreCase(scheme) || "mailto".equalsIgnoreCase(scheme));
    }

    private static final class SafeLinkSpan extends ClickableSpan {
        private final Context context;
        private final String destination;
        private final int color;
        SafeLinkSpan(Context context, String destination, int color) {
            this.context = context;
            this.destination = destination;
            this.color = color;
        }
        @Override public void updateDrawState(TextPaint paint) {
            paint.setColor(color == Color.TRANSPARENT ? LINK_COLOR : color);
            paint.setUnderlineText(true);
        }
        @Override public void onClick(View widget) {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(destination)));
            } catch (Exception ignored) {
                Toast.makeText(context, "This link could not be opened.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Uses a foreground span without depending on newer platform APIs. */
    private static final class ForegroundColorSpanCompat extends android.text.style.ForegroundColorSpan {
        ForegroundColorSpanCompat(int color) { super(color); }
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }
}
