#include "usb_stream.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "driver/usb_serial_jtag.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "nvs.h"

#include "bt_stream.h"
#include "config.h"
#include "cmd_sniffer.h"
#include "esp_coexist.h"
#include "esp_system.h"
#include "esp_wifi.h"
#include "led.h"

static const char TAG[] = "USB_STREAM";
static bool s_ready;

void usb_stream_init(void)
{
    if (s_ready) return;

    usb_serial_jtag_driver_config_t cfg = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
    cfg.tx_buffer_size = 4096;
    cfg.rx_buffer_size = 256;

    esp_err_t err = usb_serial_jtag_driver_install(&cfg);
    if (err == ESP_OK || err == ESP_ERR_INVALID_STATE) {
        s_ready = true;
    } else {
        ESP_LOGE(TAG, "usb_serial_jtag_driver_install failed: %s", esp_err_to_name(err));
    }
}

void usb_stream_publish_packet(const uint8_t *payload, uint16_t len, uint32_t sec, uint32_t usec)
{
    if (!s_ready) usb_stream_init();
    if (!s_ready) return;

    uint8_t hdr[14] = {
        'I', 'T', 'S', '5',
        (uint8_t)(sec  & 0xff), (uint8_t)((sec  >>  8) & 0xff),
        (uint8_t)((sec >> 16) & 0xff), (uint8_t)((sec >> 24) & 0xff),
        (uint8_t)(usec & 0xff), (uint8_t)((usec >>  8) & 0xff),
        (uint8_t)((usec >> 16) & 0xff), (uint8_t)((usec >> 24) & 0xff),
        (uint8_t)(len  & 0xff), (uint8_t)((len  >>  8) & 0xff),
    };

    usb_serial_jtag_write_bytes(hdr, sizeof(hdr), pdMS_TO_TICKS(50));
    if (len > 0) {
        usb_serial_jtag_write_bytes(payload, len, pdMS_TO_TICKS(50));
    }
    led_pulse_usb_tx();
}

void usb_stream_send_test_frame(void)
{
    static const uint8_t test_payload[16] = {
        0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03, 0x04,
        0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C
    };
    usb_stream_publish_packet(test_payload, sizeof(test_payload), 1234567890U, 999U);
}

/* ── USB RX config channel ──────────────────────────────────────────────
 * Protocol: wizard sends  "CFG:key=value\n"
 *           device replies "CFG_OK:key\n" or "CFG_ERR:message\n"
 * Keys map directly to NVS keys used by config.c (namespace "its").
 * ──────────────────────────────────────────────────────────────────────*/
static void cfg_reply(const char *msg)
{
    usb_serial_jtag_write_bytes((const uint8_t *)msg, strlen(msg), pdMS_TO_TICKS(200));
}

/* Map key name → config_index_t */
static int cfg_key_to_index(const char *key)
{
    /* string keys */
    if (!strcmp(key, "nodeid"))    return CONFIG_INDEX_NODEID;
    if (!strcmp(key, "mqtturi"))   return CONFIG_INDEX_MQTT_URI;
    if (!strcmp(key, "wifi1ssid")) return CONFIG_INDEX_WIFI1_SSID;
    if (!strcmp(key, "wifi1pass")) return CONFIG_INDEX_WIFI1_PASS;
    if (!strcmp(key, "wifi2ssid")) return CONFIG_INDEX_WIFI2_SSID;
    if (!strcmp(key, "wifi2pass")) return CONFIG_INDEX_WIFI2_PASS;
    if (!strcmp(key, "wifiip"))    return CONFIG_INDEX_WIFI_IP;
    if (!strcmp(key, "wifinm"))    return CONFIG_INDEX_WIFI_NM;
    if (!strcmp(key, "wifigw"))    return CONFIG_INDEX_WIFI_GW;
    if (!strcmp(key, "wifidns"))   return CONFIG_INDEX_WIFI_DNS;
    /* u8 keys */
    if (!strcmp(key, "wifiopen"))  return CONFIG_INDEX_WIFI_OPEN;
    if (!strcmp(key, "connmode"))  return CONFIG_INDEX_CONN_MODE;
    if (!strcmp(key, "sniffmode")) return CONFIG_INDEX_SNIFF_MODE;
    /* u32 keys */
    if (!strcmp(key, "sniffms"))   return CONFIG_INDEX_SNIFF_MS;
    if (!strcmp(key, "wifims"))    return CONFIG_INDEX_WIFI_MS;
    return -1;
}

