/**
 *  YoLink™ Leak Sensor (Local API Edition)
 *  © 2025 Albert Mulder
 *
 *  1.2.0 - Audit fixes: a message without a leak state no longer clears an active wet alarm (only
 *          "alert"/"normal" change water); battery 0 now reports 0% and a message without a battery reading
 *          leaves battery unchanged; no forced isStateChange events; new device token accepted from the app
 *          (setDeviceToken); broker credentials no longer stored; no extra poll on app save; debug logging
 *          turns itself off after 30 minutes.
 *  1.1.2 - Updated fro HPM
 *  1.1.1 - Harden the beginning of processStateData(String payload) to coerce/guard data and loraInfo before dereferencing.
 *  1.1.0 - Initial working driver
 */
import groovy.json.JsonSlurper

def clientVersion() { "1.2.0" }
def copyright()     { "© 2025 Albert Mulder" }
def driverName()    { "YoLink™ Leak Sensor (Local API Edition)" }

/* ============================ Preferences ============================ */
preferences {
    input name: "info",
          type: "paragraph",
          title: "Driver Info",
          description: """<b>Driver:</b> ${driverName()}<br>
                          <b>Version:</b> v${clientVersion()}<br>
                          <b>Temperature Scale:</b> ${activeScale()}<br>
                          <b>Date/Time Format:</b> ${activeFmt()}<br><br>
                          ${copyright()}"""
    input name: "debug", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
}

