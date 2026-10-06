package com.example.ava.microwakeword

import android.content.Context
import com.example.ava.openwakeword.OpenWakeWordProvider
import com.example.ava.wakewordlibrary.WakeWordLibraryManager

object WakeWordProviderFactory {

    fun microWakeWordProvider(context: Context): WakeWordProvider {
        val library = WakeWordLibraryManager.getInstance(context)
        return CompositeWakeWordProvider(
            builtIn = AssetWakeWordProvider(context.assets),
            imported = ImportedWakeWordProvider(library),
        )
    }

    fun openWakeWordProvider(context: Context): OpenWakeWordProvider {
        val library = WakeWordLibraryManager.getInstance(context)
        return OpenWakeWordProvider(
            assets = context.assets,
            importedRoot = library.importedOpenDir,
            learnStore = com.example.ava.wakelearn.WakeLearnStore.forContext(context),
        )
    }

    @Deprecated("use openWakeWordProvider", ReplaceWith("openWakeWordProvider(context)"))
    fun vsWakeWordProvider(context: Context): OpenWakeWordProvider = openWakeWordProvider(context)
}
