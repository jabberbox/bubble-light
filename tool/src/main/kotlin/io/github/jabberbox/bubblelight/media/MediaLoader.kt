package io.github.jabberbox.bubblelight.media

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A picture attached to a message, identified by the server's attachment guid. */
data class PhotoRef(val guid: String, val width: Int, val height: Int)

/**
 * Turns a [PhotoRef] into pixels: download it, keep the file on disk, and decode it at the size actually
 * needed. Downloads are limited to a few at once so a chat full of photos does not swamp the connection.
 *
 * The download and decode approach follows craigeley/chat (MIT), https://github.com/craigeley/chat.
 */
class MediaLoader(
    private val cache: MediaCache,
    private val download: (guid: String, dest: File) -> Unit,
    private val log: (String) -> Unit,
) {
    private val dir get() = cache.dir
    private val gate = Semaphore(3)
    private val fileLocks = HashMap<String, Mutex>()

    /** Decoded bitmaps by size, capped at an eighth of the app's memory. */
    private val memory = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }

    init {
        trimDisk()
    }

    /** The image decoded to at most [maxWidthPx] wide, or null if it cannot be loaded. Never throws for ordinary failures. */
    suspend fun bitmap(ref: PhotoRef, maxWidthPx: Int): ImageBitmap? {
        val key = "${ref.guid}@$maxWidthPx"
        memory.get(key)?.let { return it }
        return try {
            val file = original(ref)
            val decoded = withContext(Dispatchers.Default) { decode(file, maxWidthPx) }
            decoded?.also { memory.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("photo failed: ${e::class.simpleName}")
            null
        }
    }

    private suspend fun original(ref: PhotoRef): File {
        val file = cache.fileFor(ref.guid)
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val lock = synchronized(fileLocks) { fileLocks.getOrPut(file.name) { Mutex() } }
        return lock.withLock {
            if (file.exists() && file.length() > 0) return@withLock file
            gate.withPermit {
                withContext(Dispatchers.IO) {
                    // Write to a temp file first so a dropped connection never leaves half an image that looks complete.
                    val temp = File(dir, "${file.name}.part")
                    try {
                        download(ref.guid, temp)
                        temp.renameTo(file)
                    } finally {
                        temp.delete()
                    }
                }
            }
            file
        }
    }

    /** Decodes with subsampling so a 12-megapixel photo never becomes a 48 MB bitmap, then applies the EXIF rotation. */
    private fun decode(file: File, maxWidthPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        // Sizes only, never content: enough to tell from the log whether a photo came out the wrong shape.
        log("photo ${bounds.outWidth}x${bounds.outHeight} exif=$orientation shown at ${bitmap.width}x${bitmap.height}")
        return ImageOps.rotate(bitmap, orientation).asImageBitmap()
    }

    /** Keeps the cache under a size limit by deleting the least recently used files. */
    private fun trimDisk() {
        Thread {
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return@Thread
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= MAX_CACHE_BYTES) break
                total -= f.length()
                f.delete()
            }
        }.start()
    }

    private companion object {
        const val MAX_CACHE_BYTES = 200L * 1024 * 1024
    }
}
