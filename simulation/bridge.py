"""Feeds switch-box presses to the GivenVoice app running on the Android emulator.

The real ESP32 sends "BTN:1" / "BTN:2" / "BTN:3" lines over USB serial. The emulator
has no USB, so in debug builds the app instead connects to this bridge over TCP
(through "adb reverse tcp:5000 tcp:5000", which run-emulator.ps1 sets up) and receives the exact
same lines.

Two input modes:
  python bridge.py              read the simulated ESP32 in Wokwi (hardware/switch_serial)
  python bridge.py --keyboard   press 1 / 2 / 3 in this terminal instead (q to quit)
"""

import argparse
import socket
import sys
import threading
import time

BUTTONS = {"1": "TAB  (yellow, next)", "2": "ENTER (green, select)", "3": "BACK (red, previous)"}


class AppServer:
    """TCP server the app connects to; every line is sent to all connected apps."""

    def __init__(self, port):
        self.clients = []
        self.lock = threading.Lock()
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # "adb reverse" connects to this PC's loopback, so nothing outside the PC can connect.
        self.server.bind(("127.0.0.1", port))
        self.server.listen()
        threading.Thread(target=self._accept, daemon=True).start()
        print(f"Waiting for the app on port {port} (start it on the emulator in a debug build)")

    def _accept(self):
        while True:
            conn, _ = self.server.accept()
            with self.lock:
                self.clients.append(conn)
            print("App connected")

    def send(self, line):
        data = (line + "\n").encode("ascii")
        with self.lock:
            for conn in list(self.clients):
                try:
                    conn.sendall(data)
                except OSError:
                    self.clients.remove(conn)
                    print("App disconnected")
            connected = len(self.clients)
        if connected == 0:
            print("  (no app connected yet - press not delivered)")


def press(server, value):
    line = f"BTN:{value}"
    print(f"{line}  {BUTTONS[value]}")
    server.send(line)


def run_keyboard(server):
    print("Press 1 = next, 2 = select, 3 = previous, q = quit")
    try:
        import msvcrt  # Windows: read single key presses without Enter

        def read_key():
            return msvcrt.getwch()
    except ImportError:

        def read_key():
            return (sys.stdin.readline().strip() or " ")[0]

    while True:
        key = read_key()
        if key in BUTTONS:
            press(server, key)
        elif key.lower() == "q":
            return


def run_wokwi(server, url):
    try:
        import serial
    except ImportError:
        sys.exit("pyserial is missing: pip install -r simulation/requirements.txt")

    while True:
        try:
            port = serial.serial_for_url(url, baudrate=115200, timeout=1)
        except (serial.SerialException, OSError):
            print(f"Waiting for Wokwi at {url} (VS Code: F1 -> 'Wokwi: Start Simulator')")
            time.sleep(3)
            continue
        print("Connected to the simulated ESP32 - click its buttons in the Wokwi window")
        try:
            while True:
                raw = port.readline().decode("ascii", errors="replace").strip()
                if raw.startswith("BTN:") and raw[4:] in BUTTONS:
                    press(server, raw[4:])
                elif raw:
                    print(f"  esp32: {raw}")  # boot messages etc.
        except (serial.SerialException, OSError):
            print("Wokwi simulator stopped")
        finally:
            port.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--keyboard", action="store_true", help="use keys 1/2/3 instead of Wokwi")
    parser.add_argument("--wokwi", default="rfc2217://localhost:4000", help="Wokwi serial URL (see wokwi.toml)")
    parser.add_argument("--port", type=int, default=5000, help="TCP port the app connects to")
    args = parser.parse_args()

    server = AppServer(args.port)
    try:
        if args.keyboard:
            run_keyboard(server)
        else:
            run_wokwi(server, args.wokwi)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
