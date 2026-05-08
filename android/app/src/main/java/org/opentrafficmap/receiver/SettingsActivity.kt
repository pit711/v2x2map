package org.opentrafficmap.receiver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import org.opentrafficmap.receiver.databinding.ActivitySettingsBinding

/**
 * Single-screen settings: hosts persistent app prefs (auto-follow, MQTT broker
 * URL, node ID, MQTT enabled), one-shot actions (start/stop recording, prefetch
 * map tiles, BLE coex cycle dialog), and pointers to the about screen.
 *
 * Tightly coupled with [MainActivity] via static observers because MQTT and
 * recording also touch live data — see [SettingsBus].
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // --- Auto-follow -----------------------------------------------------
        binding.swFollow.isChecked = Prefs.followEnabled(this)
        binding.swFollow.setOnCheckedChangeListener { _, c ->
            Prefs.setFollowEnabled(this, c)
            SettingsBus.followChanged(c)
        }

        // --- Geiger-counter audio -------------------------------------------
        binding.swAudio.isChecked = Prefs.audioFeedback(this)
        binding.swAudio.setOnCheckedChangeListener { _, c ->
            Prefs.setAudioFeedback(this, c)
            SettingsBus.audioChanged(c)
        }

        // --- MQTT ------------------------------------------------------------
        binding.edBrokerUrl.setText(Prefs.mqttBroker(this))
        binding.edNodeId.setText(Prefs.nodeId(this))
        binding.swMqtt.isChecked = Prefs.mqttEnabled(this)
        binding.swMqtt.setOnCheckedChangeListener { _, c ->
            Prefs.setMqttBroker(this, binding.edBrokerUrl.text.toString().trim())
            Prefs.setNodeId(this, binding.edNodeId.text.toString().trim())
            Prefs.setMqttEnabled(this, c)
            SettingsBus.mqttToggle(c)
        }

        // --- Recording -------------------------------------------------------
        renderRecordingState()
        binding.btnRecord.setOnClickListener {
            val msg = SettingsBus.recordingToggle()
            Toast.makeText(this, msg ?: getString(R.string.rec_status_idle), Toast.LENGTH_LONG).show()
            renderRecordingState()
        }

        // --- Marker / path TTL -----------------------------------------------
        binding.edMarkerTtl.setText(Prefs.markerTtlMinutes(this).toString())

        // --- Map prefetch ----------------------------------------------------
        binding.btnDownloadMap.setOnClickListener {
            SettingsBus.downloadVisibleMap()
            // The actual progress lives in MainActivity. Close the screen so the
            // user can watch the toasts there.
            finish()
        }

        // --- BLE coex --------------------------------------------------------
        binding.btnCycle.setOnClickListener {
            val controller = SettingsBus.btController()
            if (controller == null) {
                Toast.makeText(this, getString(R.string.cycle_not_connected),
                               Toast.LENGTH_SHORT).show()
            } else {
                CycleSettingsDialog.show(this, SettingsBus.lastCycleConfig()) { newCfg ->
                    SettingsBus.applyCycle(newCfg)
                }
            }
        }

        // --- About / GitHub --------------------------------------------------
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.btnGithub.setOnClickListener { openUrl(getString(R.string.github_url)) }
    }

    override fun onPause() {
        super.onPause()
        Prefs.setMqttBroker(this, binding.edBrokerUrl.text.toString().trim())
        Prefs.setNodeId(this, binding.edNodeId.text.toString().trim())
        binding.edMarkerTtl.text.toString().trim().toIntOrNull()
            ?.let { Prefs.setMarkerTtlMinutes(this, it) }
    }

    private fun renderRecordingState() {
        val rec = SettingsBus.recorder()
        binding.btnRecord.text = getString(
            if (rec?.isRecording == true) R.string.rec_on else R.string.rec_off
        )
        binding.recStatus.text = if (rec?.isRecording == true) {
            getString(R.string.rec_status_active,
                      rec.file?.absolutePath ?: "?", rec.frameCount)
        } else {
            getString(R.string.rec_status_idle)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { /* no browser */ }
    }
}
