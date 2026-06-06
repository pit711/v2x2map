package org.opentrafficmap.receiver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PcapFileAdapter(
    private val onUpload: (File) -> Unit
) : ListAdapter<File, PcapFileAdapter.VH>(FileDiff) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.pcapFileName)
        val info: TextView = view.findViewById(R.id.pcapFileInfo)
        val btnUpload: MaterialButton = view.findViewById(R.id.btnUploadFile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pcap_file, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = getItem(position)
        val date = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        holder.name.text = file.name
        holder.info.text = "$date  ·  ${formatSize(file.length())}"
        holder.btnUpload.setOnClickListener { onUpload(file) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024L        -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        else                 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }

    object FileDiff : DiffUtil.ItemCallback<File>() {
        override fun areItemsTheSame(a: File, b: File) = a.absolutePath == b.absolutePath
        override fun areContentsTheSame(a: File, b: File) =
            a.lastModified() == b.lastModified() && a.length() == b.length()
    }
}
