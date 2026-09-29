package com.example.data.model

import com.example.platform.currentTimeMillis

data class PushMessageItem(
    val id: String = currentTimeMillis().toString(),
    val title: String,
    val body: String,
    val dataPayload: Map<String, String> = emptyMap(),
    val receivedAtTimestamp: Long = currentTimeMillis(),
    val isVoipCallPush: Boolean = false,
    val callerUri: String? = null,
    val callerName: String? = null
)
