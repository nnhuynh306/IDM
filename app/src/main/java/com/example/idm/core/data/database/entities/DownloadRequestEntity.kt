package com.example.idm.core.data.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_request")
data class DownloadRequestEntity(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "destination") val destination: String,
    @ColumnInfo(name = "totalSize") val totalSize: Long,
)