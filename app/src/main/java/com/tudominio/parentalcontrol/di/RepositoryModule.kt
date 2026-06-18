package com.tudominio.parentalcontrol.di

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for infra-level singletons that are NOT yet Hilt-managed
 * (time, auth, Supabase client). Singletons that have been migrated to
 * `@Singleton @Inject constructor` — [com.tudominio.parentalcontrol.sync.SyncManager],
 * [com.tudominio.parentalcontrol.outbox.OutboxManager],
 * [com.tudominio.parentalcontrol.health.HealthMonitor],
 * [com.tudominio.parentalcontrol.analytics.AnalyticsManager],
 * [com.tudominio.parentalcontrol.reward.RewardManager],
 * [com.tudominio.parentalcontrol.data.repository.TimeExtraRepository] —
 * are bound automatically by Hilt via their `@Inject` constructors and
 * need no provider here.
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
    fun provideDeviceAuthManager(@ApplicationContext context: Context): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSupabaseClientProvider(@ApplicationContext context: Context): SupabaseClientProvider =
        SupabaseClientProvider.getInstance(context)
}
