/**
 *  SAS - Samsung Dryer
 *
 *  Child of Samsung Appliance Listener. Verified against DA_WM_TP1_21_COMMON
 *  (24 entities). Shares a board family with the washer -- the listener
 *  disambiguates on device name, not board family.
 *
 *  Attribute names follow the HubiThings Replica Samsung Dryer driver so
 *  Rule Machine rules carry over; see attrRenames().
 *
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - setCycle, setDryLevel, setWrinklePrevent and setSelectOption, for any other select.
 *    - energy is in kWh, Hubitat's EnergyMeter unit; LocalThings' Wh is converted.
 *    - A subdevice entity lands on its own subdevice1-prefixed attribute instead of overwriting the main one.
 *    - setDelayStart, as on the dishwasher.
 */

metadata {
    definition(name: "SAS - Samsung Dryer", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "EnergyMeter"
        capability "Refresh"

        // HubiThings Replica names (Samsung Dryer)
        attribute "switch",               "string"
        attribute "machineState",         "string"
        attribute "dryerJobState",        "string"
        attribute "completionTime",       "string"
        attribute "timeRemaining",        "string"
        attribute "dryerDryLevel",        "string"
        attribute "dryerWrinklePrevent",  "string"
        attribute "lockState",            "string"
        attribute "remoteControlEnabled", "string"

        // No Replica equivalent
        attribute "running",         "string"
        attribute "progressPercent", "number"
        attribute "cycle",           "string"
        attribute "delayStart",      "number"
        attribute "dryerType",       "string"
        attribute "buzzerSound",     "string"

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "start"
        command "stop"
        command "pause"
        command "setCycle", [[name: "Cycle*", type: "STRING", description: "See the 'Cycle' state variable for this appliance's options"]]
        command "setDryLevel", [[name: "Dry level*", type: "STRING", description: "See the 'Dry Level' state variable for this appliance's options"]]
        command "setWrinklePrevent", [[name: "Wrinkle prevent*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDelayStart", [[name: "Hours*", type: "NUMBER"]]
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

/** Library hook: this driver's names -> HubiThings Replica names. */
Map attrRenames() {
    return [
        "power"          : "switch",
        "machineState"   : [name: "machineState", values: RV_MACHINE_STATE],
        "progress"       : [name: "dryerJobState", values: RV_DRYER_JOB],
        "estimatedFinish": [name: "completionTime", format: "isoZ"],
        "completionTime" : [name: "timeRemaining", format: "hms"],
        "dryLevel"       : "dryerDryLevel",
        "wrinklePrevent" : "dryerWrinklePrevent",
        "childLock"      : [name: "lockState", values: RV_LOCK],
        "smartControl"   : [name: "remoteControlEnabled", values: RV_BOOL]
    ]
}

void installed() { log.info "${device.displayName} installed"; commonInstalled() }
void updated() { log.info "${device.displayName} updated"; commonUpdated() }

void uninstalled() { commonUninstalled() }

void parseEntity(Map e) {
    beginEntity(e)

    // A subdevice reading gets its scope as a prefix, so it can never
    // overwrite the main attribute of the same name.
    String scope = scopePrefix(e)
    if (scope) {
        if (e.domain == "select") {
            rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
            emitSelect(e, scope)
        } else {
            emitGeneric(e, scope)
        }
        return
    }
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, "")
        return
    }

    switch (e.suffix) {
        case "energy":
            emitEnergy("energy", e)
            return
        default:
            emitGeneric(e)
    }
}

void start() { haPress("start") }
void stop()  { haPress("stop") }
void pause() { haPress("pause") }

void setDelayStart(BigDecimal hours) { haSetNumber("delay_start", hours) }

void setCycle(String v)    { setSelectOption("cycle", v) }
void setDryLevel(String v) { setSelectOption("dry_level", v) }

/** A switch on most boards; a select where the board offers more than off and on. */
void setWrinklePrevent(String v) {
    if (optionsFor("wrinkle_prevent")) setSelectOption("wrinkle_prevent", v)
    else setSwitchOption("wrinkle_prevent", v)
}

// --- settings -----------------------------------------------------------

void refresh() { haRefreshAll() }
