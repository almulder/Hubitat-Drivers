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
 *  Version: 0.6.1
 *  Author:  Albert Mulder (almulder)
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
    }

    preferences {
        input name: "exposeDiagnostics", type: "bool", defaultValue: false,
              title: "Expose diagnostic entities"
        input name: "logEnable", type: "bool", defaultValue: true, title: "Debug logging"
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

void installed() { log.info "${device.displayName} installed" }
void updated() { log.info "${device.displayName} updated" }

void parseEntity(Map e) {
    rememberEntity((String) e.scope, (String) e.suffix, (String) e.entityId)
    noteEntityHealth(e)
    if (logEnable) log.debug "entity ${e.entityId} (${e.suffix}, scope=${e.scope}) = ${e.state}"
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, e.scope == "main" ? "" : "lowerOven")
        return
    }

    // Second oven cavity -> lowerOven* attributes.
    if (e.scope != "main") {
        cavityScope((String) e.scope)
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
            Map unit = [unit: "°${location.temperatureScale}"]
            emit("temperature", coerce(e.state), unit)       // TemperatureMeasurement
            emit("ovenTemperature", coerce(e.state), unit)   // Replica
            return
        default:
            emitGeneric(e)
    }
}

/**
 * The listener names a subdevice's scope after it ("subdevice1" on the range).
 * Remembered rather than assumed, so commands reach whatever this oven's
 * second cavity is called.
 */
private void cavityScope(String scope) {
    if (state.cavityScope != scope) state.cavityScope = scope
}

private String sub() { return (state.cavityScope ?: "subdevice1") as String }

// --- upper / whole oven -----------------------------------------------------

void on()  { haTurnOn("power") }
void off() { haTurnOff("power") }

/** Replica's start(mode, time, setpoint); every argument is optional. */
void start(String mode = null, String time = null, BigDecimal setpoint = null) {
    startCook("main", mode, time, setpoint)
}

void stop() { haPress("stop") }

void setOvenSetpoint(BigDecimal t) { haSetNumber("setpoint", t) }
void setOvenMode(String v)         { setSelectOption("cooking_mode", v) }
void setOperationTime(String t)    { setCookMinutes("main", t) }

// --- lower oven --------------------------------------------------------------

void startLowerOven(String mode = null, String time = null, BigDecimal setpoint = null) {
    if (!cavityInstalled("startLowerOven")) return
    startCook(sub(), mode, time, setpoint)
}

void stopLowerOven() {
    if (!cavityInstalled("stopLowerOven")) return
    haPress("stop", sub())
}

void setLowerOvenSetpoint(BigDecimal t) {
    if (!cavityInstalled("setLowerOvenSetpoint")) return
    haSetNumber("setpoint", t, sub())
}

void setLowerOvenMode(String v) {
    if (!cavityInstalled("setLowerOvenMode")) return
    setSelectOption("cooking_mode", v, sub())
}

void setLowerOvenOperationTime(String t) {
    if (!cavityInstalled("setLowerOvenOperationTime")) return
    setCookMinutes(sub(), t)
}

/** Replica refused cavity commands unless ovenCavityStatus was "on". */
private boolean cavityInstalled(String what) {
    if (device.currentValue("ovenCavityStatus") == "off") {
        log.warn "${device.displayName}: ${what} ignored -- the divider is out " +
                 "(ovenCavityStatus: off), so there is no lower oven"
        return false
    }
    return true
}

private void startCook(String scope, String mode, String time, BigDecimal setpoint) {
    if (mode) setSelectOption("cooking_mode", mode, scope)
    if (time) setCookMinutes(scope, time)
    if (setpoint != null) haSetNumber("setpoint", setpoint, scope)
    haPress("start_cooking", scope)
}

private void setCookMinutes(String scope, String time) {
    Integer mins = replicaTimeToMinutes(time)
    if (mins != null) haSetNumber("cook_time", mins, scope)
}

// --- settings -----------------------------------------------------------

void setFastPreheat(String v) { setSwitchOption("fast_preheat", v) }
void setLamp(String v) { setSwitchOption("lamp", v) }

void refresh() { haRefreshAll() }
