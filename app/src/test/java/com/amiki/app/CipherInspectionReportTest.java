package com.amiki.app;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class CipherInspectionReportTest {
 private JSONObject valid()throws Exception{JSONObject f=new JSONObject().put("relative_path","src/main.py").put("line",12).put("rule","credential_pattern").put("file_sha256","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");return new JSONObject().put("root_id","amiki_core").put("status","complete").put("complete",true).put("files_scanned",5).put("bytes_scanned",1024).put("findings",new JSONArray().put(f)).put("detected_values_included",false).put("execution_performed",false);}
 @Test public void rendersValidatedMetadata()throws Exception{String r=CipherInspectionReport.render(valid(),"amiki_core");assertTrue(r.contains("Status: COMPLETE"));assertTrue(r.contains("src/main.py:12"));assertTrue(r.contains("Execution performed: NO"));}
 @Test public void retainsPartialStatus()throws Exception{String r=CipherInspectionReport.render(valid().put("status","partial").put("complete",false).put("limit_reason","max_files reached"),"amiki_core");assertTrue(r.contains("Status: PARTIAL"));assertTrue(r.contains("max_files reached"));}
 @Test public void rejectsWrongRootAndExecutionClaims()throws Exception{reject(valid(),"other");reject(valid().put("execution_performed",true),"amiki_core");reject(valid().put("detected_values_included",true),"amiki_core");}
 @Test public void rejectsTraversalAndBadDigest()throws Exception{JSONObject f=new JSONObject().put("relative_path","../secret").put("line",1).put("rule","x").put("file_sha256","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");reject(valid().put("findings",new JSONArray().put(f)),"amiki_core");f.put("relative_path","safe.txt").put("file_sha256","bad");reject(valid().put("findings",new JSONArray().put(f)),"amiki_core");}
 @Test public void rejectsOversizeAndInconsistentStatus()throws Exception{reject(valid().put("bytes_scanned",10000001),"amiki_core");reject(valid().put("files_scanned",1001),"amiki_core");reject(valid().put("status","partial"),"amiki_core");}
 private void reject(JSONObject value,String root){try{CipherInspectionReport.render(value,root);fail("invalid response accepted");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
}
