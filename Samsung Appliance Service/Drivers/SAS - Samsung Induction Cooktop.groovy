/**
 *  SAS - Samsung Induction Cooktop
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/induction_cooktop.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 6 binary_sensor, 7 sensor, 1 switch.
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
    definition(name: "SAS - Samsung Induction Cooktop", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "TemperatureMeasurement"

        attribute "cooktopPower",                "string"
        attribute "cooktopSafetyShutoffEnabled", "string"   // diagnostic
        attribute "pairedHoodConnected",         "string"
        attribute "pairedHoodLight",             "string"
        attribute "pairedHoodPower",             "string"
        attribute "probeConnected",              "string"
        attribute "cooktopState",                "string"
        attribute "pairedHoodFanSpeed",          "number"
        attribute "pairedHoodFirmware",          "string"   // diagnostic
        attribute "pairedHoodModel",             "string"   // diagnostic
        attribute "probeBattery",                "string"   // diagnostic
        attribute "probeTemperature",            "number"
        attribute "cooktopChildLock",            "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "setCooktopChildLock", [[name: "Cooktop child lock*", type: "ENUM", constraints: ["Off", "On"]]]
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
        case "probe_target_temperature":
            if (isNullState(e.state)) return
            emit("temperature", coerce(e.state), [unit: "°${location.temperatureScale}"])
            return
        default:
            emitGeneric(e)
    }
}

private String cap(String s) { return s ? s[0].toUpperCase() + s.substring(1) : s }

// --- commands ---------------------------------------------------------------

void setCooktopChildLock(String v) { setSwitchOption("cooktop_child_lock", v) }

void refresh() { haRefreshAll() }
