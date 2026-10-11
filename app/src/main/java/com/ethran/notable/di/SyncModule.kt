package com.ethran.notable.di

import com.ethran.notable.data.ContentMirror
import com.ethran.notable.sync.automerge.NotableSyncClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Editor writes go into the Automerge docs first (ADR-001); see [NotableSyncClient.write]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    abstract fun bindContentMirror(client: NotableSyncClient): ContentMirror
}
