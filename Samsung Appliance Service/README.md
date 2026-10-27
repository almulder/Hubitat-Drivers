# SAS — Samsung Appliance Service for Hubitat

Bring Samsung appliances into Hubitat over your LAN, as **one Hubitat device per
appliance** instead of one per Home Assistant entity.

```
appliance  --CoAP/DTLS-->  Home Assistant + LocalThings  --websocket-->  Hubitat
```

A washer arrives as a single **Samsung Washer** device with `machineState`,
`running`, `progressPercent`, `estimatedFinish`, `childLock` and the rest as
attributes, plus `start` / `stop` / `pause` / `setCycle` as commands. Not 34
separate devices.

> **Status: working, and young.** Six appliance types are running on real
> hardware. Twelve more are written from upstream's own registry definitions
> and have never been run. See [Supported appliances](#supported-appliances).

---

## ⏳ Getting credentials: SmartThings API access is no longer free

**This is the part that may cost money or no longer be possible.**

LocalThings talks to your appliances locally, but obtaining the client
certificate for an appliance goes through Samsung's SmartThings API. Access
to that API was free through **30 September 2026**; Samsung began charging
for it in **October 2026**.

If you minted your certificates before then, you are set: they keep working,
survive appliance resets and do not need regenerating.

If you have not, check Samsung's current SmartThings API terms and the
[LocalThings](https://github.com/mbillow/localthings) project before you
start — whether certificates can still be minted, at what price and on what
terms, is up to Samsung, and this package cannot help with that step.

Two related notes:

- **Do not deregister your appliances from SmartThings.** Deregistering
  triggers a network-settings reset the next time the appliance reaches
  Samsung's servers, dropping it off Wi-Fi until you re-onboard it through the
  app. Registered-but-firewalled is the safe state.
- Once you have certificates, the local path keeps working with the appliance
  blocked from the internet — with one caveat under
  [Known behaviour](#known-behaviour).

---

## What you need

| | |
|---|---|
| **Home Assistant** | with [`mbillow/localthings`](https://github.com/mbillow/localthings) installed, your appliances added, and authenticating. **This is a hard dependency** — see below. |
| **A long-lived access token** | Home Assistant → your profile → Security |
| **Hubitat** | platform **2.5.0 or later** to appear under Integrations; it works on older builds but lands under Apps |
| **Appliances** | Samsung, roughly 2022 or newer — Tizen RT 3.x / DAWIT 3.0+ board families |

### Why Home Assistant is required

Hubitat cannot talk to these appliances directly. They speak DTLS-CoAP with
client-certificate authentication, and Groovy has no DTLS-CoAP stack. There is
no way around this short of a separate bridge process, which is what
LocalThings already is — with fifteen maintained appliance registries behind
it.

If LocalThings is not already working in Home Assistant, start there. This
integration is only useful once it is.

---

## Installing

The library must go in **first** — the appliance drivers will not compile
without it.

1. **Libraries code** → *New Library* → paste `Libraries/SAS-Samsung-Appliance-Common.groovy`
2. **Drivers code** → *New Driver* → paste `Drivers/SAS - Samsung Appliance Listener.groovy`
3. **Drivers code** → paste each `Drivers/SAS - Samsung <appliance>.groovy` you need
4. **Apps code** → *New App* → paste `App/SAS - Samsung Appliance Service.groovy`
5. **Integrations** → *Add user integration* → **SAS - Samsung Appliance Service**

Then enter your Home Assistant IP, port and token, press **Test connection**,
open **Find and select appliances**, tick the ones you want, and press Done.

The IP field wants a bare address like `192.168.1.158`. Paste a full URL and it
strips the scheme, path and port for you.

This package is not in Hubitat Package Manager yet: HPM installs apps and
drivers but the shared library has to ship as a Hubitat bundle, which is still
to be published. Until then, install by hand as above.

**Upgrading:** update the library first, then the listener and the appliance
drivers, then the app. Open each appliance device and press **Save
Preferences** once, so its debug logging picks up the 30-minute auto-off.

### ⚠️ Breaking changes in this release

Check any rule, dashboard or webCoRE piston that uses these before upgrading:

- **Energy is now in kWh, 1000× smaller than before.** LocalThings reports Wh;
  the drivers now convert to kWh, Hubitat's EnergyMeter unit.
  - Dishwasher, Dryer, Microwave: `energy`
  - Washer: `energy`, `energySaved`
  - Refrigerator: `energy`, `energySaved`, `energyThisMonth`,
    `energyLastMonth`, `powerEnergy`
- **Temperatures are converted to your hub's scale**, and every setpoint command
  takes a value in your hub's scale. Before, values were passed through and
  only the label changed. A setpoint command is refused, with a warning in the
  log, until the appliance has reported which unit it uses.
- **Air Conditioner:** `temperature` is now the room reading. The outdoor
  reading that used to be there is now `outdoorTemperature`. The air
  conditioner also gains Switch: `on`, `off` and `setClimateMode` drive it.
- **Induction Cooktop:** `temperature` is now the probe's reading. The probe's
  target, which used to be there, is now `probeTargetTemperature`.
- **Air Monitor, Vacuum Station:** `battery` is a number with the Battery
  capability, not a string.
- **Dishwasher:** `filterUsage` is a number.
- **Cooktop:** `mainTimerState` is a string.
- **Subdevice attributes:** a subdevice's selects are named like its other
  readings, `subdevice1CookingMode` rather than `Subdevice1CookingMode`. On the
  Washer, Dryer, Dishwasher, Refrigerator and Microwave, a subdevice reading now
  gets its own `subdevice1…` attribute instead of overwriting the main one.
- **Refrigerator:** `setFlexZoneMode` and `setPantryZoneMode` take free text,
  any mode this fridge offers, instead of one model's fixed list.
  `setRapidCooling` and `setRapidFreezing` offer Off/On; on and off still work.
- **Debug logging is off by default** on every device and turns itself off
  30 minutes after you enable it.

**The token must belong to a Home Assistant administrator.** The listener reads
the device and entity registries and subscribes to your appliances' state
changes, which Home Assistant only allows administrators to do.

If Home Assistant rejects the token, the listener stops trying until you
change the token in the app or press **Reconnect** on the listener device.
Retrying a bad token every few minutes would fill Home Assistant's log and, if
`ip_ban_enabled` is on, get the hub's address banned — after which even the
corrected token is refused until you remove the ban from
`ip_bans.yaml`.

---

## What you get

**One device per appliance.** A range with two oven cavities is still one
device. `ovenCavityStatus` says whether the divider is in: with it out the
main attributes are the whole oven, with it in they are the upper oven and
the lower oven arrives as `lowerOven*` attributes. Wall ovens with a divider
work the same way on the Oven driver.

**HubiThings Replica names.** Where a HubiThings Replica driver exists for
the appliance (washer, dryer, dishwasher, refrigerator, oven/range), the
attributes and commands use Replica's names — `machineState`,
`washerJobState`, `completionTime`, `timeRemaining`, `lockState`,
`remoteControlEnabled`, `operatingState`, `ovenSetpoint` and so on — so rules
written against Replica carry over. Values are translated where Replica's
differed (`machineState` reads `run`/`stop`/`pause`, `lockState` reads
`locked`/`unlocked`).

**Everything else is named from the entity** rather than a hardcoded list, so
an entity LocalThings adds later shows up on its own.

**Commands** for the controls that work over the local connection — `start`,
`stop`, `pause`, `setOvenSetpoint`, `setBrightnessLevel`, `setHoodFanSpeed`,
`setCoolingSetpoint`, `setFlexZoneMode`, and the rest. Settings HA lists but
the appliance ignores over the local connection have no command; their
current value still shows as an attribute.

**A state variable per selectable setting**, listing exactly what *your*
appliance offers:

```
Cycle:                Bedding, Normal, Small Load, Delicate, Self Clean+, Towels, ...
Spin Speed:           Rinse hold, No spin, Extra low, Low, Medium, High, Extra high
Flex Zone Mode:       Freeze, Soft freeze, Meat/Fish, Fruit & vegetables, Beverage
```

Names come from Home Assistant's own translation table at run time, so they
match what HA shows and they are right for whatever board your appliance has.
Where a command offers a dropdown, such as the fridge's zone modes, it lists
those same names.

**`healthStatus`** — `online`, `partial` or `offline` — so a rule can tell
"the door is closed" from "the appliance is not answering". An unavailable
entity holds its last value rather than reporting a fake one.

---

## Supported appliances

Running on real hardware:

| | Board family tested against |
|---|---|
| Dishwasher | `DA_DW_A51_20_COMMON` |
| Washer | `DA_WM_TP1_21_COMMON` |
| Dryer | `DA_WM_TP1_21_COMMON` |
| Refrigerator | `TP2X_REF_20K` |
| Range (incl. second cavity) | `TP1X_DA-KS-RANGE-0101X` |
| Microwave | `TP1X_DA-KS-MICROWAVE-01051` |

Written but **never run** — generated from upstream's registry definitions,
because none of this hardware was available:

Air Conditioner · Air Dresser · Air Monitor · Air Purifier · Cooktop ·
Dehumidifier · Heat Pump (EHS) · Induction Cooktop · Oven · Range Hood ·
Vacuum Station · Water Purifier

They should work — nothing in a driver is specific to one appliance model, and
option lists and names are read from your install at run time. But they are
untested, and **reports are very welcome**.

---

## Known behaviour

Things that look like bugs and are not:

- **Some writes are hardware-gated.** Power, child lock and
  remote-control-enable accept the write and then snap back to the physical
  switch. Samsung's own app cannot change these remotely either.
- **Brief connection drops are normal.** Samsung's firmware closes DTLS
  sessions periodically and LocalThings reconnects. `healthStatus` will flap
  occasionally. Only worry above a handful per minute.
- **One session per appliance.** Samsung's stack allows exactly one DTLS
  session, so nothing else can hold one while Home Assistant does — no running
  probes or cert tooling alongside it.
- **Blocking an appliance from the internet costs you push updates.** Reads and
  writes keep working over the LAN, but the appliance stops emitting local
  state-change notifications when it cannot reach Samsung. Freshness drops from
  near-instant to one poll interval.
- **Select settings are text fields, not dropdowns.** Hubitat cannot build a
  dropdown from live data — command constraints are fixed when the driver is
  saved, and driver preferences are not dynamic either. A static list would
  have to offer every option every board might have (71 washer cycles against
  the 14 a given machine can do) with no way to hide the rest. The state
  variable next to each command tells you what to type.
- **Some options show as raw codes.** LocalThings has no name for every cycle
  on every board. Anything unnamed appears as its hex code, exactly as it does
  in Home Assistant. If you work out what one is, please open an issue — those
  belong upstream.

---

## Limitations

- The air conditioner's power and HVAC mode can be set; its setpoints and fan
  modes cannot yet. The heat pump's `water_heater` mode is reported as
  `waterHeaterMode` but cannot be set yet.
- Diagnostic entities are hidden by default; there is a toggle in the app.
  Turning it off removes the diagnostic attributes already on the devices.
- Temperatures are converted to your hub's scale. A setpoint command takes a
  value in your hub's scale too, and is converted to the appliance's unit
  (rounded to a whole degree) before it is sent. Until the appliance has
  reported its unit, the command is refused rather than guessed, and an oven
  `start` with a setpoint does not press Start.
- Device names with accented or non-Latin letters will not route: Home
  Assistant transliterates them when building entity ids, and Hubitat has no
  equivalent. LocalThings names devices in English, so this should not arise.
- Energy readings are converted to kWh. LocalThings sends Wh, or no unit at
  all, in which case Wh is assumed — confirm against your appliance before
  building cost calculations on them.

---

## This is unofficial

Not affiliated with, endorsed by, or supported by Samsung or Hubitat.

It works because these firmware families ship a factory ACE granting a
publicly-known UUID access, and because the intermediate CA has been public for
years. The underlying certificate is valid into 2035 and a fleet-wide rotation
would be expensive and risky for Samsung, so the approach is not fragile — but
it is also not sanctioned, and **some 2026 models already reject it**, using
newer authentication profiles for which no working credential exists. Samsung's
likely direction is new profiles on new hardware rather than breaking existing
ones.

Use it with that understood.

---

## Credits

- **[mbillow/localthings](https://github.com/mbillow/localthings)** — the Home
  Assistant integration this is built on. All the hard protocol work lives
  there.
- **[QuiteYellow/SmartThings-Local](https://github.com/QuiteYellow/SmartThings-Local)**
  — the DTLS-CoAP protocol library underneath it.

Neither project is responsible for this one. If something is wrong with how
your appliance is *read*, it is probably worth reporting there; if it is wrong
with how it appears *in Hubitat*, report it here.

---

## Contributing

Cycle names are the most useful thing you can contribute. If a select shows raw
hex codes, set that option on the appliance's own control panel, note the code
the device reports, and open an issue with the code, the name, and your board
family. Those go upstream to LocalThings so everyone benefits.

Development notes — how entities are routed, how option names are resolved at
run time, and the offline validators used to check changes against captured
fixtures — are kept with the working sources rather than in this package.
