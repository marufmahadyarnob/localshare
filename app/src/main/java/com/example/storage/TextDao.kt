package com.example.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.SharedText
import kotlinx.coroutines.flow.Flow

@Dao
interface TextDao {
    @Query("SELECT * FROM shared_texts ORDER BY addedTime DESC")
    fun getAllTexts(): Flow<List<SharedText>>

    @Query("SELECT * FROM shared_texts ORDER BY addedTime DESC")
    suspend fun getAllTextsList(): List<SharedText>

    @Query("SELECT * FROM shared_texts WHERE id = :id LIMIT 1")
    suspend fun getTextById(id: String): SharedText?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertText(text: SharedText)

    @Query("DELETE FROM shared_texts WHERE id = :id")
    suspend fun deleteTextById(id: String)

    @Query("DELETE FROM shared_texts")
    suspend fun deleteAllTexts()

    @Query("SELECT COUNT(*) FROM shared_texts")
    fun getTextCount(): Flow<Int>
}
