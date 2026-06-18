package com.example.parentalcontrol.di

import android.content.Context
import com.example.parentalcontrol.auth.DeviceAuthManager
import com.example.parentalcontrol.health.HealthMonitor
import com.example.parentalcontrol.network.SupabaseClientProvider
import com.example.parentalcontrol.outbox.OutboxManager
import com.example.parentalcontrol.sync.SyncManager
import com.example.parentalcontrol.time.DefaultTimeProvider
import com.example.parentalcontrol.time.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for infra-level singletons: time, sync, outbox, health,
 * auth, and the Supabase client provider.
 *
 * Split from the former [AppModule] so DB wiring lives in [DatabaseModule]
 * and the rest of the application infra lives here.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

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
    fun provideOutboxManager(@ApplicationContext context: Context): OutboxManager =
        OutboxManager.getInstance(context)

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
