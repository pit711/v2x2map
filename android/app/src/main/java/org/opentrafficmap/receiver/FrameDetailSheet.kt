package org.opentrafficmap.receiver

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object FrameDetailSheet {

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun show(context: Context, f: Frame) {
        val sheet = BottomSheetDialog(context)
        val view  = LayoutInflater.from(context).inflate(R.layout.dialog_frame_detail, null)
        sheet.setContentView(view)

        // --- title row ---
        view.findViewById<View>(R.id.detailDot)
            .backgroundTintList = android.content.res.ColorStateList.valueOf(f.msgType.color)
        view.findViewById<TextView>(R.id.detailTitle).text = titleFor(f)
        view.findViewById<TextView>(R.id.detailSize).text  = "${f.len} B"

        // --- metadata block ---
        view.findViewById<TextView>(R.id.detailMeta).text = buildMeta(f)

        // --- hex dump ---
        view.findViewById<TextView>(R.id.detailHex).text = hexDump(f.payload)

        sheet.show()
    }

    // -----------------------------------------------------------------------

    private fun titleFor(f: Frame): String {
        val typeName = when (f.msgType) {
            ItsG5Decoder.MsgType.CAM    -> "CAM  –  Fahrzeug"
            ItsG5Decoder.MsgType.DENM   -> "DENM  –  Gefahrenhinweis"
            ItsG5Decoder.MsgType.MAPEM  -> "MAPEM  –  Kreuzungsgeometrie"
            ItsG5Decoder.MsgType.SPATEM -> "SPATEM  –  Ampelphasen"
            ItsG5Decoder.MsgType.IVIM   -> "IVIM  –  Fahrzeuginfo"
            ItsG5Decoder.MsgType.SREM   -> "SREM  –  Signalanfrage"
            ItsG5Decoder.MsgType.SSEM   -> "SSEM  –  Signalstatus"
            ItsG5Decoder.MsgType.TLM    -> "TLM  –  Ampelsteuerung"
            ItsG5Decoder.MsgType.RTCMEM -> "RTCMEM  –  GNSS-Korrekturdaten"
            ItsG5Decoder.MsgType.UNKNOWN -> when (f.etherType) {
                null   -> "Unbekannt"
                0x8947 -> "ITS-G5 (unbekannter BTP-Port)"
                else   -> "EtherType 0x%04x".format(f.etherType)
            }
        }
        return typeName
    }

    private fun buildMeta(f: Frame): String {
        val sb = StringBuilder()

        fun row(label: String, value: String) {
            sb.append("%-12s  %s\n".format(label, value))
        }

        row("Frame",    "#${f.seq}")
        row("Zeit",     timeFmt.format(Date()) +
                        "  (ts ${f.sec}.%06d s)".format(f.usec))
        row("EtherType", when (f.etherType) {
            null   -> "—"
            0x8947 -> "0x8947  ITS-G5"
            else   -> "0x%04x".format(f.etherType)
        })

        if (f.stationId != null) {
            // Show full 8-byte GN address as hex
            row("Station",  "%016x".format(f.stationId))
        }

        val ll = f.latLon
        if (ll != null) {
            val latStr = "%.6f° %s".format(abs(ll.first),  if (ll.first  >= 0) "N" else "S")
            val lonStr = "%.6f° %s".format(abs(ll.second), if (ll.second >= 0) "E" else "W")
            row("Position", "$latStr   $lonStr")
        } else {
            row("Position", "—")
        }

        if (f.speedMps != null && f.speedMps > 0.0) {
            row("Speed",    "%.1f km/h".format(f.speedMps * 3.6))
        }
        if (f.headingDeg != null) {
            row("Heading",  "%.1f°  %s".format(f.headingDeg, compassPoint(f.headingDeg)))
        }

        return sb.toString().trimEnd()
    }

    private fun hexDump(data: ByteArray): String {
        val sb = StringBuilder()
        var off = 0
        while (off < data.size) {
            sb.append("%04x  ".format(off))
            val end = minOf(off + 16, data.size)
            for (i in off until end) {
                sb.append("%02x ".format(data[i].toInt() and 0xFF))
                if (i - off == 7) sb.append(' ')
            }
            // pad short last line
            val pad = 16 - (end - off)
            repeat(pad) { sb.append("   ") }
            if (pad > 8) sb.append(' ')
            sb.append(" |")
            for (i in off until end) {
                val c = data[i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append("|\n")
            off = end
        }
        return sb.toString().trimEnd()
    }

    private fun compassPoint(deg: Double): String {
        val norm = ((deg % 360) + 360) % 360
        val idx  = ((norm + 22.5) / 45).toInt() % 8
        return arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[idx]
    }
}
