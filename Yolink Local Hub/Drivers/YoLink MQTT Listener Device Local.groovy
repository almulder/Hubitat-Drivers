/**
 *  YoLink™ MQTT Listener (Local API Edition)
 *  © 2025 Albert Mulder
 *
 *  1.2.0 - Audit fixes: reset keeps subnet/hub IP so MQTT keeps working; watchdog re-subscribes and
 *          reconnects a silent connection; reconnects back off (30s up to 10 min) and no longer drop a
 *          healthy connection; stopped re-injecting cached fields into partial messages (replayed old
 *          alerts) and caching unselected devices; broker secret read from the app instead of device
 *          state; no forced isStateChange events; per-message timestamp kept in memory; debug logging
 *          turns itself off after 30 minutes. Silent-connection reconnect after 6h (was 3h); a reconnect
 *          already pending is not rescheduled (backoff no longer doubles twice per failure).
 *  1.1.1 - Updated for HPM
 *  1.1.0 - Initial working driver
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

def clientVersion() { "1.2.0" }
def copyright()     { "© 2025 Albert Mulder" }
def driverName()    { "YoLink™ MQTT Listener (Local API Edition)" }

// Last MQTT message time per device id, kept in memory so each message doesn't rewrite device state
@Field static ConcurrentHashMap<String, Long> lastMsgTs = new ConcurrentHashMap<String, Long>()

@Field static final int RECONNECT_MIN_SECS = 30
@Field static final int RECONNECT_MAX_SECS = 600
@Field static final long STALE_MS = 6L * 60L * 60L * 1000L   // no messages for 6h => reconnect

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
        logDebug("Device token changed")
        state.token = token
    } else {
        logDebug("Device token unchanged")
    }
}

// clientId/clientSecret parameters are kept for compatibility but no longer stored:
// the broker credentials are read from the parent app at connect time.
void ServiceSetup(Hubitat_dni, subnetId, devname, devtype, devtoken, devId, localHubIP=null, clientId=null, clientSecret=null) {
    state.my_dni       = Hubitat_dni
    state.subnetId     = subnetId
    state.name         = devname
    state.type         = devtype
    state.token        = devtoken
    state.devId        = devId
    state.localHubIP   = localHubIP   ?: creds()?.localHubIP
    state.remove("clientSecret")
    state.remove("clientId")
    rememberState("devId", devId)

    logDebug("ServiceSetup(): subnetId=${state.subnetId}, localHubIP=${state.localHubIP}")
    // The app calls initialize() right after this, which connects; don't connect twice.
}

public def getSetup() {
    [
      my_dni: state.my_dni, subnetId: state.subnetId, name: state.name, type: state.type,
      devId: state.devId, localHubIP: state.localHubIP
    ]
}

public def isSetup() {
    Map c = creds()
    return (state.my_dni && state.subnetId && state.name && state.type && state.token && state.devId && c?.clientId && c?.clientSecret)
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
    if (state.debug) runIn(1800, "logsOff")
    cleanupLegacyState()
}

def initialize() {
    log.trace "Initializing: Establishing MQTT connection to YoLink Local API"
    cleanupLegacyState()
    state.reconnectDelay = RECONNECT_MIN_SECS
    connect()
    runEvery1Minute(mqttWatchdog)
    if (state.debug) runIn(1800, "logsOff")
}

def uninstalled() {
    unschedule()
    try { interfaces.mqtt.disconnect() } catch (ignored) {}
    log.warn "MQTT Listener Uninstalled"
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("debug", [value: "false", type: "bool"])
    state.debug = false
}

// Earlier versions cached every device's last payload and the broker secret in state
private void cleanupLegacyState() {
    try {
        List stale = state.keySet().findAll { it?.toString()?.startsWith("lastData_") } as List
        stale.each { state.remove(it) }
    } catch (ignored) {}
    state.remove("clientSecret")
    state.remove("clientId")
    state.remove("lastMsgTs")
}

/* ============================ Poll / Connect ========================= */

