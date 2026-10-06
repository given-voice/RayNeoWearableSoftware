# Simulation (no glasses needed)

Test the app and the three-switch box on this PC. Nothing in this folder ships in the app.

```
Wokwi ESP32 (real firmware) --serial--> bridge.py --TCP:5000--> adb reverse --> app on emulator
 or your real ESP32 (--wokwi COM3)  ---^                         (127.0.0.1:5000 in the emulator)
 or keys 1/2/3 (--keyboard) --------^
```

On the glasses, the app reads the same `BTN:1/2/3` lines from the ESP32 over a USB OTG cable.
In debug builds on the emulator, it reads them from `bridge.py` instead. Both paths go through
`SwitchInput.kt`, so the simulation exercises the same parsing and navigation code.

| Line  | Switch | Does |
|-------|--------|------|
| BTN:1 | Yellow (TAB) | highlight the next box / sentence |
| BTN:2 | Green (ENTER) | select the highlighted one |
| BTN:3 | Red (BACK) | highlight the previous one |

To return to the situation list, highlight **← Back** (the first item on a phrase screen) and press green.

## One-time setup
```
pip install -r simulation/requirements.txt
```

## Run
1. **App on the emulator**:
   `powershell -ExecutionPolicy Bypass -File simulation\run-emulator.ps1`
   This starts the first emulator from `emulator -list-avds` (`Medium_Phone_API_37.0` here), installs the
   debug build, runs `adb reverse tcp:5000 tcp:5000` and opens the app. Use `-Avd <name>` to pick another one.
   If you press Run in Android Studio instead, run `adb reverse tcp:5000 tcp:5000` yourself once after each
   emulator start (the emulator's usual PC address, 10.0.2.2, is blocked for apps on Android 17).
2. **Pick an input source:**
   - **Keyboard (quickest):** `python simulation/bridge.py --keyboard`, then press 1 / 2 / 3.
   - **Real ESP32 on a USB port:** `python simulation/bridge.py --wokwi COM3` (use your COM port; close any
     serial monitor first).
   - **Simulated ESP32:** open `hardware/switch_serial/` in VS Code, press F1 and choose
     *Wokwi: Start Simulator*, then run `python simulation/bridge.py` and click the yellow, green
     and red buttons in the Wokwi window. To rebuild the firmware after editing the sketch:
     `arduino-cli compile --fqbn esp32:esp32:esp32 --output-dir build .` inside that folder.
3. Watch the logs in Android Studio's Logcat (filter `GivenVoice`): every press shows as `Switch box: BTN:n`.

## Troubleshooting
- **"no app connected yet":** the app retries every few seconds. Check that it's open on the emulator and is a
  debug build, and that `adb reverse --list` shows `tcp:5000 tcp:5000`. Logcat (filter `GivenVoice`) shows
  `Simulation bridge not reachable` with the reason while it can't connect.
- **Wokwi never connects:** the simulator must be running, and `wokwi.toml` must keep `rfc2217ServerPort = 4000`.
- **Highlight doesn't move:** check Logcat. If lines arrive but nothing happens, the screen may not have finished drawing yet.

## What this can't test
The emulator has no USB host, so the USB cable path (`UsbSerialSwitchSource.kt`) can only be tested on real glasses.
