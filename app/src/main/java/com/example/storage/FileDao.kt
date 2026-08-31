package com.example.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.SharedFile
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    @Query("SELECT * FROM shared_files ORDER BY addedTime DESC")
    fun getAllFiles(): Flow<List<SharedFile>>

    @Query("SELECT * FROM shared_files ORDER BY addedTime DESC")
    suspend fun getAllFilesList(): List<SharedFile>

    @Query("SELECT * FROM shared_files WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: String): SharedFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: SharedFile)

    @Update
    suspend fun updateFile(file: SharedFile)

    @Query("DELETE FROM shared_files WHERE id = :id")
    suspend fun deleteFileById(id: String)

    @Query("DELETE FROM shared_files")
    suspend fun deleteAllFiles()

    @Query("SELECT COUNT(*) FROM shared_files")
    fun getFileCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(size), 0) FROM shared_files")
    fun getTotalSize(): Flow<Long>
}
