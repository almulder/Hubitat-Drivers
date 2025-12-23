/**
 *  YoLink™ MQTT Listener (Local API Edition)
 *  © 2025 Albert Mulder
 *
 *  1.1.1 - Updated for HPM
 *  1.1.0 - Initial working driver
 */

import groovy.json.JsonSlurper

def clientVersion() { "1.1.1" }
def copyright()     { "© 2025 Albert Mulder" }
def driverName()    { "YoLink™ MQTT Listener (Local API Edition)" }

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
    input name: "debug", type: "bool", title: "Enable debug logging", defaultValue: false
}

/* ============================== Metadata ============================= */
metadata {
    definition (name: "YoLink MQTT Listener Device Local", namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Polling"
        capability "Initialize"

        command "connect"
        command "reset"

        attribute "online", "String"
        attribute "devId", "String"
        // attribute "driver", "String"          // hidden (no events sent)
        attribute "firmware", "String"
        attribute "lastPoll", "String"
        attribute "MQTT", "String"
        attribute "lastResponse", "String"
    }
}

/* ============================== Setup ================================ */

// keep to tolerate parent broadcasts; do NOT emit an attribute
def temperatureScale(String scale = null) {
    if (scale) {
        state.temperatureScale = scale
        logDebug("temperatureScale set to '${scale}' (stored only)")
    }
    return state.temperatureScale ?: (parent?.temperatureScale() ?: "F")
}

void setDeviceToken(token) {
    if (state.token != token) {
        logDebug("Device token changed from '${state.token}' to '${token}'")
        state.token = token
    } else {
        logDebug("Device token unchanged: '${state.token}'")
    }
}

void ServiceSetup(Hubitat_dni, subnetId, devname, devtype, devtoken, devId, localHubIP=null, clientId=null, clientSecret=null) {
    state.my_dni       = Hubitat_dni
    state.subnetId     = subnetId
    state.name         = devname
    state.type         = devtype
    state.token        = devtoken
    state.devId        = devId
    state.localHubIP   = localHubIP   ?: parent?.settings?.localHubIP
    state.clientId     = clientId     ?: parent?.settings?.Client_ID
    state.clientSecret = clientSecret ?: parent?.settings?.Client_Secret
    rememberState("devId", devId)

    logDebug("ServiceSetup(): subnetId=${state.subnetId}, localHubIP=${state.localHubIP}, clientID=${state.clientId}, secretLen=${state.clientSecret?.length()}")
    connect()
}

public def getSetup() {
    [
      my_dni: state.my_dni, subnetId: state.subnetId, name: state.name, type: state.type,
      token: state.token, devId: state.devId, localHubIP: state.localHubIP,
      clientId: state.clientId, clientSecret: state.clientSecret
    ]
}

public def isSetup() {
    return (state.my_dni && state.subnetId && state.name && state.type && state.token && state.devId && state.clientId && state.clientSecret)
}

/* ============================ Lifecycle ============================== */

def installed() {
    log.info "YoLink MQTT Listener Local Device Installed"
    state.driverVersion = clientVersion() // keep in state only; no event
    updated()
}

def updated() {
    log.info "YoLink MQTT Listener Local Device Updated"
    state.driverVersion = clientVersion()
    state.debug = (settings?.debug == true)
}

def initialize() {
    log.trace "Initializing: Establishing MQTT connection to YoLink Local API"
    connect()
    runEvery1Minute(mqttWatchdog)
}

def uninstalled() {
    interfaces.mqtt.disconnect()
    log.warn "MQTT Listener Uninstalled"
}

/* ============================ Poll / Connect ========================= */

def poll() {
    def isConn = interfaces.mqtt.isConnected()
    rememberState("online", isConn)
    logDebug("poll(): MQTT isConnected() = ${isConn}")
    if (isConn) {
        rememberState("MQTT", "connected")
    } else {
        runIn(1, connect)
    }
}

def pollDevice(delay=1) {
    poll()
    sendEvent(name:"lastPoll", value: nowFmt(), isStateChange:true)
}

def logDebug(msg) {
    if (state.debug) log.debug "[${timestamp()}] ${msg}"
}

def connect() {
    logDebug("connect(): Preparing MQTT reconnect...")
    unsubscribe_MQTT()
    interfaces.mqtt.disconnect()
    if (!state.clientId || !state.clientSecret) {
        state.clientId = parent?.settings?.Client_ID
        state.clientSecret = parent?.settings?.Client_Secret
        logDebug("connect(): Loaded creds from parent: clientId=${state.clientId}, secretLen=${state.clientSecret?.length()}")
    }
    if (!state.subnetId) {
        log.error "connect(): subnetId is NULL — cannot subscribe to valid topic!"
        return
    }
    def mqtt_ID = "${state.subnetId}_${new Date().format('HHmmss')}_${new Random().nextInt(1000)}"
    establish_MQTT_connection(mqtt_ID)
}

def establish_MQTT_connection(mqtt_ID) {
    def MQTT = "disconnected"
    try {
        def brokerIP  = state.localHubIP ?: parent?.settings?.localHubIP
        def broker    = "tcp://${brokerIP}:18080"
        def username  = state.clientId
        def password  = state.clientSecret
        def topic     = "ylsubnet/${state.subnetId}/+/report"
        logDebug("MQTT connect: broker=${broker}, mqtt_ID=${mqtt_ID}, username=${username}, secretLen=${password?.length()}, topic=${topic}")
        if (!username || !password) {
            log.error "Missing username or password — cannot connect"
            return
        }
        if (!state.subnetId) {
            log.error "Missing subnetId — cannot subscribe"
            return
        }
        interfaces.mqtt.connect(broker, mqtt_ID, username, password, cleanSession: 1, keepAlive: 60)
        logDebug("MQTT isConnected() after connect: ${interfaces.mqtt.isConnected()}")
        runInMillis(2000, "delayedSubscribe")
        MQTT = "connected"
    } catch (e) {
        log.error("[${timestamp()}] establish_MQTT_connection() Exception: ${e.getMessage()}")
        if (state.debug) e.printStackTrace()
    } finally {
        rememberState("MQTT", MQTT)
        rememberState("online", interfaces.mqtt.isConnected())
        lastResponse("Local API MQTT ${MQTT}")
    }
}

