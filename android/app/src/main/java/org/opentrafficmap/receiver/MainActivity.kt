package org.opentrafficmap.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.opentrafficmap.receiver.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.LinkedList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FrameLogAdapter
    private val reader = FrameReader()
    private var usb: UsbSerialController? = null
    private var bt: BluetoothController? = null
    private var mqtt: MqttBridge? = null
    private lateinit var recorder: FrameRecorder
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var markers: MarkerLayer
    private lateinit var geiger: GeigerCounter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rateWindow = LinkedList<Long>()
    private var totalFrames = 0L
    @Volatile private var lastCycle: BluetoothController.CycleConfig? = null
    @Volatile private var lastSpeedMps: Float = 0f
    @Volatile private var followEnabled: Boolean = false

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startBt() else binding.btStatus.text = "BT: permission denied"
    }
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — service starts regardless */ startReceiverService() }
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            centreOnMyLocation()
            startLocationUpdates()
        } else {
            toast(getString(R.string.loc_perm_denied))
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastSpeedMps = if (loc.hasSpeed()) loc.speed else 0f
            if (followEnabled) followLocation(loc)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Prefs.legalAccepted(this)) {
            showLegalDialog(); return
        }
        setupUi()
    }

    /** Gate the entire app behind a one-shot legal disclaimer. */
    private fun showLegalDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.legal_title)
            .setMessage(R.string.legal_body)
            .setCancelable(false)
            .setPositiveButton(R.string.legal_accept) { _, _ ->
                Prefs.setLegalAccepted(this, true)
                setupUi()
            }
            .setNegativeButton(R.string.legal_quit) { _, _ -> finish() }
            .show()
    }

    private fun setupUi() {
        // OSMdroid: persistent tile cache, larger than default so panned-over
        // tiles stay available offline.
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().tileFileSystemCacheMaxBytes  = 600L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 500L * 1024 * 1024

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        recorder = FrameRecorder(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        followEnabled = Prefs.followEnabled(this)
        markers = MarkerLayer(binding.map, this)
        geiger = GeigerCounter(this)
        if (Prefs.audioFeedback(this)) geiger.start()

        // --- Toolbar / menu ---
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setOnMenuItemClickListener(::onMenuItemClick)

        // --- Map ---
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(6.0)
        binding.map.controller.setCenter(GeoPoint(51.0, 10.0))
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.map)
        binding.map.overlays.add(locationOverlay)

        // --- Frame log ---
        adapter = FrameLogAdapter { f -> FrameDetailSheet.show(this, f) }
        binding.log.layoutManager = LinearLayoutManager(this)
        binding.log.adapter = adapter

        // --- Top-level actions ---
        binding.btnConnect.setOnClickListener { toggleUsb() }
        binding.btnConnectBt.setOnClickListener { toggleBt() }
        binding.fabLocate.setOnClickListener { onLocateClick() }

        wireSettingsBus()
        if (Prefs.mqttEnabled(this)) startMqtt()
        if (followEnabled) ensureLocation()

        mainHandler.post(rateRefresh)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    private fun onMenuItemClick(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> false
    }

    override fun onResume() {
        super.onResume()
        // Lifecycle fires while the legal dialog is up — at that point the UI
        // hasn't been inflated yet. Guard every lateinit access.
        if (!::binding.isInitialized) return
        binding.map.onResume()
        if (followEnabled) ensureLocation()
    }

    override fun onPause() {
        super.onPause()
        if (!::binding.isInitialized) return
        binding.map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ReceiverForegroundService::class.java))
        usb?.stop()
        bt?.stop()
        mqtt?.stop()
        if (::recorder.isInitialized) recorder.stop()
        if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation()
        if (::fused.isInitialized) stopLocationUpdates()
        if (::geiger.isInitialized) geiger.stop()
        SettingsBus.liveRecorder = null
        SettingsBus.liveBtController = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------- Settings glue

    private fun wireSettingsBus() {
        SettingsBus.liveRecorder = recorder

        SettingsBus.onFollowChanged = SettingsBus.OnFollowChanged { on ->
            followEnabled = on
            invalidateOptionsMenu()
            if (on) ensureLocation() else stopLocationUpdates()
        }
        SettingsBus.onMqttToggle = SettingsBus.OnMqttToggle { on ->
            if (on) startMqtt() else stopMqtt()
        }
        SettingsBus.onRecordingToggle = SettingsBus.OnRecordingToggle {
            toggleRecording()
        }
        SettingsBus.onMapDownload = SettingsBus.OnMapDownload { downloadVisibleMap() }
        SettingsBus.onAudioChanged = SettingsBus.OnAudioChanged { on ->
            if (on) geiger.start() else geiger.stop()
        }
        SettingsBus.onCycleApply = SettingsBus.OnCycleApply { c ->
            lastCycle = c
            SettingsBus.liveCycleConfig = c
            val ok = bt?.writeConfig(c) ?: false
            toast(getString(if (ok) R.string.cycle_saved else R.string.cycle_not_connected))
        }
    }

    // --------------------------------------------------- Foreground service

    private fun ensureServiceRunning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReceiverService()
        }
    }

    private fun startReceiverService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    // -------------------------------------------------------------- USB

    private fun toggleUsb() {
        if (usb == null) {
            ensureServiceRunning()
            usb = UsbSerialController(this, ::onSerialBytes, ::onUsbState).also { it.start() }
            binding.btnConnect.text = getString(R.string.disconnect)
        } else {
            usb?.stop(); usb = null
            binding.btnConnect.text = getString(R.string.connect)
            binding.usbStatus.text = getString(R.string.status_disconnected)
        }
    }

    private fun onUsbState(state: UsbSerialController.State, info: String?) = runOnUiThread {
        binding.usbStatus.text = when (state) {
            UsbSerialController.State.IDLE       -> getString(R.string.status_disconnected)
            UsbSerialController.State.REQUESTING -> info ?: "USB: requesting…"
            UsbSerialController.State.CONNECTED  -> getString(R.string.status_connected, info ?: "ok")
            UsbSerialController.State.ERROR      -> "USB error: ${info ?: "?"}"
        }
        binding.dotUsb.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when (state) {
                UsbSerialController.State.IDLE       -> 0xFF9E9E9E.toInt()
                UsbSerialController.State.REQUESTING -> 0xFFFF9800.toInt()
                UsbSerialController.State.CONNECTED  -> 0xFF4CAF50.toInt()
                UsbSerialController.State.ERROR      -> 0xFFF44336.toInt()
            }
        )
    }

    // --------------------------------------------------------------- BT

    private fun toggleBt() {
        if (bt == null) {
            val missing = BluetoothController.runtimePermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) { btPermissionLauncher.launch(missing.toTypedArray()); return }
            startBt()
        } else {
            bt?.stop(); bt = null
            SettingsBus.liveBtController = null
            binding.btnConnectBt.text = getString(R.string.connect_bt)
            binding.btStatus.text = getString(R.string.bt_status_disconnected)
        }
    }

    private fun startBt() {
        ensureServiceRunning()
        bt = BluetoothController(
            context  = this,
            onBytes  = ::onSerialBytes,
            onState  = ::onBtState,
            onConfig = {
                lastCycle = it
                SettingsBus.liveCycleConfig = it
            },
        ).also {
            it.start()
            SettingsBus.liveBtController = it
        }
        binding.btnConnectBt.text = getString(R.string.disconnect_bt)
    }

    private fun onBtState(state: BluetoothController.State, info: String?) = runOnUiThread {
        binding.btStatus.text = when (state) {
            BluetoothController.State.IDLE       -> getString(R.string.bt_status_disconnected)
            BluetoothController.State.SCANNING   -> info ?: "BT: scan…"
            BluetoothController.State.CONNECTING -> info ?: "BT: connect…"
            BluetoothController.State.CONNECTED  -> getString(R.string.bt_status_connected, info ?: "ok")
            BluetoothController.State.ERROR      -> "BT error: ${info ?: "?"}"
        }
        binding.dotBt.backgroundTintList = android.content.res.ColorStateList.valueOf(
            when (state) {
                BluetoothController.State.IDLE       -> 0xFF9E9E9E.toInt()
                BluetoothController.State.SCANNING,
                BluetoothController.State.CONNECTING -> 0xFFFF9800.toInt()
                BluetoothController.State.CONNECTED  -> 0xFF4CAF50.toInt()
                BluetoothController.State.ERROR      -> 0xFFF44336.toInt()
            }
        )
    }

    // ---------------------------------------------------------- Location

    private fun onLocateClick() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        centreOnMyLocation()
    }

    private fun ensureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        startLocationUpdates()
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fused.requestLocationUpdates(req, locationCallback, mainLooper)
        } catch (_: SecurityException) {}
    }

    private fun stopLocationUpdates() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    private fun centreOnMyLocation() {
        if (!locationOverlay.isMyLocationEnabled) {
            locationOverlay.enableMyLocation()
        }
        val now = locationOverlay.myLocation
        if (now != null) {
            binding.map.controller.animateTo(now)
            binding.map.controller.setZoom(15.0)
        } else {
            locationOverlay.runOnFirstFix {
                runOnUiThread {
                    binding.map.controller.animateTo(locationOverlay.myLocation)
                    binding.map.controller.setZoom(15.0)
                }
            }
            toast(getString(R.string.loc_unknown))
        }
    }

    /** Auto-follow with speed-adaptive zoom: more zoom when stationary, less
     *  when fast. Speeds in m/s. */
    private fun followLocation(loc: Location) {
        val pt = GeoPoint(loc.latitude, loc.longitude)
        val zoom = when {
            lastSpeedMps < 1f   -> 18.0     // standing
            lastSpeedMps < 5f   -> 17.0     // walking
            lastSpeedMps < 14f  -> 16.0     // city ~50 km/h
            lastSpeedMps < 22f  -> 15.0     // ~80 km/h
            else                -> 14.0     // highway
        }
        binding.map.controller.setZoom(zoom)
        binding.map.controller.animateTo(pt)
    }

    // -------------------------------------------------------- Recording

    private fun toggleRecording(): String {
        return if (recorder.isRecording) {
            val stopped = recorder.stop()
            getString(R.string.rec_stopped, recorder.frameCount, stopped?.absolutePath ?: "?")
        } else {
            val f = recorder.start()
            if (f != null) getString(R.string.rec_started, f.absolutePath)
            else "recording start failed"
        }
    }

    // ------------------------------------------------------- Map cache

    private fun downloadVisibleMap() {
        // Mapnik (and most other public OSM tile sources) forbid bulk-download
        // via TileSourcePolicy. Calling CacheManager.downloadAreaAsync on such
        // a source throws TileSourcePolicyException out of the AsyncTask's
        // doInBackground — uncatchable, kills the app. Pre-flight the policy
        // and surface a friendly toast instead. The on-pan tile cache still
        // grows automatically, so users can still build an offline cache by
        // panning around.
        val src = binding.map.tileProvider.tileSource as? org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
        if (src == null || !src.tileSourcePolicy.acceptsBulkDownload()) {
            toast(getString(R.string.map_dl_unsupported))
            return
        }
        val mgr = CacheManager(binding.map)
        val box = binding.map.boundingBox
        val zoomMin = binding.map.zoomLevelDouble.toInt()
        val zoomMax = (zoomMin + 2).coerceAtMost(src.maximumZoomLevel)
        val total = mgr.possibleTilesInArea(box, zoomMin, zoomMax)
        toast(getString(R.string.map_dl_started))
        try {
            mgr.downloadAreaAsync(this, box, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete()           = runOnUiThread { toast(getString(R.string.map_dl_done)) }
                override fun onTaskFailed(errors: Int)  = runOnUiThread { toast("Map dl error: $errors") }
                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                    if (progress % 50 == 0) runOnUiThread {
                        toast(getString(R.string.map_dl_progress, progress, total))
                    }
                }
                override fun downloadStarted() {}
                override fun setPossibleTilesInArea(total: Int) {}
            })
        } catch (e: Exception) {
            toast("Map dl: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ MQTT

    private fun startMqtt() {
        stopMqtt()
        val broker = Prefs.mqttBroker(this).trim()
        val nodeId = Prefs.nodeId(this).trim().ifEmpty { getString(R.string.default_node_id) }
        val uri = normaliseBroker(broker)
        mqtt = MqttBridge(nodeId, uri).also { it.start() }
    }

    private fun stopMqtt() { mqtt?.stop(); mqtt = null }

    /** Convert user-facing `mqtts://`/`mqtt://` to Paho's `ssl://`/`tcp://`. */
    private fun normaliseBroker(s: String): String = when {
        s.startsWith("mqtts://") -> "ssl://" + s.removePrefix("mqtts://")
        s.startsWith("mqtt://")  -> "tcp://" + s.removePrefix("mqtt://")
        s.startsWith("ssl://")   -> s
        s.startsWith("tcp://")   -> s
        else                      -> "ssl://$s"
    }

    // ---------------------------------------------------------- Frames

    private fun onSerialBytes(chunk: ByteArray) {
        val frames = synchronized(reader) { reader.feed(chunk) }
        if (frames.isEmpty()) return
        runOnUiThread { handleFrames(frames) }
    }

    private fun handleFrames(frames: List<Frame>) {
        for (f in frames) {
            totalFrames++
            adapter.prepend(f)
            if (binding.emptyLog.visibility != View.GONE) binding.emptyLog.visibility = View.GONE
            markers.add(f)
            geiger.click(f.msgType)
            mqtt?.publish(f.payload)
            if (recorder.isRecording) recorder.append(f)
            rateWindow.add(System.currentTimeMillis())
        }
        binding.totalCounter.text = getString(R.string.stat_total, totalFrames.toInt())
    }

    private val rateRefresh = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - 60_000
            while (rateWindow.isNotEmpty() && rateWindow.first() < cutoff) rateWindow.removeFirst()
            binding.rateCounter.text = getString(R.string.stat_rate, rateWindow.size)
            if (::markers.isInitialized) markers.prune()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
