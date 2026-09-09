/**
 *  SAS - Samsung Range
 *
 *  Child of Samsung Appliance Listener. Verified against TP1X_DA-KS-RANGE-0101X
 *  (32 entities) plus its Subdevice 1 second oven cavity (13 entities).
 *
 *  The second cavity is a separate HA device linked by via_device_id. The
 *  listener folds it into this device rather than creating a seventh
 *  appliance; its entities arrive with scope "subdevice1" and land on
 *  cavity2* attributes.
 *
 *  Version: 0.5.0
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Range", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "ContactSensor"
        capability "TemperatureMeasurement"
        capability "Refresh"

        attribute "machineState",        "string"
        attribute "running",             "string"
        attribute "cavityState",         "string"
        attribute "cooktopRunningState", "string"
        attribute "activeBurners",       "number"
        attribute "burner1",             "string"
        attribute "burner2",             "string"
        attribute "burner3",             "string"
        attribute "burner4",             "string"
        attribute "burner5",             "string"
        attribute "setpoint",            "number"
        attribute "cookTime",            "number"
        attribute "cookingMode",         "string"
        attribute "operationTime",       "number"
        attribute "progressPercent",     "number"
        attribute "estimatedFinish",     "string"
        attribute "childLock",           "string"
        attribute "smartControl",        "string"
        attribute "lamp",                "string"
        attribute "energySaving",        "string"
        attribute "cooktopOnAlert",      "string"
        attribute "sound",               "string"

        // Second oven cavity
        attribute "cavity2CavityState",     "string"
        attribute "cavity2MachineState",    "string"
        attribute "cavity2Running",         "string"
        attribute "cavity2Temperature",     "number"
        attribute "cavity2Setpoint",        "number"
        attribute "cavity2CookTime",        "number"
        attribute "cavity2CookingMode",     "string"
        attribute "cavity2ProgressPercent", "number"
        attribute "cavity2OperationTime",   "number"
        attribute "cavity2EstimatedFinish", "string"

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "stop"
        command "setOvenSetpoint", [[name: "Temperature*", type: "NUMBER"]]
        command "setCookTime",     [[name: "Minutes*",     type: "NUMBER"]]
        command "setCookingMode", [[name: "Mode*", type: "STRING", description: "See the 'Cooking Mode' state variable for this appliance's options"]]
        command "lampOn"
        command "lampOff"
        command "syncClock"

        command "stopCavity2"
        command "setCavity2Setpoint",    [[name: "Temperature*", type: "NUMBER"]]
        command "setCavity2CookTime",    [[name: "Minutes*",     type: "NUMBER"]]
        command "setCavity2CookingMode", [[name: "Mode*", type: "STRING", description: "See the 'Cooking Mode' state variable for this appliance's options"]]
        command "setSound", [[name: "Sound*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setEnergySaving", [[name: "Energy saving*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setCooktopOnAlert", [[name: "Cooktop-on alert*", type: "ENUM", constraints: ["Off", "On"]]]
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

/**
 * Scope the listener assigns to the second cavity: snakeToCamel("subdevice_1").
 * A method rather than a top-level constant -- in a Groovy script
 * `static final` at top level is a local inside run() and methods cannot see it.
 */
private String sub() { return "subdevice1" }

void installed() { log.info "${device.displayName} installed" }
void updated() { log.info "${device.displayName} updated" }

void parseEntity(Map e) {
    rememberEntity((String) e.scope, (String) e.suffix, (String) e.entityId)
    noteEntityHealth(e)
    if (logEnable) log.debug "entity ${e.entityId} (${e.suffix}, scope=${e.scope}) = ${e.state}"
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, e.scope == "main" ? "" : "cavity2")
        return
    }

    // Second oven cavity -> cavity2* attributes.
    if (e.scope != "main") {
        emitGeneric(e, "cavity2")
        return
    }

    switch (e.suffix) {
        case "power":
            emitOnOff("switch", e.state)
            return
        case "door":
            emitOpenClosed("contact", e.state)
            return
        // Both are number attributes and both read "unknown" whenever the oven
        // is off, so skip rather than push a string into a numeric attribute.
        case "temperature":
        case "setpoint":
            if (isNullState(e.state)) {
                if (logEnable) log.debug "${e.suffix}: no reading -- not emitting"
                return
            }
            emit((String) e.suffix, coerce(e.state), [unit: "°${location.temperatureScale}"])
            return
        default:
            emitGeneric(e)
    }
}

// --- main oven / cooktop ----------------------------------------------------

void on()  { haTurnOn("power") }
void off() { haTurnOff("power") }

void stop() { haPress("stop") }

void setOvenSetpoint(BigDecimal t) { haSetNumber("setpoint", t) }
void setCookTime(BigDecimal m)     { haSetNumber("cook_time", m) }
void setCookingMode(String v)      { setSelectOption("cooking_mode", v) }

void lampOn()    { haTurnOn("lamp") }
void lampOff()   { haTurnOff("lamp") }
void syncClock() { haPress("sync_clock") }

// --- second cavity ----------------------------------------------------------

void stopCavity2()                        { haPress("stop", sub()) }
void setCavity2Setpoint(BigDecimal t)     { haSetNumber("setpoint",  t, sub()) }
void setCavity2CookTime(BigDecimal m)     { haSetNumber("cook_time", m, sub()) }
void setCavity2CookingMode(String v)      { setSelectOption("cooking_mode", v, sub()) }


// --- settings -----------------------------------------------------------

void setSound(String v) { setSwitchOption("sound", v) }
void setEnergySaving(String v) { setSwitchOption("energy_saving", v) }
void setCooktopOnAlert(String v) { setSwitchOption("cooktop_on_alert", v) }

void refresh() { haRefreshAll() }
