package com.example.ava.sendspin

/**
 * Judges a Sendspin `group/update.group_id` change.
 *
 * Every client starts in its own solo group (aiosendspin assigns a fresh UUID).
 * `add_client` is the server multicast: the joiner's `group_id` changes to the
 * leader's. After that, `stream/start` / `stream/clear` / PCM carry no
 * intended-player field — the session itself is the membership. The protocol
 * has no join-consent message, so the only proof a group change is *user-made*
 * is Music Assistant's own sync state (`synced_to` / `group_childs`): the user
 * ticked that pairing. A change without that proof is a fan-out we never asked
 * for.
 *
 * Two late verdicts exist because the proof can lag the multicast:
 * - [adoptCurrentAsShared]: MA confirmed the pairing after the id already
 *   changed (player_updated arrives on a separate socket).
 * - [adoptCurrentAsSolo]: the blocked id stayed completely quiet — no stream,
 *   no PCM, no "playing". That is the server re-homing us into a replacement
 *   solo group (typical unsync handout), not a hijack.
 */
class SendspinGroupIdentity {
    // Mutated from the WS receive thread (group/update) and from client-scope
    // coroutines (probation / self-heal / late MA proof). @Synchronized keeps
    // each transition atomic; @Volatile keeps the hot [blocked] gate reads
    // (PCM path, command path) from seeing a stale value.
    @Volatile
    var soloGroupId: String? = null
        private set

    @Volatile
    var currentGroupId: String? = null
        private set

    @Volatile
    var blocked: Boolean = false
        private set

    /** [markLeavingForeign] was sent; the next **new** id is our replacement solo. */
    @Volatile
    var expectingNewSolo: Boolean = false
        private set

    enum class Decision {
        SOLO,
        UNCHANGED,
        ADOPT_NEW_SOLO,
        /** User-made pairing (MA sync proves it) — join the shared group. */
        ADOPT_SHARED,
        FOREIGN,
    }

    @Synchronized
    fun reset() {
        soloGroupId = null
        currentGroupId = null
        blocked = false
        expectingNewSolo = false
    }

    /**
     * [userSanctioned] must be true when the user provably made this grouping
     * (MA sync state), and also when no arbiter exists at all (device without
     * a Music Assistant connection) — only a live "not grouped" vetoes.
     */
    @Synchronized
    fun onGroupId(groupId: String, userSanctioned: Boolean): Decision {
        val id = groupId.trim()
        if (id.isEmpty()) return Decision.UNCHANGED

        if (soloGroupId == null) {
            soloGroupId = id
            currentGroupId = id
            blocked = false
            expectingNewSolo = false
            return Decision.SOLO
        }

        if (id == currentGroupId) {
            // Still the same room. Do not treat this as the replacement solo —
            // leave may not have landed yet, and adopting would unblock a
            // foreign group that is still playing.
            return Decision.UNCHANGED
        }

        currentGroupId = id

        if (expectingNewSolo) {
            return adoptNewSolo(id)
        }

        if (id == soloGroupId) {
            blocked = false
            expectingNewSolo = false
            return Decision.SOLO
        }

        if (userSanctioned) {
            blocked = false
            expectingNewSolo = false
            return Decision.ADOPT_SHARED
        }

        blocked = true
        return Decision.FOREIGN
    }

    /** MA pairing proof arrived after the id change. Returns true if unblocked. */
    @Synchronized
    fun adoptCurrentAsShared(): Boolean {
        if (!blocked) return false
        blocked = false
        expectingNewSolo = false
        return true
    }

    /**
     * The blocked id never streamed at us: it is our replacement solo group,
     * not someone's room. Returns true if unblocked.
     */
    @Synchronized
    fun adoptCurrentAsSolo(): Boolean {
        if (!blocked) return false
        soloGroupId = currentGroupId
        blocked = false
        expectingNewSolo = false
        return true
    }

    @Synchronized
    fun markLeavingForeign() {
        expectingNewSolo = true
    }

    private fun adoptNewSolo(id: String): Decision {
        soloGroupId = id
        blocked = false
        expectingNewSolo = false
        return Decision.ADOPT_NEW_SOLO
    }
}
