#include "sdkconfig.h"

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

#include "esp_console.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "mbedtls/base64.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "argtable3/argtable3.h"

#include "config.h"

static const char TAG[] = "CONFIG";

static nvs_handle_t handle;

typedef esp_err_t (*config_u8_getter)  (uint8_t  *out);
typedef esp_err_t (*config_u16_getter) (uint16_t *out);
typedef esp_err_t (*config_u32_getter) (uint32_t *out);
typedef esp_err_t (*config_u64_getter) (uint64_t *out);
typedef esp_err_t (*config_i8_getter)  (int8_t   *out);
typedef esp_err_t (*config_i16_getter) (int16_t  *out);
typedef esp_err_t (*config_i32_getter) (int32_t  *out);
typedef esp_err_t (*config_i64_getter) (int64_t  *out);
typedef esp_err_t (*config_str_getter) (char *out, size_t *size);
typedef esp_err_t (*config_blob_getter)(uint8_t *out, size_t *size);

typedef esp_err_t (*config_u8_setter)  (uint8_t  val);
typedef esp_err_t (*config_u16_setter) (uint16_t val);
typedef esp_err_t (*config_u32_setter) (uint32_t val);
typedef esp_err_t (*config_u64_setter) (uint64_t val);
typedef esp_err_t (*config_i8_setter)  (int8_t   val);
typedef esp_err_t (*config_i16_setter) (int16_t  val);
typedef esp_err_t (*config_i32_setter) (int32_t  val);
typedef esp_err_t (*config_i64_setter) (int64_t  val);
typedef esp_err_t (*config_str_setter) (const char *val);
typedef esp_err_t (*config_blob_setter)(const uint8_t *val, size_t size);

typedef struct config_key {
    char name[NVS_KEY_NAME_MAX_SIZE];
    nvs_type_t type;
    size_t buffer_size;
    void *getter;
    void *setter;
} config_key_t;

static esp_err_t config_get_node_id(char *out, size_t *size);
static esp_err_t config_get_mqtt_uri(char *out, size_t *size);
static esp_err_t config_get_autostart_chan(uint32_t *autostart_chan);
static esp_err_t config_get_broadcast_only(uint8_t *broadcast_only);
static esp_err_t config_get_led_brightness(uint8_t *led_brightness);

