/**
 * wifi_manager.c
 *
 * Connects the ESP32-C5 to a home WiFi network stored in NVS, then
 * operates the sniffer in one of three modes:
 *
 *   REALTIME  — STA + promiscuous simultaneously on the AP's channel.
 *               Lowest latency; sniffs whatever is on the AP channel.
 *
 *   CYCLE     — Alternates between full promiscuous sniffing (sniffer_pause
 *               OFF = normal sniff) and a WiFi window in which buffered frames
 *               are forwarded via MQTT.  Uses sniffer_pause() / sniffer_resume().
 *
 *   INDIVIDUAL — Same as CYCLE but with user-configured timing.
 *
 * Reconnection: WiFi events drive a reconnect after a 5-second back-off.
 */

#include "wifi_manager.h"

#include <string.h>

#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"
#include "lwip/ip4_addr.h"
#include "lwip/inet.h"

#include "cmd_sniffer.h"
#include "config.h"

static const char TAG[] = "WIFI_MGR";

#define EV_CONNECTED   BIT0
#define EV_SCAN_DONE   BIT1
#define RECONNECT_MS   5000

static EventGroupHandle_t s_evg;
static volatile bool      s_connected = false;
static uint8_t            s_conn_mode;
static uint8_t            s_sniff_mode;
static uint32_t           s_sniff_ms;
static uint32_t           s_wifi_ms;
static esp_netif_t       *s_netif = NULL;
static TaskHandle_t       s_cycle_task = NULL;

/* ── helpers ──────────────────────────────────────────────────────────── */

static void load_str(config_index_t idx, char *buf, size_t len)
{
    size_t sz = len;
    if (config_get_str(idx, buf, &sz) != ESP_OK) buf[0] = '\0';
}

static bool has_str(config_index_t idx)
{
    char tmp[4]; size_t sz = sizeof(tmp);
    return config_get_str(idx, tmp, &sz) == ESP_OK && tmp[0] != '\0';
}

/* Apply static IP from NVS (if wifiip is set) or leave DHCP. */
static void apply_ip_config(void)
{
    char ip[16], nm[16], gw[16], dns[16];
    load_str(CONFIG_INDEX_WIFI_IP,  ip,  sizeof(ip));
    if (ip[0] == '\0') return;   /* DHCP */

    load_str(CONFIG_INDEX_WIFI_NM,  nm,  sizeof(nm));
    load_str(CONFIG_INDEX_WIFI_GW,  gw,  sizeof(gw));
    load_str(CONFIG_INDEX_WIFI_DNS, dns, sizeof(dns));

    esp_netif_dhcpc_stop(s_netif);
    esp_netif_ip_info_t info = {0};
    ip4addr_aton(ip[0]  ? ip  : "0.0.0.0",       (ip4_addr_t *)&info.ip);
    ip4addr_aton(nm[0]  ? nm  : "255.255.255.0",  (ip4_addr_t *)&info.netmask);
    ip4addr_aton(gw[0]  ? gw  : "0.0.0.0",        (ip4_addr_t *)&info.gw);
    esp_netif_set_ip_info(s_netif, &info);

    if (dns[0]) {
        esp_netif_dns_info_t d = {0};
        ip4addr_aton(dns, (ip4_addr_t *)&d.ip.u_addr.ip4);
        d.ip.type = ESP_IPADDR_TYPE_V4;
        esp_netif_set_dns_info(s_netif, ESP_NETIF_DNS_MAIN, &d);
    }
}

/* ── connect helpers ──────────────────────────────────────────────────── */

static void try_connect(const char *ssid, const char *pass)
{
    wifi_config_t cfg = {0};
    strncpy((char *)cfg.sta.ssid,     ssid, sizeof(cfg.sta.ssid)     - 1);
    strncpy((char *)cfg.sta.password, pass, sizeof(cfg.sta.password) - 1);
    cfg.sta.failure_retry_cnt = 2;
    esp_wifi_set_config(WIFI_IF_STA, &cfg);
    esp_wifi_connect();
    ESP_LOGI(TAG, "trying SSID '%s'", ssid);
}

static void try_open_ap(void)
{
    wifi_scan_config_t sc = {
        .ssid = NULL, .channel = 0, .show_hidden = false,
        .scan_type = WIFI_SCAN_TYPE_ACTIVE,
        .scan_time.active.min = 100, .scan_time.active.max = 300,
    };
    esp_wifi_scan_start(&sc, false);   /* result via WIFI_EVENT_SCAN_DONE */
}

