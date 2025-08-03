package com.example.idm.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.idm.core.data.database.IDMDatabase
import com.example.idm.core.data.database.dao.DownloadRequestDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): IDMDatabase {
        return Room.databaseBuilder(
            context = context,
            klass = IDMDatabase::class.java,
            "idm_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideDownloadRequestDao(
        database: IDMDatabase
    ): DownloadRequestDao {
        return database.downloadRequestDao()
    }
}