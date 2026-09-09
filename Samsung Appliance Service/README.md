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

## ⏳ Get your credentials before 30 September 2026

**This is the part that expires.**

LocalThings talks to your appliances locally, but obtaining the client
certificate for an appliance goes through Samsung's SmartThings API. Samsung
begins charging for SmartThings API access in **October 2026** — it is free
through **30 September 2026**.

Nobody knows yet whether certificates can still be minted afterwards, at what
price, or on what terms. If you intend to use this — or think you might —
**set up LocalThings and mint your certificates before that date.**

Once minted, they keep working: they survive appliance resets and do not need
regenerating. The expensive step is getting them in the first place.

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
5. **Integrations** → *Add User App* → **SAS - Samsung Appliance Service**

Then enter your Home Assistant IP, port and token, press **Test connection**,
open **Find and select appliances**, tick the ones you want, and press Done.

The IP field wants a bare address like `192.168.1.158`. Paste a full URL and it
strips the scheme, path and port for you.

---

## What you get

**One device per appliance.** A range with two oven cavities is still one
device — the second cavity arrives as `cavity2*` attributes rather than a
seventh appliance.

**Attributes worth automating on**, named from the entity rather than a
hardcoded list, so an entity LocalThings adds later shows up on its own:
`machineState`, `running`, `door`/`contact`, `progressPercent`,
`completionTime`, `estimatedFinish`, `energy`, temperatures, and so on.

**Commands** for everything writable — `start`, `stop`, `pause`, `setCycle`,
`setSpinSpeed`, `setCoolerSetpoint`, and the rest.

**A state variable per selectable setting**, listing exactly what *your*
appliance offers:

```
Cycle:                Bedding, Normal, Small Load, Delicate, Self Clean+, Towels, ...
Spin Speed:           Rinse hold, No spin, Extra Low, Low, Medium, High, Extra High
Wash Temperature:     None, Tap Cold, Cold, Warm, Hot, Extra hot
```

The matching command takes that text — `setSpinSpeed("Extra High")` — or the
raw device code, so a rule can use either. Names come from Home Assistant's own
translation table at run time, so they match what HA shows and they are right
for whatever board your appliance has.

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

- Air conditioner and heat pump also expose `climate` / `water_heater`
  entities. Their state is reported, but setting them needs service helpers
  that do not exist yet.
- Diagnostic entities are hidden by default; there is a toggle in the app.
- The `energy` sensors arrive with no unit. Values look like Wh and are passed
  through unchanged — confirm against your appliance before building cost
  calculations on them.

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
