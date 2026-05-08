package org.opentrafficmap.receiver

import android.content.Context
import androidx.core.content.edit

/**
 * Thin SharedPreferences wrapper for the small set of values the app keeps
 * across sessions. Keys are namespaced under [PREFS_NAME].
 */
object Prefs {
    private const val PREFS_NAME = "v2x2map"

    private const val KEY_LEGAL_ACCEPTED = "legal_accepted"
    private const val KEY_FOLLOW_ENABLED = "follow_enabled"
    private const val KEY_MQTT_ENABLED   = "mqtt_enabled"
    private const val KEY_MQTT_BROKER    = "mqtt_broker"
    private const val KEY_NODE_ID        = "node_id"
    private const val KEY_AUDIO_FEEDBACK  = "audio_feedback"
    private const val KEY_MARKER_TTL_MIN  = "marker_ttl_min"

    const val DEFAULT_MARKER_TTL_MIN = 5
    const val MAX_MARKER_TTL_MIN     = 999_999

    fun legalAccepted(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LEGAL_ACCEPTED, false)

    fun setLegalAccepted(ctx: Context, accepted: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_LEGAL_ACCEPTED, accepted) }

    fun followEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FOLLOW_ENABLED, false)

    fun setFollowEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_FOLLOW_ENABLED, on) }

    fun mqttEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MQTT_ENABLED, false)

    fun setMqttEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_MQTT_ENABLED, on) }

    fun mqttBroker(ctx: Context): String =
        prefs(ctx).getString(KEY_MQTT_BROKER, null)
            ?: ctx.getString(R.string.default_mqtt_broker)

    fun setMqttBroker(ctx: Context, url: String) =
        prefs(ctx).edit { putString(KEY_MQTT_BROKER, url) }

    fun nodeId(ctx: Context): String =
        prefs(ctx).getString(KEY_NODE_ID, null)
            ?: ctx.getString(R.string.default_node_id)

    fun setNodeId(ctx: Context, id: String) =
        prefs(ctx).edit { putString(KEY_NODE_ID, id) }

    fun audioFeedback(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUDIO_FEEDBACK, false)

    fun setAudioFeedback(ctx: Context, on: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_AUDIO_FEEDBACK, on) }

    fun markerTtlMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MARKER_TTL_MIN, DEFAULT_MARKER_TTL_MIN)
            .coerceIn(1, MAX_MARKER_TTL_MIN)

    fun setMarkerTtlMinutes(ctx: Context, minutes: Int) =
        prefs(ctx).edit { putInt(KEY_MARKER_TTL_MIN, minutes.coerceIn(1, MAX_MARKER_TTL_MIN)) }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
