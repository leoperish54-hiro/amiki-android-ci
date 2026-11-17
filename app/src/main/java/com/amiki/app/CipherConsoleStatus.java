package com.amiki.app;

import org.json.JSONObject;

/** Strict, display-safe projection of the Amiki-to-Cipher read-only health contract. */
final class CipherConsoleStatus {
    final String status;
    final boolean auditChainValid;
    final int auditEventCount;

    private CipherConsoleStatus(String status, boolean auditChainValid, int auditEventCount) {
        this.status = status;
        this.auditChainValid = auditChainValid;
        this.auditEventCount = auditEventCount;
    }

    static CipherConsoleStatus fromJson(JSONObject value) throws IllegalArgumentException {
        if (value == null || !"cipher".equals(value.optString("service", ""))
                || !(value.opt("connected") instanceof Boolean) || !value.optBoolean("connected")
                || !"loopback_read_only_prototype".equals(value.optString("mode", ""))
                || !(value.opt("action_execution_enabled") instanceof Boolean)
                || value.optBoolean("action_execution_enabled")) {
            throw new IllegalArgumentException("Cipher status contract is invalid or not read-only.");
        }
        String status = value.optString("status", "");
        if (!"ok".equals(status) && !"degraded".equals(status))
            throw new IllegalArgumentException("Cipher returned an unknown health state.");
        Object auditValue = value.opt("audit_chain_valid");
        if (!(auditValue instanceof Boolean))
            throw new IllegalArgumentException("Cipher audit-chain state is invalid.");
        Object countValue = value.opt("audit_event_count");
        if (!(countValue instanceof Number) || countValue instanceof Boolean)
            throw new IllegalArgumentException("Cipher audit event count is invalid.");
        double count = ((Number) countValue).doubleValue();
        if (count < 0 || count > Integer.MAX_VALUE || count != Math.floor(count))
            throw new IllegalArgumentException("Cipher audit event count is outside the supported range.");
        return new CipherConsoleStatus(status, (Boolean) auditValue, (int) count);
    }

    boolean isHealthy() { return "ok".equals(status) && auditChainValid; }
}
