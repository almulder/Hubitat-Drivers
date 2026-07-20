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
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

def clientVersion() { "1.1.1" }
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
    "on_cmd_type": "on_command_type", "opt": "optimistic", "ret": "retain",
    "mf": "manufacturer", "mdl": "model", "sw": "sw_version", "hw": "hw_version",
    "ids": "identifiers", "cu": "configuration_url", "sa": "suggested_area"
]

metadata {
    definition(name: "InvisOutlet MQTT Listener", namespace: "almulder", author: "Albert Mulder") {
        capability "Initialize"
        capability "Configuration"
        capability "Refresh"

        attribute "connectionStatus", "string"
        attribute "deviceCount", "number"

        command "connect"
        command "disconnect"
        command "resubscribe"
        command "clearDiscoveryCache"
        command "auditTopics"
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
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "rawTrafficLogging", type: "bool", title: "Log raw MQTT traffic (topic + payload) - very verbose, use temporarily", defaultValue: false
    }
}

// ---------------- lifecycle ----------------

def installed() {
    state.topicToEntity = [:]
    state.discovered = [:]
}

def initialize() {
    if (state.topicToEntity == null) state.topicToEntity = [:]
    if (state.discovered == null) state.discovered = [:]
    connect()
}

def uninstalled() {
    disconnect()
}

def refresh() {
    resubscribe()
}

// Called by the app right after creating this device, and again any time its MQTT
// settings change. Just stores the values - call initialize() separately to (re)connect.
def configure(Map cfg) {
    state.brokerUri = cfg.broker
    state.mqttUser = cfg.username
    state.mqttPassword = cfg.password
    state.discoveryPrefix = cfg.prefix ?: "homeassistant"
    state.deviceFilter = cfg.filter ?: ""
}

// Called by the app once setup is finished, so this device knows which discovered
// units are actually wanted - MQTT updates for anything else are still tracked for
// online/offline purposes but not forwarded anywhere.
def registerActiveDevices(List<String> dnis) {
    state.activeDnis = dnis as Set
    sendEvent(name: "deviceCount", value: dnis?.size() ?: 0)
}

// ---------------- MQTT connection ----------------

def connect() {
    try {
        if (!state.brokerUri) {
            log.warn "MQTT broker not configured yet"
            return
        }
        interfaces.mqtt.connect(
            state.brokerUri,
            "hubitat-invisoutlet-${device.id}",
            state.mqttUser ?: null,
            state.mqttPassword ?: null
        )
    } catch (e) {
        log.error "MQTT connect failed: ${e}"
        sendEvent(name: "connectionStatus", value: "error")
        runIn(30, "connect")
    }
}

def disconnect() {
    try {
        interfaces.mqtt.disconnect()
    } catch (ignored) { }
    sendEvent(name: "connectionStatus", value: "disconnected")
}

def resubscribe() {
    def prefix = state.discoveryPrefix ?: "homeassistant"

    // Explicitly unsubscribe from every topic we've ever tracked first. MQTT sessions
    // can retain old subscriptions across a disconnect/reconnect, so a topic subscribed
    // to individually in a previous session can keep causing duplicate delivery even
    // after the code stops re-issuing that subscribe. This forces a clean slate.
    state.topicToEntity?.keySet()?.each { t ->
        try { interfaces.mqtt.unsubscribe(t) } catch (ignored) { }
    }

    if (logEnable) log.debug "Subscribing to ${prefix}/#"
    interfaces.mqtt.subscribe("${prefix}/#")
    // Only add a topic explicitly if it's OUTSIDE the wildcard above - subscribing to
    // both "prefix/#" and a specific topic already covered by it causes most brokers
    // (including Mosquitto) to deliver every message on that topic twice.
    state.topicToEntity?.keySet()?.each { t -> subscribeIfNeeded(t) }
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
        runIn(1, "resubscribe")
    } else if (status.startsWith("Error") || status.startsWith("Failure")) {
        log.warn "MQTT connection issue: ${status}"
        sendEvent(name: "connectionStatus", value: "error")
        runIn(15, "connect")
    }
}

// ---------------- incoming messages ----------------

