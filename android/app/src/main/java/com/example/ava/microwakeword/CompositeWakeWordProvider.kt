package com.example.ava.microwakeword

import com.example.ava.wakewordlibrary.WakeWordJsonParser
import com.example.ava.wakewordlibrary.WakeWordLibraryManager
import java.nio.ByteBuffer

class ImportedWakeWordProvider(
    private val libraryManager: WakeWordLibraryManager,
) : WakeWordProvider {

    override fun getWakeWords(): List<WakeWordWithId> {
        val base = libraryManager.importedMicroDir
        return base.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val id = dir.name
            val jsonFile = dir.resolve("$id.json")
            if (!jsonFile.exists()) return@mapNotNull null
            val wakeWord = WakeWordJsonParser.parseMicroWakeWord(jsonFile.readText())
                ?: return@mapNotNull null
            val modelFile = dir.resolve(wakeWord.model)
            if (!modelFile.exists()) return@mapNotNull null
            WakeWordWithId(id, wakeWord)
        }?.sortedBy { it.wakeWord.wake_word.lowercase() } ?: emptyList()
    }

    override fun loadWakeWordModel(model: String): ByteBuffer =
        loadModelFile(model)

    private fun loadModelFile(model: String): ByteBuffer {
        val fileName = model.substringAfterLast('/')
        val id = fileName.removeSuffix(".tflite")
        val modelFile = libraryManager.importedMicroDir.resolve(id).resolve(fileName)
        if (!modelFile.exists()) {
            error("Imported model not found: $model")
        }
        val bytes = modelFile.readBytes()
        return ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
    }
}

class CompositeWakeWordProvider(
    private val builtIn: WakeWordProvider,
    private val imported: WakeWordProvider,
) : WakeWordProvider {

    override fun getWakeWords(): List<WakeWordWithId> {
        val importedList = imported.getWakeWords()
        val importedIds = importedList.map { it.id }.toSet()
        val builtInList = builtIn.getWakeWords().filter { it.id !in importedIds }
        return builtInList + importedList
    }

    override fun loadWakeWordModel(model: String): ByteBuffer {
        val fileName = model.substringAfterLast('/')
        val id = fileName.removeSuffix(".tflite")
        val importedIds = imported.getWakeWords().map { it.id }.toSet()
        return if (id in importedIds) {
            imported.loadWakeWordModel(model)
        } else {
            builtIn.loadWakeWordModel(model)
        }
    }
}
