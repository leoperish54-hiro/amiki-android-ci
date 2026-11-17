package com.amiki.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Read-only Cipher console. Requests go through Amiki; no Cipher token is stored here. */
public final class CipherConsoleActivity extends Activity {
    private static final int BG=0xFF0E1224, PANEL=0xFF171C33, CARD=0xFF1D2340;
    private static final int PURPLE=0xFF8B7CFF, CYAN=0xFF4DE1C1, TEXT=0xFFE8EAF6, MUTED=0xFFADB4D0;
    private static final int MAX_RESPONSE_CHARS=1024*1024;
    private LinearLayout content;
    private TextView connectionState, auditState, reportState;
    private EditText rootIdInput;
    private Button inspectButton;
    private volatile boolean requestInFlight;
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}

    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);buildScreen();refreshStatus();}

    private void buildScreen(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(10),dp(6),dp(14),dp(6));header.setBackgroundColor(PANEL);
        Button back=button("‹ BACK",CARD);back.setOnClickListener(v->finish());header.addView(back,lp(dp(88),dp(46)));
        TextView title=text("CIPHER CONSOLE",16,TEXT);title.setTypeface(null,Typeface.BOLD);title.setGravity(Gravity.CENTER);header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(header,lp(-1,dp(60)));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(15),dp(16),dp(28));
        content.addView(text("INDEPENDENT DEFENSIVE AUTHORITY",10,CYAN),lp(-1,-2));
        TextView intro=text("Cipher protects registered workspaces. Cipher X analyses evidence for the owner; it cannot approve or execute its own proposals.",13,MUTED);intro.setPadding(0,dp(7),0,dp(13));content.addView(intro,lp(-1,-2));
        LinearLayout health=card();health.addView(text("CIPHER SERVICE",10,PURPLE),lp(-1,-2));connectionState=text("Checking the Amiki → Cipher health bridge…",14,MUTED);connectionState.setPadding(0,dp(7),0,dp(4));health.addView(connectionState,lp(-1,-2));auditState=text("Audit-chain state: not yet verified",12,MUTED);health.addView(auditState,lp(-1,-2));Button refresh=button("↻  REFRESH HEALTH",PURPLE);refresh.setOnClickListener(v->refreshStatus());LinearLayout.LayoutParams rp=lp(-1,dp(44));rp.topMargin=dp(10);health.addView(refresh,rp);addCard(content,health);
        addSection("WATCHTOWER");addPolicy("Monitor · Analyse · Investigate","Read-only monitoring and evidence review. The console can request bounded inspection of an owner-registered root.","AVAILABLE AFTER BRIDGE CONFIGURATION");
        addSection("REGISTERED ROOT INSPECTION");LinearLayout inspection=card();inspection.addView(text("ROOT ID",10,PURPLE),lp(-1,-2));rootIdInput=new EditText(this);rootIdInput.setSingleLine(true);rootIdInput.setText("amiki_core");rootIdInput.setHint("Owner-allowlisted root ID (not a path)");rootIdInput.setTextColor(TEXT);rootIdInput.setHintTextColor(MUTED);rootIdInput.setBackground(roundRect(0xFF242B4A,10));rootIdInput.setPadding(dp(11),dp(7),dp(11),dp(7));LinearLayout.LayoutParams ip=lp(-1,dp(48));ip.topMargin=dp(7);inspection.addView(rootIdInput,ip);
        TextView note=text("Only a root ID is sent. Amiki checks its explicit allowlist; filesystem paths and Cipher credentials never leave the backend.",11,MUTED);note.setPadding(0,dp(7),0,dp(7));inspection.addView(note,lp(-1,-2));inspectButton=button("INSPECT REGISTERED ROOT",PURPLE);inspectButton.setOnClickListener(v->inspectRoot());inspection.addView(inspectButton,lp(-1,dp(46)));reportState=text("No inspection requested in this session.",12,MUTED);reportState.setPadding(0,dp(10),0,0);inspection.addView(reportState,lp(-1,-2));addCard(content,inspection);
        addSection("DEFENSIVE POLICIES");addPolicy("ACTIVE DEFENSE","Authorized containment, controlled deception, evidence/TTP reporting and recovery.","NOT EXPOSED — EXECUTION ADAPTERS NOT VALIDATED");addPolicy("EMERGENCY ISOLATION","Isolate affected systems from the ecosystem, then permit only owner-authorized internal defensive response.","NOT EXPOSED — ISOLATION / RECOVERY NOT VALIDATED");addPolicy("CIPHER X","Advanced evidence analysis and investigative hypotheses. No self-approval, privilege expansion, external penetration or retaliation.","SIMULATION / INVESTIGATION GATE PENDING");
        TextView boundary=text("SECURITY BOUNDARY  •  This console is read-only. It cannot authorize operations, change incidents, isolate devices, quarantine files, revoke credentials or execute response actions. A green health response is not a security certification.",11,MUTED);boundary.setPadding(dp(2),dp(12),dp(2),0);content.addView(boundary,lp(-1,-2));scroll.addView(content,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }

    private void refreshStatus(){connectionState.setText("Checking the Amiki → Cipher health bridge…");auditState.setText("Audit-chain state: pending");new Thread(()->{CipherConsoleStatus parsed=null;String failure=null;try{parsed=CipherConsoleStatus.fromJson(new JSONObject(request("GET",ApiEndpoints.CIPHER_STATUS,null)));}catch(Exception e){failure=safeMessage(e);}final CipherConsoleStatus result=parsed;final String message=failure;runOnUiThread(()->{if(isFinishing())return;if(result==null){connectionState.setText("NOT CONNECTED  •  "+message);connectionState.setTextColor(Color.rgb(255,170,100));auditState.setText("Audit-chain state: unavailable; no health evidence received.");auditState.setTextColor(MUTED);return;}connectionState.setText((result.isHealthy()?"CONNECTED  •  HEALTHY":"CONNECTED  •  DEGRADED")+"\nMode: loopback read-only prototype");connectionState.setTextColor(result.isHealthy()?CYAN:Color.rgb(255,170,100));auditState.setText("Audit chain: "+(result.auditChainValid?"valid":"INVALID")+"  •  Events: "+result.auditEventCount);auditState.setTextColor(result.auditChainValid?CYAN:Color.rgb(255,100,100));});},"cipher-status").start();}

    private void inspectRoot(){if(requestInFlight)return;String rootId=rootIdInput.getText().toString().trim();if(!rootId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")){rootIdInput.setError("Enter a registered root ID, not a filesystem path.");return;}requestInFlight=true;inspectButton.setEnabled(false);reportState.setText("Requesting bounded read-only inspection…");new Thread(()->{String rendered;try{JSONObject body=new JSONObject();body.put("root_id",rootId);rendered=renderInspection(new JSONObject(request("POST",ApiEndpoints.CIPHER_INSPECT,body)),rootId);}catch(Exception e){rendered="Inspection unavailable: "+safeMessage(e)+"\nNo inspection result is being assumed.";}final String result=rendered;runOnUiThread(()->{requestInFlight=false;inspectButton.setEnabled(true);if(!isFinishing())reportState.setText(result);});},"cipher-inspection").start();}

    private String renderInspection(JSONObject value,String expectedRoot)throws Exception{return CipherInspectionReport.render(value,expectedRoot);}

    private String request(String method,String endpoint,JSONObject body)throws Exception{HttpURLConnection c=null;try{c=ApiConnection.open(this, endpoint);c.setRequestMethod(method);c.setConnectTimeout(2500);c.setReadTimeout("GET".equals(method)?4000:18000);c.setRequestProperty("Accept","application/json");if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}}int code=c.getResponseCode();InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();String response=readBounded(stream);if(code<200||code>=300)throw new Exception("Amiki API returned HTTP "+code+". "+safeApiDetail(response));return response;}finally{if(c!=null)c.disconnect();}}
    private String readBounded(InputStream in)throws Exception{if(in==null)return "";StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){b.append(line);if(b.length()>MAX_RESPONSE_CHARS)throw new Exception("Response exceeded the 1 MiB display limit.");}}return b.toString();}
    private String safeApiDetail(String response){try{String detail=new JSONObject(response).optString("detail","");if(detail.matches("[a-zA-Z0-9_.-]{1,80}"))return detail;}catch(Exception ignored){}return "The backend did not return a safe error code.";}
    private String safeMessage(Exception e){String m=e.getMessage();if(m==null||m.trim().isEmpty())return "The request failed.";return m.length()>180?m.substring(0,180):m;}
    private void addSection(String label){TextView t=text(label,10,PURPLE);t.setTypeface(null,Typeface.BOLD);t.setPadding(dp(2),dp(12),0,dp(7));content.addView(t,lp(-1,-2));}
    private void addPolicy(String title,String detail,String state){LinearLayout c=card();TextView h=text(title,13,TEXT);h.setTypeface(null,Typeface.BOLD);c.addView(h,lp(-1,-2));TextView d=text(detail,11,MUTED);d.setPadding(0,dp(5),0,dp(6));c.addView(d,lp(-1,-2));c.addView(text(state,9,CYAN),lp(-1,-2));addCard(content,c);}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(13),dp(12),dp(13),dp(12));c.setBackground(roundRect(CARD,14));return c;}
    private void addCard(LinearLayout p,View v){LinearLayout.LayoutParams x=lp(-1,-2);x.setMargins(0,0,0,dp(8));p.addView(v,x);}
    private TextView text(String s,float size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(11);b.setAllCaps(false);b.setBackground(roundRect(color,10));return b;}
    private GradientDrawable roundRect(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
}
