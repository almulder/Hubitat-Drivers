/**
 *  SAS - Samsung Dehumidifier
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/dehumidifier.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 1 binary_sensor, 1 number, 4 select, 6 sensor, 4 switch.
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
    definition(name: "SAS - Samsung Dehumidifier", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"

        attribute "autoCleanRunning",         "string"   // diagnostic
        attribute "targetHumidity",           "number"
        attribute "airFilterThreshold",       "string"
        attribute "operatingMode",            "string"
        attribute "watertankLightBrightness", "string"
        attribute "watertankLightColor",      "string"
        attribute "airFilterStatus",          "string"   // diagnostic
        attribute "airFilterUsage",           "number"   // diagnostic
        attribute "airFilterUsageHours",      "number"   // diagnostic
        attribute "autoCleanProgress",        "string"   // diagnostic
        attribute "humidity",                 "number"
        attribute "watertankFullAlarmStatus", "string"   // diagnostic
        attribute "autoClean",                "string"
        attribute "display",                  "string"
        attribute "muteOnce",                 "string"
        attribute "watertankLight",           "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "setAutoClean", [[name: "Auto clean*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDisplay", [[name: "Display*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setMuteOnce", [[name: "Mute once*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setWatertankLight", [[name: "Watertank light*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAirFilterThreshold", [[name: "Air filter threshold*", type: "STRING", description: "Name or raw code; the dropdown is under Preferences"]]
        command "setOperatingMode", [[name: "Operating mode*", type: "STRING", description: "Name or raw code; the dropdown is under Preferences"]]
        command "setWatertankLightBrightness", [[name: "Watertank light brightness*", type: "STRING", description: "Name or raw code; the dropdown is under Preferences"]]
        command "setWatertankLightColor", [[name: "Watertank light color*", type: "STRING", description: "See the 'Watertank Light Color' state variable for this appliance's options"]]
        command "setTargetHumidity", [[name: "Target humidity*", type: "NUMBER"]]
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

void setAutoClean(String v) { setSwitchOption("auto_clean", v) }
void setDisplay(String v) { setSwitchOption("display", v) }
void setMuteOnce(String v) { setSwitchOption("mute_once", v) }
void setWatertankLight(String v) { setSwitchOption("watertank_light", v) }
void setAirFilterThreshold(String v) { setSelectOption("air_filter_threshold", v) }
void setOperatingMode(String v) { setSelectOption("operating_mode", v) }
void setWatertankLightBrightness(String v) { setSelectOption("watertank_light_brightness", v) }
void setWatertankLightColor(String v) { setSelectOption("watertank_light_color", v) }
void setTargetHumidity(BigDecimal v) { haSetNumber("target_humidity", v) }

void refresh() { haRefreshAll() }
