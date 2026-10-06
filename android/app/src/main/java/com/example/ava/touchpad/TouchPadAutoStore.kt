package com.example.ava.touchpad

/** Compact text form for up to [TouchPadAuto.MAX_TAKES] saved recordings. */
internal object TouchPadAutoStore {
    private const val VERSION = "v2"
    private const val VERSION_V1 = "v1"
    private const val EMPTY = "-"
    private const val FIELD = '\u001e'
    private const val UNIT = '\u001f'

    fun encode(takes: List<TouchPadAutoTake?>, selected: Int): String {
        val lines = ArrayList<String>(2 + TouchPadAuto.MAX_TAKES)
        lines += VERSION
        lines += selected.coerceIn(0, TouchPadAuto.MAX_TAKES - 1).toString()
        repeat(TouchPadAuto.MAX_TAKES) { index ->
            val take = takes.getOrNull(index)
            lines += if (take == null || !take.hasContent()) EMPTY else encodeTake(take)
        }
        return lines.joinToString("\n")
    }

    fun decode(raw: String): Pair<List<TouchPadAutoTake?>, Int> {
        val blank = List<TouchPadAutoTake?>(TouchPadAuto.MAX_TAKES) { null }
        if (raw.isBlank()) return blank to 0
        val lines = raw.split('\n')
        val version = lines.firstOrNull()
        if (version != VERSION && version != VERSION_V1) return blank to 0
        val selected = lines.getOrNull(1)?.toIntOrNull()
            ?.coerceIn(0, TouchPadAuto.MAX_TAKES - 1) ?: 0
        val takes = List(TouchPadAuto.MAX_TAKES) { index ->
            val line = lines.getOrNull(2 + index) ?: return@List null
            if (line == EMPTY || line.isBlank()) null else decodeTake(line)
        }
        return takes to selected
    }

    private fun encodeTake(take: TouchPadAutoTake): String {
        val steps = take.steps.joinToString("|") { step ->
            listOf(
                step.type.name,
                step.x.toString(),
                step.y.toString(),
                step.x2.toString(),
                step.y2.toString(),
                step.durationMs.toString(),
                cleanExtra(step.extra),
                step.keyCode.toString(),
            ).joinToString(UNIT.toString())
        }
        val windows = take.sceneWindows.joinToString(",") { clean(it) }
        return listOf(
            clean(take.sceneRoute),
            clean(take.scenePackage),
            windows,
            steps,
        ).joinToString(FIELD.toString())
    }

    private fun decodeTake(line: String): TouchPadAutoTake? {
        val fields = line.split(FIELD)
        if (fields.size < 3) return null
        val hasWindows = fields.size >= 4
        val stepField = if (hasWindows) fields[3] else fields[2]
        val windows = if (hasWindows && fields[2].isNotBlank()) {
            fields[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val steps = stepField.split('|').mapNotNull { token ->
            val parts = token.split(UNIT)
            if (parts.size < 6) return@mapNotNull null
            val type = runCatching { TouchPadAutoStepType.valueOf(parts[0]) }.getOrNull()
                ?: return@mapNotNull null
            TouchPadAutoStep(
                type = type,
                x = parts[1].toFloatOrNull() ?: return@mapNotNull null,
                y = parts[2].toFloatOrNull() ?: return@mapNotNull null,
                x2 = parts[3].toFloatOrNull() ?: return@mapNotNull null,
                y2 = parts[4].toFloatOrNull() ?: return@mapNotNull null,
                durationMs = parts[5].toLongOrNull() ?: return@mapNotNull null,
                extra = parts.getOrNull(6).orEmpty(),
                keyCode = parts.getOrNull(7)?.toIntOrNull() ?: 0,
            )
        }
        val take = TouchPadAutoTake(
            steps = steps,
            sceneRoute = fields[0],
            scenePackage = fields[1],
            sceneWindows = windows,
        )
        return take.takeIf { it.hasContent() }
    }

    private fun clean(value: String): String =
        value.replace('\n', ' ').replace(FIELD, ' ').replace(UNIT, ' ').replace(',', ' ')

    private fun cleanExtra(value: String): String =
        value.replace('\n', ' ').replace(FIELD, ' ').replace(UNIT, ' ')
}
