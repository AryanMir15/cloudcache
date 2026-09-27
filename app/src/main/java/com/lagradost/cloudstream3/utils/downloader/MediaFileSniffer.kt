package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lagradost.safefile.SafeFile
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Cheap magic-byte validation for downloaded video files.
 *
 * The downloader never validates content (HTML/JSON error pages or m3u8
 * playlists saved as .mp4 pass every size check), and ExoPlayer reports
 * unrecognizable input as ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003),
 * which surfaces in the UI as a confusing "No Links Found" toast. Both the
 * play gate (DownloadButtonSetup) and the downloader completion path use
 * this to reject garbage before it ever reaches the player.
 *
 * [looksLikeMedia] is the cheap head check; [looksLikePlayableMedia] adds
 * media3's MP4 top-level box walk (see [sniffMp4]) so files that ExoPlayer
 * rejects with NoDeclaredBrand are rejected here too.
 */
object MediaFileSniffer {
    private const val TAG = "MediaFileSniffer"
    const val HEAD_SIZE = 64

    /** media3 Sniffer.SEARCH_LENGTH — how far the MP4 box walk looks. */
    private const val SEARCH_LENGTH = 4L * 1024

    // fourcc constants (media3 Mp4Box.TYPE_*)
    private const val TYPE_FTYP = 0x66747970
    private const val TYPE_FREE = 0x66726565
    private const val TYPE_MOOV = 0x6D6F6F76
    private const val TYPE_TRAK = 0x7472616B
    private const val TYPE_MDIA = 0x6D646961
    private const val TYPE_MINF = 0x6D696E66
    private const val TYPE_MOOF = 0x6D6F6F66
    private const val TYPE_MVEX = 0x6D766578
    private const val TYPE_MDAT = 0x6D646174
    private const val TYPE_STBL = 0x7374626C

    /** media3 Sniffer.COMPATIBLE_BRANDS (heic excluded — photos, not video). */
    private val COMPATIBLE_BRANDS = intArrayOf(
        0x69736F6D, // isom
        0x69736F32, // iso2
        0x69736F33, // iso3
        0x69736F34, // iso4
        0x69736F35, // iso5
        0x69736F36, // iso6
        0x69736F39, // iso9
        0x61766331, // avc1
        0x68766331, // hvc1
        0x68657631, // hev1
        0x61763031, // av01
        0x6D703431, // mp41
        0x6D703432, // mp42
        0x33673261, // 3g2a
        0x33673262, // 3g2b
        0x33677236, // 3gr6
        0x33677336, // 3gs6
        0x33676536, // 3ge6
        0x33676736, // 3gg6
        0x4D345620, // M4V
        0x4D344120, // M4A
        0x66347620, // f4v
        0x6B646469, // kddi
        0x4D345650, // M4VP
        0x71742020, // qt
        0x4D534E56, // MSNV
        0x64627931, // dby1
        0x69736D6C, // isml
        0x70696666, // piff
    )

