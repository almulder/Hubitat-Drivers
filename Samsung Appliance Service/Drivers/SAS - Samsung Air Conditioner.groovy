/**
 *  SAS - Samsung Air Conditioner
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/airconditioner.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 5 binary_sensor, 3 button, 1 climate, 4 number, 10 select, 34 sensor, 20 switch, 2 time.
 *
 *  Nothing here is specific to one appliance model. Option names and option
 *  lists are resolved per install by the listener, so this driver should show
 *  the right cycles and names on whatever board it meets. An entity upstream
 *  adds later still appears, named from its suffix, via emitGeneric.
 *
 *  NOT YET SUPPORTED: this type also exposes climate entities (climate).
 *  Their state is reported as a plain attribute, but setting them needs
 *  climate/water_heater service helpers the library does not have yet.
 *
 *  Version: 0.5.0
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Air Conditioner", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "TemperatureMeasurement"

        attribute "autoCleanRunning",            "string"   // diagnostic
        attribute "currentLimitEnabled",         "string"   // diagnostic
        attribute "deviceActive",                "string"   // diagnostic
        attribute "odorControllerActive",        "string"   // diagnostic
        attribute "overloadProtectionActive",    "string"   // diagnostic
        attribute "goodSleep",                   "number"
        attribute "sensingInterval",             "number"
        attribute "soundVolume",                 "number"
        attribute "tropicalNightMode",           "number"
        attribute "airFilterPm1Threshold",       "string"
        attribute "airFilterThreshold",          "string"
        attribute "edgeLightingColor",           "string"
        attribute "edgeLightingMode",            "string"
        attribute "energySavingMode",            "string"
        attribute "filterAlarmTime",             "string"
        attribute "indicatorLightMode",          "string"
        attribute "sensingMode",                 "string"
        attribute "soundMode",                   "string"
        attribute "ventilationMode",             "string"
        attribute "absencePowerSavingMode",      "number"   // diagnostic
        attribute "airFilterPm1Status",          "number"   // diagnostic
        attribute "airFilterPm1Usage",           "number"   // diagnostic
        attribute "airFilterPm1UsageHours",      "number"   // diagnostic
        attribute "airFilterStatus",             "string"   // diagnostic
        attribute "airFilterUsage",              "number"   // diagnostic
        attribute "airFilterUsageHours",         "number"   // diagnostic
        attribute "airSensingState",             "string"   // diagnostic
        attribute "autoCleanProgress",           "string"   // diagnostic
        attribute "autoCleanProgressLegacy",     "string"   // diagnostic
        attribute "cleanLevel",                  "number"   // diagnostic
        attribute "co2",                         "number"   // diagnostic
        attribute "currentLimitLevel",           "number"   // diagnostic
        attribute "currentTemperatureC",         "number"
        attribute "diagnosisStatus",             "string"   // diagnostic
        attribute "energyKwh",                   "number"
        attribute "energyLastMonthKwh",          "number"
        attribute "energySavedKwh",              "number"
        attribute "energySavingOperatingStatus", "number"   // diagnostic
        attribute "energySavingState",           "number"   // diagnostic
        attribute "energyThisMonthKwh",          "number"
        attribute "filterTime",                  "number"
        attribute "hepaFilterStatus",            "string"   // diagnostic
        attribute "hepaFilterUsage",             "number"   // diagnostic
        attribute "humidity",                    "number"
        attribute "lastAirSensingLevel",         "number"   // diagnostic
        attribute "lastAirSensingTime",          "number"   // diagnostic
        attribute "motionDetectWindMode",        "string"   // diagnostic
        attribute "odorControllerProgress",      "string"   // diagnostic
        attribute "overloadProtectionMode",      "string"   // diagnostic
        attribute "powerEnergyKwh",              "number"
        attribute "powerWatts",                  "number"
        attribute "soundOutput",                 "string"   // diagnostic
        attribute "absenceClean",                "string"
        attribute "absencePowerSavingActive",    "string"
        attribute "airMonitoring",               "string"
        attribute "airPurify",                   "string"
        attribute "autoClean",                   "string"
        attribute "autoCleanLegacy",             "string"
        attribute "beep",                        "string"
        attribute "display",                     "string"
        attribute "displayLight",                "string"
        attribute "edgeLighting",                "string"
        attribute "indicatorLight",              "string"
        attribute "motionDetectWindActive",      "string"
        attribute "muteOnce",                    "string"
        attribute "periodicAirSensing",          "string"
        attribute "periodicSensingSkipStatus",   "string"
        attribute "spi",                         "string"
        attribute "uvLed",                       "string"
        attribute "ventilationAlarm",            "string"
        attribute "windfree",                    "string"
        attribute "windsleep",                   "string"
        attribute "sensingSkipEnd",              "string"
        attribute "sensingSkipStart",            "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "autoCleanStop"
        command "diagnosisStart"
        command "filterTimeReset"
        command "setAbsenceClean", [[name: "Absence clean*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAbsencePowerSavingActive", [[name: "Absence power saving active*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAirMonitoring", [[name: "Air monitoring*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAirPurify", [[name: "Air purify*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAutoClean", [[name: "Auto clean*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAutoCleanLegacy", [[name: "Auto clean legacy*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setBeep", [[name: "Beep*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDisplay", [[name: "Display*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDisplayLight", [[name: "Display light*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setEdgeLighting", [[name: "Edge lighting*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setIndicatorLight", [[name: "Indicator light*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setMotionDetectWindActive", [[name: "Motion detect wind active*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setMuteOnce", [[name: "Mute once*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setPeriodicAirSensing", [[name: "Periodic air sensing*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setPeriodicSensingSkipStatus", [[name: "Periodic sensing skip status*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setSpi", [[name: "Spi*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setUvLed", [[name: "Uv led*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setVentilationAlarm", [[name: "Ventilation alarm*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setWindfree", [[name: "Windfree*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setWindsleep", [[name: "Windsleep*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAirFilterPm1Threshold", [[name: "Air filter pm1 threshold*", type: "STRING", description: "Name or raw code; the dropdown is under Preferences"]]
        command "setAirFilterThreshold", [[name: "Air filter threshold*", type: "STRING", description: "Name or raw code; the dropdown is under Preferences"]]
        command "setEdgeLightingColor", [[name: "Edge lighting color*", type: "STRING", description: "See the 'Edge Lighting Color' state variable for this appliance's options"]]
        command "setEdgeLightingMode", [[name: "Edge lighting mode*", type: "STRING", description: "See the 'Edge Lighting Mode' state variable for this appliance's options"]]
        command "setEnergySavingMode", [[name: "Energy saving mode*", type: "STRING", description: "Name or raw code; the dropdown is under Preferences"]]
        command "setFilterAlarmTime", [[name: "Filter alarm time*", type: "STRING", description: "See the 'Filter Alarm Time' state variable for this appliance's options"]]
        command "setIndicatorLightMode", [[name: "Indicator light mode*", type: "STRING", description: "See the 'Indicator Light Mode' state variable for this appliance's options"]]
        command "setSensingMode", [[name: "Sensing mode*", type: "STRING", description: "See the 'Sensing Mode' state variable for this appliance's options"]]
        command "setSoundMode", [[name: "Sound mode*", type: "STRING", description: "See the 'Sound Mode' state variable for this appliance's options"]]
        command "setVentilationMode", [[name: "Ventilation mode*", type: "STRING", description: "See the 'Ventilation Mode' state variable for this appliance's options"]]
        command "setGoodSleep", [[name: "Good sleep*", type: "NUMBER"]]
        command "setSensingInterval", [[name: "Sensing interval*", type: "NUMBER"]]
        command "setSoundVolume", [[name: "Sound volume*", type: "NUMBER"]]
        command "setTropicalNightMode", [[name: "Tropical night mode*", type: "NUMBER"]]
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
        case "outdoor_temperature":
            if (isNullState(e.state)) return
            emit("temperature", coerce(e.state), [unit: "°${location.temperatureScale}"])
            return
        default:
            emitGeneric(e)
    }
}

private String cap(String s) { return s ? s[0].toUpperCase() + s.substring(1) : s }

// --- commands ---------------------------------------------------------------

void autoCleanStop() { haPress("auto_clean_stop") }
void diagnosisStart() { haPress("diagnosis_start") }
void filterTimeReset() { haPress("filter_time_reset") }
void setAbsenceClean(String v) { setSwitchOption("absence_clean", v) }
void setAbsencePowerSavingActive(String v) { setSwitchOption("absence_power_saving_active", v) }
void setAirMonitoring(String v) { setSwitchOption("air_monitoring", v) }
void setAirPurify(String v) { setSwitchOption("air_purify", v) }
void setAutoClean(String v) { setSwitchOption("auto_clean", v) }
void setAutoCleanLegacy(String v) { setSwitchOption("auto_clean_legacy", v) }
void setBeep(String v) { setSwitchOption("beep", v) }
void setDisplay(String v) { setSwitchOption("display", v) }
void setDisplayLight(String v) { setSwitchOption("display_light", v) }
void setEdgeLighting(String v) { setSwitchOption("edge_lighting", v) }
void setIndicatorLight(String v) { setSwitchOption("indicator_light", v) }
void setMotionDetectWindActive(String v) { setSwitchOption("motion_detect_wind_active", v) }
void setMuteOnce(String v) { setSwitchOption("mute_once", v) }
void setPeriodicAirSensing(String v) { setSwitchOption("periodic_air_sensing", v) }
void setPeriodicSensingSkipStatus(String v) { setSwitchOption("periodic_sensing_skip_status", v) }
void setSpi(String v) { setSwitchOption("spi", v) }
void setUvLed(String v) { setSwitchOption("uv_led", v) }
void setVentilationAlarm(String v) { setSwitchOption("ventilation_alarm", v) }
void setWindfree(String v) { setSwitchOption("windfree", v) }
void setWindsleep(String v) { setSwitchOption("windsleep", v) }
void setAirFilterPm1Threshold(String v) { setSelectOption("air_filter_pm1_threshold", v) }
void setAirFilterThreshold(String v) { setSelectOption("air_filter_threshold", v) }
void setEdgeLightingColor(String v) { setSelectOption("edge_lighting_color", v) }
void setEdgeLightingMode(String v) { setSelectOption("edge_lighting_mode", v) }
void setEnergySavingMode(String v) { setSelectOption("energy_saving_mode", v) }
void setFilterAlarmTime(String v) { setSelectOption("filter_alarm_time", v) }
void setIndicatorLightMode(String v) { setSelectOption("indicator_light_mode", v) }
void setSensingMode(String v) { setSelectOption("sensing_mode", v) }
void setSoundMode(String v) { setSelectOption("sound_mode", v) }
void setVentilationMode(String v) { setSelectOption("ventilation_mode", v) }
void setGoodSleep(BigDecimal v) { haSetNumber("good_sleep", v) }
void setSensingInterval(BigDecimal v) { haSetNumber("sensing_interval", v) }
void setSoundVolume(BigDecimal v) { haSetNumber("sound_volume", v) }
void setTropicalNightMode(BigDecimal v) { haSetNumber("tropical_night_mode", v) }
void setSensingSkipEnd(String v) { haSetTime("sensing_skip_end", v) }
void setSensingSkipStart(String v) { haSetTime("sensing_skip_start", v) }

void refresh() { haRefreshAll() }
