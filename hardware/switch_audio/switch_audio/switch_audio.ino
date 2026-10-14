// GivenVoice speaker box, step 1: the ESP32 plays the stored phrases through a Bluetooth speaker by itself.
//
// The 47 phrases the app caches (ElevenLabs MP3s) are built into the firmware (audio_data.h, made by
// make_audio_data.py). The ESP32 connects to the speaker as a Bluetooth audio source, decodes the MP3,
// and streams it. The glasses' own Bluetooth cannot send audio to a speaker, so this box does it.
//
// Libraries: ESP32-A2DP, libhelix (arduino-libhelix), both by pschatzmann.
// Build with the "Huge APP" partition scheme (the audio makes the firmware ~2.5 MB):
//   arduino-cli compile --fqbn esp32:esp32:esp32:PartitionScheme=huge_app .
//
// Test controls (this step): red / green / yellow switch -> phrase 1 / 2 / 3.
//   Or type a phrase number (1-47) into the serial monitor at 115200 baud.
//
// Put the speaker in pairing mode the first time; after that it reconnects by itself when both are on.

#include "BluetoothA2DPSource.h"
#include "MP3DecoderHelix.h"
#include "audio_data.h"
#include "esp_gap_ble_api.h"

const char* SPEAKER_NAME = "JBL Charge 4";
const uint8_t PIN_RED = 27, PIN_GREEN = 26, PIN_YELLOW = 25;
const uint8_t TEST_PHRASE[3] = { 1, 2, 3 };
const int VOLUME = 90;  // 0-127, the speaker's own volume is separate

BluetoothA2DPSource a2dp;
libhelix::MP3DecoderHelix mp3;

// --- PCM ring buffer: the decoder task writes stereo frames, the Bluetooth callback reads them ---
const uint32_t RING_FRAMES = 8192;  // about 0.19 s at 44.1 kHz (smaller to leave heap for BLE)
static Frame ring[RING_FRAMES];
static volatile uint32_t ringRead = 0, ringWrite = 0;  // only ever increase; index = value % RING_FRAMES

static inline uint32_t ringUsed() { return ringWrite - ringRead; }

static void ringPush(int16_t left, int16_t right) {
  while (RING_FRAMES - ringUsed() == 0) vTaskDelay(1);  // wait for the speaker to catch up
  ring[ringWrite % RING_FRAMES] = Frame(left, right);
  ringWrite = ringWrite + 1;
}

static volatile int requestedPhrase = 0;  // set by the buttons, taken by the decoder task

// Called by the Bluetooth stack when it needs audio. Silence keeps the link steady between phrases.
int32_t getDataFrames(Frame* frames, int32_t count) {
  for (int32_t i = 0; i < count; i++) {
    if (ringUsed() > 0) {
      frames[i] = ring[ringRead % RING_FRAMES];
      ringRead = ringRead + 1;
    } else {
      frames[i] = Frame(0, 0);
    }
  }
  return count;
}

// Called by the MP3 decoder with each decoded block. The phrases are mono, so each sample goes to both channels.
void onPcm(MP3FrameInfo& info, short* pcm, size_t len, void*) {
  if (info.nChans == 1) {
    for (size_t i = 0; i < len; i++) ringPush(pcm[i], pcm[i]);
  } else {
    for (size_t i = 0; i + 1 < len; i += 2) ringPush(pcm[i], pcm[i + 1]);
  }
}

static void pushSilence(uint32_t frames) {
  for (uint32_t i = 0; i < frames; i++) ringPush(0, 0);
}

// Decodes whichever phrase was requested. A newer request interrupts the current one.
void playerTask(void*) {
  for (;;) {
    int id = requestedPhrase;
    if (id == 0) {
      vTaskDelay(5);
      continue;
    }
    requestedPhrase = 0;
    if (id < 1 || id > PHRASE_MAX_ID || PHRASE_DATA[id] == nullptr) {
      Serial.printf("No audio for phrase %d\n", id);
      continue;
    }
    Serial.printf("Playing phrase %d (%u bytes)\n", id, (unsigned)PHRASE_LEN[id]);
    ringRead = ringWrite;   // drop anything still queued
    pushSilence(4410);      // 0.1 s lead-in so the speaker's amp is awake before the first word
    mp3.begin();
    const uint8_t* data = PHRASE_DATA[id];
    size_t len = PHRASE_LEN[id], pos = 0;
    while (pos < len && requestedPhrase == 0) {
      size_t chunk = min((size_t)512, len - pos);
      mp3.write(data + pos, chunk);
      pos += chunk;
    }
    mp3.end();
    if (requestedPhrase == 0) pushSilence(4410);
  }
}

// --- Switches (same debounce and lockout as the other sketches) ---
const unsigned long ACCEPT_MS = 40, RELEASE_MS = 40, LOCKOUT_MS = 300;
struct Switch {
  uint8_t pin;
  uint8_t phrase;
  bool raw;
  bool pressed;
  unsigned long changedAt;
};
Switch switches[] = {
  { PIN_RED, TEST_PHRASE[0] },
  { PIN_GREEN, TEST_PHRASE[1] },
  { PIN_YELLOW, TEST_PHRASE[2] },
};
unsigned long lastSentAt = 0;
bool sentOnce = false;

// --- BLE broadcast of the button presses to the glasses (same format as switch_ble.ino) ---
// The classic-Bluetooth speaker link and BLE advertising share the radio (BTDM mode).
const unsigned long HOLD_MS = 1200;
const uint8_t PIN_LED = 2;
uint16_t bleSeq = 0;
uint8_t bleButton = 0;
unsigned long bleSentAt = 0;
esp_ble_adv_params_t advParams = {};

