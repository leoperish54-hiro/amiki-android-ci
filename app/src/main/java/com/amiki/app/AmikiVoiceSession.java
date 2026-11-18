package com.amiki.app;

/**
 * Native equivalent of Jarvis's wake / command / guard / deaf voice modes.
 *
 * A monotonically increasing generation identifies one conversational turn.
 * Async work must retain its generation and check isCurrent() before publishing
 * results. Barge-in and STOP invalidate every callback from the previous turn.
 */
public final class AmikiVoiceSession {
    public enum Mode { WAKE, COMMAND, GUARD, DEAF }

    public static final class Snapshot {
        public final Mode mode;
        public final long generation;
        private Snapshot(Mode mode, long generation) {
            this.mode = mode;
            this.generation = generation;
        }
    }

    private Mode mode = Mode.DEAF;
    private long generation;

    public synchronized long armWake() {
        generation++;
        mode = Mode.WAKE;
        return generation;
    }

    public synchronized long wakeDetected() {
        if (mode != Mode.WAKE) return -1L;
        generation++;
        mode = Mode.COMMAND;
        return generation;
    }

    public synchronized long beginCommand() {
        generation++;
        mode = Mode.COMMAND;
        return generation;
    }

    public synchronized boolean beginResponse(long turnGeneration) {
        if (!isCurrent(turnGeneration) || mode != Mode.COMMAND) return false;
        mode = Mode.GUARD;
        return true;
    }

    /** A barge-in immediately invalidates the response that was interrupted. */
    public synchronized long speechStarted() {
        if (mode == Mode.GUARD) {
            generation++;
            mode = Mode.COMMAND;
            return generation;
        }
        return mode == Mode.COMMAND ? generation : -1L;
    }

    public synchronized boolean finishResponse(long turnGeneration) {
        if (!isCurrent(turnGeneration)) return false;
        mode = Mode.WAKE;
        return true;
    }

    public synchronized long stop() {
        generation++;
        mode = Mode.DEAF;
        return generation;
    }

    public synchronized boolean isCurrent(long turnGeneration) {
        return turnGeneration > 0L && turnGeneration == generation && mode != Mode.DEAF;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mode, generation);
    }
}
