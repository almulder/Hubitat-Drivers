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
 *  NOT YET SUPPORTED: setting the water_heater entity. Its operation mode
 *  is reported as waterHeaterMode.
 *
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - Subdevice selects are named like every other subdevice reading,
 *      e.g. subdevice1CookingMode, no longer Subdevice1CookingMode.
 *    - Temperatures are converted to the hub's scale, and setZoneTargetTemperature converts back.
 *    - The device-named water_heater reports as waterHeaterMode, not a raw water_heater attribute.
 *    - Select command descriptions point at the state variable listing the options.
 *    - Setpoints are refused, not sent unconverted, until the appliance has reported its unit.
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
        attribute "waterHeaterMode",       "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "setAwayMode", [[name: "Away mode*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setMuteOnce", [[name: "Mute once*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setZonePower", [[name: "Zone power*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setZoneMode", [[name: "Zone mode*", type: "STRING", description: "See the 'Zone Mode' state variable for this appliance's options"]]
        command "setZoneTargetTemperature", [[name: "Zone target temperature*", type: "NUMBER"]]
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

    // The water heater named for the device: its state is the operation mode.
    if (e.domain == "water_heater" && !e.suffix) {
        if (!isNullState(e.state)) emit("waterHeaterMode", e.state.toString())
        return
    }

    switch (e.suffix) {
        case "zone_temperature":
            emitTemperature("temperature", e)
            return
        default:
            emitGeneric(e)
    }
}

// --- commands ---------------------------------------------------------------

void setAwayMode(String v) { setSwitchOption("away_mode", v) }
void setMuteOnce(String v) { setSwitchOption("mute_once", v) }
void setZonePower(String v) { setSwitchOption("zone_power", v) }
void setZoneMode(String v) { setSelectOption("zone_mode", v) }
void setZoneTargetTemperature(BigDecimal v) { haSetTemperature("zone_target_temperature", v) }

void refresh() { haRefreshAll() }
