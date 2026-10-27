/**
 *  YoLink™ THSensor Device (Local API Edition)
 *  © 2025 Albert Mulder
 *  
 *  1.2.0 - Audit fixes: temperature correction offset converted as a difference (+1°C = +1.8°F, was 33.8°F);
 *          missing alarm block no longer writes "null" alarmCode; "mode" attribute declared; battery 0 now
 *          reports 0% and a message without a battery reading leaves battery unchanged; no forced
 *          isStateChange events; new device token accepted from the app (setDeviceToken); broker credentials
 *          no longer stored; no extra poll on app save; debug logging turns itself off after 30 minutes.
 *          Re-review: fixed an error on every poll introduced by the alarm-block change.
 *  1.1.2 - Updated for HPM
 *  1.1.1 - Harden the beginning of processStateData(String payload) to coerce/guard data and loraInfo before dereferencing.
 *  1.1.0 - Initial working driver
 */

import groovy.json.JsonSlurper

def clientVersion() { return "1.2.0"}
def copyright()     { return "© 2025 Albert Mulder" }
def bold(t)         { return "<strong>$t</strong>" }
def driverName()    { return "YoLink™ THSensor (Local API Edition)"}

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
    input name: "debug", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
}


/* ============================== Metadata ============================= */
metadata {
    definition(name: "YoLink THSensor Device Local", namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Polling"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
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
        attribute "state", "String"           // "normal" | "alert"
        attribute "mode", "String"            // device mode, when reported

        // Readings
        attribute "temperature", "Number"
        attribute "humidity", "Number"

        // Alarm block (raw booleans as strings for dashboards)
        attribute "alarmCode", "Number"
        attribute "alarmLowBattery", "String"
        attribute "alarmLowTemp", "String"
        attribute "alarmHighTemp", "String"
        attribute "alarmLowHumidity", "String"
        attribute "alarmHighHumidity", "String"
        attribute "alarmPeriod", "String"
        attribute "alarmSummary", "String"    // comma-joined active flags

        // Device config/state extras
        attribute "interval", "Number"        // reporting interval
        attribute "tempLimitMin", "Number"
        attribute "tempLimitMax", "Number"
        attribute "humidityLimitMin", "Number"
        attribute "humidityLimitMax", "Number"
        attribute "tempCorrection", "Number"
        attribute "humidityCorrection", "Number"
        attribute "batteryType", "String"

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
    state.type         = devtype          // "THSensor"
    state.token        = devtoken
    state.devId        = devId
    state.localHubIP   = localHubIP
    state.remove("clientId")       // broker credentials are only needed by the MQTT Listener
    state.remove("clientSecret")
    rememberState("devId", devId)

    logDebug "ServiceSetup: DNI=${state.my_dni}, Device Id=${state.devId}, HubIP=${state.localHubIP}, TokenSet=${!!state.token}"
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
    logDebug("THSensor getDevicestate()")
    try {
        def request = [
            method      : "THSensor.getState",
            targetDevice: state.devId,
            token       : state.token
        ]
        def object = parent.pollAPI(request, state.name ?: "THSensor", state.type ?: "THSensor")
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
private void parseDevice(object) {
    def data   = object?.data ?: [:]
    def st     = data?.state ?: [:]
    def alarm  = st?.alarm ?: [:]
    def lora   = data?.loraInfo ?: [:]

    // Core readings
    def tempC      = st?.temperature
    def humidity   = st?.humidity
    def batteryPct = parent?.batterylevel(st?.battery)

    // Device/meta
    String stateStr    = st?.state           // "normal" | "alert"
    String mode        = st?.mode
    Integer interval   = st?.interval as Integer
    String firmware    = st?.version?.toUpperCase()
    String batteryType = st?.batteryType

    // Limits & corrections (convert temps to parent's scale)
    def tMinC  = st?.tempLimit?.min
    def tMaxC  = st?.tempLimit?.max
    def hMin   = st?.humidityLimit?.min
    def hMax   = st?.humidityLimit?.max
    def tCorrC = st?.tempCorrection
    def hCorr  = st?.humidityCorrection

    // LoRa
    String devNetType = lora?.devNetType
    def    signal     = lora?.signal
    String gatewayId  = lora?.gatewayId
    def    gateways   = lora?.gateways

    // Timestamps
    def reportAt = data?.reportAt

    // Emit readings in preferred units
    if (tempC != null) {
        def temp = convertCToPreferred(tempC)
        sendEvent(name: "temperature", value: temp, unit: activeScale())
    }
    if (humidity != null) sendEvent(name: "humidity", value: humidity, unit: "%")
    if (batteryPct != null) rememberBatteryState(batteryPct as int, true)

    if (stateStr) rememberState("state", stateStr)
    if (mode) rememberState("mode", mode)
    if (interval != null) rememberState("interval", interval as int)
    if (firmware) rememberState("firmware", firmware)
    if (batteryType) rememberState("batteryType", batteryType)
    if (reportAt) rememberState("reportAt", fmtTs(reportAt))

    // Limits/corrections (convert temperature values)
    if (tMinC != null) rememberState("tempLimitMin", convertCToPreferred(tMinC))
    if (tMaxC != null) rememberState("tempLimitMax", convertCToPreferred(tMaxC))
    if (hMin  != null) rememberState("humidityLimitMin", hMin as double)
    if (hMax  != null) rememberState("humidityLimitMax", hMax as double)
    if (tCorrC!= null) rememberState("tempCorrection", convertCDeltaToPreferred(tCorrC))
    if (hCorr != null) rememberState("humidityCorrection", hCorr as double)

    // Alarms (only when the response carried an alarm block)
    Integer aCode = null   // declared here: the debug line below logs it
    if (alarm instanceof Map && !alarm.isEmpty()) {
        aCode = (alarm?.code as Integer)
        def flags = [
            lowBattery  : alarm?.lowBattery,
            lowTemp     : alarm?.lowTemp,
            highTemp    : alarm?.highTemp,
            lowHumidity : alarm?.lowHumidity,
            highHumidity: alarm?.highHumidity,
            period      : alarm?.period
        ]
        if (aCode != null) rememberState("alarmCode", aCode)
        rememberState("alarmLowBattery",  toStr(flags.lowBattery))
        rememberState("alarmLowTemp",     toStr(flags.lowTemp))
        rememberState("alarmHighTemp",    toStr(flags.highTemp))
        rememberState("alarmLowHumidity", toStr(flags.lowHumidity))
        rememberState("alarmHighHumidity",toStr(flags.highHumidity))
        rememberState("alarmPeriod",      toStr(flags.period))
        rememberState("alarmSummary", summarizeAlarms(flags))
    }

    // LoRa details
    if (devNetType) rememberState("loraDevNetType", devNetType)
    if (signal != null) sendEvent(name: "signal", value: "${signal} dBm")
    if (gateways != null) rememberState("gateways", gateways as int)
    if (gatewayId != null) rememberState("gatewayId", gatewayId)

    logDebug("Parsed(getState): T=${tempC}C H=${humidity}% batt4=${st?.battery}(${batteryPct}%) mode=${mode} interval=${interval} " +
             "limits=[${tMinC},${tMaxC}]C / [${hMin},${hMax}]% corr=[${tCorrC}C, ${hCorr}%] alarmCode=${aCode} state=${stateStr} loraSig=${signal}")
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

        String fw = (data?.version ?: data?.state?.version)?.toUpperCase()

        def tempC      = (data?.temperature != null) ? data.temperature : data?.state?.temperature
        def humidity   = (data?.humidity != null)    ? data.humidity    : data?.state?.humidity
        def battery4   = (data?.battery  != null)    ? data.battery     : data?.state?.battery
        def batteryPct = parent?.batterylevel(battery4)

        String stateStr = (data?.state instanceof String) ? data.state : data?.state?.state
        def alarm      = (data?.alarm instanceof Map) ? data.alarm : [:]

        String mode      = data?.mode
        Integer interval = data?.interval as Integer

        def tMinC  = data?.tempLimit?.min
        def tMaxC  = data?.tempLimit?.max
        def hMin   = data?.humidityLimit?.min
        def hMax   = data?.humidityLimit?.max
        def tCorrC = data?.tempCorrection
        def hCorr  = data?.humidityCorrection

        def signal    = lora?.signal
        def gatewayId = lora?.gatewayId
        def gateways  = lora?.gateways
        def devNet    = lora?.devNetType

        def reportAt  = data?.reportAt
        def changedAt = data?.stateChangedAt

        if (tempC != null)  sendEvent(name: "temperature", value: convertCToPreferred(tempC), unit: activeScale())
        if (humidity != null) sendEvent(name: "humidity", value: humidity, unit: "%")
        if (batteryPct != null) rememberBatteryState(batteryPct as int, false)
        if (fw) rememberState("firmware", fw)

        if (stateStr) rememberState("state", stateStr)
        if (mode) rememberState("mode", mode)
        if (interval != null) rememberState("interval", interval as int)

        if (tMinC != null) rememberState("tempLimitMin", convertCToPreferred(tMinC))
        if (tMaxC != null) rememberState("tempLimitMax", convertCToPreferred(tMaxC))
        if (hMin  != null) rememberState("humidityLimitMin", hMin as double)
        if (hMax  != null) rememberState("humidityLimitMax", hMax as double)
        if (tCorrC!= null) rememberState("tempCorrection", convertCDeltaToPreferred(tCorrC))
        if (hCorr != null) rememberState("humidityCorrection", hCorr as double)

        if (alarm instanceof Map && !alarm.isEmpty()) {
            Integer aCode = (alarm?.code as Integer)
            def flags = [
                lowBattery  : alarm?.lowBattery,
                lowTemp     : alarm?.lowTemp,
                highTemp    : alarm?.highTemp,
                lowHumidity : alarm?.lowHumidity,
                highHumidity: alarm?.highHumidity,
                period      : alarm?.period
            ]
            if (aCode != null) rememberState("alarmCode", aCode)
            rememberState("alarmLowBattery",  (flags.lowBattery  == null) ? "" : flags.lowBattery.toString())
            rememberState("alarmLowTemp",     (flags.lowTemp     == null) ? "" : flags.lowTemp.toString())
            rememberState("alarmHighTemp",    (flags.highTemp    == null) ? "" : flags.highTemp.toString())
            rememberState("alarmLowHumidity", (flags.lowHumidity == null) ? "" : flags.lowHumidity.toString())
            rememberState("alarmHighHumidity",(flags.highHumidity== null) ? "" : flags.highHumidity.toString())
            rememberState("alarmPeriod",      (flags.period      == null) ? "" : flags.period.toString())
            rememberState("alarmSummary", summarizeAlarms(flags))
        }

        if (devNet) rememberState("loraDevNetType", devNet)
        if (signal != null) sendEvent(name: "signal", value: "${signal} dBm")
        if (gateways != null) rememberState("gateways", gateways as int)
        if (gatewayId != null) rememberState("gatewayId", gatewayId)

        if (reportAt)  rememberState("reportAt", fmtTs(reportAt))
        if (changedAt) rememberState("stateChangedAt", fmtTs(changedAt))

        lastResponse("MQTT Success")
    } catch (e) {
        log.warn "THSensor processStateData error: ${e}"
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

/* === Formatting helpers (format comes from parent app) === */
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

/* === Temperature helpers (use parent's scale/convert) === */
private def convertCToPreferred(def tempC) {
    try {
        return parent?.convertTemperature(tempC)
    } catch (e) {
        log.warn "parent.convertTemperature failed (${e}); using raw °C"
        return tempC
    }
}

/* Offsets are temperature differences: scale by 9/5 for °F, no +32 */
private def convertCDeltaToPreferred(def deltaC) {
    try {
        return parent?.convertTemperatureDelta(deltaC)
    } catch (e) {
        log.warn "parent.convertTemperatureDelta failed (${e}); using raw °C"
        return deltaC
    }
}

/* === Alarm helpers === */
private String toStr(def b) { (b == null) ? "" : b.toString() }
private String summarizeAlarms(Map flags) {
    def on = []
    flags.each { k,v -> if (v == true) on << k }
    return on.join(", ")
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
