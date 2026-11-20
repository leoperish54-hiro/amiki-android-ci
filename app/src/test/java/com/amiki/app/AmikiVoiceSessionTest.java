package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class AmikiVoiceSessionTest {
    @Test
    public void wakeAndCommandModesAdvanceGeneration() {
        AmikiVoiceSession session = new AmikiVoiceSession();
        assertEquals(AmikiVoiceSession.Mode.DEAF, session.snapshot().mode);

        long wakeGeneration = session.armWake();
        assertEquals(AmikiVoiceSession.Mode.WAKE, session.snapshot().mode);

        long commandGeneration = session.wakeDetected();
        assertTrue(commandGeneration > wakeGeneration);
        assertEquals(AmikiVoiceSession.Mode.COMMAND, session.snapshot().mode);
    }

    @Test
    public void staleResponseCannotChangeNewerTurn() {
        AmikiVoiceSession session = new AmikiVoiceSession();
        long first = session.beginCommand();
        assertTrue(session.beginResponse(first));

        long second = session.speechStarted();
        assertTrue(second > first);
        assertEquals(AmikiVoiceSession.Mode.COMMAND, session.snapshot().mode);
        assertFalse(session.finishResponse(first));
        assertTrue(session.isCurrent(second));
    }

    @Test
    public void bargeInOnlyAdvancesGenerationDuringGuardMode() {
        AmikiVoiceSession session = new AmikiVoiceSession();
        long command = session.beginCommand();
        assertEquals(command, session.speechStarted());

        assertTrue(session.beginResponse(command));
        long interrupted = session.speechStarted();
        assertTrue(interrupted > command);
        assertEquals(AmikiVoiceSession.Mode.COMMAND, session.snapshot().mode);
    }

    @Test
    public void stopInvalidatesOutstandingCallbacks() {
        AmikiVoiceSession session = new AmikiVoiceSession();
        long command = session.beginCommand();
        long stopped = session.stop();

        assertTrue(stopped > command);
        assertFalse(session.isCurrent(command));
        assertEquals(AmikiVoiceSession.Mode.DEAF, session.snapshot().mode);
    }
}
