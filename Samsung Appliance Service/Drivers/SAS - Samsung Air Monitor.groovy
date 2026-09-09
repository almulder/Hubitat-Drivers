/**
 *  SAS - Samsung Air Monitor
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/air_monitor.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 1 binary_sensor, 4 sensor, 1 switch, 2 time.
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
    definition(name: "SAS - Samsung Air Monitor", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"

        attribute "batteryCharging",    "string"   // diagnostic
        attribute "airQualityStandard", "string"   // diagnostic
        attribute "battery",            "string"   // diagnostic
        attribute "co2",                "number"
        attribute "humidity",           "number"
        attribute "dnd",                "string"
        attribute "dndEnd",             "string"
        attribute "dndStart",           "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "setDnd", [[name: "Dnd*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDndEnd", [[name: "Dnd end* (HH:MM:SS)", type: "STRING"]]
        command "setDndStart", [[name: "Dnd start* (HH:MM:SS)", type: "STRING"]]
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

void setDnd(String v) { setSwitchOption("dnd", v) }
void setDndEnd(String v) { haSetTime("dnd_end", v) }
void setDndStart(String v) { haSetTime("dnd_start", v) }

void refresh() { haRefreshAll() }
