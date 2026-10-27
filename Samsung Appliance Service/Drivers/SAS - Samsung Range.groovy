/**
 *  SAS - Samsung Range
 *
 *  Child of Samsung Appliance Listener. Verified against TP1X_DA-KS-RANGE-0101X
 *  (32 entities) plus its Subdevice 1 second oven cavity (13 entities).
 *
 *  A range is the oven + cooktop combo. An oven on its own uses
 *  SAS - Samsung Oven, and a cooktop on its own SAS - Samsung Cooktop.
 *
 *  ONE device for the whole range, doing the job of the two HubiThings
 *  Replica drivers (Samsung Oven + Samsung Oven Cavity):
 *
 *    divider OUT   ovenCavityStatus = off. The main attributes are the whole
 *                  oven; cavity commands are refused, as Replica's were.
 *    divider IN    ovenCavityStatus = on. The main attributes are the UPPER
 *                  oven and the cavity* attributes are the LOWER oven.
 *
 *  The second cavity is a separate HA device linked by via_device_id. The
 *  listener folds it into this device; its entities arrive with scope
 *  "subdevice1" and land on lowerOven* attributes.
 *
 *  Where the divider comes from: the lower cavity's /connected resource,
 *  which LocalThings exposes as a diagnostic binary sensor it calls "Cloud
 *  connected". On this range it is really the cavity-installed flag that
 *  SmartThings reports as ovenCavityStatus -- On with the divider in, Off
 *  without. "Cavity state" is NOT it: that stays Ready either way.
 *
 *  Attribute and command names follow the Replica drivers so Rule Machine
 *  rules carry over. Lower-oven names start with "lowerOven" so they are
 *  easy to find, since both ovens now share one device.
 *
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - Temperatures and setpoints are converted to the hub's scale, and setpoint commands convert back.
 *    - start and startLowerOven stop, rather than start the oven, when the mode is not offered or the time can't be read.
 *    - Cooking helpers moved to the library, shared with the other oven driver.
 *    - The lower oven's scope is learned as it is seen, as on the wall oven, not assumed.
 *    - setLamp, as on the wall oven; lampOn and lampOff still work.
 *    - setSelectOption, for any select; the lower oven's scope is learned from its selects too.
 *    - Setpoints are refused, not sent unconverted, until the appliance has reported its unit.
 */

