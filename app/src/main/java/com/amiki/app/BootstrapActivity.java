package com.amiki.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

/** Branded launch surface shown while the local companion services are started. */
public class BootstrapActivity extends Activity {
    private static final long MINIMUM_BRANDED_DISPLAY_MS = 850L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean opened;

    private final Runnable openMain = () -> {
        if (opened || isFinishing()) return;
        opened = true;
        startActivity(new Intent(this, MobileShellActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(4, 7, 25));
        window.setNavigationBarColor(Color.rgb(4, 7, 25));
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            window.getDecorView().setSystemUiVisibility(0);
        }

        setContentView(new AmikiSplashView(this));

        if (!TermuxBackendLauncher.start(this)) {
            new SentinelJournal(this).append(
                    "RECOVERY_FAILED", "WARNING", "bootstrap", "failed",
                    "Termux backend command could not be launched. Verify Termux installation, Run Command permission, and repository path.",
                    null
            );
        }

        SentinelService.start(this);
        handler.postDelayed(openMain, MINIMUM_BRANDED_DISPLAY_MS);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(openMain);
        super.onDestroy();
    }
}
