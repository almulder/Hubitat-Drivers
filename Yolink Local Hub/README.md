# YoLink™ Local Hub ↔ Hubitat (Local-only) — App & Drivers

This is an **App + drivers** that let Hubitat talk **directly** to the **YoLink Local Hub** over your LAN.  
**No cloud required.** Events arrive via the hub’s **MQTT** broker; optional state “reconcile” uses the local **HTTP API**.

> **BETA:** Tested on my setup. Please expect rough edges and report anything you find.
>
> This project is **not developed, endorsed, or associated** with YoLink™ or YoSmart, Inc.  
> Provided “AS IS,” without warranties of any kind.

---

## What you get

- **Local-only integration**: works even if the internet is down
- **Real-time events** via MQTT (port `18080`)
- **Optional periodic reconcile** via local HTTP `getState` (port `1080`)
- **Customizable Date/Time format** for all timestamps
- **Temperature scale** selection (°F/°C) with automatic conversion
- Cleaner device pages (hides internal format/driver attributes from Current States)

**Initial device support**
- THSensor (Temp/Humidity)
- Leak Sensor / Leak Sensor 3
- Door Sensor
- Power Failure Alarm (PFA)
- Motion Sensor (Indoor / Outdoor)
- MQTT Listener “device” (handles the message bus)

> More YoLink device types can be added if I can get my hands on them

---

## Why local?

- **Latency**: MQTT events in milliseconds on your LAN  
- **Resilience**: automations keep working offline  
- **Privacy**: data stays on your network

---

## How it works

1. **Parent App** (`YoLink™ Device Local Service`)
   - Stores your Local Hub **IP**, **Subnet (Net ID)**, **Client ID**, and **Client Secret**
   - Requests a **local access token**, discovers devices, lets you **select which to expose**
   - Creates one **MQTT Listener** device that subscribes to `ylsubnet/<NetID>/+/report` and routes messages by `deviceId`

2. **Drivers**
   - Receive MQTT reports from the app
   - Support a local HTTP `getState` call for optional “reconcile”
   - Pull **date/time format** and **temperature scale** from the parent app

3. **Update strategy**
   - **Primary**: MQTT push (near real-time)
   - **Secondary**: optional “Periodic reconcile” (Off / Hourly / 6h / 12h / Daily)

---

## Requirements

- **YoLink Local Hub** with Local API enabled
- **Static/reserved IP** for your YoLink hub on your router
- **Client ID** and **Client Secret** from the YoLink app (Integrations → Local API)
- Hubitat Elevation (current firmware recommended)

> This integration is **only** for the **Local Hub**. YoLink Cloud is not used.

---

## Installation (quick start)

### 1) Install code in Hubitat
- **Apps Code** → add the parent app from this folder → Save.
- **Drivers Code** → add drivers you need (THSensor, Leak Sensor, Door Sensor, PFA, MQTT Listener) → Save.

### 2) Add the App
- Apps → **Add User App** → **YoLink™ Device Local Service**

### 3) Enter credentials
- **Local Hub IP** (reserved/static)
- **Subnet (Net ID)**
- **Client ID**
- **Client Secret**

### 4) Other Settings
- **Temperature Scale** (F/C)
- **Date/Time format** (dropdown with live examples)
- **Periodic reconcile**: Off / Hourly / Every 6 hours / Every 12 hours / Daily  
  *(Recommended while testing: Hourly or Every 6 hours)*

### 5) Select devices
- Choose only the YoLink devices you want in Hubitat.  
- The app auto-creates one **MQTT Listener** device.

### 6) Done
- Open a created device and watch events arrive.
- Optional: enable **debug logging** in the app or driver while testing.

---

## Updating / Migration notes

- Children now use the **YoLink `deviceId` as the DNI** (for reliable MQTT routing).  
  If you’re coming from older builds, the app will recreate devices with the new DNI automatically.

---

## Troubleshooting

- **MQTT connected?**  
  Open the **MQTT Listener** device. You should see `MQTT: connected` and live log entries.

- **“Unable to locate target device … for MQTT”**  
  The app **ignores events** for devices you did not create (no errors, only a debug line if debugging is on).

- **No updates?**  
  - Verify Local Hub IP and that the hub is reachable on the LAN.  
  - Re-enter credentials; check that the token request succeeds.  
  - Don’t set “Periodic reconcile” to Off while verifying.

- **Timestamps / Units wrong?**  
  Change Date/Time format and Temperature Scale in the parent app; drivers pick it up automatically.

---

## FAQ

**Q: Do I need the YoLink cloud account?**  
A: No. This is local-only (Local Hub + LAN).

**Q: Can I choose not to add some YoLink devices?**  
A: Yes. Only selected devices are created. MQTT for unselected devices is ignored.

**Q: How often should I reconcile?**  
A: For most homes, **Hourly** or **Every 6 hours** is plenty. Turn it **Off** if you’re happy with pure MQTT.

---

## Privacy & Security

- All traffic is local (MQTT + HTTP on your LAN).  
- Use a **reserved IP** for your YoLink hub.  
- Ensure your LAN is trusted.

---

## Contributing

- Issues, logs, new devices sent to me for driver creation are welcome—especially for additional device types.

---

## Credits

- Huge thanks to **Steven Barcus** for foundational work on the cloud side.  
- Built with a lot of late nights (and a lot (and I mean a lot) of AI assistance).

---

## License / Disclaimer

This software is neither developed, endorsed, or associated with **YoLink™** or **YoSmart, Inc.**  
Developer retains all rights, title, and interest. This is provided **“AS IS”** without warranties.

See headers in the source for license details.

---
