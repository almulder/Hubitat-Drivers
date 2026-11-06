/**
 *  YoLink™ Finger / Garage Door Controller 2 (Local API Edition)
 *  © 2026 Albert Mulder
 *
 *  1.2.2 - Audit fixes:
 *   - "pushed" now really fires only when a press is confirmed (Local API 000000, or the MQTT setState that
 *     follows a failed/unanswered request); it used to fire as soon as a command was requested, even when the
 *     door-state check then ignored it or the hub rejected it.
 *   - With a bound door sensor, the door no longer flips back to "open" two seconds into a close (the contact
 *     is still open then): the driver now waits for the contact to reach the target state, checking every 5s,
 *     and at the timeout reports what the contact actually shows instead of always "unknown".
 *   - reset keeps the device id, token and binding, so the Finger keeps working without re-running the app.
 *   - New device token accepted from the app (setDeviceToken) and picked up after each Local API call.
 *   - The YoLink client secret is no longer stored in device state.
 *   - No response from the Local API now clears "busy" instead of leaving it set until the timeout.
 *   - A report without a battery or firmware reading leaves them unchanged (was written as blank).
 *   - appStatus attribute shows what to do while the YoLink app is out of date (as the other YoLink drivers).
 *   - Debug auto-off now also updates state, and lastResponse is not forced as a change on every poll.
 *  1.1.14
 *  Key behavior:
 *   - "pushed: 1" is emitted ONLY when the action is CONFIRMED:
 *       (A) Local API returns code 000000, OR
 *       (B) MQTT confirms event Finger.setState with data.state in [stop,start,toggle]
 *
 *   - Hubitat open/close/push requests set a pending confirmation window.
 *     If local API fails but MQTT later confirms, the pushed event still fires.
 *
 *  Other:
 *   - Preferences-only debug toggle
 *   - Rate-limit + single-slot queue
 *   - Optional retry once on 000201
 *   - pollDevice(Integer) compatibility
 *   - Optional retry once on 000201 with backoff
 *   - Debounced busy attribute for WebCoRE to wait for completion
 *
 *  1.1.8 - Enhancements:
 *   - MQTT echo suppression so Hubitat-triggered actions don’t get logged as “YoLink App”
 *   - STRICT MQTT “push” detection to avoid phantom “YoLink App” presses:
 *        - Only treat event == "Finger.setState" AND data.state in [stop,start,toggle] as a press
 *   - Button events for WebCoRE:
 *        - capability PushableButton
 *        - numberOfButtons = 1
 *        - sendEvent pushed=1 on:
 *            • Hubitat push/open/close
 *            • YoLink app press (MQTT stop/start/toggle)
 *  1.1.4 - Enhancements:
 *   - Command rate-limit / queue to reduce 000201 "Cannot connect to the device" when pressing too fast
 *   - MQTT echo suppression improved: suppress MQTT "push" logs while a local command is in-flight/cooldown
 *  1.1.2 - Updated to ignore (Contact not found isses) - Can now be used without a contact
 *  1.1.0 - Intital Driver
 */


import groovy.json.JsonSlurper


/* ============================ Version / Info ============================ */
def clientVersion() { return "1.2.2" }
def driverName()    { return "YoLink™ Finger Device (Local API Edition)" }
def copyright()     { return "<br>© 2025-" + new Date().format("yyyy") + " Albert Mulder. All rights reserved." }


