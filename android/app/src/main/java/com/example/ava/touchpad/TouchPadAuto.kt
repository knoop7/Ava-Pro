package com.example.ava.touchpad

/**
 * One recording: taps, holds, and slides in the order they happened.
 * Repeat-tap is just a take that only contains taps — not a separate mode.
 * Open for the whole visit so the 30-minute idle closer cannot dismiss the
 * pad mid-record or mid-play and leave taps on the real screen.
 */
internal enum class TouchPadAutoPage { ARM, RECORD, PLAY, PAUSED }

internal enum class TouchPadAutoStepType {
    TAP, PRESS, SLIDE, DELAY, BACK, SECONDARY, RECENTS, SCROLL,
    SIDEBAR, ROUTE, WINDOW, KEY, TEXT,
}

internal fun TouchPadAutoStepType.isMark(): Boolean = when (this) {
    TouchPadAutoStepType.TAP,
    TouchPadAutoStepType.PRESS,
    TouchPadAutoStepType.SLIDE,
    TouchPadAutoStepType.BACK,
    TouchPadAutoStepType.SECONDARY,
    TouchPadAutoStepType.RECENTS,
    TouchPadAutoStepType.SCROLL,
    TouchPadAutoStepType.SIDEBAR -> true
    TouchPadAutoStepType.DELAY,
    TouchPadAutoStepType.ROUTE,
    TouchPadAutoStepType.WINDOW,
    TouchPadAutoStepType.KEY,
    TouchPadAutoStepType.TEXT -> false
}

internal data class TouchPadAutoStep(
    val type: TouchPadAutoStepType,
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val durationMs: Long = TouchPadMath.TAP_DURATION_MS,
    val extra: String = "",
    val keyCode: Int = 0,
)

internal data class TouchPadAutoTake(
    val steps: List<TouchPadAutoStep>,
    val sceneRoute: String = "",
    val scenePackage: String = "",
    val sceneWindows: List<String> = emptyList(),
) {
    fun hasContent(): Boolean = steps.any { it.type != TouchPadAutoStepType.DELAY }

    fun scene(): TouchPadAutoScene = TouchPadAutoScene(sceneRoute, scenePackage, sceneWindows)
}

internal class TouchPadAuto {
    var open: Boolean = false
        private set
    var page: TouchPadAutoPage = TouchPadAutoPage.ARM
        private set
    var selected: Int = 0
        private set

    private val library = arrayOfNulls<TouchPadAutoTake>(MAX_TAKES)
    private val steps = ArrayList<TouchPadAutoStep>()
    private var lastEndAt = 0L
    private var preferNewSlot = false
    var playCursor: Int = 0
        private set
    var loop: Boolean = false
    var sceneRoute: String = ""
        private set
    var scenePackage: String = ""
        private set
    var sceneWindows: List<String> = emptyList()
        private set
    private var startScene = TouchPadAutoScene()
    private var liveScene = TouchPadAutoScene()

    fun holdsIdleClose(): Boolean = open

    fun hasTake(): Boolean = steps.any { it.type != TouchPadAutoStepType.DELAY }

    fun hasAnyTake(): Boolean = library.any { it?.hasContent() == true }

    fun filledMask(): BooleanArray =
        BooleanArray(MAX_TAKES) { library[it]?.hasContent() == true }

    fun snapshot(): List<TouchPadAutoStep> = steps.toList()

    fun snapshotLibrary(): List<TouchPadAutoTake?> = library.toList()

    fun restoreLibrary(takes: List<TouchPadAutoTake?>, selectedIndex: Int = 0) {
        for (i in 0 until MAX_TAKES) {
            library[i] = takes.getOrNull(i)?.takeIf { it.hasContent() }
        }
        selected = selectedIndex.coerceIn(0, MAX_TAKES - 1)
        if (library[selected] == null) {
            selected = library.indices.firstOrNull { library[it] != null } ?: 0
        }
        preferNewSlot = hasAnyTake()
        if (open && page == TouchPadAutoPage.ARM) loadSelectedIntoLive()
    }

    fun clearTake(index: Int): Boolean {
        val next = index.coerceIn(0, MAX_TAKES - 1)
        if (library[next] == null) return false
        if (page == TouchPadAutoPage.RECORD) return false
        if (page == TouchPadAutoPage.PLAY || page == TouchPadAutoPage.PAUSED) {
            if (next == selected) {
                page = TouchPadAutoPage.ARM
                playCursor = 0
            }
        }
        library[next] = null
        if (selected == next) {
            selected = library.indices.firstOrNull { library[it] != null } ?: 0
            if (open && page == TouchPadAutoPage.ARM) loadSelectedIntoLive()
        }
        preferNewSlot = hasAnyTake()
        return true
    }