static bool is_u8_key(const char *key)
{
    return !strcmp(key,"wifiopen") || !strcmp(key,"connmode") || !strcmp(key,"sniffmode");
}
static bool is_u32_key(const char *key)
{
    return !strcmp(key,"sniffms") || !strcmp(key,"wifims");
}

/* Set while a USB-triggered WiFi scan is in progress.
 * wifi_manager checks this flag to skip connect_from_nvs() on STA_START. */
static volatile bool s_cfg_scanning = false;
bool usb_cfg_is_scanning(void) { return s_cfg_scanning; }

/* Temp event handler: sets BIT0 on WIFI_EVENT_STA_START so the scan task
 * knows WiFi is ready before calling esp_wifi_scan_start(). */
static EventGroupHandle_t s_scan_ready_evg;
static void scan_sta_start_cb(void *arg, esp_event_base_t base,
                               int32_t id, void *data)
{
    if (id == WIFI_EVENT_STA_START && s_scan_ready_evg)
        xEventGroupSetBits(s_scan_ready_evg, BIT0);
}

/* WiFi scan task — triggered by SCAN command from the setup wizard */
static void cfg_scan_task(void *arg)
{
    char errbuf[80];
    s_cfg_scanning = true;

    bt_stream_pause();
    esp_coex_preference_set(ESP_COEX_PREFER_WIFI);

    esp_err_t err = esp_wifi_set_mode(WIFI_MODE_STA);
    if (err != ESP_OK) {
        snprintf(errbuf, sizeof(errbuf), "SCAN_ERR:set_mode %s\n", esp_err_to_name(err));
        cfg_reply(errbuf); goto done;
    }

    esp_wifi_set_storage(WIFI_STORAGE_RAM);

    /* Country DE: channels 1-13 only (avoids scanning empty 5 GHz channels
     * that BLE coex extends scan time on) */
    wifi_country_t country = {
        .cc = "DE", .schan = 1, .nchan = 13,
        .max_tx_power = 20, .policy = WIFI_COUNTRY_POLICY_MANUAL
    };
    esp_wifi_set_country(&country);

    err = esp_wifi_start();
    if (err != ESP_OK) {
        snprintf(errbuf, sizeof(errbuf), "SCAN_ERR:start %s\n", esp_err_to_name(err));
        cfg_reply(errbuf); goto done;
    }

    vTaskDelay(pdMS_TO_TICKS(300));

    /* Exact v0.1.7 config that found 1 AP */
    wifi_scan_config_t sc = {
        .ssid = NULL, .bssid = NULL,
        .channel = 0, .show_hidden = false,
        .scan_type = WIFI_SCAN_TYPE_ACTIVE,
        .scan_time.active.min = 500,
        .scan_time.active.max = 3000,
    };
    err = esp_wifi_scan_start(&sc, true);  /* blocking — waits for scan to finish */
    if (err != ESP_OK) {
        snprintf(errbuf, sizeof(errbuf), "SCAN_ERR:scan_start %s\n", esp_err_to_name(err));
        cfg_reply(errbuf); goto done;
    }

    {
        uint16_t n = 0;
        esp_wifi_scan_get_ap_num(&n);
        char dbg[32];
        int dl = snprintf(dbg, sizeof(dbg), "SCAN_N:%d\n", (int)n);
        usb_serial_jtag_write_bytes((uint8_t *)dbg, dl, pdMS_TO_TICKS(200));

        wifi_ap_record_t *aps = NULL;
        if (n > 0) {
            aps = malloc(n * sizeof(*aps));
            if (aps) esp_wifi_scan_get_ap_records(&n, aps);
        }
        for (uint16_t i = 0; i < n && aps; i++) {
            char line[72];
            int len = snprintf(line, sizeof(line), "SCAN_AP:%s,ch%d,auth%d\n",
                               (char *)aps[i].ssid,
                               aps[i].primary, aps[i].authmode);
            if (len > 0)
                usb_serial_jtag_write_bytes((uint8_t *)line, len, pdMS_TO_TICKS(200));
        }
        free(aps);
    }

done:
    usb_serial_jtag_write_bytes((uint8_t *)"SCAN_DONE\n", 10, pdMS_TO_TICKS(200));
    esp_wifi_stop();
    esp_wifi_set_mode(WIFI_MODE_NULL);
    esp_coex_preference_set(ESP_COEX_PREFER_BT);
    bt_stream_resume();
    s_cfg_scanning = false;
    vTaskDelete(NULL);
}