static const config_key_t config_keys[] = {
    [CONFIG_INDEX_NODEID]         = { "nodeid",           NVS_TYPE_STR, CONFIG_NODEID_BUFFER_SIZE,   config_get_node_id,        NULL },
    [CONFIG_INDEX_MQTT_URI]       = { "mqtturi",          NVS_TYPE_STR, CONFIG_MQTT_URI_BUFFER_SIZE, config_get_mqtt_uri,       NULL },
    [CONFIG_INDEX_ETH_IP]         = { "ethip",            NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,     NULL,                      NULL },
    [CONFIG_INDEX_ETH_NETMASK]    = { "ethnm",            NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,     NULL,                      NULL },
    [CONFIG_INDEX_ETH_GATEWAY]    = { "ethgw",            NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,     NULL,                      NULL },
    [CONFIG_INDEX_ETH_DNS0]       = { "ethdns0",          NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,     NULL,                      NULL },
    [CONFIG_INDEX_ETH_DNS1]       = { "ethdns1",          NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,     NULL,                      NULL },
    [CONFIG_INDEX_ETH_DNS2]       = { "ethdns2",          NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,     NULL,                      NULL },
    [CONFIG_INDEX_AUTOSTART_CHAN] = { "autostartchan",    NVS_TYPE_U32, 0,                           config_get_autostart_chan, NULL },
    [CONFIG_INDEX_BROADCAST_ONLY] = { "broadcastonly",    NVS_TYPE_U8,  0,                           config_get_broadcast_only, NULL },
    [CONFIG_INDEX_LED_BRIGHTNESS] = { "ledbrightness",    NVS_TYPE_U8,  0,                           config_get_led_brightness, NULL },
    [CONFIG_INDEX_WIFI1_SSID]    = { "wifi1ssid",  NVS_TYPE_STR, CONFIG_WIFI_SSID_BUFFER_SIZE, NULL, NULL },
    [CONFIG_INDEX_WIFI1_PASS]    = { "wifi1pass",  NVS_TYPE_STR, CONFIG_WIFI_PASS_BUFFER_SIZE, NULL, NULL },
    [CONFIG_INDEX_WIFI2_SSID]    = { "wifi2ssid",  NVS_TYPE_STR, CONFIG_WIFI_SSID_BUFFER_SIZE, NULL, NULL },
    [CONFIG_INDEX_WIFI2_PASS]    = { "wifi2pass",  NVS_TYPE_STR, CONFIG_WIFI_PASS_BUFFER_SIZE, NULL, NULL },
    [CONFIG_INDEX_WIFI_OPEN]     = { "wifiopen",   NVS_TYPE_U8,  0,                            NULL, NULL },
    [CONFIG_INDEX_CONN_MODE]     = { "connmode",   NVS_TYPE_U8,  0,                            NULL, NULL },
    [CONFIG_INDEX_WIFI_IP]       = { "wifiip",     NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,      NULL, NULL },
    [CONFIG_INDEX_WIFI_NM]       = { "wifinm",     NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,      NULL, NULL },
    [CONFIG_INDEX_WIFI_GW]       = { "wifigw",     NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,      NULL, NULL },
    [CONFIG_INDEX_WIFI_DNS]      = { "wifidns",    NVS_TYPE_STR, CONFIG_IPV4_BUFFER_SIZE,      NULL, NULL },
    [CONFIG_INDEX_SNIFF_MODE]    = { "sniffmode",  NVS_TYPE_U8,  0,                            NULL, NULL },
    [CONFIG_INDEX_SNIFF_MS]      = { "sniffms",    NVS_TYPE_U32, 0,                            NULL, NULL },
    [CONFIG_INDEX_WIFI_MS]       = { "wifims",     NVS_TYPE_U32, 0,                            NULL, NULL },
};
#define CONFIG_KEYS_END (&config_keys[sizeof(config_keys)/sizeof(*config_keys)])


void config_init(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_ERROR_CHECK(nvs_open("its", NVS_READWRITE, &handle));
}

static esp_err_t config_get_node_id(char *out, size_t *size)
{
    size_t size_copy = *size;

    esp_err_t res = nvs_get_str(handle, "nodeid", out, &size_copy);
    if (res != ESP_OK)
    {
        if (res != ESP_ERR_NVS_NOT_FOUND)
            ESP_LOGW(TAG, "nvs_get_str failed: %s", esp_err_to_name(res));

        int print_res;
        {
            uint8_t eth_mac[6];
            ESP_ERROR_CHECK(esp_read_mac(eth_mac, ESP_MAC_ETH));

            // Espressif...
            eth_mac[0] |= 2;

            print_res = snprintf(out, *size, "V2X2MAP%02hhx%02hhx",
                                 eth_mac[4], eth_mac[5]);
        }

        if (print_res > *size - 1)
        {
            ESP_LOGE(TAG, "buffer too small to print");
            return ESP_ERR_INVALID_SIZE;
        }

        if (print_res < 0)
        {
            ESP_LOGE(TAG, "snprintf failed");
            return ESP_ERR_INVALID_STATE;
        }

        size_copy = print_res + 1;
    }

    *size = size_copy;

    return ESP_OK;
}

static esp_err_t config_get_mqtt_uri(char *out, size_t *size)
{
    size_t size_copy = *size;

    esp_err_t res = nvs_get_str(handle, "mqtturi", out, &size_copy);
    if (res != ESP_OK)
    {
        if (res != ESP_ERR_NVS_NOT_FOUND)
            ESP_LOGW(TAG, "nvs_get_str failed: %s", esp_err_to_name(res));

        int print_res = snprintf(out, *size, "%s", "mqtts://cits1.opentrafficmap.org");

        if (print_res > *size - 1)
        {
            ESP_LOGE(TAG, "buffer too small to print");
            return ESP_ERR_INVALID_SIZE;
        }

        if (print_res < 0)
        {
            ESP_LOGE(TAG, "snprintf failed");
            return ESP_ERR_INVALID_STATE;
        }

        size_copy = print_res + 1;
    }

    *size = size_copy;

    return ESP_OK;
}

