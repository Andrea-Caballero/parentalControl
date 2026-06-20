package com.tudominio.parentalcontrol.di

import android.content.Context
import com.tudominio.parentalcontrol.BuildConfig
import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine
import com.tudominio.parentalcontrol.security.network.NetworkSecurityConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Hilt module that provides the `@SupabaseClient` `HttpClient` binding.
 *
 * Per design §D1/D3/D7 of `openspec/changes/hotfix-parent-auth-session/design.md`:
 * the binding reads `BuildConfig.USE_MOCK_SUPABASE` (propagated from
 * `local.properties` via the `buildConfigField` in `app/build.gradle.kts`)
 * and returns either a Ktor [MockEngine]-backed client (reading fixture
 * JSON from `app/src/main/assets/mock-supabase/`) or the real OkHttp
 * engine (currently unreachable against the placeholder Supabase config,
 * same as today).
 *
 * The mock/real decision is internal to this module; consumers inject
 * `@SupabaseClient httpClient: HttpClient` and never need to branch on
 * the flag themselves. Unit tests mock the `HttpClient` via MockK, so
 * `BuildConfig.USE_MOCK_SUPABASE` is never read in the test classpath.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @SupabaseClient
    fun provideHttpClient(@ApplicationContext context: Context): HttpClient {
        return if (BuildConfig.USE_MOCK_SUPABASE) {
            MockSupabaseEngine(context).httpClient
        } else {
            buildRealHttpClient(context)
        }
    }

    private fun buildRealHttpClient(context: Context): HttpClient {
        val okHttpClient = NetworkSecurityConfig.createSecureOkHttpClient(context)
        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
            }
        }
    }
}
