# InvisOutlet™ ↔ Hubitat (MQTT) — App & Drivers (BETA)

This is an **App + drivers** that let Hubitat talk to **InvisOutlet Aura and Pro** smart outlets over **MQTT**.
No cloud, no vendor bridge box - InvisOutlet publishes standard Home Assistant MQTT Discovery messages to whatever broker you point it at, and this project speaks that protocol directly.

> **BETA:** Tested on my setup. Please expect rough edges and report anything you find.
>
> This project is **not developed, endorsed, or associated** with Intecular or InvisOutlet.
> Provided "AS IS," without warranties of any kind.

---

## What you get

- **One real Hubitat device per physical outlet** - not a maze of child devices, everything (both outlets, the nightlight/color light, sensors, buttons) lives on a single device
- **Automatic Aura vs. Pro detection** - the app creates the correct driver type for each unit automatically based on what it discovers
- **Independent control of both outlets** - `outlet1On/Off()` and `outlet2On/Off()`, named by position rather than "top/bottom" so it's correct even if the unit is mounted upside down
- **Touch button support** - physical button presses show up as standard Hubitat button events (`pushed` 1/2), ready for Rule Machine or Button Controller
- **Full sensor support (Pro)**: temperature, humidity, illuminance, motion, occupancy/presence, distance, Air Quality Index, CO2, Air Pressure, VOC
- **Color support (Aura)**: hue/saturation and color temperature via the standard `ColorControl`/`ColorTemperature` capabilities
- **Selectable units**: Temperature (°F/°C) and Distance (cm/in), applied instantly to already-known readings when changed
- **webCoRE-visible attributes and commands** - everything is explicitly declared, not just ad-hoc events
- **De-duplication** for devices/firmware that republish the same state multiple times per action
- **Built-in setup guides** for standing up an MQTT broker if you don't already have one (Raspberry Pi, or Hubitat's own built-in broker)

**Initial device support**

- InvisOutlet Pro - both outlets, nightlight, temperature, humidity, illuminance, motion, occupancy, distance, AQI, CO2, air pressure, VOC, touch buttons
- InvisOutlet Aura - both outlets, RGB/color-temperature light, illuminance, touch buttons
- MQTT Listener "device" (handles the message bus - not something you interact with directly)

> More InvisOutlet entities can be added as they're identified; PRs and MQTT log captures welcome.

---

## Why MQTT?

- **No extra vendor hardware** - unlike some ecosystems, InvisOutlet doesn't need a proprietary bridge; any MQTT broker works
- **Real-time events** - state changes arrive as they happen, not on a polling interval
- **Standards-based** - it's Home Assistant's own MQTT Discovery format, not a reverse-engineered proprietary protocol
- **Flexible broker options** - use a Raspberry Pi, a Docker container, or Hubitat's own built-in MQTT broker (added in recent firmware) - your choice, with setup guides for both built into the app

---

## How it works

1. **Parent App** (`InvisOutlet Device Service`)
   - Stores your MQTT broker address, username, and password
   - Creates a hidden **MQTT Listener** device that connects, subscribes, and parses discovery messages
   - Briefly waits during setup to let the listener see every entity for each unit before showing you the device list (so it can tell Aura from Pro correctly)
   - Lets you **select which units to expose**, then creates one real device per unit using the correct driver
   - Routes MQTT state updates and outgoing commands between the Listener and your devices

2. **MQTT Listener driver**
   - Holds the actual broker connection (only drivers can, on Hubitat)
   - Parses Home Assistant MQTT Discovery messages, including the "device trigger" format used for the touch buttons
   - Builds a catalog of what it finds; creates no child devices itself
   - Includes an `auditTopics` command that summarizes every MQTT topic seen - which are wired up, which were skipped and why - useful for finding anything not yet supported

3. **InvisOutlet Pro / InvisOutlet Aura drivers**
   - Normal top-level Hubitat devices, not nested under the listener
   - Receive updates from the app and send commands back through it

---

## Requirements

- An MQTT broker reachable by both Hubitat and your InvisOutlet unit(s) - a Raspberry Pi running Mosquitto, or Hubitat's own built-in MQTT broker (Integrations → MQTT Export Integration)
- Your InvisOutlet's MQTT settings pointed at that same broker (InvisHome app → Outlet Settings → Customizations → Advanced → MQTT)
- Hubitat Elevation (current firmware recommended)