metadata {
    definition (name: "YoLink Finger Device Local", namespace: "almulder", author: "Albert Mulder", singleThreaded: true) {
        capability "Initialize"
        capability "Polling"
        capability "Momentary"
        capability "ContactSensor"
        capability "GarageDoorControl"
        capability "SignalStrength"
        capability "Battery"
        capability "PushableButton"

        command "reset"
        command "scan"
        command "timestampFormat", [[name:"timestampFormat",type:"STRING", description:"Formatting template for event timestamp values."]]
        command "bind", [[name:"bind",type:"STRING", description:"Device ID (YoLink devId) of Garage Door Sensor (DoorSensor) to bind."]]
        command "unbind"
        command "doorTimeout", [[name:"doortimeout",type:"NUMBER", description:"Time in seconds before setting door=unknown. 15..120. Default=45"]]

        attribute "online", "String"
        attribute "devId", "String"
        attribute "firmware", "String"
        attribute "lastPoll", "String"
        attribute "lastResponse", "String"
        attribute "appStatus", "String"      // "OK", or what to do when the YoLink app is out of date
        attribute "door", "String"
        attribute "moving", "String"
        attribute "signal", "String"
        attribute "rssi", "String"

        attribute "bound_devId", "String"
        attribute "bound_name", "String"

        attribute "lastAction", "String"
        attribute "lastActionSource", "String"

        // WebCoRE-friendly “wait until not busy”
        attribute "busy", "String"          // "true" / "false"
        attribute "busyReason", "String"    // e.g., "hubitat:open", "hubitat:push", "mqtt:push", "retry"
    }
}

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

    input name: "showInterimState", type: "bool", title: "Show interim states (opening/closing)", defaultValue: true
    input name: "ignoreDoorState",  type: "bool", title: "Ignore door state checks (always allow open/close)", defaultValue: false
    input name: "doorTimeoutSec",   type: "number", title: "Door move timeout (sec)", defaultValue: 45, range: "15..120"

    input name: "mqttEchoSuppressSec", type: "number", title: "Suppress MQTT echo after Hubitat command (sec)", defaultValue: 10, range: "0..30"

    input name: "minCommandGapMs", type: "number", title: "Minimum time between commands (ms)", defaultValue: 3000, range: "0..10000"
    input name: "retryOn201", type: "bool", title: "Retry once if 000201 (Cannot connect)", defaultValue: true

    // Busy (debounced) settings
    input name: "busyTimeoutSec", type: "number", title: "Busy auto-clear timeout (sec)", defaultValue: 20, range: "5..120"
    input name: "busyDebounceMs", type: "number", title: "Busy debounce (ms)", defaultValue: 3000, range: "0..5000"
}

/* ============================ Lifecycle ============================ */
def installed() { runIn(2, "initialize") }
def updated()   { runIn(2, "initialize") }

def initialize() {
    if (settings?.debug == true) runIn(1800, "logsOff")

    if (!device.currentValue("door"))    sendEvent(name:"door", value:"unknown")
    if (!device.currentValue("contact")) sendEvent(name:"contact", value:"unknown")
    if (!device.currentValue("moving"))  sendEvent(name:"moving", value:"false")

    // Button setup (critical for WebCoRE / Rule Machine)
    sendEvent(name: "numberOfButtons", value: 1)

    // Busy defaults
    if (device.currentValue("busy") == null) sendEvent(name:"busy", value:"false")

    pollDevice()
}

def logsOff() {
    device.updateSetting("debug",[value:"false",type:"bool"])
    log.info "${device.displayName}: debug logging disabled"
}

/* ============================ Setup hook ============================ */

// True while the parent app is older than 1.3.0 (those apps pass the broker credentials to ServiceSetup
// and have no getDeviceTokenFor()). Earlier versions of this driver kept the secret in state, so its
// presence marks a device the new app hasn't set up yet.
private boolean legacyApp() { state.legacyApp || state.clientSecret }

// Device-page status only; the MQTT Listener is the one that logs the warning
private void updateAppStatus() {
    String v = legacyApp() ? "YoLink app is older than 1.3.0, or has not been set up since the last update. Fix: in HPM run Repair on 'YoLink Local Hub Connector', then open the YoLink app and click Done." : "OK"
    if (device.currentValue("appStatus") != v) sendEvent(name: "appStatus", value: v)
}

// After a Local API call: the app may have refreshed this device's token during that call
private void pickUpLatestToken() {
    if (legacyApp()) return
    String latestToken = parent.getDeviceTokenFor(state.devId?.toString())
    if (latestToken && latestToken != state.token) state.token = latestToken
}

