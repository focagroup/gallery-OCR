package io.github.minuus69.galleryocr.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.MediaStoreSource

/**
 * Hands images or text to other apps via Android's share sheet. This app itself never
 * transmits anything; what the receiving app does is up to that app.
 */
object Intents {
    fun shareImages(context: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val uris = ids.map { MediaStoreSource.imageUri(it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = "image/*"
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, context.getString(R.string.share)))
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(intent, context.getString(R.string.share_text)))
    }

    fun openImage(context: Context, id: Long, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(MediaStoreSource.imageUri(id), mimeType.ifEmpty { "image/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, context.getString(R.string.open_with)))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_found, Toast.LENGTH_SHORT).show()
        }
    }
}
