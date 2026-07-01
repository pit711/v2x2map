package org.opentrafficmap.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.constraintlayout.widget.ConstraintSet
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
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.time.Instant
import java.util.LinkedList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FrameLogAdapter
    private val reader = FrameReader()
    private var usb: UsbSerialController? = null
    private var bt: BluetoothController? = null
    private val mqttBridges = mutableListOf<MqttBridge>()
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
    @Volatile private var spatLightEnabled: Boolean = true
    private var logExpanded = true

    // Button default tints (restored on IDLE)
    private var defaultBtnTintUsb: ColorStateList? = null
    private var defaultBtnTintBt:  ColorStateList? = null

    // Own GPS track
    private val ownTrackPoints = mutableListOf<GeoPoint>()
    private var ownTrackLine: Polyline? = null
    @Volatile private var ownTrackEnabled: Boolean = false

    // Compass / bearing-up mode
    @Volatile private var compassMode: Boolean = false

    // Frame log: buffer fills on the main thread, drains every LOG_REFRESH_MS
    private val pendingLogFrames = mutableListOf<Frame>()

    private data class SpatRsu(
        val lat: Double, val lon: Double,
        val phase: SpatTemParser.Phase, val lastSeenMs: Long
    )
    private val spatRsus = mutableMapOf<Long, SpatRsu>()
    private var lastLocation: Location? = null

    // ---------------------------------------------------------------- launchers

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r -> if (r.values.all { it }) startBt()
             else Toast.makeText(this, "BT: permission denied", Toast.LENGTH_SHORT).show() }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startReceiverService() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { centreOnMyLocation(); startLocationUpdates() }
        else toast(getString(R.string.loc_perm_denied))
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastSpeedMps = if (loc.hasSpeed()) loc.speed else 0f
            lastLocation = loc
            if (followEnabled) followLocation(loc)
            if (ownTrackEnabled && ::binding.isInitialized)
                runOnUiThread { addOwnTrackPoint(GeoPoint(loc.latitude, loc.longitude)) }
            if (::binding.isInitialized) {
                adapter.userLocation = loc          // for distance column
                runOnUiThread { updateSpeedOverlay(loc) }
            }
            if (compassMode && loc.hasBearing() && ::binding.isInitialized)
                runOnUiThread { binding.map.mapOrientation = -loc.bearing }
            updateSpatLight(loc)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.legalAccepted(this)) { showLegalDialog(); return }
        spatLightEnabled = Prefs.spatLightEnabled(this)
        setupUi()
    }

    private fun showLegalDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.legal_title)
            .setMessage(R.string.legal_body)
            .setCancelable(false)
            .setPositiveButton(R.string.legal_accept) { _, _ -> Prefs.setLegalAccepted(this, true); setupUi() }
            .setNegativeButton(R.string.legal_quit) { _, _ -> finish() }
            .show()
    }

    private fun setupUi() {
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().tileFileSystemCacheMaxBytes  = 600L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 500L * 1024 * 1024

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        recorder = FrameRecorder(this)
        fused    = LocationServices.getFusedLocationProviderClient(this)
        followEnabled   = Prefs.followEnabled(this)
        ownTrackEnabled = Prefs.ownTrackEnabled(this)
        compassMode     = Prefs.compassMode(this)
        markers = MarkerLayer(binding.map, this)
        geiger  = GeigerCounter(this)
        if (Prefs.audioFeedback(this)) geiger.start()

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setOnMenuItemClickListener(::onMenuItemClick)

        binding.map.setTileSource(tileSourceForKey(Prefs.mapLayer(this)))
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(6.0)
        binding.map.controller.setCenter(GeoPoint(51.0, 10.0))
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.map)
        binding.map.overlays.add(locationOverlay)

        adapter = FrameLogAdapter(this) { f -> FrameDetailSheet.show(this, f) }
        binding.log.layoutManager = LinearLayoutManager(this)
        binding.log.adapter = adapter

        // Cache default button tints before any programmatic change
        defaultBtnTintUsb = binding.btnConnect.backgroundTintList
        defaultBtnTintBt  = binding.btnConnectBt.backgroundTintList

        binding.btnConnect.setOnClickListener { toggleUsb() }
        binding.btnConnectBt.setOnClickListener { toggleBt() }
        binding.fabLocate.setOnClickListener { onLocateClick() }
        binding.fabLayers.setOnClickListener { showLayerPicker() }
        binding.fabCompass.setOnClickListener { toggleCompassMode() }
        binding.logCollapseBtn.setOnClickListener { toggleLogPanel() }

        // Reflect saved compass mode on FAB immediately
        applyCompassFabTint()

        applyKeepScreenOn()
        wireSettingsBus()
        if (Prefs.mqttEnabled(this) && Prefs.mqttBrokerList(this).isNotEmpty()) startMqtt()
        if (followEnabled || ownTrackEnabled || compassMode) ensureLocation()
        mainHandler.post(rateRefresh)
        mainHandler.post(logRefresh)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    private fun onMenuItemClick(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> false
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        binding.map.onResume()
        applyKeepScreenOn()
        if (followEnabled || ownTrackEnabled || compassMode) ensureLocation()
    }

    override fun onPause() {
        super.onPause()
        if (!::binding.isInitialized) return
        binding.map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ReceiverForegroundService::class.java))
        usb?.stop(); bt?.stop(); stopMqtt()
        if (::recorder.isInitialized) recorder.stop()
        if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation()
        if (::fused.isInitialized) stopLocationUpdates()
        if (::geiger.isInitialized) geiger.stop()
        SettingsBus.liveRecorder = null
        SettingsBus.liveBtController = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ----------------------------------------------------------- Settings bus

    private fun wireSettingsBus() {
        SettingsBus.liveRecorder = recorder
        SettingsBus.onFollowChanged = SettingsBus.OnFollowChanged { on ->
            followEnabled = on
            invalidateOptionsMenu()
            if (on || ownTrackEnabled || compassMode) ensureLocation() else stopLocationUpdates()
        }
        SettingsBus.onMqttToggle = SettingsBus.OnMqttToggle { on ->
            stopMqtt(); if (on) startMqtt()
        }
        SettingsBus.onRecordingToggle = SettingsBus.OnRecordingToggle { toggleRecording() }
        SettingsBus.onMapDownload = SettingsBus.OnMapDownload { downloadVisibleMap() }
        SettingsBus.onAudioChanged = SettingsBus.OnAudioChanged { on ->
            if (on) geiger.start() else geiger.stop()
        }
        SettingsBus.onSpatLightChanged = SettingsBus.OnSpatLightChanged { on ->
            spatLightEnabled = on
            if (!on) binding.spatLight.visibility = View.GONE
            else lastLocation?.let { updateSpatLight(it) }
        }
        SettingsBus.onCycleApply = SettingsBus.OnCycleApply { c ->
            lastCycle = c; SettingsBus.liveCycleConfig = c
            val ok = bt?.writeConfig(c) ?: false
            toast(getString(if (ok) R.string.cycle_saved else R.string.cycle_not_connected))
        }
        SettingsBus.onKeepScreenOnChanged = SettingsBus.OnKeepScreenOnChanged { applyKeepScreenOn() }
        SettingsBus.onDarkModeChanged = SettingsBus.OnDarkModeChanged { on ->
            AppCompatDelegate.setDefaultNightMode(
                if (on) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
        SettingsBus.onOwnTrackChanged = SettingsBus.OnOwnTrackChanged { on ->
            ownTrackEnabled = on
            if (!on) {
                ownTrackPoints.clear()
                ownTrackLine?.let { binding.map.overlays.remove(it) }
                ownTrackLine = null
                binding.map.invalidate()
            }
            if (on) ensureLocation()
        }
        SettingsBus.onResetAll = SettingsBus.OnResetAll { resetAll() }
    }

    // ------------------------------------------ Keep screen on

    private fun applyKeepScreenOn() {
        if (!::binding.isInitialized) return
        if (Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------ Reset

    private fun resetAll() {
        pendingLogFrames.clear()
        if (::markers.isInitialized) markers.clear()
        adapter.clear()
        totalFrames = 0
        rateWindow.clear()
        spatRsus.clear()
        ownTrackPoints.clear()
        ownTrackLine?.let { binding.map.overlays.remove(it) }
        ownTrackLine = null
        binding.logStats.text    = getString(R.string.log_stats, 0, 0)
        binding.emptyLog.visibility  = View.VISIBLE
        binding.spatLight.visibility = View.GONE
        binding.map.invalidate()
    }

    // ------------------------------------------ Log panel collapse

    private fun toggleLogPanel() {
        logExpanded = !logExpanded
        val cs = ConstraintSet()
        cs.clone(binding.root)
        cs.setGuidelinePercent(R.id.logSplit, if (logExpanded) 0.70f else 0.96f)
        cs.applyTo(binding.root)
        binding.log.visibility = if (logExpanded) View.VISIBLE else View.GONE
        if (!logExpanded) binding.emptyLog.visibility = View.GONE
        else if (adapter.itemCount == 0) binding.emptyLog.visibility = View.VISIBLE
        binding.logCollapseBtn.rotation = if (logExpanded) 0f else 180f
    }

    // ------------------------------------------ Map layers

    private fun showLayerPicker() {
        val keys   = arrayOf("MAPNIK", "DARK", "SATELLITE", "TRANSPORT", "HOT")
        val labels = arrayOf(
            getString(R.string.map_layer_standard),
            getString(R.string.map_layer_dark),
            getString(R.string.map_layer_satellite),
            getString(R.string.map_layer_transport),
            getString(R.string.map_layer_humanitarian)
        )
        val current = keys.indexOf(Prefs.mapLayer(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.map_layer_title)
            .setSingleChoiceItems(labels, current) { dlg, which ->
                val key = keys[which]
                Prefs.setMapLayer(this, key)
                binding.map.setTileSource(tileSourceForKey(key))
                dlg.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tileSourceForKey(key: String): ITileSource = when (key) {
        "DARK" -> XYTileSource(
            "CartoDB_DarkMatter", 0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/",
                "https://d.basemaps.cartocdn.com/dark_all/"
            )
        )
        "SATELLITE" -> object : OnlineTileSourceBase("Esri_WorldImagery", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
                "${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
        }
        "TRANSPORT" -> XYTileSource(
            "OPNV_Karte", 0, 18, 256, ".png",
            arrayOf("https://tileserver.memomaps.de/tilegen/")
        )
        "HOT" -> XYTileSource(
            "HOT", 0, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.fr/hot/",
                "https://b.tile.openstreetmap.fr/hot/",
                "https://c.tile.openstreetmap.fr/hot/"
            )
        )
        else -> TileSourceFactory.MAPNIK
    }

    // ------------------------------------------ Compass / bearing-up

    private fun toggleCompassMode() {
        compassMode = !compassMode
        Prefs.setCompassMode(this, compassMode)
        applyCompassFabTint()
        if (!compassMode) {
            binding.map.mapOrientation = 0f  // reset to North-up
        } else {
            ensureLocation()
        }
        toast(getString(if (compassMode) R.string.compass_bearing_up else R.string.compass_north_up))
    }

    private fun applyCompassFabTint() {
        if (!::binding.isInitialized) return
        binding.fabCompass.backgroundTintList = if (compassMode)
            ColorStateList.valueOf(0xFF2196F3.toInt())   // blue = active
        else
            defaultBtnTintUsb  // same neutral tint as other FABs
    }

    // ------------------------------------------ Speed overlay

    private fun updateSpeedOverlay(loc: Location) {
        val speedKph = loc.speed * 3.6f
        val bearingText = if (loc.hasBearing()) formatBearing(loc.bearing) else "—°"
        binding.speedOverlay.text = "$bearingText  ${"%3.0f km/h".format(speedKph)}"
        binding.speedOverlay.visibility = View.VISIBLE
    }

    private fun formatBearing(deg: Float): String {
        val card = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = ((deg / 45f + 0.5f).toInt() % 8).coerceIn(0, 7)
        return "%03.0f° %s".format(deg, card[idx])
    }

    // ------------------------------------------ Own track

    private fun addOwnTrackPoint(pt: GeoPoint) {
        ownTrackPoints.add(pt)
        if (ownTrackPoints.size > OWN_TRACK_MAX) ownTrackPoints.removeAt(0)
        val line = ownTrackLine ?: Polyline().also { poly ->
            poly.outlinePaint.color       = 0xFF2196F3.toInt()
            poly.outlinePaint.strokeWidth = 5f
            poly.outlinePaint.alpha       = 200
            binding.map.overlays.add(0, poly)
            ownTrackLine = poly
        }
        line.setPoints(ownTrackPoints)
        binding.map.invalidate()
    }

    // ------------------------------------------------------- Foreground service

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
        } else {
            usb?.stop(); usb = null
            onUsbState(UsbSerialController.State.IDLE, null)
        }
    }

    private fun onUsbState(state: UsbSerialController.State, info: String?) = runOnUiThread {
        binding.btnConnect.text = when (state) {
            UsbSerialController.State.IDLE       -> getString(R.string.connect)
            UsbSerialController.State.REQUESTING -> info ?: getString(R.string.usb_connecting)
            UsbSerialController.State.CONNECTED  -> "${getString(R.string.disconnect)} ${info ?: ""}"
            UsbSerialController.State.ERROR      -> "${getString(R.string.usb_error_btn)}  ${info?.take(22) ?: ""}"
        }
        binding.btnConnect.backgroundTintList = when (state) {
            UsbSerialController.State.IDLE       -> defaultBtnTintUsb
            UsbSerialController.State.REQUESTING -> ColorStateList.valueOf(0xFFFF9800.toInt())
            UsbSerialController.State.CONNECTED  -> ColorStateList.valueOf(0xFF4CAF50.toInt())
            UsbSerialController.State.ERROR      -> ColorStateList.valueOf(0xFFF44336.toInt())
        }
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
            onBtState(BluetoothController.State.IDLE, null)
        }
    }

    private fun startBt() {
        ensureServiceRunning()
        bt = BluetoothController(
            context  = this,
            onBytes  = ::onSerialBytes,
            onState  = ::onBtState,
            onConfig = { lastCycle = it; SettingsBus.liveCycleConfig = it },
        ).also { it.start(); SettingsBus.liveBtController = it }
    }

    private fun onBtState(state: BluetoothController.State, info: String?) = runOnUiThread {
        binding.btnConnectBt.text = when (state) {
            BluetoothController.State.IDLE       -> getString(R.string.connect_bt)
            BluetoothController.State.SCANNING   -> info ?: getString(R.string.bt_scanning_label)
            BluetoothController.State.CONNECTING -> info ?: getString(R.string.bt_connecting_label)
            BluetoothController.State.CONNECTED  -> "${getString(R.string.disconnect_bt)} ${info ?: ""}"
            BluetoothController.State.ERROR      -> "${getString(R.string.bt_error_btn)}  ${info?.take(22) ?: ""}"
        }
        binding.btnConnectBt.backgroundTintList = when (state) {
            BluetoothController.State.IDLE       -> defaultBtnTintBt
            BluetoothController.State.SCANNING,
            BluetoothController.State.CONNECTING -> ColorStateList.valueOf(0xFFFF9800.toInt())
            BluetoothController.State.CONNECTED  -> ColorStateList.valueOf(0xFF4CAF50.toInt())
            BluetoothController.State.ERROR      -> ColorStateList.valueOf(0xFFF44336.toInt())
        }
    }

    // ---------------------------------------------------------- Location

    private fun onLocateClick() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        centreOnMyLocation()
    }

    private fun ensureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        startLocationUpdates()
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L).build()
        try { fused.requestLocationUpdates(req, locationCallback, mainLooper) }
        catch (_: SecurityException) {}
    }

    private fun stopLocationUpdates() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    private fun centreOnMyLocation() {
        if (!locationOverlay.isMyLocationEnabled) locationOverlay.enableMyLocation()
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

    /** Pan only — zoom is never changed here. */
    private fun followLocation(loc: Location) {
        binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
    }

    // -------------------------------------------------------- Recording

    private fun toggleRecording(): String = if (recorder.isRecording) {
        val stopped = recorder.stop()
        getString(R.string.rec_stopped, recorder.frameCount, stopped?.absolutePath ?: "?")
    } else {
        val f = recorder.start()
        if (f != null) getString(R.string.rec_started, f.absolutePath) else "recording start failed"
    }

    // ------------------------------------------------------- Map cache

    private fun downloadVisibleMap() {
        val src = binding.map.tileProvider.tileSource
            as? org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
        if (src == null || !src.tileSourcePolicy.acceptsBulkDownload()) {
            toast(getString(R.string.map_dl_unsupported)); return
        }
        val mgr     = CacheManager(binding.map)
        val box     = binding.map.boundingBox
        val zoomMin = binding.map.zoomLevelDouble.toInt()
        val zoomMax = (zoomMin + 2).coerceAtMost(src.maximumZoomLevel)
        val total   = mgr.possibleTilesInArea(box, zoomMin, zoomMax)
        toast(getString(R.string.map_dl_started))
        try {
            mgr.downloadAreaAsync(this, box, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete()          = runOnUiThread { toast(getString(R.string.map_dl_done)) }
                override fun onTaskFailed(e: Int)      = runOnUiThread { toast("Map dl error: $e") }
                override fun updateProgress(p: Int, cz: Int, zn: Int, zx: Int) {
                    if (p % 50 == 0) runOnUiThread { toast(getString(R.string.map_dl_progress, p, total)) }
                }
                override fun downloadStarted() {}
                override fun setPossibleTilesInArea(t: Int) {}
            })
        } catch (e: Exception) {
            toast("Map dl: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ MQTT

    private fun startMqtt() {
        stopMqtt()
        val nodeId = Prefs.nodeId(this).trim().ifEmpty { getString(R.string.default_node_id) }
        Prefs.mqttBrokerList(this).filter { it.isNotBlank() }.forEach { url ->
            MqttBridge(nodeId, normaliseBroker(url.trim())).also {
                it.start()
                mqttBridges.add(it)
            }
        }
    }

    private fun stopMqtt() {
        mqttBridges.forEach { it.stop() }
        mqttBridges.clear()
    }

    private fun normaliseBroker(s: String): String = when {
        s.startsWith("mqtts://") -> "ssl://" + s.removePrefix("mqtts://")
        s.startsWith("mqtt://")  -> "tcp://" + s.removePrefix("mqtt://")
        s.startsWith("ssl://")   -> s
        s.startsWith("tcp://")   -> s
        else                      -> "ssl://$s"
    }

    // ---------------------------------------------------------- Frames

    private fun onSerialBytes(chunk: ByteArray) {
        val frames = synchronized(reader) { reader.feed(chunk, Instant.now()) }
        if (frames.isEmpty()) return
        runOnUiThread { handleFrames(frames) }
    }

    private fun handleFrames(frames: List<Frame>) {
        val mqttFilter = Prefs.mqttFilterTypes(this)
        for (f in frames) {
            totalFrames++
            pendingLogFrames.add(f)          // buffered; drains in logRefresh
            markers.add(f)
            geiger.click(f.msgType)
            if (f.msgType.name in mqttFilter) mqttBridges.forEach { it.publish(f.payload) }
            if (recorder.isRecording) recorder.append(f)
            rateWindow.add(System.currentTimeMillis())

            if (f.msgType == ItsG5Decoder.MsgType.SPATEM && f.latLon != null) {
                val (lat, lon) = f.latLon
                spatRsus[f.stationId ?: 0L] = SpatRsu(lat, lon,
                    f.spatPhase ?: SpatTemParser.Phase.UNKNOWN, System.currentTimeMillis())
                lastLocation?.let { updateSpatLight(it) }
            }
        }
    }

    private fun updateSpatLight(loc: Location) {
        if (!spatLightEnabled) return
        val now = System.currentTimeMillis()
        spatRsus.entries.removeAll { now - it.value.lastSeenMs > 30_000 }
        if (spatRsus.isEmpty()) { binding.spatLight.visibility = View.GONE; return }

        val results = FloatArray(2)
        val nearest = spatRsus.values.filter { rsu ->
            Location.distanceBetween(loc.latitude, loc.longitude, rsu.lat, rsu.lon, results)
            val dist = results[0]
            if (dist > 400f) return@filter false
            if (loc.hasBearing()) {
                val diff = Math.abs(((loc.bearing - results[1] + 180f + 360f) % 360f) - 180f)
                diff < 70f
            } else dist < 250f
        }.minByOrNull { rsu ->
            Location.distanceBetween(loc.latitude, loc.longitude, rsu.lat, rsu.lon, results)
            results[0]
        }

        if (nearest == null) { binding.spatLight.visibility = View.GONE; return }
        binding.spatLight.visibility = View.VISIBLE
        applyPhaseColors(nearest.phase)
    }

    private fun applyPhaseColors(phase: SpatTemParser.Phase) {
        val DIM = 0x33
        fun tint(active: Boolean, r: Int, g: Int, b: Int): ColorStateList {
            val alpha = if (active) 0xFF else DIM
            return ColorStateList.valueOf((alpha shl 24) or (r shl 16) or (g shl 8) or b)
        }
        binding.lightRed.backgroundTintList    = tint(phase == SpatTemParser.Phase.RED,    0xCC, 0x22, 0x22)
        binding.lightYellow.backgroundTintList = tint(phase == SpatTemParser.Phase.YELLOW, 0xCC, 0xAA, 0x00)
        binding.lightGreen.backgroundTintList  = tint(phase == SpatTemParser.Phase.GREEN,  0x22, 0xCC, 0x22)
    }

    /** Drains frame buffer into the grouped adapter every 300 ms. */
    private val logRefresh = object : Runnable {
        override fun run() {
            if (pendingLogFrames.isNotEmpty()) {
                val batch = pendingLogFrames.toList()
                pendingLogFrames.clear()
                adapter.addFrames(batch)
                if (logExpanded && binding.emptyLog.visibility != View.GONE)
                    binding.emptyLog.visibility = View.GONE
            }
            mainHandler.postDelayed(this, LOG_REFRESH_MS)
        }
    }

    private val rateRefresh = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - 60_000
            while (rateWindow.isNotEmpty() && rateWindow.first() < cutoff) rateWindow.removeFirst()
            val rate = rateWindow.size
            val suffix = when {
                rate > 300 -> " ⚡"
                rate > 60  -> ""
                else       -> ""
            }
            binding.logStats.text = getString(R.string.log_stats, totalFrames.toInt(), rate) + suffix
            if (::markers.isInitialized) markers.prune()
            ReceiverForegroundService.instance?.updateStats(totalFrames.toInt(), rate)
            mainHandler.postDelayed(this, 1_000)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val OWN_TRACK_MAX  = 2000
        private const val LOG_REFRESH_MS = 300L  // log drain interval
    }
}
