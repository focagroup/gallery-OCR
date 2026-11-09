package io.github.minuus69.galleryocr

import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.minuus69.galleryocr.data.MediaStoreSource
import io.github.minuus69.galleryocr.ui.AppRoot
import io.github.minuus69.galleryocr.ui.GalleryOcrTheme
import io.github.minuus69.galleryocr.ui.MainViewModel
import io.github.minuus69.galleryocr.util.Permissions

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Picks up new screenshots while the app is open. */
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            viewModel.refresh(delayMs = 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GalleryOcrTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Scan for new or deleted images every time the app comes to the foreground.
        viewModel.onAccessChecked(Permissions.mediaAccess(this))
        contentResolver.registerContentObserver(MediaStoreSource.collection, true, mediaObserver)
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(mediaObserver)
        super.onStop()
    }
}
