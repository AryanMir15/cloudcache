package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lagradost.safefile.SafeFile

/**
 * Cheap magic-byte validation for downloaded video files.
 *
 * The downloader never validates content (HTML/JSON error pages or m3u8
 * playlists saved as .mp4 pass every size check), and ExoPlayer reports
 * unrecognizable input as ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003),
 * which surfaces in the UI as a confusing "No Links Found" toast. Both the
 * play gate (DownloadButtonSetup) and the downloader completion path use
 * this to reject garbage before it ever reaches the player.
 */
object MediaFileSniffer {
    private const val TAG = "MediaFileSniffer"
    const val HEAD_SIZE = 64

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

    private fun readHead(input: java.io.InputStream): ByteArray {
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
     * True when [head] can be sniffed by UpdatedDefaultExtractorsFactory
     * (mp4/mov, mkv/webm, ts, avi, flv, ogg, wav, flac, mp3/adts, ac3, mpeg-ps, mpeg audio).
     * null (unreadable) → true: can't prove it's bad, let the player decide.
     */
    fun looksLikeMedia(head: ByteArray?): Boolean {
        if (head == null) return true
        // empty or truncated beyond use — no container can start like this
        if (head.size < 4) return false

        fun ascii(offset: Int, length: Int): String =
            String(head, offset, length, Charsets.ISO_8859_1)

        // MP4 / MOV / fragmented MP4 — common top-level boxes at offset 4
        if (head.size >= 8) {
            when (ascii(4, 4)) {
                "ftyp", "styp", "moov", "mdat", "free", "skip", "wide", "pnot",
                "moof", "sidx", "mfra" -> return true
            }
        }
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

    /** Hex log of [head] for debugging blocked files. */
    fun headToHex(head: ByteArray?): String {
        if (head == null) return "null"
        if (head.isEmpty()) return "empty"
        return head.joinToString(" ") { "%02X".format(it) }
    }
}