void ServiceSetup(Hubitat_dni, subnetId, devname, devtype, devtoken, devId,
                  localHubIP=null, clientId=null, clientSecret=null) {
    state.my_dni       = Hubitat_dni
    state.subnetId     = subnetId
    state.name         = devname
    state.type         = devtype
    state.token        = devtoken
    state.devId        = devId
    state.localHubIP   = localHubIP
    if (clientSecret) state.legacyApp = true else state.remove("legacyApp")
    state.remove("clientId")       // broker credentials are only needed by the MQTT Listener
    state.remove("clientSecret")

    rememberState("devId", devId)
    updateAppStatus()
}

// Called by the app when the Local Hub issues a new device token
void setDeviceToken(token) {
    if (token && state.token != token) {
        state.token = token
        logDebug("Device token updated")
    }
}

/* ============================ Commands ============================ */

def open()  { requestCommand("open") }
def close() { requestCommand("close") }
def push()  { requestCommand("push") }  // Momentary

/**
 * Rate-limited command request entry point.
 * Uses a single-slot queue (latest command wins) to avoid hammering the local hub.
 */
private void requestCommand(String func) {
    func = (func ?: "")?.toString()?.toLowerCase()

    Long lastSent = (state.lastCmdSentTs ?: 0L) as Long
    Integer gap = (settings?.minCommandGapMs ?: 3000) as Integer
    if (gap < 0) gap = 0

    Long age = now() - lastSent
    if (lastSent > 0 && age < gap) {
        state.pendingCmd = func
        Long delayMs = (gap - age)
        Integer delaySec = Math.max(3, Math.round(delayMs / 1000.0f) as Integer)
        log.warn "${device.displayName}: Command '${func}' queued (${delayMs}ms gap). Will run in ~${delaySec}s"

        setBusy(true, "hubitat:queued:${func}")
        runIn(delaySec, "runPendingCommand")
        return
    }

    doToggle(func, false)
}

def runPendingCommand() {
    def func = state.pendingCmd
    state.remove("pendingCmd")
    if (!func) return
    logDebug("runPendingCommand(): executing '${func}'")
    doToggle(func.toString(), false)
}

/**
 * Actual command execution.
 */
private void doToggle(String func, boolean isRetry) {
    func = (func ?: "push")?.toString()?.toLowerCase()

    def currentDoor = doorState()
    boolean ignoreDoor = (settings?.ignoreDoorState == true)
    boolean interim    = (settings?.showInterimState != false)

    logDebug("doToggle(${func}): door=${currentDoor}, ignoreDoorState=${ignoreDoor}, interim=${interim}, retry=${isRetry}")

    // Optional door sanity checks if bound to DoorSensor. Checked before anything is recorded, so an
    // ignored command leaves no "pushed" event or lastAction behind.
    if (boundToDevice() && !ignoreDoor) {
        switch (func) {
            case "open":
                if (!(currentDoor in ["closed","closing","unknown"])) {
                    log.info "${device.displayName}: Open ignored; door is ${currentDoor}"
                    scheduleClearBusyDebounced("ignored:${func}")
                    return
                }
                break
            case "close":
                if (!(currentDoor in ["open","opening","unknown"])) {
                    log.info "${device.displayName}: Close ignored; door is ${currentDoor}"
                    scheduleClearBusyDebounced("ignored:${func}")
                    return
                }
                break
        }
    }

    // Mark local command attempt (suppresses MQTT echo + keeps busy true until MQTT or timeout)
    markLocalCommand(func)

    // Busy starts when we *attempt* a command (covers success/fail/retry)
    setBusy(true, (isRetry ? "retry:${func}" : "hubitat:${func}"))

    if (!isRetry) {
        switch (func) {
            case "push":  log.info "${device.displayName}: Finger activated (push) via Hubitat"; break
            case "open":  log.info "${device.displayName}: Door open requested via Hubitat."; break
            case "close": log.info "${device.displayName}: Door close requested via Hubitat."; break
            default:      log.info "${device.displayName}: Command requested via Hubitat: ${func}"; break
        }
        rememberAction(func, "hubitat")
    } else {
        log.warn "${device.displayName}: Retrying '${func}' after 000201"
    }

    // Door movement tracking if bound to a DoorSensor: remember where the door should end up, and
    // optionally show opening/closing until the contact gets there
    if (boundToDevice() && !isRetry) startMove(interim)

    // Record send time for rate limiting
    state.lastCmdSentTs = now()

    def request = [
        method      : "${state.type}.toggle",
        targetDevice: "${state.devId}",
        token       : "${state.token}"
    ]

    try {
        def object = parent.pollAPI(request, state.name, state.type)
        pickUpLatestToken()
        updateAppStatus()
        if (!object) {
            lastResponse("No response from Local API")
            scheduleClearBusyDebounced("noResponse")
            return
        }

        logDebug("doToggle(): pollAPI response: ${object}")

        if (successful(object)) {
            def rssi = object?.data?.loraInfo?.signal
            fmtSignal(rssi)
            lastResponse("Toggle sent")
            rememberState("online","true")
            rememberState("lastPoll", nowFmt())
            confirmLocalPress("api")
            runIn(2, "refreshSensor")
            // We do NOT clear busy here; we wait for MQTT setState (or timeout) = “completion”
        } else {
            def code = object?.code?.toString()
            log.warn "Toggle failed: ${code} - ${parent.translateCode(code)}"
            lastResponse("Toggle failed: ${code}")

            // Backoff+retry once on 000201
            if (code == "000201" && settings?.retryOn201 == true && !isRetry) {
                extendLocalCommandWindow()
                setBusy(true, "retryScheduled:${func}")
                runIn(3, "retryLastCommand")
            } else {
                // Failed and no retry -> clear busy soon (debounced)
                scheduleClearBusyDebounced("failed:${code}")
            }
        }
    } catch (e) {
        log.error "doToggle(): exception: ${e}"
        lastResponse("Toggle exception")
        scheduleClearBusyDebounced("exception")
    }
}

