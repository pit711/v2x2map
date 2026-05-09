#include "sdkconfig.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "linenoise/linenoise.h"
#include "esp_console.h"
#include "esp_crt_bundle.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_vfs_fat.h"
#include "esp_wifi.h"
#include "nvs_flash.h"

#include "cmd_sniffer.h"
#include "cmd_pcap.h"
#include "config.h"
#include "ethernet.h"
#include "led.h"
#include "mqtt.h"
#include "ota.h"
#include "sdcard.h"
#include "spi.h"
#include "temperature.h"
#include "usb_stream.h"
#include "bt_stream.h"
#include "wifi_manager.h"

#if CONFIG_SNIFFER_STORE_HISTORY
#define HISTORY_MOUNT_POINT "/data"
#define HISTORY_FILE_PATH HISTORY_MOUNT_POINT "/history.txt"
#endif

static const char TAG[] = "MAIN";

/* Initialize filesystem */
static void initialize_filesystem(void)
{
    static wl_handle_t wl_handle;
    const esp_vfs_fat_mount_config_t mount_config = {
        .max_files = 4,
        .format_if_mount_failed = true
    };
    esp_err_t err = esp_vfs_fat_spiflash_mount_rw_wl(HISTORY_MOUNT_POINT, "storage", &mount_config, &wl_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to mount FATFS (%s)", esp_err_to_name(err));
        return;
    }
}

/* Initialize wifi with tcp/ip adapter */
static void initialize_wifi(void)
{
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_NULL));
}

static esp_console_repl_t *repl;
static void console_init(void)
{
    // 31 buffer size - length of colors and 1 space after prompt - 1 closing angle bracket - 1 null byte
    const size_t max_nodeid_length = 31 - (sizeof(LOG_COLOR_I " " LOG_RESET_COLOR) - 1) - 1 - 1;

    char prompt[CONFIG_NODEID_BUFFER_SIZE];
    size_t size = sizeof(prompt);
    ESP_ERROR_CHECK(config_get_str(CONFIG_INDEX_NODEID, prompt, &size));

    if (size - 1 > max_nodeid_length)
        strcpy(&prompt[max_nodeid_length - 3], "...");

    strcat(prompt, ">");

    esp_console_repl_config_t repl_config = ESP_CONSOLE_REPL_CONFIG_DEFAULT();
#if CONFIG_SNIFFER_STORE_HISTORY
    repl_config.history_save_path = HISTORY_FILE_PATH;
#endif
    repl_config.prompt = prompt;

    // install console REPL environment
#if CONFIG_ESP_CONSOLE_UART
    esp_console_dev_uart_config_t uart_config = ESP_CONSOLE_DEV_UART_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_uart(&uart_config, &repl_config, &repl));
#elif CONFIG_ESP_CONSOLE_USB_CDC
    esp_console_dev_usb_cdc_config_t cdc_config = ESP_CONSOLE_DEV_CDC_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_usb_cdc(&cdc_config, &repl_config, &repl));
#elif CONFIG_ESP_CONSOLE_USB_SERIAL_JTAG
    esp_console_dev_usb_serial_jtag_config_t usbjtag_config = ESP_CONSOLE_DEV_USB_SERIAL_JTAG_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_usb_serial_jtag(&usbjtag_config, &repl_config, &repl));
#endif
}

int cmd_reboot(int argc, char **argv)
{
    (void)argc;
    (void)argv;

    esp_restart();

    return 1;
}

void register_reboot(void)
{
    const esp_console_cmd_t cmd = {
        .command = "reboot",
        .help = "reboot the device",
        .hint = NULL,
        .func = &cmd_reboot,
    };
    ESP_ERROR_CHECK(esp_console_cmd_register(&cmd));
}

void app_main(void)
{
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_timer_init();

    config_init();

    led_init();

    initialize_filesystem();

    initialize_spi();

    // Make sure MQTT has registered its event handlers before Ethernet goes up
    mqtt_init();

    /*--- Initialize Network ---*/
    /* Initialize WiFi */
    initialize_wifi();
    /* Initialize Ethernet */
    initialize_ethernet();

    /*--- Initialize Console ---*/
    console_init();

    sniffer_init();
    ota_init();

#ifdef CONFIG_ENABLE_TEMPERATURE
    temperature_init();
#endif

    /* Register commands */
#if CONFIG_ENABLE_SD
    sdcard_register_commands();
#endif
    register_sniffer_cmd();
    register_pcap_cmd();

    config_register_commands();

    register_reboot();

    register_ota_cmd();

    usb_stream_init();
    usb_config_rx_start();   /* USB RX config channel for setup wizard */
    bt_stream_init();

    /* Start WiFi manager if configured; BLE always available */
    wifi_manager_init();     /* returns ESP_ERR_NOT_SUPPORTED for BLE-only */

    sniffer_autostart();

    usb_stream_send_test_frame();

    // start console REPL
    ESP_ERROR_CHECK(esp_console_start_repl(repl));

    led_update();
}