    fun select(index: Int): Boolean {
        if (!open || page != TouchPadAutoPage.ARM) return false
        val next = index.coerceIn(0, MAX_TAKES - 1)
        selected = next
        preferNewSlot = false
        loadSelectedIntoLive()
        return true
    }

    fun open() {
        open = true
        page = TouchPadAutoPage.ARM
        playCursor = 0
        preferNewSlot = hasAnyTake()
        loadSelectedIntoLive()
    }

    fun bindScene(route: String?, packageName: String?) {
        bindScene(TouchPadAutoScene(route.orEmpty(), packageName.orEmpty()))
    }

    fun bindScene(scene: TouchPadAutoScene) {
        if (!open) return
        startScene = scene
        liveScene = scene
        applyScene(scene)
    }

    fun currentScene(): TouchPadAutoScene =
        TouchPadAutoScene(sceneRoute, scenePackage, sceneWindows)

    fun beginRecord(): Boolean {
        if (!open) return false
        if (page == TouchPadAutoPage.PLAY || page == TouchPadAutoPage.PAUSED) return false
        if (preferNewSlot) {
            val empty = firstEmpty()
            if (empty >= 0) selected = empty
        }
        preferNewSlot = false
        steps.clear()
        lastEndAt = 0L
        playCursor = 0
        startScene = TouchPadAutoScene()
        liveScene = TouchPadAutoScene()
        applyScene(TouchPadAutoScene())
        page = TouchPadAutoPage.RECORD
        return true
    }

    fun finishRecord() {
        if (page != TouchPadAutoPage.RECORD) return
        page = TouchPadAutoPage.ARM
        if (hasTake()) {
            library[selected] = TouchPadAutoTake(
                steps = steps.toList(),
                sceneRoute = startScene.route,
                scenePackage = startScene.packageName,
                sceneWindows = startScene.windows,
            )
            preferNewSlot = true
            applyScene(startScene)
        } else {
            loadSelectedIntoLive()
            preferNewSlot = hasAnyTake()
        }
    }

    fun beginPlay(): Boolean {
        if (!open || !hasTake()) return false
        if (page == TouchPadAutoPage.PLAY || page == TouchPadAutoPage.PAUSED) return false
        playCursor = 0
        applyScene(startScene)
        page = TouchPadAutoPage.PLAY
        return true
    }

    fun pausePlay(): Boolean {
        if (page != TouchPadAutoPage.PLAY) return false
        page = TouchPadAutoPage.PAUSED
        return true
    }

    fun resumePlay(): Boolean {
        if (page != TouchPadAutoPage.PAUSED || !hasTake()) return false
        page = TouchPadAutoPage.PLAY
        return true
    }

    /** Stop this run and forget the cursor. The take stays. */
    fun haltPlay() {
        if (page != TouchPadAutoPage.PLAY && page != TouchPadAutoPage.PAUSED) return
        page = TouchPadAutoPage.ARM
        playCursor = 0
    }

    /** Start this take again from the first step. */
    fun replayPlay(): Boolean {
        if (!open || !hasTake()) return false
        if (page != TouchPadAutoPage.PLAY && page != TouchPadAutoPage.PAUSED) return false
        playCursor = 0
        applyScene(startScene)
        page = TouchPadAutoPage.PLAY
        return true
    }

    fun finishPlay() = haltPlay()

    /** One pass is done: stay on the play face, rewind, wait. */
    fun parkAfterPass(): Boolean {
        if (page != TouchPadAutoPage.PLAY || !hasTake()) return false
        playCursor = 0
        page = TouchPadAutoPage.PAUSED
        return true
    }

    fun finishedPass(): Boolean = steps.isNotEmpty() && playCursor >= steps.size

    fun wrapPlayCursor(): Boolean {
        if (!finishedPass()) return false
        playCursor = 0
        return true
    }

    fun consumePlayStep(): TouchPadAutoStep? {
        if (page != TouchPadAutoPage.PLAY) return null
        if (playCursor !in steps.indices) return null
        return steps[playCursor++]
    }

    /** Action mark under the play head, or -1 before the first action. */
    fun playedActionIndex(): Int {
        var count = 0
        val end = playCursor.coerceIn(0, steps.size)
        for (i in 0 until end) {
            if (steps[i].type.isMark()) count++
        }
        return count - 1
    }