    /**
     * Reads the first [HEAD_SIZE] bytes of [uri].
     * Returns an empty array for empty files, or null when unreadable
     * (unresolvable/permission issues — caller should let the player decide).
     */
    fun readHead(context: Context, uri: Uri?): ByteArray? {
        if (uri == null || uri == Uri.EMPTY) return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                readHead(input)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "readHead failed for $uri: $t")
            null
        }
    }

    /** Same as [readHead] but directly from a [SafeFile] (downloader internal paths). */
    fun readHead(file: SafeFile): ByteArray? {
        return try {
            file.openInputStream()?.use { input ->
                readHead(input)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "readHead failed: $t")
            null
        }
    }

    private fun readHead(input: InputStream): ByteArray {
        val buf = ByteArray(HEAD_SIZE)
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n <= 0) break
            read += n
        }
        return buf.copyOf(read)
    }

    /**
     * True when [uri] looks playable by the media3 extractor set: the cheap
     * magic-byte check plus, for MP4-family files, media3's top-level box walk
     * (compatible ftyp brand or a top-level mdat within the first 4KB).
     * Unreadable input → true: can't prove it's bad, let the player decide.
     */
    fun looksLikePlayableMedia(context: Context, uri: Uri?, fileLength: Long?): Boolean {
        if (uri == null || uri == Uri.EMPTY) return true
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            Log.w(TAG, "looksLikePlayableMedia open failed for $uri: $t")
            null
        } ?: return true
        input.use {
            return looksLikePlayableMedia(it, fileLength?.takeIf { length -> length > 0 })
        }
    }

    /** Same as the [Context]/[Uri] overload but for downloader internal paths. */
    fun looksLikePlayableMedia(file: SafeFile): Boolean {
        val input = try {
            file.openInputStream()
        } catch (t: Throwable) {
            Log.w(TAG, "looksLikePlayableMedia open failed: $t")
            null
        } ?: return true
        val length = try {
            file.lengthOrThrow().takeIf { it > 0 }
        } catch (t: Throwable) {
            null
        }
        input.use {
            return looksLikePlayableMedia(it, length)
        }
    }

    private fun looksLikePlayableMedia(input: InputStream, fileLength: Long?): Boolean {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val head = readHead(buffered)
        // empty or truncated beyond use — no container can start like this
        if (head.size < 4) return false
        if (mp4BoxMagic(head)) {
            // re-run from offset 0 through media3's MP4 box walk
            try {
                val pushback = PushbackInputStream(buffered, head.size)
                pushback.unread(head)
                if (sniffMp4(pushback, fileLength)) return true
            } catch (t: Throwable) {
                // unreadable mid-walk (SAF hiccup) — fall through to the cheap checks
                Log.w(TAG, "sniffMp4 failed: $t")
            }
            // other extractors (TS/EBML/…) may still accept it
        }
        return nonMp4Magic(head)
    }

    /**
     * True when [head] can be sniffed by UpdatedDefaultExtractorsFactory
     * (mp4/mov, mkv/webm, ts, avi, flv, ogg, wav, flac, mp3/adts, ac3, mpeg-ps, mpeg audio).
     * null (unreadable) → true: can't prove it's bad, let the player decide.
     */
    fun looksLikeMedia(head: ByteArray?): Boolean {
        if (head == null) return true
        if (head.size < 4) return false
        return mp4BoxMagic(head) || nonMp4Magic(head)
    }

    /** common top-level MP4/MOV/fMP4 box types at offset 4 */
    private fun mp4BoxMagic(head: ByteArray): Boolean {
        if (head.size < 8) return false
        val type = String(head, 4, 4, Charsets.ISO_8859_1)
        return type == "ftyp" || type == "styp" || type == "moov" || type == "mdat" ||
                type == "free" || type == "skip" || type == "wide" || type == "pnot" ||
                type == "moof" || type == "sidx" || type == "mfra"
    }

    /** everything the cheap check accepts besides the MP4 box family */
    private fun nonMp4Magic(head: ByteArray): Boolean {
        if (head.size < 4) return false

        fun ascii(offset: Int, length: Int): String =
            String(head, offset, length, Charsets.ISO_8859_1)

        // Matroska / WebM (EBML magic)
        if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        ) return true
        // MPEG-TS — sync byte at a packet boundary (0 or 188)
        if (head[0].toInt() == 0x47) return true
        if (head.size >= 189 && head[188].toInt() == 0x47) return true
        // MPEG-PS (VOB) pack header
        if (head[0] == 0.toByte() && head[1] == 0.toByte() &&
            head[2] == 1.toByte() && head[3] == 0xBA.toByte()
        ) return true
        // FLV
        if (ascii(0, 3) == "FLV") return true
        // RIFF → AVI / WAV
        if (head.size >= 12 && ascii(0, 4) == "RIFF") {
            val inner = ascii(8, 4)
            if (inner == "AVI " || inner == "WAVE") return true
        }
        // Ogg / FLAC
        if (ascii(0, 4) == "OggS" || ascii(0, 4) == "fLaC") return true
        // MP3 (ID3 tag)
        if (head.size >= 3 && head[0] == 0x49.toByte() &&
            head[1] == 0x44.toByte() && head[2] == 0x32.toByte()
        ) return true
        // MPEG/ADTS/AAC frame sync (11 set bits)
        if ((head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) return true
        // AC-3 sync word
        if (head[0] == 0x0B.toByte() && head[1] == 0x77.toByte()) return true
        return false
    }

    /**
     * Port of androidx.media3.extractor.mp4.Sniffer.sniffInternal for the
     * unfragmented + fragmented pair (Mp4Extractor + FragmentedMp4Extractor are
     * both in the app's extractor set, so fragmentation state does not matter —
     * only "no declared brand" / malformed boxes reject in both).
     *
     * Walks top-level box headers from offset 0 of [input] (sequential read,
     * no re-seeking) within media3's 4KB search window (extended through a
     * moov box, as media3 does) and returns true iff a compatible ftyp brand
     * or a top-level mdat was seen before the window ended.
     */
    private fun sniffMp4(input: InputStream, fileLength: Long?): Boolean {
        val length = fileLength ?: -1L
        var bytesToSearch = if (length < 0 || length > SEARCH_LENGTH) SEARCH_LENGTH else length
        var bytesSearched = 0L
        var foundGoodFileType = false

        while (bytesSearched < bytesToSearch) {
            // media3: peekFully(header, allowEndOfInput=true) — EOF ends the walk
            val header = readFullyOrNull(input, 8) ?: break
            var headerSize = 8L
            var atomSize = readU32(header, 0)
            val atomType = readU32(header, 4).toInt()

            if (atomSize == 1L) {
                // 64-bit large size follows the header
                val large = readFullyOrNull(input, 8) ?: return false
                headerSize = 16L
                atomSize = readU64(large, 0)
            } else if (atomSize == 0L) {
                // box extends to the end of the file (needs a known length)
                if (length >= 0) atomSize = length - bytesSearched
            }

            if (atomSize < headerSize) {
                if (atomType == TYPE_FREE && headerSize == 8L) {
                    // media3 workaround for malformed 'free' boxes < header size
                    atomSize = headerSize
                } else {
                    // AtomSizeTooSmallSniffFailure — both extractors reject
                    return false
                }
            }
            bytesSearched += headerSize

            when (atomType) {
                TYPE_MOOV -> {
                    // media3 walks inside moov (to find mvex/stbl) and extends
                    // the search window accordingly (media3 casts to int)
                    bytesToSearch += atomSize.toInt().toLong()
                    if (length >= 0 && bytesToSearch > length) bytesToSearch = length
                    continue
                }
                TYPE_TRAK, TYPE_MDIA, TYPE_MINF -> continue
                // moof/mvex or huge stbl decide fragmentation — irrelevant for
                // us (one of the two extractors accepts either state)
                TYPE_MOOF, TYPE_MVEX -> break
                TYPE_MDAT -> foundGoodFileType = true // QuickTime files need no ftyp
            }
            if (atomType == TYPE_STBL && atomSize > 1_000_000L) break

            // peeking this box would exceed the search window (media3 stops here;
            // combined with the mdat/ftyp rules above this yields NoDeclaredBrand)
            if (bytesSearched + atomSize - headerSize >= bytesToSearch) break

            val atomDataSize = atomSize - headerSize
            bytesSearched += atomDataSize
            if (atomType == TYPE_FTYP) {
                if (atomDataSize < 8 || atomDataSize > Int.MAX_VALUE) return false
                val body = readFullyOrNull(input, atomDataSize.toInt()) ?: return false
                if (!ftypHasCompatibleBrand(body) && !foundGoodFileType) {
                    // UnsupportedBrandsSniffFailure — no compatible brand anywhere
                    return false
                }
            } else if (atomDataSize != 0L) {
                if (!skipFully(input, atomDataSize)) return false
            }
        }
        return foundGoodFileType
    }

    private fun ftypHasCompatibleBrand(body: ByteArray): Boolean {
        if (body.size < 8) return false
        if (isCompatibleBrand(readU32(body, 0).toInt())) return true
        // skip minorVersion, then check compatible brands
        var offset = 8
        while (offset + 4 <= body.size) {
            if (isCompatibleBrand(readU32(body, offset).toInt())) return true
            offset += 4
        }
        return false
    }

    private fun isCompatibleBrand(brand: Int): Boolean {
        // brands starting with '3gp' are all compatible
        if ((brand ushr 8) == 0x00336770) return true
        for (compatible in COMPATIBLE_BRANDS) {
            if (compatible == brand) return true
        }
        return false
    }

    private fun readFullyOrNull(input: InputStream, size: Int): ByteArray? {
        if (size < 0) return null
        val buf = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buf, read, size - read)
            if (n < 0) return null
            read += n
        }
        return buf
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // skip() returns 0 at EOF or on streams that cannot skip — read instead
            if (input.read() < 0) return false
            remaining--
        }
        return true
    }

    private fun readU32(b: ByteArray, offset: Int): Long =
        ((b[offset].toLong() and 0xFF) shl 24) or
                ((b[offset + 1].toLong() and 0xFF) shl 16) or
                ((b[offset + 2].toLong() and 0xFF) shl 8) or
                (b[offset + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, offset: Int): Long =
        (readU32(b, offset) shl 32) or readU32(b, offset + 4)

    /** Hex log of [head] for debugging blocked files. */
    fun headToHex(head: ByteArray?): String {
        if (head == null) return "null"
        if (head.isEmpty()) return "empty"
        return head.joinToString(" ") { "%02X".format(it) }
    }
}
