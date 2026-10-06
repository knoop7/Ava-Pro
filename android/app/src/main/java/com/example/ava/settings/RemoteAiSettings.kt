package com.example.ava.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire protocol. Claude messages / OpenAI chat completions / OpenAI Responses / Ollama.
 * [OPENAI_RESPONSES] is the only OpenAI wire that carries tool calls on GPT-5.4+
 * reasoning models; [OPENAI] stays for every other OpenAI-compatible server.
 */
@Serializable
enum class RemoteAiKind {
    CLAUDE,
    OPENAI,
    OLLAMA,
    OPENAI_RESPONSES,
}

@Serializable
data class RemoteAiProfile(
    val id: String,
    val kind: RemoteAiKind,
    val label: String,
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val token: String = "",
)

@Serializable
data class RemoteAiSettings(
    val selectedId: String = "claude",
    /** Display name for this instruction set. Blank shows the default label. */
    val instructionName: String = "",
    /**
     * Extra instructions appended after the host preset. Blank writes nothing.
     * Never replaces the system prompt or the live tool one-liners.
     */
    val extraPrompt: String = "",
    /** Spoken user/assistant pairs kept for the next turn. New installs start at 10. */
    val historyTurns: Int = REMOTE_AI_HISTORY_TURNS_DEFAULT,
    /** Built-in families. Missing / true = on (OpenClaw Mini skill default). */
    val toolsHa: Boolean = true,
    val toolsMusic: Boolean = true,
    val toolsWeb: Boolean = true,
    val toolsSelf: Boolean = true,
    val toolsVoice: Boolean = true,
    val toolsPhone: Boolean = true,
    /**
     * Speak sentence by sentence while the model is still writing. Off by
     * default: the one-shot path is the proven one, and a provider that
     * mishandles SSE would otherwise break every turn.
     */
    val streaming: Boolean = false,
    /**
     * Leave a thinking model's chain of thought on. Off asks hosts that
     * understand it to skip the think block. On sends no extra field, so a
     * gateway that rejects unknown keys keeps working.
     */
    val thinking: Boolean = true,
    /**
     * When on, slot 1 is the voice primary. Later slots that are filled are
     * tried in order after the current model fails twice. Off: [selectedId] only.
     */
    val fallbackEnabled: Boolean = false,
    /** Bumped once tokens on disk are sealed by [SecretBox]; forces the migrating write. */
    val secretsVersion: Int = 0,
    val profiles: List<RemoteAiProfile> = RemoteAiSettings.defaults(),
) {
    fun selected(): RemoteAiProfile? {
        val picked = profiles.firstOrNull { it.id == selectedId } ?: return null
        return picked.takeIf { it.ready() }
    }

    companion object {
        fun defaults(): List<RemoteAiProfile> = listOf(
            RemoteAiProfile(
                id = "claude",
                kind = RemoteAiKind.CLAUDE,
                label = "Anthropic",
                baseUrl = "https://api.anthropic.com",
            ),
            RemoteAiProfile(
                id = "openai",
                kind = RemoteAiKind.OPENAI,
                label = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
            ),
            RemoteAiProfile(
                id = "ollama",
                kind = RemoteAiKind.OLLAMA,
                label = "Ollama",
                baseUrl = "http://127.0.0.1:11434",
            ),
        )
    }
}

const val REMOTE_AI_SLOT_MAX = 5
const val REMOTE_AI_HISTORY_TURNS_DEFAULT = 10
const val REMOTE_AI_HISTORY_TURNS_MIN = 3
const val REMOTE_AI_HISTORY_TURNS_MAX = 30

fun snapRemoteHistoryTurns(value: Int): Int = when {
    value <= 0 -> 0
    value < REMOTE_AI_HISTORY_TURNS_MIN -> REMOTE_AI_HISTORY_TURNS_MIN
    value > REMOTE_AI_HISTORY_TURNS_MAX -> REMOTE_AI_HISTORY_TURNS_MAX
    else -> value
}

fun RemoteAiKind.defaultBaseUrl(): String = when (this) {
    RemoteAiKind.CLAUDE -> "https://api.anthropic.com"
    RemoteAiKind.OPENAI, RemoteAiKind.OPENAI_RESPONSES -> "https://api.openai.com/v1"
    RemoteAiKind.OLLAMA -> "http://127.0.0.1:11434"
}

