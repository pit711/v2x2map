#include "bt_stream.h"

#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"

#include "esp_err.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_coexist.h"
#include "esp_wifi.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "driver/usb_serial_jtag.h"

#include "cmd_sniffer.h"
#include "led.h"

/* Private NimBLE API: forces the controller's public address. Used here so
 * old Android stacks (Galaxy S6 / AOSP) accept the advertiser — they ignore
 * adv from random-static addresses they have not paired with.
 */
extern int ble_hs_id_set_pub(const uint8_t *pub_addr);

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs_mbuf.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char TAG[] = "BT_STREAM";

#define DEVICE_NAME            "ITS-G5-RX"
#define HEADER_LEN             14
#define MAX_PAYLOAD            2048
#define FRAME_QUEUE_DEPTH      16

/* Vendor UUIDs (NimBLE stores them little-endian, so the bytes below are
 * reversed vs. the canonical string form):
 *   Service:  b6e57e90-12d8-4a47-9b21-3f0000000001
 *   Notify :  b6e57e90-12d8-4a47-9b21-3f0000000002
 *   Config :  b6e57e90-12d8-4a47-9b21-3f0000000003 (8 B = 4×u16 LE)
 */
static const ble_uuid128_t SVC_UUID = BLE_UUID128_INIT(
    0x01, 0x00, 0x00, 0x00, 0x00, 0x3f, 0x21, 0x9b,
    0x47, 0x4a, 0xd8, 0x12, 0x90, 0x7e, 0xe5, 0xb6);

static const ble_uuid128_t CHR_UUID = BLE_UUID128_INIT(
    0x02, 0x00, 0x00, 0x00, 0x00, 0x3f, 0x21, 0x9b,
    0x47, 0x4a, 0xd8, 0x12, 0x90, 0x7e, 0xe5, 0xb6);

static const ble_uuid128_t CFG_UUID = BLE_UUID128_INIT(
    0x03, 0x00, 0x00, 0x00, 0x00, 0x3f, 0x21, 0x9b,
    0x47, 0x4a, 0xd8, 0x12, 0x90, 0x7e, 0xe5, 0xb6);

typedef struct {
    uint16_t total_len;        /* HEADER_LEN + payload_len */
    uint8_t  data[];           /* preformatted ITS5 frame */
} qframe_t;

static uint8_t        s_addr_type;
static uint16_t       s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t       s_chr_val_handle;
static uint16_t       s_cfg_val_handle;
static volatile bool  s_notify_enabled;
static bool           s_ready;
static QueueHandle_t  s_queue;

/* Coex cycle parameters, runtime-tunable via the config characteristic.
 * Values are milliseconds; volatile because read by coex_window_task and
 * written from the GATT access callback.
 *   0..1: discovery cycle (no client connected)
 *   2..3: connected cycle  (client present)
 */
/* connSniff (index 2) MUST stay below the BLE central's supervision timeout.
 * During a connected sniff window the BLE radio is fully dark; if that lasts
 * longer than the supervision timeout (~700 ms on iOS/macOS — the firmware's
 * requested 5 s update is NOT honored by the host) the link drops right after
 * connect. A connSniff sweep on an ESP32-C5 showed: stable up to 680 ms, hard
 * cliff at 700 ms. 500 ms is chosen for a ~28 % safety margin (for weaker
 * field RF / other centrals) while still sniffing ~56 % of the time.
 * Discovery (index 0..1) tuned for fast (re)connect: a connect-time sweep found
 * discSniff=1000/discBle=1000 → ~1.4 s connect (vs ~12 s at 10000/2000); discBle
 * below ~500 ms hurts (advert windows get missed). The iOS app exposes 3 presets
 * — Stabil(connSniff 300) / Ausgewogen(500) / Mehr Daten(600) — and this
 * compiled default equals the Ausgewogen preset. */