static void handle_cfg_command(char *line)
{
    /* Reboot command */
    if (strcmp(line, "REBOOT") == 0) {
        cfg_reply("REBOOT_OK\n");
        vTaskDelay(pdMS_TO_TICKS(150));
        esp_restart();
        return;
    }

    /* WiFi scan via device radio */
    if (strcmp(line, "SCAN") == 0) {
        cfg_reply("SCAN_START\n");
        xTaskCreate(cfg_scan_task, "cfg_scan", 8192, NULL, 3, NULL);
        return;
    }

    /* Expect "CFG:key=value" */
    if (strncmp(line, "CFG:", 4) != 0) return;
    char *kv = line + 4;
    char *eq = strchr(kv, '=');
    if (!eq) { cfg_reply("CFG_ERR:no equals\n"); return; }
    *eq = '\0';
    char *key = kv, *val = eq + 1;

    int idx = cfg_key_to_index(key);
    if (idx < 0) { cfg_reply("CFG_ERR:unknown key\n"); return; }

    esp_err_t err;
    if (is_u8_key(key)) {
        uint8_t v = (uint8_t)atoi(val);
        err = config_set_u8((config_index_t)idx, v);
    } else if (is_u32_key(key)) {
        uint32_t v = (uint32_t)atol(val);
        err = config_set_u32((config_index_t)idx, v);
    } else {
        err = config_set_str((config_index_t)idx, val);
    }

    /* key/value come out of the 128-byte command line buffer, so size the reply
     * to hold the worst case — otherwise -Os trips -Werror=format-truncation. */
    char reply[256];
    if (err == ESP_OK) snprintf(reply, sizeof(reply), "CFG_OK:%s\n", key);
    else               snprintf(reply, sizeof(reply), "CFG_ERR:%s:%s\n", key, esp_err_to_name(err));
    cfg_reply(reply);
}

static void usb_config_rx_task(void *arg)
{
    char line[128];
    int  pos = 0;

    for (;;) {
        uint8_t ch;
        int n = usb_serial_jtag_read_bytes(&ch, 1, pdMS_TO_TICKS(50));
        if (n <= 0) continue;

        if (ch == '\n' || ch == '\r') {
            if (pos > 0) {
                line[pos] = '\0';
                handle_cfg_command(line);
                pos = 0;
            }
        } else if (pos < (int)sizeof(line) - 1) {
            line[pos++] = (char)ch;
        } else {
            pos = 0;  /* overflow — reset */
        }
    }
}

void usb_config_rx_start(void)
{
    xTaskCreate(usb_config_rx_task, "usb_cfg_rx", 4096, NULL, 3, NULL);
}
