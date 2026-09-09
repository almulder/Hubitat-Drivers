/**
 *  SAS - Samsung Microwave
 *
 *  Child of Samsung Appliance Listener. Verified against
 *  TP1X_DA-KS-MICROWAVE-01051 (22 entities).
 *
 *  This appliance exposes a bare `fan.` entity with NO suffix -- it is the
 *  vent hood. It maps to the `vent` attribute.
 *
 *  Version: 0.5.0
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Microwave", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "ContactSensor"
        capability "EnergyMeter"
        capability "Refresh"

        attribute "machineState",    "string"
        attribute "running",         "string"
        attribute "cavityState",     "string"
        attribute "cookingMode",     "string"
        attribute "cookTime",        "number"
        attribute "operationTime",   "number"
        attribute "powerLevel",      "number"
        attribute "progressPercent", "number"
        attribute "estimatedFinish", "string"
        attribute "childLock",       "string"
        attribute "vent",            "string"
        attribute "lamp",            "string"
        attribute "sound",           "string"
        attribute "endSignalReminder", "string"
        attribute "filterReminder",  "string"

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "stop"
        command "setCookTime",    [[name: "Minutes*", type: "NUMBER"]]
        command "setCookingMode", [[name: "Mode*", type: "STRING", description: "See the 'Cooking Mode' state variable for this appliance's options"]]
        command "ventOn"
        command "ventOff"
        command "lampOn"
        command "lampOff"
        command "setSound", [[name: "Sound*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setEndSignalReminder", [[name: "End signal reminder*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setFilterReminder", [[name: "Filter reminder*", type: "ENUM", constraints: ["Off", "On"]]]
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
    if (logEnable) log.debug "entity ${e.entityId} (${e.suffix}) = ${e.state}"
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, "")
        return
    }

    // The vent hood arrives as a fan entity named exactly for the device, so
    // its suffix is empty.
    if (e.domain == "fan" && !e.suffix) {
        emitOnOff("vent", e.state)
        return
    }

    switch (e.suffix) {
        case "door":
            emitOpenClosed("contact", e.state)
            return
        case "energy":
            if (!isNullState(e.state)) emit("energy", coerce(e.state), [unit: e.unit ?: "Wh"])
            return
        default:
            emitGeneric(e)
    }
}

void stop() { haPress("stop") }

void setCookTime(BigDecimal m) { haSetNumber("cook_time", m) }

void setCookingMode(String v) { setSelectOption("cooking_mode", v) }

/** The vent is a fan entity with no suffix; address it by its empty-string key. */
void ventOn()  { haService("fan", "turn_on",  "") }
void ventOff() { haService("fan", "turn_off", "") }

void lampOn()  { haTurnOn("lamp") }
void lampOff() { haTurnOff("lamp") }

// --- settings -----------------------------------------------------------

void setSound(String v) { setSwitchOption("sound", v) }
void setEndSignalReminder(String v) { setSwitchOption("end_signal_reminder", v) }
void setFilterReminder(String v) { setSwitchOption("filter_reminder", v) }

void refresh() { haRefreshAll() }
