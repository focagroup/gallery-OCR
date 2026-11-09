package io.github.minuus69.galleryocr

import android.app.Application
import android.content.Context
import io.github.minuus69.galleryocr.data.AppDatabase
import io.github.minuus69.galleryocr.data.Settings
import io.github.minuus69.galleryocr.scan.Notifications

class GalleryOcrApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val settings: Settings by lazy { Settings(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

val Context.app: GalleryOcrApp get() = applicationContext as GalleryOcrApp
