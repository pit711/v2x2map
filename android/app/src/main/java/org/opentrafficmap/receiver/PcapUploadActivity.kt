package org.opentrafficmap.receiver

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opentrafficmap.receiver.databinding.ActivityPcapUploadBinding
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPcapUploadBinding
    private var uploadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPcapUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = PcapFileAdapter(::startUpload)
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter

        val files = loadPcapFiles()
        if (files.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.fileList.visibility = View.GONE
        } else {
            adapter.submitList(files)
        }
    }

    private fun loadPcapFiles(): List<File> {
        val dir = getExternalFilesDir(null) ?: filesDir
        return (dir.listFiles { f -> f.name.startsWith("itsg5-") && f.name.endsWith(".pcap") }
            ?: emptyArray()).sortedByDescending { it.lastModified() }
    }

    private fun startUpload(file: File) {
        if (uploadJob?.isActive == true) {
            Toast.makeText(this, getString(R.string.upload_already_running), Toast.LENGTH_SHORT).show()
            return
        }

        val brokers = Prefs.mqttBrokerList(this).filter { it.isNotBlank() }
        if (brokers.isEmpty()) {
            Toast.makeText(this, getString(R.string.upload_no_broker), Toast.LENGTH_LONG).show()
            return
        }

        val nodeId = Prefs.nodeId(this).trim().ifEmpty { getString(R.string.default_node_id) }
        showProgress(file.name)

        uploadJob = lifecycleScope.launch {
            val bridges = brokers.mapIndexed { i, url ->
                MqttBridge(nodeId, normaliseBroker(url.trim()), clientIdSuffix = "-upload-$i")
                    .also { it.start() }
            }
            try {
                // Wait up to 10 s for at least one broker to connect
                withContext(Dispatchers.IO) {
                    repeat(20) {
                        if (bridges.any { it.isConnected() }) return@repeat
                        delay(500L)
                    }
                }

                if (!bridges.any { it.isConnected() }) {
                    bridges.forEach { it.stop() }
                    hideProgress()
                    Toast.makeText(this@PcapUploadActivity,
                        getString(R.string.upload_connect_failed), Toast.LENGTH_LONG).show()
                    return@launch
                }

                val packets = withContext(Dispatchers.IO) { readPcapPackets(file) }
                if (packets.isEmpty()) {
                    bridges.forEach { it.stop() }
                    hideProgress()
                    Toast.makeText(this@PcapUploadActivity,
                        getString(R.string.upload_empty_pcap), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val total = packets.size
                var sent = 0
                for ((idx, payload) in packets.withIndex()) {
                    bridges.filter { it.isConnected() }.forEach { it.publish(payload) }
                    sent++
                    updateProgress(idx + 1, total, ((idx + 1) * 100) / total)
                    delay(PACKET_DELAY_MS)
                }

                bridges.forEach { it.stop() }
                hideProgress()
                Toast.makeText(this@PcapUploadActivity,
                    getString(R.string.upload_done, sent, total), Toast.LENGTH_LONG).show()

            } catch (e: CancellationException) {
                bridges.forEach { it.stop() }
                hideProgress()
                throw e
            } catch (e: Exception) {
                bridges.forEach { it.stop() }
                hideProgress()
                Toast.makeText(this@PcapUploadActivity,
                    getString(R.string.upload_error, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showProgress(filename: String) {
        binding.progressCard.visibility = View.VISIBLE
        binding.progressFilename.text = filename
        binding.progressBar.progress = 0
        binding.progressText.text = getString(R.string.upload_progress_fmt, 0, 0)
        binding.btnCancelUpload.setOnClickListener { uploadJob?.cancel() }
    }

    private fun updateProgress(current: Int, total: Int, pct: Int) {
        binding.progressBar.progress = pct
        binding.progressText.text = getString(R.string.upload_progress_fmt, current, total)
    }

    private fun hideProgress() {
        binding.progressCard.visibility = View.GONE
    }

    // Reads raw packet payloads from a standard libpcap file.
    private fun readPcapPackets(file: File): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        try {
            FileInputStream(file).use { fis ->
                val globalHdr = ByteArray(24)
                if (fis.read(globalHdr) != 24) return packets
                val magic = ByteBuffer.wrap(globalHdr, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (magic != PCAP_MAGIC) return packets   // not our format

                val pktHdr = ByteArray(16)
                while (fis.read(pktHdr) == 16) {
                    val h = ByteBuffer.wrap(pktHdr).order(ByteOrder.LITTLE_ENDIAN)
                    h.int; h.int                          // skip ts_sec, ts_usec
                    val inclLen = h.int
                    h.int                                 // skip orig_len
                    if (inclLen <= 0 || inclLen > 65535) break
                    val payload = ByteArray(inclLen)
                    var read = 0
                    while (read < inclLen) {
                        val r = fis.read(payload, read, inclLen - read)
                        if (r < 0) break
                        read += r
                    }
                    if (read == inclLen) packets.add(payload)
                }
            }
        } catch (_: Exception) {}
        return packets
    }

    private fun normaliseBroker(s: String): String = when {
        s.startsWith("mqtts://") -> "ssl://" + s.removePrefix("mqtts://")
        s.startsWith("mqtt://")  -> "tcp://" + s.removePrefix("mqtt://")
        s.startsWith("ssl://")   -> s
        s.startsWith("tcp://")   -> s
        else                     -> "ssl://$s"
    }

    override fun onDestroy() {
        super.onDestroy()
        uploadJob?.cancel()
    }

    companion object {
        // 0xa1b2c3d4 in little-endian — same magic FrameRecorder writes
        private val PCAP_MAGIC = 0xa1b2c3d4.toInt()
        private const val PACKET_DELAY_MS = 25L
    }
}
