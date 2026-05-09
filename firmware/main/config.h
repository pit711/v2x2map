#pragma once

#include "esp_err.h"

#define CONFIG_NODEID_BUFFER_SIZE 64
#define CONFIG_MQTT_URI_BUFFER_SIZE 256
#define CONFIG_IPV4_BUFFER_SIZE 16

void config_init(void);

typedef enum {
    CONFIG_INDEX_NODEID,
    CONFIG_INDEX_MQTT_URI,
    CONFIG_INDEX_ETH_IP,
    CONFIG_INDEX_ETH_NETMASK,
    CONFIG_INDEX_ETH_GATEWAY,
    CONFIG_INDEX_ETH_DNS0,
    CONFIG_INDEX_ETH_DNS1,
    CONFIG_INDEX_ETH_DNS2,
    CONFIG_INDEX_AUTOSTART_CHAN,
    CONFIG_INDEX_BROADCAST_ONLY,
    CONFIG_INDEX_LED_BRIGHTNESS,
    /* WiFi credentials + behaviour */
    CONFIG_INDEX_WIFI1_SSID,
    CONFIG_INDEX_WIFI1_PASS,
    CONFIG_INDEX_WIFI2_SSID,
    CONFIG_INDEX_WIFI2_PASS,
    CONFIG_INDEX_WIFI_OPEN,    /* u8: 1 = connect to any open AP */
    CONFIG_INDEX_CONN_MODE,    /* u8: 0=ble 1=wifi 2=both */
    CONFIG_INDEX_WIFI_IP,      /* str: static IP or "" for DHCP */
    CONFIG_INDEX_WIFI_NM,
    CONFIG_INDEX_WIFI_GW,
    CONFIG_INDEX_WIFI_DNS,
    CONFIG_INDEX_SNIFF_MODE,   /* u8: 0=realtime 1=cycle 2=individual */
    CONFIG_INDEX_SNIFF_MS,     /* u32: sniff window ms  (default 10000) */
    CONFIG_INDEX_WIFI_MS,      /* u32: WiFi window ms   (default 2000)  */
} config_index_t;

#define CONFIG_WIFI_SSID_BUFFER_SIZE  33
#define CONFIG_WIFI_PASS_BUFFER_SIZE  65

esp_err_t config_get_u8(config_index_t index, uint8_t *out);
esp_err_t config_set_u8(config_index_t index, uint8_t value);
esp_err_t config_get_u16(config_index_t index, uint16_t *out);
esp_err_t config_set_u16(config_index_t index, uint16_t value);
esp_err_t config_get_u32(config_index_t index, uint32_t *out);
esp_err_t config_set_u32(config_index_t index, uint32_t value);
esp_err_t config_get_u64(config_index_t index, uint64_t *out);
esp_err_t config_set_u64(config_index_t index, uint64_t value);
esp_err_t config_get_i8(config_index_t index, int8_t *out);
esp_err_t config_set_i8(config_index_t index, int8_t value);
esp_err_t config_get_i16(config_index_t index, int16_t *out);
esp_err_t config_set_i16(config_index_t index, int16_t value);
esp_err_t config_get_i32(config_index_t index, int32_t *out);
esp_err_t config_set_i32(config_index_t index, int32_t value);
esp_err_t config_get_i64(config_index_t index, int64_t *out);
esp_err_t config_set_i64(config_index_t index, int64_t value);

esp_err_t config_get_str(config_index_t index, char *out, size_t *size);
esp_err_t config_set_str(config_index_t index, const char *value);



void config_register_commands(void);
