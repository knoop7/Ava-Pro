package com.example.ava.localllm

import org.json.JSONObject

/** One tool the remote model asked the host to run. */
data class LocalToolCall(val name: String, val arguments: JSONObject)
