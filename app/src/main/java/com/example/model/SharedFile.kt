package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_files")
data class SharedFile(
    @PrimaryKey
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val addedTime: Long = System.currentTimeMillis(),
    val localPath: String,
    val isUploadedFromWeb: Boolean = false
)