metadata {
    definition(name: "SAS - Samsung Range", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "ContactSensor"
        capability "TemperatureMeasurement"
        capability "Refresh"

        // Upper oven, or the whole oven with the divider out (Replica: Oven)
        attribute "operatingState",        "string"
        attribute "ovenTemperature",       "number"
        attribute "ovenSetpoint",          "number"
        attribute "ovenMode",              "string"
        attribute "progress",              "number"
        attribute "completionTime",        "string"
        attribute "operationTime",         "number"
        attribute "lockState",             "string"
        attribute "remoteControlEnabled",  "string"
        attribute "doorState",             "string"
        attribute "cooktopOperatingState", "string"
        attribute "probeStatus",           "string"
        attribute "probeTemperature",      "number"
        attribute "probeSetpoint",         "number"

        // Lower oven (Replica: Oven Cavity)
        attribute "ovenCavityStatus",      "string"
        attribute "lowerOvenOperatingState",  "string"
        attribute "lowerOvenTemperature", "number"
        attribute "lowerOvenSetpoint",    "number"
        attribute "lowerOvenMode",        "string"
        attribute "lowerOvenProgress",        "number"
        attribute "lowerOvenCompletionTime",  "string"
        attribute "lowerOvenOperationTime",   "number"
        attribute "lowerOvenCookTime",        "number"
        attribute "lowerOvenRunning",         "string"
        attribute "lowerOvenState",       "string"

        // No Replica equivalent
        attribute "running",             "string"
        attribute "cavityState",         "string"
        attribute "cookTime",            "number"
        attribute "activeBurners",       "number"
        attribute "burner1",             "string"
        attribute "burner2",             "string"
        attribute "burner3",             "string"
        attribute "burner4",             "string"
        attribute "burner5",             "string"
        attribute "lamp",                "string"
        attribute "energySaving",        "string"
        attribute "cooktopOnAlert",      "string"
        attribute "sound",               "string"
        attribute "fastPreheat",         "string"   // wall ovens
        attribute "naturalSteam",        "string"   // wall ovens
        attribute "diagnosisStatus",     "string"   // diagnostic

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "start", [[name: "Mode", type: "STRING", description: "See the 'Cooking Mode' state variable"],
                          [name: "Time (hh:mm:ss OR secs)", type: "STRING"],
                          [name: "Setpoint", type: "NUMBER"]]
        command "stop"
        command "setOvenSetpoint",  [[name: "Temperature*", type: "NUMBER"]]
        command "setOvenMode",      [[name: "Mode*", type: "STRING", description: "See the 'Cooking Mode' state variable for this appliance's options"]]
        command "setOperationTime", [[name: "Time* (hh:mm:ss OR secs)", type: "STRING"]]

        command "startLowerOven", [[name: "Mode", type: "STRING", description: "See the 'Cooking Mode (subdevice1)' state variable"],
                                [name: "Time (hh:mm:ss OR secs)", type: "STRING"],
                                [name: "Setpoint", type: "NUMBER"]]
        command "stopLowerOven"
        command "setLowerOvenSetpoint",  [[name: "Temperature*", type: "NUMBER"]]
        command "setLowerOvenMode",      [[name: "Mode*", type: "STRING", description: "See the 'Cooking Mode (subdevice1)' state variable for this appliance's options"]]
        command "setLowerOvenOperationTime", [[name: "Time* (hh:mm:ss OR secs)", type: "STRING"]]

        command "lampOn"
        command "lampOff"
        command "syncClock"
        command "setFastPreheat", [[name: "Fast preheat*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setLamp", [[name: "Lamp*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setSelectOption", [[name: "Entity suffix*", type: "STRING"],
                                    [name: "Option*", type: "STRING"]]
    }

    preferences {
        input name: "exposeDiagnostics", type: "bool", defaultValue: false,
              title: "Expose diagnostic entities"
        input name: "logEnable", type: "bool", defaultValue: false, title: "Debug logging",
              description: "Turns itself off after 30 minutes"
        input name: "txtEnable", type: "bool", defaultValue: true, title: "Description text logging"
    }
}

#include almulder.SAS-Samsung-Appliance-Common

/** Library hook: this driver's names -> HubiThings Replica names. */
Map attrRenames() {
    return [
        "machineState"          : [name: "operatingState", values: RV_OPERATING_STATE],
        "setpoint"              : "ovenSetpoint",
        "cookingMode"           : "ovenMode",
        "progressPercent"       : "progress",
        "estimatedFinish"       : [name: "completionTime", format: "isoZ"],
        "childLock"             : [name: "lockState", values: RV_LOCK],
        "smartControl"          : [name: "remoteControlEnabled", values: RV_BOOL],
        "cooktopRunningState"   : "cooktopOperatingState",
        "probeConnected"        : [name: "probeStatus", values: RV_PROBE],
        "probeTargetTemperature": "probeSetpoint",
        "foodProbe"             : "probeTemperature",
        "foodProbeTarget"       : "probeSetpoint",

        "lowerOvenMachineState"    : [name: "lowerOvenOperatingState", values: RV_OPERATING_STATE],
        "lowerOvenCookingMode"     : "lowerOvenMode",
        "lowerOvenProgressPercent" : "lowerOvenProgress",
        "lowerOvenEstimatedFinish" : [name: "lowerOvenCompletionTime", format: "isoZ"],
        "lowerOvenCavityState"     : "lowerOvenState"
    ]
}

void installed() { log.info "${device.displayName} installed"; commonInstalled() }
void updated() { log.info "${device.displayName} updated"; commonUpdated() }

void uninstalled() { commonUninstalled() }

void parseEntity(Map e) {
    beginEntity(e)
    // Before the select branch: a lower-oven select is often the first
    // entity of that cavity to arrive.
    if (e.scope != "main") ovenNoteCavity((String) e.scope)
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, e.scope == "main" ? "" : "lowerOven")
        return
    }

    // Second oven cavity -> lowerOven* attributes.
    if (e.scope != "main") {
        // The divider flag: LocalThings' "Divider" sensor (almulder fork), or
        // on stock LocalThings the same reading as the diagnostic "Cloud
        // connected", which emitGeneric would otherwise hide.
        if (e.suffix == "divider" || e.suffix == "cloud_connected") {
            emitOnOff("ovenCavityStatus", e.state)
            return
        }
        emitGeneric(e, "lowerOven")
        return
    }

    switch (e.suffix) {
        case "power":
            emitOnOff("switch", e.state)
            return
        case "door":
            emitOpenClosed("contact", e.state)
            emitOpenClosed("doorState", e.state)
            return
        // Both are number attributes and both read "unknown" whenever the oven
        // is off, so skip rather than push a string into a numeric attribute.
        case "temperature":
            if (isNullState(e.state)) {
                if (logEnable) log.debug "${e.suffix}: no reading -- not emitting"
                return
            }
            emitTemperature("temperature", e)        // TemperatureMeasurement
            emitTemperature("ovenTemperature", e)    // Replica
            return
        case "setpoint":
            emitTemperature("setpoint", e)
            return
        default:
            emitGeneric(e)
    }
}

// --- upper / whole oven and cooktop -----------------------------------------

void on()  { haTurnOn("power") }
void off() { haTurnOff("power") }

/**
 * Replica's start(mode, time, setpoint). Every argument is optional: while
 * the oven is idle LocalThings holds each value it is given, filling the rest
 * from the mode's defaults, and Start cooking sends them together.
 */
void start(String mode = null, String time = null, BigDecimal setpoint = null) {
    ovenStartCook("main", mode, time, setpoint)
}

void stop() { haPress("stop") }

void setOvenSetpoint(BigDecimal t) { haSetTemperature("setpoint", t) }
void setOvenMode(String v)         { setSelectOption("cooking_mode", v) }
void setOperationTime(String t)    { ovenSetCookMinutes("main", t) }

void lampOn()    { haTurnOn("lamp") }
void lampOff()   { haTurnOff("lamp") }
void setLamp(String v) { setSwitchOption("lamp", v) }
void syncClock() { haPress("sync_clock") }

// --- lower oven --------------------------------------------------------------

void startLowerOven(String mode = null, String time = null, BigDecimal setpoint = null) {
    if (!ovenCavityReady("startLowerOven")) return
    ovenStartCook(ovenCavityScope(), mode, time, setpoint)
}

void stopLowerOven() {
    if (!ovenCavityReady("stopLowerOven")) return
    haPress("stop", ovenCavityScope())
}

void setLowerOvenSetpoint(BigDecimal t) {
    if (!ovenCavityReady("setLowerOvenSetpoint")) return
    haSetTemperature("setpoint", t, ovenCavityScope())
}

void setLowerOvenMode(String v) {
    if (!ovenCavityReady("setLowerOvenMode")) return
    setSelectOption("cooking_mode", v, ovenCavityScope())
}

void setLowerOvenOperationTime(String t) {
    if (!ovenCavityReady("setLowerOvenOperationTime")) return
    ovenSetCookMinutes(ovenCavityScope(), t)
}

// --- settings -----------------------------------------------------------

void setFastPreheat(String v) { setSwitchOption("fast_preheat", v) }

void refresh() { haRefreshAll() }
