package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.Severity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class StopRulesTest {
    @Test fun treeBlindNoProgressSaysAppUnresponsive() {
        val v = GuardVerdict("no_progress", Severity.Stop, buildJsonObject { put("treeBlind", true) })
        assertTrue(StopRules.guard(v).reason.contains("automation"))
    }

    @Test fun plainNoProgressKeepsGenericMessage() {
        val v = GuardVerdict("no_progress", Severity.Stop, buildJsonObject { put("unchanged", 3) })
        assertTrue(StopRules.guard(v).reason.contains("Screen remained unchanged"))
    }
}