def retryLastCommand() {
    def func = (state.lastLocalCmdAction ?: "push")?.toString()
    doToggle(func, true)
}

private void markLocalCommand(String func) {
    state.lastLocalCmdTs = now()
    state.lastLocalCmdAction = (func ?: "")?.toString()?.toLowerCase()
    state.localCmdConfirmed = false

    // “In-flight” window covers delayed MQTT updates (and failures/retries)
    state.localCmdInFlightUntil = now() + 30000L

    logDebug("markLocalCommand(action=${state.lastLocalCmdAction}, ts=${state.lastLocalCmdTs}, inFlightUntil=${state.localCmdInFlightUntil})")
}

// A retry keeps the echo window open long enough for the retried command's MQTT update
private void extendLocalCommandWindow() {
    state.localCmdInFlightUntil = now() + 30000L
}

// First confirmation of a Hubitat command (Local API success, or its MQTT setState) emits the button press
private void confirmLocalPress(String via) {
    if (state.localCmdConfirmed) return
    state.localCmdConfirmed = true
    logDebug("Hubitat command confirmed via ${via}")
    emitButtonPushed("hubitat")
}

/* ============================ Polling ============================ */

def poll() { pollDevice() }

// No default args here (Hubitat compile quirk). Provide both signatures explicitly.
def pollDevice() {
    pollDeviceInternal()
}
def pollDevice(Integer ignored) {
    pollDeviceInternal()
}
private void pollDeviceInternal() {
    getDeviceState()
    refreshSensor()
}

def getDeviceState() {
    logDebug("getDeviceState()")

    def request = [
        method      : "${state.type}.getState",
        targetDevice: "${state.devId}",
        token       : "${state.token}"
    ]

    try {
        def object = parent.pollAPI(request, state.name, state.type)
        pickUpLatestToken()
        updateAppStatus()
        if (!object) {
            lastResponse("No response from Local API")
            return
        }

        if (successful(object)) {
            rememberReadings(object?.data)
            rememberState("online","true")
            rememberState("lastPoll", nowFmt())
            lastResponse("State updated")
        } else {
            rememberState("online","false")
            logDebug("getDeviceState(): ${object?.code} - ${parent.translateCode(object?.code)}")
        }
    } catch (e) {
        rememberState("online","false")
        log.error "getDeviceState(): exception: ${e}"
    }
}

