package com.example.ava.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Composable
internal fun rememberMinimalLauncherWallpaperBitmap(uriString: String): State<Bitmap?> {
    val context = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val targetWidth = (configuration.screenWidthDp * density).toInt().coerceAtLeast(1)
    val targetHeight = (configuration.screenHeightDp * density).toInt().coerceAtLeast(1)
    return produceState<Bitmap?>(
        initialValue = null,
        key1 = uriString,
        key2 = targetWidth,
        key3 = targetHeight,
    ) {
        value = if (uriString.isBlank()) null else withContext(Dispatchers.IO) {
            decodeWallpaper(context, Uri.parse(uriString), targetWidth, targetHeight)
        }
    }
}

@Composable
internal fun MinimalLauncherWallpaper(
    uriString: String,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberMinimalLauncherWallpaperBitmap(uriString).value ?: return
    MinimalLauncherWallpaperImage(
        bitmap = bitmap,
        scale = scale,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = modifier,
    )
}

@Composable
internal fun MinimalLauncherWallpaperImage(
    bitmap: Bitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX * widthPx
                    translationY = offsetY * heightPx
                },
        )
    }
}

private fun decodeWallpaper(
    context: Context,
    uri: Uri,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width.coerceAtLeast(1)
            val height = info.size.height.coerceAtLeast(1)
            val scale = max(
                targetWidth.toFloat() / width,
                targetHeight.toFloat() / height,
            ).coerceAtMost(1f)
            val maxPixels = targetWidth.toLong() * targetHeight.toLong() * 2L
            val memoryScale = sqrt(
                maxPixels.toDouble() / (width.toLong() * height.toLong()).coerceAtLeast(1L)
            ).toFloat().coerceAtMost(1f)
            val safeScale = min(scale, memoryScale)
            decoder.setTargetSize(
                (width * safeScale).toInt().coerceAtLeast(1),
                (height * safeScale).toInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            val maxPixels = targetWidth.toLong() * targetHeight.toLong() * 2L
            while (
                (
                    bounds.outWidth / (sample * 2) >= targetWidth &&
                        bounds.outHeight / (sample * 2) >= targetHeight
                    ) ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() /
                    ((sample * 2L) * (sample * 2L)) > maxPixels
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
