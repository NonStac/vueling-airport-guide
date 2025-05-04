package com.nonstac.airportguide.data.model

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isSentByUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)