static esp_err_t config_get_autostart_chan(uint32_t *autostart_chan)
{
    uint32_t out;
    esp_err_t res = nvs_get_u32(handle, "autostartchan", &out);
    if (res != ESP_OK)
    {
        if (res != ESP_ERR_NVS_NOT_FOUND)
            ESP_LOGE(TAG, "nvs_get_u8 failed: %s", esp_err_to_name(res));

        out = 5900;
    }

    *autostart_chan = out;
    return ESP_OK;
}

static esp_err_t config_get_broadcast_only(uint8_t *broadcast_only)
{
    uint8_t out;
    esp_err_t res = nvs_get_u8(handle, "broadcastonly", &out);
    if (res != ESP_OK)
    {
        if (res != ESP_ERR_NVS_NOT_FOUND)
            ESP_LOGE(TAG, "nvs_get_u8 failed: %s", esp_err_to_name(res));

        out = 1;
    }

    *broadcast_only = !!out;
    return ESP_OK;
}

static esp_err_t config_get_led_brightness(uint8_t *led_brightness)
{
    uint8_t out;
    esp_err_t res = nvs_get_u8(handle, "ledbrightness", &out);
    if (res != ESP_OK)
    {
        if (res != ESP_ERR_NVS_NOT_FOUND)
            ESP_LOGE(TAG, "nvs_get_u8 failed: %s", esp_err_to_name(res));

        out = 255;
    }

    *led_brightness = out;
    return ESP_OK;
}

esp_err_t config_get_u8(config_index_t index, uint8_t *out)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_U8)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type u8", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_u8_getter)key->getter)(out);
    else
        return nvs_get_u8(handle, key->name, out);
}

esp_err_t config_set_u8(config_index_t index, uint8_t value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_U8)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type u8", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->setter)
        return ((config_u8_setter)key->setter)(value);
    else
        return nvs_set_u8(handle, key->name, value);
}

esp_err_t config_get_u16(config_index_t index, uint16_t *out)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_U16)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type u16", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_u16_getter)key->getter)(out);
    else
        return nvs_get_u16(handle, key->name, out);
}

esp_err_t config_set_u16(config_index_t index, uint16_t value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_U16)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type u16", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->setter)
        return ((config_u16_setter)key->setter)(value);
    else
        return nvs_set_u16(handle, key->name, value);
}

esp_err_t config_get_u32(config_index_t index, uint32_t *out)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_U32)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type u32", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_u32_getter)key->getter)(out);
    else
        return nvs_get_u32(handle, key->name, out);
}

esp_err_t config_set_u32(config_index_t index, uint32_t value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_U32)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type u32", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->setter)
        return ((config_u32_setter)key->setter)(value);
    else
        return nvs_set_u32(handle, key->name, value);
}

esp_err_t config_get_u64(config_index_t index, uint64_t *out)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_U64)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type u64", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_u64_getter)key->getter)(out);
    else
        return nvs_get_u64(handle, key->name, out);
}

esp_err_t config_set_u64(config_index_t index, uint64_t value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_U64)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type u64", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->setter)
        return ((config_u64_setter)key->setter)(value);
    else
        return nvs_set_u64(handle, key->name, value);
}

esp_err_t config_get_i8(config_index_t index, int8_t *out)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_I8)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type i8", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_i8_getter)key->getter)(out);
    else
        return nvs_get_i8(handle, key->name, out);
}

esp_err_t config_set_i8(config_index_t index, int8_t value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_I8)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type i8", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->setter)
        return ((config_i8_setter)key->setter)(value);
    else
        return nvs_set_i8(handle, key->name, value);
}

esp_err_t config_get_i16(config_index_t index, int16_t *out)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_I16)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type i16", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_i16_getter)key->getter)(out);
    else
        return nvs_get_i16(handle, key->name, out);
}