// Battery/firmware/signal from a getState or MQTT payload; missing readings leave the old value
private void rememberReadings(data) {
    def battery  = parent.batterylevel(data?.battery)
    def firmware = (data?.version ?: "")?.toString()?.toUpperCase()
    if (battery != null) rememberState("battery", battery, "%")
    if (firmware)        rememberState("firmware", firmware)
    fmtSignal(data?.loraInfo?.signal)
}

/* ============================ MQTT / Realtime ============================ */

def parse(topic) {
    if (topic?.payload) processStateData(topic.payload)
}

def processStateData(String payload) {
    rememberState("online","true")

    Map object
    try {
        // Fully-qualified so imports can’t break compilation on Hubitat
        object = new groovy.json.JsonSlurper().parseText(payload) as Map
    } catch (e) {
        logDebug("processStateData: bad JSON: ${e?.message}")
        return
    }

    def devId = (object?.deviceId ?: object?.data?.deviceId ?: object?.targetDevice) as String
    if (!devId || state.devId != devId) return

    // Deduplicate by msgid if present (prevents repeats)
    def msgid = (object?.msgid ?: "")?.toString()
    if (msgid && msgid == (state.lastMqttMsgid ?: "")) {
        logDebug("Duplicate MQTT msgid suppressed: ${msgid}")
        return
    }
    if (msgid) state.lastMqttMsgid = msgid

    def eventFull = (object?.event ?: "")?.toString()
    def event = (eventFull?.tokenize('.')?.last() ?: eventFull) ?: "unknown"

    logDebug("processStateData(event=${event} full=${eventFull}): ${payload}")

    switch(event) {
        case "Report":
        case "setState":
            rememberReadings(object?.data)
            rememberState("lastPoll", nowFmt())

            def action = detectAction(object)
            if (action == "push") {
                // IMPORTANT: even if it’s an echo, it still means the device “completed” -> clear busy
                clearBusyOnCompletion("mqtt:setState")

                if (isEchoOfLocalCommand(action)) {
                    // Our own command: this confirms it if the Local API call didn't
                    confirmLocalPress("mqtt")
                } else {
                    log.info "${device.displayName}: Finger activated (push) via YoLink App"
                    rememberAction("push", "mqtt")
                    emitButtonPushed("yolink")
                }
            }
            break

        default:
            break
    }
}

/**
 * STRICT detection to avoid phantom presses:
 * Only treat the confirmed press signature as a push:
 *   event == "Finger.setState" AND data.state in [stop,start,toggle]
 */
private String detectAction(Map obj) {
    def ev = (obj?.event ?: "")?.toString()
    if (!ev.equalsIgnoreCase("Finger.setState")) return null

    def s = obj?.data?.state?.toString()?.trim()?.toLowerCase()
    if (s in ["stop","start","toggle"]) return "push"

    return null
}

private boolean isEchoOfLocalCommand(String action) {
    // If we are inside the in-flight window, suppress MQTT push as echo
    Long until = (state.localCmdInFlightUntil ?: 0L) as Long
    if (until > 0L && now() <= until) {
        if (action?.toString()?.toLowerCase() == "push") return true
    }

    // Secondary simple time window
    Integer sup = (settings?.mqttEchoSuppressSec ?: 10) as Integer
    if (sup <= 0) return false

    Long lastTs = (state.lastLocalCmdTs ?: 0L) as Long
    if (lastTs == 0L) return false

    def ageMs = now() - lastTs
    if (ageMs < 0) ageMs = 0

    if (ageMs <= (sup * 1000L)) {
        if (action?.toString()?.toLowerCase() == "push") return true
    }
    return false
}

/* ============================ Busy (debounced) ============================ */

