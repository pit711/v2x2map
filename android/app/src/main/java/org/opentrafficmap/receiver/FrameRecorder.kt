package org.opentrafficmap.receiver

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes received frames as a standard PCAP file (libpcap format, link type 105 =
 * LINKTYPE_IEEE802_11 raw 802.11 frames). Files can be opened directly in Wireshark.
 *
 * Output path: Android/data/org.opentrafficmap.receiver/files/  (no extra permission)
 */
class FrameRecorder(private val context: Context) {

    private val tag = "FrameRecorder"
    private var stream: BufferedOutputStream? = null
    var file: File? = null
        private set
    var frameCount: Int = 0
        private set

    val isRecording: Boolean get() = stream != null

    fun start(): File? {
        if (stream != null) return file
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "itsg5-$ts.pcap")
        return try {
            val out = BufferedOutputStream(FileOutputStream(f))
            writePcapGlobalHeader(out)
            out.flush()
            stream = out
            file = f
            frameCount = 0
            Log.i(tag, "recording to ${f.absolutePath}")
            f
        } catch (e: Exception) {
            Log.w(tag, "open failed", e)
            null
        }
    }

    fun stop(): File? {
        val s = stream ?: return null
        return try {
            s.flush(); s.close()
            stream = null
            Log.i(tag, "stopped after $frameCount frames → ${file?.absolutePath}")
            file
        } catch (e: Exception) {
            Log.w(tag, "close failed", e); null
        }
    }

    @Synchronized
    fun append(frame: Frame) {
        val s = stream ?: return
        try {
            writePcapPacket(s, frame)
            frameCount++
        } catch (e: Exception) {
            Log.w(tag, "write failed", e)
            stop()
        }
    }

    // ── PCAP format ────────────────────────────────────────────────────────

    /** 24-byte global header (little-endian). Link type 105 = LINKTYPE_IEEE802_11. */
    private fun writePcapGlobalHeader(out: BufferedOutputStream) {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(PCAP_MAGIC)      // magic number (LE)
        buf.putShort(2)             // version major
        buf.putShort(4)             // version minor
        buf.putInt(0)               // GMT offset
        buf.putInt(0)               // timestamp accuracy
        buf.putInt(65535)           // snaplen
        buf.putInt(LINK_IEEE80211)  // link type: raw 802.11 (no radiotap)
        out.write(buf.array())
    }

    /** 16-byte per-packet header + payload. */
    private fun writePcapPacket(out: BufferedOutputStream, frame: Frame) {
        val len = frame.payload.size
        val hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(frame.wallTime.epochSecond.toInt())   // ts_sec
        hdr.putInt(frame.wallTime.nano)  // ts_usec
        hdr.putInt(len)                 // incl_len
        hdr.putInt(len)                 // orig_len
        out.write(hdr.array())
        out.write(frame.payload)
    }

    companion object {
        private val PCAP_MAGIC     = 0xA1B23C4D.toInt()
        private const val LINK_IEEE80211 = 105
    }
}