static volatile uint16_t s_cycle_ms[4] = { 1000, 1000, 500, 400 };
#define CYC_DISC_SNIFF  s_cycle_ms[0]
#define CYC_DISC_BLE    s_cycle_ms[1]
#define CYC_CONN_SNIFF  s_cycle_ms[2]
#define CYC_CONN_BLE    s_cycle_ms[3]
#define CFG_NS          "btstream"
#define CFG_KEY         "cycle"

static void cfg_load(void);
static void cfg_save(void);

static int gap_event_cb(struct ble_gap_event *ev, void *arg);

/* Inject ASCII diagnostics into the USB stream. The frame reader (resync on
 * the "ITS5" magic) drops every byte that is not part of a real frame, so we
 * can piggy-back \r\n-terminated lines without disturbing the parser. */
static void dbg_printf(const char *fmt, ...)
{
    char line[160];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof(line) - 2, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof(line) - 2) n = sizeof(line) - 2;
    line[n++] = '\r';
    line[n++] = '\n';
    usb_serial_jtag_write_bytes((const uint8_t *)line, n, pdMS_TO_TICKS(20));
}

static int chr_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                         struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        return BLE_ATT_ERR_READ_NOT_PERMITTED;
    }
    return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
}

static int cfg_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                         struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t buf[8];
        for (int i = 0; i < 4; i++) {
            uint16_t v = s_cycle_ms[i];
            buf[i*2]     = (uint8_t)(v        & 0xff);
            buf[i*2 + 1] = (uint8_t)((v >> 8) & 0xff);
        }
        int rc = os_mbuf_append(ctxt->om, buf, sizeof(buf));
        return (rc == 0) ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint16_t total = OS_MBUF_PKTLEN(ctxt->om);
        if (total != 8) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        uint8_t buf[8];
        if (ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof(buf), NULL) != 0) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        for (int i = 0; i < 4; i++) {
            uint16_t v = (uint16_t)buf[i*2] | ((uint16_t)buf[i*2 + 1] << 8);
            /* Sanity: clamp to 100..60000 ms; rejecting silently is more
             * forgiving than returning an error and locking the client out. */
            if (v < 100)    v = 100;
            if (v > 60000)  v = 60000;
            s_cycle_ms[i] = v;
        }
        cfg_save();
        dbg_printf("[bt] cfg write: %u/%u %u/%u",
                   CYC_DISC_SNIFF, CYC_DISC_BLE,
                   CYC_CONN_SNIFF, CYC_CONN_BLE);
        return 0;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static const struct ble_gatt_chr_def chr_defs[] = {
    {
        .uuid       = &CHR_UUID.u,
        .access_cb  = chr_access_cb,
        .flags      = BLE_GATT_CHR_F_NOTIFY,
        .val_handle = &s_chr_val_handle,
    },
    {
        .uuid       = &CFG_UUID.u,
        .access_cb  = cfg_access_cb,
        .flags      = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
        .val_handle = &s_cfg_val_handle,
    },
    { 0 },
};

static void cfg_load(void)
{
    nvs_handle_t h;
    if (nvs_open(CFG_NS, NVS_READONLY, &h) != ESP_OK) return;
    size_t sz = sizeof(s_cycle_ms);
    uint16_t tmp[4];
    if (nvs_get_blob(h, CFG_KEY, tmp, &sz) == ESP_OK && sz == sizeof(tmp)) {
        for (int i = 0; i < 4; i++) {
            uint16_t v = tmp[i];
            if (v >= 100 && v <= 60000) s_cycle_ms[i] = v;
        }
    }
    nvs_close(h);
}

static void cfg_save(void)
{
    nvs_handle_t h;
    if (nvs_open(CFG_NS, NVS_READWRITE, &h) != ESP_OK) return;
    uint16_t tmp[4];
    for (int i = 0; i < 4; i++) tmp[i] = s_cycle_ms[i];
    nvs_set_blob(h, CFG_KEY, tmp, sizeof(tmp));
    nvs_commit(h);
    nvs_close(h);
}

