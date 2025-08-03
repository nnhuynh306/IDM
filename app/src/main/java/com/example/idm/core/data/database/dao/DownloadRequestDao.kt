package com.example.idm.core.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.example.idm.core.data.database.entities.DownloadRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadRequestDao {
    @Query("SELECT * FROM download_request")
    fun getAll(): Flow<List<DownloadRequestEntity>>
    @Insert
    suspend fun insert(entity: DownloadRequestEntity)
    @Query("DELETE FROM download_request WHERE destination = :destination")
    suspend fun delete(destination: String)
}