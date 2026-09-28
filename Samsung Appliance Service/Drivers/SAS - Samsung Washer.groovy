/**
 *  SAS - Samsung Washer
 *
 *  Child of Samsung Appliance Listener. Verified against DA_WM_TP1_21_COMMON
 *  (34 entities).
 *
 *  Note: the washer exposes power as a READ-ONLY binary_sensor, not a switch,
 *  so this driver deliberately does not claim capability "Switch" -- there is
 *  nothing to turn on.
 *
 *  Attribute names follow the HubiThings Replica Samsung Washer driver so
 *  Rule Machine rules carry over; see attrRenames().
 *
 *  Version: 0.6.1
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Washer", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "EnergyMeter"
        capability "Refresh"

        // HubiThings Replica names (Samsung Washer)
        attribute "switch",                 "string"
        attribute "machineState",           "string"
        attribute "washerJobState",         "string"
        attribute "completionTime",         "string"
        attribute "timeRemaining",          "string"
        attribute "washerWaterTemperature", "string"
        attribute "washerSpinLevel",        "string"
        attribute "lockState",              "string"
        attribute "remoteControlEnabled",   "string"

        // No Replica equivalent
        attribute "running",          "string"
        attribute "progressPercent",  "number"
        attribute "cycle",            "string"
        attribute "delayStart",       "number"
        attribute "detergentLow",     "string"
        attribute "softenerLow",      "string"
        attribute "waterConsumption", "number"
        attribute "energySaved",      "number"
        attribute "drumCleanDueIn",   "number"
        attribute "buzzerSound",      "string"
        attribute "detergentQuantity","string"
        attribute "detergentWaterHardness", "string"
        attribute "softenerQuantity", "string"
        attribute "softenerConcentration",  "string"

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "start"
        command "stop"
        command "pause"
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
        "power"          : "switch",
        "machineState"   : [name: "machineState", values: RV_MACHINE_STATE],
        "progress"       : [name: "washerJobState", values: RV_WASHER_JOB],
        "estimatedFinish": [name: "completionTime", format: "isoZ"],
        "completionTime" : [name: "timeRemaining", format: "hms"],
        "washTemperature": "washerWaterTemperature",
        "spinSpeed"      : "washerSpinLevel",
        "childLock"      : [name: "lockState", values: RV_LOCK],
        "smartControl"   : [name: "remoteControlEnabled", values: RV_BOOL]
    ]
}

void installed() { log.info "${device.displayName} installed" }
void updated() { log.info "${device.displayName} updated" }

void parseEntity(Map e) {
    rememberEntity((String) e.scope, (String) e.suffix, (String) e.entityId)
    noteEntityHealth(e)
    if (logEnable) log.debug "entity ${e.entityId} (${e.suffix}) = ${e.state}"
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, "")
        return
    }

    switch (e.suffix) {
        case "energy":
        case "energy_saved":
            if (!isNullState(e.state)) {
                emit(snakeToCamel((String) e.suffix), coerce(e.state), [unit: e.unit ?: "Wh"])
            }
            return
        case "water_consumption":
            if (!isNullState(e.state)) emit("waterConsumption", coerce(e.state), [unit: e.unit ?: "L"])
            return
        default:
            emitGeneric(e)
    }
}

void start() { haPress("start") }
void stop()  { haPress("stop") }
void pause() { haPress("pause") }

// --- settings -----------------------------------------------------------

void refresh() { haRefreshAll() }