esp_err_t config_set_i16(config_index_t index, int16_t value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_I16)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type i16", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->setter)
        return ((config_i16_setter)key->setter)(value);
    else
        return nvs_set_i16(handle, key->name, value);
}

esp_err_t config_get_i32(config_index_t index, int32_t *out)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_I32)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type i32", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_i32_getter)key->getter)(out);
    else
        return nvs_get_i32(handle, key->name, out);
}

esp_err_t config_set_i32(config_index_t index, int32_t value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_I32)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type i32", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->setter)
        return ((config_i32_setter)key->setter)(value);
    else
        return nvs_set_i32(handle, key->name, value);
}

esp_err_t config_get_i64(config_index_t index, int64_t *out)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_I64)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type i64", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_i64_getter)key->getter)(out);
    else
        return nvs_get_i64(handle, key->name, out);
}

esp_err_t config_set_i64(config_index_t index, int64_t value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_I64)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type i64", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->setter)
        return ((config_i64_setter)key->setter)(value);
    else
        return nvs_set_i64(handle, key->name, value);
}

esp_err_t config_get_str(config_index_t index, char *out, size_t *size)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_STR)
    {
        ESP_LOGE(TAG, "Attempt to get key %s with wrong type str", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (key->getter)
        return ((config_str_getter)key->getter)(out, size);
    else
        return nvs_get_str(handle, key->name, out, size);
}

esp_err_t config_set_str(config_index_t index, const char *value)
{
    const config_key_t *key = &config_keys[index];

    if (key->type != NVS_TYPE_STR)
    {
        ESP_LOGE(TAG, "Attempt to set key %s with wrong type str", key->name);
        return ESP_ERR_INVALID_ARG;
    }

    if (strlen(value) >= key->buffer_size)
        return ESP_ERR_INVALID_SIZE;

    if (key->setter)
        return ((config_str_setter)key->setter)(value);
    else
        return nvs_set_str(handle, key->name, value);
}

static const config_key_t *config_key_find(const char *name)
{
    for (const config_key_t *it = config_keys; it != CONFIG_KEYS_END; it++)
    {
        if (strcmp(name, it->name))
            continue;

        return it;
    }

    return NULL;
}

static struct {
    arg_lit_t *raw;
    arg_str_t *key;
    arg_str_t *value;
    arg_end_t *end;
} config_set_args;

static int cmd_config_set(int argc, char **argv)
{
    int nerrors = arg_parse(argc, argv, (void **)&config_set_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, config_set_args.end, argv[0]);
        return 1;
    }

    const char *key_str = config_set_args.key->sval[0];
    const char *value_str = config_set_args.value->sval[0];

    const config_key_t *key = config_key_find(key_str);
    if (key == NULL)
    {
        ESP_LOGE(TAG, "No such config key: %s", key_str);
        return 1;
    }

    esp_err_t res;
    switch (key->type)
    {
    case NVS_TYPE_U8:
    case NVS_TYPE_U16:
    case NVS_TYPE_U32:
    case NVS_TYPE_U64:
        {
            char *end;
            uint64_t parsed = strtoull(value_str, &end, 0);
            if (*end != '\0')
            {
                ESP_LOGE(TAG, "Invalid unsigned integer");
                return 1;
            }

            if ((key->type == NVS_TYPE_U8  && parsed > UINT8_MAX) ||
                (key->type == NVS_TYPE_U16 && parsed > UINT16_MAX) ||
                (key->type == NVS_TYPE_U32 && parsed > UINT32_MAX))
            {
                ESP_LOGE(TAG, "Value out of range for type");
                return 1;
            }

            if (key->setter == NULL || config_set_args.raw->count)
            {
                switch (key->type) {
                case NVS_TYPE_U8:
                    res = nvs_set_u8(handle, key_str, (uint8_t)parsed);
                    break;
                case NVS_TYPE_U16:
                    res = nvs_set_u16(handle, key_str, (uint16_t)parsed);
                    break;
                case NVS_TYPE_U32:
                    res = nvs_set_u32(handle, key_str, (uint32_t)parsed);
                    break;
                case NVS_TYPE_U64:
                    res = nvs_set_u64(handle, key_str, parsed);
                    break;
                default:
                    __builtin_unreachable();
                }
            }
            else
            {
                switch (key->type) {
                case NVS_TYPE_U8:
                    res = ((config_u8_setter)key->setter)((uint8_t)parsed);
                    break;
                case NVS_TYPE_U16:
                    res = ((config_u16_setter)key->setter)((uint16_t)parsed);
                    break;
                case NVS_TYPE_U32:
                    res = ((config_u32_setter)key->setter)((uint32_t)parsed);
                    break;
                case NVS_TYPE_U64:
                    res = ((config_u64_setter)key->setter)(parsed);
                    break;
                default:
                    __builtin_unreachable();
                }
            }
        }

        break;
    case NVS_TYPE_I8:
    case NVS_TYPE_I16:
    case NVS_TYPE_I32:
    case NVS_TYPE_I64:
        {
            char *end;
            int64_t parsed = strtoll(value_str, &end, 0);
            if (*end != '\0')
            {
                ESP_LOGE(TAG, "Invalid signed integer");
                return 1;
            }

            if ((key->type == NVS_TYPE_I8  && (parsed < INT8_MIN  || parsed > INT8_MAX)) ||
                (key->type == NVS_TYPE_I16 && (parsed < INT16_MIN || parsed > INT16_MAX)) ||
                (key->type == NVS_TYPE_I32 && (parsed < INT32_MIN || parsed > INT32_MAX)))
            {
                ESP_LOGE(TAG, "Value out of range for type");
                return 1;
            }

            if (key->setter == NULL || config_set_args.raw->count)
            {
                switch (key->type) {
                case NVS_TYPE_U8:
                    res = nvs_set_i8(handle, key_str, (int8_t)parsed);
                    break;
                case NVS_TYPE_U16:
                    res = nvs_set_i16(handle, key_str, (int16_t)parsed);
                    break;
                case NVS_TYPE_U32:
                    res = nvs_set_i32(handle, key_str, (int32_t)parsed);
                    break;
                case NVS_TYPE_U64:
                    res = nvs_set_i64(handle, key_str, parsed);
                    break;
                default:
                    __builtin_unreachable();
                }
            }
            else
            {
                switch (key->type) {
                case NVS_TYPE_U8:
                    res = ((config_i8_setter)key->setter)((int8_t)parsed);
                    break;
                case NVS_TYPE_U16:
                    res = ((config_i16_setter)key->setter)((int16_t)parsed);
                    break;
                case NVS_TYPE_U32:
                    res = ((config_i32_setter)key->setter)((int32_t)parsed);
                    break;
                case NVS_TYPE_U64:
                    res = ((config_i64_setter)key->setter)(parsed);
                    break;
                default:
                    __builtin_unreachable();
                }
            }
        }

        break;
    case NVS_TYPE_STR:
        if (strlen(value_str) >= key->buffer_size)
        {
            ESP_LOGE(TAG, "Value too long");
            return 1;
        }

        if (key->setter == NULL || config_set_args.raw->count)
            res = nvs_set_str(handle, key_str, value_str);
        else
            res = ((config_str_setter)key->setter)(value_str);
        break;
    case NVS_TYPE_BLOB:
        {
            unsigned char *buf = malloc(key->buffer_size);
            size_t olen;
            int result = mbedtls_base64_decode(buf, key->buffer_size, &olen, (const uint8_t *)value_str, strlen(value_str));
            if (result != 0)
            {
                ESP_LOGE(TAG, "mbedtls_base64_decode failed: %#x", result);
                free(buf);
                return 1;
            }

            if (olen > key->buffer_size)
            {
                ESP_LOGE(TAG, "Value too long");
                free(buf);
                return 1;
            }

            if (key->setter == NULL || config_set_args.raw->count)
                res = nvs_set_blob(handle, key_str, buf, olen);
            else
                res = ((config_blob_setter)key->setter)(buf, olen);
            free(buf);
        }
        break;
    default:
        __builtin_unreachable();
        // avoid compiler warning
        res = ESP_ERR_INVALID_STATE;
    }

    if (res != ESP_OK)
    {
        ESP_LOGE(TAG, "set failed: %s", esp_err_to_name(res));
        return 1;
    }

    return 0;
}

