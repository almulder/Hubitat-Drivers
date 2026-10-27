/**
 *  YoLink™ Door Sensor (Local API Edition)
 *  © 2025 Albert Mulder
 *
 *  1.2.0 - Audit fixes: a message without a door state no longer sets contact to "null" / switch off;
 *          battery 0 now reports 0% and a message without a battery reading leaves battery unchanged; no
 *          forced isStateChange events; new device token accepted from the app (setDeviceToken); broker
 *          credentials no longer stored; no extra poll on app save; debug logging turns itself off after 30
 *          minutes.
 *  1.1.2 - Updated for HPM
 *  1.1.1 - Harden the beginning of processStateData(String payload) to coerce/guard data and loraInfo before dereferencing.
 *  1.1.0 - Initial working driver
 */
import groovy.json.JsonSlurper

def clientVersion() { "1.2.0" }
def copyright()     { "© 2025 Albert Mulder" }
def driverName()    { "YoLink™ Door Sensor (Local API Edition)" }

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
    definition (name: "YoLink DoorSensor Device Local", namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Polling"
        capability "Battery"
        capability "ContactSensor"
        capability "Refresh"
        capability "TemperatureMeasurement"   // optional: device internal temp (if present)
        capability "SignalStrength"

        command "reset"

        // Common status/meta
        attribute "online", "String"
        attribute "devId", "String"
        attribute "firmware", "String"
        attribute "lastPoll", "String"
        attribute "lastResponse", "String"
        attribute "reportAt", "String"
        attribute "stateChangedAt", "String"

        // Door specifics
        attribute "contact", "String"            // "open" | "closed"
        attribute "state", "String"              // raw state from API
        attribute "switch", "String"             // "on" when closed, "off" when open (status-only)
        attribute "openRemindDelay", "String"

        // Optional battery/meta
        attribute "batteryType", "String"

        // LoRa info
        attribute "loraDevNetType", "String"
        attribute "signal", "String"             // "NN dBm"
        attribute "gateways", "Number"
        attribute "gatewayId", "String"
    }
}

/* ============================== Setup ================================ */
void ServiceSetup(Hubitat_dni, subnetId, devname, devtype, devtoken, devId, localHubIP=null, clientId=null, clientSecret=null) {
    state.my_dni       = Hubitat_dni
    state.subnetId     = subnetId
    state.name         = devname
    state.type         = devtype
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
    logDebug("DoorSensor getDevicestate()")
    try {
        def request = [
            method      : "${state.type}.getState",  // DoorSensor.getState
            targetDevice: state.devId,
            token       : state.token
        ]
        def object = parent.pollAPI(request, state.name ?: "DoorSensor", state.type ?: "DoorSensor")
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
 * data.state.{ state, battery, version, devTemperature?, openRemindDelay?, batteryType? }
 * data.{ reportAt, loraInfo{devNetType, signal, gatewayId, gateways} }
 */
private void parseDevice(object) {
    def data   = object?.data ?: [:]
    def st     = data?.state ?: [:]
    def lora   = data?.loraInfo ?: [:]

    String rawState    = st?.state
    String contact     = normalizeContact(rawState)          // "open"|"closed"|null

    def     batt4      = st?.battery
    Integer batteryPct = parent?.batterylevel(batt4)
    String  fw         = st?.version?.toUpperCase()
    String  bType      = st?.batteryType

    def openRemind     = st?.openRemindDelay
    def devTempC       = st?.devTemperature

    String devNetType  = lora?.devNetType
    def    signal      = lora?.signal
    String gatewayId   = lora?.gatewayId
    def    gateways    = lora?.gateways

    def reportAt       = data?.reportAt

    updateContact(rawState, contact)
    if (batteryPct != null) rememberBatteryState(batteryPct as int, true)
    if (fw) rememberState("firmware", fw)
    if (bType) rememberState("batteryType", bType)
    if (openRemind != null) rememberState("openRemindDelay", openRemind?.toString())

    if (devTempC != null) {
        def t = convertCToPreferred(devTempC)
        sendEvent(name: "temperature", value: t, unit: activeScale())
    }

    if (reportAt) rememberState("reportAt", fmtTs(reportAt))
    if (devNetType) rememberState("loraDevNetType", devNetType)
    if (signal != null) sendEvent(name: "signal", value: "${signal} dBm")
    if (gateways != null) rememberState("gateways", gateways as int)
    if (gatewayId != null) rememberState("gatewayId", gatewayId)

    logDebug("Parsed(getState): contact=${contact} batt4=${batt4}(${batteryPct}%) fw=${fw} tempC=${devTempC} signal=${signal}")
}

/* ============== MQTT handlers (Report/Alert/StatusChange) ============== */
def parse(topic) { processStateData(topic?.payload) }

def processStateData(String payload) {
    try {
        def root = new JsonSlurper().parseText(payload)
        if (!root?.deviceId || state.devId != root.deviceId) return
        rememberState("online", "true")

        String eventType = (root?.event ?: "").replace("${state.type}.", "")

        // ⬇️ Make 'data' a Map no matter what came in
        def dataRaw = root?.data
        Map data = (dataRaw instanceof Map) ? dataRaw : [ state: (dataRaw?.toString()) ]

        // Guard loraInfo the same way
        Map lora = (data?.loraInfo instanceof Map) ? data.loraInfo : [:]

        String rawState = (data?.state instanceof String) ? data.state : data?.state?.state
        String contact  = normalizeContact(rawState)

        def batt4 = (data?.battery != null) ? data.battery
                    : (data?.state instanceof Map ? data.state.battery : null)
        Integer batteryPct = parent?.batterylevel(batt4)

        String fw    = ((data?.version) ?: (data?.state instanceof Map ? data.state.version : null))?.toUpperCase()
        String bType = (data instanceof Map ? data.batteryType : null) ?:
                       (data?.state instanceof Map ? data.state.batteryType : null)

        def openRemind = (data?.openRemindDelay != null) ? data.openRemindDelay
                         : (data?.state instanceof Map ? data.state.openRemindDelay : null)
        def devTempC   = (data?.devTemperature != null) ? data.devTemperature
                         : (data?.state instanceof Map ? data.state.devTemperature : null)

        def reportAt  = data?.reportAt
        def changedAt = data?.stateChangedAt

        def signal       = lora?.signal
        def gateways     = lora?.gateways
        String gatewayId = lora?.gatewayId
        String devNetType= lora?.devNetType

        updateContact(rawState, contact)

        if (batteryPct != null) rememberBatteryState(batteryPct as int, (eventType == "Alert"))
        if (fw)    rememberState("firmware", fw)
        if (bType) rememberState("batteryType", bType)
        if (openRemind != null) rememberState("openRemindDelay", openRemind?.toString())

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
        log.warn "DoorSensor processStateData error: ${e}"
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
private String activeFmt()   { parent?.getDateTimeFormat() ?: "MM/dd/yyyy hh:mm:ss a" }
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

/* Normalize API quirks: sometimes returns 'close' instead of 'closed'. Anything else => null */
private String normalizeContact(String raw) {
    if (!raw) return null
    switch (raw.toLowerCase()) {
        case "open":   return "open"
        case "closed":
        case "close":  return "closed"
        default:       return null
    }
}

/* Only touch contact/switch when the message actually carried a door state */
private void updateContact(String rawState, String contact) {
    if (rawState) rememberState("state", rawState)
    if (contact) {
        rememberState("contact", contact)
        rememberState("switch", (contact == "closed") ? "on" : "off")
    } else if (rawState) {
        logDebug("Unrecognised door state '${rawState}'; contact left unchanged")
    }
}
