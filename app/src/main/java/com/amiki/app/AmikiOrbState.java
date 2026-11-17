package com.amiki.app;

/** Pure runtime-state priority used by the adaptive orb and covered by local unit tests. */
final class AmikiOrbState {
    private AmikiOrbState() {}

    static String resolve(boolean listening, boolean thinking, boolean speaking,
                          boolean healthKnown, boolean backendOnline) {
        if (listening) return AmikiOrbView.STATE_LISTENING;
        if (thinking) return AmikiOrbView.STATE_THINKING;
        if (speaking) return AmikiOrbView.STATE_SPEAKING;
        if (healthKnown && !backendOnline) return AmikiOrbView.STATE_OFFLINE;
        return AmikiOrbView.STATE_IDLE;
    }
}
