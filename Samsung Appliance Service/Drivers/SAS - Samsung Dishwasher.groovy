/**
 *  SAS - Samsung Dishwasher
 *
 *  Child of Samsung Appliance Listener. One Hubitat device for the whole
 *  appliance. Verified against DA_DW_A51_20_COMMON (25 entities).
 *
 *  Version: 0.5.0
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Dishwasher", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "EnergyMeter"
        capability "Refresh"

        attribute "machineState",     "string"
        attribute "running",          "string"
        attribute "progress",         "string"
        attribute "progressPercent",  "number"
        attribute "completionTime",   "number"
        attribute "estimatedFinish",  "string"
        attribute "cycle",            "string"
        attribute "delayStart",       "number"
        attribute "childLock",        "string"
        attribute "sanitize",         "string"
        attribute "stormWash",        "string"
        attribute "autoReleaseDry",   "string"
        attribute "smartControl",     "string"
        attribute "filterStatus",     "string"
        attribute "filterUsage",      "string"
        attribute "drumCleanDueIn",   "number"

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "start"
        command "stop"
        command "pause"
        command "setCycle", [[name: "Cycle*", type: "STRING", description: "See the 'Cycle' state variable for this appliance's options"]]
        command "setDelayStart", [[name: "Hours*", type: "NUMBER"]]
        command "sanitizeOn"
        command "sanitizeOff"
        command "stormWashOn"
        command "stormWashOff"
        command "setAutoReleaseDry", [[name: "Auto release dry*", type: "ENUM", constraints: ["Off", "On"]]]
        command "startDiagnosis"
        command "setSelectOption", [[name: "Entity suffix*", type: "STRING"],
                                    [name: "Option*", type: "STRING"]]
    }

    preferences {
        input name: "exposeDiagnostics", type: "bool", defaultValue: false,
              title: "Expose diagnostic entities"
        input name: "logEnable", type: "bool", defaultValue: true,  title: "Debug logging"
        input name: "txtEnable", type: "bool", defaultValue: true,  title: "Description text logging"
    }
}

#include almulder.SAS-Samsung-Appliance-Common

void installed() { log.info "${device.displayName} installed" }
void updated() { log.info "${device.displayName} updated" }

// ---------------------------------------------------------------------------
// Inbound
// ---------------------------------------------------------------------------

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
        case "power":
            emitOnOff("switch", e.state)
            return
        case "energy":
            if (!isNullState(e.state)) emit("energy", coerce(e.state), [unit: e.unit ?: "Wh"])
            return
        default:
            emitGeneric(e)
    }
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

void on()    { haTurnOn("power") }
void off()   { haTurnOff("power") }

void start() { haPress("start") }
void stop()  { haPress("stop") }
void pause() { haPress("pause") }

void setCycle(String cycle) { setSelectOption("cycle", cycle) }

void setDelayStart(BigDecimal hours) { haSetNumber("delay_start", hours) }

void sanitizeOn()   { haTurnOn("sanitize") }
void sanitizeOff()  { haTurnOff("sanitize") }
void stormWashOn()  { haTurnOn("storm_wash") }
void stormWashOff() { haTurnOff("storm_wash") }

// --- settings -----------------------------------------------------------

void setAutoReleaseDry(String v) { setSwitchOption("auto_release_dry", v) }

/** Diagnostic: only visible as an attribute when diagnostics are enabled. */
void startDiagnosis() { haPress("start_diagnosis") }

void refresh() { haRefreshAll() }
