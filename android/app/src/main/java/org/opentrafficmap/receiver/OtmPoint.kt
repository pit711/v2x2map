package org.opentrafficmap.receiver

/** One entity received from the OpenTrafficMap WebSocket live feed. */
data class OtmPoint(
    val mac: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double?,
    val headingDeg: Double?,
    val spatPhase: SpatTemParser.Phase?,
    val spatSecsLeft: Int? = null,       // seconds until phase change (if provided by OTM)
)
