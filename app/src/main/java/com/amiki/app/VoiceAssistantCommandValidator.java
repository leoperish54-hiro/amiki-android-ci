package com.amiki.app;

/** Bounds and normalizes a single command returned by Android's speech recognizer. */
public final class VoiceAssistantCommandValidator {
    public static final int MAX_COMMAND_CHARS = 2000;

    private VoiceAssistantCommandValidator() {}

    public static String validate(String recognizedText) {
        if (recognizedText == null) {
            throw new IllegalArgumentException("No voice command was recognized.");
        }
        String normalized = recognizedText.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("The voice command was empty.");
        }
        if (normalized.length() > MAX_COMMAND_CHARS) {
            throw new IllegalArgumentException("The voice command is too long.");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char value = normalized.charAt(i);
            if (value == 0 || (Character.isISOControl(value) && value != '\n' && value != '\t')) {
                throw new IllegalArgumentException("The voice command contains unsupported control characters.");
            }
        }
        return normalized;
    }
}
