package com.amiki.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class LearningReviewItemTest {
    @Test
    public void parsesKnowledgeAndRetainsEvidenceProvenance() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", "know_123");
        json.put("collection", "knowledge");
        json.put("kind", "knowledge");
        json.put("topic", "Example topic");
        json.put("content", "Evidence-backed content");
        json.put("source", "Research ingestion");
        json.put("created_at", "2026-11-04T10:00:00+00:00");
        json.put("confidence", 0.35);
        json.put("evidence", new JSONArray()
                .put("source_url: https://example.org/article")
                .put("retrieved_at: 2026-11-04T10:00:00+00:00"));

        LearningReviewItem item = LearningReviewItem.fromJson(json);

        assertNotNull(item);
        assertEquals("Example topic", item.title);
        assertEquals(2, item.evidence.size());
        assertTrue(item.evidence.get(0).contains("https://example.org/article"));
        assertTrue(item.canApprove());
        assertFalse(item.isCorrectionExperience());
    }

    @Test
    public void correctionExperiencesCanBeRejectedButNeverApproved() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", "exp_123");
        json.put("collection", "experiences");
        json.put("kind", "correction_signal");
        json.put("content", "The correct answer is 42.");

        LearningReviewItem item = LearningReviewItem.fromJson(json);

        assertNotNull(item);
        assertEquals("Correction experience", item.title);
        assertFalse(item.canApprove());
        assertTrue(item.isCorrectionExperience());
    }

    @Test
    public void rejectsRecordsWithoutStableIdOrKnownCollection() throws Exception {
        JSONObject missingId = new JSONObject();
        missingId.put("collection", "knowledge");
        assertNull(LearningReviewItem.fromJson(missingId));

        JSONObject unknownCollection = new JSONObject();
        unknownCollection.put("id", "x");
        unknownCollection.put("collection", "skills");
        assertNull(LearningReviewItem.fromJson(unknownCollection));
    }
}
