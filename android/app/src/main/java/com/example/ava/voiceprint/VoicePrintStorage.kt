package com.example.ava.voiceprint

import android.content.Context
import com.example.ava.settings.WakeWordEngine
import org.json.JSONObject
import java.io.File

object VoicePrintStorage {
    private const val DIR_NAME = "voiceprint"
    private const val META_FILE = "meta.json"

    fun engineKey(engine: WakeWordEngine): String = when (engine) {
        WakeWordEngine.MICRO_WAKE_WORD -> "micro"
        WakeWordEngine.OPEN_WAKE_WORD -> "open"
    }

    fun dir(context: Context, engine: WakeWordEngine): File {
        val root = root(context)
        val sub = File(root, engineKey(engine))
        if (!sub.exists()) {
            sub.mkdirs()
        }
        migrateLegacyIfNeeded(root, sub)
        writeMetaIfMissing(sub, engine)
        return sub
    }

    private fun root(context: Context): File {
        val root = File(context.filesDir, DIR_NAME)
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    /**
     * Pre-engine-split files lived in `files/voiceprint/user_*.vpt`. Move them once
     * into the engine dir being opened so existing enrollments keep working.
     */
    private fun migrateLegacyIfNeeded(root: File, sub: File) {
        val legacy0 = File(root, "user_0.vpt")
        val legacy1 = File(root, "user_1.vpt")
        if (!legacy0.exists() && !legacy1.exists()) return
        val destHas = File(sub, "user_0.vpt").exists() || File(sub, "user_1.vpt").exists()
        if (!destHas) {
            if (legacy0.exists()) legacy0.copyTo(File(sub, "user_0.vpt"), overwrite = false)
            if (legacy1.exists()) legacy1.copyTo(File(sub, "user_1.vpt"), overwrite = false)
            File(root, META_FILE).takeIf { it.exists() }
                ?.copyTo(File(sub, META_FILE), overwrite = false)
        }
        legacy0.delete()
        legacy1.delete()
        File(root, META_FILE).delete()
    }

    private fun writeMetaIfMissing(root: File, engine: WakeWordEngine) {
        val meta = File(root, META_FILE)
        if (meta.exists()) return
        val json = JSONObject()
            .put("version", 2)
            .put("engine", engineKey(engine))
            .put("feature", "logmel32_delta_energy")
            .put("sampleRate", 16_000)
            .put("maxUsers", 2)
        meta.writeText(json.toString())
    }

    fun hasStoredProfiles(context: Context, engine: WakeWordEngine): Boolean =
        hasUserProfile(context, 0, engine) || hasUserProfile(context, 1, engine)

    fun hasStoredProfilesAny(context: Context): Boolean =
        WakeWordEngine.entries.any { engineHasFiles(context, it) } || hasLegacyRootProfiles(context)

    fun hasUserProfile(context: Context, userIndex: Int, engine: WakeWordEngine): Boolean {
        if (userIndex !in 0..1) return false
        if (profileFile(context, engine, userIndex).exists()) return true
        return File(root(context), "user_$userIndex.vpt").exists()
    }

    private fun engineHasFiles(context: Context, engine: WakeWordEngine): Boolean =
        profileFile(context, engine, 0).exists() || profileFile(context, engine, 1).exists()

    private fun hasLegacyRootProfiles(context: Context): Boolean {
        val root = root(context)
        return File(root, "user_0.vpt").exists() || File(root, "user_1.vpt").exists()
    }

    private fun profileFile(context: Context, engine: WakeWordEngine, userIndex: Int): File =
        File(File(root(context), engineKey(engine)), "user_$userIndex.vpt")

    fun clearProfiles(context: Context) {
        WakeWordEngine.entries.forEach { clearProfiles(context, it) }
        File(root(context), "user_0.vpt").delete()
        File(root(context), "user_1.vpt").delete()
    }

    fun clearProfiles(context: Context, engine: WakeWordEngine) {
        val root = dir(context, engine)
        File(root, "user_0.vpt").delete()
        File(root, "user_1.vpt").delete()
    }

    fun clearUserProfile(context: Context, userIndex: Int, engine: WakeWordEngine) {
        if (userIndex !in 0..1) return
        File(dir(context, engine), "user_$userIndex.vpt").delete()
    }

    fun hasMinimumManualEnrollment(
        context: Context,
        engine: WakeWordEngine,
        user0Samples: Int,
        user1Samples: Int,
        requiredSamples: Int = 5,
    ): Boolean {
        val required = requiredSamples.coerceAtLeast(1)
        return hasUserProfile(context, 0, engine) || user0Samples >= required ||
            hasUserProfile(context, 1, engine) || user1Samples >= required
    }
}
