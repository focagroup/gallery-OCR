package io.github.minuus69.galleryocr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.BoxCodec
import io.github.minuus69.galleryocr.data.ImageRow
import io.github.minuus69.galleryocr.data.MediaStoreSource
import io.github.minuus69.galleryocr.data.OcrStatus
import io.github.minuus69.galleryocr.data.SearchText
import io.github.minuus69.galleryocr.image.ImageDecoding
import io.github.minuus69.galleryocr.ocr.OcrBox
import io.github.minuus69.galleryocr.util.Deleter
import io.github.minuus69.galleryocr.util.Intents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class LoadedImage(val width: Int, val height: Int, val strips: List<Pair<Int, ImageBitmap>>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: MainViewModel, startId: Long, deleter: Deleter) {
    val context = LocalContext.current
    val items = vm.viewerItems
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val startIndex = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    val current = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex)) ?: return
    val highlight by vm.settings.highlight.collectAsStateWithLifecycle()
    val terms by vm.highlightTerms.collectAsState()
    val collections by vm.collections.collectAsStateWithLifecycle()
    var zoomed by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var pickCollection by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }

    if (pickCollection) {
        val membershipFlow = remember(current.id) { vm.membership(current.id) }
        val membership by membershipFlow.collectAsState(initial = emptyList())
        CollectionPickerDialog(
            count = 1,
            collections = collections,
            membership = membership.toSet(),
            onToggle = { c, add ->
                if (add) vm.addToCollection(c.id, listOf(current.id)) else vm.removeFromCollection(c.id, listOf(current.id))
            },
            onCreate = { vm.createCollection(it, listOf(current.id)) },
            onDismiss = { pickCollection = false },
        )
    }
    if (showText) {
        TextSheet(vm, current, onDismiss = { showText = false })
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val row = items[page]
            ZoomableImage(
                row = row,
                terms = if (highlight) terms else emptyList(),
                loadBoxes = { vm.ocrText(it)?.boxes },
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                onTap = { chromeVisible = !chromeVisible },
            )
        }
        if (chromeVisible) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.dateTaken)),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            current.bucketName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ViewerAction(Icons.AutoMirrored.Outlined.Notes, stringResource(R.string.text)) { showText = true }
                ViewerAction(Icons.Outlined.Share, stringResource(R.string.share)) {
                    Intents.shareImages(context, listOf(current.id))
                }
                ViewerAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open)) {
                    Intents.openImage(context, current.id, current.mimeType)
                }
                ViewerAction(Icons.AutoMirrored.Outlined.PlaylistAdd, stringResource(R.string.collection)) { pickCollection = true }
                ViewerAction(Icons.Outlined.Delete, stringResource(R.string.delete)) { deleter.delete(listOf(current.id)) }
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomableImage(
    row: ImageRow,
    terms: List<String>,
    loadBoxes: suspend (Long) -> String?,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val image by produceState<LoadedImage?>(null, row.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val decoded = ImageDecoding.decodeForViewer(context.contentResolver, MediaStoreSource.imageUri(row.id), row.orientation)
                LoadedImage(decoded.width, decoded.height, decoded.strips.map { it.top to it.bitmap.asImageBitmap() })
            }.getOrNull()
        }
    }
    // Highlight rectangles in image-relative coordinates (0..1).
    val highlights by produceState(emptyList<OcrBox>(), row.id, terms) {
        value = if (terms.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                BoxCodec.decode(loadBoxes(row.id) ?: "[]").flatMap { box ->
                    val w = box.right - box.left
                    SearchText.matchSpans(box, terms).map { span ->
                        box.copy(left = box.left + w * span.start, right = box.left + w * span.endInclusive)
                    }
                }
            }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val highlightFill = HighlightColor.copy(alpha = 0.30f)

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val img = image
        if (img == null) {
            CircularProgressIndicator(color = Color.White)
            return@BoxWithConstraints
        }
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val fit = min(cw / img.width, ch / img.height)
        val dw = img.width * fit
        val dh = img.height * fit
        val center = Offset(cw / 2, ch / 2)

        fun clamp(o: Offset, s: Float): Offset {
            val mx = max(0f, (dw * s - cw) / 2)
            val my = max(0f, (dh * s - ch) / 2)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(img) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                // Zoom so that the image fills the width (long screenshots) or 2.5x.
                                val target = max(2.5f, cw / dw).coerceAtMost(8f)
                                offset = clamp((tap - center) * (1 - target), target)
                                scale = target
                            }
                            onZoomChanged(scale > 1.01f)
                        },
                    )
                }
                .pointerInput(img) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.count { it.pressed }
                            // Only fingers that were down before and still are count towards the centroid,
                            // so it is Unspecified (NaN) on the final finger-up; using it would make the
                            // offset NaN and the image disappear.
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if ((pointers > 1 || scale > 1.01f) && centroid.isSpecified) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val newScale = (scale * zoom).coerceIn(1f, 8f)
                                // Keep the content point under the fingers in place.
                                val newOffset = centroid + pan - center - (centroid - center - offset) * (newScale / scale)
                                scale = newScale
                                offset = if (newScale <= 1.01f) Offset.Zero else clamp(newOffset, newScale)
                                onZoomChanged(scale > 1.01f)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            val left = (size.width - dw) / 2
            val top = (size.height - dh) / 2
            for ((stripTop, bitmap) in img.strips) {
                val y0 = (top + stripTop * fit).roundToInt()
                val y1 = (top + (stripTop + bitmap.height) * fit).roundToInt()
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(left.roundToInt(), y0),
                    dstSize = IntSize(dw.roundToInt(), max(1, y1 - y0)),
                    filterQuality = FilterQuality.Medium,
                )
            }
            for (b in highlights) {
                val topLeft = Offset(left + b.left * dw, top + b.top * dh)
                val boxSize = Size((b.right - b.left) * dw, (b.bottom - b.top) * dh)
                drawRect(highlightFill, topLeft, boxSize)
                drawRect(HighlightColor, topLeft, boxSize, style = Stroke(width = 2.dp.toPx() / scale))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(vm: MainViewModel, row: ImageRow, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState()
    val entity by produceState<Pair<Boolean, String?>>(false to null, row.id) {
        value = true to withContext(Dispatchers.IO) { vm.ocrText(row.id)?.text }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            val (loaded, text) = entity
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recognized_text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!text.isNullOrBlank()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_all))
                    }
                    IconButton(onClick = { Intents.shareText(context, text) }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share_text))
                    }
                }
            }
            when {
                !loaded -> CircularProgressIndicator(Modifier.padding(16.dp))
                text.isNullOrBlank() -> Text(
                    stringResource(if (row.status == OcrStatus.PENDING) R.string.not_scanned_yet else R.string.no_text_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> SelectionContainer(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!text.isNullOrBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.copy_all))
                }
            }
        }
    }
}
