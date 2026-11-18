package com.amiki.app;

import java.util.regex.Pattern;

/**
 * Jarvis-inspired transcript endpointing. A pause is not automatically the end
 * of a thought: fragments ending in conjunctions, prepositions or trailing
 * punctuation are held so the next recognizer segment can complete the phrase.
 * The caller owns the timer and passes monotonic elapsed time into feed().
 */
public final class VoiceTranscriptAssembler {
    public static final long SETTLE_MS = 250L;
    public static final long CONTINUE_MS = 1600L;
    public static final long MAX_HOLD_MS = 6000L;

    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[,;:–—-]$");
    private static final Pattern EXPLICIT_END = Pattern.compile("[.!?]$");
    private static final Pattern CONTINUES = Pattern.compile(
            "(?i)\\b(and|or|but|so|because|since|if|when|while|that|which|who|whose|to|of|in|on|at|by|for|with|from|about|into|onto|over|under|between|through|the|a|an|my|your|his|her|its|our|their|is|are|was|were|be|been|do|does|did|have|has|had|can|could|would|should|will|shall|might|must|like|than|then|as|very|really|just|some|any|all|both|either|neither)$");
    private static final Pattern OVERRIDE = Pattern.compile(
            "(?i)\\b(stop|wait|cancel|enough|quiet|hold on|shut up|never ?mind|forget it)\\b");

    public static final class Update {
        public final String heldText;
        public final String completedText;
        public final long waitMillis;
        private Update(String heldText, String completedText, long waitMillis) {
            this.heldText = heldText;
            this.completedText = completedText;
            this.waitMillis = waitMillis;
        }
        public boolean isComplete() {
            return completedText != null && !completedText.isEmpty();
        }
    }

    private String held = "";
    private long firstAt = -1L;

    public synchronized Update feed(String transcript, boolean speechStillActive, long nowElapsedMs) {
        String clean = normalize(transcript);
        if (clean.isEmpty()) return new Update(held, null, remainingHold(nowElapsedMs));

        held = held.isEmpty() ? clean : normalize(held + " " + clean);
        if (firstAt < 0L) firstAt = nowElapsedMs;

        if (OVERRIDE.matcher(held).find()) return complete();
        if (speechStillActive) return new Update(held, null, remainingHold(nowElapsedMs));

        long wait = Math.min(holdFor(held), remainingHold(nowElapsedMs));
        if (wait <= 0L) return complete();
        return new Update(held, null, wait);
    }

    public synchronized Update flush() {
        return held.isEmpty() ? new Update("", null, 0L) : complete();
    }

    public synchronized void cancel() {
        held = "";
        firstAt = -1L;
    }

    public synchronized String heldText() {
        return held;
    }

    private long remainingHold(long nowElapsedMs) {
        if (firstAt < 0L) return MAX_HOLD_MS;
        return Math.max(0L, MAX_HOLD_MS - Math.max(0L, nowElapsedMs - firstAt));
    }

    private static long holdFor(String text) {
        String value = text.trim();
        if (value.isEmpty()) return CONTINUE_MS;
        if (EXPLICIT_END.matcher(value).find()) return 0L;
        if (TRAILING_PUNCTUATION.matcher(value).find()) return CONTINUE_MS;
        String[] words = value.split("\\s+");
        if (CONTINUES.matcher(words[words.length - 1]).find()) return CONTINUE_MS;
        if (words.length <= 2) return CONTINUE_MS;
        return SETTLE_MS;
    }

    private Update complete() {
        String completed = held;
        held = "";
        firstAt = -1L;
        return new Update("", completed, 0L);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
