# WLED Controller for Hubitat

A Hubitat driver for [WLED](https://kno.wled.ge/) controllers. Turn the whole controller on and off, dim it, and switch presets, no matter how many segments each preset uses.

Based on [WLED Device Type](https://github.com/joyfulhouse/hubitat-wled) by Bryan Li, with fixes and changes listed below. Licensed under the [Apache License 2.0](LICENSE).

> Provided "AS IS," without warranties of any kind.

---

## What you get

- **On / Off** for the whole controller and all its segments (or a single segment, if you prefer).
- **Set Preset** to switch to any preset saved in WLED. The controller turns on if it was off.
- **Level** dims the master brightness.
- **Refresh** keeps on/off and level in sync with changes made in the WLED app.
- Color, color temperature, effect and alarm (siren/strobe) commands, applied to the segment set in **LED Segment**.

**Tip:** build each look (colors, effects, segment layout) as a preset in WLED and use **Set Preset** from your rules, webCoRE pistons or dashboards. Presets keep their own segment layout, so they work the same whether a preset uses one segment or five.

---

## Installing

### Hubitat Package Manager (recommended)

1. In HPM, choose **Install**, search for **WLED Controller**, and install it.
2. Go to **Devices → Add device → Virtual**, pick the **WLED Controller** driver, and save.
3. Enter the controller's address in **URI** (for example `http://192.168.1.50`) and click **Save Preferences**.

### Manual

1. **Drivers Code → New driver**, then **Import** this URL and **Save**:
   `https://raw.githubusercontent.com/almulder/Hubitat-Drivers/main/WLED/Drivers/WLED%20Controller.groovy`
2. Create the device as in steps 2 and 3 above.

### Moving from the original WLED driver

The driver has a new name and namespace, so it installs next to the old one. On each WLED device page, change **Type** to **WLED Controller**, click **Save Device**, then click **Save Preferences** once. Your rules and pistons keep working because the device stays the same. When no devices use the old driver, you can delete it.

---

## Preferences

| Setting | What it does |
| --- | --- |
| **URI** | The controller's address, e.g. `http://192.168.1.50`. Give it a DHCP reservation so it doesn't change. |
| **Control** | **Whole controller** (default): On, Off and Level act on the controller and all segments. **Single segment**: they act on the segment in LED Segment only. |
| **LED Segment** | Segment id used by Single segment mode and by the color/effect commands. Ids are shown in WLED under Segments. |
| **Transition time** | Transition used for level changes. |
| **Refresh interval** | How often the driver checks the controller's state. 1 Hour is plenty if you only control it from Hubitat. |
| **Debug logging** | Off by default; turns itself off 30 minutes after you enable it. |

---

## Changes from the original

**2.0.0 (2026-11-03)**

- New **Control** preference. **Whole controller** (default) turns the controller and all segments on and off and dims the master brightness, so it works no matter how many segments a preset uses. **Single segment** keeps the original per-segment behavior.
- Fixed the log error `NullPointerException: Cannot get property 'on' on null object on line 156 (method parseResp)`. It appeared on every refresh when the configured segment wasn't in the controller's reply, for example after a preset changed the segment layout.
- Segments are looked up by their WLED id, not by their position in the list.
- Fixed the **1 Hour** refresh interval, which actually polled every minute.
- Level is now synchronized from the controller, not just on/off.
- Only the segment list is kept in device state, not the whole JSON reply.
- Effect and palette list requests no longer reference an undefined variable.
- Color commands no longer crash before the first refresh, set Solid in one request instead of two, and setHue no longer sends out-of-range brightness.
- Set Preset refreshes the device two seconds later so on/off and level stay accurate.
- Debug logging defaults off and turns itself off after 30 minutes.
- Removed the **Power Off** preference (replaced by **Control**).
