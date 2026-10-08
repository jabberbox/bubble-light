package io.github.jabberbox.bubblelight.media

import java.io.File

/** The on-disk home of downloaded attachment files, keyed by attachment guid, under the tool's own files folder. */
class MediaCache(root: File) {
    val dir = File(root, "media").apply { mkdirs() }

    fun fileFor(guid: String): File = File(dir, guid.filter { it.isLetterOrDigit() }.takeLast(80).ifEmpty { "media" })

    fun clear() {
        dir.deleteRecursively()
        dir.mkdirs()
    }
}
