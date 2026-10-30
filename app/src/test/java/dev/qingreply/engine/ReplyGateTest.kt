package dev.qingreply.engine

import org.junit.Assert.*
import org.junit.Test

class ReplyGateTest {
    private val start = 1_000_000L

    @Test fun duplicateCannotPassAfterCooldownOrProcessRestart() {
        val gate = ReplyGate()
        assertEquals(GateResult.ALLOWED, gate.reserve("same", "group", start, 20, 30))
        val restored = ReplyGate(gate.snapshot())
        assertEquals(GateResult.DUPLICATE, restored.reserve("same", "group", start + 60_000L, 20, 30))
    }

    @Test fun cooldownIsPerConversationAndSkippedMessageIsNotReplayed() {
        val gate = ReplyGate()
        assertEquals(GateResult.ALLOWED, gate.reserve("a", "group-a", start, 20, 30))
        assertEquals(GateResult.COOLDOWN, gate.reserve("b", "group-a", start + 1000L, 20, 30))
        assertEquals(GateResult.ALLOWED, gate.reserve("c", "group-b", start + 1000L, 20, 30))
        assertEquals(GateResult.DUPLICATE, gate.reserve("b", "group-a", start + 30_000L, 20, 30))
        assertEquals(GateResult.ALLOWED, gate.reserve("d", "group-a", start + 30_000L, 20, 30))
    }

    @Test fun hourlyLimitIsGlobalRollingAndPersistsAcrossRestart() {
        val gate = ReplyGate()
        assertEquals(GateResult.ALLOWED, gate.reserve("a", "one", start, 10, 1))
        val restored = ReplyGate(gate.snapshot())
        assertEquals(GateResult.HOURLY_LIMIT, restored.reserve("b", "two", start + 60_000L, 10, 1))
        assertEquals(GateResult.ALLOWED, restored.reserve("c", "two", start + 3_600_000L, 10, 1))
    }

    @Test fun fingerprintRetentionIsBounded() {
        val gate = ReplyGate()
        repeat(1100) { gate.reserve(it.toString(), "group", start + it, 10, 120) }
        assertTrue(gate.snapshot().seen.size <= 1000)
    }
}