fun remoteAiSlotId(index: Int): String = index.toString()

fun RemoteAiProfile.slotIndexOrNull(): Int? =
    id.toIntOrNull()?.takeIf { it in 1..REMOTE_AI_SLOT_MAX }

fun RemoteAiSettings.slotList(): List<RemoteAiProfile> {
    val numbered = profiles.mapNotNull { profile ->
        val index = profile.slotIndexOrNull() ?: return@mapNotNull null
        index to profile.copy(label = index.toString())
    }.sortedBy { it.first }.map { it.second }
    if (numbered.isNotEmpty()) return numbered.take(REMOTE_AI_SLOT_MAX)
    val picked = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    val seed = picked ?: RemoteAiProfile(
        id = remoteAiSlotId(1),
        kind = RemoteAiKind.CLAUDE,
        label = "1",
        baseUrl = RemoteAiKind.CLAUDE.defaultBaseUrl(),
    )
    return listOf(seed.copy(id = remoteAiSlotId(1), label = "1"))
}

fun RemoteAiProfile.ready(): Boolean {
    if (baseUrl.isBlank() || model.isBlank()) return false
    return kind == RemoteAiKind.OLLAMA || token.isNotBlank()
}

/** Kind + host + model + token of the selected slot. Slot id is not the wire. */
fun RemoteAiProfile.wireKey(): String =
    listOf(kind.name, baseUrl.trim().trimEnd('/'), model.trim(), token).joinToString("\u0001")

fun RemoteAiSettings.pickedProfile(): RemoteAiProfile? =
    profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()

fun RemoteAiSettings.wireKey(): String = pickedProfile()?.wireKey().orEmpty()

/** Slot used for the live voice seat. Continuation on: always slot 1. */
fun RemoteAiSettings.voiceProfile(): RemoteAiProfile? =
    if (fallbackEnabled) slotList().firstOrNull()?.takeIf { it.ready() } else selected()

fun RemoteAiSettings.voiceWireKey(): String = voiceProfile()?.wireKey().orEmpty()

/** Primary first, then each later slot that is filled. Empty when none are ready. */
fun RemoteAiSettings.failoverChain(): List<RemoteAiProfile> {
    if (!fallbackEnabled) return listOfNotNull(selected())
    val slots = slotList()
    val first = slots.firstOrNull()?.takeIf { it.ready() } ?: return emptyList()
    return listOf(first) + slots.drop(1).filter { it.ready() }
}

/** Log line only — never the token. */
fun RemoteAiProfile.wireLabel(): String {
    val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
        ?.ifBlank { null }
        ?: baseUrl.trim().ifBlank { "-" }
    return "${kind.name.lowercase()} model=${model.trim()} host=$host"
}

fun RemoteAiSettings.wireLabel(): String = pickedProfile()?.wireLabel() ?: "none"

/**
 * Tokens are sealed with [SecretBox] on the way to disk and opened on the way
 * back, so everything above the serializer keeps working with plain strings.
 */
class RemoteAiSettingsSerializer : Serializer<RemoteAiSettings> {
    private val inner = SettingsSerializer(RemoteAiSettings.serializer(), RemoteAiSettings())
    override val defaultValue: RemoteAiSettings = RemoteAiSettings()

    override suspend fun readFrom(input: InputStream): RemoteAiSettings {
        val stored = inner.readFrom(input)
        return stored.copy(profiles = stored.profiles.map { it.copy(token = SecretBox.open(it.token)) })
    }

    override suspend fun writeTo(t: RemoteAiSettings, output: OutputStream) {
        inner.writeTo(
            t.copy(
                secretsVersion = SECRETS_VERSION,
                profiles = t.profiles.map { it.copy(token = SecretBox.seal(it.token)) },
            ),
            output,
        )
    }

    companion object {
        const val SECRETS_VERSION = 1
    }
}

val Context.remoteAiSettingsStore: DataStore<RemoteAiSettings> by dataStore(
    fileName = "remote_ai_settings.json",
    serializer = RemoteAiSettingsSerializer(),
    corruptionHandler = defaultCorruptionHandler(RemoteAiSettings()),
)

