/**
 *  SAS - Samsung Air Dresser
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/air_dresser.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 1 binary_sensor, 3 button, 1 number, 2 select, 7 sensor, 2 switch.
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
    definition(name: "SAS - Samsung Air Dresser", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"

        attribute "cycleActive",        "string"
        attribute "delayStartHours",    "number"
        attribute "buzzerSound",        "string"
        attribute "finishSound",        "string"
        attribute "completionMinutes",  "number"
        attribute "diagnosisStatus",    "string"   // diagnostic
        attribute "finishTime",         "number"
        attribute "jobBeginningStatus", "string"   // diagnostic
        attribute "machineState",       "string"
        attribute "progress",           "string"
        attribute "progressPercentage", "number"
        attribute "sanitize",           "string"
        attribute "wrinklePrevent",     "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "diagnosisStart"
        command "pause"
        command "start"
        command "setSanitize", [[name: "Sanitize*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setWrinklePrevent", [[name: "Wrinkle prevent*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setBuzzerSound", [[name: "Buzzer sound*", type: "STRING", description: "See the 'Buzzer Sound' state variable for this appliance's options"]]
        command "setFinishSound", [[name: "Finish sound*", type: "STRING", description: "See the 'Finish Sound' state variable for this appliance's options"]]
        command "setDelayStartHours", [[name: "Delay start hours*", type: "NUMBER"]]
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
void pause() { haPress("pause") }
void start() { haPress("start") }
void setSanitize(String v) { setSwitchOption("sanitize", v) }
void setWrinklePrevent(String v) { setSwitchOption("wrinkle_prevent", v) }
void setBuzzerSound(String v) { setSelectOption("buzzer_sound", v) }
void setFinishSound(String v) { setSelectOption("finish_sound", v) }
void setDelayStartHours(BigDecimal v) { haSetNumber("delay_start_hours", v) }

void refresh() { haRefreshAll() }
