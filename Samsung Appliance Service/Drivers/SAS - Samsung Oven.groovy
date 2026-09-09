/**
 *  SAS - Samsung Oven
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/oven.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 3 binary_sensor, 1 button, 2 number, 1 select, 7 sensor, 6 switch.
 *
 *  Nothing here is specific to one appliance model. Option names and option
 *  lists are resolved per install by the listener, so this driver should show
 *  the right cycles and names on whatever board it meets. An entity upstream
 *  adds later still appears, named from its suffix, via emitGeneric.
 *
 *  Version: 0.5.0
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Oven", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "ContactSensor"

        attribute "cloudConnected",       "string"   // diagnostic
        attribute "cycleActive",          "string"
        attribute "cookTime",             "number"
        attribute "ovenSetpoint",         "number"
        attribute "ovenMode",             "string"
        attribute "currentTempC",         "number"
        attribute "diagnosisStatus",      "string"   // diagnostic
        attribute "finishTime",           "number"
        attribute "machineState",         "string"
        attribute "operationTimeMinutes", "number"
        attribute "ovenState",            "string"
        attribute "progressPercentage",   "number"
        attribute "cooktopOnAlert",       "string"
        attribute "energySaving",         "string"
        attribute "fastPreheat",          "string"
        attribute "lamp",                 "string"
        attribute "naturalSteam",         "string"
        attribute "sound",                "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "diagnosisStart"
        command "setCooktopOnAlert", [[name: "Cooktop on alert*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setEnergySaving", [[name: "Energy saving*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setFastPreheat", [[name: "Fast preheat*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setLamp", [[name: "Lamp*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setNaturalSteam", [[name: "Natural steam*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setSound", [[name: "Sound*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setOvenMode", [[name: "Oven mode*", type: "STRING", description: "See the 'Oven Mode' state variable for this appliance's options"]]
        command "setCookTime", [[name: "Cook time*", type: "NUMBER"]]
        command "setOvenSetpoint", [[name: "Oven setpoint*", type: "NUMBER"]]
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
    if (logEnable) log.debug "entity ${e.entityId} (${e.suffix}, scope=${e.scope}) = ${e.state}"
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, e.scope == "main" ? "" : cap(e.scope as String))
        return
    }
    if (e.scope != "main") {
        emitGeneric(e, e.scope as String)
        return
    }

    switch (e.suffix) {
        case "door_open":
            emitOpenClosed("contact", e.state)
            return
        default:
            emitGeneric(e)
    }
}

private String cap(String s) { return s ? s[0].toUpperCase() + s.substring(1) : s }

// --- commands ---------------------------------------------------------------

void diagnosisStart() { haPress("diagnosis_start") }
void setCooktopOnAlert(String v) { setSwitchOption("cooktop_on_alert", v) }
void setEnergySaving(String v) { setSwitchOption("energy_saving", v) }
void setFastPreheat(String v) { setSwitchOption("fast_preheat", v) }
void setLamp(String v) { setSwitchOption("lamp", v) }
void setNaturalSteam(String v) { setSwitchOption("natural_steam", v) }
void setSound(String v) { setSwitchOption("sound", v) }
void setOvenMode(String v) { setSelectOption("oven_mode", v) }
void setCookTime(BigDecimal v) { haSetNumber("cook_time", v) }
void setOvenSetpoint(BigDecimal v) { haSetNumber("oven_setpoint", v) }

void refresh() { haRefreshAll() }
