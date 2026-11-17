package com.amiki.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Formats the Security Agent's structured audit response without hiding evidence. */
final class SecurityReportFormatter {
    private SecurityReportFormatter() {}

    static String format(JSONObject result) {
        if (result == null) return "";
        JSONObject data = result;
        // Direct-agent tools may wrap the actual result in one or more {data: ...}
        // transport envelopes. Prefer the deepest object, with a strict depth bound.
        for (int depth = 0; depth < 5; depth++) {
            JSONObject nested = data.optJSONObject("data");
            if (nested == null || nested == data) break;
            data = nested;
        }

        StringBuilder out = new StringBuilder();
        appendField(out, data, "status", "Status");
        appendField(out, data, "scope", "Audit scope");
        appendField(out, data, "safe", "Safe");

        JSONObject scan = data.optJSONObject("source_scan");
        if (scan != null) {
            appendSection(out, "Source scan coverage");
            appendField(out, scan, "status", "  Status");
            appendField(out, scan, "scope", "  Scope");
            appendField(out, scan, "repositories_scanned", "  Repositories");
            appendField(out, scan, "files_scanned", "  Files scanned");
            appendField(out, scan, "bytes_scanned", "  Bytes scanned");
            appendField(out, scan, "truncated", "  Truncated");
        }

        JSONObject androidScan = data.optJSONObject("android_scan");
        if (androidScan != null) {
            appendSection(out, "Android source scan");
            appendField(out, androidScan, "status", "  Status");
            appendField(out, androidScan, "files_scanned", "  Files scanned");
            appendField(out, androidScan, "bytes_scanned", "  Bytes scanned");
            appendField(out, androidScan, "truncated", "  Truncated");
            appendField(out, androidScan, "reason", "  Coverage note");
        }

        JSONArray checks = data.optJSONArray("checks");
        if (checks != null && checks.length() > 0) {
            appendSection(out, "Checks");
            for (int i = 0; i < checks.length(); i++) {
                JSONObject check = checks.optJSONObject(i);
                if (check == null) continue;
                String name = value(check, "name", "Unnamed check");
                String status = value(check, "status", "unknown").toUpperCase();
                String detail = value(check, "detail", "");
                out.append("• ").append(status).append(" — ").append(name);
                if (!detail.isEmpty()) out.append("\n  ").append(detail);
                out.append("\n");
            }
        }

        JSONArray findings = data.optJSONArray("findings");
        if (findings != null && findings.length() > 0) {
            appendSection(out, "Findings");
            for (int i = 0; i < findings.length(); i++) {
                JSONObject finding = findings.optJSONObject(i);
                if (finding == null) {
                    Object raw = findings.opt(i);
                    if (raw != null && !JSONObject.NULL.equals(raw)) out.append("• ").append(raw).append("\n");
                    continue;
                }
                String severity = value(finding, "severity", "unspecified").toUpperCase();
                String check = value(finding, "check", value(finding, "category", ""));
                String description = value(finding, "finding", value(finding, "detail", ""));
                out.append("• [").append(severity).append("]");
                if (!check.isEmpty()) out.append(" ").append(check).append(":");
                if (!description.isEmpty()) out.append(" ").append(description);
                out.append("\n");
            }
        }

        appendStringArray(out, data.optJSONArray("limitations"), "Limitations");
        appendStringArray(out, data.optJSONArray("patterns"), "Patterns checked");

        if (out.length() == 0) {
            String message = value(data, "message", value(data, "summary", value(data, "detail", value(data, "content", ""))));
            if (!message.isEmpty()) out.append(message);
        }
        if (out.length() > 0) {
            out.append("\n\nReminder: a clean bounded pattern scan is not proof that the application is secure.");
        }
        return out.toString().trim();
    }

    private static void appendField(StringBuilder out, JSONObject object, String key, String label) {
        String value = value(object, key, "");
        if (value.isEmpty()) return;
        appendSection(out, label + ": " + value);
    }

    private static void appendSection(StringBuilder out, String heading) {
        if (out.length() > 0) out.append("\n");
        out.append(heading).append("\n");
    }

    private static void appendStringArray(StringBuilder out, JSONArray values, String label) {
        if (values == null || values.length() == 0) return;
        appendSection(out, label);
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            if (value != null && !JSONObject.NULL.equals(value)) out.append("• ").append(value).append("\n");
        }
    }

    private static String value(JSONObject object, String key, String fallback) {
        if (object == null || !object.has(key)) return fallback;
        Object value = object.opt(key);
        if (value == null || JSONObject.NULL.equals(value)) return fallback;
        if (value instanceof JSONObject || value instanceof JSONArray) return value.toString();
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? fallback : text;
    }
}