/* ============================== Metadata ============================= */
metadata {
    definition (name: "YoLink LeakSensor Device Local", namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Polling"
        capability "Battery"
        capability "WaterSensor"
        capability "Refresh"
        capability "TemperatureMeasurement"
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
        attribute "state", "String"           // raw: "normal" | "alert"
        attribute "switch", "String"          // "on" (wet) | "off" (dry)

        // Readings
        attribute "temperature", "Number"     // uses parent's scale
        attribute "water", "String"           // "wet" | "dry"

        // Battery/meta
        attribute "batteryType", "String"
        attribute "supportChangeMode", "String" // present on LeakSensor3
        attribute "modelHint", "String"         // derived hint (LeakSensor vs LeakSensor3)

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
    state.type         = devtype          // "LeakSensor"
    state.token        = devtoken
    state.devId        = devId
    state.localHubIP   = localHubIP
    state.remove("clientId")       // broker credentials are only needed by the MQTT Listener
    state.remove("clientSecret")
    rememberState("devId", devId)

    logDebug("ServiceSetup: DNI=${state.my_dni}, Device Id=${state.devId}, HubIP=${state.localHubIP}, TokenSet=${!!state.token}")
    state.driverVersion = clientVersion()  // keep local only
    // No poll here: the app runs a reconcile right after setup, which polls every device once
}

boolean isSetup() { (state.devId && state.token) }

// Called by the app when the Local Hub issues a new device token
void setDeviceToken(token) {
    if (token && state.token != token) {
        state.token = token
        logDebug("Device token updated")
    }
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("debug", [value: "false", type: "bool"])
    state.debug = false
}

def installed() {
    state.driverVersion = clientVersion() // keep in state only; do NOT sendEvent
    updated()
}

def updated() {
    state.debug = (settings?.debug == true)
    state.driverVersion = clientVersion() // keep in state only; do NOT sendEvent
    if (state.debug) runIn(1800, "logsOff")
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
    state.lastPollMs = now()
    runIn((delay ?: 1) as int, "getDevicestate")
    sendEvent(name: "lastPoll", value: new Date().format(activeFmt(), location?.timeZone))
}

/* ========================= Local API Interaction ===================== */
def getDevicestate() {
    logDebug("LeakSensor getDevicestate()")
    try {
        def request = [
            method      : "${state.type}.getState",  // LeakSensor.getState
            targetDevice: state.devId,
            token       : state.token
        ]
        def object = parent.pollAPI(request, state.name ?: "LeakSensor", state.type ?: "LeakSensor")
        String latestToken = parent.getDeviceTokenFor(state.devId?.toString())   // pollAPI may have refreshed it
        if (latestToken && latestToken != state.token) state.token = latestToken
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
/**
 * Poll response typical shape:
 *   data.state.{ state, battery, version, devTemperature, batteryType, supportChangeMode? }
 *   data.{ reportAt, loraInfo{devNetType, signal, gatewayId, gateways} }
 */
private void parseDevice(object) {
    def data   = object?.data ?: [:]
    def st     = data?.state ?: [:]
    def lora   = data?.loraInfo ?: [:]

    // Raw/state
    String rawState   = st?.state              // "normal" | "alert"
    String waterState = waterFromState(rawState)

    // Battery & firmware
    def     batt4     = st?.battery
    Integer battery   = parent?.batterylevel(batt4)
    String  fw        = st?.version?.toUpperCase()
    String  bType     = st?.batteryType

    // Temperature (device internal)
    def devTempC      = st?.devTemperature

    // LeakSensor3 hint
    def supportChange = st?.supportChangeMode

    // LoRa
    String devNetType = lora?.devNetType
    def    signal     = lora?.signal
    String gatewayId  = lora?.gatewayId
    def    gateways   = lora?.gateways

    // Timestamps
    def reportAt      = data?.reportAt

    // Emit
    updateWater(rawState, waterState)
    if (battery != null) rememberBatteryState(battery as int, true)
    if (fw) rememberState("firmware", fw)
    if (bType) rememberState("batteryType", bType)
    if (supportChange != null) {
        rememberState("supportChangeMode", supportChange.toString())
        rememberState("modelHint", supportChange ? "LeakSensor3" : "LeakSensor")
    }

    if (devTempC != null) {
        def t = convertCToPreferred(devTempC)
        sendEvent(name: "temperature", value: t, unit: activeScale())
    }

    if (reportAt) rememberState("reportAt", fmtTs(reportAt))
    if (devNetType) rememberState("loraDevNetType", devNetType)
    if (signal != null) sendEvent(name: "signal", value: "${signal} dBm")
    if (gateways != null) rememberState("gateways", gateways as int)
    if (gatewayId != null) rememberState("gatewayId", gatewayId)

    logDebug("Parsed(getState): state=${rawState} water=${waterState} batt4=${batt4}(${battery}%) fw=${fw} devTempC=${devTempC} signal=${signal}")
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
        Map data = (dataRaw instanceof Map) ? dataRaw : [ state: (dataRaw?.toString()) ]
        Map lora = (data?.loraInfo instanceof Map) ? (Map) data.loraInfo : [:]

        // 👇 KEY LINE: make a Map view of state, even if the raw is a String
        Map stateMap = (data?.state instanceof Map) ? (Map) data.state
                        : (data?.state instanceof String ? [state: data.state] : [:])

        String eventType = (root?.event ?: "").replace("${state.type}.", "")

        String rawState   = (stateMap?.state instanceof String) ? stateMap.state : (data?.state instanceof String ? data.state : null)
        String waterState = waterFromState(rawState)

        def     batt4   = (data?.battery != null) ? data.battery : stateMap?.battery
        Integer battery = parent?.batterylevel(batt4)

        String fw       = ((data?.version ?: stateMap?.version) ?: "").toString()?.toUpperCase()
        String bType    = (data?.batteryType != null) ? data.batteryType?.toString()
                          : (stateMap?.batteryType != null ? stateMap.batteryType?.toString() : null)
        def    devTempC = (data?.devTemperature != null) ? data.devTemperature : stateMap?.devTemperature
        def    supportChange = (data?.supportChangeMode != null) ? data.supportChangeMode : stateMap?.supportChangeMode

        def reportAt  = data?.reportAt ?: stateMap?.reportAt
        def changedAt = data?.stateChangedAt ?: stateMap?.stateChangedAt

        def signal     = lora?.signal
        def gatewayId  = lora?.gatewayId
        def gateways   = lora?.gateways
        def devNetType = lora?.devNetType

        updateWater(rawState, waterState)

        if (battery != null) rememberBatteryState(battery as int, (eventType == "Alert"))
        if (fw) rememberState("firmware", fw)
        if (bType) rememberState("batteryType", bType)
        if (supportChange != null) {
            rememberState("supportChangeMode", supportChange.toString())
            rememberState("modelHint", supportChange ? "LeakSensor3" : "LeakSensor")
        }

        if (devTempC != null) {
            def t = convertCToPreferred(devTempC)
            sendEvent(name: "temperature", value: t, unit: activeScale())
        }

        if (reportAt)  rememberState("reportAt", fmtTs(reportAt))
        if (changedAt) rememberState("stateChangedAt", fmtTs(changedAt))

        if (devNetType) rememberState("loraDevNetType", devNetType)
        if (signal != null) sendEvent(name: "signal", value: "${signal} dBm")
        if (gateways != null) rememberState("gateways", gateways as int)
        if (gatewayId != null) rememberState("gatewayId", gatewayId)

        lastResponse("MQTT Success (${eventType ?: 'Report'})")
    } catch (e) {
        log.warn "LeakSensor processStateData error: ${e}"
        lastResponse("MQTT Exception")
    }
}



/* ============================== Utilities ============================= */
def reset() {
    state.driverVersion = clientVersion()  // keep local only
    lastResponse("Reset completed")
    poll(true)
}

def lastResponse(value) { sendEvent(name: "lastResponse", value: "$value") }

def rememberState(name, value, unit=null) {
    if (state."$name" != value) {
        state."$name" = value
        if (unit) {
            sendEvent(name: "$name", value: "$value", unit: "$unit")
        } else {
            sendEvent(name: "$name", value: "$value")
        }
    }
}

def rememberBatteryState(def value, boolean forceSend = false) {
    if (value == null) return            // no reading in this message: leave battery as is
    if (state.battery != value || forceSend) {
        state.battery = value
        sendEvent(name: "battery", value: value?.toString(), unit: "%")
        logDebug("rememberBatteryState: battery => ${value}% (forceSend=${forceSend})")
    }
}

/* === Formatting helpers (pulled from parent app) === */
private String activeFmt() { parent?.getDateTimeFormat() ?: "MM/dd/yyyy hh:mm:ss a" }
private String activeScale() { parent?.temperatureScale() ?: "F" }

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
                for (p in patterns) { try { d = Date.parse(p, s); break } catch (ignored) {} }
            }
        }
        return d ? d.format(f, location?.timeZone) : (ts?.toString())
    } catch (e) {
        return ts?.toString()
    }
}