def parse(String description) {
    def msg
    try {
        msg = interfaces.mqtt.parseMessage(description)
    } catch (e) {
        log.error "Could not parse MQTT message: ${e}"
        return
    }
    def topic = msg.topic
    def payload = msg.payload
    def prefix = state.discoveryPrefix ?: "homeassistant"

    if (rawTrafficLogging) log.info "RAW MQTT: topic='${topic}' payload='${payload}'"

    if (state.seenTopics == null) state.seenTopics = [] as Set
    state.seenTopics << topic

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

def handleDiscoveryMessage(String topic, String payload) {
    if (!payload || payload.trim() == "") {
        if (logEnable) log.debug "Empty discovery payload on ${topic} (entity likely removed upstream)"
        return
    }

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
    if (component == "device" && components instanceof Map) {
        def deviceInfo = normalized.device ?: [:]
        if (!deviceMatchesFilter(deviceInfo)) {
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
        if (!deviceMatchesFilter(deviceInfo)) {
            recordSkip(topic, "device_automation filter mismatch")
            return
        }
        handleDeviceAutomation(normalized, objectId, deviceInfo)
        return
    }

    def deviceInfo = normalized.device ?: [:]
    if (!deviceMatchesFilter(deviceInfo)) {
        recordSkip(topic, "entity filter mismatch")
        return
    }
    registerEntity(normalized, component, objectId, deviceInfo)
}

def recordSkip(String topic, String reason) {
    if (state.skippedTopics == null) state.skippedTopics = [:]
    state.skippedTopics[topic] = reason
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

def deviceMatchesFilter(Map deviceInfo) {
    def filter = (state.deviceFilter ?: "").trim().toLowerCase()
    if (!filter) return true
    def idText = (deviceInfo?.identifiers instanceof List) ? deviceInfo.identifiers.join(" ") : deviceInfo?.identifiers
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
    ["state_topic", "command_topic", "availability_topic", "json_attributes_topic",
     "brightness_state_topic", "brightness_command_topic"].each { key ->
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
    def entry = state.discovered ? state.discovered[dni] : null
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

    if (state.discovered == null) state.discovered = [:]
    if (state.discovered[dni] == null) state.discovered[dni] = [deviceInfo: deviceInfo, entities: [:]]
    state.discovered[dni].deviceInfo = deviceInfo

    def isButton = isButtonLikeEntity(platform, cfg)
    state.discovered[dni].entities[attrName] = [
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

    [
        [cfg.state_topic, "state"],
        [cfg.brightness_state_topic, "brightness"],
        [cfg.hs_state_topic, "hs"],
        [cfg.color_temp_state_topic, "colorTemp"]
    ].each { pair ->
        def t = pair[0]
        def kind = pair[1]
        if (t) {
            if (state.topicToEntity == null) state.topicToEntity = [:]
            def list = state.topicToEntity[t] ?: []
            if (!list.any { it.dni == dni && it.attr == attrName && it.kind == kind }) {
                list << [dni: dni, attr: attrName, kind: kind]
            }
            state.topicToEntity[t] = list
            subscribeIfNeeded(t)
        }
    }

    log.info "Discovered '${cfg.name ?: objectId}' -> attribute '${attrName}' (platform=${platform}, class=${cfg.device_class}) for device ${dni}"
}

// Called by the app's device-selection page.
def getDiscoveredDevices() {
    def result = []
    state.discovered?.each { dni, entry ->
        def model = computeModel(dni)
        def lastSeen = (state.lastNotifySeen ?: [:])[dni]
        def online = lastSeen && (now() - (lastSeen as Long)) < 60000
        result << [dni: dni, name: (entry.deviceInfo?.name ?: dni), model: model, online: online]
    }
    return result
}

// ---------------- state updates ----------------

// Some devices/firmwares publish more than one confirmation message per action
// (e.g. an optimistic echo plus a sensor-verified follow-up, or the same state
// mirrored on more than one topic). If the identical value shows up again for the
// same attribute within windowMs, treat it as a duplicate and suppress it.
def isDuplicateUpdate(String dni, String attr, value, long windowMs) {
    if (state.lastValueTime == null) state.lastValueTime = [:]
    def key = "${dni}|${attr}"
    def nowMs = now()
    def prev = state.lastValueTime[key]
    def valueStr = value.toString()
    def dup = prev && prev.value == valueStr && (nowMs - (prev.time as Long)) < windowMs
    state.lastValueTime[key] = [value: valueStr, time: nowMs]
    return dup
}

// NEW in 1.1.1: caps how often we'll forward a given attribute upstream, independent of
// whether the value is an exact repeat of the last one. The Aura/Pro units republish their
// full sensor telemetry on a roughly 1-second cycle even when nothing meaningfully changed,
// and isDuplicateUpdate() alone doesn't catch that for readings that jitter slightly each
// cycle (e.g. illuminance, distance, air quality). This throttle caps call frequency itself,
// which is what was actually tripping Hubitat's "excessive hub load" governor - the
// binary_sensor (motion/occupancy) and default numeric branch below had no rate limiting
// of any kind before this version.
def isThrottled(String dni, String attr, long minIntervalMs) {
    if (state.lastForwardTime == null) state.lastForwardTime = [:]
    def key = "${dni}|${attr}"
    def nowMs = now()
    def last = state.lastForwardTime[key]
    if (last && (nowMs - (last as Long)) < minIntervalMs) return true
    state.lastForwardTime[key] = nowMs
    return false
}

def handleStateMessage(String topic, String payload) {
    def entries = state.topicToEntity ? state.topicToEntity[topic] : null
    if (!entries) return

    entries.each { entry ->
        def entityMap = state.discovered ? state.discovered[entry.dni]?.entities : null
        def meta = entityMap ? entityMap[entry.attr] : null
        if (!meta) return

        if (state.lastNotifySeen == null) state.lastNotifySeen = [:]
        state.lastNotifySeen[entry.dni] = now()

        def isActive = (state.activeDnis ?: []).contains(entry.dni)
        if (!isActive) return // still tracked for online/offline purposes, just not forwarded

        def kind = entry.kind ?: "state"

        if (kind == "brightness") {
            def raw = meta.brightnessValueTemplate ? applyValueTemplate(meta.brightnessValueTemplate, payload) : payload
            def scale = (meta.brightnessScale ?: 255) as Integer
            def num = toNumberOrNull(raw)
            if (num == null) return
            def level = Math.round((num / scale) * 100)
            parent?.forwardUpdateLevel(entry.dni, level)
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
            parent?.forwardUpdateColor(entry.dni, huePercent, satPercent.setScale(1, java.math.RoundingMode.HALF_UP))
            return
        }

        if (kind == "colorTemp") {
            def num = toNumberOrNull(payload)
            if (num == null) return
            parent?.forwardUpdateColorTemperature(entry.dni, num as Integer)
            return
        }

        def value = meta.valueTemplate ? applyValueTemplate(meta.valueTemplate, payload) : payload

        switch (meta.platform) {
            case "event":
                def matches = (meta.expectedPayload == null) || (payload?.toString()?.trim() == meta.expectedPayload.toString().trim())
                if (logEnable) log.debug "event on ${topic}: payload='${payload}' expected='${meta.expectedPayload}' matches=${matches}"
                if (matches && !isDuplicateUpdate(entry.dni, entry.attr, "press", 500)) parent?.forwardButtonEvent(entry.dni, entry.attr)
                break
            case "switch":
            case "light":
                def isOn = value?.toString()?.trim() == meta.payloadOn?.toString()
                if (!isDuplicateUpdate(entry.dni, entry.attr, isOn, 2000)) {
                    parent?.forwardUpdateEntity(entry.dni, entry.attr, meta.platform, meta.deviceClass, isOn, meta.unit)
                } else if (logEnable) {
                    log.debug "Suppressing duplicate '${entry.attr}' update (${isOn}) - device appears to have republished the same state"
                }
                break
            case "binary_sensor":
                def v = value?.toString()?.trim()
                def active = (v == meta.payloadOn?.toString()) || v?.equalsIgnoreCase("on") || v == "1"
                if (meta.isButton) {
                    if (active && !isDuplicateUpdate(entry.dni, entry.attr, "press", 500)) parent?.forwardButtonEvent(entry.dni, entry.attr)
                } else if (!isDuplicateUpdate(entry.dni, entry.attr, active, 2000) && !isThrottled(entry.dni, entry.attr, 1500)) {
                    // 1.1.1: motion/occupancy previously had no rate limiting at all.
                    parent?.forwardUpdateEntity(entry.dni, entry.attr, meta.platform, meta.deviceClass, active, meta.unit)
                } else if (logEnable) {
                    log.debug "Suppressing '${entry.attr}' update (${active}) - duplicate or too frequent"
                }
                break
            default:
                def numeric = toNumberOrNull(value)
                if (numeric != null && meta.deviceClass == "temperature") {
                    if (state.lastRawTemp == null) state.lastRawTemp = [:]
                    if (state.lastRawTemp[entry.dni] == null) state.lastRawTemp[entry.dni] = [:]
                    state.lastRawTemp[entry.dni][entry.attr] = [value: numeric, unit: meta.unit]
                    // 1.1.1: raw value is still cached every time (so a C/F toggle recompute
                    // stays accurate), but the actual forward upstream is now throttled.
                    if (!isThrottled(entry.dni, entry.attr, 5000)) {
                        def converted = convertTemperatureForDisplay(numeric, meta.unit)
                        parent?.forwardUpdateEntity(entry.dni, entry.attr, meta.platform, meta.deviceClass, converted.value, converted.unit)
                    }
                } else if (numeric != null && entry.attr == "distance") {
                    if (state.lastRawDistanceCm == null) state.lastRawDistanceCm = [:]
                    if (state.lastRawDistanceCm[entry.dni] == null) state.lastRawDistanceCm[entry.dni] = [:]
                    state.lastRawDistanceCm[entry.dni][entry.attr] = numeric
                    if (!isThrottled(entry.dni, entry.attr, 5000)) {
                        def converted = convertDistanceForDisplay(numeric)
                        parent?.forwardUpdateEntity(entry.dni, entry.attr, meta.platform, meta.deviceClass, converted.value, converted.unit)
                    }
                } else if (!isThrottled(entry.dni, entry.attr, 5000)) {
                    // 1.1.1: covers humidity, illuminance, AQI, CO2, air pressure, VOC, and
                    // any other numeric sensor that previously flooded through unguarded.
                    parent?.forwardUpdateEntity(entry.dni, entry.attr, meta.platform, meta.deviceClass, numeric != null ? numeric : value, meta.unit)
                }
                break
        }
    }
}

// Converts a raw temperature reading to whichever unit the app's "Temperature Scale"
// setting specifies, regardless of which unit the device itself reports.
def convertTemperatureForDisplay(BigDecimal value, fromUnit) {
    def desired = parent?.getTemperatureScale() ?: "C"
    def fromIsF = fromUnit?.toString()?.contains("F")
    def valueC = fromIsF ? ((value - 32) * 5 / 9) : value
    def result = (desired == "F") ? (valueC * 9 / 5 + 32) : valueC
    return [value: result.setScale(1, java.math.RoundingMode.HALF_UP), unit: (desired == "F") ? "°F" : "°C"]
}

// Re-pushes already-known temperature readings using the app's current setting, so
// flipping C/F updates the device immediately instead of waiting for the next publish.
def recomputeTemperatures() {
    state.lastRawTemp?.each { dni, attrs ->
        if (!(state.activeDnis ?: []).contains(dni)) return
        attrs.each { attrName, raw ->
            def converted = convertTemperatureForDisplay(raw.value, raw.unit)
            parent?.forwardUpdateEntity(dni, attrName, "sensor", "temperature", converted.value, converted.unit)
        }
    }
}

// Converts a raw distance reading (device always reports cm) to whichever unit the
// app's "Measurement Scale" setting specifies.
def convertDistanceForDisplay(BigDecimal valueCm) {
    def desired = parent?.getDistanceUnit() ?: "cm"
    if (desired == "in") {
        return [value: (valueCm / 2.54).setScale(1, java.math.RoundingMode.HALF_UP), unit: "in"]
    }
    return [value: valueCm.setScale(1, java.math.RoundingMode.HALF_UP), unit: "cm"]
}

def recomputeDistances() {
    state.lastRawDistanceCm?.each { dni, attrs ->
        if (!(state.activeDnis ?: []).contains(dni)) return
        attrs.each { attrName, rawCm ->
            def converted = convertDistanceForDisplay(rawCm)
            parent?.forwardUpdateEntity(dni, attrName, "sensor", "distance", converted.value, converted.unit)
        }
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
    def entityMap = state.discovered ? state.discovered[dni]?.entities : null
    return entityMap ? entityMap[attrName] : null
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
    state.discovered = [:]
    state.topicToEntity = [:]
    state.seenTopics = [] as Set
    state.skippedTopics = [:]
    state.lastRawTemp = [:]
    state.lastRawDistanceCm = [:]
    state.lastNotifySeen = [:]
    state.lastForwardTime = [:]  // 1.1.1: new throttle-tracking map
    sendEvent(name: "deviceCount", value: 0)
}

// Prints a summary to the logs of every MQTT topic seen since the hub last restarted
// (or since the cache was last cleared): how many are wired up to a known entity, how
// many were seen but explicitly skipped (with why), and how many were seen but never
// even reached a skip check. Run this, then toggle whatever feature you're trying to
// find (e.g. a light effect) and run it again - the new/changed line is your answer.
def auditTopics() {
    def seen = (state.seenTopics ?: []) as Set
    def known = (state.topicToEntity?.keySet() ?: []) as Set
    def skipped = state.skippedTopics ?: [:]

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
