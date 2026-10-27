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
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - Subdevice selects are named like every other subdevice reading,
 *      e.g. subdevice1CookingMode, no longer Subdevice1CookingMode.
 *    - temperature is the probe's reading; it was the probe's target, now probeTargetTemperature.
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
        attribute "probeTargetTemperature",      "number"
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
        input name: "logEnable", type: "bool", defaultValue: false, title: "Debug logging",
              description: "Turns itself off after 30 minutes"
        input name: "txtEnable", type: "bool", defaultValue: true, title: "Description text logging"
    }
}

#include almulder.SAS-Samsung-Appliance-Common

/** Library hook: no HubiThings Replica driver to match for this type. */
Map attrRenames() { return [:] }

void installed() { log.info "${device.displayName} installed"; commonInstalled() }

void updated() { log.info "${device.displayName} updated"; commonUpdated() }

void uninstalled() { commonUninstalled() }

void parseEntity(Map e) {
    beginEntity(e)
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, scopePrefix(e))
        return
    }
    if (e.scope != "main") {
        emitGeneric(e, scopePrefix(e))
        return
    }

    switch (e.suffix) {
        // temperature is the probe's reading, not its target.
        case "probe_temperature":
            emitGeneric(e)
            emitTemperature("temperature", e)
            return
        default:
            emitGeneric(e)
    }
}

// --- commands ---------------------------------------------------------------

void setCooktopChildLock(String v) { setSwitchOption("cooktop_child_lock", v) }

void refresh() { haRefreshAll() }
