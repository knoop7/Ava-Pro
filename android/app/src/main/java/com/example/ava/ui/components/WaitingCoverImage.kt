package com.example.ava.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/** Waiting / no-art cover — `assets/waiting.webp`. */
private const val WAITING_ASSET = "waiting.webp"

fun decodeWaitingCoverBitmap(context: android.content.Context): Bitmap? =
    runCatching {
        context.assets.open(WAITING_ASSET).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

@Composable
fun WaitingCoverImage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember {
        decodeWaitingCoverBitmap(context)
    }
    val image = remember(bitmap) {
        bitmap?.takeUnless { it.isRecycled }?.let { runCatching { it.asImageBitmap() }.getOrNull() }
    }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            // Asset already fills the square — Crop without extra zoom.
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    }
}
