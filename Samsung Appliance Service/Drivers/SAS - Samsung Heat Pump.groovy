/**
 *  SAS - Samsung Heat Pump
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/ehs.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 1 number, 1 select, 1 sensor, 3 switch, 1 water_heater.
 *
 *  Nothing here is specific to one appliance model. Option names and option
 *  lists are resolved per install by the listener, so this driver should show
 *  the right cycles and names on whatever board it meets. An entity upstream
 *  adds later still appears, named from its suffix, via emitGeneric.
 *
 *  NOT YET SUPPORTED: this type also exposes water_heater entities (water_heater).
 *  Their state is reported as a plain attribute, but setting them needs
 *  climate/water_heater service helpers the library does not have yet.
 *
 *  Version: 0.5.0
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Heat Pump", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "TemperatureMeasurement"

        attribute "zoneTargetTemperature", "number"
        attribute "zoneMode",              "string"
        attribute "awayMode",              "string"
        attribute "muteOnce",              "string"
        attribute "zonePower",             "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "setAwayMode", [[name: "Away mode*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setMuteOnce", [[name: "Mute once*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setZonePower", [[name: "Zone power*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setZoneMode", [[name: "Zone mode*", type: "STRING", description: "Name or raw code; the dropdown is under Preferences"]]
        command "setZoneTargetTemperature", [[name: "Zone target temperature*", type: "NUMBER"]]
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
        case "zone_temperature":
            if (isNullState(e.state)) return
            emit("temperature", coerce(e.state), [unit: "°${location.temperatureScale}"])
            return
        default:
            emitGeneric(e)
    }
}

private String cap(String s) { return s ? s[0].toUpperCase() + s.substring(1) : s }

// --- commands ---------------------------------------------------------------

void setAwayMode(String v) { setSwitchOption("away_mode", v) }
void setMuteOnce(String v) { setSwitchOption("mute_once", v) }
void setZonePower(String v) { setSwitchOption("zone_power", v) }
void setZoneMode(String v) { setSelectOption("zone_mode", v) }
void setZoneTargetTemperature(BigDecimal v) { haSetNumber("zone_target_temperature", v) }

void refresh() { haRefreshAll() }
