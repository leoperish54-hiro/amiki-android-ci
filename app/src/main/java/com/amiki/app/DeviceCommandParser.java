package com.amiki.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DeviceCommandParser {

    public enum Action {
        OPEN_SETTINGS,
        OPEN_CHROME,
        BATTERY_STATE,
        CALL_CONTACT,
        SET_ALARM
    }

    public static final class Command {
        public final Action action;
        public final String argument;
        public final int hour;
        public final int minute;
        public final String message;

        private Command(
                Action action,
                String argument,
                int hour,
                int minute,
                String message
        ) {
            this.action = action;
            this.argument = argument;
            this.hour = hour;
            this.minute = minute;
            this.message = message;
        }

        static Command simple(Action action) {
            return new Command(action, null, -1, -1, null);
        }

        static Command call(String name) {
            return new Command(Action.CALL_CONTACT, name, -1, -1, null);
        }

        static Command alarm(int hour, int minute, String message) {
            return new Command(Action.SET_ALARM, null, hour, minute, message);
        }
    }

    private static final Pattern CALL =
            Pattern.compile("^(?:please\\s+)?(?:call|dial)\\s+(.{1,128})$", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALARM =
            Pattern.compile(
                    "^(?:please\\s+)?(?:set|create)\\s+(?:an?\\s+)?alarm\\s+(?:for\\s+)?([^\\s]+(?:\\s+[ap]m)?)(?:\\s+(?:to|for)\\s+(.{1,160}))?$",
                    Pattern.CASE_INSENSITIVE
            );

    private DeviceCommandParser() {}

    public static Command parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        String request = normalize(raw);
        String lowered = request.toLowerCase(Locale.ROOT);

        if (lowered.equals("battery")
                || lowered.equals("battery status")
                || lowered.equals("check battery")
                || lowered.equals("check my battery")
                || lowered.equals("what is my battery")
                || lowered.equals("what's my battery")
                || lowered.equals("how much battery do i have")) {
            return Command.simple(Action.BATTERY_STATE);
        }

        if (lowered.equals("open settings")
                || lowered.equals("open android settings")
                || lowered.equals("settings")) {
            return Command.simple(Action.OPEN_SETTINGS);
        }

        if (lowered.equals("open chrome")
                || lowered.equals("launch chrome")
                || lowered.equals("start chrome")) {
            return Command.simple(Action.OPEN_CHROME);
        }

        Matcher call = CALL.matcher(request);
        if (call.matches()) {
            String name = stripPunctuation(call.group(1));
            return name.isEmpty() ? null : Command.call(name);
        }

        Matcher alarm = ALARM.matcher(request);
        if (alarm.matches()) {
            int[] hm = parseTime(alarm.group(1));
            if (hm == null) {
                return null;
            }
            String message = alarm.group(2) == null
                    ? null
                    : stripPunctuation(alarm.group(2));
            return Command.alarm(hm[0], hm[1], message);
        }

        return null;
    }

    private static String normalize(String raw) {
        String text = raw.trim().replaceAll("\\s+", " ");
        String lower = text.toLowerCase(Locale.ROOT);
        String[] prefixes = {"amiki:", "amiki,", "amiki "};
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                return text.substring(prefix.length()).trim();
            }
        }
        return text;
    }

    private static String stripPunctuation(String value) {
        return value == null
                ? ""
                : value.trim().replaceFirst("[.!?]+$", "").trim();
    }

    private static int[] parseTime(String token) {
        if (token == null) {
            return null;
        }

        Matcher match = Pattern.compile(
                "^([0-9]{1,2})(?::([0-9]{2}))?\\s*([ap]m)?$",
                Pattern.CASE_INSENSITIVE
        ).matcher(token.trim());

        if (!match.matches()) {
            return null;
        }

        int hour = Integer.parseInt(match.group(1));
        int minute = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
        String meridiem = match.group(3);

        if (minute > 59) {
            return null;
        }

        if (meridiem != null) {
            if (hour < 1 || hour > 12) {
                return null;
            }
            if (meridiem.equalsIgnoreCase("am")) {
                hour = hour == 12 ? 0 : hour;
            } else {
                hour = hour == 12 ? 12 : hour + 12;
            }
        } else if (hour > 23) {
            return null;
        }

        return new int[]{hour, minute};
    }
}
