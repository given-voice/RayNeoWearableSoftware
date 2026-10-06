// Three-switch box for the RayNeo glasses, ESP32 over a USB cable.
//
// Each button click is converted to a value and sent over USB serial, one line per click:
//
//   Switch        Pin      Value line   Intended meaning
//   Yellow  TAB   GPIO25   BTN:1        next option
//   Green   ENTER GPIO26   BTN:2        select
//   Red     BACK  GPIO27   BTN:3        back
//
// Connect the ESP32's USB port to the glasses' USB-C port with a USB-C OTG adapter/cable;
// NOTE: The glasses power the ESP32 through that cable.

const uint8_t PIN_TAB = 25, PIN_ENTER = 26, PIN_BACK = 27;

// --- Tuning for the user's motor control -----------------------------------
// A switch must stay closed this long before it counts. Filters contact bounce raise it (e.g. 150-300) to ignore brief accidental bumps ("slow keys").
const unsigned long ACCEPT_MS = 40;
// A switch must stay open this long before it can fire again.
const unsigned long RELEASE_MS = 40;
// After any accepted click, ignore every switch for this long (tremor / spasm double-hits).
const unsigned long LOCKOUT_MS = 300;
// ---------------------------------------------------------------------------

struct Switch {
  uint8_t pin;
  uint8_t value;            // sent as BTN:<value>
  bool raw;                 // last reading, may be bouncing
  bool pressed;             // debounced state
  unsigned long changedAt;  // when `raw` last changed
};

Switch switches[] = {
  { PIN_TAB, 1 },
  { PIN_ENTER, 2 },
  { PIN_BACK, 3 },
};

unsigned long lastSentAt = 0;
bool sentOnce = false;

// Initialize the switches and the serial connection.
void setup() {
  Serial.begin(115200);
  for (Switch& s : switches) {
    pinMode(s.pin, INPUT_PULLUP);
  }
}

// Check for switch changes and send values over serial.
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

      // One value per click: holding the switch never repeats.
      s.pressed = true;
      if (!sentOnce || now - lastSentAt >= LOCKOUT_MS) {
        Serial.print("BTN:");
        Serial.println(s.value);  // println sends "\r\n"; the app accepts either ending
        lastSentAt = now;
        sentOnce = true;
      }

    // If the switch is released and has been stable for long enough, reset the pressed state.
    } else if (!s.raw && s.pressed && stableFor >= RELEASE_MS) {
      s.pressed = false;
    }
  }
}
