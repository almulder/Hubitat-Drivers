/**
 *  YoLink MotionSensor Device (Local API Edition)
 *  © 2025 Albert Mulder
 *
 *  
 *  1.1.2 - Updated for HPM
 *  1.1.1 - Harden the beginning of processStateData(String payload) to coerce/guard data and loraInfo before dereferencing.
 *  1.1.0 - Initial working driver
 */

import groovy.json.JsonSlurper

def clientVersion() { return "1.1.2" }
def copyright()     { return "© 2025 Albert Mulder" }
def driverName()    { return "YoLink™ MotionSensor (Local API Edition)" }

/* ============================ Preferences ============================ */
preferences {
    input name: "info",
          type: "paragraph",
          title: "Driver Info",
          description: """<b>Driver:</b> ${driverName()}<br>
                          <b>Version:</b> v${clientVersion()}<br>
                          <b>Temperature Scale:</b> ${activeScale()}<br>
                          <b>Date/Time Format:</b> ${activeFmt()}<br>
                          <br>${copyright()}"""
    input name: "debug", type: "bool", title: "Enable debug logging", defaultValue: false
}

/* ============================== Metadata ============================= */
metadata {
    definition(name: "YoLink MotionSensor Device Local", namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Polling"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "MotionSensor"
        capability "Refresh"
        capability "SignalStrength"
        command "reset"

        // Common status
        attribute "online", "String"
        attribute "devId", "String"
        attribute "firmware", "String"
        attribute "lastPoll", "String"
        attribute "lastResponse", "String"
        attribute "reportAt", "String"
        attribute "stateChangedAt", "String"
        attribute "state", "String"           // "normal" | "alert" (if provided)

        // Readings
        attribute "temperature", "Number"

        // LoRa info
        attribute "loraDevNetType", "String"
        attribute "signal", "String"          // "NN dBm"
        attribute "gateways", "Number"
        attribute "gatewayId", "String"
    }
}

/* ============================== Setup ================================ */
void ServiceSetup(Hubitat_dni, subnetId, devname, devtype, devtoken, devId, localHubIP=null, clientId=null, clientSecret=null) {
    state.my_dni       = Hubitat_dni
    state.subnetId     = subnetId
    state.name         = devname
    state.type         = devtype          // "MotionSensor"
    state.token        = devtoken
    state.devId        = devId
    state.localHubIP   = localHubIP
    state.clientId     = clientId
    state.clientSecret = clientSecret
    rememberState("devId", devId)

    logDebug "ServiceSetup: DNI=${state.my_dni}, Device Id=${state.devId}, HubIP=${state.localHubIP}, TokenSet=${!!state.token}"
    reset()
}

boolean isSetup() { (state.devId && state.token) }

def installed() {
    state.driverVersion = clientVersion() // keep in state only; do NOT sendEvent
    updated()
}

def updated() {
    state.debug = (settings?.debug == true)
    state.driverVersion = clientVersion() // keep in state only; do NOT sendEvent
}

def uninstalled() {
    log.warn "Device '${state.name}' removed"
}

/* ========================= Polling / Refresh ========================= */
def poll(force = null) {
    def min_seconds = 5
    def nowMs = now()
    if (force) state.lastPollMs = nowMs - (min_seconds * 1000)
    if (!state.lastPollMs || nowMs - state.lastPollMs >= (min_seconds * 1000)) {
        state.lastPollMs = nowMs
        pollDevice()
    } else {
        log.warn "Poll skipped (rate limit ${min_seconds}s)"
    }
}
def refresh() { poll(true) }

def pollDevice(delay=1) {
    runIn((delay ?: 1) as int, "getDevicestate")
    sendEvent(name: "lastPoll", value: new Date().format(activeFmt(), location?.timeZone), isStateChange: true)
}

/* ========================= Local API Interaction ===================== */
def getDevicestate() {
    logDebug("MotionSensor getDevicestate()")
    try {
        def request = [
            method      : "MotionSensor.getState",
            targetDevice: state.devId,
            token       : state.token
        ]
        def object = parent.pollAPI(request, state.name ?: "MotionSensor", state.type ?: "MotionSensor")
        if (object) {
            if (successful(object)) {
                parseDevice(object)
                rememberState("online", "true")
                lastResponse("Poll Success")
            } else {
                pollError(object)
            }
        } else {
            lastResponse("No response from Local API")
        }
    } catch (Exception e) {
        lastResponse("Exception $e")
        log.error "getDevicestate() exception: $e"
    }
}

