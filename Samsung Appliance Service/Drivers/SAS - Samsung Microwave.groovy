/**
 *  SAS - Samsung Microwave
 *
 *  Child of Samsung Appliance Listener. Verified against
 *  TP1X_DA-KS-MICROWAVE-01051 (22 entities).
 *
 *  Light and vent follow the HubiThings Replica Samsung Microwave Hood
 *  driver, so Rule Machine rules carry over:
 *
 *    brightnessLevel / setBrightnessLevel   off, low, high
 *    switch / on / off                      the light; on restores the last level
 *    hoodFanSpeed / setHoodFanSpeed         0 (off) to settableMaxFanSpeed
 *
 *  Low needs LocalThings' "Lamp level" select (added in the almulder/localthings
 *  fork, upstream issue #181). Without it only the on/off "Lamp" switch exists,
 *  so low falls back to on, which the microwave runs at High.
 *
 *  The vent arrives as a fan entity named exactly for the device, so its
 *  suffix is empty. HA reports its speed as a percentage in steps of
 *  100 / speed count; this driver turns that back into Samsung's 0-4.
 *
 *  Version: 0.6.1
 *  Author:  Albert Mulder (almulder)
 */

metadata {
    definition(name: "SAS - Samsung Microwave", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "ContactSensor"
        capability "EnergyMeter"
        capability "Refresh"

        // HubiThings Replica names (Samsung Microwave Hood)
        attribute "brightnessLevel",       "string"
        attribute "hoodFanSpeed",          "number"
        attribute "settableMinFanSpeed",   "number"
        attribute "settableMaxFanSpeed",   "number"
        attribute "supportedHoodFanSpeed", "string"

        // No Replica equivalent
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

        command "setBrightnessLevel", [[name: "brightnessLevel*", type: "ENUM", constraints: ["off", "low", "high"]]]
        command "setHoodFanSpeed",    [[name: "speed*", type: "NUMBER", description: "0 (off) to settableMaxFanSpeed"]]

        command "stop"
    }

    preferences {
        input name: "exposeDiagnostics", type: "bool", defaultValue: false,
              title: "Expose diagnostic entities"
        input name: "logEnable", type: "bool", defaultValue: true, title: "Debug logging"
        input name: "txtEnable", type: "bool", defaultValue: true, title: "Description text logging"
    }
}

#include almulder.SAS-Samsung-Appliance-Common

/** Library hook: names are handled in parseEntity, where light and vent need logic. */
Map attrRenames() { return [:] }

void installed() { log.info "${device.displayName} installed" }
void updated() { log.info "${device.displayName} updated" }

void parseEntity(Map e) {
    rememberEntity((String) e.scope, (String) e.suffix, (String) e.entityId)
    noteEntityHealth(e)
    if (logEnable) log.debug "entity ${e.entityId} (${e.suffix}) = ${e.state}"

    // Three-level light, from the fork's select. HA's state is the lowercase
    // translation key -- off, low, high -- which is Replica's spelling too.
    if (e.domain == "select" && e.suffix == "lamp_level") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        if (isNullState(e.state)) return
        emitBrightness(e.state.toString().toLowerCase())
        return
    }

    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, "")
        return
    }

    if (e.domain == "fan" && !e.suffix) {
        emitVent(e)
        return
    }

    switch (e.suffix) {
        case "lamp":
            emitOnOff("lamp", e.state)
            // The on/off switch speaks for the light only while there is no
            // level select; the microwave's "on" is High.
            if (!hasLampLevel() && !isNullState(e.state)) {
                emitBrightness(onOff(e.state) == "on" ? "high" : "off")
            }
            return
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

private void emitBrightness(String level) {
    emit("brightnessLevel", level)
    emit("switch", level == "off" ? "off" : "on")
    if (level != "off") state.lastBrightnessLevel = level
}

private void emitVent(Map e) {
    emitOnOff("vent", e.state)
    if (isNullState(e.state)) return
    Integer step = fanStep(e.percentageStep)
    if (step) {
        int max = (int) Math.round(100.0d / step)
        if (state.fanStep != step) state.fanStep = step
        emit("settableMinFanSpeed", 0)
        emit("settableMaxFanSpeed", max)
        emit("supportedHoodFanSpeed", (0..max).toList().toString())
    }
    Integer pct = (e.percentage instanceof Number) ? ((Number) e.percentage).intValue()
                : (e.percentage?.toString()?.isNumber() ? new BigDecimal(e.percentage.toString()).intValue() : null)
    int speed = 0
    if (onOff(e.state) == "on" && pct != null && step) speed = (int) Math.round(pct / (double) step)
    emit("hoodFanSpeed", speed)
}

private Integer fanStep(def raw) {
    if (raw == null) return (state.fanStep ?: null) as Integer
    if (!raw.toString().isNumber()) return null
    long s = Math.round(new BigDecimal(raw.toString()).doubleValue())
    return s > 0 ? (int) s : null
}

private boolean hasLampLevel() { return entityFor("lamp_level") != null }

// --- light ---------------------------------------------------------------

void setBrightnessLevel(String level) {
    String l = level?.toLowerCase()
    if (!(l in ["off", "low", "high"])) {
        log.warn "${device.displayName}: brightness level '${level}' -- use off, low or high"
        return
    }
    if (hasLampLevel()) {
        setSelectOption("lamp_level", l)
        return
    }
    if (l == "low") {
        log.warn "${device.displayName}: low needs LocalThings' Lamp level control " +
                 "(almulder/localthings fork) -- turning the light on at High instead"
    }
    if (l == "off") haTurnOff("lamp") else haTurnOn("lamp")
}

/** Replica: on restores the last level the light was at. */
void on()  { setBrightnessLevel((state.lastBrightnessLevel ?: "high") as String) }
void off() { setBrightnessLevel("off") }

// --- vent ----------------------------------------------------------------

void setHoodFanSpeed(BigDecimal speed) {
    int s = speed == null ? 0 : speed.intValue()
    def maxV = device.currentValue("settableMaxFanSpeed")
    int max = maxV != null ? (maxV as Integer) : 4
    s = Math.max(0, Math.min(s, max))
    if (s == 0) { ventOff(); return }
    Integer step = fanStep(null) ?: (int) Math.round(100.0d / max)
    haService("fan", "set_percentage", "", [percentage: s * step])
}

void ventOff() { haService("fan", "turn_off", "") }

// --- cooking --------------------------------------------------------------

void stop() { haPress("stop") }

// --- settings -----------------------------------------------------------

void refresh() { haRefreshAll() }
