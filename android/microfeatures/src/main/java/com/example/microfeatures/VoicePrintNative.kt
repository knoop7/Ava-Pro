package com.example.microfeatures

object VoicePrintNative {
    const val DISABLED = 0
    const val LEARNING = 1
    const val READY = 2
    const val USER_0 = 3
    const val USER_1 = 4
    const val UNKNOWN = 5
    const val LOW_QUALITY = 6

    init {
        System.loadLibrary("microfeatures")
    }

    external fun create(): Long

    external fun destroy(handle: Long)

    external fun processWake(
        handle: Long,
        pcm16: ShortArray,
        sampleRate: Int,
        learningEnabled: Boolean,
        rejectLearning: Boolean,
        wakeConfidence: Float,
        verifyOnly: Boolean,
        storageDir: String,
        outScores: FloatArray,
    ): Int

    /** Manual guided enrollment: stores one template for [userIndex] (0 or 1). Returns [READY] on success. */
    external fun enrollSample(
        handle: Long,
        pcm16: ShortArray,
        sampleRate: Int,
        userIndex: Int,
        storageDir: String,
        outScores: FloatArray,
    ): Int

    external fun resetProfiles(handle: Long)

    external fun reloadProfiles(handle: Long, storageDir: String)

    /** Returns [LEARNING] when no templates exist (auto), [READY] when at least user 0 is enrolled. */
    external fun queryIdleState(handle: Long, storageDir: String): Int
}