/* ============================= Parsing =============================== */
private void parseDevice(object) {
    def data = object?.data ?: [:]
    def st   = data?.state ?: [:]
    def lora = data?.loraInfo ?: [:]

    // Temperature fields vary by model/firmware. Try common locations (Local API poll path).
    def tempRaw = (st?.temperature != null) ? st.temperature :
                  (st?.temp != null) ? st.temp :
                  (st?.devTemperature != null) ? st.devTemperature :
                  (data?.temperature != null) ? data.temperature :
                  (data?.devTemperature != null) ? data.devTemperature : null
    def temp = (tempRaw != null) ? normalizeAndConvertTemperature(tempRaw) : null

    def batteryPct = parent?.batterylevel(st?.battery)

    String stateStr = st?.state            // "normal" | "alert" (if provided)
    String firmware = st?.version?.toUpperCase()

    // Motion signal: some payloads use st.motion (bool), others imply via state==alert
    def motionVal = (st?.motion != null) ? st.motion : null
    String motionStr = deriveMotion(motionVal, stateStr, st)

    def reportAt = data?.reportAt
    def changedAt = data?.stateChangedAt

    // Emit readings
    if (temp != null) sendEvent(name: "temperature", value: temp, unit: activeScale(), isStateChange: true)
    if (batteryPct != null) rememberBatteryState(batteryPct as int, true)

    if (motionStr) sendEvent(name: "motion", value: motionStr, isStateChange: true)

    rememberState("state", stateStr)
    if (firmware) rememberState("firmware", firmware)
    if (reportAt) rememberState("reportAt", fmtTs(reportAt))
    if (changedAt) rememberState("stateChangedAt", fmtTs(changedAt))

    // LoRa details
    if (lora?.devNetType) rememberState("loraDevNetType", lora.devNetType)
    if (lora?.signal != null) sendEvent(name: "signal", value: "${lora.signal} dBm", isStateChange: true)
    if (lora?.gateways != null) rememberState("gateways", lora.gateways as int)
    if (lora?.gatewayId != null) rememberState("gatewayId", lora.gatewayId)

    logDebug("Parsed(getState): tempRaw=${tempRaw} => temp=${temp}${activeScale()} batt4=${st?.battery}(${batteryPct}%) motion=${motionStr} state=${stateStr} loraSig=${lora?.signal}")
}

/* ============== MQTT handlers (Report/Alert/StatusChange) ============== */
def parse(topic) { processStateData(topic?.payload) }

def processStateData(String payload) {
    try {
        def root = new JsonSlurper().parseText(payload)
        if (!root?.deviceId || state.devId != root.deviceId) return
        rememberState("online", "true")

        // Normalize data/lora
        def dataRaw = root?.data
        Map data = (dataRaw instanceof Map) ? (Map)dataRaw : [:]
        Map lora = (data?.loraInfo instanceof Map) ? (Map) data.loraInfo : [:]

        // Normalize state block (some payloads use data.state as a String like "normal")
        Map st = (data?.state instanceof Map) ? (Map)data.state : [:]
        String stateStr = (data?.state instanceof String) ? (String)data.state : (st?.state as String)

        // Temperature location differs by device/model (MQTT path). Try common keys.
        def tempRaw = (data?.temperature != null) ? data.temperature :
                      (data?.temp != null) ? data.temp :
                      (st?.temperature != null) ? st.temperature :
                      (st?.temp != null) ? st.temp :
                      (data?.devTemperature != null) ? data.devTemperature :
                      (st?.devTemperature != null) ? st.devTemperature : null
        def temp = (tempRaw != null) ? normalizeAndConvertTemperature(tempRaw) : null

        def battery4   = (data?.battery  != null) ? data.battery : st?.battery
        def batteryPct = parent?.batterylevel(battery4)

        String fw = (data?.version ?: st?.version)?.toUpperCase()

        def motionVal = (data?.motion != null) ? data.motion : st?.motion
        String motionStr = deriveMotion(motionVal, stateStr, st ?: data)

        def reportAt  = data?.reportAt
        def changedAt = data?.stateChangedAt

        if (temp != null) sendEvent(name: "temperature", value: temp, unit: activeScale(), isStateChange: true)
        if (batteryPct != null) rememberBatteryState(batteryPct as int, false)
        if (fw) rememberState("firmware", fw)
        if (stateStr) rememberState("state", stateStr)
        if (motionStr) sendEvent(name: "motion", value: motionStr, isStateChange: true)

        // LoRa
        if (lora?.devNetType) rememberState("loraDevNetType", lora.devNetType)
        if (lora?.signal != null) sendEvent(name: "signal", value: "${lora.signal} dBm", isStateChange: true)
        if (lora?.gateways != null) rememberState("gateways", lora.gateways as int)
        if (lora?.gatewayId != null) rememberState("gatewayId", lora.gatewayId)

        if (reportAt)  rememberState("reportAt", fmtTs(reportAt))
        if (changedAt) rememberState("stateChangedAt", fmtTs(changedAt))

        lastResponse("MQTT Success")
        logDebug("MQTT parsed: tempRaw=${tempRaw} => temp=${temp}${activeScale()} batt4=${battery4}(${batteryPct}%) motion=${motionStr} state=${stateStr}")
    } catch (e) {
        log.warn "MotionSensor processStateData error: ${e}"
        lastResponse("MQTT Exception")
    }
}

