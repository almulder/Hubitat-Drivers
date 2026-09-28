/**
 *  SAS - Samsung Dryer
 *
 *  Child of Samsung Appliance Listener. Verified against DA_WM_TP1_21_COMMON
 *  (24 entities). Shares a board family with the washer -- the listener
 *  disambiguates on device name, not board family.
 *
 *  Attribute names follow the HubiThings Replica Samsung Dryer driver so
 *  Rule Machine rules carry over; see attrRenames().
 *
 *  Version: 0.6.1
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Dryer", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "EnergyMeter"
        capability "Refresh"

        // HubiThings Replica names (Samsung Dryer)
        attribute "switch",               "string"
        attribute "machineState",         "string"
        attribute "dryerJobState",        "string"
        attribute "completionTime",       "string"
        attribute "timeRemaining",        "string"
        attribute "dryerDryLevel",        "string"
        attribute "dryerWrinklePrevent",  "string"
        attribute "lockState",            "string"
        attribute "remoteControlEnabled", "string"

        // No Replica equivalent
        attribute "running",         "string"
        attribute "progressPercent", "number"
        attribute "cycle",           "string"
        attribute "delayStart",      "number"
        attribute "dryerType",       "string"
        attribute "buzzerSound",     "string"

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
        "progress"       : [name: "dryerJobState", values: RV_DRYER_JOB],
        "estimatedFinish": [name: "completionTime", format: "isoZ"],
        "completionTime" : [name: "timeRemaining", format: "hms"],
        "dryLevel"       : "dryerDryLevel",
        "wrinklePrevent" : "dryerWrinklePrevent",
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
            if (!isNullState(e.state)) emit("energy", coerce(e.state), [unit: e.unit ?: "Wh"])
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
