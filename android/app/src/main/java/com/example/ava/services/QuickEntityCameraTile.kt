package com.example.ava.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import com.example.ava.homeassistant.HaManager
import com.example.ava.homeassistant.HaMediaAuth
import com.example.ava.settings.QuickEntitySlot
import com.example.ava.ui.components.MdiColorMapper
import com.example.ava.utils.HaCameraJpeg
import com.example.ava.utils.HaMediaUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class QuickEntityCameraRenderer(
    context: Context,
    private val surfaceHost: () -> ViewGroup?,
    private val invalidate: (Rect?) -> Unit
) {
    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollUrls = mutableMapOf<String, String>()
    private val pollCandidates = mutableMapOf<String, List<String>>()
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val pollSessions = mutableMapOf<String, SnapshotPollSession>()
    private val dirtyRects = mutableMapOf<String, RectF>()
    private val invalidateRects = mutableMapOf<String, Rect>()
    private val contentSizes = mutableMapOf<String, Pair<Int, Int>>()
    private val pendingFrames = ConcurrentHashMap<String, Bitmap>()
    private val flushPosted = AtomicBoolean(false)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blitPaint = Paint()
    private val clipPath = Path()
    private val bitmapSrcRect = Rect()
    private val tileDrawCaches = mutableMapOf<String, TileDrawCache>()
    private val lastPresentMs = mutableMapOf<String, Long>()
    private val lastJpegBounds = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val lastDecodeOut = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val decodePools = ConcurrentHashMap<String, CameraFramePool>()
    @Volatile private var adjustFadeEntityId: String? = null
    @Volatile private var adjustFadeAlpha: Float = 0f
    /** Streams only while the overlay is actually shown. Hide keeps URLs, drops sockets. */
    @Volatile private var streamingEnabled = false
    private val stillWritten = ConcurrentHashMap.newKeySet<String>()
    private val stillLoadFailed = ConcurrentHashMap.newKeySet<String>()
    private val stillDir by lazy { File(appContext.cacheDir, STILL_DIR_NAME) }

    private val missingBitmapDrawCounts = mutableMapOf<String, Int>()
    private val pendingRedrawEntities = mutableSetOf<String>()
    private val loggedMissingLayout = mutableSetOf<String>()
    private val recycleAfterDraw = ArrayDeque<Pair<Int, ArrayList<Bitmap>>>()
    private var drawPass = 0
    private val decodeTemp = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(16 * 1024)
    }

    fun syncSlots(slots: List<QuickEntitySlot>) {
        val cameraIds = slots.filter {
            it.entityId.isNotEmpty() && (it.entityType == "camera" || it.entityId.startsWith("camera."))
        }.map { it.entityId }.toSet()

        pollUrls.keys.retainAll(cameraIds)
        pollCandidates.keys.retainAll(cameraIds)
        dirtyRects.keys.retainAll(cameraIds)
        invalidateRects.keys.retainAll(cameraIds)
        contentSizes.keys.retainAll(cameraIds)
        pendingRedrawEntities.retainAll(cameraIds)
        tileDrawCaches.keys.retainAll(cameraIds)
        lastPresentMs.keys.retainAll(cameraIds)
        missingBitmapDrawCounts.keys.retainAll(cameraIds)
        lastJpegBounds.keys.retainAll(cameraIds)
        lastDecodeOut.keys.retainAll(cameraIds)
        stillWritten.retainAll(cameraIds)
        stillLoadFailed.retainAll(cameraIds)
        decodePools.keys.toList().forEach { entityId ->
            if (entityId !in cameraIds) {
                decodePools.remove(entityId)?.release()
            }
        }
        pollSessions.keys.toList().forEach { entityId ->
            if (entityId !in cameraIds) {
                pollSessions.remove(entityId)?.stop()
                pendingFrames.remove(entityId)?.takeIf { !it.isRecycled }?.recycle()
            }
        }
        bitmaps.entries.removeAll { (id, bitmap) ->
            if (id !in cameraIds) {
                if (!bitmap.isRecycled) bitmap.recycle()
                deleteStillFile(id)
                true
            } else {
                false
            }
        }

        cameraIds.forEach { entityId ->
            if (streamingEnabled) {
                pollUrls[entityId]?.let { url -> ensurePoll(entityId, url) }
            }
        }
    }

    fun updatePictureUrl(
        entityId: String,
        url: String,
        haRemoteUrl: String? = null,
        forceRestart: Boolean = false,
    ) {
        if (entityId.isEmpty() || url.isEmpty()) return
        val candidates = HaMediaUrl.cameraFetchCandidates(url, haRemoteUrl)
        val current = pollUrls[entityId]
        val session = pollSessions[entityId]
        if (!forceRestart &&
            session?.isRunning() == true &&
            (
                session.isHlsPlaying() ||
                    (
                        current != null &&
                            HaMediaUrl.cameraUrlIdentity(current) == HaMediaUrl.cameraUrlIdentity(url)
                        )
                )
        ) {
            pollUrls[entityId] = url
            pollCandidates[entityId] = candidates
            return
        }
        if (!forceRestart && pollUrls[entityId] == url && pollCandidates[entityId] == candidates) {
            if (streamingEnabled) {
                pollSessions[entityId]?.takeIf { it.isRunning() } ?: ensurePoll(entityId, url, forceRestart)
            }
            return
        }
        pollUrls[entityId] = url
        pollCandidates[entityId] = candidates
        if (!streamingEnabled) {
            loadStillIntoMemory(entityId)
            Log.i(TAG, "display hold entity=$entityId url=${maskUrl(url)} (overlay hidden)")
            return
        }
        Log.i(TAG, "display start entity=$entityId url=${maskUrl(url)} candidates=${candidates.size}")
        ensurePoll(entityId, url, forceRestart)
    }

    fun clearPictureUrl(entityId: String) {
        if (entityId.isEmpty()) return
        pollUrls.remove(entityId)
        pollCandidates.remove(entityId)
        pollSessions.remove(entityId)?.stop()
    }

    fun activeEntityIds(): Set<String> = pollUrls.keys.toSet()

    fun updateTileLayout(entityId: String, rect: RectF) {
        if (entityId.isEmpty() || rect.width() <= 0f || rect.height() <= 0f) return
        dirtyRects[entityId] = RectF(rect)
        invalidateRects[entityId] = tileContentInvalidateRect(rect)
        val newSize = contentPixelSize(rect)
        contentSizes[entityId] = newSize
        pendingRedrawEntities.remove(entityId)
        loggedMissingLayout.remove(entityId)
        if (bitmaps.containsKey(entityId)) {
            mainHandler.post { invalidate(invalidateRects[entityId]) }
        }
    }

    fun drawTile(
        canvas: Canvas,
        rect: RectF,
        slot: QuickEntitySlot,
        textPaint: Paint
    ) {
        syncLayoutFromDraw(slot.entityId, rect)

        val tileWidth = rect.width()
        val tileHeight = rect.height()
        val tileSize = min(tileWidth, tileHeight)
        val cornerRadius = tileSize * 0.12f
        val padding = tileSize * 0.12f
        val presetColor = MdiColorMapper.getTileColorForIcon(slot.icon, slot.color.ifEmpty { null })
        val cache = tileDrawCaches.getOrPut(slot.entityId) { TileDrawCache() }
        cache.ensureFrameStyle(rect, cornerRadius, presetColor.topColor)

        val contentRect = contentRectFromTile(rect, padding)
        val bitmap = bitmaps[slot.entityId]?.takeUnless { it.isRecycled }
            ?: loadStillIntoMemory(slot.entityId)
        if (bitmap != null && contentRect.width() > 0f && contentRect.height() > 0f) {
            missingBitmapDrawCounts.remove(slot.entityId)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, cache.bgPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, cache.borderPaint)
            drawBitmapCover(canvas, bitmap, contentRect, cache, slot)
        } else if (lastPresentMs.containsKey(slot.entityId)) {
            // Keep the last composited pixels instead of flashing the empty plate
            // when a bitmap is recycled before the GPU is done with it.
        } else if (contentRect.width() > 0f && contentRect.height() > 0f) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, cache.bgPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, cache.borderPaint)
            val misses = (missingBitmapDrawCounts[slot.entityId] ?: 0) + 1
            missingBitmapDrawCounts[slot.entityId] = misses
            if (misses == 1) {
                Log.w(
                    TAG,
                    "display blank entity=${slot.entityId} pollRunning=${pollSessions[slot.entityId]?.isRunning() == true}"
                )
            }
            canvas.drawRoundRect(contentRect, cache.contentCornerRadius, cache.contentCornerRadius, cache.emptyPaint)
            drawEmptyLabel(canvas, contentRect, slot, textPaint)
        }
    }

    /**
     * Overlay z-order restacks detach this view briefly. Sessions stay up
     * across that blink; [pause] is only for a real hide / cover / destroy.
     *
     * Always pins a software still and writes JPEG **before** sockets close.
     * [dropFrames] only releases decode pools — the still stays for the next show.
     */
    fun pause(dropFrames: Boolean = true) {
        streamingEnabled = false
        pinAndPersistStills()
        pollSessions.values.forEach { it.stop() }
        pollSessions.clear()
        ioScope.coroutineContext.cancelChildren()
        mainHandler.removeCallbacksAndMessages(null)
        flushPosted.set(false)
        pendingFrames.values.forEach { recycleBitmapQuietly(it) }
        pendingFrames.clear()
        recycleDeferredBitmaps(immediate = true)
        if (!dropFrames) return
        val pinned = bitmaps.values.toList()
        decodePools.values.forEach { it.release(pinned) }
        decodePools.clear()
        lastJpegBounds.clear()
        lastDecodeOut.clear()
        missingBitmapDrawCounts.clear()
        pendingRedrawEntities.clear()
    }

    fun resume() {
        if (streamingEnabled) return
        var stillDirty: Rect? = null
        pollUrls.keys.forEach { entityId ->
            if (loadStillIntoMemory(entityId) != null) {
                val dirty = invalidateRects[entityId] ?: dirtyRects[entityId]?.toInvalidateRect()
                stillDirty = if (stillDirty == null || dirty == null) {
                    dirty ?: stillDirty
                } else {
                    stillDirty.apply { union(dirty) }
                }
            }
        }
        stillDirty?.let { invalidate(it) }
        streamingEnabled = true
        if (pollUrls.isEmpty()) return
        pollUrls.forEach { (entityId, url) -> ensurePoll(entityId, url) }
    }

    fun release() {
        pause()
        bitmaps.values.forEach { recycleBitmapQuietly(it) }
        bitmaps.clear()
        lastPresentMs.clear()
        stillWritten.clear()
        stillLoadFailed.clear()
        pollUrls.clear()
        pollCandidates.clear()
        dirtyRects.clear()
        invalidateRects.clear()
        contentSizes.clear()
        tileDrawCaches.clear()
        loggedMissingLayout.clear()
    }

    fun notifyDrawn() {
        drawPass++
        recycleDeferredBitmaps(immediate = false)
    }

    private fun recycleDeferredBitmaps(immediate: Boolean) {
        val minAge = if (immediate) 0 else RECYCLE_DRAW_FENCE
        while (recycleAfterDraw.isNotEmpty()) {
            val tooOld = immediate ||
                drawPass - recycleAfterDraw.first().first >= minAge ||
                recycleAfterDraw.size > RECYCLE_QUEUE_CAP
            if (!tooOld) break
            recycleAfterDraw.removeFirst().second.forEach { bitmap ->
                if (bitmaps.containsValue(bitmap)) return@forEach
                recycleBitmapQuietly(bitmap)
            }
        }
    }

    private fun drawEmptyLabel(
        canvas: Canvas,
        contentRect: RectF,
        slot: QuickEntitySlot,
        textPaint: Paint
    ) {
        val label = slot.label.ifBlank {
            slot.entityId.substringAfter('.', missingDelimiterValue = "")
        }.ifBlank { return }
        val previousColor = textPaint.color
        val previousAlign = textPaint.textAlign
        val previousSize = textPaint.textSize
        try {
            textPaint.color = Color.parseColor("#6a6a70")
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = min(contentRect.width(), contentRect.height()) * 0.14f
            canvas.drawText(label, contentRect.centerX(), contentRect.centerY() + textPaint.textSize / 3f, textPaint)
        } finally {
            textPaint.color = previousColor
            textPaint.textAlign = previousAlign
            textPaint.textSize = previousSize
        }
    }

    private fun syncLayoutFromDraw(entityId: String, rect: RectF) {
        val previous = dirtyRects[entityId]
        if (previous != null &&
            abs(previous.left - rect.left) < 0.5f &&
            abs(previous.top - rect.top) < 0.5f &&
            abs(previous.width() - rect.width()) < 0.5f &&
            abs(previous.height() - rect.height()) < 0.5f
        ) {
            return
        }
        dirtyRects[entityId] = RectF(rect)
        invalidateRects[entityId] = tileContentInvalidateRect(rect)
        val newSize = contentPixelSize(rect)
        contentSizes[entityId] = newSize
        pendingRedrawEntities.remove(entityId)
    }

    private fun resolveFetchDisplaySize(entityId: String, bootstrap: Boolean): Pair<Int, Int>? {
        contentSizes[entityId]?.let { return it }
        return if (bootstrap) BOOTSTRAP_DISPLAY_SIZE else null
    }

    private fun contentRectFromTile(rect: RectF, padding: Float): RectF {
        val inset = padding * 0.2f
        return RectF(
            rect.left + inset,
            rect.top + inset,
            rect.right - inset,
            rect.bottom - inset
        )
    }

    private fun contentPixelSize(tileRect: RectF): Pair<Int, Int> {
        val tileSize = min(tileRect.width(), tileRect.height())
        val padding = tileSize * 0.12f
        val content = contentRectFromTile(tileRect, padding)
        return max(1, ceil(content.width()).toInt()) to max(1, ceil(content.height()).toInt())
    }

    private fun tileContentInvalidateRect(tileRect: RectF): Rect {
        val tileSize = min(tileRect.width(), tileRect.height())
        val padding = tileSize * 0.12f
        return contentRectFromTile(tileRect, padding).toInvalidateRect()
    }

    private fun RectF.toInvalidateRect(): Rect = Rect(
        left.toInt(),
        top.toInt(),
        right.toInt(),
        bottom.toInt()
    )

    private fun recycleBitmapQuietly(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        try {
            bitmap.recycle()
        } catch (_: Throwable) {
        }
    }

    /** Promote pending + live frames to software stills and write JPEG before sockets die. */
    private fun pinAndPersistStills() {
        val now = System.currentTimeMillis()
        val pending = pendingFrames.toMap()
        pendingFrames.clear()
        for ((entityId, pendingBitmap) in pending) {
            val still = toSoftwareStill(pendingBitmap)
            if (still != null) {
                val old = bitmaps.put(entityId, still)
                if (old != null && old !== still && old !== pendingBitmap) {
                    recycleBitmapQuietly(old)
                }
                lastPresentMs[entityId] = now
                persistStillSync(entityId, still)
            }
            if (pendingBitmap !== still) {
                recycleBitmapQuietly(pendingBitmap)
            }
        }
        bitmaps.keys.toList().forEach { entityId ->
            val src = bitmaps[entityId] ?: return@forEach
            val still = toSoftwareStill(src) ?: return@forEach
            if (still !== src) {
                bitmaps[entityId] = still
                recycleBitmapQuietly(src)
            }
            lastPresentMs[entityId] = now
            persistStillSync(entityId, still)
        }
    }

    private fun toSoftwareStill(src: Bitmap): Bitmap? {
        if (src.isRecycled) return null
        return try {
            src.copy(Bitmap.Config.RGB_565, false)
                ?: src.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        }
    }

    private fun persistStillOnce(entityId: String, bitmap: Bitmap) {
        if (!stillWritten.add(entityId)) return
        val snap = toSoftwareStill(bitmap)
        if (snap == null) {
            stillWritten.remove(entityId)
            return
        }
        persistStillSync(entityId, snap)
        if (snap !== bitmap) recycleBitmapQuietly(snap)
    }

    private fun persistStillSync(entityId: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val file = stillFile(entityId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, STILL_JPEG_QUALITY, out)
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            stillWritten.add(entityId)
            stillLoadFailed.remove(entityId)
        } catch (e: Exception) {
            stillWritten.remove(entityId)
            runCatching { tmp.delete() }
            Log.w(TAG, "still persist failed entity=$entityId", e)
        }
    }

    private fun loadStillIntoMemory(entityId: String): Bitmap? {
        bitmaps[entityId]?.takeUnless { it.isRecycled }?.let { return it }
        if (stillLoadFailed.contains(entityId)) return null
        val file = stillFile(entityId)
        if (!file.isFile || file.length() <= 0L) return null
        val decoded = try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Throwable) {
            null
        }
        if (decoded == null) {
            stillLoadFailed.add(entityId)
            return null
        }
        bitmaps[entityId] = decoded
        lastPresentMs[entityId] = System.currentTimeMillis()
        stillWritten.add(entityId)
        return decoded
    }

    private fun stillFile(entityId: String): File {
        val safe = entityId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(stillDir, "$safe.jpg")
    }

    private fun deleteStillFile(entityId: String) {
        stillWritten.remove(entityId)
        stillLoadFailed.remove(entityId)
        runCatching { stillFile(entityId).delete() }
    }

    private fun ensurePoll(entityId: String, pollUrl: String, forceRestart: Boolean = false) {
        if (!streamingEnabled) return
        val candidates = pollCandidates[entityId] ?: listOf(pollUrl)
        val existing = pollSessions[entityId]
        if (existing != null) {
            existing.restartIfNeeded(pollUrl, candidates, forceRestart)
        } else {
            SnapshotPollSession(entityId, pollUrl, candidates).also { session ->
                pollSessions[entityId] = session
                session.start(ioScope)
            }
        }
    }

    /**
     * Drag the cover window with the finger. [dx]/[dy] are in tile pixels;
     * grabbing the picture (finger right) reveals more of the left of the frame.
     * Returns null when there is no frame or nothing is cropped.
     */
    fun panByDrag(
        entityId: String,
        tileRect: RectF,
        panX: Float,
        panY: Float,
        zoom: Float,
        dx: Float,
        dy: Float
    ): Pair<Float, Float>? {
        val bitmap = bitmaps[entityId]?.takeUnless { it.isRecycled } ?: return null
        val tileSize = min(tileRect.width(), tileRect.height())
        val content = contentRectFromTile(tileRect, tileSize * 0.12f)
        val destW = content.width()
        val destH = content.height()
        val layout = cameraImageLayout(bitmap, destW, destH, panX, panY, zoom) ?: return null
        val extraX = layout.extraX
        val extraY = layout.extraY
        val nextX = if (extraX > 1f) {
            (panX - dx / extraX).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val nextY = if (extraY > 1f) {
            (panY - dy / extraY).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        if (nextX == panX && nextY == panY) return null
        return nextX to nextY
    }

    fun zoomByPinch(
        entityId: String,
        tileRect: RectF,
        currentZoom: Float,
        factor: Float
    ): Float? {
        if (factor <= 0f || !factor.isFinite()) return null
        val bitmap = bitmaps[entityId]?.takeUnless { it.isRecycled } ?: return null
        val tileSize = min(tileRect.width(), tileRect.height())
        val content = contentRectFromTile(tileRect, tileSize * 0.12f)
        val destW = content.width()
        val destH = content.height()
        if (destW <= 0f || destH <= 0f) return null
        val minZoom = minZoomFor(bitmap.width.toFloat(), bitmap.height.toFloat(), destW, destH)
        val next = (currentZoom * factor).coerceIn(minZoom, CAMERA_ZOOM_MAX)
        if (abs(next - currentZoom) < 0.002f) return null
        return next
    }

    private fun minZoomFor(bw: Float, bh: Float, destW: Float, destH: Float): Float {
        if (bw <= 0f || bh <= 0f || destW <= 0f || destH <= 0f) return 1f
        val cover = max(destW / bw, destH / bh)
        val contain = min(destW / bw, destH / bh)
        if (cover <= 0f) return 1f
        return (contain / cover).coerceIn(0.05f, 1f)
    }

    private class CameraImageLayout(
        val dest: RectF,
        val extraX: Float,
        val extraY: Float
    )

    private fun cameraImageLayout(
        bitmap: Bitmap,
        destW: Float,
        destH: Float,
        panX: Float,
        panY: Float,
        zoom: Float
    ): CameraImageLayout? {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f || destW <= 0f || destH <= 0f) return null
        val coverScale = max(destW / bw, destH / bh)
        if (coverScale <= 0f) return null
        val minZoom = minZoomFor(bw, bh, destW, destH)
        val scale = coverScale * zoom.coerceIn(minZoom, CAMERA_ZOOM_MAX)
        val drawnW = bw * scale
        val drawnH = bh * scale
        val extraX = drawnW - destW
        val extraY = drawnH - destH
        val px = panX.coerceIn(0f, 1f)
        val py = panY.coerceIn(0f, 1f)
        val left = if (extraX > 1f) -extraX * px else -extraX * 0.5f
        val top = if (extraY > 1f) -extraY * py else -extraY * 0.5f
        return CameraImageLayout(
            dest = RectF(left, top, left + drawnW, top + drawnH),
            extraX = extraX,
            extraY = extraY
        )
    }

    fun setAdjustFade(entityId: String?, alpha: Float) {
        adjustFadeEntityId = entityId
        adjustFadeAlpha = alpha.coerceIn(0f, 1f)
    }

    fun invalidateRectForEntity(entityId: String): Rect? = invalidateRects[entityId]
    private fun drawBitmapCover(
        canvas: Canvas,
        bitmap: Bitmap,
        rect: RectF,
        cache: TileDrawCache,
        slot: QuickEntitySlot
    ) {
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(rect, cache.contentCornerRadius, cache.contentCornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        val layout = cameraImageLayout(
            bitmap,
            rect.width(),
            rect.height(),
            slot.cameraPanX,
            slot.cameraPanY,
            slot.cameraZoom
        )
        if (layout == null) {
            canvas.restore()
            return
        }
        layout.dest.offset(rect.left, rect.top)
        canvas.drawBitmap(bitmap, null, layout.dest, bitmapPaint)
        val fadeAlpha = if (slot.entityId == adjustFadeEntityId) adjustFadeAlpha else 0f
        cache.drawCropFade(canvas, rect, fadeAlpha)
        canvas.restore()
    }

    private fun shouldRecyclePresented(bitmap: Bitmap): Boolean {
        return !bitmap.isMutable || bitmap.config != Bitmap.Config.RGB_565
    }

    private fun publishFrame(entityId: String, bitmap: Bitmap) {
        if (!streamingEnabled) {
            recycleBitmapQuietly(bitmap)
            return
        }
        val replaced = pendingFrames.put(entityId, bitmap)
        if (replaced != null && replaced !== bitmap && shouldRecyclePresented(replaced)) {
            replaced.recycle()
        }
        if (!flushPosted.compareAndSet(false, true)) return
        mainHandler.post { flushPendingFrames() }
    }

    private fun flushPendingFrames() {
        flushPosted.set(false)
        if (!streamingEnabled) {
            pendingFrames.values.forEach { recycleBitmapQuietly(it) }
            pendingFrames.clear()
            return
        }
        if (pendingFrames.isEmpty()) return

        val now = System.currentTimeMillis()
        val updates = ArrayList<Map.Entry<String, Bitmap>>(pendingFrames.size)
        val deferred = ArrayList<Map.Entry<String, Bitmap>>()

        for (entry in pendingFrames.entries) {
            val lastPresent = lastPresentMs[entry.key]
            if (lastPresent != null && now - lastPresent < MIN_PRESENT_INTERVAL_MS) {
                deferred.add(entry)
            } else {
                updates.add(entry)
            }
        }
        pendingFrames.clear()
        deferred.forEach { pendingFrames[it.key] = it.value }

        val recycled = ArrayList<Bitmap>(updates.size)
        for ((entityId, bitmap) in updates) {
            val old = bitmaps.put(entityId, bitmap)
            if (old != null && old !== bitmap && shouldRecyclePresented(old)) {
                recycled.add(old)
            }
            lastPresentMs[entityId] = now
            persistStillOnce(entityId, bitmap)
        }
        if (recycled.isNotEmpty()) {
            recycleAfterDraw.addLast(drawPass to recycled)
            recycleDeferredBitmaps(immediate = false)
        }

        var dirtyUnion: Rect? = null
        for ((entityId, _) in updates) {
            val dirty = invalidateRects[entityId]
                ?: dirtyRects[entityId]?.toInvalidateRect()
                ?: run {
                    pendingRedrawEntities.add(entityId)
                    continue
                }
            dirtyUnion = if (dirtyUnion == null) dirty else dirtyUnion.apply { union(dirty) }
        }
        if (dirtyUnion != null) {
            invalidate(dirtyUnion)
        } else {
            for (entityId in pendingRedrawEntities) {
                if (loggedMissingLayout.add(entityId)) {
                    Log.w(TAG, "display waiting layout entity=$entityId")
                }
            }
        }

        if (pendingFrames.isNotEmpty() && flushPosted.compareAndSet(false, true)) {
            val delayMs = MIN_PRESENT_INTERVAL_MS - (System.currentTimeMillis() - now).coerceAtLeast(0L)
            if (delayMs > 0L) {
                mainHandler.postDelayed({ flushPendingFrames() }, delayMs)
            } else {
                mainHandler.post { flushPendingFrames() }
            }
        }
    }

    private inner class SnapshotPollSession(
        private val entityId: String,
        @Volatile private var pollUrl: String,
        @Volatile private var urlCandidates: List<String>
    ) {
        private var job: Job? = null
        @Volatile private var running = false
        private var nextPollAtMs = 0L
        private var successCount = 0
        private var missCount = 0
        @Volatile private var immediatePollRequested = false
        @Volatile private var workingUrl: String? = null
        private var nextStreamAtMs = 0L
        private var nextHlsAtMs = 0L
        private var hlsGiveUpUntilMs = 0L
        private val hlsPlayer = HaCameraHlsPlayer()
        private val downloadBuffer = JpegDownloadBuffer()
        private val jpegCursor = HaCameraJpeg.Cursor()
        @Volatile private var liveConnection: AutoCloseable? = null

        fun isRunning(): Boolean = running

        fun isHlsPlaying(): Boolean = hlsPlayer.isPlaying

        fun requestImmediatePoll() {
            immediatePollRequested = true
            nextPollAtMs = 0L
        }

        fun start(parentScope: CoroutineScope) {
            stop()
            if (!streamingEnabled) return
            running = true
            successCount = 0
            missCount = 0
            workingUrl = null
            nextStreamAtMs = 0L
            nextHlsAtMs = 0L
            job = parentScope.launch(Dispatchers.IO) {
                nextPollAtMs = 0L
                while (running && streamingEnabled && isActive) {
                    val isBootstrap = successCount == 0
                    if (!isBootstrap) {
                        val waitMs = nextPollAtMs - System.currentTimeMillis()
                        if (waitMs > 0) delay(waitMs)
                    }
                    if (!running) break

                    val displaySize = resolveFetchDisplaySize(entityId, isBootstrap)
                    if (displaySize == null) {
                        delay(LAYOUT_WAIT_MS)
                        continue
                    }

                    val alreadyStream = hasExistingStreamUrl()
                    if (isBootstrap && !alreadyStream) {
                        val bitmap = fetchFromCandidates(displaySize)
                        if (bitmap != null && running) {
                            successCount++
                            publishFrame(entityId, bitmap)
                        } else if (running) {
                            missCount++
                            if (missCount == 1 || missCount % 10 == 0) {
                                Log.w(TAG, "display fetch miss entity=$entityId count=$missCount")
                            }
                        }
                    }
                    if (!running) break

                    var streamed = false
                    val tryStream = System.currentTimeMillis() >= nextStreamAtMs
                    if (tryStream) {
                        for (streamUrl in streamUrlsToTry()) {
                            Log.i(TAG, "display stream entity=$entityId url=${maskUrl(streamUrl)}")
                            streamed = consumeMjpegStream(streamUrl)
                            if (streamed) {
                                workingUrl = streamUrl
                                nextStreamAtMs = 0L
                                break
                            }
                            if (!running || !isActive) break
                        }
                        if (!streamed && running) {
                            nextStreamAtMs = System.currentTimeMillis() + STREAM_RETRY_BACKOFF_MS
                        }
                    }
                    if (!running || !isActive) break
                    if (streamed) {
                        nextPollAtMs = 0L
                        continue
                    }

                    if (HaMediaAuth.signedIn && System.currentTimeMillis() >= nextHlsAtMs) {
                        val hlsUrl = withTimeoutOrNull(HLS_CONNECT_MS) {
                            HaManager.get()?.requestCameraStreamUrl(entityId)
                        }
                        if (!hlsUrl.isNullOrBlank()) {
                            Log.i(TAG, "display hls entity=$entityId url=${maskUrl(hlsUrl)}")
                            val played = try {
                                hlsPlayer.play(appContext, surfaceHost(), hlsUrl) { bitmap ->
                                    try {
                                        if (running && streamingEnabled) {
                                            successCount++
                                            publishFrame(entityId, bitmap)
                                        } else if (!bitmap.isRecycled) {
                                            bitmap.recycle()
                                        }
                                    } catch (t: Throwable) {
                                        if (!bitmap.isRecycled) bitmap.recycle()
                                        Log.w(TAG, "display hls frame drop entity=$entityId", t)
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "display hls abort entity=$entityId", t)
                                false
                            }
                            if (!running || !isActive) break
                            if (played) {
                                nextHlsAtMs = 0L
                                nextPollAtMs = 0L
                                continue
                            }
                        }
                        nextHlsAtMs = System.currentTimeMillis() + STREAM_RETRY_BACKOFF_MS
                    }
                    if (!running || !isActive) break

                    val fetchStartMs = System.currentTimeMillis()
                    var fetchElapsedMs = 0L
                    fetchLimiter.acquire()
                    try {
                        val bitmap = fetchFromCandidates(displaySize)
                        fetchElapsedMs = System.currentTimeMillis() - fetchStartMs
                        if (bitmap != null && running) {
                            successCount++
                            publishFrame(entityId, bitmap)
                        } else if (running) {
                            missCount++
                            fetchElapsedMs = SLOW_FETCH_THRESHOLD_MS
                        }
                    } catch (e: Exception) {
                        if (running) {
                            Log.w(TAG, "display fetch error entity=$entityId", e)
                            fetchElapsedMs = SLOW_FETCH_THRESHOLD_MS
                        }
                    } finally {
                        fetchLimiter.release()
                        val immediate = immediatePollRequested
                        immediatePollRequested = false
                        nextPollAtMs = when {
                            immediate -> 0L
                            fetchElapsedMs >= SLOW_FETCH_THRESHOLD_MS ->
                                System.currentTimeMillis() + SNAPSHOT_ERROR_BACKOFF_MS
                            else -> fetchStartMs + MIN_POLL_INTERVAL_MS
                        }
                    }
                }
            }
        }

        fun restartIfNeeded(newUrl: String, candidates: List<String>, forceRestart: Boolean = false) {
            val samePipe = HaMediaUrl.cameraUrlIdentity(newUrl) == HaMediaUrl.cameraUrlIdentity(pollUrl)
            pollUrl = newUrl
            urlCandidates = candidates
            if (!streamingEnabled) return
            if (!forceRestart && hlsPlayer.isPlaying && HaMediaAuth.signedIn && job?.isActive == true) {
                return
            }
            if (!forceRestart && samePipe && job?.isActive == true) return
            start(ioScope)
        }

        fun stop() {
            running = false
            hlsPlayer.stop()
            val connection = liveConnection
            liveConnection = null
            runCatching { connection?.close() }
            job?.cancel()
            job = null
            jpegCursor.reset()
            downloadBuffer.release()
        }

        private fun adoptConnection(closeable: AutoCloseable) {
            liveConnection = closeable
        }

        private fun dropConnection(closeable: AutoCloseable) {
            if (liveConnection === closeable) liveConnection = null
        }

        private fun snapshotUrlsToTry(): List<String> =
            buildList {
                workingUrl?.let { add(it) }
                addAll(urlCandidates)
                add(pollUrl)
            }.distinct().filterNot { HaMediaUrl.isCameraStreamUrl(it) }

        private fun streamUrlsToTry(): List<String> =
            buildList {
                workingUrl?.let { base ->
                    if (HaMediaUrl.isCameraStreamUrl(base)) {
                        add(base)
                    } else {
                        HaMediaUrl.streamUrlFromSnapshot(base)?.let { add(it) }
                    }
                }
                val token = queryToken(workingUrl) ?: queryToken(pollUrl)
                HaMediaUrl.signedInCameraStreamUrl(entityId, token)?.let { add(it) }
                if (HaMediaUrl.isCameraStreamUrl(pollUrl)) {
                    add(pollUrl)
                } else {
                    HaMediaUrl.streamUrlFromSnapshot(pollUrl)?.let { add(it) }
                }
            }.distinct()

        private fun queryToken(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val q = url.indexOf('?')
            if (q < 0) return null
            return url.substring(q + 1).split('&').firstOrNull { part ->
                part.startsWith("token=", ignoreCase = true) && part.length > 6
            }?.substringAfter('=')?.takeIf { it.isNotBlank() }
        }

        private fun hasExistingStreamUrl(): Boolean =
            HaMediaUrl.isCameraStreamUrl(pollUrl) ||
                urlCandidates.any { HaMediaUrl.isCameraStreamUrl(it) } ||
                (workingUrl?.let { HaMediaUrl.isCameraStreamUrl(it) } == true)

        private fun fetchFromCandidates(displaySize: Pair<Int, Int>): Bitmap? {
            for (url in snapshotUrlsToTry()) {
                val bitmap = fetchSnapshot(
                    url = url,
                    displaySize = displaySize,
                    downloadBuffer = downloadBuffer,
                    logContext = entityId
                )
                if (bitmap != null) {
                    workingUrl = url
                    return bitmap
                }
            }
            return null
        }

        private fun consumeMjpegStream(url: String): Boolean {
            val response = try {
                openSnapshotConnection(url)
            } catch (e: Exception) {
                Log.w(TAG, "display stream fail entity=$entityId", e)
                return false
            }
            if (HaMediaUrl.isRejectedCameraStreamType(response.contentType)) {
                Log.w(TAG, "display stream skip entity=$entityId type=${response.contentType}")
                try {
                    response.close()
                } catch (_: Exception) {
                }
                return false
            }
            adoptConnection(response)
            var frames = 0
            try {
                downloadBuffer.reset()
                jpegCursor.reset()
                val chunk = ByteArray(READ_BUFFER_BYTES)
                val input = response.stream
                while (running && streamingEnabled && job?.isActive == true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    if (downloadBuffer.size + read > MAX_SNAPSHOT_BYTES) {
                        trimStreamBuffer()
                        if (downloadBuffer.size + read > MAX_SNAPSHOT_BYTES) {
                            downloadBuffer.reset()
                            jpegCursor.reset()
                        }
                    }
                    downloadBuffer.append(chunk, 0, read)
                    if (publishLatestJpegInBuffer()) {
                        frames++
                    }
                    if (downloadBuffer.size > MAX_STREAM_LEFTOVER) {
                        trimStreamBuffer()
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "display stream error entity=$entityId", e)
            } finally {
                dropConnection(response)
                try {
                    response.close()
                } catch (_: Exception) {
                }
            }
            if (frames > 0) {
                Log.i(TAG, "display stream end entity=$entityId frames=$frames")
            }
            return frames > 0
        }

        private fun publishLatestJpegInBuffer(): Boolean {
            var latest: IntRange? = null
            while (true) {
                latest = HaCameraJpeg.findCompleteJpeg(
                    downloadBuffer.data,
                    downloadBuffer.size,
                    jpegCursor
                ) ?: break
            }
            val span = latest ?: return false
            val displaySize = resolveFetchDisplaySize(entityId, successCount == 0)
                ?: BOOTSTRAP_DISPLAY_SIZE
            val bitmap = try {
                decodeJpegFromBytes(
                    downloadBuffer.data,
                    span.first,
                    span.last - span.first + 1,
                    displaySize,
                    entityId
                )
            } catch (e: Exception) {
                Log.w(TAG, "display decode error entity=$entityId", e)
                null
            }
            val consumed = span.last + 1
            downloadBuffer.replaceWithRange(consumed, downloadBuffer.size)
            jpegCursor.compact(consumed)
            if (bitmap != null && running) {
                successCount++
                publishFrame(entityId, bitmap)
                return true
            }
            if (bitmap != null && shouldRecyclePresented(bitmap) && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            return false
        }

        private fun trimStreamBuffer() {
            trimStaleStreamBytes(downloadBuffer)
            jpegCursor.reset()
        }

    }

    private class TileDrawCache {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#101114")
        }
        val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        var contentCornerRadius: Float = 0f
        private var layoutKey: Int = Int.MIN_VALUE
        private var fadeKey: Int = Int.MIN_VALUE
        private var leftFade: LinearGradient? = null
        private var rightFade: LinearGradient? = null
        private var topFade: LinearGradient? = null
        private var bottomFade: LinearGradient? = null

        fun ensureFrameStyle(rect: RectF, cornerRadius: Float, accentColor: Int) {
            contentCornerRadius = cornerRadius * 0.85f
            val key = layoutKey(rect, accentColor)
            if (key == layoutKey) return
            layoutKey = key
            bgPaint.shader = LinearGradient(
                rect.left, rect.top,
                rect.left + rect.width() * 0.3f, rect.bottom,
                Color.parseColor("#0f1012"), Color.parseColor("#08090a"),
                Shader.TileMode.CLAMP
            )
            borderPaint.color = Color.argb(
                100,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
        }

        fun drawCropFade(canvas: Canvas, rect: RectF, alpha: Float) {
            if (alpha <= 0.01f) return
            val fade = min(rect.width(), rect.height()) * EDGE_FADE_FRACTION
            if (fade < 1f) return
            ensureFadeShaders(rect, fade)
            fadePaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            fadePaint.shader = leftFade
            canvas.drawRect(rect.left, rect.top, rect.left + fade, rect.bottom, fadePaint)
            fadePaint.shader = rightFade
            canvas.drawRect(rect.right - fade, rect.top, rect.right, rect.bottom, fadePaint)
            fadePaint.shader = topFade
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + fade, fadePaint)
            fadePaint.shader = bottomFade
            canvas.drawRect(rect.left, rect.bottom - fade, rect.right, rect.bottom, fadePaint)
            fadePaint.alpha = 255
            fadePaint.shader = null
        }

        private fun ensureFadeShaders(rect: RectF, fade: Float) {
            val key = fadeKey(rect, fade)
            if (key == fadeKey) return
            fadeKey = key
            val edge = Color.parseColor("#0f1012")
            val clear = Color.TRANSPARENT
            leftFade = LinearGradient(
                rect.left, rect.top, rect.left + fade, rect.top,
                edge, clear, Shader.TileMode.CLAMP
            )
            rightFade = LinearGradient(
                rect.right, rect.top, rect.right - fade, rect.top,
                edge, clear, Shader.TileMode.CLAMP
            )
            topFade = LinearGradient(
                rect.left, rect.top, rect.left, rect.top + fade,
                edge, clear, Shader.TileMode.CLAMP
            )
            bottomFade = LinearGradient(
                rect.left, rect.bottom, rect.left, rect.bottom - fade,
                edge, clear, Shader.TileMode.CLAMP
            )
        }

        private fun fadeKey(rect: RectF, fade: Float): Int {
            var hash = 17
            hash = 31 * hash + rect.left.roundToInt()
            hash = 31 * hash + rect.top.roundToInt()
            hash = 31 * hash + rect.width().roundToInt()
            hash = 31 * hash + rect.height().roundToInt()
            hash = 31 * hash + fade.roundToInt()
            return hash
        }

        private fun layoutKey(rect: RectF, accentColor: Int): Int {
            var hash = 17
            hash = 31 * hash + rect.width().roundToInt()
            hash = 31 * hash + rect.height().roundToInt()
            hash = 31 * hash + accentColor
            return hash
        }
    }

    private fun fetchSnapshot(
        url: String,
        displaySize: Pair<Int, Int>?,
        downloadBuffer: JpegDownloadBuffer,
        logContext: String
    ): Bitmap? {
        return decodeSnapshotFromUrl(url, displaySize, downloadBuffer, logContext)
            ?: HaMediaUrl.streamUrlFromSnapshot(url)?.let { streamUrl ->
                if (streamUrl == url) return@let null
                Log.w(TAG, "display retry stream entity=$logContext")
                decodeSnapshotFromUrl(streamUrl, displaySize, downloadBuffer, logContext)
            }
    }

    private fun decodeSnapshotFromUrl(
        url: String,
        displaySize: Pair<Int, Int>?,
        downloadBuffer: JpegDownloadBuffer,
        logContext: String
    ): Bitmap? {
        val response = try {
            openSnapshotConnection(url)
        } catch (e: Exception) {
            Log.w(TAG, "display http fail entity=$logContext url=${maskUrl(url)}", e)
            return null
        }
        response.use {
            if (!readImagePayload(
                    input = it.stream,
                    contentType = it.contentType,
                    contentLength = it.contentLength,
                    maxBytes = MAX_SNAPSHOT_BYTES,
                    buffer = downloadBuffer
                )
            ) {
                Log.w(
                    TAG,
                    "display read fail entity=$logContext type=${it.contentType} bytes=${downloadBuffer.size}"
                )
                return null
            }
        }
        return decodeJpegFromBuffer(downloadBuffer, displaySize, logContext)
    }

    private fun decodeJpegFromBuffer(
        downloadBuffer: JpegDownloadBuffer,
        displaySize: Pair<Int, Int>?,
        logContext: String
    ): Bitmap? {
        if (downloadBuffer.size <= 0) return null
        if (!normalizeToJpegBuffer(downloadBuffer)) {
            Log.w(TAG, "display not jpeg entity=$logContext")
            return null
        }
        return decodeJpegFromBytes(downloadBuffer.data, 0, downloadBuffer.size, displaySize, logContext)
    }

    private fun decodeJpegFromBytes(
        data: ByteArray,
        offset: Int,
        length: Int,
        displaySize: Pair<Int, Int>?,
        entityId: String? = null
    ): Bitmap? {
        if (length <= 0 || offset < 0 || offset + length > data.size) return null

        val known = entityId?.let { lastJpegBounds[it] }
        if (known != null) {
            decodeWithOptions(data, offset, length, displaySize, known.first, known.second, entityId)
                ?.let { return it }
            lastJpegBounds.remove(entityId)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, offset, length, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (entityId != null) {
            lastJpegBounds[entityId] = bounds.outWidth to bounds.outHeight
        }
        return decodeWithOptions(
            data,
            offset,
            length,
            displaySize,
            bounds.outWidth,
            bounds.outHeight,
            entityId
        )
    }

    private fun decodeWithOptions(
        data: ByteArray,
        offset: Int,
        length: Int,
        displaySize: Pair<Int, Int>?,
        jpegWidth: Int,
        jpegHeight: Int,
        entityId: String?,
    ): Bitmap? {
        val decodeMax = resolveDecodeMaxDimension(jpegWidth, jpegHeight, displaySize)
        val sampleSize = calculateInSampleSize(jpegWidth, jpegHeight, decodeMax)
        decodeHardware(data, offset, length, sampleSize)?.let { decoded ->
            if (entityId != null) lastDecodeOut[entityId] = decoded.width to decoded.height
            return decoded
        }
        val reuse = entityId?.let { id ->
            val size = lastDecodeOut[id]
            if (size == null) {
                null
            } else {
                decodePools.getOrPut(id) { CameraFramePool() }.acquire(
                    size.first,
                    size.second,
                    listOf(bitmaps[id], pendingFrames[id]),
                )
            }
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
            inDither = true
            inMutable = true
            inBitmap = reuse
            inTempStorage = decodeTemp.get()
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(data, offset, length, options)
        } catch (_: Throwable) {
            options.inBitmap = null
            try {
                BitmapFactory.decodeByteArray(data, offset, length, options)
            } catch (_: OutOfMemoryError) {
                decodeDownsampled(data, offset, length, sampleSize)
            }
        } ?: run {
            if (options.inBitmap == null) {
                try {
                    decodeDownsampled(data, offset, length, sampleSize)
                } catch (_: Throwable) {
                    null
                }
            } else {
                options.inBitmap = null
                try {
                    BitmapFactory.decodeByteArray(data, offset, length, options)
                } catch (_: Throwable) {
                    decodeDownsampled(data, offset, length, sampleSize)
                }
            }
        }
        if (decoded != null && entityId != null) {
            lastDecodeOut[entityId] = decoded.width to decoded.height
        }
        return decoded
    }

    private fun decodeHardware(
        data: ByteArray,
        offset: Int,
        length: Int,
        sampleSize: Int,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.HARDWARE
            inMutable = false
            inTempStorage = decodeTemp.get()
        }
        return try {
            BitmapFactory.decodeByteArray(data, offset, length, options)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeDownsampled(
        data: ByteArray,
        offset: Int,
        length: Int,
        sampleSize: Int
    ): Bitmap? {
        val fallback = BitmapFactory.Options().apply {
            inSampleSize = (sampleSize * 2).coerceAtLeast(2)
            inPreferredConfig = Bitmap.Config.RGB_565
            inTempStorage = decodeTemp.get()
        }
        return BitmapFactory.decodeByteArray(data, offset, length, fallback)
    }

    private fun trimStaleStreamBytes(buffer: JpegDownloadBuffer) {
        val data = buffer.data
        val size = buffer.size
        if (size <= 0) return
        var soi = -1
        val min = (size - MAX_STREAM_LEFTOVER).coerceAtLeast(0)
        var i = size - 2
        while (i >= min) {
            if (data[i] == JPEG_SOI0 && data[i + 1] == JPEG_SOI1) {
                soi = i
                break
            }
            i--
        }
        when {
            soi > 0 -> buffer.replaceWithRange(soi, size)
            soi < 0 && size > MAX_STREAM_LEFTOVER -> buffer.reset()
        }
    }

    private fun resolveDecodeMaxDimension(
        jpegWidth: Int,
        jpegHeight: Int,
        displaySize: Pair<Int, Int>?
    ): Int {
        displaySize?.let { (w, h) ->
            return (max(w, h).toFloat() * SHARPNESS_SCALE)
                .roundToInt()
                .coerceIn(MIN_DECODE_PX, MAX_DECODE_PX)
        }
        return max(jpegWidth, jpegHeight).coerceIn(MIN_DECODE_PX, MAX_DECODE_PX)
    }

    private class JpegDownloadBuffer(initialCapacity: Int = 256 * 1024) {
        private var backing = ByteArray(initialCapacity)
        var size: Int = 0
            private set

        val data: ByteArray
            get() = backing

        fun reset() {
            size = 0
        }

        fun release() {
            size = 0
            backing = EMPTY_BYTES
        }

        fun append(data: ByteArray, offset: Int, length: Int) {
            ensureCapacity(size + length)
            System.arraycopy(data, offset, backing, size, length)
            size += length
        }

        fun replaceWithRange(start: Int, endExclusive: Int) {
            if (start <= 0 && endExclusive >= size) return
            val length = (endExclusive - start).coerceAtLeast(0)
            if (length <= 0) {
                size = 0
                return
            }
            System.arraycopy(backing, start, backing, 0, length)
            size = length
        }

        fun copyRange(span: IntRange): ByteArray? {
            val start = span.first
            val length = span.last - span.first + 1
            if (start < 0 || length <= 0 || start + length > size) return null
            return backing.copyOfRange(start, start + length)
        }

        private fun ensureCapacity(minCapacity: Int) {
            if (backing.size >= minCapacity) return
            var newSize = backing.size.coerceAtLeast(INITIAL_CAPACITY)
            while (newSize < minCapacity) {
                newSize *= 2
            }
            backing = backing.copyOf(newSize)
        }

        companion object {
            private const val INITIAL_CAPACITY = 256 * 1024
            private val EMPTY_BYTES = ByteArray(0)
        }
    }

    companion object {
        private const val TAG = "QuickEntityCamera"
        private const val MIN_POLL_INTERVAL_MS = 66L
        private const val MIN_PRESENT_INTERVAL_MS = 50L
        private const val SLOW_FETCH_THRESHOLD_MS = 700L
        private const val SNAPSHOT_ERROR_BACKOFF_MS = 200L
        private const val STREAM_RETRY_BACKOFF_MS = 2500L
        private const val HLS_CONNECT_MS = 2000L
        private const val STREAM_REDIRECT_LIMIT = 5
        private const val SHARPNESS_SCALE = 1.0f
        private const val CAMERA_ZOOM_MAX = 3f
        private const val EDGE_FADE_FRACTION = 0.10f
        private const val RECYCLE_DRAW_FENCE = 2
        private const val RECYCLE_QUEUE_CAP = 4
        private const val MAX_STREAM_LEFTOVER = 384 * 1024
        private const val MIN_DECODE_PX = 160
        private const val MAX_DECODE_PX = 960
        private const val LAYOUT_WAIT_MS = 80L
        private const val STILL_DIR_NAME = "qe-camera"
        private const val STILL_JPEG_QUALITY = 80
        private val BOOTSTRAP_DISPLAY_SIZE = 480 to 360
        private const val MAX_SNAPSHOT_BYTES = 6 * 1024 * 1024
        private const val READ_BUFFER_BYTES = 32 * 1024
        private const val FETCH_CONCURRENCY = 2
        private val JPEG_SOI0: Byte = 0xFF.toByte()
        private val JPEG_SOI1: Byte = 0xD8.toByte()

        private val fetchLimiter = Semaphore(FETCH_CONCURRENCY)

        fun resolveHaPictureUrl(picturePath: String, haHost: String?): String? {
            return HaMediaUrl.resolvePreferringSignedIn(picturePath, haHost)?.takeUnless {
                // Relative path with no peer host and no signed-in origin cannot be fetched.
                picturePath.startsWith("/") && it.startsWith("/")
            }
        }

        fun resolveCameraPollUrl(
            entityId: String,
            entityPicture: String?,
            accessToken: String?,
            haHost: String?,
            lastUrl: String? = null,
        ): String? {
            if (!entityPicture.isNullOrEmpty()) {
                resolveHaPictureUrl(entityPicture, haHost)?.let { return it }
            }
            HaMediaUrl.signedInCameraProxyUrl(entityId)?.let { return it }
            if (entityId.startsWith("camera.") && !accessToken.isNullOrEmpty() && !haHost.isNullOrEmpty()) {
                return "http://${HaMediaUrl.hostForUrl(haHost)}:${HaMediaUrl.haPortOrDefault}/api/camera_proxy/$entityId?token=$accessToken"
            }
            if (!lastUrl.isNullOrBlank()) {
                if (HaMediaAuth.signedIn) {
                    HaMediaUrl.resolvePreferringSignedIn(lastUrl, haHost)?.let { return it }
                } else if (!haHost.isNullOrEmpty()) {
                    HaMediaUrl.resolve(lastUrl, haHost)?.let { return it }
                } else {
                    return lastUrl
                }
            }
            return null
        }

        private fun readImagePayload(
            input: BufferedInputStream,
            contentType: String,
            contentLength: Int,
            maxBytes: Int,
            buffer: JpegDownloadBuffer
        ): Boolean {
            buffer.reset()
            val isMultipart = contentType.contains("multipart/x-mixed-replace") ||
                contentType.contains("multipart/mixed")
            val chunk = ByteArray(READ_BUFFER_BYTES)

            if (!isMultipart && contentType.startsWith("image/") &&
                contentLength in 1..maxBytes
            ) {
                var remaining = contentLength
                while (remaining > 0) {
                    val read = input.read(chunk, 0, min(chunk.size, remaining))
                    if (read <= 0) break
                    buffer.append(chunk, 0, read)
                    remaining -= read
                }
                return buffer.size > 0
            }

            while (buffer.size < maxBytes) {
                val read = input.read(chunk)
                if (read <= 0) break
                if (buffer.size + read > maxBytes) return false
                buffer.append(chunk, 0, read)
                if (HaCameraJpeg.findCompleteJpeg(buffer.data, buffer.size) != null) {
                    return true
                }
            }
            return buffer.size > 0
        }

        private fun normalizeToJpegBuffer(buffer: JpegDownloadBuffer): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(buffer.data, 0, buffer.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) return true

            val span = HaCameraJpeg.findCompleteJpeg(buffer.data, buffer.size) ?: return false
            buffer.replaceWithRange(span.first, span.last + 1)
            return true
        }

        private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
            var sampleSize = 1
            while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            return sampleSize.coerceAtLeast(1)
        }

        private class SnapshotHttpResponse(
            private val connection: HttpURLConnection
        ) : AutoCloseable {
            val contentType: String = connection.contentType?.lowercase().orEmpty()
            val contentLength: Int = connection.contentLength
            val stream = BufferedInputStream(connection.inputStream, READ_BUFFER_BYTES)
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try {
                    stream.close()
                } finally {
                    connection.disconnect()
                }
            }
        }

        private fun openSnapshotConnection(url: String): SnapshotHttpResponse {
            var current = url
            var hops = 0
            while (true) {
                val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = if (HaMediaUrl.isCameraStreamUrl(current)) 15000 else 10000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("Accept", "image/jpeg,image/*,multipart/x-mixed-replace,*/*;q=0.8")
                    HaMediaUrl.applyBearer(this, current)
                }
                connection.connect()
                val code = connection.responseCode
                if (code in 301..308 && hops < STREAM_REDIRECT_LIMIT) {
                    val next = HaMediaUrl.resolveRedirect(current, connection.getHeaderField("Location"))
                    connection.disconnect()
                    if (next == null || next == current) {
                        throw java.io.IOException("HTTP $code redirect")
                    }
                    current = next
                    hops++
                    continue
                }
                if (code !in 200..299) {
                    connection.disconnect()
                    throw java.io.IOException("HTTP $code")
                }
                return SnapshotHttpResponse(connection)
            }
        }

        internal fun maskUrl(url: String): String {
            return url.replace(Regex("([?&]token=)[^&]+"), "$1***")
        }
    }
}

/** Same pixel size, RGB_565, 3 slots so the on-screen frame is never overwritten. */
internal class CameraFramePool(
    private val config: Bitmap.Config = Bitmap.Config.RGB_565,
    private val slotCount: Int = 3,
) {
    private val slots = arrayOfNulls<Bitmap>(slotCount)
    private var cursor = 0

    fun acquire(width: Int, height: Int, avoid: Collection<Bitmap?>): Bitmap? {
        if (width <= 0 || height <= 0) return null
        repeat(slotCount) { step ->
            val index = (cursor + step) % slotCount
            val existing = slots[index]
            if (existing != null &&
                !existing.isRecycled &&
                existing.width == width &&
                existing.height == height &&
                existing.config == config &&
                existing !in avoid
            ) {
                cursor = (index + 1) % slotCount
                return existing
            }
        }
        return try {
            val created = Bitmap.createBitmap(width, height, config)
            val index = cursor
            val old = slots[index]
            if (old != null && old !== created && old !in avoid && !old.isRecycled) {
                try {
                    old.recycle()
                } catch (_: Throwable) {
                }
            }
            slots[index] = created
            cursor = (index + 1) % slotCount
            created
        } catch (_: Throwable) {
            null
        }
    }

    fun release(avoid: Collection<Bitmap?> = emptyList()) {
        slots.forEachIndexed { index, bitmap ->
            if (bitmap != null && bitmap !in avoid && !bitmap.isRecycled) {
                try {
                    bitmap.recycle()
                } catch (_: Throwable) {
                }
            }
            slots[index] = null
        }
        cursor = 0
    }
}
