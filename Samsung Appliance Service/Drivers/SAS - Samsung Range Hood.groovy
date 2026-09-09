/**
 *  SAS - Samsung Range Hood
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/range_hood.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 6 binary_sensor, 1 button, 1 fan, 1 select, 20 sensor, 1 switch.
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
    definition(name: "SAS - Samsung Range Hood", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"

        attribute "afterRunActive",            "string"
        attribute "automaticOperation",        "string"   // diagnostic
        attribute "firmwareUpdate",            "string"   // diagnostic
        attribute "frontVentOpen",             "string"   // diagnostic
        attribute "greaseFilterAlarm",         "string"   // diagnostic
        attribute "periodicAirSensing",        "string"   // diagnostic
        attribute "fan",                       "string"
        attribute "lampBrightness",            "string"
        attribute "afterRunProgress",          "string"
        attribute "airSensingState",           "string"   // diagnostic
        attribute "alarmCode",                 "string"   // diagnostic
        attribute "autoVentilationAction",     "string"
        attribute "automaticVentilationState", "string"   // diagnostic
        attribute "cleanLevel",                "number"
        attribute "energyKwh",                 "number"
        attribute "energyLastMonthKwh",        "number"
        attribute "energySavedKwh",            "number"
        attribute "energyThisMonthKwh",        "number"
        attribute "hoodFanSpeed",              "number"
        attribute "hoodFilterCapacity",        "number"   // diagnostic
        attribute "hoodFilterStatus",          "string"   // diagnostic
        attribute "hoodFilterUsage",           "number"   // diagnostic
        attribute "hoodLamp",                  "string"
        attribute "lastAirSensingLevel",       "number"   // diagnostic
        attribute "lastAirSensingTime",        "number"   // diagnostic
        attribute "powerEnergyKwh",            "number"
        attribute "powerWatts",                "number"
        attribute "usageRuntimeHours",         "number"   // diagnostic
        attribute "lamp",                      "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "afterRunCancel"
        command "setLamp", [[name: "Lamp*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setFan", [[name: "Fan*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setLampBrightness", [[name: "Lamp brightness*", type: "STRING", description: "See the 'Lamp Brightness' state variable for this appliance's options"]]
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
        // no suffix needs special handling on this type
        default:
            emitGeneric(e)
    }
}

private String cap(String s) { return s ? s[0].toUpperCase() + s.substring(1) : s }

// --- commands ---------------------------------------------------------------

void afterRunCancel() { haPress("after_run_cancel") }
void setLamp(String v) { setSwitchOption("lamp", v) }
void setFan(String v) { if (onOff(v) == "on") haService("fan", "turn_on", "fan") else haService("fan", "turn_off", "fan") }
void setLampBrightness(String v) { setSelectOption("lamp_brightness", v) }

void refresh() { haRefreshAll() }
