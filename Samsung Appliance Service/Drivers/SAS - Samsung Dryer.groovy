/**
 *  SAS - Samsung Dryer
 *
 *  Child of Samsung Appliance Listener. Verified against DA_WM_TP1_21_COMMON
 *  (24 entities). Shares a board family with the washer -- the listener
 *  disambiguates on device name, not board family.
 *
 *  Version: 0.5.0
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Dryer", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "EnergyMeter"
        capability "Refresh"

        attribute "machineState",    "string"
        attribute "running",         "string"
        attribute "power",           "string"
        attribute "progress",        "string"
        attribute "progressPercent", "number"
        attribute "completionTime",  "number"
        attribute "estimatedFinish", "string"
        attribute "cycle",           "string"
        attribute "dryLevel",        "string"
        attribute "delayStart",      "number"
        attribute "childLock",       "string"
        attribute "smartControl",    "string"
        attribute "wrinklePrevent",  "string"
        attribute "dryerType",       "string"
        attribute "buzzerSound",     "string"

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "start"
        command "stop"
        command "pause"
        command "setCycle", [[name: "Cycle*", type: "STRING", description: "See the 'Cycle' state variable for this appliance's options"]]
        command "setDryLevel", [[name: "Dry level*", type: "STRING", description: "See the 'Dry Level' state variable for this appliance's options"]]
        command "setDelayStart", [[name: "Hours*",     type: "NUMBER"]]
        command "wrinklePreventOn"
        command "wrinklePreventOff"
        command "setBuzzerSound", [[name: "Buzzer*", type: "STRING", description: "See the 'Buzzer Sound' state variable for this appliance's options"]]
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

void setCycle(String v)    { setSelectOption("cycle", v) }
void setDryLevel(String v) { setSelectOption("dry_level", v) }

void setDelayStart(BigDecimal hours) { haSetNumber("delay_start", hours) }

void wrinklePreventOn()  { haTurnOn("wrinkle_prevent") }
void wrinklePreventOff() { haTurnOff("wrinkle_prevent") }


// --- settings -----------------------------------------------------------

void setBuzzerSound(String v) { setSelectOption("buzzer_sound", v) }

void refresh() { haRefreshAll() }
