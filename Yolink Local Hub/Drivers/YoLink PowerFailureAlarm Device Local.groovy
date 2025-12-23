/**
 *  YoLink™ Power Failure Alarm (Local API Edition)
 *  © 2025 Albert Mulder
 *
 *  1.1.2 - Updated for HPM
 *  1.1.1 - Harden the beginning of processStateData(String payload) to coerce/guard data and loraInfo before dereferencing.
 *  1.1.0 - Initial working driver
 */
import groovy.json.JsonSlurper

def clientVersion() { "1.1.2" }
def copyright()     { "© 2025 Albert Mulder" }
def driverName()    { "YoLink™ Power Failure Alarm (Local API Edition)" }

/* ============================ Preferences ============================ */
preferences {
    input name: "info",
          type: "paragraph",
          title: "Driver Info",
          description: """<b>Driver:</b> ${driverName()}<br>
                          <b>Version:</b> v${clientVersion()}<br>
                          <b>Date/Time Format:</b> ${activeFmt()}<br><br>
                          ${copyright()}"""
    input name: "debug", type: "bool", title: "Enable debug logging", defaultValue: false
}

/* ============================== Metadata ============================= */
metadata {
    definition (name: "YoLink PowerFailureAlarm Device Local",
               namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {

        capability "Polling"
        capability "Refresh"
        capability "Battery"
        capability "PowerSource"      // "mains" or "battery"

        attribute "online", "String"
        attribute "devId", "String"
        attribute "firmware", "String"
        attribute "lastPoll", "String"
        attribute "lastResponse", "String"
        attribute "reportAt", "String"
        attribute "stateChangedAt", "String"
        attribute "sound", "Number"
        attribute "state", "String"               // "normal" | "alert" | "off"
        attribute "powerSupply", "String"         // "true"/"false"
        attribute "beep", "String"                // "true"/"false"
        attribute "mute", "String"                // "true"/"false"
        attribute "alertType", "String"
        attribute "switch", "String"              // "on" (outage) | "off" (normal)

    }
}

/* ============================== Setup ================================ */
void ServiceSetup(Hubitat_dni, subnetId, devname, devtype, devtoken, devId,
                  localHubIP=null, clientId=null, clientSecret=null) {
    state.my_dni       = Hubitat_dni
    state.subnetId     = subnetId
    state.name         = devname
    state.type         = devtype
    state.token        = devtoken
    state.devId        = devId
    state.localHubIP   = localHubIP
    state.clientId     = clientId
    state.clientSecret = clientSecret
    rememberState("devId", devId)

    logDebug "ServiceSetup: DNI=${state.my_dni}, DeviceId=${state.devId}, HubIP=${state.localHubIP}, TokenSet=${!!state.token}"
    reset()
}

boolean isSetup() { (state.devId && state.token) }

def installed() {
    state.driverVersion = clientVersion() // keep in state only
    updated()
}

def updated() {
    state.debug = (settings?.debug == true)
    state.driverVersion = clientVersion() // keep in state only
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
    // driverVersion stays in state (no event)
    runIn((delay ?: 1) as int, "getDevicestate")
    sendEvent(name: "lastPoll", value: new Date().format(activeFmt(), location?.timeZone), isStateChange: true)
}

/* ========================= Local API Interaction ===================== */
def getDevicestate() {
    logDebug("PFA getDevicestate()")
    try {
        def request = [
            method      : "${state.type}.getState",  // PowerFailureAlarm.getState
            targetDevice: state.devId,
            token       : state.token
        ]
        def object = parent.pollAPI(request, state.name, state.type)
        if (object) {
            if (successful(object)) {
                parseDevice(object)
                rememberState("online", "true")
                lastResponse("Success")
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

    String  rawState  = st?.state                 // "normal"|"alert"|"off"
    String  alertType = st?.alertType
    Integer soundLvl  = (st?.sound as Integer)
    Integer battery4  = (st?.battery as Integer) ?: 0
    Boolean supply    = (st?.powerSupply instanceof Boolean) ? st.powerSupply : null
    Boolean beepOn    = (st?.beep instanceof Boolean)        ? st.beep        : null
    Boolean muteOn    = (st?.mute instanceof Boolean)        ? st.mute        : null
    String  fwVer     = st?.version
    def     reportAt  = data?.reportAt

    Integer batteryPct = Math.min(100, Math.max(0, battery4 * 25))
    boolean outage  = (rawState == "alert") || (supply == false)
    String  powerSrc = outage ? "battery" : "mains"
    String  swState  = outage ? "on" : "off"

    if (reportAt) rememberState("reportAt", fmtTs(reportAt))
    rememberState("state", rawState)
    rememberBatteryState(batteryPct, true)
    rememberState("powerSupply", (supply == null ? "" : supply.toString()))
    rememberState("powerSource", powerSrc)
    rememberState("switch", swState)
    if (fwVer)          rememberState("firmware", fwVer?.toUpperCase())
    if (alertType)      rememberState("alertType", alertType)
    if (beepOn != null) rememberState("beep",  beepOn.toString())
    if (muteOn != null) rememberState("mute",  muteOn.toString())
    if (soundLvl != null) sendEvent(name: "sound", value: soundLvl, isStateChange: true)

    logDebug("Parsed(getState): state=${rawState}, powerSupply=${supply}, powerSource=${powerSrc}, " +
             "switch=${swState}, battery=${batteryPct}%, sound=${soundLvl}, beep=${beepOn}, mute=${muteOn}, " +
             "fw=${fwVer}, reportAt=${fmtTs(reportAt)}")
}

/* ============================ MQTT handler =========================== */
def parse(topic) { processStateData(topic?.payload) }

def processStateData(String payload) {
    try {
        def root = new JsonSlurper().parseText(payload)
        if (state?.devId && root?.deviceId && state.devId != root.deviceId) return
        rememberState("online", "true")

        // Normalize data/lora
        def dataRaw = root?.data
        Map data = (dataRaw instanceof Map) ? dataRaw : [ state: (dataRaw?.toString()) ]
        Map st   = (data?.state instanceof Map) ? (Map) data.state : data
        Map lora = (data?.loraInfo instanceof Map) ? (Map) data.loraInfo : [:]

        String  rawState  = st?.state
        String  alertType = st?.alertType
        Integer sound     = (st?.sound as Integer)
        Integer batt4     = (st?.battery as Integer) ?: 0
        Boolean supply    = (st?.powerSupply instanceof Boolean) ? st.powerSupply : null
        Boolean beepOn    = (st?.beep instanceof Boolean) ? st.beep : null
        Boolean muteOn    = (st?.mute instanceof Boolean) ? st.mute : null
        String  fwVer     = st?.version
        def     changedAt = st?.stateChangedAt

        Integer batteryPct = Math.min(100, Math.max(0, batt4 * 25))
        boolean outage  = (rawState == "alert") || (supply == false)
        String  powerSrc = outage ? "battery" : "mains"
        String  swState  = outage ? "on" : "off"

        rememberState("state", rawState)
        rememberState("powerSupply", (supply == null ? "" : supply.toString()))
        rememberState("powerSource", powerSrc)
        rememberState("switch", swState)
        rememberBatteryState(batteryPct, false)
        if (fwVer)          rememberState("firmware", fwVer?.toUpperCase())
        if (alertType)      rememberState("alertType", alertType)
        if (beepOn != null) rememberState("beep",  beepOn.toString())
        if (muteOn != null) rememberState("mute",  muteOn.toString())
        if (sound != null)  sendEvent(name: "sound", value: sound, isStateChange: true)
        if (changedAt)      rememberState("stateChangedAt", fmtTs(changedAt))
    } catch (e) {
        log.warn "PFA processStateData error: ${e}"
    }
}


/* ============================== Utilities ============================= */
def reset() {
    state.driverVersion = clientVersion()  // keep local only
    rememberState("powerSource", "mains")
    rememberState("switch", "off")
    lastResponse("Reset completed")
    poll(true)
}

def lastResponse(value) { sendEvent(name: "lastResponse", value: "$value", isStateChange: true) }

def rememberState(name, value, unit=null) {
    if (state."$name" != value) {
        state."$name" = value
        if (unit) sendEvent(name: "$name", value: "$value", unit: "$unit", isStateChange: true)
        else      sendEvent(name: "$name", value: "$value", isStateChange: true)
    }
}

def rememberBatteryState(def value, boolean forceSend = false) {
    if (state.battery != value || forceSend) {
        state.battery = value
        sendEvent(name: "battery", value: value?.toString(), unit: "%", isStateChange: true)
        logDebug("rememberBatteryState: battery => ${value}% (forceSend=${forceSend})")
    }
}

/* === Date/Time formatting — pulled from parent (no attribute emitted) === */
private String activeFmt() {
    try { return parent?.getDateTimeFormat() ?: "MM/dd/yyyy hh:mm:ss a" }
    catch (ignored) { return "MM/dd/yyyy hh:mm:ss a" }
}
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

/* === Misc helpers === */
def successful(o) { o?.code == "000000" }

def pollError(o) {
    rememberState("online", "false")
    log.warn "Polling error: ${o?.code}"
}

def logDebug(msg) { if (state.debug == true) log.debug msg }

/* Ignore temperatureScale() broadcasts from parent app */
def temperatureScale(String scale) { /* not applicable */ }
