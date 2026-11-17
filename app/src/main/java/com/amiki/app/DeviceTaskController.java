package com.amiki.app;

/**
 * Deterministic lifecycle state machine for multi-step device operations.
 * It validates task transitions; it does not execute actions or grant access.
 */
public final class DeviceTaskController {
    public enum State {
        QUEUED, OBSERVING, AWAITING_OWNER_APPROVAL, EXECUTING, VERIFYING,
        COMPLETED, FAILED, CANCELLED, NEEDS_OWNER_INPUT
    }

    public static final class Snapshot {
        public final String taskId;
        public final State state;
        public final int completedSteps;
        public final String detail;
        public final boolean stopRequested;

        private Snapshot(String taskId, State state, int completedSteps,
                         String detail, boolean stopRequested) {
            this.taskId = taskId;
            this.state = state;
            this.completedSteps = completedSteps;
            this.detail = detail;
            this.stopRequested = stopRequested;
        }
    }

    private final String taskId;
    private State state = State.QUEUED;
    private int completedSteps;
    private String detail = "Task queued.";
    private boolean stopRequested;

    public DeviceTaskController(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("A non-empty task ID is required.");
        }
        this.taskId = taskId.trim();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(taskId, state, completedSteps, detail, stopRequested);
    }

    public synchronized boolean mayStartNextStep() {
        return !stopRequested && state == State.EXECUTING;
    }

    public synchronized Snapshot beginObservation() {
        requireState(State.QUEUED, State.OBSERVING, State.NEEDS_OWNER_INPUT);
        transition(State.OBSERVING, "Observing current device state.");
        return snapshot();
    }

    public synchronized Snapshot requestOwnerApproval(String summary) {
        requireState(State.OBSERVING);
        transition(State.AWAITING_OWNER_APPROVAL, safeDetail(summary, "Owner approval required."));
        return snapshot();
    }

    public synchronized Snapshot ownerApproved() {
        requireState(State.AWAITING_OWNER_APPROVAL);
        if (stopRequested) return cancelNow();
        transition(State.EXECUTING, "Owner approved this action.");
        return snapshot();
    }

    public synchronized Snapshot ownerRejected() {
        requireState(State.AWAITING_OWNER_APPROVAL);
        transition(State.CANCELLED, "Owner declined the action.");
        stopRequested = true;
        return snapshot();
    }

    public synchronized Snapshot beginExecution() {
        requireState(State.OBSERVING);
        if (stopRequested) return cancelNow();
        transition(State.EXECUTING, "Executing an authorized step.");
        return snapshot();
    }

    public synchronized Snapshot stepCompleted(String receipt) {
        requireState(State.EXECUTING);
        if (stopRequested) return cancelNow();
        completedSteps++;
        transition(State.OBSERVING, safeDetail(receipt, "Step completed; re-observation required."));
        return snapshot();
    }

    public synchronized Snapshot beginVerification() {
        requireState(State.EXECUTING, State.OBSERVING);
        if (stopRequested) return cancelNow();
        transition(State.VERIFYING, "Verifying the requested outcome.");
        return snapshot();
    }

    public synchronized Snapshot complete(String verifiedOutcome) {
        requireState(State.VERIFYING);
        if (stopRequested) return cancelNow();
        transition(State.COMPLETED, safeDetail(verifiedOutcome, "Outcome verified."));
        return snapshot();
    }

    public synchronized Snapshot needsOwnerInput(String question) {
        requireState(State.OBSERVING, State.EXECUTING, State.VERIFYING);
        transition(State.NEEDS_OWNER_INPUT, safeDetail(question, "Owner input is required."));
        return snapshot();
    }

    public synchronized Snapshot fail(String reason) {
        requireNonTerminal();
        transition(State.FAILED, safeDetail(reason, "Task failed."));
        stopRequested = true;
        return snapshot();
    }

    /** Prevents future steps; cannot roll back an external side effect already accepted. */
    public synchronized Snapshot stop() {
        if (!isTerminal(state)) {
            stopRequested = true;
            transition(State.CANCELLED,
                    "Stopped. An external side effect already accepted may not be reversible.");
        }
        return snapshot();
    }

    private Snapshot cancelNow() {
        stopRequested = true;
        transition(State.CANCELLED, "Stopped before another action could begin.");
        return snapshot();
    }

    private void requireState(State... allowed) {
        for (State candidate : allowed) if (state == candidate) return;
        throw new IllegalStateException("Invalid task transition from " + state + ".");
    }

    private void requireNonTerminal() {
        if (isTerminal(state)) throw new IllegalStateException("A terminal task cannot transition again.");
    }

    private static boolean isTerminal(State value) {
        return value == State.COMPLETED || value == State.FAILED || value == State.CANCELLED;
    }

    private void transition(State next, String nextDetail) {
        state = next;
        detail = nextDetail;
    }

    private static String safeDetail(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
