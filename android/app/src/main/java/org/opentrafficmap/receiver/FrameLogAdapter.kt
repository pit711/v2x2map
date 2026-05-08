package org.opentrafficmap.receiver

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FrameLogAdapter(
    private val onItemClick: (Frame) -> Unit = {}
) : RecyclerView.Adapter<FrameLogAdapter.VH>() {

    private val items = ArrayDeque<Frame>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun prepend(frame: Frame) {
        items.addFirst(frame)
        notifyItemInserted(0)
        if (items.size > MAX) {
            items.removeLast()
            notifyItemRemoved(items.size) // size after removal = former last index
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        holder.itemView.setOnClickListener { onItemClick(f) }
        holder.time.text = fmt.format(Date())
        holder.len.text = f.len.toString()
        holder.hex.text = f.hexPreview()
        when (f.etherType) {
            null -> {
                holder.et.text = "?"
                holder.et.setTextColor(Color.parseColor("#F0883E"))
            }
            0x8947 -> {
                holder.et.text = "ITS-G5"
                holder.et.setTextColor(Color.parseColor("#7EE787"))
            }
            else -> {
                holder.et.text = "0x%04x".format(f.etherType)
                holder.et.setTextColor(Color.parseColor("#8B949E"))
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.fTime)
        val et: TextView = v.findViewById(R.id.fEt)
        val len: TextView = v.findViewById(R.id.fLen)
        val hex: TextView = v.findViewById(R.id.fHex)
    }

    companion object { const val MAX = 500 }
}
