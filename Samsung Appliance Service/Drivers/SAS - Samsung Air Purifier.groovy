/**
 *  SAS - Samsung Air Purifier
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/air_purifier.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 1 binary_sensor, 1 button, 3 fan, 2 number, 6 select, 14 sensor, 9 switch, 2 time.
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
    definition(name: "SAS - Samsung Air Purifier", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"

        attribute "deviceActive",                 "string"   // diagnostic
        attribute "airflowFan",                   "string"
        attribute "fan",                          "string"
        attribute "windStrengthFan",              "string"
        attribute "sensingInterval",              "number"
        attribute "soundVolume",                  "number"
        attribute "boosterFanMode",               "string"
        attribute "boosterLightBrightness",       "string"
        attribute "boosterLightColorTemperature", "string"
        attribute "boosterOscillationAngle",      "string"
        attribute "sensingMode",                  "string"
        attribute "soundMode",                    "string"
        attribute "airSensingState",              "string"   // diagnostic
        attribute "boosterAngleLocation",         "string"   // diagnostic
        attribute "co2",                          "number"
        attribute "diagnosisStatus",              "string"   // diagnostic
        attribute "fanDirection",                 "string"   // diagnostic
        attribute "fanSpeedLevel",                "number"   // diagnostic
        attribute "filterProgress",               "string"   // diagnostic
        attribute "hepaFilterStatus",             "string"   // diagnostic
        attribute "hepaFilterUsage",              "number"   // diagnostic
        attribute "lastAirSensingLevel",          "number"   // diagnostic
        attribute "lastAirSensingTime",           "number"   // diagnostic
        attribute "operatingMode",                "string"   // diagnostic
        attribute "panelStatus",                  "string"   // diagnostic
        attribute "soundOutput",                  "string"   // diagnostic
        attribute "boosterLight",                 "string"
        attribute "boosterLightManualBrightness", "string"
        attribute "boosterOscillation",           "string"
        attribute "display",                      "string"
        attribute "displayLight",                 "string"
        attribute "muteOnce",                     "string"
        attribute "periodicAirSensing",           "string"
        attribute "periodicSensingSkipStatus",    "string"
        attribute "petFilterActivation",          "string"
        attribute "sensingSkipEnd",               "string"
        attribute "sensingSkipStart",             "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "diagnosisStart"
        command "setBoosterLight", [[name: "Booster light*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setBoosterLightManualBrightness", [[name: "Booster light manual brightness*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setBoosterOscillation", [[name: "Booster oscillation*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDisplay", [[name: "Display*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDisplayLight", [[name: "Display light*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setMuteOnce", [[name: "Mute once*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setPeriodicAirSensing", [[name: "Periodic air sensing*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setPeriodicSensingSkipStatus", [[name: "Periodic sensing skip status*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setPetFilterActivation", [[name: "Pet filter activation*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAirflowFan", [[name: "Airflow fan*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setFan", [[name: "Fan*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setWindStrengthFan", [[name: "Wind strength fan*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setBoosterFanMode", [[name: "Booster fan mode*", type: "STRING", description: "See the 'Booster Fan Mode' state variable for this appliance's options"]]
        command "setBoosterLightBrightness", [[name: "Booster light brightness*", type: "STRING", description: "See the 'Booster Light Brightness' state variable for this appliance's options"]]
        command "setBoosterLightColorTemperature", [[name: "Booster light color temperature*", type: "STRING", description: "See the 'Booster Light Color Temperature' state variable for this appliance's options"]]
        command "setBoosterOscillationAngle", [[name: "Booster oscillation angle*", type: "STRING", description: "See the 'Booster Oscillation Angle' state variable for this appliance's options"]]
        command "setSensingMode", [[name: "Sensing mode*", type: "STRING", description: "See the 'Sensing Mode' state variable for this appliance's options"]]
        command "setSoundMode", [[name: "Sound mode*", type: "STRING", description: "See the 'Sound Mode' state variable for this appliance's options"]]
        command "setSensingInterval", [[name: "Sensing interval*", type: "NUMBER"]]
        command "setSoundVolume", [[name: "Sound volume*", type: "NUMBER"]]
        command "setSensingSkipEnd", [[name: "Sensing skip end* (HH:MM:SS)", type: "STRING"]]
        command "setSensingSkipStart", [[name: "Sensing skip start* (HH:MM:SS)", type: "STRING"]]
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

void diagnosisStart() { haPress("diagnosis_start") }
void setBoosterLight(String v) { setSwitchOption("booster_light", v) }
void setBoosterLightManualBrightness(String v) { setSwitchOption("booster_light_manual_brightness", v) }
void setBoosterOscillation(String v) { setSwitchOption("booster_oscillation", v) }
void setDisplay(String v) { setSwitchOption("display", v) }
void setDisplayLight(String v) { setSwitchOption("display_light", v) }
void setMuteOnce(String v) { setSwitchOption("mute_once", v) }
void setPeriodicAirSensing(String v) { setSwitchOption("periodic_air_sensing", v) }
void setPeriodicSensingSkipStatus(String v) { setSwitchOption("periodic_sensing_skip_status", v) }
void setPetFilterActivation(String v) { setSwitchOption("pet_filter_activation", v) }
void setAirflowFan(String v) { if (onOff(v) == "on") haService("fan", "turn_on", "airflow_fan") else haService("fan", "turn_off", "airflow_fan") }
void setFan(String v) { if (onOff(v) == "on") haService("fan", "turn_on", "fan") else haService("fan", "turn_off", "fan") }
void setWindStrengthFan(String v) { if (onOff(v) == "on") haService("fan", "turn_on", "wind_strength_fan") else haService("fan", "turn_off", "wind_strength_fan") }
void setBoosterFanMode(String v) { setSelectOption("booster_fan_mode", v) }
void setBoosterLightBrightness(String v) { setSelectOption("booster_light_brightness", v) }
void setBoosterLightColorTemperature(String v) { setSelectOption("booster_light_color_temperature", v) }
void setBoosterOscillationAngle(String v) { setSelectOption("booster_oscillation_angle", v) }
void setSensingMode(String v) { setSelectOption("sensing_mode", v) }
void setSoundMode(String v) { setSelectOption("sound_mode", v) }
void setSensingInterval(BigDecimal v) { haSetNumber("sensing_interval", v) }
void setSoundVolume(BigDecimal v) { haSetNumber("sound_volume", v) }
void setSensingSkipEnd(String v) { haSetTime("sensing_skip_end", v) }
void setSensingSkipStart(String v) { haSetTime("sensing_skip_start", v) }

void refresh() { haRefreshAll() }
