package com.example.idm.core.di

import com.example.downloadexecutor.DownloadManager
import com.example.downloadexecutor.createManager
import com.example.idm.core.data.repository.FileRepository
import com.example.idm.core.data.repository.FileRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindsFileRepository(fileRepository: FileRepositoryImpl): FileRepository
}

@Module
@InstallIn(SingletonComponent::class)
object ProvideManagerModule {
    @Provides
    @Singleton
    fun providesDownloadManager(): DownloadManager {
        return createManager();
    }
}