package app.fonte.install

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ABIs an APK carries native code for ("lib/<abi>/…"), read from the last bytes of the file: the end record
 * and the central directory of the ZIP. A ranged request for those bytes avoids downloading a build for the
 * wrong ABI.
 */
object ZipAbis {
    private const val EOCD = 0x06054b50
    private const val CENTRAL = 0x02014b50

    /** Null when [tail] does not hold the whole central directory. An empty set: no native code (any ABI). */
    fun fromTail(tail: ByteArray, fileSize: Long): Set<String>? {
        if (tail.size < 22) return null
        val b = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var end = -1
        for (i in tail.size - 22 downTo maxOf(0, tail.size - 22 - 0xffff)) {
            if (b.getInt(i) == EOCD) {
                end = i
                break
            }
        }
        if (end < 0) return null
        val size = b.getInt(end + 12).toLong() and 0xffffffffL
        val offset = b.getInt(end + 16).toLong() and 0xffffffffL
        val start = offset - (fileSize - tail.size)
        if (start < 0 || start + size > end) return null
        val abis = HashSet<String>()
        var p = start.toInt()
        val stop = (start + size).toInt()
        while (p + 46 <= stop && b.getInt(p) == CENTRAL) {
            val nameLen = b.getShort(p + 28).toInt() and 0xffff
            val extraLen = b.getShort(p + 30).toInt() and 0xffff
            val commentLen = b.getShort(p + 32).toInt() and 0xffff
            if (p + 46 + nameLen > tail.size) return null
            val name = String(tail, p + 46, nameLen, Charsets.UTF_8)
            if (name.startsWith("lib/")) name.split('/').getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { abis += it }
            p += 46 + nameLen + extraLen + commentLen
        }
        return abis
    }

    /** The total size from a Content-Range header ("bytes 100-199/12345"). */
    fun totalFromContentRange(header: String?): Long? = header?.substringAfterLast('/')?.trim()?.toLongOrNull()
}
