package com.example.idm.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.idm.core.data.database.dao.DownloadRequestDao
import com.example.idm.core.data.database.entities.DownloadRequestEntity

@Database(entities = [DownloadRequestEntity::class], version = 1)
abstract class IDMDatabase: RoomDatabase() {
    abstract fun downloadRequestDao(): DownloadRequestDao
}