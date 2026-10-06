// Three-switch keyboard for the RayNeo Switch Menu app (SwitchMenuActivity) over Bluetooth LE.
//
// ESP32: Bluetooth LE keyboard (install the "ESP32 BLE Keyboard" library by T-vK), pairs straight to the glasses, no cable required.
//
// Wiring: each switch goes between its pin and GND. Internal pull-ups are used, so no resistors are needed.
//
//   Switch        Key sent   ESP32 pin
//   Yellow  TAB   TAB        GPIO25
//   Green   ENTER ENTER      GPIO26
//   Red     BACK  ESC        GPIO27
//
// NOTE: ESP32 uses different pins because GPIO2/3/4 are strapping / serial pins there.

#if defined(ARDUINO_ARCH_ESP32)
#include <BleKeyboard.h>
BleKeyboard Keyboard("Switch Menu Buttons", "jenn-voice", 100);
const uint8_t PIN_TAB = 25, PIN_ENTER = 26, PIN_BACK = 27;
#else
#include <Keyboard.h>
const uint8_t PIN_TAB = 2, PIN_ENTER = 3, PIN_BACK = 4;
#endif

// --- Tuning for the user's motor control -----------------------------------
// A switch must stay closed this long before it counts. Filters contact bounce;
const unsigned long ACCEPT_MS = 40;
// A switch must stay open this long before it can fire again.
const unsigned long RELEASE_MS = 40;
// After any accepted press, ignore every switch for this long (tremor / spasm double-hits).
const unsigned long LOCKOUT_MS = 300;
// ---------------------------------------------------------------------------

struct Switch {
  uint8_t pin;
  uint8_t key;
  const char* name;
  bool raw;                 // last reading, may be bouncing
  bool pressed;             // debounced state
  unsigned long changedAt;  // when 'raw' last changed
};

Switch switches[] = {
  { PIN_TAB, KEY_TAB, "TAB" },
  { PIN_ENTER, KEY_RETURN, "ENTER" },
  { PIN_BACK, KEY_ESC, "BACK" },
};

unsigned long lastSentAt = 0;
bool sentOnce = false;

void setup() {
  Serial.begin(115200);
  pinMode(LED_BUILTIN, OUTPUT);
  for (Switch& s : switches) {
    pinMode(s.pin, INPUT_PULLUP);
  }
  Keyboard.begin();
}

// Check for switch changes and send values over Bluetooth LE.
void loop() {
  unsigned long now = millis();
  for (Switch& s : switches) {
    bool raw = digitalRead(s.pin) == LOW;
    if (raw != s.raw) {
      s.raw = raw;
      s.changedAt = now;
    }
    unsigned long stableFor = now - s.changedAt;

    // If the switch is pressed and has been stable for long enough, send the value.
    if (s.raw && !s.pressed && stableFor >= ACCEPT_MS) {
      // One key per press: holding the switch never repeats.
      s.pressed = true;
      if (!sentOnce || now - lastSentAt >= LOCKOUT_MS) {
        send(s);
        lastSentAt = now;
        sentOnce = true;
      }

    // If the switch is released and has been stable for long enough, reset the pressed state.
    } else if (!s.raw && s.pressed && stableFor >= RELEASE_MS) {
      s.pressed = false;
    }
  }
  digitalWrite(LED_BUILTIN, sentOnce && now - lastSentAt < 100 ? HIGH : LOW);
}

// Send a key press and release.
void send(const Switch& s) {
#if defined(ARDUINO_ARCH_ESP32)
  if (!Keyboard.isConnected()) {
    Serial.print(s.name);
    Serial.println(" (not paired, dropped)");
    return;
  }
#endif
  Keyboard.write(s.key);  // press + release
  Serial.println(s.name);
}
