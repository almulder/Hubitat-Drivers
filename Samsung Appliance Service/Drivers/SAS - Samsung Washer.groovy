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
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - setCycle, setWashTemperature, setSpinSpeed and setSelectOption, for any other select.
 *    - energy is in kWh, Hubitat's EnergyMeter unit; LocalThings' Wh is converted. energySaved too.
 *    - A subdevice entity lands on its own subdevice1-prefixed attribute instead of overwriting the main one.
 *    - setDelayStart, as on the dishwasher.
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
        command "setCycle", [[name: "Cycle*", type: "STRING", description: "See the 'Cycle' state variable for this appliance's options"]]
        command "setWashTemperature", [[name: "Wash temperature*", type: "STRING", description: "See the 'Wash Temperature' state variable for this appliance's options"]]
        command "setSpinSpeed", [[name: "Spin speed*", type: "STRING", description: "See the 'Spin Speed' state variable for this appliance's options"]]
        command "setDelayStart", [[name: "Hours*", type: "NUMBER"]]
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

void installed() { log.info "${device.displayName} installed"; commonInstalled() }
void updated() { log.info "${device.displayName} updated"; commonUpdated() }

void uninstalled() { commonUninstalled() }

void parseEntity(Map e) {
    beginEntity(e)

    // A subdevice reading gets its scope as a prefix, so it can never
    // overwrite the main attribute of the same name.
    String scope = scopePrefix(e)
    if (scope) {
        if (e.domain == "select") {
            rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
            emitSelect(e, scope)
        } else {
            emitGeneric(e, scope)
        }
        return
    }
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, "")
        return
    }

    switch (e.suffix) {
        case "energy":
        case "energy_saved":
            emitEnergy(snakeToCamel((String) e.suffix), e)
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

void setDelayStart(BigDecimal hours) { haSetNumber("delay_start", hours) }

void setCycle(String v)           { setSelectOption("cycle", v) }
void setWashTemperature(String v) { setSelectOption("wash_temperature", v) }
void setSpinSpeed(String v)       { setSelectOption("spin_speed", v) }

// --- settings -----------------------------------------------------------

void refresh() { haRefreshAll() }
