package com.example.ava.sendspin

enum class MediaCommandOutcome {
    ACCEPTED, IGNORED, REJECTED;

    /** Legacy UI callers ask whether the gesture was consumed, not whether a packet was sent. */
    val handled: Boolean get() = this != REJECTED
}

internal object SeekCommandDispatch {
    fun run(deadStream: Boolean, advancePending: Boolean, onIgnored: () -> Unit, send: () -> Boolean): MediaCommandOutcome {
        if (deadStream || advancePending) {
            onIgnored()
            return MediaCommandOutcome.IGNORED
        }
        return if (send()) MediaCommandOutcome.ACCEPTED else MediaCommandOutcome.REJECTED
    }
}
