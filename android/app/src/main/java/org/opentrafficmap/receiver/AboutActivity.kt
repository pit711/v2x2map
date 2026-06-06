package org.opentrafficmap.receiver

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.opentrafficmap.receiver.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            val versionName = info.versionName ?: "?"
            val versionCode =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    info.longVersionCode.toInt()
                else @Suppress("DEPRECATION") info.versionCode
            binding.version.text = getString(R.string.about_version, versionName, versionCode)
        } catch (_: PackageManager.NameNotFoundException) {
            binding.version.text = ""
        }

        binding.btnGithub.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url))))
            } catch (_: Exception) {}
        }

        updateDemoButton()
        binding.btnDemo.setOnClickListener {
            val newState = !SettingsBus.demoModeActive
            SettingsBus.demoModeActive = newState
            SettingsBus.demoModeChanged(newState)
            updateDemoButton()
        }
    }

    private fun updateDemoButton() {
        binding.btnDemo.text = getString(
            if (SettingsBus.demoModeActive) R.string.demo_stop else R.string.demo_start
        )
    }
}