def poll() {
    def isConn = interfaces.mqtt.isConnected()
    rememberState("online", isConn.toString())
    logDebug("poll(): MQTT isConnected() = ${isConn}")
    if (isConn) {
        rememberState("MQTT", "connected")
    } else {
        runIn(1, "reconnectIfNeeded")
    }
}

def pollDevice(delay=1) {
    poll()
    sendEvent(name:"lastPoll", value: nowFmt())
}

def logDebug(msg) {
    if (state.debug) log.debug "[${timestamp()}] ${msg}"
}

private Map creds() {
    try { return parent?.getMqttCredentials() } catch (ignored) { return null }
}

// Scheduled/automatic reconnects go through here so a pending retry can't drop a healthy connection
def reconnectIfNeeded() {
    if (interfaces.mqtt.isConnected() && state.subscribed) {
        logDebug("reconnectIfNeeded(): already connected")
        return
    }
    connect()
}

// Also the "connect" command: always reconnects
def connect() {
    logDebug("connect(): Preparing MQTT reconnect...")
    unschedule("reconnectIfNeeded")
    try { unsubscribe_MQTT() } catch (ignored) {}
    try { interfaces.mqtt.disconnect() } catch (ignored) {}
    state.subscribed = false

    Map c = creds()
    if (!state.subnetId)   state.subnetId   = c?.subnetId
    if (!state.localHubIP) state.localHubIP = c?.localHubIP
    if (!state.subnetId) {
        log.error "connect(): subnetId is NULL — cannot subscribe to valid topic! Re-open the YoLink app and click through to Done."
        return
    }
    def mqtt_ID = "${state.subnetId}_${new Date().format('HHmmss')}_${new Random().nextInt(1000)}"
    establish_MQTT_connection(mqtt_ID, c)
}

def establish_MQTT_connection(mqtt_ID, Map c = null) {
    def MQTT = "disconnected"
    try {
        c = c ?: creds()
        def brokerIP  = state.localHubIP ?: c?.localHubIP
        def broker    = "tcp://${brokerIP}:18080"
        def username  = c?.clientId
        def password  = c?.clientSecret
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
        interfaces.mqtt.connect(broker, mqtt_ID, username, password, cleanSession: true, keepAlive: 60)
        logDebug("MQTT isConnected() after connect: ${interfaces.mqtt.isConnected()}")
        runInMillis(2000, "delayedSubscribe")
        MQTT = "connected"
    } catch (e) {
        log.error("[${timestamp()}] establish_MQTT_connection() Exception: ${e.getMessage()}")
        if (state.debug) e.printStackTrace()
        scheduleReconnect()
    } finally {
        rememberState("MQTT", MQTT)
        rememberState("online", interfaces.mqtt.isConnected().toString())
        lastResponse("Local API MQTT ${MQTT}")
    }
}

def delayedSubscribe() {
    if (!state.subnetId) {
        log.error "Cannot subscribe: subnetId is NULL"
        return
    }
    if (!interfaces.mqtt.isConnected()) {
        logDebug("Skipping subscribe — not connected (watchdog will retry)")
        return
    }
    def topic = "ylsubnet/${state.subnetId}/+/report"
    logDebug("Subscribing to ${topic}, isConnected=${interfaces.mqtt.isConnected()}")
    try {
        interfaces.mqtt.subscribe(topic, 1)
        state.subscribed = true
        state.reconnectDelay = RECONNECT_MIN_SECS   // healthy again: reset backoff
        state.remove("nextReconnectAt")
        unschedule("reconnectIfNeeded")
        lastMsgTs[device.id as String] = now()          // start the silence timer from the subscribe
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
        logDebug("Unsubscribe error: ${e.getMessage()}")
    }
}

// Exponential backoff: 30s, 60s, 120s ... capped at 10 minutes
private void scheduleReconnect() {
    // A connect that throws AND posts an error status would otherwise schedule (and double) twice
    Long pending = state.nextReconnectAt as Long
    if (pending && now() < pending) {
        logDebug("Reconnect already scheduled; not rescheduling")
        return
    }
    int delay = ((state.reconnectDelay ?: RECONNECT_MIN_SECS) as int)
    delay = Math.max(RECONNECT_MIN_SECS, Math.min(RECONNECT_MAX_SECS, delay))
    state.nextReconnectAt = now() + (delay * 1000L)
    runIn(delay, "reconnectIfNeeded")
    state.reconnectDelay = Math.min(RECONNECT_MAX_SECS, delay * 2)
    logDebug("Reconnect scheduled in ${delay}s")
}