static const struct ble_gatt_svc_def svc_defs[] = {
    {
        .type            = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid            = &SVC_UUID.u,
        .characteristics = chr_defs,
    },
    { 0 },
};

static void start_advertising(void)
{
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)DEVICE_NAME;
    fields.name_len = strlen(DEVICE_NAME);
    fields.name_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_set_fields rc=%d", rc);
        dbg_printf("[bt] adv_set_fields rc=%d", rc);
        return;
    }

    /* Put the 128-bit service UUID in the scan response so the adv packet
     * stays under the 31-byte legacy limit. */
    struct ble_hs_adv_fields scan_rsp = {0};
    scan_rsp.uuids128 = (ble_uuid128_t *)&SVC_UUID;
    scan_rsp.num_uuids128 = 1;
    scan_rsp.uuids128_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&scan_rsp);
    if (rc != 0) {
        ESP_LOGW(TAG, "adv_rsp_set_fields rc=%d", rc);
    }

    struct ble_gap_adv_params p = {0};
    p.conn_mode = BLE_GAP_CONN_MODE_UND;
    p.disc_mode = BLE_GAP_DISC_MODE_GEN;
    /* 30 ms / 50 ms in 0.625 ms units — fast enough that Samsung-S6 BLE
     * scan windows reliably catch us. Defaults are slower. */
    p.itvl_min = 0x30;
    p.itvl_max = 0x50;
    rc = ble_gap_adv_start(s_addr_type, NULL, BLE_HS_FOREVER, &p, gap_event_cb, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_start rc=%d", rc);
        dbg_printf("[bt] adv_start rc=%d", rc);
    } else {
        ESP_LOGI(TAG, "advertising as %s", DEVICE_NAME);
        dbg_printf("[bt] advertising as %s", DEVICE_NAME);
    }
}

static int gap_event_cb(struct ble_gap_event *ev, void *arg)
{
    (void)arg;
    switch (ev->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (ev->connect.status == 0) {
            s_conn_handle = ev->connect.conn_handle;
            s_notify_enabled = false;
            led_status_ble_connected(true);
            ESP_LOGI(TAG, "connect: handle=%d", s_conn_handle);
            /* Stretch the supervision timeout so the coex sniff windows (where
             * the BLE link is starved) don't tear the link down.
             *
             * iOS caps a peripheral's requested supervision timeout at 6 s
             * (Apple Bluetooth Accessory Design Guidelines: 2 s..6 s). Requesting
             * more (we used to ask for 30 s) makes iOS REJECT the whole update
             * and fall back to its ~0.7 s default, so every connected sniff
             * window (CYC_CONN_SNIFF, e.g. 800 ms) trips connectionTimeout and
             * the iPhone reconnect-loops. 5 s is within Apple's range and still
             * comfortably spans the sniff window. Keep CYC_CONN_SNIFF < ~5 s. */
            struct ble_gap_upd_params up = {
                .itvl_min            = 0x18,   /* 30 ms */
                .itvl_max            = 0x28,   /* 50 ms */
                .latency             = 0,
                .supervision_timeout = 500,    /* 5.0 s (units of 10 ms) */
                .min_ce_len          = 0,
                .max_ce_len          = 0,
            };
            ble_gap_update_params(s_conn_handle, &up);
        } else {
            ESP_LOGW(TAG, "connect failed status=%d", ev->connect.status);
            start_advertising();
        }
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnect: reason=0x%x", ev->disconnect.reason);
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        s_notify_enabled = false;
        led_status_ble_connected(false);
        start_advertising();
        break;
    case BLE_GAP_EVENT_SUBSCRIBE:
        if (ev->subscribe.attr_handle == s_chr_val_handle) {
            s_notify_enabled = ev->subscribe.cur_notify;
            ESP_LOGI(TAG, "notify subscription=%d", s_notify_enabled);
        }
        break;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "mtu: handle=%d value=%d",
                 ev->mtu.conn_handle, ev->mtu.value);
        break;
    default:
        break;
    }
    return 0;
}

