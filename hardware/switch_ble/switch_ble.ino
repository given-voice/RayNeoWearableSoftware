// Three-switch box for the GivenVoice app on the RayNeo glasses, over Bluetooth LE advertising.
//
// No pairing and no connection: the ESP32 puts the last button press into its BLE advertisement
// and the app (BleSwitchSource.kt) scans for it. (RayNeo's system UI refuses to pair new devices,
// so a normal Bluetooth keyboard cannot be paired - this route never asks to.)
//
// Wiring: each switch goes between its pin and GND. Internal pull-ups are used, so no resistors are needed.
//
//   Switch        Pin      Button   App action
//   Red     TAB   GPIO27   1        next option
//   Green   ENTER GPIO26   2        select
//   Yellow  BACK  GPIO25   3        back
//
// Advertisement: manufacturer data, company id 0xFFFF, payload  'G' 'V' button seq_hi seq_lo
//   button = 0 when idle, 1..3 for about HOLD_MS after a press. seq changes on every press, so
//   the app acts once per seq even though it hears the same advert many times.

#include <BLEDevice.h>
#include <BLEAdvertising.h>

const uint8_t PIN_TAB = 27, PIN_ENTER = 26, PIN_BACK = 25;
#ifndef LED_BUILTIN
#define LED_BUILTIN 2  // the blue LED on ESP32 DevKit boards
#endif

// --- Tuning for the user's motor control -----------------------------------
// A switch must stay closed this long before it counts. Filters contact bounce; raise it to ignore brief bumps.
const unsigned long ACCEPT_MS = 40;
// A switch must stay open this long before it can fire again.
const unsigned long RELEASE_MS = 40;
// After any accepted press, ignore every switch for this long (tremor / spasm double-hits).
const unsigned long LOCKOUT_MS = 300;
// How long a press keeps being advertised, so a scanner that misses a few packets still hears it.
const unsigned long HOLD_MS = 1200;
// ---------------------------------------------------------------------------

struct Switch {
  uint8_t pin;
  uint8_t value;            // button number sent to the app
  bool raw;                 // last reading, may be bouncing
  bool pressed;             // debounced state
  unsigned long changedAt;  // when `raw` last changed
};

Switch switches[] = {
  { PIN_TAB, 1 },
  { PIN_ENTER, 2 },
  { PIN_BACK, 3 },
};

BLEAdvertising* advertising;
uint16_t seq;
uint8_t currentButton = 0;
unsigned long lastSentAt = 0;
bool sentOnce = false;

// Replace the advertised payload.
void advertise(uint8_t button) {
  currentButton = button;
  const char payload[7] = {
    (char)0xFF, (char)0xFF,  // company id 0xFFFF (reserved for testing)
    'G', 'V', (char)button, (char)(seq >> 8), (char)(seq & 0xFF),
  };
  BLEAdvertisementData data;
  data.setFlags(0x06);  // general discoverable, no classic BT
  data.setManufacturerData(String(payload, sizeof(payload)));
  advertising->stop();
  advertising->setAdvertisementData(data);
  advertising->start();
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_BUILTIN, OUTPUT);
  for (Switch& s : switches) {
    pinMode(s.pin, INPUT_PULLUP);
  }
  BLEDevice::init("GivenVoice Box");
  advertising = BLEDevice::getAdvertising();
  advertising->setAdvertisementType(ADV_TYPE_NONCONN_IND);  // broadcast only, no connections
  advertising->setMinInterval(32);  // 20 ms (units of 0.625 ms) - fast, so presses arrive quickly
  advertising->setMaxInterval(48);  // 30 ms
  seq = (uint16_t)esp_random();     // random start so a reboot never repeats an old seq
  advertise(0);
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

    // If the switch is pressed and has been stable for long enough, advertise the press.
    if (s.raw && !s.pressed && stableFor >= ACCEPT_MS) {
      // One press per click: holding the switch never repeats.
      s.pressed = true;
      if (!sentOnce || now - lastSentAt >= LOCKOUT_MS) {
        seq++;
        advertise(s.value);
        Serial.print("BTN:");
        Serial.println(s.value);
        lastSentAt = now;
        sentOnce = true;
      }

    // If the switch is released and has been stable for long enough, reset the pressed state.
    } else if (!s.raw && s.pressed && stableFor >= RELEASE_MS) {
      s.pressed = false;
    }
  }

  // Go back to the idle payload (same seq) once the press has been advertised long enough.
  if (currentButton != 0 && now - lastSentAt >= HOLD_MS) {
    advertise(0);
  }
  digitalWrite(LED_BUILTIN, sentOnce && now - lastSentAt < 100 ? HIGH : LOW);

  // Once a second, print what each pin reads (1 = open, 0 = pressed) to check the wiring.
  static unsigned long lastReport = 0;
  if (now - lastReport >= 1000) {
    lastReport = now;
    Serial.printf("pins 27(red)=%d 26(green)=%d 25(yellow)=%d\n",
                  digitalRead(PIN_TAB), digitalRead(PIN_ENTER), digitalRead(PIN_BACK));
  }
}
