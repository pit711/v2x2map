package org.opentrafficmap.receiver

import android.util.Log
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.net.ssl.SSLContext

/**
 * Async MQTT publisher to the OpenTrafficMap broker. TLS uses the Android
 * system trust store, which already contains all common public CAs (incl.
 * Let's Encrypt) — no client cert, anonymous publish.
 *
 * Usage:
 *   bridge.start()
 *   bridge.publish(payload)
 *   bridge.stop()
 */
class MqttBridge(
    private val nodeId: String,
    private val brokerUri: String = "ssl://cits1.opentrafficmap.org:8883",
    private val clientIdSuffix: String = "",
) {
    private val tag = "MqttBridge"
    private val packetTopic = "its/$nodeId/packet"
    private val statusTopic = "its/$nodeId/status"

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var connected = false

    fun isConnected(): Boolean = connected

    fun start() {
        if (client != null) return
        val c = MqttAsyncClient(brokerUri, "android-bridge-$nodeId$clientIdSuffix", MemoryPersistence())
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Log.w(tag, "connection lost", cause)
                connected = false
            }
            override fun messageArrived(topic: String, message: MqttMessage) {}
            override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 15
            keepAliveInterval = 60
            socketFactory = SSLContext.getDefault().socketFactory
            setWill(statusTopic, "offline".toByteArray(), 1, true)
        }
        try {
            c.connect(opts, null, object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
                override fun onSuccess(token: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    Log.i(tag, "connected to $brokerUri")
                    connected = true
                    try {
                        c.publish(statusTopic, "online".toByteArray(), 1, true)
                    } catch (e: MqttException) { Log.w(tag, "online publish failed", e) }
                }
                override fun onFailure(token: org.eclipse.paho.client.mqttv3.IMqttToken?, ex: Throwable?) {
                    Log.w(tag, "connect failed", ex)
                    connected = false
                }
            })
            client = c
        } catch (e: MqttException) {
            Log.w(tag, "start failed", e)
        }
    }

    fun publish(payload: ByteArray) {
        val c = client ?: return
        if (!connected) return
        try {
            c.publish(packetTopic, payload, 0, false)
        } catch (e: MqttException) {
            Log.w(tag, "publish failed", e)
        }
    }

    fun stop() {
        val c = client ?: return
        client = null
        connected = false
        try {
            c.publish(statusTopic, "offline".toByteArray(), 1, true)
            c.disconnect(2000L)
        } catch (e: Exception) {
            Log.w(tag, "stop failed", e)
        }
        try { c.close(true) } catch (_: Exception) {}
    }
}
