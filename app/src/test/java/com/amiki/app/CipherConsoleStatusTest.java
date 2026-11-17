package com.amiki.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CipherConsoleStatusTest {
    private JSONObject healthy() throws Exception { return new JSONObject().put("service","cipher").put("connected",true).put("status","ok").put("mode","loopback_read_only_prototype").put("action_execution_enabled",false).put("audit_chain_valid",true).put("audit_event_count",12); }
    @Test public void parsesHealthyReadOnlyStatus() throws Exception { CipherConsoleStatus s=CipherConsoleStatus.fromJson(healthy());assertTrue(s.isHealthy());assertTrue(s.auditChainValid);assertEquals(12,s.auditEventCount); }
    @Test public void degradedOrInvalidAuditChainIsNotHealthy() throws Exception { CipherConsoleStatus s=CipherConsoleStatus.fromJson(healthy().put("status","degraded").put("audit_chain_valid",false));assertFalse(s.isHealthy());assertFalse(s.auditChainValid); }
    @Test public void rejectsDisconnectedOrExecutionEnabledStatus() throws Exception { try{CipherConsoleStatus.fromJson(healthy().put("connected",false));fail("disconnected status accepted");}catch(IllegalArgumentException expected){} try{CipherConsoleStatus.fromJson(healthy().put("action_execution_enabled",true));fail("execution-enabled status accepted");}catch(IllegalArgumentException expected){} }
    @Test public void rejectsInvalidAuditMetadata() throws Exception { try{CipherConsoleStatus.fromJson(healthy().put("audit_event_count",-1));fail("negative count accepted");}catch(IllegalArgumentException expected){} try{CipherConsoleStatus.fromJson(healthy().put("audit_chain_valid","true"));fail("string boolean accepted");}catch(IllegalArgumentException expected){} }
}
