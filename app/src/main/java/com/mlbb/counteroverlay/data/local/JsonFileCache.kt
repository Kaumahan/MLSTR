package com.mlbb.counteroverlay.data.local

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Small crash-safe key -> JSON text store in app-private storage.
 * The file's modification time doubles as the "fetched at" timestamp.
 */
class JsonFileCache(context: Context) {

    data class Entry(val text: String, val savedAtMillis: Long)

    private val dir = File(context.filesDir, "api_cache").apply { mkdirs() }

    private fun file(key: String) = AtomicFile(File(dir, "$key.json"))

    suspend fun read(key: String): Entry? = withContext(Dispatchers.IO) {
        val f = file(key)
        try {
            val text = f.readFully().toString(Charsets.UTF_8)
            if (text.isBlank()) null else Entry(text, f.baseFile.lastModified())
        } catch (_: IOException) {
            null
        }
    }

    suspend fun write(key: String, text: String) = withContext(Dispatchers.IO) {
        val f = file(key)
        val out = f.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: IOException) {
            f.failWrite(out)
            throw e
        }
    }
}
