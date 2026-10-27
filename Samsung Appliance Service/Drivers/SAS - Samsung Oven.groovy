/**
 *  SAS - Samsung Oven
 *
 *  Child of SAS - Samsung Appliance Listener. For an oven on its own (a wall
 *  oven); a range -- oven plus cooktop -- uses SAS - Samsung Range.
 *
 *  UNTESTED: no standalone oven on hand. Built from upstream's registry
 *  (`registry/by_type/oven.py`), which binds the same oven resources as the
 *  range -- so the entity names below are the ones verified on the range.
 *
 *  ONE device for the whole oven, doing the job of the two HubiThings
 *  Replica drivers (Samsung Oven + Samsung Oven Cavity), for ovens with a
 *  removable divider:
 *
 *    divider OUT   ovenCavityStatus = off. The main attributes are the whole
 *                  oven; cavity commands are refused, as Replica's were.
 *    divider IN    ovenCavityStatus = on. The main attributes are the UPPER
 *                  oven and the cavity* attributes are the LOWER oven.
 *
 *  An oven without a divider simply never reports a second cavity.
 *
 *  Where the divider comes from: the lower cavity's /connected resource,
 *  which LocalThings exposes as a diagnostic binary sensor it calls "Cloud
 *  connected". "Cavity state" is NOT it: that stays Ready either way.
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
 *    - lampOn and lampOff, as on the range.
 *    - setSelectOption, for any select; the lower oven's scope is learned from its selects too.
 *    - Setpoints are refused, not sent unconverted, until the appliance has reported its unit.
 */

metadata {
    definition(name: "SAS - Samsung Oven", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "ContactSensor"
        capability "TemperatureMeasurement"
        capability "Refresh"

        // Upper oven, or the whole oven with the divider out (Replica: Oven)
        attribute "operatingState",       "string"
        attribute "ovenTemperature",      "number"
        attribute "ovenSetpoint",         "number"
        attribute "ovenMode",             "string"
        attribute "progress",             "number"
        attribute "completionTime",       "string"
        attribute "operationTime",        "number"
        attribute "lockState",            "string"
        attribute "remoteControlEnabled", "string"
        attribute "doorState",            "string"
        attribute "probeStatus",          "string"
        attribute "probeTemperature",     "number"
        attribute "probeSetpoint",        "number"

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
        attribute "running",         "string"
        attribute "cavityState",     "string"
        attribute "cookTime",        "number"
        attribute "cooktopOnAlert",  "string"
        attribute "energySaving",    "string"
        attribute "fastPreheat",     "string"
        attribute "lamp",            "string"
        attribute "naturalSteam",    "string"
        attribute "sound",           "string"
        attribute "diagnosisStatus", "string"   // diagnostic

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

        command "setFastPreheat", [[name: "Fast preheat*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setLamp", [[name: "Lamp*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setSelectOption", [[name: "Entity suffix*", type: "STRING"],
                                    [name: "Option*", type: "STRING"]]
        command "lampOn"
        command "lampOff"
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
        case "temperature":
            if (isNullState(e.state)) return
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

// --- upper / whole oven -----------------------------------------------------

void on()  { haTurnOn("power") }
void off() { haTurnOff("power") }

/** Replica's start(mode, time, setpoint); every argument is optional. */
void start(String mode = null, String time = null, BigDecimal setpoint = null) {
    ovenStartCook("main", mode, time, setpoint)
}

void stop() { haPress("stop") }

void setOvenSetpoint(BigDecimal t) { haSetTemperature("setpoint", t) }
void setOvenMode(String v)         { setSelectOption("cooking_mode", v) }
void setOperationTime(String t)    { ovenSetCookMinutes("main", t) }

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
void setLamp(String v) { setSwitchOption("lamp", v) }
void lampOn()  { haTurnOn("lamp") }
void lampOff() { haTurnOff("lamp") }

void refresh() { haRefreshAll() }