static void on_sync(void)
{
    dbg_printf("[bt] on_sync");

    /* Override the controller-supplied address with the factory BT MAC so the
     * advertiser comes up as a Public address — random-static is silently
     * dropped by some older Android stacks (Galaxy S6 etc.). */
    uint8_t mac_be[6] = {0};
    if (esp_read_mac(mac_be, ESP_MAC_BT) == ESP_OK) {
        uint8_t mac_le[6] = {
            mac_be[5], mac_be[4], mac_be[3],
            mac_be[2], mac_be[1], mac_be[0],
        };
        int prc = ble_hs_id_set_pub(mac_le);
        dbg_printf("[bt] forced public mac %02X:%02X:%02X:%02X:%02X:%02X rc=%d",
                   mac_be[0], mac_be[1], mac_be[2], mac_be[3], mac_be[4], mac_be[5], prc);
    } else {
        dbg_printf("[bt] esp_read_mac BT failed");
    }
    s_addr_type = BLE_OWN_ADDR_PUBLIC;

    uint8_t addr[6] = {0};
    ble_hs_id_copy_addr(s_addr_type, addr, NULL);
    ESP_LOGI(TAG, "BLE addr=%02X:%02X:%02X:%02X:%02X:%02X",
             addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
    dbg_printf("[bt] addr=%02X:%02X:%02X:%02X:%02X:%02X",
               addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
    start_advertising();
}

static void on_reset(int reason)
{
    ESP_LOGW(TAG, "stack reset: %d", reason);
}

static void host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

/* Duty-cycle the Wi-Fi promiscuous mode while no BLE client is connected, so
 * passive BLE scanners (Galaxy S6, generic USB BT dongle) actually see the
 * adv between sniffer windows. The C5 has a single RF transceiver shared
 * between 5 GHz Wi-Fi and 2.4 GHz BLE — without a pause, promisc keeps the
 * radio glued to 5 GHz RX and BLE TX never hits air. iPhone catches the rare
 * legacy adv only because its scan duty cycle is exceptionally aggressive.
 *
 * Once connected, BLE has scheduled connection events that the SW coex layer
 * services correctly, so we leave promisc on continuously.
 */
/* The single ESP32-C5 RF transceiver can't service Wi-Fi promisc and BLE adv
 * simultaneously: while the sniffer is active (with phy_11p_set + 5.9 GHz
 * channel locked), BLE TX is starved and no scanner (Galaxy S6, USB BT
 * dongle) can find the device. iPhones are the rare exception because their
 * scan duty cycle is aggressive enough to catch the few packets that slip
 * through.
 *
 * Resolution: time-share the radio. Sniff for SNIFFER_ON_MS, then pause the
 * sniffer (releases promisc + 802.11p PHY + esp_wifi_stop) for BLE_ON_MS so
 * scanners see the adv and a connected client gets its notification queue
 * drained. While paused, captured frames sit in the BLE queue until the
 * window opens; while sniffing, the BLE link is starved (clients usually
 * survive the supervision-timeout window with a fresh adv arriving in time).
 */
/* Sleep in short chunks so the loop notices a BLE link state change within
 * CHUNK_MS instead of holding the radio for the full sniff window. Returns
 * early when the connection state flips relative to `was_connected`.
 *
 * Why this matters: in wardriving mode (sniff 8000 ms / BLE 400 ms), a
 * disconnect mid-sniff would otherwise leave the phone scanning blind for
 * up to 8 s before the next BLE window opens. Likewise, a fresh connect
 * mid-sniff would starve the link past the host supervision timeout (~5 s
 * on Android default) and trigger an immediate reconnect-loop. */
#define COEX_CHUNK_MS 200
static void chunked_delay(uint32_t total_ms, bool was_connected)
{
    uint32_t left = total_ms;
    while (left > 0) {
        uint32_t step = (left > COEX_CHUNK_MS) ? COEX_CHUNK_MS : left;
        vTaskDelay(pdMS_TO_TICKS(step));
        left -= step;
        bool now = (s_conn_handle != BLE_HS_CONN_HANDLE_NONE);
        if (now != was_connected) return;
    }
}

static void coex_window_task(void *arg)
{
    (void)arg;
    /* Boot-time: give BLE the radio first so a user has a chance to
     * connect before the sniffer takes over. */
    sniffer_pause();
    chunked_delay(CYC_DISC_BLE, false);
    int n = 0;
    for (;;) {
        bool connected = (s_conn_handle != BLE_HS_CONN_HANDLE_NONE);
        /* Freshly connected but the client hasn't subscribed to notifications
         * yet: keep the radio on BLE (skip sniffing) so the link can settle —
         * in particular so the 5 s supervision-timeout parameter update can
         * complete — before we start starving the radio. Otherwise a large
         * CYC_CONN_SNIFF window trips the initial (short ~1.5 s) supervision
         * timeout and the connection drops right after connect, before the 5 s
         * value ever takes effect. Once the client subscribes, the normal
         * connected duty cycle takes over. */
        if (connected && !s_notify_enabled) {
            chunked_delay(CYC_CONN_BLE, connected);
            continue;
        }
        uint16_t sniff_ms = connected ? CYC_CONN_SNIFF : CYC_DISC_SNIFF;
        uint16_t ble_ms   = connected ? CYC_CONN_BLE   : CYC_DISC_BLE;
        sniffer_resume();
        chunked_delay(sniff_ms, connected);
        sniffer_pause();
        if ((n++ & 0xF) == 0) {
            dbg_printf("[bt] cycle %s: sniff=%u ble=%u",
                       connected ? "CONN" : "DISC", sniff_ms, ble_ms);
        }
        chunked_delay(ble_ms, connected);
    }
}

static void writer_task(void *arg)
{
    (void)arg;
    qframe_t *qf = NULL;
    for (;;) {
        if (xQueueReceive(s_queue, &qf, portMAX_DELAY) != pdTRUE) continue;

        if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || !s_notify_enabled) {
            free(qf);
            continue;
        }

        uint16_t mtu = ble_att_mtu(s_conn_handle);
        uint16_t chunk = (mtu > 3) ? (uint16_t)(mtu - 3) : 20;
        uint16_t sent = 0;
        while (sent < qf->total_len) {
            uint16_t n = qf->total_len - sent;
            if (n > chunk) n = chunk;
            struct os_mbuf *om = ble_hs_mbuf_from_flat(qf->data + sent, n);
            if (om == NULL) {
                ESP_LOGW(TAG, "mbuf alloc failed, dropping frame");
                break;
            }
            int rc = ble_gatts_notify_custom(s_conn_handle, s_chr_val_handle, om);
            if (rc != 0) {
                ESP_LOGW(TAG, "notify rc=%d, dropping", rc);
                /* om is consumed by NimBLE on success; on failure we leak —
                 * but there is no public API to free it explicitly here. */
                break;
            }
            led_pulse_ble_tx();
            sent += n;
        }
        free(qf);
    }
}

void bt_stream_init(void)
{
    if (s_ready) return;

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        err = nvs_flash_init();
    }
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "nvs_flash_init: %s", esp_err_to_name(err));
    }

    cfg_load();
    dbg_printf("[bt] cfg load: %u/%u %u/%u",
               CYC_DISC_SNIFF, CYC_DISC_BLE, CYC_CONN_SNIFF, CYC_CONN_BLE);

    /* Wi-Fi promiscuous mode otherwise hogs the RF; with BALANCE the BLE adv
     * is so rare that BLE 4.x scanners (Galaxy S6, generic USB BT dongle)
     * miss every window. iPhone catches it because its scan duty cycle is
     * far higher than what Android exposes. PREFER_BT lets BLE pre-empt
     * Wi-Fi long enough to emit a complete adv packet. */
    esp_err_t cerr = esp_coex_preference_set(ESP_COEX_PREFER_BT);
    dbg_printf("[bt] coex_preference_set(PREFER_BT) rc=%d", cerr);

    dbg_printf("[bt] init: nimble_port_init…");
    err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init: %s", esp_err_to_name(err));
        dbg_printf("[bt] nimble_port_init FAIL: %s", esp_err_to_name(err));
        return;
    }
    dbg_printf("[bt] nimble_port_init ok");

    ble_hs_cfg.sync_cb  = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    int rc = ble_svc_gap_device_name_set(DEVICE_NAME);
    if (rc != 0) ESP_LOGW(TAG, "name_set rc=%d", rc);

    ble_svc_gap_init();
    ble_svc_gatt_init();

    rc = ble_gatts_count_cfg(svc_defs);
    if (rc != 0) {
        ESP_LOGE(TAG, "count_cfg rc=%d", rc);
        return;
    }
    rc = ble_gatts_add_svcs(svc_defs);
    if (rc != 0) {
        ESP_LOGE(TAG, "add_svcs rc=%d", rc);
        return;
    }

    s_queue = xQueueCreate(FRAME_QUEUE_DEPTH, sizeof(qframe_t *));
    if (s_queue == NULL) {
        ESP_LOGE(TAG, "queue create failed");
        return;
    }
    BaseType_t xrc = xTaskCreate(writer_task, "bt_writer", 4096, NULL, 5, NULL);
    if (xrc != pdPASS) {
        ESP_LOGE(TAG, "writer task create failed");
        return;
    }
    xTaskCreate(coex_window_task, "bt_coex", 3072, NULL, 4, NULL);

    nimble_port_freertos_init(host_task);
    s_ready = true;
    ESP_LOGI(TAG, "initialized");
    dbg_printf("[bt] initialized, awaiting on_sync");
}

