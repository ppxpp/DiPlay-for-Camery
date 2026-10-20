package com.shilapi.xcertplay.pioneer

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PioneerWiredSessionTest {
    @After fun tearDown() { PioneerWiredSession.current?.close() }

    @Test fun bothTransportsOwnTheSameAdaptationLifetime() {
        for (backend in PioneerWiredSession.Backend.entries) {
            val session = PioneerWiredSession.begin(backend, true)
            assertTrue(PioneerWiredSession.active)
            assertTrue(PioneerWiredSession.adaptationActive)
            assertTrue(PioneerWiredSession.isCurrent(session.id))
            assertFalse(session.firstFrame)
            assertFalse(session.controlAccepted)
            PioneerWiredSession.controlAccepted(session.id)
            assertTrue(session.controlAccepted)
            PioneerWiredSession.firstFrame(session.id)
            assertTrue(session.firstFrame)
            session.close()
            assertFalse(PioneerWiredSession.active)
            assertFalse(PioneerWiredSession.adaptationActive)
            assertFalse(PioneerWiredSession.isCurrent(session.id))
        }
    }

    @Test fun staleReleaseAndFrameCannotAffectTheNextBackend() {
        val old = PioneerWiredSession.begin(PioneerWiredSession.Backend.LWIP, true)
        old.close()
        val next = PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM, true)
        old.close()
        PioneerWiredSession.firstFrame(old.id)
        PioneerWiredSession.controlAccepted(old.id)
        assertSame(next, PioneerWiredSession.current)
        assertFalse(next.firstFrame)
        assertFalse(next.controlAccepted)
        assertFalse(PioneerWiredSession.isCurrent(old.id))
    }

    @Test fun overlappingTransportCannotReplaceAnActiveOwner() {
        val owner = PioneerWiredSession.begin(PioneerWiredSession.Backend.LWIP, true)
        try {
            PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM, true)
            fail("An active transport must be stopped before manual switching")
        } catch (_: IllegalStateException) {
            assertSame(owner, PioneerWiredSession.current)
        }
    }

    @Test fun unverifiedDeviceDoesNotAcquireVehiclePolicies() {
        val session = PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM, false)
        assertTrue(PioneerWiredSession.active)
        assertFalse(PioneerWiredSession.adaptationActive)
        assertFalse(PioneerWiredSession.isCurrent(session.id))
    }
}
