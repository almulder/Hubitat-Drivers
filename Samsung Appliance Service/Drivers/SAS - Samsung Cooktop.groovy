/**
 *  SAS - Samsung Cooktop
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/cooktop.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 8 binary_sensor, 8 sensor.
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
    definition(name: "SAS - Samsung Cooktop", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"

        attribute "anyBurnerActive",     "string"
        attribute "childLock",           "string"
        attribute "cloudConnected",      "string"   // diagnostic
        attribute "firmwareUpdate",      "string"   // diagnostic
        attribute "pairedHoodConnected", "string"
        attribute "pairedHoodLight",     "string"
        attribute "pairedHoodPower",     "string"
        attribute "powerState",          "string"
        attribute "alarmCode",           "string"   // diagnostic
        attribute "energyKwh",           "number"
        attribute "mainTimerCurrent",    "number"
        attribute "mainTimerState",      "number"
        attribute "pairedHoodFanSpeed",  "number"
        attribute "pairedHoodFirmware",  "string"   // diagnostic
        attribute "pairedHoodModel",     "string"   // diagnostic
        attribute "usageRuntimeHours",   "number"   // diagnostic
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

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



void refresh() { haRefreshAll() }
