package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_texts")
data class SharedText(
    @PrimaryKey
    val id: String,
    val content: String,
    val addedTime: Long = System.currentTimeMillis(),
    val senderDevice: String = "Web"
)
