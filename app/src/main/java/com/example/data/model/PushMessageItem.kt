package com.example.data.model

data class PushMessageItem(
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val body: String,
    val dataPayload: Map<String, String> = emptyMap(),
    val receivedAtTimestamp: Long = System.currentTimeMillis(),
    val isVoipCallPush: Boolean = false,
    val callerUri: String? = null,
    val callerName: String? = null
)
