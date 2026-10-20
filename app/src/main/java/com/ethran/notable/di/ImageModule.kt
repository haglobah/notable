package com.ethran.notable.di

import com.ethran.notable.image.EditorImage
import com.ethran.notable.image.ImageService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The editor's view of the live code image (ADR-002). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ImageModule {
    @Binds
    abstract fun bindEditorImage(service: ImageService): EditorImage
}