private void setBusy(boolean on, String reason) {
    String target = on ? "true" : "false"

    state.lastBusySetTs = now()
    sendEvent(name: "busy", value: target, isStateChange: true)
    if (reason != null) sendEvent(name: "busyReason", value: reason.toString(), isStateChange: true)

    if (on) {
        Integer t = (settings?.busyTimeoutSec ?: 20) as Integer
        if (t < 5) t = 5
        if (t > 120) t = 120
        // auto-clear safety net
        runIn(t, "busyTimeoutFired")
    }
}

def busyTimeoutFired() {
    // Debounced clear (only if no recent busy set)
    scheduleClearBusyDebounced("timeout")
}

private void clearBusyOnCompletion(String reason) {
    // completion clears busy (debounced so it won’t flap)
    scheduleClearBusyDebounced(reason)
}

private void scheduleClearBusyDebounced(String reason) {
    Integer d = (settings?.busyDebounceMs ?: 3000) as Integer
    if (d < 0) d = 0
    state.pendingBusyClearReason = reason
    // runIn only supports seconds; if 0ms, clear next tick
    Integer sec = Math.max(1, Math.round(d / 1000.0f) as Integer)
    runIn(sec, "debouncedBusyClear")
}

def debouncedBusyClear() {
    Integer d = (settings?.busyDebounceMs ?: 3000) as Integer
    if (d < 0) d = 0

    Long last = (state.lastBusySetTs ?: 0L) as Long
    Long age = now() - last
    if (age < d) {
        // still inside debounce window, try again
        runIn(1, "debouncedBusyClear")
        return
    }

    if ((device.currentValue("busy") ?: "false").toString() != "false") {
        setBusy(false, (state.pendingBusyClearReason ?: "clear")?.toString())
    }
    state.remove("pendingBusyClearReason")
}

/* ============================ Button helper ============================ */

private void emitButtonPushed(String source) {
    // WebCoRE reliably triggers off "pushed" events; keep it simple.
    sendEvent(
        name: "pushed",
        value: 1,
        isStateChange: true,
        descriptionText: "${device.displayName}: button 1 pushed (${source})"
    )
}

/* ============================ Binding to DoorSensor ============================ */

def bind(String sensorid) {
    if (!sensorid) {
        log.error "bind(): missing devId"
        return
    }

    def ndx = sensorid.indexOf("=")
    if (ndx != -1) sensorid = sensorid.substring(0, ndx).trim()

    if (sensorid.length() != 16) {
        log.error "bind(): devId '${sensorid}' is not 16 chars"
        return
    }

    def sensorDev = parent?.getChildDevice(sensorid) ?: getChildDevice(sensorid)
    def label = sensorDev?.displayName ?: sensorid

    state.bound_devId = sensorid
    state.bound_name  = label

    sendEvent(name:"bound_devId", value: sensorid)
    sendEvent(name:"bound_name",  value: label)

    log.info "${device.displayName}: bound to sensor ${label} (${sensorid})"
    lastResponse("Bound to ${label}")
    refreshSensor()
}

def unbind() {
    state.remove("bound_devId")
    state.remove("bound_name")
    endMove()
    sendEvent(name:"bound_devId", value: "")
    sendEvent(name:"bound_name", value: "")
    rememberState("door","unknown")
    rememberState("contact","unknown")
    lastResponse("Unbound")
}

boolean boundToDevice() {
    return (state?.bound_devId?.toString()?.length() == 16)
}

String boundContactState() {
    if (!boundToDevice()) return "unknown"

    def sensorDev = parent?.getChildDevice(state.bound_devId) ?: getChildDevice(state.bound_devId)
    if (!sensorDev) return "unknown"

    def v = sensorDev.currentValue("contact", true)
    return (v ?: "unknown") as String
}

/* ---- door movement (bound DoorSensor only) ---- */

// Called when a command is sent. The contact keeps its old value for a while after the toggle (a closing
// door stays "open" until it is fully down), so the door state only follows the contact once it reaches
// the target, or when the timeout fires.
private void startMove(boolean interim) {
    String contact = boundContactState()
    String target = (contact == "open") ? "closed" : (contact == "closed") ? "open" : null
    if (target) {
        state.moveTarget = target
        if (interim) rememberState("door", target == "open" ? "opening" : "closing")
    }
    rememberState("moving", "true")
    scheduleDoorTimeout()
    runIn(5, "moveCheck")
}