/* ── WiFi event handler ───────────────────────────────────────────────── */

static void on_wifi(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT) {
        switch (id) {
        case WIFI_EVENT_STA_START:
            /* After start: try credentials in order */
            if (has_str(CONFIG_INDEX_WIFI1_SSID)) {
                char ssid[33], pass[65];
                load_str(CONFIG_INDEX_WIFI1_SSID, ssid, sizeof(ssid));
                load_str(CONFIG_INDEX_WIFI1_PASS, pass, sizeof(pass));
                try_connect(ssid, pass);
            } else if (has_str(CONFIG_INDEX_WIFI2_SSID)) {
                char ssid[33], pass[65];
                load_str(CONFIG_INDEX_WIFI2_SSID, ssid, sizeof(ssid));
                load_str(CONFIG_INDEX_WIFI2_PASS, pass, sizeof(pass));
                try_connect(ssid, pass);
            } else {
                uint8_t open = 0;
                config_get_u8(CONFIG_INDEX_WIFI_OPEN, &open);
                if (open) try_open_ap();
            }
            break;

        case WIFI_EVENT_STA_DISCONNECTED: {
            wifi_event_sta_disconnected_t *ev = data;
            ESP_LOGW(TAG, "disconnected (reason=%d), retry in %ds", ev->reason, RECONNECT_MS/1000);
            s_connected = false;
            /* If we tried wifi1 and failed, try wifi2, then open */
            static int attempt = 0;
            attempt++;
            if (attempt == 1 && has_str(CONFIG_INDEX_WIFI2_SSID)) {
                char ssid[33], pass[65];
                load_str(CONFIG_INDEX_WIFI2_SSID, ssid, sizeof(ssid));
                load_str(CONFIG_INDEX_WIFI2_PASS, pass, sizeof(pass));
                try_connect(ssid, pass);
            } else if (attempt <= 2) {
                uint8_t open = 0;
                config_get_u8(CONFIG_INDEX_WIFI_OPEN, &open);
                if (open) { try_open_ap(); }
                else { vTaskDelay(pdMS_TO_TICKS(RECONNECT_MS)); esp_wifi_connect(); }
            } else {
                attempt = 0;
                vTaskDelay(pdMS_TO_TICKS(RECONNECT_MS));
                esp_wifi_connect();
            }
            break;
        }

        case WIFI_EVENT_SCAN_DONE: {
            uint16_t n = 0; esp_wifi_scan_get_ap_num(&n);
            if (n == 0) { ESP_LOGW(TAG, "no APs found"); break; }
            wifi_ap_record_t *aps = malloc(n * sizeof(*aps));
            if (!aps) break;
            esp_wifi_scan_get_ap_records(&n, aps);
            bool found = false;
            /* Try wifi1/wifi2 SSID in scan results first */
            char ssid1[33], ssid2[33], pass1[65], pass2[65];
            load_str(CONFIG_INDEX_WIFI1_SSID, ssid1, sizeof(ssid1));
            load_str(CONFIG_INDEX_WIFI1_PASS, pass1, sizeof(pass1));
            load_str(CONFIG_INDEX_WIFI2_SSID, ssid2, sizeof(ssid2));
            load_str(CONFIG_INDEX_WIFI2_PASS, pass2, sizeof(pass2));
            for (int i = 0; i < n && !found; i++) {
                const char *s = (char *)aps[i].ssid;
                if (ssid1[0] && strcmp(s, ssid1) == 0) { try_connect(ssid1, pass1); found = true; }
                else if (ssid2[0] && strcmp(s, ssid2) == 0) { try_connect(ssid2, pass2); found = true; }
            }
            if (!found) {
                /* Connect to strongest open AP */
                for (int i = 0; i < n && !found; i++) {
                    if (aps[i].authmode == WIFI_AUTH_OPEN) {
                        wifi_config_t cfg = {0};
                        memcpy(cfg.sta.ssid, aps[i].ssid, sizeof(cfg.sta.ssid));
                        esp_wifi_set_config(WIFI_IF_STA, &cfg);
                        esp_wifi_connect();
                        ESP_LOGI(TAG, "connecting to open AP '%s'", (char *)aps[i].ssid);
                        found = true;
                    }
                }
            }
            if (!found) ESP_LOGW(TAG, "no matching AP in scan");
            free(aps);
            break;
        }

        default: break;
        }
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *ev = data;
        ESP_LOGI(TAG, "connected, IP=" IPSTR, IP2STR(&ev->ip_info.ip));
        s_connected = true;
        xEventGroupSetBits(s_evg, EV_CONNECTED);

        if (s_sniff_mode == SNIFF_MODE_REALTIME) {
            /* Stay in STA mode, enable promiscuous on the connected channel */
            wifi_promiscuous_filter_t f = {
                .filter_mask = WIFI_PROMIS_FILTER_MASK_ALL & ~WIFI_PROMIS_FILTER_MASK_FCSFAIL
            };
            esp_wifi_set_promiscuous_filter(&f);
            esp_wifi_set_promiscuous(true);
            ESP_LOGI(TAG, "realtime mode: STA + promiscuous active");
        }
        /* CYCLE mode: cycle_task handles the timing */
    }
}

