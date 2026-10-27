/**
 *  SAS - Samsung Dishwasher
 *
 *  Child of Samsung Appliance Listener. One Hubitat device for the whole
 *  appliance. Verified against DA_DW_A51_20_COMMON (25 entities).
 *
 *  Attribute names follow the HubiThings Replica Samsung Dishwasher driver
 *  so Rule Machine rules carry over; see attrRenames().
 *
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - filterUsage is a number.
 *    - energy is in kWh, Hubitat's EnergyMeter unit; LocalThings' Wh is converted.
 *    - setSanitize and setStormWash, the Off/On form other drivers use; the old commands still work.
 *    - A subdevice entity lands on its own subdevice1-prefixed attribute instead of overwriting the main one.
 */

metadata {
    definition(name: "SAS - Samsung Dishwasher", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "EnergyMeter"
        capability "Refresh"

        // HubiThings Replica names (Samsung Dishwasher)
        attribute "operatingState",       "string"
        attribute "jobState",             "string"
        attribute "washingCourse",        "string"
        attribute "completionTime",       "string"
        attribute "remainingTime",        "number"
        attribute "lockState",            "string"
        attribute "remoteControlEnabled", "string"

        // No Replica equivalent
        attribute "running",          "string"
        attribute "progressPercent",  "number"
        attribute "delayStart",       "number"
        attribute "sanitize",         "string"
        attribute "stormWash",        "string"
        attribute "autoReleaseDry",   "string"
        attribute "filterStatus",     "string"
        attribute "filterUsage",      "number"
        attribute "drumCleanDueIn",   "number"

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "start"
        command "stop"
        command "cancel"
        command "pause"
        command "setWashingCourse", [[name: "Course*", type: "STRING", description: "See the 'Cycle' state variable for this appliance's options"]]
        command "setDelayStart", [[name: "Hours*", type: "NUMBER"]]
        command "sanitizeOn"
        command "sanitizeOff"
        command "stormWashOn"
        command "stormWashOff"
        command "setSanitize",  [[name: "Sanitize*",   type: "ENUM", constraints: ["Off", "On"]]]
        command "setStormWash", [[name: "Storm wash*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setAutoReleaseDry", [[name: "Auto release dry*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setSelectOption", [[name: "Entity suffix*", type: "STRING"],
                                    [name: "Option*", type: "STRING"]]
    }

    preferences {
        input name: "exposeDiagnostics", type: "bool", defaultValue: false,
              title: "Expose diagnostic entities"
        input name: "logEnable", type: "bool", defaultValue: false, title: "Debug logging",
              description: "Turns itself off after 30 minutes"
        input name: "txtEnable", type: "bool", defaultValue: true,  title: "Description text logging"
    }
}

#include almulder.SAS-Samsung-Appliance-Common

/** Library hook: this driver's names -> HubiThings Replica names. */
Map attrRenames() {
    return [
        "machineState"   : [name: "operatingState", values: RV_OPERATING_STATE],
        "progress"       : "jobState",
        "cycle"          : "washingCourse",
        "estimatedFinish": [name: "completionTime", format: "isoZ"],
        "completionTime" : "remainingTime",
        "childLock"      : [name: "lockState", values: RV_LOCK],
        "smartControl"   : [name: "remoteControlEnabled", values: RV_BOOL]
    ]
}

void installed() { log.info "${device.displayName} installed"; commonInstalled() }
void updated() { log.info "${device.displayName} updated"; commonUpdated() }

void uninstalled() { commonUninstalled() }

// ---------------------------------------------------------------------------
// Inbound
// ---------------------------------------------------------------------------

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
        case "power":
            emitOnOff("switch", e.state)
            return
        case "energy":
            emitEnergy("energy", e)
            return
        default:
            emitGeneric(e)
    }
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

void on()    { haTurnOn("power") }
void off()   { haTurnOff("power") }

void start() { haPress("start") }
void stop()  { haPress("stop") }
void pause() { haPress("pause") }

/** Replica's name for stop. */
void cancel() { stop() }

void setWashingCourse(String course) { setSelectOption("cycle", course) }

void setDelayStart(BigDecimal hours) { haSetNumber("delay_start", hours) }

void sanitizeOn()   { haTurnOn("sanitize") }
void sanitizeOff()  { haTurnOff("sanitize") }
void stormWashOn()  { haTurnOn("storm_wash") }
void stormWashOff() { haTurnOff("storm_wash") }

/** The setX(Off|On) form the other drivers use. */
void setSanitize(String v)  { setSwitchOption("sanitize", v) }
void setStormWash(String v) { setSwitchOption("storm_wash", v) }

// --- settings -----------------------------------------------------------

void setAutoReleaseDry(String v) { setSwitchOption("auto_release_dry", v) }

void refresh() { haRefreshAll() }