def moveCheck() {
    if (!state.moveTarget) return
    refreshSensor()
    if (state.moveTarget) runIn(5, "moveCheck")
}

private void endMove() {
    state.remove("moveTarget")
    unschedule("moveCheck")
    unschedule("doorTimeoutFired")
}

def refreshSensor() {
    if (!boundToDevice()) return

    def contact = boundContactState()
    rememberState("contact", contact)

    String target = state.moveTarget
    if (target) {
        if (contact == target) {
            rememberState("door", contact)
            rememberState("moving", "false")
            endMove()
        }
        return
    }

    if (contact == "open")   rememberState("door", "open")
    if (contact == "closed") rememberState("door", "closed")

    if (device.currentValue("door") in ["open","closed"]) {
        rememberState("moving","false")
    }
}

String doorState() {
    return (device.currentValue("door", true) ?: "unknown") as String
}

def scheduleDoorTimeout() {
    Integer t = ((settings?.doorTimeoutSec ?: 45) as Integer)
    if (t < 15) t = 15
    if (t > 120) t = 120
    runIn(t, "doorTimeoutFired")
}

def doorTimeoutFired() {
    // The door didn't reach the expected position in time: report whatever the contact shows now
    String contact = boundContactState()
    endMove()
    rememberState("contact", contact)
    rememberState("door", (contact in ["open","closed"]) ? contact : "unknown")
    rememberState("moving","false")
}

def doorTimeout(value) {
    Integer v = (value ?: 45) as Integer
    if (v < 15 || v > 120) {
        log.warn "doorTimeout: must be 15..120"
        return
    }
    device.updateSetting("doorTimeoutSec",[value:"${v}", type:"number"])
}

def timestampFormat(value) {
    value = value ?: "MM/dd/yyyy hh:mm:ss a"
    try {
        def stamp = new Date().format(value)
        state.timestampFormat = value
        logDebug("timestampFormat set to '${value}' (now='${stamp}')")
    } catch (e) {
        log.error "timestampFormat invalid: '${value}'"
    }
}

/* ============================ Misc commands ============================ */

def reset() {
    // Keep what the app set up and the sensor binding; everything else is cleared
    List keep = ["my_dni", "subnetId", "name", "type", "token", "devId", "localHubIP", "legacyApp",
                 "bound_devId", "bound_name"]
    Map saved = [:]
    keep.each { k -> if (state[k] != null) saved[k] = state[k] }
    unschedule()
    state.clear()
    saved.each { k, v -> state[k] = v }
    lastResponse("Reset")
    runIn(1, "initialize")
}

def scan() {
    lastResponse("Scan not required (local MQTT already running)")
}

/* ============================ Helpers ============================ */

def lastResponse(value) {
    sendEvent(name:"lastResponse", value: "$value")
}

def rememberAction(String action, String source) {
    sendEvent(name:"lastAction", value: action, isStateChange:true)
    sendEvent(name:"lastActionSource", value: source, isStateChange:true)
}

def rememberState(name, value, unit=null) {
    if (state."$name" != value) {
        state."$name" = value
        def s = (value == null) ? "" : value.toString()
        if (unit == null) {
            sendEvent(name:"$name", value: "$s", isStateChange:true)
        } else {
            sendEvent(name:"$name", value: "$s", unit: "$unit", isStateChange:true)
        }
    }
}

def successful(object) { (object?.code == "000000") }

def logDebug(msg) {
    if (settings?.debug == true) log.debug msg
}

def fmtSignal(rssi) {
    if (rssi == null) return
    rememberState("rssi", rssi)
    rememberState("signal", "${rssi} dBm")
}

private String activeScale() { parent?.temperatureScale() ?: (location?.temperatureScale ?: "F") }
private String activeFmt()   { parent?.getDateTimeFormat() ?: "MM/dd/yyyy hh:mm:ss a" }
private String nowFmt()      { new Date().format(activeFmt(), location?.timeZone) }
