package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelErrorReasonTest {
    @Test fun rateLimitHasStableFailureCode() {
        val failure = StopRules.modelFailed(ModelError.RateLimited(null))
        assertEquals(FailKind.Model, failure.kind)
        assertEquals(FailureCode.ModelRateLimited, failure.code)
        assertEquals(null, StopRules.modelFailed(ModelError.Network()).code)
    }

    @Test fun modelNotFoundNamesTheModel() {
        assertEquals(
            "Model 'gemini' does not exist at this provider. Check the model name in Settings › Model (enter the full model ID, e.g. gemini-flash-latest) or switch to a preset.",
            StopRules.modelFailed(ModelError.ModelNotFound("gemini")).reason,
        )
        assertEquals("Model not found. Please check the model name and Base URL in Settings › Model.", StopRules.modelFailed(ModelError.ModelNotFound()).reason)
        assertEquals("Model not found: gemini", ModelError.ModelNotFound("gemini").message)
    }

    @Test fun modelNotFoundCarriesWhatTheServerSaid() {
        assertEquals(
            "When calling model 'group/rpa', server responded: none of group/rpa's models is ready. The model name may be incorrect or the service is temporarily unavailable. " +
                "Check the model name in Settings › Model (enter the full model ID, e.g. gemini-flash-latest) or switch to a preset.",
            StopRules.modelFailed(ModelError.ModelNotFound("group/rpa", "none of group/rpa's models is ready")).reason,
        )
        assertEquals(
            "server responded: The model `x` does not exist. The model name may be incorrect or the service is temporarily unavailable. Please check the model name and Base URL in Settings › Model.",
            StopRules.modelFailed(ModelError.ModelNotFound("", "The model `x` does not exist.")).reason,
        )
        val said = StopRules.modelFailed(ModelError.ModelNotFound("m", "gone")).reason
        assertFalse(said.contains("does not exist"))
    }

    @Test fun quotedServerTextDropsOneTrailingPeriod() {
        fun reasons(detail: String) = listOf(
            StopRules.modelFailed(ModelError.ModelNotFound("m", detail)).reason,
            StopRules.modelFailed(ModelError.ChatGptNotEligible(detail)).reason,
            StopRules.modelFailed(ModelError.AccountPlanNotAllowed("ChatGPT", detail)).reason,
        )
        reasons("Not allowed.").forEach { assertTrue(it, it.contains("Not allowed.") && !it.contains("Not allowed..")) }
        reasons("Disallowed.").forEach { assertTrue(it, it.contains("Disallowed.") && !it.contains("Disallowed..")) }
        reasons("Not allowed").forEach { assertTrue(it, it.contains("Not allowed.")) }
        reasons("Too long…").forEach { assertTrue(it, it.contains("Too long….")) }
        reasons("Wait...").forEach { assertTrue(it, it.contains("Wait....")) }
    }

    @Test fun modelNotAvailableNamesTheModel() {
        assertEquals(
            "Account is not authorized or activated for model 'qwen-max'. Switch models in Settings › Model or activate it in your provider dashboard.",
            StopRules.modelFailed(ModelError.ModelNotAvailable("qwen-max", "not activated")).reason,
        )
        assertEquals(
            "Model 'gemma' does not support tool calling. Mika requires tool support. Switch to a model with tool calling in Settings › Model.",
            StopRules.modelFailed(ModelError.ToolsUnsupported("gemma")).reason,
        )
    }

    @Test fun rejectedReasoningEffortPointsToTheActualSetting() {
        val detail = "Invalid value: 'ultra'. Supported values are: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', and 'max'."
        val reason = StopRules.modelFailed(ModelError.ReasoningEffortRejected(detail)).reason
        assertTrue(reason.contains(detail.dropLast(1)))
        assertTrue(reason.contains("Adjust reasoning effort to default"))
        assertFalse(reason.contains("Base URL"))
    }

    @Test fun chainFailedLeadsWithPrimaryAndListsTrail() {
        val primary = ModelError.RateLimited(null)
        val e = ModelError.ChainFailed(primary, listOf("inferx-main" to primary, "gemini-backup" to ModelError.Server(400)))
        val reason = StopRules.modelFailed(e).reason
        assertTrue(reason.startsWith(userReason(primary)))
        assertTrue(reason.contains("'inferx-main' HTTP 429"))
        assertTrue(reason.contains("'gemini-backup' HTTP 400"))
    }

    @Test fun streamCutIsNotBlamedOnConfiguration() {
        val cut = StopRules.modelFailed(ModelError.StreamCut()).reason
        assertTrue(cut.contains("Connection was closed before model finished streaming"))
        assertFalse(cut.contains("Base URL"))
        assertTrue(StopRules.modelFailed(ModelError.BadResponse("Stream response not valid JSON")).reason.contains("Base URL"))
    }
}
