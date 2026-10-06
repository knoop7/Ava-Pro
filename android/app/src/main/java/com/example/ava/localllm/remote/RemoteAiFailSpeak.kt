package com.example.ava.localllm.remote

import android.content.Context
import com.example.ava.R

/**
 * Spoken line when the remote model seat fails. Words live in
 * [R.string.remote_ai_vision_refused] (picture attached, gateway refused),
 * [R.array.remote_ai_refused_lines] (4xx),
 * [R.array.remote_ai_unavailable_lines] (429 / 5xx),
 * [R.array.remote_ai_fail_lines] (network / timeout),
 * [R.array.remote_ai_stopped_lines] (stopped after real work, checkpoint kept).
 * The HTTP status is spoken last when the host has one.
 * Do not teach the user to say continue.
 */
object RemoteAiFailSpeak {
    fun pick(context: Context, resumable: Boolean = false, error: Throwable? = null): String {
        if (error is RemoteAiVisionRejected) {
            return RemoteAiClient.withStatus(context.getString(R.string.remote_ai_vision_refused), error)
        }
        val array = if (resumable) {
            R.array.remote_ai_stopped_lines
        } else {
            failArray(error)
        }
        val line = context.resources.getStringArray(array).randomOrNull().orEmpty()
        return RemoteAiClient.withStatus(line, error)
    }

    private fun failArray(error: Throwable?): Int {
        val code = error?.let { RemoteAiClient.httpStatus(it) } ?: return R.array.remote_ai_fail_lines
        return when {
            code == 408 -> R.array.remote_ai_fail_lines
            code == 429 || code >= 500 -> R.array.remote_ai_unavailable_lines
            code in 400..499 -> R.array.remote_ai_refused_lines
            else -> R.array.remote_ai_fail_lines
        }
    }
}