/* === Temperature helpers (use parent's scale/convert) === */
private def convertCToPreferred(def tempC) {
    try {
        return parent?.convertTemperature(tempC)
    } catch (e) {
        log.warn "parent.convertTemperature failed (${e}); using raw °C"
        return tempC
    }
}

/* === Logging & helpers === */
def successful(o) { o?.code == "000000" }

def pollError(o) {
    rememberState("online", "false")
    log.warn "Polling error: ${o?.code}"
}

def logDebug(msg) { if (state.debug == true) log.debug msg }

/* Tolerate parent's generic temperatureScale() ping; driver follows parent */
def temperatureScale(String scale) { /* no-op */ }

/* "alert" => wet, "normal" => dry, anything else (incl. missing) => null so the alarm is not cleared */
private String waterFromState(String raw) {
    switch (raw?.toLowerCase()) {
        case "alert":  return "wet"
        case "normal": return "dry"
        default:       return null
    }
}

/* Only touch water/switch when the message actually carried a leak state */
private void updateWater(String rawState, String waterState) {
    if (rawState) rememberState("state", rawState)
    if (waterState) {
        rememberState("water", waterState)
        rememberState("switch", (waterState == "wet") ? "on" : "off")
    } else if (rawState) {
        logDebug("Unrecognised leak state '${rawState}'; water left unchanged")
    }
}
