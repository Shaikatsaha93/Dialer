package com.example.data.model

data class PhoneContact(
    val id: String,
    val name: String,
    val primaryNumber: String,
    val allNumbers: List<String> = listOf(primaryNumber),
    val photoUri: String? = null,
    val lookupKey: String? = null
)
