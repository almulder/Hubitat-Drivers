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
 *  Version: 0.6.0
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
        command "setCycle", [[name: "Cycle*", type: "STRING", description: "See the 'Cycle' state variable for this appliance's options"]]
        command "setWasherSpinLevel", [[name: "Spin speed*", type: "STRING", description: "See the 'Spin Speed' state variable for this appliance's options"]]
        command "setWasherWaterTemperature", [[name: "Temperature*", type: "STRING", description: "See the 'Wash Temperature' state variable for this appliance's options"]]
        command "setDelayStart",      [[name: "Hours*", type: "NUMBER"]]
        command "setBuzzerSound", [[name: "Buzzer*", type: "STRING", description: "See the 'Buzzer Sound' state variable for this appliance's options"]]
        command "setDetergentQuantity", [[name: "Amount*", type: "STRING", description: "See the 'Detergent Quantity' state variable for this appliance's options"]]
        command "setDetergentWaterHardness", [[name: "Hardness*", type: "STRING", description: "See the 'Detergent Water Hardness' state variable for this appliance's options"]]
        command "setSoftenerQuantity", [[name: "Amount*", type: "STRING", description: "See the 'Softener Quantity' state variable for this appliance's options"]]
        command "setSoftenerConcentration", [[name: "Concentration*", type: "STRING", description: "See the 'Softener Concentration' state variable for this appliance's options"]]
        command "startDiagnosis"
        command "setSelectOption", [[name: "Entity suffix*", type: "STRING"],
                                    [name: "Option*", type: "STRING"]]
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

void setCycle(String v)           { setSelectOption("cycle", v) }
void setWasherSpinLevel(String v)        { setSelectOption("spin_speed", v) }
void setWasherWaterTemperature(String v) { setSelectOption("wash_temperature", v) }

void setDelayStart(BigDecimal hours) { haSetNumber("delay_start", hours) }


// --- settings -----------------------------------------------------------

void setBuzzerSound(String v) { setSelectOption("buzzer_sound", v) }
void setDetergentQuantity(String v) { setSelectOption("detergent_quantity", v) }
void setDetergentWaterHardness(String v) { setSelectOption("detergent_water_hardness", v) }
void setSoftenerQuantity(String v) { setSelectOption("softener_quantity", v) }
void setSoftenerConcentration(String v) { setSelectOption("softener_concentration", v) }

/** Diagnostic: only visible as an attribute when diagnostics are enabled. */
void startDiagnosis() { haPress("start_diagnosis") }

void refresh() { haRefreshAll() }
