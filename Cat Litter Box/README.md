# Cat Litter Box (Tuya Local) for Hubitat

A Hubitat driver for the Tuya **"Smart Cat Litter Box 04 ZY"** (sold under several brands and usually set up in the **Smart Life** or **Tuya Smart** app). It controls the box directly over your local network, with no cloud, so you can block the box from the internet and still run it from Hubitat.

The box uses Tuya's newest local protocol (3.5), which the existing Hubitat Tuya drivers don't support. This driver implements it natively.

Licensed under the [PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0): free for personal / noncommercial use.

> Provided "AS IS," without warranties of any kind.

---

## What you get

| Command | What it does |
| --- | --- |
| **clean** / **stopClean** | Start or stop a cleaning cycle. Starting a clean turns Auto mode off, as it does in the app. |
| **empty** / **cancelEmpty** | Dump all the litter, or cancel. |
| **autoMode** on/off | Clean automatically after the cat leaves. |
| **deodorize** on/off | Deodorizer. |
| **infraredSensor** on/off | Cat-detection radar. Leave it on so the box doesn't move with a cat inside. (On these boxes the underlying setting is inverted, "radar off"; the driver handles that, so **on** means the radar is on.) |
| **childLock** on/off | Locks the buttons on the box. |
| **smartClean** on/off | Smart clean. The box also turns this on while an automatic clean is running and off when it finishes. |
| **autoDeodorize** on/off | Deodorize automatically after each clean (about 30 minutes). |
| **setClumpingTime** | Wait after the cat leaves before cleaning, 0–60 minutes. |
| **setCleaningInterval** | Cleaning interval, 0–120 minutes. |
| **setBinCalibration** | Waste bin calibration, 0–15. |
| **setLitterLeveling** | Litter leveling setting, 0–6. |
| **refresh** | Reads the full state from the box. |

**Cat detection:** the device is a **Motion Sensor**. `motion` is `active` while the cat is at or in the box and `inactive` once it leaves, so you can use cat visits in Rule Machine, webCoRE or dashboards (for example, count visits or get notified).

**Visit data:** after each visit the box reports `catWeight` (in lb or kg, set by the **Weight unit** preference), `lastVisitDuration` (seconds) and, on boxes that send it, `visitsToday`. `fault` shows `none`, or which part reported a problem (motor, program, g-sensor). A new fault is also logged as a warning.

**Waste bin:** `wasteBin` is `full` while the box's Full light is on and `ok` otherwise.

**Change log for every data point:** each change the box reports becomes a `dpChange` event (for example `DP 101 clean: false → true`), labeled as confirmed, `[unconfirmed]` or `UNKNOWN`, including data points that appear for the first time. Hubitat keeps only a limited number of events per attribute (11 by default), so raise the device's max events if you want a longer history.

**Typical automatic cycle:** cleaning starts after the cleaning wait time once the cat leaves, takes about 5 minutes (Auto mode reads off while it runs), then deodorizes for about 30 minutes. If `cleaning` stays on much longer than that, the box has usually paused, for example because the lid is open or jammed, and needs attention. That makes a good rule or piston trigger.

Each setting is also an attribute (`cleaning`, `emptying`, `autoMode`, `deodorizing`, `infraredSensor`, `childLock`, `smartClean`, `autoDeodorize`, `clumpingTime`, `cleaningInterval`, `binCalibration`, `litterLeveling`) that updates as soon as the box reports a change, including changes made in the app or on the box itself. `session` shows the connection state (`ready`, `negotiating`, `offline`).

---

## Installing

### Hubitat Package Manager (recommended)

1. In HPM, choose **Install**, search for **Cat Litter Box**, and install it.
2. Go to **Devices → Add device → Virtual**, pick the **Cat Litter Box (Tuya Local)** driver, and save.
3. Fill in the preferences (below) and click **Save Preferences**.

### Manual

1. **Drivers Code → New driver**, then **Import** this URL and **Save**:
   `https://raw.githubusercontent.com/almulder/Hubitat-Drivers/main/Cat%20Litter%20Box/Drivers/Cat%20Litter%20Box%20(Tuya%20Local).groovy`
