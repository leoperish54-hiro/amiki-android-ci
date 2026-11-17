package com.amiki.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SecurityReportFormatterTest {
    @Test
    public void formatsTopLevelSecurityEvidenceAndLimitations() throws Exception {
        JSONObject result = new JSONObject()
                .put("status", "degraded")
                .put("scope", "bounded_runtime_and_source_policy_audit")
                .put("safe", true)
                .put("checks", new JSONArray().put(new JSONObject()
                        .put("name", "device_execution_boundary")
                        .put("status", "warning")
                        .put("detail", "Final device outcome is not independently verified."))
                        .put(new JSONObject()
                                .put("name", "agent_tool_boundaries")
                                .put("status", "ok")
                                .put("detail", "Seven agent builders inspected.")))
                .put("findings", new JSONArray().put(new JSONObject()
                        .put("severity", "high")
                        .put("check", "source_code_patterns")
                        .put("finding", "Potential credential literal detected.")))
                .put("limitations", new JSONArray().put("Not a dependency vulnerability database audit."))
                .put("source_scan", new JSONObject()
                        .put("files_scanned", 42)
                        .put("truncated", false));

        String report = SecurityReportFormatter.format(result);

        assertTrue(report.contains("Status: degraded"));
        assertTrue(report.contains("device_execution_boundary"));
        assertTrue(report.contains("Final device outcome is not independently verified."));
        assertTrue(report.contains("[HIGH] source_code_patterns"));
        assertTrue(report.contains("Not a dependency vulnerability database audit."));
        assertTrue(report.contains("Files scanned: 42"));
        assertTrue(report.contains("not proof that the application is secure"));
    }

    @Test
    public void unwrapsTransportEnvelopeAndDoesNotRenderHttpSuccessAsTheReport() throws Exception {
        JSONObject payload = new JSONObject()
                .put("status", "pass")
                .put("checks", new JSONArray().put(new JSONObject()
                        .put("name", "agent_tool_boundaries")
                        .put("status", "ok")
                        .put("detail", "Boundaries inspected.")));
        JSONObject result = new JSONObject()
                .put("message", "HTTP 200 Success")
                .put("data", new JSONObject().put("message", "HTTP 200 Success").put("data", payload));

        String report = SecurityReportFormatter.format(result);

        assertTrue(report.contains("Boundaries inspected."));
        assertFalse(report.contains("HTTP 200 Success"));
    }

    @Test
    public void handlesNullAndEmptyPayloads() {
        assertTrue(SecurityReportFormatter.format(null).isEmpty());
        assertTrue(SecurityReportFormatter.format(new JSONObject()).isEmpty());
    }
}
