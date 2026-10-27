/**
 * InvisOutlet MQTT Listener
 *
 * Managed by the "InvisOutlet Device Service" app - you should not need to create or
 * configure this device directly. It holds the actual MQTT broker connection (only
 * drivers can do that on Hubitat, not apps) and parses Home Assistant MQTT Discovery
 * messages, but it does not create any child devices itself. Instead it builds an
 * internal catalog of what it finds, which the app reads during setup to let you pick
 * which physical units to add as real Hubitat devices - and once you have, this
 * listener forwards their MQTT state changes up to the app, which routes each update
 * to the correct device.
 *
 * Debug logging and the raw-traffic/audit tools below are the only things on this
 * device meant for a person to actually look at; everything else is configured by
 * the app automatically.
 *
 * Version numbers below are left as originally assigned, not renumbered - a gap (e.g.
 * 1.2.0 jumping to 1.5.0) marks where entries were removed entirely because they
 * described an intermediate approach that was tried, real-world tested, found broken,
 * and fully replaced by a later entry, with none of that code remaining today.
 *
 * 1.1.0 - Initial Working Version
 * 1.1.1 - Fixed "LimitExceededException: ... generates excessive hub load" errors caused
 *         by the Aura/Pro units republishing their full sensor telemetry on a ~1s cycle
 *         regardless of whether anything changed. The existing isDuplicateUpdate() only
 *         guarded the switch/light and event/button paths - binary_sensor (motion/
 *         occupancy) and the numeric default branch (temperature, humidity, illuminance,
 *         distance, AQI, CO2, air pressure, VOC) had no rate limiting at all, so every
 *         republish cascaded straight through to forwardUpdateEntity(). Added a new
 *         isThrottled() time-based cap (separate from the exact-value dedup, since a
 *         slightly-jittering reading can bypass a value-equality check) and applied it
 *         to both previously-unguarded paths: 1.5s for motion/occupancy, 5s for the
 *         slow-moving environmental readings. clearDiscoveryCache() now also resets the
 *         new throttle-tracking state.
 * 1.2.0 - Added a testPort(ip, port) command that opens a raw TCP socket (separate from
 *         the MQTT connection) to check whether something is listening - used by the app
 *         to auto-detect Hubitat's built-in MQTT broker before you've entered any broker
 *         details. Since this only ever runs before initialize()/connect() are called for
 *         real, there's no moment where both interfaces are active at once, so parse()
 *         just checks a state.portTestActive flag at the top and routes accordingly - no
 *         separate driver needed for this.
 * 1.5.0 - Added probeAllDevices(dnis) to actively confirm every discovered device is
 *         still real and reachable, rather than passively waiting to see if it happens
 *         to publish something within some timeout. InvisHome does not appear to clear a
 *         device's retained MQTT discovery message when it's removed from the phone app,
 *         so passive "have we heard anything recently" timing can't tell a genuinely
 *         removed device apart from one that's simply quiet for a moment. Probes the
 *         WHOLE queue of dnis internally, one at a time via runInMillis chaining, briefly
 *         flashing each device's nightlight/colorlight on then off and comparing a
 *         before/after timestamp to detect a real MQTT echo. The app calls this exactly
 *         once, waits once (using estimateProbeDurationMs() to know how long), then
 *         reads the finished results back via getProbeResults() - a plain method call
 *         return value, not an attribute/currentValue() read, since real testing across
 *         several earlier per-device designs showed attribute reads from the app across
 *         a wait were simply not reliable here, regardless of the exact wait shape tried.
 * 1.5.1 - Real hardware test with an unplugged Pro and a powered-on Aura came back
 *         completely swapped - the unplugged unit "confirmed" and the powered one
 *         "no_response". Log timestamps suggest deviceList() may have run its probing
 *         sequence twice in close succession (Hubitat's dynamicPage/content methods are
 *         known to sometimes execute more than once per page load) - a second overlapping
 *         call to probeAllDevices() would overwrite state.currentProbeDni/
 *         probeBeforeTime while the FIRST run's scheduled probeQueueSendOff/
 *         probeQueueFinalize steps were still pending, causing a stale callback to
 *         misattribute one device's real echo to a different device's confirmation
 *         window. probeAllDevices() now calls unschedule() on both scheduled step names
 *         before starting, so only one probing run can ever be in flight at a time.
 * 1.5.2 - Added getEntityAttributesFor(dni), called by the app right after creating a
 *         device: returns exactly which attributes THIS specific unit has (Aura vs Pro
 *         differ), excluding button/event entities which have no persistent state. Used
 *         by the app to pre-populate every known attribute with a "pending" placeholder
 *         immediately on creation, rather than leaving the driver page blank. No other
 *         changes in this file - the live-message path is untouched from 1.5.1.
 * 2.0.0 - Changelog cleanup only, no functional changes from 1.5.2: removed entries
 *         (1.2.1-1.2.2, 1.3.0-1.4.1) that described a fix-then-revert with zero net
 *         effect on this file (1.2.1's blank-password guard, undone by 1.2.2), and an
 *         entire earlier generation of the device-confirmation feature - probeDevice()/
 *         probeSendOff()/probeFinalize() and the probeResult attribute - that was
 *         completely removed and replaced by probeAllDevices() in 1.5.0. None of that
 *         code exists anymore, so keeping the changelog entries around would only
 *         mislead anyone reading this history looking for how the current code works.
 * 2.0.1 - Fixed unbounded state growth (724KB+ within a few hours of real use) traced
 *         to state.seenTopics, used only by the auditTopics() diagnostic tool. It was
 *         tracked as a Set, appended to on every single MQTT message - but Hubitat
 *         persists state as JSON, which has no native Set type, so a Set can silently
 *         come back as a plain List after being saved and reloaded, at which point <<
 *         stops deduplicating and just appends the same handful of topics forever,
 *         once per message. Switched to a Map (topic -> true), whose keys can't have
 *         that ambiguity regardless of how the underlying type round-trips. initialize()
 *         also auto-resets any already-bloated seenTopics left over from before this
 *         fix, so existing installs clean up automatically without a manual cache clear.
 * 2.0.2 - Real testing showed state size still climbing well after the seenTopics fix
 *         (2 devices already past a YoLink Listener install's size with 19 devices),
 *         even with every attribute confirmed populated - meaning something is still
 *         adding new keys rather than only overwriting existing ones, despite every
 *         structure reviewed in code looking properly bounded. Added auditStateSize(),
 *         a new command that logs the key-count of every individual structure in state
 *         plus a rough total serialized size, so the actual leak can be pinpointed from
 *         real data (comparing two runs over time) instead of further guessing.
 * 2.0.3 - Found the real leak via auditStateSize(): seenTopics had 654 entries against
 *         only 25 in topicToEntity - a 26x gap, with 2 devices already exceeding a
 *         19-device YoLink Listener install's entire state size. Root cause: this
 *         device subscribes to the whole "homeassistant/#" wildcard (needed for its own
 *         discovery), which also receives traffic from any OTHER device exposed through
 *         the same shared broker - very likely Hubitat's own MQTT Export Integration
 *         publishing other Hubitat devices (lights, fans, Hue Bridge, etc.) under that
 *         same tree for Home Assistant compatibility. seenTopics was tracking every
 *         single one of those, completely unrelated to InvisOutlet, which is why it
 *         kept growing independent of whether InvisOutlet's own attributes had all
 *         been seen. Now only tracks topics that actually look like InvisOutlet's own -
 *         its real state topics live under "intecular/...", its discovery/config topics
 *         have "invisoutlet-..." in the object id. initialize() also retroactively
 *         prunes anything already tracked that doesn't match, cleaning up existing
 *         installs automatically.
 * 2.1.0 - Code-audit fixes, mostly around rate limiting and hub load:
 *         - All per-message bookkeeping (dedup/throttle times, last-seen times, raw
 *           temperature/distance caches, seenTopics/skippedTopics) moved out of state into
 *           in-memory @Field maps keyed by device id. Every MQTT message (~1/s per entity)
 *           used to re-persist the whole state, discovery catalog included. Only the
 *           catalog stays in state; initialize() removes the obsolete keys.
 *         - Dedup now compares against the last value actually FORWARDED, not the last one
 *           seen, and a throttled value is held and sent once the window ends (trailing
 *           edge) instead of being dropped. Fixes motion/occupancy getting stuck "active"
 *           and the last reading of a burst never arriving. Unchanged values are re-sent
 *           at most every 10 minutes as a resync.
 *         - Brightness, color and color-temperature updates now go through the same
 *           dedup/throttle as everything else.
 *         - registerActiveDevices() replays the last-known value of every attribute, so a
 *           newly created device fills in without waiting for something to change.
 *         - The discovery catalog is kept in memory and updated copy-on-write under a
 *           lock, so the retained-discovery burst on subscribe can't lose entities to
 *           concurrent parse() calls.
 *         - Discovery payloads that don't mention the device filter are ignored before
 *           JSON parsing, skippedTopics is capped, and devices whose manufacturer/
 *           identifiers/origin say Hubitat (MQTT Export re-publishing our own devices)
 *           are rejected.
 *         - Added the hs_* and clr_temp_* abbreviations and "~" expansion for color topics.
 *         - Reconnect backs off exponentially (15s up to 5 min), pending retries are
 *           cancelled on success, connect() skips if already connected, and initialize()
 *           disconnects before reconnecting.
 *         - A stale port-test flag (e.g. hub rebooted mid-test) no longer silently drops
 *           every MQTT message.
 *         - Debug logging now defaults off, and both debug and raw-traffic logging turn
 *           themselves off after 30 minutes.
 *         - The broker password is no longer kept in device state - it's read from the
 *           app at connect time.
 *         - Probing restores the nightlight to its previous state and only counts an echo
 *           of the value it actually commanded, so retained/republished state can't
 *           "confirm" an unplugged unit.
 *         - setLevel(0) turns the light off instead of publishing brightness 0.
 *         - clearDiscoveryCache() clears everything and resubscribes immediately.
 *         - "Discovered ..." is only logged at info level when an entity is new or
 *           changed, not for every retained re-delivery on reconnect.
 *         - Re-review follow-ups: the in-memory catalog holds plain LinkedHashMap/
 *           ArrayList copies (JsonSlurper's LazyMap builds itself lazily and isn't safe to
 *           read from several threads), an unchanged re-delivered entity returns early
 *           instead of copying the catalog, and the catalog lock is a Semaphore with a
 *           timeout. Reconnect backoff only grows once per failure. Entities whose topics
 *           start with "hubitat/" or don't live under "intecular/" are rejected (MQTT
 *           Export echoes). initialize() also arms the 30-minute logging auto-off, so
 *           existing installs with debug left on get it turned off. During a port test,
 *           a real MQTT message is processed normally instead of being taken as the test
 *           result.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

def clientVersion() { "2.1.0" }
private def copyright() { return "<br>© 2026-" + new Date().format("yyyy") + " Albert Mulder. All rights reserved." }
def driverName() { "InvisOutlet MQTT Listener" }
def activeScale() { (parent?.getTemperatureScale() == "F") ? "Fahrenheit (°F)" : "Celsius (°C)" }
def activeMeasurementScale() { (parent?.getDistanceUnit() == "in") ? "Inches (in)" : "Centimeters (cm)" }

@Field static final Map ABBR = [
    "~": "~", "dev": "device", "o": "origin", "cmps": "components", "p": "platform",
    "uniq_id": "unique_id", "obj_id": "object_id",
    "stat_t": "state_topic", "cmd_t": "command_topic", "val_tpl": "value_template",
    "dev_cla": "device_class", "unit_of_meas": "unit_of_measurement",
    "pl_on": "payload_on", "pl_off": "payload_off", "pl_avail": "payload_available",
    "pl_not_avail": "payload_not_available", "avty_t": "availability_topic",
    "json_attr_t": "json_attributes_topic", "json_attr_tpl": "json_attributes_template",
    "bri_stat_t": "brightness_state_topic", "bri_cmd_t": "brightness_command_topic",
    "bri_scl": "brightness_scale", "bri_val_tpl": "brightness_value_template",
    "hs_stat_t": "hs_state_topic", "hs_cmd_t": "hs_command_topic", "hs_val_tpl": "hs_value_template",
    "clr_temp_stat_t": "color_temp_state_topic", "clr_temp_cmd_t": "color_temp_command_topic",
    "clr_temp_val_tpl": "color_temp_value_template",
    "on_cmd_type": "on_command_type", "opt": "optimistic", "ret": "retain",
    "mf": "manufacturer", "mdl": "model", "sw": "sw_version", "hw": "hw_version",
    "ids": "identifiers", "cu": "configuration_url", "sa": "suggested_area"
]

// Topic keys that may use the "~" base-topic shorthand.
@Field static final List TILDE_TOPIC_KEYS = [
    "state_topic", "command_topic", "availability_topic", "json_attributes_topic",
    "brightness_state_topic", "brightness_command_topic",
    "hs_state_topic", "hs_command_topic", "color_temp_state_topic", "color_temp_command_topic"
]

// 2.1.0: in-memory bookkeeping, keyed by "<device id>|<name>". Lives as long as the hub
// keeps this driver's code loaded (lost on reboot or code save, which is fine - all of it
// is rebuilt from the next few MQTT messages). Kept out of state on purpose: state is
// re-persisted by every execution that writes it, and this is written on every message.
@Field static final ConcurrentHashMap<String, Map> MEM = new ConcurrentHashMap()
// Serializes catalog updates. A Semaphore rather than synchronized, acquired with a
// timeout (see withCatalogLock()) so a stuck holder can never hang the driver.
@Field static final Semaphore CATALOG_LOCK = new Semaphore(1)

// Identical values are normally suppressed, but re-sent at most this often so a device
// that somehow got out of step with the hardware catches back up on its own.
@Field static final long RESYNC_MS = 600000L
@Field static final int MAX_SKIPPED_TOPICS = 200
@Field static final int MAX_RECONNECT_DELAY_S = 300

metadata {
    definition(name: "InvisOutlet MQTT Listener", namespace: "almulder", author: "Albert Mulder") {
        capability "Initialize"
        capability "Configuration"
        capability "Refresh"

        attribute "connectionStatus", "string"
        attribute "deviceCount", "number"
        attribute "portTestResult", "string"

        command "connect"
        command "disconnect"
        command "resubscribe"
        command "clearDiscoveryCache"
        command "auditTopics"
        command "auditStateSize"
        command "testPort", [[name:"ip", type:"STRING"], [name:"port", type:"NUMBER"]]
    }

    preferences {
        input name: "info",
              type: "paragraph",
              title: "Driver Info",
              description: """<b>Driver:</b> ${driverName()}<br>
                              <b>Version:</b> v${clientVersion()}<br>
                              <b>Temperature Scale:</b> ${activeScale()}<br>
                              <b>Measurement Scale:</b> ${activeMeasurementScale()}<br>
                              ${copyright()}"""
        input name: "logEnable", type: "bool", title: "Enable debug logging (turns itself off after 30 minutes)", defaultValue: false
        input name: "rawTrafficLogging", type: "bool", title: "Log raw MQTT traffic (topic + payload) - very verbose, turns itself off after 30 minutes", defaultValue: false
    }
}

// ---------------- lifecycle ----------------

def installed() {
    state.topicToEntity = [:]
    state.discovered = [:]
}

def updated() {
    if (logEnable || rawTrafficLogging) runIn(1800, "logsOff")
}

def logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    device.updateSetting("rawTrafficLogging", [value: "false", type: "bool"])
    log.info "${device.displayName}: debug and raw-traffic logging auto-disabled after 30 minutes"
}

def initialize() {
    if (state.topicToEntity == null) state.topicToEntity = [:]
    if (state.discovered == null) state.discovered = [:]
    // 2.1.0: everything below now lives in memory (see MEM) - remove the old copies,
    // including the plaintext broker password, from state.
    ["seenTopics", "skippedTopics", "lastValueTime", "lastForwardTime", "lastAttrSeen",
     "lastNotifySeen", "lastRawTemp", "lastRawDistanceCm", "mqttPassword"].each { state.remove(it) }
    // A port test interrupted by a reboot or code save would otherwise leave parse()
    // swallowing every MQTT message forever.
    clearPortTestFlags()
    state.remove("reconnectDelay")
    state.remove("reconnectAt")
    unschedule("connect")
    // Installs from before 2.1.0 had no auto-off at all, so debug could be left on forever.
    if (logEnable || rawTrafficLogging) runIn(1800, "logsOff")
    // Drop any existing session first, so a call on an already-connected Listener (e.g.
    // from the app's device list page) reconnects cleanly with the current settings.
    try { if (interfaces.mqtt.isConnected()) interfaces.mqtt.disconnect() } catch (ignored) { }
    connect()
}

def uninstalled() {
    unschedule()
    disconnect()
}

def refresh() {
    resubscribe()
}

// Called by the app right after creating this device, and again any time its MQTT
// settings change. Just stores the values - call initialize() separately to (re)connect.
// The password is deliberately NOT stored in state (it would show under State Variables
// on this device's page) - connect() asks the app for it, with an in-memory copy as a
// fallback.
// Configure button (capability "Configuration") - reconnect with the stored settings.
def configure() {
    initialize()
}

def configure(Map cfg) {
    state.brokerUri = cfg.broker
    state.mqttUser = cfg.username
    state.remove("mqttPassword")
    if (cfg.password) mem("cfg").put("password", cfg.password) else mem("cfg").remove("password")
    state.discoveryPrefix = cfg.prefix ?: "homeassistant"
    state.deviceFilter = cfg.filter ?: ""
}

// Called by the app once setup is finished, so this device knows which discovered
// units are actually wanted - MQTT updates for anything else are still tracked for
// online/offline purposes but not forwarded anywhere.
def registerActiveDevices(List<String> dnis) {
    def list = (dnis ?: []).collect { it.toString() }
    // Stored as a List - a Set doesn't survive state's JSON round trip (see 2.0.1).
    state.activeDnis = list
    sendEvent(name: "deviceCount", value: list.size())
    // Forget what we think these devices currently show (they may be brand new, or
    // carrying placeholders), then replay every known value to them shortly.
    Map lastFwd = mem("lastForwarded")
    lastFwd.keySet().toList().each { key ->
        if (list.any { dni -> key.startsWith("${dni}|") }) lastFwd.remove(key)
    }
    runIn(2, "replayLastKnown")
}

// Re-sends the latest value seen for every attribute of every active device - used after
// registerActiveDevices(), since the units only publish when something changes.
def replayLastKnown() {
    mem("lastReceived").each { key, upd ->
        if (isActiveDni(upd.dni)) gateForward(key, upd, 0)
    }
}

private boolean isActiveDni(dni) {
    return (state.activeDnis ?: []).contains(dni)
}

// ---------------- in-memory bookkeeping ----------------

private Map mem(String name) {
    String key = "${device.id}|${name}".toString()
    Map m = MEM.get(key)
    if (m == null) {
        MEM.putIfAbsent(key, new ConcurrentHashMap())
        m = MEM.get(key)
    }
    return m
}

// The discovery catalog - discovered (dni -> deviceInfo/entities) and topicToEntity
// (topic -> list of dni/attr/kind). Loaded from state once, then read from memory on
// every message. Updated copy-on-write under CATALOG_LOCK (see registerEntity()), so a
// published map is never mutated after the fact and concurrent discovery messages can't
// lose each other's entries.
private Map catalog() {
    Map c = mem("catalog")
    if (c.isEmpty()) {
        withCatalogLock {
            if (c.isEmpty()) {
                c.put("discovered", plainCopy(state.discovered ?: [:]))
                c.put("topicToEntity", plainCopy(state.topicToEntity ?: [:]))
            }
        }
    }
    return c
}

private withCatalogLock(Closure body) {
    boolean locked = false
    try {
        locked = CATALOG_LOCK.tryAcquire(5, TimeUnit.SECONDS)
        if (!locked) log.warn "Discovery catalog lock not acquired within 5s - continuing without it"
        return body()
    } finally {
        if (locked) CATALOG_LOCK.release()
    }
}

// Deep copy into plain LinkedHashMap/ArrayList/String. Anything shared between threads
// through MEM goes through this: JsonSlurper's LazyMap builds itself on first access and
// isn't safe to read from several threads at once, and GStrings would compare unequal to
// the Strings they become after a state round trip.
private plainCopy(obj) {
    if (obj instanceof Map) {
        Map out = new LinkedHashMap()
        obj.each { k, v -> out.put(k?.toString(), plainCopy(v)) }
        return out
    }
    if (obj instanceof Collection) {
        List out = new ArrayList()
        obj.each { out.add(plainCopy(it)) }
        return out
    }
    if (obj instanceof GString) return obj.toString()
    return obj
}

// ---------------- MQTT connection ----------------

def connect() {
    try {
        if (!state.brokerUri) {
            log.warn "MQTT broker not configured yet"
            return
        }
        if (interfaces.mqtt.isConnected()) {
            if (logEnable) log.debug "connect(): already connected - skipping"
            return
        }
        interfaces.mqtt.connect(
            state.brokerUri,
            "hubitat-invisoutlet-${device.id}",
            state.mqttUser ?: null,
            brokerPassword() ?: null
        )
    } catch (e) {
        log.error "MQTT connect failed: ${e}"
        sendEvent(name: "connectionStatus", value: "error")
        scheduleReconnect()
    }
}

// The copy configure() just received comes first (the app always calls configure()
// before initialize()); after a reboot or code save that's gone, so ask the app.
private brokerPassword() {
    def pw = mem("cfg").get("password")
    if (pw != null) return pw
    try {
        return parent?.getMqttPassword()
    } catch (ignored) {
        return null
    }
}

// Exponential backoff - 15s, 30s, 60s ... up to 5 minutes - so bad credentials or a
// broker that's gone don't retry (and log) every 15 seconds forever.
// A single failure can be reported twice (connect()'s catch and an "Error" status), so
// if a retry is already pending this does nothing - the delay grows once per attempt.
private void scheduleReconnect() {
    long nowMs = now()
    Long pendingAt = state.reconnectAt as Long
    if (pendingAt != null && pendingAt > nowMs) return
    int delay = (state.reconnectDelay ?: 15) as Integer
    log.warn "Retrying MQTT connection in ${delay}s"
    state.reconnectAt = nowMs + delay * 1000L
    runIn(delay, "connect")
    state.reconnectDelay = Math.min(delay * 2, MAX_RECONNECT_DELAY_S)
}

def disconnect() {
    try {
        interfaces.mqtt.disconnect()
    } catch (ignored) { }
    sendEvent(name: "connectionStatus", value: "disconnected")
}

def resubscribe() {
    def prefix = state.discoveryPrefix ?: "homeassistant"
    def topics = (catalog().topicToEntity?.keySet() ?: []).collect { it.toString() }

    // Explicitly unsubscribe from every topic we've ever tracked first. MQTT sessions
    // can retain old subscriptions across a disconnect/reconnect, so a topic subscribed
    // to individually in a previous session can keep causing duplicate delivery even
    // after the code stops re-issuing that subscribe. This forces a clean slate.
    topics.each { t ->
        try { interfaces.mqtt.unsubscribe(t) } catch (ignored) { }
    }

    if (logEnable) log.debug "Subscribing to ${prefix}/#"
    interfaces.mqtt.subscribe("${prefix}/#")
    // Only add a topic explicitly if it's OUTSIDE the wildcard above - subscribing to
    // both "prefix/#" and a specific topic already covered by it causes most brokers
    // (including Mosquitto) to deliver every message on that topic twice.
    topics.each { t -> subscribeIfNeeded(t) }
}

def subscribeIfNeeded(String topic) {
    def prefix = state.discoveryPrefix ?: "homeassistant"
    if (!topic.toString().startsWith("${prefix}/")) {
        interfaces.mqtt.subscribe(topic)
    } else if (logEnable) {
        log.debug "Skipping explicit subscribe to '${topic}' - already covered by ${prefix}/# wildcard"
    }
}

def mqttClientStatus(String status) {
    if (logEnable) log.debug "mqttClientStatus: ${status}"
    if (status.startsWith("Status: Connection succeeded")) {
        sendEvent(name: "connectionStatus", value: "connected")
        unschedule("connect")
        state.remove("reconnectDelay")
        state.remove("reconnectAt")
        runIn(1, "resubscribe")
    } else if (status.startsWith("Error") || status.startsWith("Failure")) {
        log.warn "MQTT connection issue: ${status}"
        sendEvent(name: "connectionStatus", value: "error")
        scheduleReconnect()
    }
}

// ---------------- incoming messages ----------------

def parse(String description) {
    // A port test in progress means this callback is from the Raw Socket interface, not
    // MQTT - any incoming data at all proves the target is open and responding. A test
    // never legitimately runs longer than a couple of seconds, so a flag older than that
    // was left behind by an interrupted test and is cleared rather than trusted.
    def msg = null
    if (state.portTestActive) {
        if (now() - ((state.portTestStartedAt ?: 0) as Long) < 10000) {
            // A forced retest can run while MQTT is connected - a message that parses as
            // MQTT is real traffic, so handle it normally rather than treating it as the
            // test result (and dropping it).
            boolean mqttUp = false
            try { mqttUp = interfaces.mqtt.isConnected() } catch (ignored) { }
            if (mqttUp) {
                try { msg = interfaces.mqtt.parseMessage(description) } catch (ignored) { msg = null }
            }
            if (msg == null || msg.topic == null) {
                msg = null
                if (state.awaitingPortTestResult) finalizePortTest("open")
                return
            }
        } else {
            clearPortTestFlags()
        }
    }

    if (msg == null) {
        try {
            msg = interfaces.mqtt.parseMessage(description)
        } catch (e) {
            log.error "Could not parse MQTT message: ${e}"
            return
        }
    }
    def topic = msg.topic
    def payload = msg.payload
    def prefix = state.discoveryPrefix ?: "homeassistant"

    if (rawTrafficLogging) log.info "RAW MQTT: topic='${topic}' payload='${payload}'"

    // Only track topics that are actually InvisOutlet-related - real testing showed
    // this ballooning to 600+ entries with just 2 devices, versus ~25 in topicToEntity,
    // because the "homeassistant/#" wildcard this device subscribes to (needed for its
    // own discovery) also receives traffic from any OTHER device exposed through the
    // same shared broker (e.g. Hubitat's own MQTT Export Integration publishing other
    // Hubitat devices under that same tree for Home Assistant compatibility) - none of
    // which this diagnostic tool has any use for. InvisOutlet's real state topics live
    // under "intecular/...", and its discovery/config topics have "invisoutlet-..." in
    // the object id - a topic matching neither isn't ours, so don't bother tracking it.
    def topicLower = topic.toString().toLowerCase()
    if (topicLower.contains("intecular") || topicLower.contains("invis")) {
        mem("seenTopics").put(topic.toString(), true)
    }

    try {
        if (topic.startsWith("${prefix}/") && topic.endsWith("/config")) {
            handleDiscoveryMessage(topic, payload)
        } else {
            handleStateMessage(topic, payload)
        }
    } catch (e) {
        log.error "Error handling MQTT message on ${topic}: ${e}"
    }
}

// ---------------- broker port test (used by the app before real MQTT setup) ----------------

// Opens a raw TCP socket to check whether something is listening - used to auto-detect
// Hubitat's built-in MQTT broker before any real broker details have been entered. Only
// ever runs before initialize()/connect() are called for real, so there's no risk of this
// colliding with an active MQTT session on the same device.
def testPort(String ip, port) {
    def portNum = port as Integer
    if (logEnable) log.debug "Testing port ${ip}:${portNum}"
    state.portTestActive = true
    state.portTestStartedAt = now()
    state.awaitingPortTestResult = true
    state.sawPortTestError = false
    sendEvent(name: "portTestResult", value: "testing")

    try { interfaces.rawSocket.close() } catch (ignored) { }

    try {
        interfaces.rawSocket.connect(ip, portNum)
    } catch (e) {
        if (logEnable) log.debug "Immediate connect failure to ${ip}:${portNum}: ${e}"
        finalizePortTest("closed")
        return
    }
    runInMillis(1200, "portTestTimeoutCheck")
}

def portTestTimeoutCheck() {
    if (state.awaitingPortTestResult) finalizePortTest(state.sawPortTestError ? "closed" : "open")
}

// Raw Socket interface's status callback - only meaningful while a port test is active,
// since MQTT connection status comes through mqttClientStatus() instead.
def socketStatus(String status) {
    if (!state.portTestActive) return
    if (logEnable) log.debug "socketStatus (port test): ${status}"
    def lower = status?.toLowerCase() ?: ""
    if (lower.contains("error") || lower.contains("fail") || lower.contains("refused") || lower.contains("reset")) {
        state.sawPortTestError = true
        if (state.awaitingPortTestResult) finalizePortTest("closed")
    }
}

private finalizePortTest(String result) {
    clearPortTestFlags()
    sendEvent(name: "portTestResult", value: result)
    try { interfaces.rawSocket.close() } catch (ignored) { }
    if (logEnable) log.debug "Port test result: ${result}"
}

private clearPortTestFlags() {
    state.awaitingPortTestResult = false
    state.portTestActive = false
    state.remove("portTestStartedAt")
}

def handleDiscoveryMessage(String topic, String payload) {
    if (!payload || payload.trim() == "") {
        if (logEnable) log.debug "Empty discovery payload on ${topic} (entity likely removed upstream)"
        return
    }

    // Cheap pre-filter: the "homeassistant/#" wildcard also carries every other device on
    // a shared broker (e.g. everything Hubitat's MQTT Export Integration publishes). If
    // the filter text doesn't appear anywhere in the payload, the parsed device can't
    // match it either - skip it without JSON-parsing it or recording it anywhere.
    def filter = (state.deviceFilter ?: "").trim().toLowerCase()
    if (filter && !payload.toLowerCase().contains(filter)) return

    def json
    try {
        json = new JsonSlurper().parseText(payload)
    } catch (e) {
        log.warn "Could not parse discovery JSON on ${topic}: ${e}"
        return
    }

    def parts = topic.split("/")
    def component = parts.length > 1 ? parts[1] : null
    def objectId = parts.length > 2 ? parts[parts.length - 2] : "unknown"

    def normalized = normalizeKeys(json)
    expandTopicShorthand(normalized, normalized["~"])

    def components = normalized.components
    def origin = normalized.origin
    if (component == "device" && components instanceof Map) {
        def deviceInfo = normalized.device ?: [:]
        if (!deviceMatchesFilter(deviceInfo, origin)) {
            recordSkip(topic, "device filter mismatch (${deviceInfo?.name ?: deviceInfo?.manufacturer})")
            return
        }
        components.each { key, entityCfgRaw ->
            def entityCfg = normalizeKeys(entityCfgRaw)
            expandTopicShorthand(entityCfg, normalized["~"])
            registerEntity(entityCfg, (entityCfg.platform ?: "sensor").toString(), key.toString(), deviceInfo)
        }
        return
    }

    // MQTT Device Trigger discovery - a different shape entirely, commonly used for
    // momentary physical button presses. There's no persistent state to track, just
    // a topic + exact payload that means "this trigger fired".
    if (component == "device_automation") {
        def deviceInfo = normalized.device ?: [:]
        if (!deviceMatchesFilter(deviceInfo, origin)) {
            recordSkip(topic, "device_automation filter mismatch")
            return
        }
        handleDeviceAutomation(normalized, objectId, deviceInfo)
        return
    }

    def deviceInfo = normalized.device ?: [:]
    if (!deviceMatchesFilter(deviceInfo, origin)) {
        recordSkip(topic, "entity filter mismatch")
        return
    }
    registerEntity(normalized, component, objectId, deviceInfo)
}

// Only diagnostic (read by auditTopics()), so kept in memory and capped - it used to
// grow by one state entry per foreign entity on a shared broker, forever.
def recordSkip(String topic, String reason) {
    Map skipped = mem("skippedTopics")
    String t = topic.toString()
    if (skipped.containsKey(t) || skipped.size() < MAX_SKIPPED_TOPICS) skipped.put(t, reason.toString())
    if (logEnable) log.debug "Skipping ${topic}: ${reason}"
}

def handleDeviceAutomation(Map cfg, String objectId, Map deviceInfo) {
    if ((cfg.automation_type ?: "trigger") != "trigger") return
    if (!cfg.topic) {
        if (logEnable) log.debug "device_automation trigger ${objectId} has no topic - skipping"
        return
    }
    def label = "${cfg.type ?: 'button'} ${cfg.subtype ?: objectId}".trim()
    def synthetic = [
        state_topic     : cfg.topic,
        device_class    : "button",
        name            : label,
        expected_payload: cfg.payload
    ]
    def uniqueId = "${cfg.type ?: 'trigger'}_${cfg.subtype ?: objectId}".toString()
    registerEntity(synthetic, "event", uniqueId, deviceInfo)
}

def deviceMatchesFilter(Map deviceInfo, origin = null) {
    def idText = (deviceInfo?.identifiers instanceof List) ? deviceInfo.identifiers.join(" ") : deviceInfo?.identifiers
    // Hubitat's own MQTT Export Integration re-publishing the devices this app created
    // (named "InvisOutlet - ...") would otherwise pass the name filter and show up as
    // phantom units whose command topics point back at Hubitat itself.
    def originName = (origin instanceof Map) ? origin.name : origin
    def sourceText = [deviceInfo?.manufacturer, idText, originName]
        .collect { it?.toString()?.toLowerCase() ?: "" }.join(" ")
    if (sourceText.contains("hubitat")) return false

    def filter = (state.deviceFilter ?: "").trim().toLowerCase()
    if (!filter) return true
    def haystack = [deviceInfo?.name, deviceInfo?.manufacturer, deviceInfo?.model, idText]
        .collect { it?.toString()?.toLowerCase() ?: "" }.join(" ")
    return haystack.contains(filter)
}

def normalizeKeys(input) {
    if (input instanceof Map) {
        def out = [:]
        input.each { k, v -> out[(ABBR[k] ?: k)] = normalizeKeys(v) }
        return out
    } else if (input instanceof List) {
        return input.collect { normalizeKeys(it) }
    }
    return input
}

def expandTopicShorthand(Map m, prefixVal) {
    if (!prefixVal) return
    def prefix = prefixVal.toString()
    TILDE_TOPIC_KEYS.each { key ->
        def v = m[key]
        if (v && v.toString().startsWith("~")) {
            m[key] = prefix + v.toString().substring(1)
        }
    }
}

// ---------------- discovery catalog (no child devices - the app reads this) ----------------

// Text-based fallback - kept in case a future firmware actually puts "Aura"/"Pro"
// in the model or name field. Confirmed via real device data that current
// firmware does NOT do this (both units report model "IVO1" with no distinguishing
// text), so featureBasedModelLabel() below is the primary signal in practice.
def resolveModelLabel(Map deviceInfo) {
    def text = "${deviceInfo?.model ?: ''} ${deviceInfo?.name ?: ''}".toLowerCase()
    if (text.contains("pro")) return "Pro"
    if (text.contains("aura")) return "Aura"
    return deviceInfo?.model ?: "InvisOutlet"
}

// Confirmed from real discovery data: the Pro has air-quality/motion/occupancy/
// distance sensors and a plain "Nightlight"; the Aura has none of those and has a
// "Color Light" instead. Returns null if this particular entity isn't distinctive.
def featureBasedModelLabel(String objectId, String platform, deviceClass) {
    def id = objectId.toLowerCase()
    if (id.contains("colorlight")) return "Aura"
    if (id.contains("aqi") || id.contains("co2") || id.contains("voc") || id.contains("pressure") || id.contains("distance")) return "Pro"
    if (platform == "binary_sensor" && deviceClass in ["motion", "occupancy"]) return "Pro"
    return null
}

def computeModel(String dni) {
    def entry = catalog().discovered?.get(dni)
    if (!entry) return "InvisOutlet"
    def model = "InvisOutlet"
    entry.entities?.each { attrName, meta ->
        def label = featureBasedModelLabel(meta.objectId, meta.platform, meta.deviceClass)
        if (label) model = label
    }
    if (model == "InvisOutlet") model = resolveModelLabel(entry.deviceInfo)
    return model
}

def sanitizeAttr(String s) {
    return s.toString().toLowerCase().replaceAll(/[^a-z0-9_]/, "_")
}

def isButtonLikeEntity(String platform, Map cfg) {
    if (platform == "event") return true
    if (platform == "binary_sensor") {
        def nameLower = (cfg.name ?: "").toString().toLowerCase()
        return cfg.device_class == "button" || nameLower.contains("button") || nameLower.contains("touch")
    }
    return false
}

// Maps the real InvisOutlet MQTT object-ids to clean, consistent attribute names.
// Falls back to a sanitized version of the raw id for anything not recognized here
// (e.g. a future firmware revision that adds new entities).
def friendlyAttrName(String objectId) {
    def id = objectId.toLowerCase()
    if (id.contains("outlet1")) return "outlet1"
    if (id.contains("outlet2")) return "outlet2"
    if (id.contains("nightlight")) return "nightlight"
    if (id.contains("colorlight")) return "nightlight"
    if (id.contains("aqi")) return "airQualityIndex"
    if (id.contains("co2")) return "co2"
    if (id.contains("distance")) return "distance"
    if (id.contains("pressure")) return "airPressure"
    if (id.contains("voc")) return "voc"
    if (id.contains("button") && id.endsWith("1")) return "button1"
    if (id.contains("button") && id.endsWith("2")) return "button2"
    return sanitizeAttr(objectId)
}

def registerEntity(Map cfg, String platform, String objectId, Map deviceInfo) {
    if (!(platform in ["switch", "light", "binary_sensor", "sensor", "event"])) {
        recordSkip(cfg.state_topic ?: "entity:${objectId}", "unhandled platform '${platform}' (name='${cfg.name}')")
        return
    }

    def baseId = (deviceInfo?.identifiers instanceof List ? deviceInfo.identifiers[0] : deviceInfo?.identifiers) ?: "unknown"
    def dni = "invisoutlet-${baseId}".toString().replaceAll(/[^a-zA-Z0-9_\-]/, "_")
    def attrName = friendlyAttrName(objectId)

    def isButton = isButtonLikeEntity(platform, cfg)
    def newMeta = [
        objectId               : objectId,
        commandTopic           : cfg.command_topic,
        stateTopic              : cfg.state_topic,
        valueTemplate           : cfg.value_template,
        payloadOn               : cfg.payload_on ?: "ON",
        payloadOff              : cfg.payload_off ?: "OFF",
        platform                : platform,
        deviceClass             : cfg.device_class,
        unit                    : cfg.unit_of_measurement,
        brightnessStateTopic    : cfg.brightness_state_topic,
        brightnessCommandTopic  : cfg.brightness_command_topic,
        brightnessValueTemplate : cfg.brightness_value_template,
        brightnessScale         : cfg.brightness_scale ?: 255,
        hsStateTopic            : cfg.hs_state_topic,
        hsCommandTopic          : cfg.hs_command_topic,
        colorTempStateTopic     : cfg.color_temp_state_topic,
        colorTempCommandTopic   : cfg.color_temp_command_topic,
        isButton                : isButton,
        expectedPayload         : cfg.expected_payload
    ]

    def stateTopics = [
        [cfg.state_topic, "state"],
        [cfg.brightness_state_topic, "brightness"],
        [cfg.hs_state_topic, "hs"],
        [cfg.color_temp_state_topic, "colorTemp"]
    ].findAll { it[0] }

    // Structural check against MQTT Export echoes: InvisOutlet's own state/command topics
    // all live under "intecular/..." (see 2.0.3). A config whose topics point anywhere
    // else - "hubitat/..." in particular - is some other integration re-publishing it.
    // Button triggers (platform "event", built from device_automation configs) only get
    // the "hubitat/" check - their topic layout hasn't been confirmed from real data.
    def allTopics = ([cfg.command_topic] + stateTopics.collect { it[0] }).findAll { it }.collect { it.toString().toLowerCase() }
    boolean foreign = allTopics.any { it.startsWith("hubitat/") } ||
        (platform != "event" && !allTopics.any { it.startsWith("intecular/") })
    if (allTopics && foreign) {
        recordSkip(cfg.state_topic ?: "entity:${objectId}", "no topics under intecular/ (${allTopics.join(', ')}) - likely re-published by another integration")
        return
    }

    Map normalizedMeta = (Map) plainCopy(newMeta)
    Map plainDeviceInfo = (Map) plainCopy(deviceInfo ?: [:])

    // Retained discovery is re-delivered on every reconnect - if nothing about this
    // entity changed, there's nothing to copy, persist or log.
    if (isRegistered(catalog(), dni, attrName, normalizedMeta, plainDeviceInfo, stateTopics)) {
        if (logEnable) log.debug "Discovery for '${attrName}' on ${dni} unchanged"
        return
    }

    // Copy-on-write: build the updated maps (copying only the levels being changed) and
    // swap them in, all under the lock - the retained-discovery burst on subscribe runs
    // many of these at once, and parse() threads read the published maps without locking.
    withCatalogLock {
        Map cat = catalog()
        Map disc = new LinkedHashMap(cat.discovered ?: [:])
        Map t2e = new LinkedHashMap(cat.topicToEntity ?: [:])

        def oldEntry = disc[dni]
        Map entities = new LinkedHashMap(oldEntry?.entities ?: [:])
        entities.put(attrName, normalizedMeta)
        disc.put(dni, [deviceInfo: plainDeviceInfo, entities: entities])

        stateTopics.each { pair ->
            String t = pair[0].toString()
            String kind = pair[1]
            List list = new ArrayList(t2e[t] ?: [])
            if (!list.any { it.dni == dni && it.attr == attrName && it.kind == kind }) {
                list.add([dni: dni, attr: attrName, kind: kind])
            }
            t2e.put(t, list)
        }

        cat.put("discovered", disc)
        cat.put("topicToEntity", t2e)
        // Only the catalog is persisted - it's only written when discovery actually
        // changes something, not on state messages.
        state.discovered = disc
        state.topicToEntity = t2e
    }

    stateTopics.each { pair -> subscribeIfNeeded(pair[0].toString()) }

    log.info "Discovered '${cfg.name ?: objectId}' -> attribute '${attrName}' (platform=${platform}, class=${cfg.device_class}) for device ${dni}"
}

private boolean isRegistered(Map cat, String dni, String attrName, Map meta, Map deviceInfo, List stateTopics) {
    def entry = cat.discovered?.get(dni)
    if (entry == null || entry.deviceInfo != deviceInfo || entry.entities?.get(attrName) != meta) return false
    return stateTopics.every { pair ->
        def list = cat.topicToEntity?.get(pair[0].toString())
        list && list.any { it.dni == dni && it.attr == attrName && it.kind == pair[1] }
    }
}

// Called by the app's device-selection page.
def getDiscoveredDevices() {
    def result = []
    Map lastSeenMap = mem("lastNotifySeen")
    catalog().discovered?.each { dni, entry ->
        def model = computeModel(dni)
        def lastSeen = lastSeenMap.get(dni)
        def online = lastSeen && (now() - (lastSeen as Long)) < 60000
        result << [dni: dni, name: (entry.deviceInfo?.name ?: dni), model: model, online: online]
    }
    return result
}

// Called by the app right after creating a device, so it knows exactly which attributes
// THIS specific unit has (Aura vs Pro differ). Excludes button entities ("event"
// platform and button-like binary_sensors) - those are momentary triggers with no
// persistent state.
def getEntityAttributesFor(String dni) {
    def entityMap = catalog().discovered?.get(dni)?.entities
    if (!entityMap) return []
    def result = []
    entityMap.each { attrName, meta ->
        if (meta.platform == "event" || meta.isButton) return
        result << [attr: attrName, platform: meta.platform, deviceClass: meta.deviceClass, unit: meta.unit]
    }
    return result
}

// ---------------- state updates ----------------

// 2.1.0: one gate for everything forwarded upstream, replacing isDuplicateUpdate() +
// isThrottled(). The Aura/Pro units republish their full telemetry on a ~1s cycle whether
// or not anything changed (the "excessive hub load" problem from 1.1.1), so:
//   - a value identical to the last one actually FORWARDED is dropped (re-sent at most
//     every RESYNC_MS as a resync). The old dedup compared against the last value SEEN
//     and slid its window forward on every republish, so a value that was throttled
//     once could then be "deduplicated" forever - e.g. motion stuck active.
//   - a changed value inside the attribute's minimum interval is held, not dropped, and
//     sent when the interval ends (trailing edge), so the last value of a burst always
//     arrives. A newer value replaces the held one; one that reverts to what was last
//     forwarded cancels it.
// key is "<dni>|<attr>" (plus "#level"/"#hs"/"#ct" for the light's extra channels).
// The read-then-write below isn't atomic across threads; the worst a race can do is
// forward the same value twice or hold one value a moment longer, both harmless.
private void gateForward(String key, Map upd, long minIntervalMs) {
    Map lastFwd = mem("lastForwarded")
    Map pending = mem("pending")
    String valueStr = String.valueOf(upd.value)
    long nowMs = now()
    Map prev = (Map) lastFwd.get(key)
    long sincePrev = prev ? (nowMs - (prev.time as Long)) : Long.MAX_VALUE

    if (prev && prev.value == valueStr && sincePrev < RESYNC_MS) {
        pending.remove(key)
        return
    }
    if (prev && minIntervalMs > 0 && sincePrev < minIntervalMs) {
        upd.dueAt = (prev.time as Long) + minIntervalMs
        pending.put(key, upd)
        scheduleFlush(upd.dueAt as Long)
        if (logEnable) log.debug "Holding '${key}' = ${valueStr} until its ${minIntervalMs}ms window ends"
        return
    }
    pending.remove(key)
    lastFwd.put(key, [value: valueStr, time: nowMs])
    sendUpstream(upd)
}

// Remembers the latest value for every attribute (forwarded or not - used by
// replayLastKnown()), then gates it if this device is active.
private void route(String key, Map upd, long minIntervalMs, boolean isActive) {
    mem("lastReceived").put(key, upd)
    if (isActive) gateForward(key, upd, minIntervalMs)
}

private void sendUpstream(Map upd) {
    switch (upd.kind) {
        case "level":
            parent?.forwardUpdateLevel(upd.dni, upd.value)
            break
        case "color":
            parent?.forwardUpdateColor(upd.dni, upd.hue, upd.sat)
            break
        case "colorTemp":
            parent?.forwardUpdateColorTemperature(upd.dni, upd.value)
            break
        default:
            parent?.forwardUpdateEntity(upd.dni, upd.attr, upd.platform, upd.deviceClass, upd.value, upd.unit)
    }
}

// One shared flush job, but it's only ever moved EARLIER, never pushed later - so a
// steady stream of new held values can't keep postponing the ones already waiting.
private void scheduleFlush(long dueAt) {
    Map f = mem("flush")
    Long scheduled = f.get("at") as Long
    long nowMs = now()
    // A recorded time more than 2s in the past means that job was lost (e.g. unschedule)
    // or is running right now and will reschedule for whatever is left - either way,
    // schedule a fresh one.
    if (scheduled != null && scheduled >= nowMs - 2000 && scheduled <= dueAt) return
    f.put("at", dueAt)
    runInMillis(Math.max(50L, dueAt - nowMs), "flushPending")
}

def flushPending() {
    mem("flush").remove("at")
    Map pending = mem("pending")
    Map lastFwd = mem("lastForwarded")
    long nowMs = now()
    Long nextDue = null
    pending.keySet().toList().each { key ->
        Map upd = (Map) pending.get(key)
        if (upd == null) return
        long due = (upd.dueAt ?: 0) as Long
        if (due <= nowMs + 50) {
            // Two-arg remove: only if a newer value hasn't replaced it in the meantime.
            if (pending.remove(key, upd)) {
                lastFwd.put(key, [value: String.valueOf(upd.value), time: nowMs])
                if (isActiveDni(upd.dni)) sendUpstream(upd)
            }
        } else if (nextDue == null || due < nextDue) {
            nextDue = due
        }
    }
    if (nextDue != null) scheduleFlush(nextDue)
}

// Button presses have no state to keep in sync, so a plain sliding window is right
// here: some firmware sends one press as several messages in quick succession.
private boolean isRepeatPress(String key, long windowMs) {
    long nowMs = now()
    Long last = mem("lastPress").put(key, nowMs) as Long
    return last != null && (nowMs - last) < windowMs
}

private Map entityUpdate(entry, meta, value, unit) {
    return [kind: "entity", dni: entry.dni, attr: entry.attr, platform: meta.platform,
            deviceClass: meta.deviceClass, value: value, unit: unit]
}

// Tracks the nightlight's last reported on/off for every unit, active or not - used by
// probing to restore the light afterwards and to recognise a genuine echo.
private void noteNightlightState(String dni, Map meta, String payload, long nowMs) {
    def v = meta.valueTemplate ? applyValueTemplate(meta.valueTemplate, payload) : payload
    boolean isOn = v?.toString()?.trim() == meta.payloadOn?.toString()
    mem("nightlightOn").put(dni, isOn)
    def expected = mem("probeExpect").get(dni)
    if (expected != null && expected == isOn) mem("probeEcho").put(dni, nowMs)
}

def handleStateMessage(String topic, String payload) {
    Map cat = catalog()
    def entries = cat.topicToEntity?.get(topic)
    if (!entries) return
    Map discovered = cat.discovered ?: [:]
    long nowMs = now()

    entries.each { entry ->
        def meta = discovered.get(entry.dni)?.entities?.get(entry.attr)
        if (!meta) return

        def kind = entry.kind ?: "state"
        // Recorded for every unit, before the active check below - probing runs before a
        // device is marked active, and the device picker's online flag needs this too.
        mem("lastNotifySeen").put(entry.dni, nowMs)
        if (entry.attr == "nightlight" && kind == "state") noteNightlightState(entry.dni, meta, payload, nowMs)

        boolean isActive = isActiveDni(entry.dni)
        String key = "${entry.dni}|${entry.attr}".toString()

        if (kind == "brightness") {
            def raw = meta.brightnessValueTemplate ? applyValueTemplate(meta.brightnessValueTemplate, payload) : payload
            def scale = (meta.brightnessScale ?: 255) as Integer
            def num = toNumberOrNull(raw)
            if (num == null) return
            def level = Math.round((num / scale) * 100)
            route("${key}#level".toString(), [kind: "level", dni: entry.dni, value: level], 1000, isActive)
            return
        }

        if (kind == "hs") {
            // Home Assistant's MQTT light hs_state_topic payload is "hue,saturation"
            // as plain numbers - hue in degrees (0-360), saturation as a percent (0-100).
            def parts = payload?.toString()?.split(",")
            if (!parts || parts.size() < 2) return
            def hueDegrees = toNumberOrNull(parts[0].trim())
            def satPercent = toNumberOrNull(parts[1].trim())
            if (hueDegrees == null || satPercent == null) return
            def huePercent = (hueDegrees / 360 * 100).setScale(1, java.math.RoundingMode.HALF_UP)
            def sat = satPercent.setScale(1, java.math.RoundingMode.HALF_UP)
            route("${key}#hs".toString(), [kind: "color", dni: entry.dni, value: "${huePercent},${sat}".toString(), hue: huePercent, sat: sat], 1000, isActive)
            return
        }

        if (kind == "colorTemp") {
            def num = toNumberOrNull(payload)
            if (num == null) return
            route("${key}#ct".toString(), [kind: "colorTemp", dni: entry.dni, value: num as Integer], 1000, isActive)
            return
        }

        def value = meta.valueTemplate ? applyValueTemplate(meta.valueTemplate, payload) : payload

        switch (meta.platform) {
            case "event":
                if (!isActive) break
                def matches = (meta.expectedPayload == null) || (payload?.toString()?.trim() == meta.expectedPayload.toString().trim())
                if (logEnable) log.debug "event on ${topic}: payload='${payload}' expected='${meta.expectedPayload}' matches=${matches}"
                if (matches && !isRepeatPress(key, 500)) parent?.forwardButtonEvent(entry.dni, entry.attr)
                break
            case "switch":
            case "light":
                def isOn = value?.toString()?.trim() == meta.payloadOn?.toString()
                // No minimum interval - an outlet change is forwarded immediately.
                route(key, entityUpdate(entry, meta, isOn, meta.unit), 0, isActive)
                break
            case "binary_sensor":
                def v = value?.toString()?.trim()
                def active = (v == meta.payloadOn?.toString()) || v?.equalsIgnoreCase("on") || v == "1"
                if (meta.isButton) {
                    if (isActive && active && !isRepeatPress(key, 500)) parent?.forwardButtonEvent(entry.dni, entry.attr)
                } else {
                    route(key, entityUpdate(entry, meta, active, meta.unit), 1500, isActive)
                }
                break
            default:
                def numeric = toNumberOrNull(value)
                if (numeric != null && meta.deviceClass == "temperature") {
                    // Raw value cached so a C/F change can be re-applied immediately.
                    mem("lastRawTemp").put(key, [value: numeric, unit: meta.unit])
                    def converted = convertTemperatureForDisplay(numeric, meta.unit)
                    route(key, entityUpdate(entry, meta, converted.value, converted.unit), 5000, isActive)
                } else if (numeric != null && entry.attr == "distance") {
                    mem("lastRawDistanceCm").put(key, numeric)
                    def converted = convertDistanceForDisplay(numeric)
                    route(key, entityUpdate(entry, meta, converted.value, converted.unit), 5000, isActive)
                } else if (value != null) {
                    // Humidity, illuminance, AQI, CO2, air pressure, VOC, and any other sensor.
                    route(key, entityUpdate(entry, meta, numeric != null ? numeric : value, meta.unit), 5000, isActive)
                }
                break
        }
    }
}

// The app's unit settings, cached for a minute - these are read on every temperature
// and distance message, and each read is a call into the parent app.
private String cachedSetting(String name, Closure fetch) {
    Map c = mem("settingsCache")
    Map hit = (Map) c.get(name)
    if (hit && (now() - (hit.time as Long)) < 60000) return hit.value
    String v = fetch()?.toString()
    if (v != null) c.put(name, [value: v, time: now()])
    return v
}

private String temperatureScale() {
    String v = cachedSetting("temperatureScale", { parent?.getTemperatureScale() })
    return v ?: "F"
}

private String distanceUnit() {
    String v = cachedSetting("distanceUnit", { parent?.getDistanceUnit() })
    return v ?: "in"
}

// Converts a raw temperature reading to whichever unit the app's "Temperature Scale"
// setting specifies, regardless of which unit the device itself reports.
def convertTemperatureForDisplay(value, fromUnit) {
    def v = value as BigDecimal
    def desired = temperatureScale()
    def fromIsF = fromUnit?.toString()?.contains("F")
    def valueC = fromIsF ? ((v - 32) * 5 / 9) : v
    def result = ((desired == "F") ? (valueC * 9 / 5 + 32) : valueC) as BigDecimal
    return [value: result.setScale(1, java.math.RoundingMode.HALF_UP), unit: (desired == "F") ? "°F" : "°C"]
}

// Re-pushes already-known temperature readings using the app's current setting, so
// flipping C/F updates the device immediately instead of waiting for the next publish.
def recomputeTemperatures() {
    mem("settingsCache").remove("temperatureScale")
    mem("lastRawTemp").each { key, raw ->
        def parts = key.toString().split("\\|")
        if (parts.size() < 2 || !isActiveDni(parts[0])) return
        def converted = convertTemperatureForDisplay(raw.value, raw.unit)
        def upd = [kind: "entity", dni: parts[0], attr: parts[1], platform: "sensor", deviceClass: "temperature", value: converted.value, unit: converted.unit]
        mem("lastReceived").put(key, upd)
        gateForward(key, upd, 0)
    }
}

// Converts a raw distance reading (device always reports cm) to whichever unit the
// app's "Measurement Scale" setting specifies.
def convertDistanceForDisplay(valueCm) {
    def v = valueCm as BigDecimal
    def desired = distanceUnit()
    if (desired == "in") {
        return [value: (v / 2.54).setScale(1, java.math.RoundingMode.HALF_UP), unit: "in"]
    }
    return [value: v.setScale(1, java.math.RoundingMode.HALF_UP), unit: "cm"]
}

def recomputeDistances() {
    mem("settingsCache").remove("distanceUnit")
    mem("lastRawDistanceCm").each { key, rawCm ->
        def parts = key.toString().split("\\|")
        if (parts.size() < 2 || !isActiveDni(parts[0])) return
        def converted = convertDistanceForDisplay(rawCm)
        def upd = [kind: "entity", dni: parts[0], attr: parts[1], platform: "sensor", deviceClass: "distance", value: converted.value, unit: converted.unit]
        mem("lastReceived").put(key, upd)
        gateForward(key, upd, 0)
    }
}

def applyValueTemplate(String template, String payload) {
    if (!template) return payload
    def inner = template.trim().replaceAll(/^\{\{\s*/, "").replaceAll(/\s*\}\}$/, "")

    // Drop any Jinja filter chain after '|' (e.g. "value_json.distance | round(1)") -
    // we can't run Jinja filters, but grabbing the raw value is far better than nothing.
    def pipeIdx = inner.indexOf('|')
    if (pipeIdx >= 0) inner = inner.substring(0, pipeIdx).trim()

    if (inner == "value") return payload

    if (inner.startsWith("value_json")) {
        def path = inner.substring("value_json".length())
        def keys = []
        def matcher = (path =~ /\.([a-zA-Z0-9_]+)|\[['"]([a-zA-Z0-9_]+)['"]\]/)
        while (matcher.find()) {
            def k = matcher.group(1) ?: matcher.group(2)
            if (k) keys << k
        }
        if (!keys) return payload
        try {
            def node = new JsonSlurper().parseText(payload)
            keys.each { k -> node = (node instanceof Map) ? node[k] : null }
            return node
        } catch (e) {
            log.warn "Failed applying value_template '${template}' to '${payload}': ${e}"
            return payload
        }
    }

    if (logEnable) log.debug "Unsupported value_template '${template}' - returning raw payload"
    return payload
}

def toNumberOrNull(v) {
    if (v == null) return null
    try { return v.toString().toBigDecimal() } catch (e) { return null }
}

// ---------------- outgoing commands (called by the app on behalf of a device) ----------------

def entityMeta(String dni, String attrName) {
    return catalog().discovered?.get(dni)?.entities?.get(attrName)
}

// Actively confirms every discovered device is still real and reachable, rather than
// passively waiting to see if it happens to publish something within some timeout.
// InvisHome does not appear to clear a device's retained MQTT discovery message when
// it's removed from the phone app - that message can keep sitting on the broker
// indefinitely, completely independent of whether the physical unit still exists,
// which means passive "have we heard anything recently" timing can't tell a genuinely
// removed device apart from one that's simply quiet for a moment. This briefly
// commands each device's nightlight on then off (every InvisOutlet model has one -
// Pro's plain nightlight and Aura's colorlight both map to the same "nightlight"
// attribute) and watches for a real MQTT state echo confirming the command actually
// took effect on real hardware, not just that the broker accepted a publish.
//
// 2.1.0: the light is flipped to the OPPOSITE of its last known state and then back,
// so a unit whose nightlight was on is left on. Only a state message carrying the value
// actually commanded counts as an echo - a retained or periodically republished message
// showing the old state can no longer "confirm" a unit that isn't really there.
//
// Probes the WHOLE queue of dnis internally, one at a time, entirely on this device -
// the app only calls this ONCE and reads getProbeResults() ONCE afterward, rather than
// going back and forth per-device, which real testing showed was unreliable regardless
// of exact wait shape. Each device's own confirm logic below (comparing a before/after
// timestamp from probeQueueFinalize's own fresh runInMillis execution) only needs the
// app to read a plain method call return value (getProbeResults()) once at the very end.
def probeAllDevices(List<String> dnis) {
    // Cancels any pending runInMillis steps left over from a PREVIOUS call to this
    // method that might still be scheduled - Hubitat's dynamicPage/content methods are
    // known to sometimes execute more than once per single page load, and if
    // probeAllDevices() got called a second time while the first run's scheduled steps
    // (probeQueueSendOff/probeQueueFinalize) were still pending, those stale callbacks
    // would later fire using state values that this NEW call is about to overwrite -
    // misattributing one device's real echo to a completely different device's
    // confirmation window. Only one probing run should ever be in flight at a time.
    unschedule("probeQueueSendOff")
    unschedule("probeQueueFinalize")

    state.probeQueue = new ArrayList(dnis ?: [])
    state.probeResults = [:]
    mem("probeExpect").clear()
    mem("probeEcho").clear()
    probeNextInQueue()
}

def probeNextInQueue() {
    def queue = state.probeQueue ?: []
    if (queue.isEmpty()) {
        log.info "Probing complete: ${state.probeResults}"
        return
    }

    def dni = queue.remove(0)
    state.probeQueue = queue
    state.currentProbeDni = dni

    def meta = entityMeta(dni, "nightlight")
    if (!meta?.commandTopic) {
        log.warn "Probe for ${dni}: no 'nightlight' entity found in the discovery catalog (or it has no command topic) - this usually means discovery for this device hasn't completed yet, not that the hardware itself is unreachable"
        state.probeResults[dni] = "unsupported"
        probeNextInQueue()
        return
    }

    // Unknown state is treated as off, so the first step turns it on (the old behavior).
    boolean firstOn = mem("nightlightOn").get(dni) != true
    state.probeFirstOn = firstOn
    state.probeBeforeTime = now()
    mem("probeEcho").remove(dni)
    mem("probeExpect").put(dni, firstOn)

    def firstPayload = firstOn ? (meta.payloadOn ?: "ON") : (meta.payloadOff ?: "OFF")
    log.info "Probing ${dni}: publishing '${firstPayload}' to ${meta.commandTopic}"
    interfaces.mqtt.publish(meta.commandTopic, firstPayload)

    runInMillis(1500, "probeQueueSendOff")
}

// Second probe step - puts the light back the way it was (name kept from when this
// always meant "off", so the unschedule() calls above still match).
def probeQueueSendOff() {
    def dni = state.currentProbeDni
    def meta = entityMeta(dni, "nightlight")
    if (meta?.commandTopic) {
        def restorePayload = (state.probeFirstOn != false) ? (meta.payloadOff ?: "OFF") : (meta.payloadOn ?: "ON")
        log.info "Probing ${dni}: publishing '${restorePayload}' to ${meta.commandTopic}"
        interfaces.mqtt.publish(meta.commandTopic, restorePayload)
    }
    runInMillis(1500, "probeQueueFinalize")
}

def probeQueueFinalize() {
    def dni = state.currentProbeDni
    def echoTime = mem("probeEcho").get(dni) as Long
    mem("probeExpect").remove(dni)
    def result = (echoTime != null && echoTime > ((state.probeBeforeTime ?: 0) as Long)) ? "confirmed" : "no_response"
    state.probeResults[dni] = result
    log.info "Probe result for ${dni}: ${result}"
    probeNextInQueue()
}

// Called by the app ONCE, after waiting out the expected total probing duration -
// a plain method call returning a value directly, not an attribute/currentValue()
// read, since that mechanism is what turned out to be unreliable across the wait.
def getProbeResults() {
    return state.probeResults ?: [:]
}

// Lets the app compute how long to wait: unsupported devices resolve instantly, real
// probes take ~3s each (two 1500ms steps), run strictly one at a time.
def estimateProbeDurationMs(List<String> dnis) {
    def total = 0
    (dnis ?: []).each { dni ->
        def meta = entityMeta(dni, "nightlight")
        total += meta?.commandTopic ? 3200 : 50
    }
    return total
}

def publishSwitch(String dni, String attrName, boolean turnOn) {
    def meta = entityMeta(dni, attrName)
    if (!meta?.commandTopic) {
        log.warn "No command topic known for '${attrName}' on device ${dni}"
        return
    }
    def payload = turnOn ? (meta.payloadOn ?: "ON") : (meta.payloadOff ?: "OFF")
    if (logEnable) log.debug "Publishing '${payload}' to ${meta.commandTopic}"
    interfaces.mqtt.publish(meta.commandTopic, payload)
}

def publishLevel(String dni, String attrName, level) {
    def meta = entityMeta(dni, attrName)
    // Level 0 means "off" (as with any Hubitat dimmer) - publishing brightness 0 alone
    // left the light reported as on.
    if ((level as Double) <= 0 && meta?.commandTopic) {
        publishSwitch(dni, attrName, false)
        return
    }
    if (!meta?.brightnessCommandTopic) {
        log.warn "No brightness command topic known for '${attrName}' on device ${dni}"
        return
    }
    def scale = (meta.brightnessScale ?: 255) as Integer
    def scaledLevel = Math.round(((level as Double) / 100) * scale)
    interfaces.mqtt.publish(meta.brightnessCommandTopic, scaledLevel.toString())
    if ((level as Double) > 0 && meta.commandTopic) publishSwitch(dni, attrName, true)
}

// huePercent/satPercent are Hubitat's usual 0-100 scale; converted to the 0-360
// degree hue that Home Assistant's MQTT light hs_command_topic expects. Only
// works on units whose light entity actually has a color command topic (the
// Aura's colorlight) - the Pro's plain nightlight doesn't support color at all.
def publishHueSat(String dni, String attrName, huePercent, satPercent) {
    def meta = entityMeta(dni, attrName)
    if (!meta?.hsCommandTopic) {
        log.warn "Device ${dni}: '${attrName}' has no color command topic - this unit likely doesn't support color"
        return
    }
    def hueDegrees = ((huePercent as Double) / 100 * 360)
    def payload = "${hueDegrees.round(1)},${(satPercent as Double).round(1)}"
    if (logEnable) log.debug "Publishing '${payload}' to ${meta.hsCommandTopic}"
    interfaces.mqtt.publish(meta.hsCommandTopic, payload)
}

// Reports Kelvin directly (matches Hubitat's own ColorTemperature scale, no
// conversion needed) - confirmed via this device's real discovery payload
// ("color_temp_kelvin": true).
def publishColorTemp(String dni, String attrName, kelvin) {
    def meta = entityMeta(dni, attrName)
    if (!meta?.colorTempCommandTopic) {
        log.warn "Device ${dni}: '${attrName}' has no color-temperature command topic - this unit likely doesn't support it"
        return
    }
    if (logEnable) log.debug "Publishing '${kelvin}' to ${meta.colorTempCommandTopic}"
    interfaces.mqtt.publish(meta.colorTempCommandTopic, (kelvin as Integer).toString())
}

// ---------------- housekeeping / diagnostics ----------------

def clearDiscoveryCache() {
    withCatalogLock {
        state.discovered = [:]
        state.topicToEntity = [:]
        Map cat = mem("catalog")
        cat.put("discovered", [:])
        cat.put("topicToEntity", [:])
    }
    ["seenTopics", "skippedTopics", "lastRawTemp", "lastRawDistanceCm", "lastNotifySeen",
     "lastForwarded", "lastReceived", "pending", "lastPress", "nightlightOn"].each { mem(it).clear() }
    sendEvent(name: "deviceCount", value: 0)
    // Re-subscribing makes the broker re-deliver every retained discovery message, so the
    // catalog rebuilds now instead of state messages being ignored until a reconnect.
    try {
        if (interfaces.mqtt.isConnected()) resubscribe()
    } catch (e) {
        log.warn "Could not resubscribe after clearing the discovery cache: ${e}"
    }
}

// Prints a summary to the logs of every MQTT topic seen since the hub last restarted
// (or since the cache was last cleared): how many are wired up to a known entity, how
// many were seen but explicitly skipped (with why), and how many were seen but never
// even reached a skip check. Run this, then toggle whatever feature you're trying to
// find (e.g. a light effect) and run it again - the new/changed line is your answer.
def auditTopics() {
    def seen = (mem("seenTopics").keySet() ?: []) as Set
    def known = (catalog().topicToEntity?.keySet() ?: []) as Set
    def skipped = mem("skippedTopics")

    def configTopics = seen.findAll { it.toString().endsWith("/config") }
    def stateTopics = seen - configTopics
    def unknownState = stateTopics - known

    log.info "=== InvisOutlet topic audit ==="
    log.info "Discovery/config messages seen: ${configTopics.size()}"
    log.info "State topics seen: ${stateTopics.size()} total, ${known.size()} wired to a known attribute, ${unknownState.size()} NOT wired to anything"
    if (unknownState) {
        log.info "--- Unwired state topics (device is publishing, we're not using it) ---"
        unknownState.each { t -> log.info "  ${t}" }
    }
    if (skipped) {
        log.info "--- Discovery entries seen but skipped, with reason ---"
        skipped.each { t, reason -> log.info "  ${t} -> ${reason}" }
    }
    if (!unknownState && !skipped) {
        log.info "Nothing unaccounted for - every topic seen so far is either wired up or was never published."
    }
    log.info "=== end audit ==="
}

// Logs the key-count of every individual structure kept in state and in memory, plus a
// rough total serialized state size - run this and compare against a later run to see
// EXACTLY which structure is actually growing, rather than guessing. Every structure here
// is supposed to be bounded by (number of devices) x (number of attributes), overwriting
// existing keys rather than appending new ones once discovery has settled - if one of
// these counts keeps climbing well past that expectation, that's the leak.
def auditStateSize() {
    def counts = [
        topicToEntity     : state.topicToEntity?.size() ?: 0,
        discovered        : state.discovered?.size() ?: 0,
        activeDnis        : state.activeDnis?.size() ?: 0,
        probeQueue        : state.probeQueue?.size() ?: 0,
        probeResults      : state.probeResults?.size() ?: 0
    ]
    def memCounts = [:]
    ["seenTopics", "skippedTopics", "lastRawTemp", "lastRawDistanceCm", "lastNotifySeen",
     "lastForwarded", "lastReceived", "pending", "lastPress", "nightlightOn"].each { memCounts[it] = mem(it).size() }

    log.info "=== InvisOutlet state size audit ==="
    counts.each { name, count -> log.info "  state.${name}: ${count} key(s)" }
    memCounts.each { name, count -> log.info "  memory ${name}: ${count} key(s)" }

    try {
        def json = JsonOutput.toJson(state)
        log.info "Total serialized state size: ${json.length()} bytes (Hubitat's own reported number may differ slightly, but should track closely)"
    } catch (e) {
        log.warn "Could not compute serialized size directly: ${e}"
    }
    log.info "=== end state size audit ==="
}