    fun close() {
        open = false
        page = TouchPadAutoPage.ARM
        steps.clear()
        lastEndAt = 0L
        playCursor = 0
        applyScene(TouchPadAutoScene())
        startScene = TouchPadAutoScene()
        liveScene = TouchPadAutoScene()
    }

    fun recordSceneIfChanged(scene: TouchPadAutoScene, nowMs: Long) {
        if (page != TouchPadAutoPage.RECORD) return
        if (startScene.isBlank() && !hasTake()) {
            bindScene(scene)
            return
        }
        if (scene.route == liveScene.route &&
            scene.packageName == liveScene.packageName &&
            scene.windows == liveScene.windows
        ) {
            return
        }
        appendGap(nowMs)
        var wrote = false
        if (scene.route != liveScene.route && scene.route.isNotBlank()) {
            steps.add(TouchPadAutoStep(type = TouchPadAutoStepType.ROUTE, extra = scene.route))
            wrote = true
        }
        val entered = scene.windows.filter { it.isNotBlank() && it !in liveScene.windows }
        for (pkg in entered) {
            steps.add(TouchPadAutoStep(type = TouchPadAutoStepType.WINDOW, x = 1f, extra = pkg))
            wrote = true
        }
        if (scene.packageName.isNotBlank() &&
            scene.packageName != liveScene.packageName &&
            scene.packageName !in entered &&
            scene.windows.isEmpty()
        ) {
            steps.add(
                TouchPadAutoStep(
                    type = TouchPadAutoStepType.WINDOW,
                    x = 1f,
                    extra = scene.packageName,
                ),
            )
            wrote = true
        }
        if (wrote) lastEndAt = nowMs
        liveScene = scene
    }

    fun recordKey(keyCode: Int, action: Int, nowMs: Long) {
        if (page != TouchPadAutoPage.RECORD) return
        if (keyCode == 0) return
        val last = steps.lastOrNull()
        if (last?.type == TouchPadAutoStepType.KEY &&
            last.keyCode == keyCode &&
            nowMs - lastEndAt < KEY_DEDUP_MS
        ) {
            return
        }
        appendGap(nowMs)
        steps.add(
            TouchPadAutoStep(
                type = TouchPadAutoStepType.KEY,
                keyCode = keyCode,
                durationMs = action.toLong(),
            ),
        )
        lastEndAt = nowMs
    }

    fun recordText(text: String, nowMs: Long) {
        if (page != TouchPadAutoPage.RECORD) return
        val last = steps.lastOrNull()
        if (last?.type == TouchPadAutoStepType.TEXT && last.extra == text) {
            lastEndAt = nowMs
            return
        }
        if (last?.type == TouchPadAutoStepType.TEXT && nowMs - lastEndAt <= TEXT_COALESCE_MS) {
            steps[steps.lastIndex] = last.copy(extra = text)
            lastEndAt = nowMs
            return
        }
        if (text.isEmpty() && steps.none { it.type == TouchPadAutoStepType.TEXT }) return
        appendGap(nowMs)
        steps.add(TouchPadAutoStep(type = TouchPadAutoStepType.TEXT, extra = text))
        lastEndAt = nowMs
    }

    private fun loadSelectedIntoLive() {
        steps.clear()
        lastEndAt = 0L
        playCursor = 0
        val take = library[selected]
        if (take != null) {
            steps.addAll(take.steps)
            startScene = take.scene()
            liveScene = startScene
            applyScene(startScene)
        } else {
            startScene = TouchPadAutoScene()
            liveScene = TouchPadAutoScene()
            applyScene(TouchPadAutoScene())
        }
    }

    private fun applyScene(scene: TouchPadAutoScene) {
        sceneRoute = scene.route
        scenePackage = scene.packageName
        sceneWindows = scene.windows
    }

    private fun firstEmpty(): Int =
        library.indices.firstOrNull { library[it]?.hasContent() != true } ?: -1

    fun recordTap(x: Float, y: Float, nowMs: Long, durationMs: Long = TouchPadMath.TAP_DURATION_MS) {
        recordPoint(TouchPadAutoStepType.TAP, x, y, nowMs, durationMs = durationMs)
    }

    fun recordPress(x: Float, y: Float, nowMs: Long, durationMs: Long) {
        if (page != TouchPadAutoPage.RECORD) return
        appendGap(nowMs)
        val held = durationMs.coerceAtLeast(1L)
        steps.add(
            TouchPadAutoStep(
                type = TouchPadAutoStepType.PRESS,
                x = x,
                y = y,
                x2 = x,
                y2 = y,
                durationMs = held,
            ),
        )
        lastEndAt = nowMs + held
    }