2. Create the device as in steps 2 and 3 above.

Add one Hubitat device per litter box.

---

## Getting the Device ID and Local Key

Each box needs its **Device ID** and **Local Key**. You get them once, for free, from Tuya's developer site. The driver's preferences page shows these steps too.

1. Pair the box in the **Smart Life** (or Tuya Smart) app.
2. Create an account at [platform.tuya.com](https://platform.tuya.com), then **Cloud → Development → Create Cloud Project**. Pick the data center for your region (for example **Western America** for the US).
3. In the project, go to **Devices → Link App Account → Add App Account**, and scan the QR code with Smart Life (**Me** tab, scan icon top right). Your box appears in the device list with its **Device ID**.
4. **Local Key:** **Cloud → API Explorer → Device Management → Query device details**, enter the Device ID, and copy the `local_key` value. Or run `python -m tinytuya wizard` with the project's Access ID and Access Secret (on the project's Overview page).
   - If the API says the **IoT Core subscription has expired**, renew the free trial under **Cloud → Cloud Services → IoT Core**.
5. Give the box a fixed/reserved IP address in your router.

Copy the key exactly. It is 16 characters and often contains symbols such as `` ` `` or `|`. **Re-pairing or resetting the box in the app changes its Local Key**, so you'd have to get the new one.

If your Smart Life devices are spread over more than one account, link each account in step 3.

---

## Preferences

| Setting | What it does |
| --- | --- |
| **Device IP address** | The box's IP on your network. Give it a DHCP reservation so it doesn't change. |
| **Device ID** | From the Tuya cloud project (see above). |
| **Local Key** | From the Tuya cloud project (see above). |
| **Weight unit** | Show `catWeight` in lb (default) or kg. |
| **Poll interval** | How often the driver reads the full state, in seconds. Changes are pushed by the box as they happen, so this is just a backstop. |
| **Description logging** | Logs each change the box reports. |
| **Debug logging** | Off by default; turns itself off 30 minutes after you enable it. |

---

## Blocking the box from the internet

Once the driver works, you can block the box's internet access in your router or firewall (for example a rule blocking the box's IP from reaching the internet). Hubitat talks to it over the LAN, so control keeps working; the Smart Life app will show it offline. Don't block traffic between Hubitat and the box.

---

## Troubleshooting

- **`no handshake reply … check the IP and Local Key`** or **`handshake rejected`**: the Local Key is wrong (often a missing or moved symbol) or the IP is wrong.
- **selfTest** checks the driver's encryption on your hub and logs `PASS`/`FAIL`, plus a short fingerprint of the stored key (not the key itself).
- The box drops idle connections after about 30 seconds; the driver sends a heartbeat every 10 seconds and reconnects automatically if the connection goes quiet.
- Other Tuya litter boxes may use different data points. Unmapped values appear in the `dps` attribute.

---

## Changes

**0.1.3 (2026-11-06)**

- Fixed false "no traffic" reconnects, a false "Local Key is wrong" warning and dropped connections. They were caused by Hubitat threads overwriting each other's state; the driver now uses `atomicState`.
- Fixed **infraredSensor** being inverted: **on** now turns the radar on.
- New: `wasteBin` (full/ok), **smartClean** and **autoDeodorize** controls.
- New: every data point change is a labeled `dpChange` event, including first appearances.
- Keeps the connection alive with a 10-second heartbeat and reconnects automatically. A failed handshake is retried before the key is blamed, an outage logs one warning and one "reconnected" message instead of one per retry, and only one status query is queued while the box is unreachable.

**0.1.2 (2026-11-04)**

- New attributes: `catWeight` (lb or kg), `lastVisitDuration`, `visitsToday` and `fault`, plus a **Weight unit** preference.

**0.1.1 (2026-11-04)**

- Cat detection: the device is now a Motion Sensor (`motion` active while the cat is at or in the box).

**0.1.0 (2026-11-04)**

- Initial release: local Tuya protocol 3.5 control of the Smart Cat Litter Box 04 ZY. Clean, empty, auto mode, deodorize, infrared sensor, child lock, clumping time, cleaning interval, bin calibration and litter leveling, with live status updates.