void bt_stream_pause(void)
{
    if (!s_ready) return;
    ble_gap_adv_stop();
    ESP_LOGI(TAG, "BLE advertising stopped for WiFi scan");
}

void bt_stream_resume(void)
{
    if (!s_ready) return;
    start_advertising();
    ESP_LOGI(TAG, "BLE advertising restarted");
}

void bt_stream_publish_packet(const uint8_t *payload, uint16_t len, uint32_t sec, uint32_t usec)
{
    if (!s_ready) return;
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || !s_notify_enabled) return;
    if (len > MAX_PAYLOAD) return;

    size_t total = HEADER_LEN + len;
    qframe_t *qf = (qframe_t *)malloc(sizeof(qframe_t) + total);
    if (qf == NULL) return;
    qf->total_len = (uint16_t)total;

    uint8_t *p = qf->data;
    p[0]  = 'I';  p[1]  = 'T';  p[2]  = 'S';  p[3]  = '5';
    p[4]  = (uint8_t)(sec        & 0xff);
    p[5]  = (uint8_t)((sec >>  8) & 0xff);
    p[6]  = (uint8_t)((sec >> 16) & 0xff);
    p[7]  = (uint8_t)((sec >> 24) & 0xff);
    p[8]  = (uint8_t)(usec        & 0xff);
    p[9]  = (uint8_t)((usec >>  8) & 0xff);
    p[10] = (uint8_t)((usec >> 16) & 0xff);
    p[11] = (uint8_t)((usec >> 24) & 0xff);
    p[12] = (uint8_t)(len        & 0xff);
    p[13] = (uint8_t)((len >>  8) & 0xff);
    if (len > 0) memcpy(p + HEADER_LEN, payload, len);

    if (xQueueSend(s_queue, &qf, 0) != pdTRUE) {
        free(qf);  /* queue full — drop newest, keep stream live */
    }
}