    fun recordSlide(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        nowMs: Long,
        durationMs: Long,
    ) {
        if (page != TouchPadAutoPage.RECORD) return
        appendGap(nowMs)
        val travel = durationMs.coerceAtLeast(1L)
        steps.add(
            TouchPadAutoStep(
                type = TouchPadAutoStepType.SLIDE,
                x = x1,
                y = y1,
                x2 = x2,
                y2 = y2,
                durationMs = travel,
            ),
        )
        lastEndAt = nowMs + travel
    }

    fun recordBack(x: Float, y: Float, nowMs: Long) {
        recordPoint(TouchPadAutoStepType.BACK, x, y, nowMs)
    }

    fun recordSecondary(x: Float, y: Float, nowMs: Long) {
        recordPoint(TouchPadAutoStepType.SECONDARY, x, y, nowMs)
    }

    fun recordRecents(x: Float, y: Float, nowMs: Long) {
        recordPoint(TouchPadAutoStepType.RECENTS, x, y, nowMs)
    }

    fun recordScroll(x: Float, y: Float, dx: Float, dy: Float, nowMs: Long) {
        if (page != TouchPadAutoPage.RECORD) return
        appendGap(nowMs)
        steps.add(
            TouchPadAutoStep(
                type = TouchPadAutoStepType.SCROLL,
                x = x,
                y = y,
                x2 = dx,
                y2 = dy,
                durationMs = TouchPadMath.SCROLL_PRESS_MS,
            ),
        )
        lastEndAt = nowMs + TouchPadMath.SCROLL_PRESS_MS
    }

    fun recordSidebar(x: Float, y: Float, dx: Float, nowMs: Long, durationMs: Long) {
        if (page != TouchPadAutoPage.RECORD) return
        if (kotlin.math.abs(dx) < TouchPadMath.TAP_SLOP_PX) return
        appendGap(nowMs)
        val travel = durationMs.coerceAtLeast(80L)
        steps.add(
            TouchPadAutoStep(
                type = TouchPadAutoStepType.SIDEBAR,
                x = x,
                y = y,
                x2 = dx,
                durationMs = travel,
            ),
        )
        lastEndAt = nowMs + travel
    }

    private fun recordPoint(
        type: TouchPadAutoStepType,
        x: Float,
        y: Float,
        nowMs: Long,
        durationMs: Long = TouchPadMath.TAP_DURATION_MS,
    ) {
        if (page != TouchPadAutoPage.RECORD) return
        appendGap(nowMs)
        val held = durationMs.coerceAtLeast(1L)
        steps.add(
            TouchPadAutoStep(
                type = type,
                x = x,
                y = y,
                x2 = x,
                y2 = y,
                durationMs = held,
            ),
        )
        lastEndAt = nowMs + held
    }

    private fun appendGap(nowMs: Long) {
        if (lastEndAt <= 0L) return
        val gap = (nowMs - lastEndAt).coerceIn(0L, MAX_GAP_MS)
        if (gap > 0L) {
            steps.add(
                TouchPadAutoStep(
                    type = TouchPadAutoStepType.DELAY,
                    durationMs = gap,
                ),
            )
        }
    }

    companion object {
        const val MAX_TAKES = 5
        const val MAX_GAP_MS = 60_000L
        const val LOOP_GAP_MS = 280L
        const val PLAY_SETTLE_MS = 16L
        const val TAP_TAIL_MS = 24L
        const val SIDEBAR_TAIL_MS = 240L
        const val RECENTS_SETTLE_MS = 560L
        const val SCENE_SETTLE_MS = 380L
        const val KEY_DEDUP_MS = 40L
        const val KEY_PLAY_MS = 90L
        const val TEXT_COALESCE_MS = 480L
        const val TEXT_CHAR_MS = 56L
        const val TEXT_MIN_MS = 200L
        const val TEXT_MAX_MS = 2_400L
        const val MAX_SWIPE_MS = 4_000L

        fun textPlayMs(length: Int): Long =
            (length.coerceAtLeast(1) * TEXT_CHAR_MS).coerceIn(TEXT_MIN_MS, TEXT_MAX_MS)

        fun playSwipeMs(recordedMs: Long): Long =
            recordedMs.coerceIn(TouchPadMath.SCREEN_SWIPE_MIN_MS, MAX_SWIPE_MS)
    }
}
