package com.amiki.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Defensive, presentation-focused representation of one ALE review candidate. */
final class LearningReviewItem {
    final String id;
    final String collection;
    final String kind;
    final String title;
    final String content;
    final String source;
    final String createdAt;
    final Double confidence;
    final List<String> evidence;

    private LearningReviewItem(String id, String collection, String kind, String title,
                               String content, String source, String createdAt,
                               Double confidence, List<String> evidence) {
        this.id = id;
        this.collection = collection;
        this.kind = kind;
        this.title = title;
        this.content = content;
        this.source = source;
        this.createdAt = createdAt;
        this.confidence = confidence;
        this.evidence = Collections.unmodifiableList(evidence);
    }

    static LearningReviewItem fromJson(JSONObject value) {
        if (value == null) return null;
        String id = value.optString("id", "").trim();
        String collection = value.optString("collection", "").trim();
        if (id.isEmpty() || (!"knowledge".equals(collection) && !"experiences".equals(collection))) {
            return null;
        }

        String kind = value.optString("kind", "").trim();
        String title = "knowledge".equals(collection)
                ? value.optString("topic", "Learning candidate")
                : "Correction experience";
        String content = value.optString("content", "").trim();
        String source = value.optString("source", "").trim();
        String createdAt = value.optString("created_at", "").trim();
        Double confidence = value.has("confidence") && !value.isNull("confidence")
                ? value.optDouble("confidence") : null;

        List<String> evidence = new ArrayList<>();
        JSONArray values = value.optJSONArray("evidence");
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                String entry = values.optString(i, "").trim();
                if (!entry.isEmpty()) evidence.add(entry);
            }
        }
        return new LearningReviewItem(id, collection, kind, title, content,
                source, createdAt, confidence, evidence);
    }

    boolean canApprove() {
        return "knowledge".equals(collection);
    }

    boolean isCorrectionExperience() {
        return "experiences".equals(collection);
    }
}
