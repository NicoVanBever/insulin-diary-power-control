package com.insulindiary.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmPlanTest {
    private val now = 1_800_000_000_000L
    private val H = 3_600_000L
    private fun occ(key: String, at: Long) = Alarms.Occ(key, at, at + 3 * H, "dose", "Time for Tresiba", "18 units", "i1", 18.0)

    @Test fun futureAlarmsRingAtTheirTime() {
        val p = Alarms.plan(listOf(occ("a", now + H), occ("b", now + 2 * H)), emptySet(), emptyMap(), emptySet(), now)
        assertEquals(listOf("a" to now + H, "b" to now + 2 * H), p)
    }

    @Test fun pastAlarmsAreNotReplayed() {
        assertEquals(emptyList<Pair<String, Long>>(), Alarms.plan(listOf(occ("a", now - H)), emptySet(), emptyMap(), emptySet(), now))
    }

    @Test fun justDueRingsNow() {
        assertEquals(listOf("a" to now + 1000L), Alarms.plan(listOf(occ("a", now - 30_000L)), emptySet(), emptyMap(), emptySet(), now))
    }

    @Test fun firedAlarmOnlyRingsAgainWhenSnoozed() {
        val o = listOf(occ("a", now - 5 * 60_000L))
        assertEquals(emptyList<Pair<String, Long>>(), Alarms.plan(o, setOf("a"), emptyMap(), emptySet(), now))
        assertEquals(listOf("a" to now + 10 * 60_000L), Alarms.plan(o, setOf("a"), mapOf("a" to now + 10 * 60_000L), emptySet(), now))
    }

    @Test fun skippedNeverRings() {
        assertEquals(emptyList<Pair<String, Long>>(), Alarms.plan(listOf(occ("a", now + H)), emptySet(), mapOf("a" to now + H), setOf("a"), now))
    }

    @Test fun snoozeBeyondWindowIsDropped() {
        val o = listOf(occ("a", now - 2 * H)) // window ends at now + 1 h
        assertEquals(emptyList<Pair<String, Long>>(), Alarms.plan(o, setOf("a"), mapOf("a" to now + 2 * H), emptySet(), now))
    }
}