/* =============================== MQTT =============================== */

def mqttClientStatus(String message) {
    logDebug("mqttClientStatus(): ${message}")
    if (message.toLowerCase().startsWith("error")) {
        // Log loudly only on the first failure of a streak; repeats while backing off are warnings
        boolean firstFailure = ((state.reconnectDelay ?: RECONNECT_MIN_SECS) as int) <= RECONNECT_MIN_SECS
        String msg = "[${timestamp()}] MQTT Error: ${message} | state: subnetId=${state.subnetId}, brokerIP=${state.localHubIP}"
        if (firstFailure) log.error(msg) else log.warn(msg)
        try {
            logDebug("Disconnecting from MQTT (isConnected=${interfaces.mqtt.isConnected()})")
            interfaces.mqtt.disconnect()
            state.subscribed = false
            rememberState("MQTT", "disconnected")
        } catch (e) {
            log.error("[${timestamp()}] mqttClientStatus(): Disconnect exception: ${e.getMessage()}")
        }
        scheduleReconnect()
    } else {
        logDebug("MQTT status update (non-error): ${message}")
    }
}

def parse(message) {
    lastMsgTs[device.id as String] = now()
    def parsed = interfaces.mqtt.parseMessage(message)

    logDebug("MQTT Topic: ${parsed?.topic}")
    logDebug("MQTT Payload: ${parsed?.payload}")

    // Messages are passed through unchanged. Drivers only update the fields a message actually
    // contains, so partial reports (battery/heartbeat) no longer need older fields merged in —
    // merging them used to replay stale "alert" states.
    if (parsed?.topic?.startsWith("ylsubnet/${state.subnetId}")) {
        def routed = parent.passMQTT(parsed)
        if (routed) logDebug("Passed MQTT message to device ${routed}")
        else        logDebug("No device matched for MQTT payload.")
    } else {
        logDebug("Received MQTT message not for this subnet ID.")
    }
}

/* ============================== Helpers ============================== */

def rememberState(name, value, unit=null) {
    if (state."$name" != value) {
        state."$name" = value
        if (unit==null) sendEvent(name:"$name", value: "$value")
        else            sendEvent(name:"$name", value: "$value", unit: "$unit")
    }
}

def reset() {
    // Keep what is needed to reconnect; everything else is cleared
    Map c = creds()
    Map keep = [
        my_dni    : state.my_dni,
        subnetId  : state.subnetId ?: c?.subnetId,
        localHubIP: state.localHubIP ?: c?.localHubIP,
        name      : state.name,
        type      : state.type,
        token     : state.token,
        devId     : state.devId
    ]
    state.clear()
    keep.each { k, v -> if (v != null) state[k] = v }
    state.driverVersion = clientVersion() // keep in state only
    state.debug = (settings?.debug == true)
    state.reconnectDelay = RECONNECT_MIN_SECS
    rememberState("firmware", "N/A")
    connect()
    log.warn "Device reset"
}

def lastResponse(value) {
    sendEvent(name:"lastResponse", value: "$value")
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
        Long next = state.nextReconnectAt as Long
        if (next && now() < next) {
            logDebug("MQTT watchdog: connection DOWN — reconnect already scheduled")
            return
        }
        logDebug("MQTT watchdog: Connection is DOWN — triggering reconnect...")
        scheduleReconnect()
        return
    }
    if (!state.subscribed) {
        logDebug("MQTT watchdog: connected but not subscribed — subscribing")
        delayedSubscribe()
        return
    }
    Long last = lastMsgTs[device.id as String]
    if (last && now() - last > STALE_MS) {
        log.info "MQTT watchdog: no messages for ${Math.round((now() - last) / 60000)} minutes — reconnecting"
        lastMsgTs[device.id as String] = now()
        connect()
        return
    }
    logDebug("MQTT watchdog: connection healthy")
}