> Setup guides for both broker options (Raspberry Pi from scratch, or Hubitat's built-in broker) are included directly in the app - no need to look elsewhere first.

---

## Installation (quick start)

### 1) Install code in Hubitat

- **Drivers Code** → add `InvisOutlet MQTT Listener`, `InvisOutlet Pro`, and `InvisOutlet Aura` → Save each.
- **Apps Code** → add `InvisOutlet Device Service` → Save.

### 2) Add the App

- Apps → **Add User App** → **InvisOutlet Device Service**

### 3) About / disclaimer page

- Optionally enable debug logging.

### 4) MQTT Broker Settings

- Enter your broker's **URI** (e.g. `tcp://192.168.1.50:1883`), **username**, and **password**.
- No broker yet? Use the built-in **Show Guide** toggles for either a Raspberry Pi or Hubitat's built-in broker.

### 5) Other Settings

- **Naming**: optionally prefix names with "InvisOutlet -" and/or append the model type
- **Temperature Scale** (°F/°C) and **Measurement Scale** (cm/in for Distance)

### 6) Select devices

- The app polls the broker briefly and shows every InvisOutlet unit it finds, labeled by name and detected model (Aura/Pro), with an online/offline indicator.
- Choose which units to expose. The app creates one device per unit and a hidden MQTT Listener device.

### 7) Done

- Open a created device and watch events arrive.
- Optional: enable debug logging on the Listener device while testing.

---

## Troubleshooting

- **Nothing shows up on the device list?**
  Double-check the broker URI/credentials, and that your InvisOutlet's own MQTT settings point at the same broker. Try the built-in `auditTopics` command on the Listener device (once created) to see exactly what's arriving on the broker.

- **A sensor or button isn't showing up?**
  Turn on "Log raw MQTT traffic" on the Listener device briefly, trigger whatever you're looking for, and run `auditTopics` - it lists any topic that arrived but wasn't wired to anything, plus why any discovery entries were skipped.

- **Same event logged multiple times?**
  Some firmware republishes the same state more than once per action. This is de-duplicated automatically; if you still see it, the device may be publishing on a wire format not yet accounted for - open an issue with a raw MQTT capture.

- **Color commands doing nothing?**
  Expected on InvisOutlet Pro - its nightlight is plain white-only. Color is Aura-only, by design (the Pro driver doesn't expose those commands at all).

- **Units wrong (C vs F, cm vs in)?**
  Change Temperature/Measurement Scale on the app's Settings page; devices update immediately without waiting for the next MQTT publish.

---

## FAQ

**Q: Do I need Home Assistant installed?**
A: No. InvisOutlet happens to use Home Assistant's MQTT Discovery *format*, but nothing here requires Home Assistant itself to be running.

**Q: Can I choose not to add some InvisOutlet units?**
A: Yes. Only selected units are created. Deselecting a unit removes its device; removing all units also removes the MQTT Listener device.

**Q: Aura or Pro - how does it know which driver to use?**
A: The app waits briefly during setup to see each unit's full set of entities, then picks based on distinguishing features (e.g. air-quality sensors and motion/occupancy mean Pro; a color light means Aura) - not on the device's reported model string, which doesn't actually distinguish them.

**Q: What about Adaptive lighting / breathing / strobe / color-cycle effects?**
A: Confirmed via raw MQTT capture that these are handled entirely on-device/in the phone app, with nothing published over MQTT to hook into. Not something this integration can control.

---

## Privacy & Security

- All traffic stays on your LAN (MQTT broker of your choice).
- Reserve a static/DHCP-fixed IP for whatever runs your broker (Pi or otherwise) so it doesn't change unexpectedly.
- If using Hubitat's built-in broker, note it restarts along with the hub.

---

## Contributing

- Issues, MQTT log captures, and requests for additional InvisOutlet entities/features are welcome.

---

## Credits

- Built with a lot of late nights (and a lot - and I mean a lot - of AI assistance).

---

## License / Disclaimer

This software is neither developed, endorsed, nor associated with **Intecular** or **InvisOutlet**.
Developer retains all rights, title, and interest. This is provided **"AS IS"** without warranties.

See headers in the source for license details.
