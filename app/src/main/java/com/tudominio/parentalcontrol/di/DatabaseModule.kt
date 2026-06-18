package com.tudominio.parentalcontrol.di

import android.content.Context
import androidx.room.Room
import com.tudominio.parentalcontrol.data.db.AppPolicyDao
import com.tudominio.parentalcontrol.data.db.BehavioralEventDao
import com.tudominio.parentalcontrol.data.db.GrantDao
import com.tudominio.parentalcontrol.data.db.OutboxDao
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.db.PolicyDao
import com.tudominio.parentalcontrol.data.db.TimeRequestDao
import com.tudominio.parentalcontrol.data.db.UsageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the Room database and its DAOs.
 *
 * Split from the former [AppModule] to keep DB concerns in one place and
 * leave infra-level singletons (auth, sync, time, etc.) to
 * [RepositoryModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ParentalDatabase =
        Room.databaseBuilder(
            context,
            ParentalDatabase::class.java,
            ParentalDatabase.DATABASE_NAME
        ).build()

    @Provides
    fun providePolicyDao(db: ParentalDatabase): PolicyDao = db.policyDao()

    @Provides
    fun provideAppPolicyDao(db: ParentalDatabase): AppPolicyDao = db.appPolicyDao()

    @Provides
    fun provideGrantDao(db: ParentalDatabase): GrantDao = db.grantDao()

    @Provides
    fun provideUsageDao(db: ParentalDatabase): UsageDao = db.usageDao()

    @Provides
    fun provideOutboxDao(db: ParentalDatabase): OutboxDao = db.outboxDao()

    @Provides
    fun provideTimeRequestDao(db: ParentalDatabase): TimeRequestDao = db.timeRequestDao()

    @Provides
    fun provideBehavioralEventDao(db: ParentalDatabase): BehavioralEventDao = db.behavioralEventDao()
}
