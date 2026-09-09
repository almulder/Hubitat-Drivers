/**
 *  SAS - Samsung Vacuum Station
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/vacuum_station.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 3 binary_sensor, 1 select, 10 sensor, 3 switch.
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
    definition(name: "SAS - Samsung Vacuum Station", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"

        attribute "batteryCharging",       "string"
        attribute "dustbagFull",           "string"
        attribute "stickBleConnected",     "string"
        attribute "dischargingTime",       "string"
        attribute "battery",               "string"
        attribute "cleanstationStatus",    "string"   // diagnostic
        attribute "dustbagUsage",          "number"   // diagnostic
        attribute "stickCleaningStatus",   "string"
        attribute "stickOperationMode",    "string"
        attribute "stickStatus",           "string"   // diagnostic
        attribute "uvcEmittedTime",        "number"   // diagnostic
        attribute "uvcFinishedTime",       "number"   // diagnostic
        attribute "uvcOperationTime",      "number"   // diagnostic
        attribute "uvcTotalOperationTime", "number"   // diagnostic
        attribute "autoEmpty",             "string"
        attribute "dustbinAutoClose",      "string"
        attribute "uvcIntensiveMode",      "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "setAutoEmpty", [[name: "Auto empty*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDustbinAutoClose", [[name: "Dustbin auto close*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setUvcIntensiveMode", [[name: "Uvc intensive mode*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDischargingTime", [[name: "Discharging time*", type: "STRING", description: "See the 'Discharging Time' state variable for this appliance's options"]]
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

void setAutoEmpty(String v) { setSwitchOption("auto_empty", v) }
void setDustbinAutoClose(String v) { setSwitchOption("dustbin_auto_close", v) }
void setUvcIntensiveMode(String v) { setSwitchOption("uvc_intensive_mode", v) }
void setDischargingTime(String v) { setSelectOption("discharging_time", v) }

void refresh() { haRefreshAll() }
