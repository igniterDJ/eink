package com.keychain.epd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** Persists every processed image (frame + thumbnail) to disk so the user can browse and reselect past uploads. */
object HistoryStore {

    private const val DIR_NAME = "history"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    data class HistoryEntry(val id: Long, val pngFile: File, val frameFile: File)

    fun save(context: Context, frame: ByteArray, preview: Bitmap) {
        val id = System.currentTimeMillis()
        val d = dir(context)
        File(d, "$id.frame").writeBytes(frame)
        FileOutputStream(File(d, "$id.png")).use { out ->
            preview.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    fun list(context: Context): List<HistoryEntry> {
        val d = dir(context)
        val frames = d.listFiles { f -> f.name.endsWith(".frame") } ?: emptyArray()
        return frames.mapNotNull { frameFile ->
            val id = frameFile.name.removeSuffix(".frame").toLongOrNull() ?: return@mapNotNull null
            val pngFile = File(d, "$id.png")
            if (!pngFile.exists()) return@mapNotNull null
            HistoryEntry(id, pngFile, frameFile)
        }.sortedWith(compareByDescending<HistoryEntry> { isStarred(it) }.thenByDescending { it.id })
    }

    fun loadFrame(entry: HistoryEntry): ByteArray = entry.frameFile.readBytes()

    fun loadThumbnail(entry: HistoryEntry): Bitmap? = BitmapFactory.decodeFile(entry.pngFile.path)

    private fun starFile(entry: HistoryEntry): File = File(entry.frameFile.parentFile, "${entry.id}.star")

    fun isStarred(entry: HistoryEntry): Boolean = starFile(entry).exists()

    fun setStarred(entry: HistoryEntry, starred: Boolean) {
        val f = starFile(entry)
        if (starred) f.createNewFile() else f.delete()
    }

    fun delete(entry: HistoryEntry) {
        entry.pngFile.delete()
        entry.frameFile.delete()
        starFile(entry).delete()
    }
}