// The glasses ask for a phrase by advertising manufacturer data FF FF 'G' 'S' phraseId seq_hi seq_lo.
// This box scans for it and plays that phrase on the speaker (act once per new seq).
static esp_ble_scan_params_t scanParams = {
  BLE_SCAN_TYPE_PASSIVE, BLE_ADDR_TYPE_PUBLIC, BLE_SCAN_FILTER_ALLOW_ALL, 0x50, 0x30, BLE_SCAN_DUPLICATE_DISABLE
};
static volatile int32_t lastPhraseSeq = -1;

void onGap(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t* p) {
  if (event == ESP_GAP_BLE_ADV_DATA_RAW_SET_COMPLETE_EVT) {
    esp_ble_gap_start_advertising(&advParams);
  } else if (event == ESP_GAP_BLE_SCAN_PARAM_SET_COMPLETE_EVT) {
    esp_ble_gap_start_scanning(0);  // 0 = scan forever
  } else if (event == ESP_GAP_BLE_SCAN_RESULT_EVT && p->scan_rst.search_evt == ESP_GAP_SEARCH_INQ_RES_EVT) {
    uint8_t len = 0;
    uint8_t* d = esp_ble_resolve_adv_data_by_type(p->scan_rst.ble_adv, p->scan_rst.adv_data_len, ESP_BLE_AD_MANUFACTURER_SPECIFIC_TYPE, &len);
    if (d && len >= 7 && d[0] == 0xFF && d[1] == 0xFF && d[2] == 'G' && d[3] == 'S') {
      int32_t seq = (d[5] << 8) | d[6];
      if (seq != lastPhraseSeq) {
        lastPhraseSeq = seq;
        requestedPhrase = d[4];
        Serial.printf("PHRASE:%u (from glasses)\n", (unsigned)d[4]);
      }
    }
  }
}

void bleAdvertise(uint8_t button) {
  bleButton = button;
  uint8_t raw[11] = { 0x02, 0x01, 0x06, 0x08, 0xFF, 0xFF, 0xFF, 'G', 'V', button, 0 };
  // manufacturer data: company 0xFFFF, 'G' 'V' button, seq (2 bytes) -> needs 12 bytes
  uint8_t pkt[12] = { 0x02, 0x01, 0x06, 0x08, 0xFF, 0xFF, 0xFF, 'G', 'V', button, (uint8_t)(bleSeq >> 8), (uint8_t)(bleSeq & 0xFF) };
  (void)raw;
  esp_ble_gap_config_adv_data_raw(pkt, sizeof(pkt));
}

void setup() {
  Serial.begin(115200);
  for (Switch& s : switches) pinMode(s.pin, INPUT_PULLUP);
  mp3.setDataCallback(onPcm);
  xTaskCreatePinnedToCore(playerTask, "player", 12288, nullptr, 2, nullptr, 1);
  pinMode(PIN_LED, OUTPUT);
  a2dp.set_default_bt_mode(ESP_BT_MODE_BTDM);  // classic audio + BLE together
  a2dp.set_auto_reconnect(true);
  a2dp.set_volume(VOLUME);
  a2dp.start(SPEAKER_NAME, getDataFrames);
  advParams.adv_int_min = 0x20;
  advParams.adv_int_max = 0x30;
  advParams.adv_type = ADV_TYPE_NONCONN_IND;
  advParams.own_addr_type = BLE_ADDR_TYPE_PUBLIC;
  advParams.channel_map = ADV_CHNL_ALL;
  advParams.adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY;
  esp_ble_gap_register_callback(onGap);
  esp_ble_gap_set_scan_params(&scanParams);
  bleAdvertise(0);
  Serial.printf("Looking for speaker \"%s\" ...\n", SPEAKER_NAME);
}

void loop() {
  unsigned long now = millis();
  for (Switch& s : switches) {
    bool raw = digitalRead(s.pin) == LOW;
    if (raw != s.raw) {
      s.raw = raw;
      s.changedAt = now;
    }
    unsigned long stableFor = now - s.changedAt;
    if (s.raw && !s.pressed && stableFor >= ACCEPT_MS) {
      s.pressed = true;
      if (!sentOnce || now - lastSentAt >= LOCKOUT_MS) {
        // (buttons no longer play audio; the phrase to speak will come from the app)
        bleSeq++;
        bleAdvertise(s.phrase);
        bleSentAt = now;
        Serial.printf("BTN:%u\n", (unsigned)s.phrase);
        lastSentAt = now;
        sentOnce = true;
      }
    } else if (!s.raw && s.pressed && stableFor >= RELEASE_MS) {
      s.pressed = false;
    }
  }

  if (bleButton != 0 && now - bleSentAt >= HOLD_MS) bleAdvertise(0);
  digitalWrite(PIN_LED, (a2dp.is_connected() ? HIGH : ((now / 300) % 2)) || (bleButton != 0));

  // Serial: a phrase number plays that phrase.
  if (Serial.available()) {
    int n = Serial.parseInt();
    if (n > 0) requestedPhrase = n;
  }

  static unsigned long lastReport = 0;
  static bool wasConnected = false;
  bool connected = a2dp.is_connected();
  if (connected != wasConnected || now - lastReport >= 5000) {
    lastReport = now;
    wasConnected = connected;
    Serial.printf("speaker: %s, free heap %u\n", connected ? "CONNECTED" : "not connected", (unsigned)ESP.getFreeHeap());
  }
}
