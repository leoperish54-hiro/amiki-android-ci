package com.amiki.app;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Amiki-owned RecognitionService bridge required by Android 11 and earlier
 * VoiceInteractionService metadata. It delegates each session to an installed
 * non-Amiki RecognitionService; it does not implement or store speech models.
 *
 * This lets Amiki own the assistant/recognition boundary while keeping the
 * actual external speech provider replaceable. A future bundled recognizer can
 * replace this delegate without changing VoiceInteractionService selection.
 */
public final class AmikiRecognitionService extends RecognitionService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session activeSession;

    private static final class Session {
        final Callback callback;
        final SpeechRecognizer recognizer;

        Session(Callback callback, SpeechRecognizer recognizer) {
            this.callback = callback;
            this.recognizer = recognizer;
        }
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        main.post(() -> startDelegate(recognizerIntent, callback));
    }

    private void startDelegate(Intent recognizerIntent, Callback callback) {
        releaseActiveSession(true);

        ComponentName provider = resolveExternalProvider();
        if (provider == null) {
            notifyError(callback, SpeechRecognizer.ERROR_CLIENT);
            return;
        }

        SpeechRecognizer delegate = null;
        try {
            delegate = SpeechRecognizer.createSpeechRecognizer(this, provider);
            final SpeechRecognizer selected = delegate;
            Session session = new Session(callback, selected);
            activeSession = session;

            selected.setRecognitionListener(new android.speech.RecognitionListener() {
                private boolean isActive() {
                    return activeSession == session;
                }

                @Override public void onReadyForSpeech(Bundle params) {
                    if (!isActive()) return;
                    try { callback.readyForSpeech(params == null ? new Bundle() : params); }
                    catch (RemoteException ignored) { releaseSession(session, true); }
                }

                @Override public void onBeginningOfSpeech() {
                    if (!isActive()) return;
                    try { callback.beginningOfSpeech(); }
                    catch (RemoteException ignored) { releaseSession(session, true); }
                }

                @Override public void onRmsChanged(float rmsdB) {
                    if (!isActive()) return;
                    try { callback.rmsChanged(rmsdB); }
                    catch (RemoteException ignored) { releaseSession(session, true); }
                }

                @Override public void onBufferReceived(byte[] buffer) {
                    // Intentionally do not forward raw microphone buffers to the
                    // outer RecognitionService client; transcript callbacks are
                    // sufficient for Amiki's current command pipeline.
                }

                @Override public void onEndOfSpeech() {
                    if (!isActive()) return;
                    try { callback.endOfSpeech(); }
                    catch (RemoteException ignored) { releaseSession(session, true); }
                }

                @Override public void onError(int error) {
                    if (!isActive()) return;
                    try { callback.error(error); }
                    catch (RemoteException ignored) { }
                    finally { releaseSession(session, false); }
                }

                @Override public void onResults(Bundle results) {
                    if (!isActive()) return;
                    try { callback.results(results == null ? new Bundle() : results); }
                    catch (RemoteException ignored) { }
                    finally { releaseSession(session, false); }
                }

                @Override public void onPartialResults(Bundle partialResults) {
                    if (!isActive()) return;
                    try { callback.partialResults(partialResults == null ? new Bundle() : partialResults); }
                    catch (RemoteException ignored) { releaseSession(session, true); }
                }

                @Override public void onEvent(int eventType, Bundle params) {
                    // RecognitionService.Callback has no general event channel.
                }
            });

            Intent request = recognizerIntent == null
                    ? new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    : new Intent(recognizerIntent);
            selected.startListening(request);
        } catch (RuntimeException failure) {
            if (delegate != null) {
                try { delegate.destroy(); } catch (RuntimeException ignored) { }
            }
            if (activeSession != null && activeSession.callback == callback) {
                activeSession = null;
            }
            notifyError(callback, SpeechRecognizer.ERROR_CLIENT);
        }
    }

    @Override
    protected void onStopListening(Callback callback) {
        main.post(() -> {
            Session session = activeSession;
            if (session == null || (callback != null && session.callback != callback)) return;
            try {
                session.recognizer.stopListening();
            } catch (RuntimeException failure) {
                releaseSession(session, true);
            }
        });
    }

    @Override
    protected void onCancel(Callback callback) {
        main.post(() -> {
            Session session = activeSession;
            if (session == null || (callback != null && session.callback != callback)) return;
            releaseSession(session, true);
        });
    }

    @Override
    public void onDestroy() {
        releaseActiveSession(true);
        super.onDestroy();
    }

    private ComponentName resolveExternalProvider() {
        Intent query = new Intent(RecognitionService.SERVICE_INTERFACE);
        PackageManager manager = getPackageManager();
        List<ResolveInfo> candidates;
        try {
            candidates = manager.queryIntentServices(query, 0);
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (candidates == null || candidates.isEmpty()) return null;

        List<ResolveInfo> ordered = new ArrayList<>(candidates);
        Collections.sort(ordered, Comparator
                .comparing((ResolveInfo info) -> !isSystemService(info))
                .thenComparing((ResolveInfo info) -> -info.priority)
                .thenComparing(info -> info.serviceInfo == null ? "" : info.serviceInfo.packageName));

        for (ResolveInfo info : ordered) {
            if (info == null || info.serviceInfo == null) continue;
            android.content.pm.ServiceInfo service = info.serviceInfo;
            if (service.packageName == null || service.name == null
                    || getPackageName().equals(service.packageName)
                    || !service.enabled || !service.exported
                    || !"android.permission.BIND_SPEECH_RECOGNITION".equals(service.permission)) {
                continue;
            }
            return new ComponentName(service.packageName, service.name);
        }
        return null;
    }

    private static boolean isSystemService(ResolveInfo info) {
        return info != null && info.serviceInfo != null
                && info.serviceInfo.applicationInfo != null
                && (info.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private void releaseActiveSession(boolean cancel) {
        Session session = activeSession;
        activeSession = null;
        if (session != null) releaseSession(session, cancel);
    }

    private void releaseSession(Session session, boolean cancel) {
        if (session == null) return;
        if (activeSession == session) activeSession = null;
        try {
            if (cancel) session.recognizer.cancel();
        } catch (RuntimeException ignored) { }
        try { session.recognizer.destroy(); } catch (RuntimeException ignored) { }
    }

    private static void notifyError(Callback callback, int error) {
        if (callback == null) return;
        try { callback.error(error); } catch (RemoteException ignored) { }
    }
}