def delayedSubscribe() {
    if (!state.subnetId) {
        log.error "Cannot subscribe: subnetId is NULL"
        return
    }
    if (!interfaces.mqtt.isConnected()) {
        logDebug("Skipping subscribe — not connected")
        return
    }
    def topic = "ylsubnet/${state.subnetId}/+/report"
    logDebug("Subscribing to ${topic}, isConnected=${interfaces.mqtt.isConnected()}")
    try {
        interfaces.mqtt.subscribe(topic, 1)
        logDebug("Subscribe complete. Connected? ${interfaces.mqtt.isConnected()}")
    } catch (e) {
        log.error("[${timestamp()}] Subscription error: ${e.getMessage()}")
        if (state.debug) e.printStackTrace()
    }
}

def unsubscribe_MQTT() {
    if (!state.subnetId) return
    def topic = "ylsubnet/${state.subnetId}/+/report"
    try {
        interfaces.mqtt.unsubscribe(topic)
        logDebug("Unsubscribed from ${topic}")
    } catch (e) {
        log.error("Unsubscribe error: ${e.getMessage()}")
        if (state.debug) e.printStackTrace()
    }
}

/* =============================== MQTT =============================== */

def mqttClientStatus(String message) {
    logDebug("mqttClientStatus(): ${message}")
    if (message.toLowerCase().startsWith("error")) {
        log.error("[${timestamp()}] MQTT Error: ${message} | state: subnetId=${state.subnetId}, brokerIP=${state.localHubIP}, clientId=${state.clientId}")
        try {
            log.info "[${timestamp()}] Disconnecting from MQTT (isConnected=${interfaces.mqtt.isConnected()})"
            interfaces.mqtt.disconnect()
            sendEvent(name:"MQTT", value: "disconnected", isStateChange:true)
            state.MQTT = "disconnected"
        } catch (e) {
            log.error("[${timestamp()}] mqttClientStatus(): Disconnect exception: ${e.getMessage()}")
        }
        runIn(30, connect)
    } else {
        logDebug("MQTT status update (non-error): ${message}")
    }
}

def parse(message) {
    state.lastMsgTs = now()
    def parsed = interfaces.mqtt.parseMessage(message)

    logDebug("MQTT Topic: ${parsed?.topic}")
    logDebug("MQTT Payload: ${parsed?.payload}")

    try {
        def json = new groovy.json.JsonSlurper().parseText(parsed?.payload)
        def devId = json?.deviceId

        if (devId && json.data instanceof Map) {
            if (!state["lastData_${devId}"]) {
                state["lastData_${devId}"] = [:]
            }
            def lastData = state["lastData_${devId}"]

            // Cache all fields, merge new data accordingly
            json.data.each { key, value ->
                if (value != null) lastData[key] = value
            }
            // Restore missing keys
            lastData.each { key, value ->
                if (!json.data.containsKey(key)) json.data[key] = value
            }
            // Re-encode enriched JSON payload
            parsed.payload = groovy.json.JsonOutput.toJson(json)
        }
    } catch (e) {
        logDebug("Failed to parse or enrich MQTT payload: ${e.message}")
    }

    if (parsed?.topic?.startsWith("ylsubnet/${state.subnetId}")) {
        def device = parent.passMQTT(parsed)
        if (device) logDebug("Passed MQTT message to device ${device}")
        else        logDebug("No device matched for MQTT payload.")
    } else {
        logDebug("Received MQTT message not for this subnet ID.")
    }
}

/* ============================== Helpers ============================== */

def rememberState(name, value, unit=null) {
    if (state."$name" != value) {
        state."$name" = value
        if (unit==null) sendEvent(name:"$name", value: "$value", isStateChange:true)
        else            sendEvent(name:"$name", value: "$value", unit: "$unit", isStateChange:true)
    }
}

def reset() {
    state.clear()
    state.driverVersion = clientVersion() // keep in state only
    rememberState("firmware", "N/A")
    connect()
    log.warn "Device reset"
}

def lastResponse(value) {
    sendEvent(name:"lastResponse", value: "$value", isStateChange:true)
}

/* ---- Date/Time + Scale helpers (pulled from parent) ---- */
private String activeFmt() { parent?.getDateTimeFormat() ?: "MM/dd/yyyy hh:mm:ss a" }
private String activeScale() { parent?.temperatureScale() ?: "F" }
private String nowFmt() { new Date().format(activeFmt(), location?.timeZone) }
private String timestamp() { nowFmt() }

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

def mqttWatchdog() {
    def connected = interfaces.mqtt.isConnected()
    if (!connected) {
        logDebug("MQTT watchdog: Connection is DOWN — triggering reconnect...")
        connect()
    } else {
        if (state.lastMsgTs && now() - state.lastMsgTs > 60000) {
            logDebug("No MQTT messages for ${(now()-state.lastMsgTs)/1000} seconds — connection may be stale")
        }
        logDebug("MQTT watchdog: connection healthy")
    }
}
