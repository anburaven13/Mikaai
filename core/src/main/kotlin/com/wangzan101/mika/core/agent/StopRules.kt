package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.tool.Outcome
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stop rules: when and how tasks terminate (give up, guard intervention, model error, no answer, step limit).
 */
internal object StopRules {
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("Reached step limit of $maxSteps steps, task incomplete.", FailKind.StepLimit)

    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("Task progress completed, but model did not produce an answer.", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("No new data read after ${NewDataTracker.LIMIT} attempts, task stopped.", FailKind.Guard)
    }

    fun noAnswer() = TaskState.Failed("Model stopped without providing an answer.", FailKind.Model)

    fun toolCallAsText() = TaskState.Failed("Model's tool call was not recognized by the service and returned as raw text, unable to execute.", FailKind.Model)

    fun modelFailed(e: Throwable): TaskState.Failed = when (e) {
        is ModelError.TaskBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.DailyBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.ProactiveBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.RateLimited -> TaskState.Failed(userReason(e), FailKind.Model, FailureCode.ModelRateLimited)
        is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
        else -> TaskState.Failed("Model call exception: ${e.message ?: e::class.simpleName}", FailKind.Model)
    }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> "Repeated the same action multiple times with no effect"
            "no_progress" -> if (treeBlind) "Cannot detect controls on this screen; gestures have no effect. This app may not support Mika's automation"
                else "Screen remained unchanged after multiple attempts"
            "read_loop" -> "Page did not change, repeated reading could not find content"
            "too_many_failures" -> "Consecutive operation failures"
            "sandbox_loop" -> "Same command failed repeatedly without changes"
            else -> "Safety guard triggered"
        }
        return TaskState.Failed("Safely stopped (${v.code}): $why.", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "Model service refused request: Invalid API Key or unauthorized. Please check your API Key in Settings › Model."
    is ModelError.IllegalHeader -> "API Key contains invalid characters. Please paste only the key itself in Settings › Model."
    is ModelError.QuotaExceeded -> "Model account quota exceeded. Please check your account balance or plan."
    is ModelError.TaskBudgetExceeded -> "Task token budget limit reached (${e.limit} tokens). Task incomplete."
    is ModelError.DailyBudgetExceeded ->
        "Daily token budget limit (${e.limit} tokens) reached for provider '${e.providerName}'. Recovers automatically tomorrow."
    is ModelError.ProactiveBudgetExceeded -> "Proactive features reached daily budget limit. Recovers tomorrow."
    is ModelError.ModelNotFound -> {
        val check = if (e.model.isEmpty()) "Please check the model name and Base URL in Settings › Model."
            else "Check the model name in Settings › Model (enter the full model ID, e.g. gemini-flash-latest) or switch to a preset."
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "" else "When calling model '${e.model}', ") +
                    "server responded: ${quoted(e.detail)}. The model name may be incorrect or the service is temporarily unavailable. $check"
            e.model.isEmpty() -> "Model not found. $check"
            else -> "Model '${e.model}' does not exist at this provider. $check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "Account is not authorized or activated for model '${e.model.ifEmpty { "current model" }}'. Switch models in Settings › Model or activate it in your provider dashboard."
    is ModelError.ToolsUnsupported ->
        "Model '${e.model.ifEmpty { "current model" }}' does not support tool calling. Mika requires tool support. Switch to a model with tool calling in Settings › Model."
    is ModelError.ContextTooLong -> "Conversation exceeded model context length. Switch to a larger context model or break task into smaller steps."
    is ModelError.ContentRefused -> "Model refused to process this content. Adjust prompt or switch models."
    is ModelError.ReasoningEffortRejected ->
        "Model does not accept the selected reasoning effort. Server responded: ${quoted(e.detail)}. Adjust reasoning effort to default in Settings › Model."
    is ModelError.RateLimited -> "Model service rate limited (HTTP 429). Please try again later or check your account rate limits."
    is ModelError.Network -> "Connection to model service failed after retries. Check network connection and Base URL."
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "Cannot connect to network proxy. Check proxy status and address/port in Settings › Network Proxy."
        ProxyFailure.AuthFailed -> "Network proxy authentication failed (HTTP 407). Check credentials in Settings › Network Proxy."
    }
    is ModelError.Server -> "Model service error (HTTP ${e.status}), please try again later."
    is ModelError.StreamCut ->
        "Connection was closed before model finished streaming. Often caused by unstable network or gateway timeout. Please try again."
    is ModelError.BadResponse -> "${e.message}. Please check Base URL and API compatibility."
    is ModelError.ChatGptLimitReached ->
        "ChatGPT quota limit reached. Please try again later or switch models."
    is ModelError.ChatGptNotEligible ->
        "This account cannot use ChatGPT quota in third-party apps. ${if (e.detail.isEmpty()) "" else "Server info: ${quoted(e.detail)}. "}You can switch models."
    is ModelError.SignInExpired -> "${e.account} sign-in expired. Please sign in again in Settings › Model."
    is ModelError.AccountPlanNotAllowed ->
        "Your ${e.account} subscription plan cannot use this endpoint (HTTP 403). ${if (e.detail.isEmpty()) "" else "Server info: ${quoted(e.detail)}. "}Use an API key or switch models."
    is ModelError.ChainFailed -> {
        val trail = e.entries.joinToString("; ") { (name, err) ->
            "'${name}'${chainErrorTag(err)}: ${err.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}"
        }
        userReason(e.primary) + " All failover models attempted and failed: $trail."
    }
}

private fun chainErrorTag(e: ModelError): String = when (e) {
    is ModelError.Server -> " HTTP ${e.status}"
    is ModelError.RateLimited -> " HTTP 429"
    is ModelError.Auth -> " " + RetryPolicy.label(e)
    else -> " ${RetryPolicy.label(e)}"
}

private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