/* ============================== Utilities ============================= */
def reset() {
    state.driverVersion = clientVersion()  // keep local only
    lastResponse("Reset completed")
    poll(true)
}

def lastResponse(value) { sendEvent(name: "lastResponse", value: "$value", isStateChange: true) }

def rememberState(name, value, unit=null) {
    if (state."$name" != value) {
        state."$name" = value
        if (unit) {
            sendEvent(name: "$name", value: "$value", unit: "$unit", isStateChange: true)
        } else {
            sendEvent(name: "$name", value: "$value", isStateChange: true)
        }
    }
}

def rememberBatteryState(def value, boolean forceSend = false) {
    if (state.battery != value || forceSend) {
        state.battery = value
        sendEvent(name: "battery", value: value?.toString(), unit: "%", isStateChange: true)
        logDebug("rememberBatteryState: battery => ${value}% (forceSend=${forceSend})")
    }
}

/* === Formatting helpers (format comes from parent app) === */
private String activeFmt() { parent?.getDateTimeFormat() ?: "MM/dd/yyyy hh:mm:ss a" }
private String activeScale() { parent?.temperatureScale() ?: "C" }

private String fmtTs(def ts) {
    String f = activeFmt()
    try {
        Date d = null
        if (ts instanceof Number) {
            d = new Date((ts as long))
        } else if (ts instanceof String) {
            String s = ts.trim()
            if (s.isLong()) {
                d = new Date(s.toLong())
            } else {
                String[] patterns = [
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ssXXX"
                ]
                for (p in patterns) {
                    try { d = Date.parse(p, s); break } catch (ignored) {}
                }
            }
        }
        return d ? d.format(f, location?.timeZone) : (ts?.toString())
    } catch (e) {
        return ts?.toString()
    }
}

/* === Temperature helpers (THSensor-compatible + Motion-specific normalization) === */
private def normalizeAndConvertTemperature(def tempRaw) {
    // 1) Coerce to a number
    Double v = null
    try {
        if (tempRaw instanceof Number) v = (tempRaw as Number).doubleValue()
        else v = tempRaw?.toString()?.toDouble()
    } catch (ignored) { v = null }

    if (v == null) return null

    // 2) Preferred behavior: Local API reports temperature in °C, so we convert like THSensor
    // BUT: Some MotionSensor payloads appear to report Fahrenheit as (F - 136).
    // If we see a strongly negative value that would become a plausible Fahrenheit temp by adding 136,
    // normalize to °C first so parent.convertTemperature behaves correctly for either parent scale.
    //
    // Example: raw -57 => 79°F (because -57 + 136 = 79°F) => 26.1°C
    if (v < -20) {
        def fCandidate = v + 136.0
        if (fCandidate >= 32 && fCandidate <= 122) {
            // Convert that Fahrenheit candidate to °C, then proceed with THSensor conversion path.
            v = (fCandidate - 32.0) * (5.0/9.0)
            logDebug("Temp normalized (F-136): raw=${tempRaw} => f=${fCandidate} => c=${v}")
        }
    }

    // 3) Convert to parent's preferred scale using the same helper your THSensor uses
    try {
        return parent?.convertTemperature(v)
    } catch (e) {
        log.warn "parent.convertTemperature failed (${e}); using raw °C"
        return v
    }
}

/* === Motion helpers === */
private String deriveMotion(def motionVal, String stateStr, def st) {
    // Prefer explicit boolean/number
    try {
        if (motionVal instanceof Boolean) return (motionVal ? "active" : "inactive")
        if (motionVal instanceof Number) return ((motionVal as Number).intValue() != 0 ? "active" : "inactive")
        if (motionVal != null) {
            String s = motionVal.toString().toLowerCase()
            if (s in ["true","active","motion","detected","1"]) return "active"
            if (s in ["false","inactive","clear","0","none"]) return "inactive"
        }
    } catch (ignored) {}

    // Some devices signal via "state" or "alarm"
    if (stateStr?.toLowerCase() == "alert") return "active"

    def alarm = (st instanceof Map) ? st?.alarm : null
    if (alarm instanceof Map) {
        if (alarm?.motion == true) return "active"
    }

    // Default to inactive if we can't infer
    return "inactive"
}

/* === Logging & helpers === */
def successful(object) { object?.code == "000000" }

def pollError(object) {
    rememberState("online", "false")
    log.warn "Polling error: ${object?.code}"
}

def logDebug(msg) {
    if (state.debug == true) log.debug msg
}

/* Tolerate parent’s setup call; not exposed as a user command */
def temperatureScale(String scale) { /* no-op; driver follows parent */ }
