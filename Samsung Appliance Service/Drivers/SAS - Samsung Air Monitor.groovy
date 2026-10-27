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
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - Subdevice selects are named like every other subdevice reading,
 *      e.g. subdevice1CookingMode, no longer Subdevice1CookingMode.
 *    - Battery, RelativeHumidityMeasurement and CarbonDioxideMeasurement; battery is a number and shows even with diagnostics hidden.
 */

metadata {
    definition(name: "SAS - Samsung Air Monitor", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Battery"
        capability "RelativeHumidityMeasurement"
        capability "CarbonDioxideMeasurement"

        attribute "batteryCharging",    "string"   // diagnostic
        attribute "airQualityStandard", "string"   // diagnostic
        attribute "co2",                "number"
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
        case "battery":
            emitBattery(e)
            return
        case "co2":
            emitCarbonDioxide(e)
            return
        default:
            emitGeneric(e)
    }
}

// --- commands ---------------------------------------------------------------

void setDnd(String v) { setSwitchOption("dnd", v) }
void setDndEnd(String v) { haSetTime("dnd_end", v) }
void setDndStart(String v) { haSetTime("dnd_start", v) }

void refresh() { haRefreshAll() }
