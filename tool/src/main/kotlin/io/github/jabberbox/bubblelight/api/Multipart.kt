package io.github.jabberbox.bubblelight.api

/**
 * The pieces of a `multipart/form-data` body around one file, so the file itself can be streamed from disk instead of
 * held in memory. The body is [head], then the file's bytes, then [tail].
 */
class Multipart(
    fields: Map<String, String>,
    private val fileField: String,
    private val fileName: String,
    private val mimeType: String,
    val boundary: String = "BubbleLight${System.nanoTime()}",
) {
    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    val head: ByteArray = buildString {
        for ((name, value) in fields) {
            append("--").append(boundary).append(CRLF)
            append("Content-Disposition: form-data; name=\"").append(quote(name)).append('"').append(CRLF).append(CRLF)
            append(value).append(CRLF)
        }
        append("--").append(boundary).append(CRLF)
        append("Content-Disposition: form-data; name=\"").append(quote(fileField)).append("\"; filename=\"").append(quote(fileName)).append('"').append(CRLF)
        append("Content-Type: ").append(mimeType).append(CRLF).append(CRLF)
    }.toByteArray(Charsets.UTF_8)

    val tail: ByteArray = "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)

    /** The size of the whole body for a file of [fileLength] bytes, which lets the upload skip chunking. */
    fun length(fileLength: Long): Long = head.size + fileLength + tail.size

    private fun quote(s: String): String = s.replace("\"", "%22").replace("\r", "").replace("\n", "")

    private companion object {
        const val CRLF = "\r\n"
    }
}