class RemoteAiSettingsStore(dataStore: DataStore<RemoteAiSettings>) :
    SettingsStoreImpl<RemoteAiSettings>(dataStore, RemoteAiSettings()) {

    /**
     * Files written before tokens were sealed still hold them in clear. One
     * write through the serializer seals them; the version bump is what makes
     * DataStore treat the otherwise identical value as a change.
     */
    suspend fun sealSecretsOnce() {
        val current = get()
        if (current.secretsVersion >= RemoteAiSettingsSerializer.SECRETS_VERSION) return
        if (current.profiles.none { it.token.isNotBlank() }) return
        update { it.copy(secretsVersion = RemoteAiSettingsSerializer.SECRETS_VERSION) }
    }

    val selectedId = SettingState(getFlow().map { it.selectedId }) { value ->
        update { it.copy(selectedId = value.trim()) }
    }

    val instructionName = SettingState(getFlow().map { it.instructionName }) { value ->
        update { it.copy(instructionName = value.trim()) }
    }

    val extraPrompt = SettingState(getFlow().map { it.extraPrompt }) { value ->
        update { it.copy(extraPrompt = value) }
    }

    val toolsHa = SettingState(getFlow().map { it.toolsHa }) { value ->
        update { it.copy(toolsHa = value) }
    }
    val toolsMusic = SettingState(getFlow().map { it.toolsMusic }) { value ->
        update { it.copy(toolsMusic = value) }
    }
    val toolsWeb = SettingState(getFlow().map { it.toolsWeb }) { value ->
        update { it.copy(toolsWeb = value) }
    }
    val toolsSelf = SettingState(getFlow().map { it.toolsSelf }) { value ->
        update { it.copy(toolsSelf = value) }
    }
    val toolsVoice = SettingState(getFlow().map { it.toolsVoice }) { value ->
        update { it.copy(toolsVoice = value) }
    }
    val toolsPhone = SettingState(getFlow().map { it.toolsPhone }) { value ->
        update { it.copy(toolsPhone = value) }
    }

    val historyTurns = SettingState(getFlow().map { it.historyTurns }) { value ->
        update { it.copy(historyTurns = snapRemoteHistoryTurns(value)) }
    }

    val streaming = SettingState(getFlow().map { it.streaming }) { value ->
        update { it.copy(streaming = value) }
    }

    val thinking = SettingState(getFlow().map { it.thinking }) { value ->
        update { it.copy(thinking = value) }
    }

    val fallbackEnabled = SettingState(getFlow().map { it.fallbackEnabled }) { value ->
        update { it.copy(fallbackEnabled = value) }
    }

    suspend fun ensureNumberedSlots() {
        update { current ->
            val slots = current.slotList()
            val selected = slots.firstOrNull { it.id == current.selectedId }?.id
                ?: slots.first().id
            if (current.profiles == slots && current.selectedId == selected) current
            else current.copy(profiles = slots, selectedId = selected)
        }
    }

    suspend fun upsert(profile: RemoteAiProfile) {
        update { current ->
            val slots = current.slotList().toMutableList()
            val saved = profile.copy(
                label = profile.slotIndexOrNull()?.toString() ?: profile.label,
            )
            val i = slots.indexOfFirst { it.id == saved.id }
            if (i >= 0) slots[i] = saved
            else if (slots.size < REMOTE_AI_SLOT_MAX) slots += saved
            current.copy(profiles = slots, selectedId = saved.id)
        }
    }

    suspend fun addSlot(): String? {
        var created: String? = null
        update { current ->
            val slots = current.slotList()
            if (slots.size >= REMOTE_AI_SLOT_MAX) return@update current
            val index = slots.size + 1
            val kind = slots.last().kind
            val next = RemoteAiProfile(
                id = remoteAiSlotId(index),
                kind = kind,
                label = index.toString(),
                baseUrl = kind.defaultBaseUrl(),
            )
            created = next.id
            current.copy(profiles = slots + next, selectedId = next.id)
        }
        return created
    }

    suspend fun removeSlot(id: String) {
        update { current ->
            val slots = current.slotList()
            val index = slots.indexOfFirst { it.id == id }
            if (index <= 0) return@update current
            val kept = slots.filterIndexed { i, _ -> i != index }
            val renumbered = kept.mapIndexed { i, profile ->
                val n = i + 1
                profile.copy(id = remoteAiSlotId(n), label = n.toString())
            }
            val pick = (index - 1).coerceAtLeast(0)
            current.copy(
                profiles = renumbered,
                selectedId = renumbered.getOrNull(pick)?.id ?: renumbered.first().id,
            )
        }
    }
}
