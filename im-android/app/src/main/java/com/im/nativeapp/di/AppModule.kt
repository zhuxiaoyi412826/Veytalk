package com.im.nativeapp.di

import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.ApiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Json 实例由 ApiClient 持有（动态重建时 converter 也要用同一个），这里转发给需要独立解码的地方 */
    @Provides
    @Singleton
    fun provideJson(apiClient: ApiClient): Json = apiClient.json
}
