package com.ethran.notable.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.ethran.notable.editor.utils.getThumbnailFile
import com.ethran.notable.io.ThumbnailGeneratorEntryPoint
import com.ethran.notable.io.thumbnailCacheKey
import dagger.hilt.EntryPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

/**
 * Renders a preview image for a page.
 *
 * Reloads when [ThumbnailUpdates] announces the page. The image is keyed on the thumbnail file's
 * modification time, so a thumbnail rewritten by the editor or dropped by sync is never shown
 * from an older cache entry.
 */
@Composable
fun PagePreview(
    modifier: Modifier = Modifier, pageId: String, onPreviewMissing: (String) -> Unit = {}
) {
    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current

    if (isPreview) {
        Box(modifier = modifier.background(Color.White))
        return
    }

    // Get the signal via EntryPoint since this is a stateless Composable
    val thumbnailUpdates = remember(context) {
        EntryPoints.get(
            context.applicationContext, ThumbnailGeneratorEntryPoint::class.java
        ).thumbnailUpdates()
    }

    val imgFile = remember(pageId) {
        getThumbnailFile(context, pageId)
    }

    // Bumped whenever this page's thumbnail file changes
    var refreshTrigger by remember(pageId) { mutableIntStateOf(0) }
    // Null until the file has been checked; then the key, or null while the file is missing
    var cacheKey by remember(pageId) { mutableStateOf<String?>(null) }

    LaunchedEffect(pageId) {
        thumbnailUpdates.updated.filter { it == pageId }.collect {
            refreshTrigger++
        }
    }

    LaunchedEffect(pageId, refreshTrigger) {
        val key = withContext(Dispatchers.IO) { thumbnailCacheKey(imgFile) }
        cacheKey = key
        if (key == null) {
            onPreviewMissing(pageId)
        }
    }

    val key = cacheKey
    if (key == null) {
        Box(modifier = modifier.background(Color.White))
        return
    }

    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context).data(imgFile)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .build()
    )

    Image(
        painter = painter,
        contentDescription = "Page Preview",
        contentScale = ContentScale.FillWidth,
        modifier = modifier.background(Color.White)
    )
}