void register_config_set(void)
{
    config_set_args.raw = arg_lit0("R", NULL, "skip setter functions, directly write NVS");
    config_set_args.key = arg_str1(NULL, NULL, "key", "the key");
    config_set_args.value = arg_str1(NULL, NULL, "value", "the value to set");
    config_set_args.end = arg_end(1);

    const esp_console_cmd_t cmd = {
        .command = "config_set",
        .help = "set a config key",
        .hint = NULL,
        .func = &cmd_config_set,
        .argtable = &config_set_args
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
}

static struct {
    arg_lit_t *raw;
    arg_str_t *key;
    arg_end_t *end;
} config_get_args;

static int cmd_config_get(int argc, char **argv)
{
    int nerrors = arg_parse(argc, argv, (void **)&config_get_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, config_get_args.end, argv[0]);
        return 1;
    }

    const char *key_str = config_get_args.key->sval[0];

    const config_key_t *key = config_key_find(key_str);
    if (key == NULL)
    {
        ESP_LOGE(TAG, "No such config key: %s", key_str);
        return 1;
    }

    esp_err_t res;

    switch (key->type)
    {
    case NVS_TYPE_U8:
    case NVS_TYPE_U16:
    case NVS_TYPE_U32:
    case NVS_TYPE_U64:
        {
            uint64_t value = 0;

            if (key->getter == NULL || config_get_args.raw->count)
            {
                switch (key->type) {
                case NVS_TYPE_U8:
                    res = nvs_get_u8(handle, key_str, (uint8_t *)&value);
                    break;
                case NVS_TYPE_U16:
                    res = nvs_get_u16(handle, key_str, (uint16_t *)&value);
                    break;
                case NVS_TYPE_U32:
                    res = nvs_get_u32(handle, key_str, (uint32_t *)&value);
                    break;
                case NVS_TYPE_U64:
                    res = nvs_get_u64(handle, key_str, &value);
                    break;
                default:
                    __builtin_unreachable();
                }
            }
            else
            {
                switch (key->type) {
                case NVS_TYPE_U8:
                    res = ((config_u8_getter)key->getter)((uint8_t *)&value);
                    break;
                case NVS_TYPE_U16:
                    res = ((config_u16_getter)key->getter)((uint16_t *)&value);
                    break;
                case NVS_TYPE_U32:
                    res = ((config_u32_getter)key->getter)((uint32_t *)&value);
                    break;
                case NVS_TYPE_U64:
                    res = ((config_u64_getter)key->getter)(&value);
                    break;
                default:
                    __builtin_unreachable();
                }
            }

            if (res == ESP_OK)
                printf("%"PRIu64 "\n", value);
        }

        break;
    case NVS_TYPE_I8:
    case NVS_TYPE_I16:
    case NVS_TYPE_I32:
    case NVS_TYPE_I64:
        {
            int64_t value = 0;

            if (key->getter == NULL || config_get_args.raw->count)
            {
                switch (key->type) {
                case NVS_TYPE_I8:
                    res = nvs_get_i8(handle, key_str, (int8_t *)&value);
                    value = (int64_t)(int8_t)value;
                    break;
                case NVS_TYPE_I16:
                    res = nvs_get_i16(handle, key_str, (int16_t *)&value);
                    value = (int64_t)(int16_t)value;
                    break;
                case NVS_TYPE_I32:
                    res = nvs_get_i32(handle, key_str, (int32_t *)&value);
                    value = (int64_t)(int32_t)value;
                    break;
                case NVS_TYPE_I64:
                    res = nvs_get_i64(handle, key_str, &value);
                    break;
                default:
                    __builtin_unreachable();
                }
            }
            else
            {
                switch (key->type) {
                case NVS_TYPE_I8:
                    res = ((config_i8_getter)key->getter)((int8_t *)&value);
                    value = (int64_t)(int8_t)value;
                    break;
                case NVS_TYPE_I16:
                    res = ((config_i16_getter)key->getter)((int16_t *)&value);
                    value = (int64_t)(int16_t)value;
                    break;
                case NVS_TYPE_I32:
                    res = ((config_i32_getter)key->getter)((int32_t *)&value);
                    value = (int64_t)(int32_t)value;
                    break;
                case NVS_TYPE_I64:
                    res = ((config_i64_getter)key->getter)(&value);
                    break;
                default:
                    __builtin_unreachable();
                }
            }

            if (res == ESP_OK)
                printf("%"PRIi64 "\n", value);
        }

        break;
    case NVS_TYPE_STR:
        char *value = malloc(key->buffer_size);
        size_t size = key->buffer_size;

        if (key->getter == NULL || config_get_args.raw->count)
            res = nvs_get_str(handle, key_str, value, &size);
        else
            res = ((config_str_getter)key->getter)(value, &size);

        if (res == ESP_OK)
            printf("%s\n", value);

        free(value);
        break;
    case NVS_TYPE_BLOB:
        {
            unsigned char *buf = malloc(key->buffer_size);
            size_t actual_size;

            if (key->getter == NULL || config_get_args.raw->count)
                res = nvs_get_blob(handle, key_str, buf, &actual_size);
            else
                res = ((config_blob_getter)key->getter)(buf, &actual_size);

            if (res != ESP_OK)
            {
                free(buf);
                break;
            }

            const size_t base64_buffer_size = (((4 * key->buffer_size / 3) + 3) & ~3) + 1;
            unsigned char *base64_buf = malloc(base64_buffer_size);
            size_t olen;
            int result = mbedtls_base64_encode(base64_buf, base64_buffer_size, &olen, (const uint8_t *)buf, actual_size);
            if (result != 0)
            {
                ESP_LOGE(TAG, "mbedtls_base64_encode failed: %#x", result);
                free(buf);
                free(base64_buf);
                return 1;
            }

            printf("%s\n", base64_buf);
            free(buf);
            free(base64_buf);
        }
        break;
    default:
        __builtin_unreachable();
        // avoid compiler warning
        res = ESP_ERR_INVALID_STATE;
    }

    if (res != ESP_OK)
    {
        ESP_LOGE(TAG, "get failed: %s", esp_err_to_name(res));
        return 1;
    }

    return 0;
}

