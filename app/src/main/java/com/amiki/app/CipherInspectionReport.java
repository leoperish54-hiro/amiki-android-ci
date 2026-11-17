package com.amiki.app;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validates bounded metadata-only inspection responses before display. */
final class CipherInspectionReport {
    private CipherInspectionReport() {}
    static String render(JSONObject value, String expectedRoot) {
        if (value == null || expectedRoot == null || !expectedRoot.equals(value.optString("root_id", ""))) throw new IllegalArgumentException("Inspection response root did not match the requested root.");
        String status=value.optString("status","");
        if (!"complete".equals(status) && !"partial".equals(status)) throw new IllegalArgumentException("Inspection status was invalid.");
        Object complete=value.opt("complete"), execution=value.opt("execution_performed"), detected=value.opt("detected_values_included");
        if (!(complete instanceof Boolean) || ((Boolean)complete) != "complete".equals(status) || !(execution instanceof Boolean) || (Boolean)execution || !(detected instanceof Boolean) || (Boolean)detected) throw new IllegalArgumentException("Inspection response violated the read-only data contract.");
        int files=bounded(value.opt("files_scanned"),0,1000,"files scanned");
        int bytes=bounded(value.opt("bytes_scanned"),0,10000000,"bytes scanned");
        JSONArray findings=value.optJSONArray("findings");
        if(findings==null||findings.length()>1000)throw new IllegalArgumentException("Inspection findings were missing or exceeded the response limit.");
        String limit=value.optString("limit_reason","partial report");
        if(limit.length()>100)throw new IllegalArgumentException("Inspection limit reason was too long.");
        StringBuilder out=new StringBuilder();out.append("Status: ").append(status.toUpperCase()).append("\nRoot: ").append(expectedRoot).append("\nFiles scanned: ").append(files).append("\nBytes scanned: ").append(bytes).append("\nFindings: ").append(findings.length()).append("\nExecution performed: NO");
        if("partial".equals(status))out.append("\nLimit: ").append(limit);
        int shown=Math.min(findings.length(),20);
        for(int i=0;i<shown;i++){
            JSONObject f=findings.optJSONObject(i);if(f==null)throw new IllegalArgumentException("Inspection finding entry was malformed.");
            String path=f.optString("relative_path","");
            if(path.isEmpty()||path.length()>1024||path.startsWith("/")||path.contains("\\")||path.matches("^[A-Za-z]:.*")||path.equals("..")||path.equals(".")||path.startsWith("../")||path.contains("/../"))throw new IllegalArgumentException("Inspection finding contained an unsafe path.");
            int line=bounded(f.opt("line"),1,Integer.MAX_VALUE,"finding line");String rule=f.optString("rule","");if(rule.isEmpty()||rule.length()>100)throw new IllegalArgumentException("Inspection finding rule was invalid.");
            String digest=f.optString("file_sha256","");if(!digest.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("Inspection finding digest was invalid.");
            out.append("\n\n• ").append(path).append(":").append(line).append("\n  Rule: ").append(rule).append("\n  SHA-256: ").append(digest.substring(0,12)).append("…");
        }
        if(findings.length()>shown)out.append("\n\nShowing ").append(shown).append(" of ").append(findings.length()).append(" findings.");return out.toString();
    }
    private static int bounded(Object value,int min,int max,String label){if(!(value instanceof Number)||value instanceof Boolean)throw new IllegalArgumentException("Inspection "+label+" value was invalid.");double n=((Number)value).doubleValue();if(n<min||n>max||n!=Math.floor(n))throw new IllegalArgumentException("Inspection "+label+" value was outside the supported range.");return(int)n;}
}
