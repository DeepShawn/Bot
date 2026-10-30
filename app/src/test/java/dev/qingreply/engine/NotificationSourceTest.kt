package dev.qingreply.engine

import dev.qingreply.data.BotSettings
import org.junit.Assert.*
import org.junit.Test

class NotificationSourceTest {
    private val main = NotificationSource("com.tencent.mobileqq", 0, 10234)
    private val clone = NotificationSource("com.tencent.mobileqq", 999, 99910234)

    @Test fun onlyTheBoundInstanceMatchesEvenWithTheSamePackageName() {
        assertTrue(clone.matches(clone.id))
        assertFalse(main.matches(clone.id))
        assertFalse(clone.matches(""))
        assertFalse(clone.matches("com.tencent.mobileqq|999|99910235"))
    }

    @Test fun identicalShortcutsInTwoUsersDoNotShareDedupOrCooldown() {
        val gate = ReplyGate()
        assertNotEquals(main.scoped("same-shortcut"), clone.scoped("same-shortcut"))
        assertEquals(GateResult.ALLOWED, gate.reserve(main.scoped("same-message"), main.scoped("chat"), 100000L, 20, 30))
        assertEquals(GateResult.ALLOWED, gate.reserve(clone.scoped("same-message"), clone.scoped("chat"), 100001L, 20, 30))
    }

    @Test fun malformedBindingsAreRejected() {
        listOf("", "com.tencent.mobileqq", "com.tencent.mobileqq|-1|10", "com.tencent.mobileqq|999|bad", "not a package|0|1").forEach {
            assertNull(NotificationSource.parse(it))
        }
        assertEquals(clone, NotificationSource.parse(clone.id))
    }

    @Test fun setupCannotEnableBotBeforeBindingTheAccountInstance() {
        val settings = BotSettings(apiKey = "test-key", aliases = "小号")
        assertNotNull(settings.startError())
        assertNull(settings.copy(boundSource = clone.id).startError())
    }
}
