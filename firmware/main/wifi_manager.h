#pragma once
#include <stdbool.h>
#include "esp_err.h"

/* Connection mode (CONFIG_INDEX_CONN_MODE) */
#define CONN_MODE_BLE   0   /* BLE/USB only — no WiFi */
#define CONN_MODE_WIFI  1   /* WiFi only */
#define CONN_MODE_BOTH  2   /* WiFi + BLE */

/* Sniffer mode (CONFIG_INDEX_SNIFF_MODE) */
#define SNIFF_MODE_REALTIME   0  /* STA + promiscuous on same channel */
#define SNIFF_MODE_CYCLE      1  /* time-slice: sniff_ms sniff / wifi_ms WiFi */
#define SNIFF_MODE_INDIVIDUAL 2  /* same as cycle, user-configured timing */

#define SNIFF_MS_DEFAULT  10000u
#define WIFI_MS_DEFAULT    2000u

/**
 * Load WiFi config from NVS, register event handlers, start connect task.
 * Call once from app_main() after config_init().
 * Returns ESP_OK if WiFi mode is configured, ESP_ERR_NOT_SUPPORTED if BLE-only.
 */
esp_err_t wifi_manager_init(void);

/** True once the station is associated and has an IP. */
bool wifi_manager_is_connected(void);

#ifdef __cplusplus
extern "C" {}
#endif
