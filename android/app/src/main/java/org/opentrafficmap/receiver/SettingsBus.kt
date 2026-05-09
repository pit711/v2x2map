package org.opentrafficmap.receiver

/**
 * Lightweight bus that lets [SettingsActivity] reach back into the live
 * [MainActivity] for actions that need its runtime state (BLE controller,
 * recorder, map view). Only static refs are held; both sides clear them in
 * onDestroy / on activity exit. Not thread-safe — calls happen on the main
 * thread.
 */
object SettingsBus {

    fun interface OnFollowChanged { fun call(enabled: Boolean) }
    fun interface OnMqttToggle    { fun call(enabled: Boolean) }
    fun interface OnRecordingToggle { fun call(): String? }
    fun interface OnMapDownload   { fun call() }
    fun interface OnCycleApply    { fun call(c: BluetoothController.CycleConfig) }
    fun interface OnAudioChanged    { fun call(enabled: Boolean) }
    fun interface OnSpatLightChanged { fun call(enabled: Boolean) }

    var onFollowChanged: OnFollowChanged? = null
    var onMqttToggle:    OnMqttToggle?    = null
    var onRecordingToggle: OnRecordingToggle? = null
    var onMapDownload:   OnMapDownload?   = null
    var onCycleApply:    OnCycleApply?    = null
    var onAudioChanged:  OnAudioChanged?  = null
    var onSpatLightChanged: OnSpatLightChanged? = null

    @Volatile var liveRecorder: FrameRecorder? = null
    @Volatile var liveBtController: BluetoothController? = null
    @Volatile var liveCycleConfig: BluetoothController.CycleConfig? = null

    // --- entry points used from SettingsActivity ----------------------------

    fun followChanged(on: Boolean)       = onFollowChanged?.call(on)
    fun mqttToggle(on: Boolean)          = onMqttToggle?.call(on)
    fun recordingToggle(): String?       = onRecordingToggle?.call()
    fun downloadVisibleMap()             = onMapDownload?.call()
    fun applyCycle(c: BluetoothController.CycleConfig) = onCycleApply?.call(c)
    fun audioChanged(on: Boolean)        = onAudioChanged?.call(on)
    fun spatLightChanged(on: Boolean)   = onSpatLightChanged?.call(on)

    fun recorder()        = liveRecorder
    fun btController()    = liveBtController
    fun lastCycleConfig() = liveCycleConfig
}
