package com.example.ava.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The continuous-conversation rule actually in force.
 *
 * 超级智能 needs the cloud model, so the stored choice only applies while the
 * AI path is cloud. On any other path the 热词 rule runs, and the stored
 * choice comes back by itself when the path returns to cloud. Picking 热词 or
 * 问号 explicitly is what clears it (SettingsViewModel).
 */
enum class ContinueMode {
    SMART,
    QUESTION_MARK,
    EXIT_KEYWORD;

    companion object {
        fun resolve(smartStored: Boolean, questionMarkStored: Boolean, cloudPath: Boolean): ContinueMode = when {
            smartStored && cloudPath -> SMART
            questionMarkStored -> QUESTION_MARK
            else -> EXIT_KEYWORD
        }

        fun flow(player: Flow<PlayerSettings>, llm: Flow<LocalLlmSettings>): Flow<ContinueMode> =
            combine(player, llm) { p, l -> p.continueMode(l) }
    }
}

fun PlayerSettings.continueMode(llm: LocalLlmSettings?): ContinueMode =
    ContinueMode.resolve(
        smartStored = enableSmartContinue,
        questionMarkStored = enableQuestionMarkContinue,
        cloudPath = llm?.resolvedPath() == LocalLlmPath.REMOTE,
    )