void register_config_get(void)
{
    config_get_args.raw = arg_lit0("R", NULL, "skip getter functions, directly read NVS");
    config_get_args.key = arg_str1(NULL, NULL, "key", "the key");
    config_get_args.end = arg_end(1);

    const esp_console_cmd_t cmd = {
        .command = "config_get",
        .help = "get a config key",
        .hint = NULL,
        .func = &cmd_config_get,
        .argtable = &config_get_args
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
}

static struct {
    arg_str_t *key;
    arg_end_t *end;
} config_clear_args;

static int cmd_config_clear(int argc, char **argv)
{
    int nerrors = arg_parse(argc, argv, (void **)&config_clear_args);
    if (nerrors != 0) {
        arg_print_errors(stderr, config_clear_args.end, argv[0]);
        return 1;
    }

    int ret = 0;
    for (int i = 0; i < config_clear_args.key->count; i++)
    {
        int res = nvs_erase_key(handle, config_clear_args.key->sval[i]);
        if (res != ESP_OK && res != ESP_ERR_NVS_NOT_FOUND)
        {
            ESP_LOGE(TAG, "nvs_erase_key failed: %s", esp_err_to_name(res));
            ret = 1;
        }
    }

    return ret;
}

void register_config_clear(void)
{
    config_clear_args.key = arg_strn(NULL, NULL, "key", 1, 20, "the key(s) to clear");
    config_clear_args.end = arg_end(1);

    const esp_console_cmd_t cmd = {
        .command = "config_clear",
        .help = "clear one or more config keys",
        .hint = NULL,
        .func = &cmd_config_clear,
        .argtable = &config_clear_args
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
}

void config_register_commands(void)
{
    register_config_set();
    register_config_get();
    register_config_clear();
}
