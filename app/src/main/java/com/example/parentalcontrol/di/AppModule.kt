package com.example.parentalcontrol.di

import android.content.Context
import androidx.room.Room
import com.example.parentalcontrol.auth.DeviceAuthManager
import com.example.parentalcontrol.data.local.AppDatabase
import com.example.parentalcontrol.health.HealthMonitor
import com.example.parentalcontrol.network.SupabaseClientProvider
import com.example.parentalcontrol.sync.SyncManager
import com.example.parentalcontrol.time.DefaultTimeProvider
import com.example.parentalcontrol.time.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build()

    @Provides
    @Singleton
    fun provideTimeProvider(@ApplicationContext context: Context): TimeProvider =
        DefaultTimeProvider(context)

    @Provides
    @Singleton
    fun provideSyncManager(@ApplicationContext context: Context): SyncManager =
        SyncManager.getInstance(context)

    @Provides
    @Singleton
    fun provideHealthMonitor(@ApplicationContext context: Context): HealthMonitor =
        HealthMonitor.getInstance(context)

    @Provides
    @Singleton
    fun provideDeviceAuthManager(@ApplicationContext context: Context): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSupabaseClientProvider(@ApplicationContext context: Context): SupabaseClientProvider =
        SupabaseClientProvider.getInstance(context)
}