/* ── cycle task ───────────────────────────────────────────────────────── */

static void cycle_task(void *arg)
{
    ESP_LOGI(TAG, "cycle task started (sniff=%lums wifi=%lums)",
             (unsigned long)s_sniff_ms, (unsigned long)s_wifi_ms);
    for (;;) {
        /* Sniff window — sniffer runs normally */
        vTaskDelay(pdMS_TO_TICKS(s_sniff_ms));

        if (!s_connected) { continue; }  /* stay in sniff if not connected */

        /* WiFi window — pause sniffer, let MQTT flush */
        sniffer_pause();
        vTaskDelay(pdMS_TO_TICKS(s_wifi_ms));
        sniffer_resume();
    }
}

/* ── public API ───────────────────────────────────────────────────────── */

bool wifi_manager_is_connected(void) { return s_connected; }

esp_err_t wifi_manager_init(void)
{
    /* Check if WiFi mode is configured at all */
    uint8_t conn = CONN_MODE_BLE;
    config_get_u8(CONFIG_INDEX_CONN_MODE, &conn);
    s_conn_mode = conn;

    if (conn == CONN_MODE_BLE) {
        ESP_LOGI(TAG, "BLE-only mode — WiFi manager inactive");
        return ESP_ERR_NOT_SUPPORTED;
    }

    /* Read sniff mode and timing */
    s_sniff_mode = SNIFF_MODE_CYCLE;
    config_get_u8(CONFIG_INDEX_SNIFF_MODE, &s_sniff_mode);

    s_sniff_ms = SNIFF_MS_DEFAULT;
    s_wifi_ms  = WIFI_MS_DEFAULT;
    config_get_u32(CONFIG_INDEX_SNIFF_MS, &s_sniff_ms);
    config_get_u32(CONFIG_INDEX_WIFI_MS,  &s_wifi_ms);
    if (s_sniff_ms < 500)  s_sniff_ms = SNIFF_MS_DEFAULT;
    if (s_wifi_ms  < 200)  s_wifi_ms  = WIFI_MS_DEFAULT;

    ESP_LOGI(TAG, "conn_mode=%d sniff_mode=%d sniff=%lums wifi=%lums",
             conn, s_sniff_mode,
             (unsigned long)s_sniff_ms, (unsigned long)s_wifi_ms);

    s_evg = xEventGroupCreate();

    ESP_ERROR_CHECK(esp_netif_init());
    s_netif = esp_netif_create_default_wifi_sta();
    apply_ip_config();

    /* WiFi already initialised in NULL mode by initialize_wifi() in main.c */
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_wifi, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT,   IP_EVENT_STA_GOT_IP, on_wifi, NULL));

    /* Country DE — allow ch 12/13 + 5 GHz */
    wifi_country_t country = { .cc = "DE", .schan = 1, .nchan = 13,
                                .max_tx_power = 20,
                                .policy = WIFI_COUNTRY_POLICY_MANUAL };
    esp_wifi_set_country(&country);

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_start());     /* triggers WIFI_EVENT_STA_START */

    if (s_sniff_mode != SNIFF_MODE_REALTIME) {
        /* Cycle / individual: start timing task */
        xTaskCreate(cycle_task, "wifi_cycle", 4096, NULL, 4, &s_cycle_task);
    }

    ESP_LOGI(TAG, "WiFi manager started");
    return ESP_OK;
}
