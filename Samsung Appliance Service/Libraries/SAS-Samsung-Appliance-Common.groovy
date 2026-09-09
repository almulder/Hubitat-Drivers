/**
 *  SAS - Samsung Appliance Common Library
 *
 *  Shared helpers for the Samsung Appliance Service child drivers: entity
 *  suffix mapping, per-attribute throttling with trailing flush, exact-value
 *  de-duplication, and Home Assistant service-call plumbing.
 *
 *  Version: 0.5.2
 *  Author:  Albert Mulder (almulder)
 *
 *  Install this library BEFORE the child drivers -- they will not compile
 *  without it.
 */

library(
    author: "Albert Mulder",
    category: "Integration",
    description: "SAS - Samsung Appliance Common: shared helpers for the SAS appliance drivers",
    name: "SAS-Samsung-Appliance-Common",
    namespace: "almulder",
    documentationLink: ""
)

import groovy.transform.Field

// ---------------------------------------------------------------------------
// Throttling policy
// ---------------------------------------------------------------------------
//
// All ~25 attributes for an appliance now land on ONE Hubitat device's event
// stream. progress_percent and completion_time both change roughly once per
// second while a cycle runs; six appliances mid-cycle is the load shape that
// produced LimitExceededException in the InvisOutlet Listener.
//
// Keyed on attribute. state is already per-device, so this is effectively
// per device + attribute.

// Attributes that must never be delayed: anything an automation triggers on.
// NOTE: `power` is deliberately absent. It is a binary on/off on the washer
// and dryer but a wattage meter on the refrigerator; the default throttle
// suits both, since an infrequent toggle is never delayed anyway.
@Field static final List<String> IMMEDIATE_ATTRS = [
    "switch", "contact", "door", "running", "machineState",
    "alarmCode", "childLock", "detergentLow", "softenerLow", "cavityState",
    "cooktopRunningState", "activeBurners", "filterStatus",
    "doorCoolerOpen", "doorFreezerOpen", "doorCvroomOpen",
    "burner1", "burner2", "burner3", "burner4", "burner5",
    "cavity2Running", "cavity2CavityState", "cavity2MachineState",
    "healthStatus"
]

// Everything noisy. Milliseconds.
@Field static final Map<String, Integer> THROTTLE_MS = [
    "progressPercent"    : 5000,
    "progress"           : 5000,
    "completionTime"     : 5000,
    "estimatedFinish"    : 5000,
    "operationTime"      : 5000,
    "cookTime"           : 5000,
    "energy"             : 5000,
    "energyThisMonth"    : 5000,
    "energyLastMonth"    : 5000,
    "energySaved"        : 5000,
    "powerEnergy"        : 5000,
    "waterConsumption"   : 5000,
    "temperature"        : 5000,
    "coolerTemperature"  : 5000,
    "freezerTemperature" : 5000,
    "setpoint"           : 5000,
    "coolerSetpoint"     : 5000,
    "freezerSetpoint"    : 5000,
    "power"              : 5000,
    "powerLevel"         : 5000,
    "filterUsage"        : 5000,
    "drumCleanDueIn"     : 5000,
    "lastUpdate"         : 300000,
    "cavity2ProgressPercent" : 5000,
    "cavity2Temperature"     : 5000,
    "cavity2Setpoint"        : 5000,
    "cavity2CookTime"        : 5000,
    "cavity2OperationTime"   : 5000
]

@Field static final Integer DEFAULT_THROTTLE_MS = 2000

// HA states that mean "no reading", not a real value.
@Field static final List<String> NULL_STATES = ["unknown", "unavailable", "none", "null", ""]

// ---------------------------------------------------------------------------
// Naming
// ---------------------------------------------------------------------------

/** Approximate Home Assistant's slugify: lowercase, non-alphanumerics collapse to _. */
String slugify(String name) {
    if (!name) return ""
    StringBuilder out = new StringBuilder()
    boolean prevUs = false
    name.toLowerCase().each { ch ->
        if (Character.isLetterOrDigit(ch as char)) {
            out.append(ch)
            prevUs = false
        } else if (!prevUs) {
            out.append("_")
            prevUs = true
        }
    }
    String s = out.toString()
    while (s.startsWith("_")) { s = s.substring(1) }
    while (s.endsWith("_"))   { s = s.substring(0, s.length() - 1) }
    return s
}

/**
 * Strip the domain and the slugified device-name prefix, leaving the stable
 * part that identifies what the entity actually is.
 *
 *   switch.samsung_refrigerator_tp2x_ref_20k_rapid_fridge  -> rapid_fridge
 *   binary_sensor.[...]_range_0101x_burner_3               -> burner_3
 *   number.[...]_subdevice_1_setpoint                      -> setpoint
 *
 * HA builds object_id by slugifying the device name and appending the entity
 * name, so with the device name in hand this is exact rather than guesswork.
 * Use the registry's `name`, NOT `name_by_user` -- after a user rename
 * name_by_user no longer matches the entity_id prefix.
 */
String suffixOf(String entityId, String deviceName) {
    if (!entityId) return ""
    String obj = entityId.contains(".") ? entityId.substring(entityId.indexOf(".") + 1) : entityId
    if (deviceName) {
        String prefix = slugify(deviceName)
        if (obj == prefix) return ""                 // entity named exactly for its device
        if (obj.startsWith(prefix + "_")) return obj.substring(prefix.length() + 1)
    }
    return obj
}

/** progress_percent -> progressPercent, burner_3 -> burner3 */
String snakeToCamel(String s) {
    if (!s) return s
    List<String> parts = s.tokenize("_")
    if (!parts) return s
    StringBuilder sb = new StringBuilder(parts[0])
    parts.drop(1).each { sb.append(it.capitalize()) }
    return sb.toString()
}

// ---------------------------------------------------------------------------
// Value coercion
// ---------------------------------------------------------------------------

Boolean isNullState(def v) {
    return (v == null) || NULL_STATES.contains(v.toString().toLowerCase())
}

/**
 * "unavailable" means Home Assistant cannot reach the entity at all --
 * LocalThings has lost the appliance. Distinct from "unknown", which means it
 * answered but has no reading right now (an oven setpoint while the oven is
 * off) and is perfectly normal.
 */
Boolean isUnavailable(def v) {
    return v?.toString()?.toLowerCase() == "unavailable"
}

/**
 * Emit an on/off attribute, or NOTHING when there is no usable reading.
 *
 * onOff() maps anything that is not explicitly on to "off", so an unavailable
 * door sensor would otherwise report "closed" and an unavailable washer would
 * report "running: off" -- confidently wrong, in the direction that makes a
 * rule act. Holding the last real value and letting healthStatus say the
 * appliance is unreachable is the honest behaviour.
 */
void emitOnOff(String attr, def raw) {
    if (isNullState(raw)) { holdPrevious(attr, raw); return }
    emit(attr, onOff(raw))
}

/** open/closed attribute, or nothing at all when there is no usable reading. */
void emitOpenClosed(String attr, def raw) {
    if (isNullState(raw)) { holdPrevious(attr, raw); return }
    emit(attr, openClosed(raw))
}

private void holdPrevious(String attr, def raw) {
    if (settings?.logEnable) {
        log.debug "${attr}: '${raw}' is not a reading -- holding the previous value"
    }
}

String onOff(def v) {
    return (v?.toString()?.toLowerCase() in ["on", "true", "open", "1"]) ? "on" : "off"
}

String openClosed(def v) {
    return (v?.toString()?.toLowerCase() in ["on", "true", "open", "1"]) ? "open" : "closed"
}

/** Return a Number when the state parses as one, otherwise the original String. */
def coerce(def v) {
    if (v == null) return null
    String s = v.toString()
    if (s.isInteger()) return s.toInteger()
    if (s.isDouble())  return s.toDouble()
    return s
}

// ---------------------------------------------------------------------------
// Event emission: de-dup, then throttle with a trailing flush
// ---------------------------------------------------------------------------

private Integer throttleMsFor(String attr) {
    if (IMMEDIATE_ATTRS.contains(attr)) return 0
    Integer ms = THROTTLE_MS[attr]
    return (ms != null) ? ms : DEFAULT_THROTTLE_MS
}

/** Exact-value dedup. A separate concern from time throttling. */
private Boolean isDuplicateUpdate(String attr, def value) {
    def cur = device.currentValue(attr)
    if (cur == null && value == null) return true
    if (cur == null) return false
    return cur.toString() == value?.toString()
}

/**
 * Time throttle, keyed on attribute. Returns true when the caller should hold
 * off. A held value is stashed and flushed on a timer so the LAST value always
 * lands -- a plain drop would leave progressPercent frozen at 88 when a cycle
 * stops updating.
 */
private Boolean isThrottled(String attr) {
    Integer ms = throttleMsFor(attr)
    if (ms <= 0) return false
    Long nowMs = now()
    Map last = (state.lastSentAt instanceof Map) ? state.lastSentAt : [:]
    Long prev = (last[attr] != null) ? (last[attr] as Long) : null
    if (prev != null && (nowMs - prev) < ms) return true
    last[attr] = nowMs
    state.lastSentAt = last
    return false
}

/**
 * Central event path. EVERY sendEvent in a child driver goes through here --
 * in the InvisOutlet Listener the paths that got missed were binary_sensor and
 * the generic numeric sensor branches.
 */
void emit(String attr, def value, Map opts = [:]) {
    if (attr == null) return
    if (isDuplicateUpdate(attr, value)) return

    if (isThrottled(attr)) {
        Map pending = (state.pending instanceof Map) ? state.pending : [:]
        pending[attr] = [value: value, opts: opts]
        state.pending = pending
        Integer secs = Math.max(1, (int) Math.ceil(throttleMsFor(attr) / 1000.0d))
        runIn(secs, "flushPending", [overwrite: true])
        return
    }
    doSend(attr, value, opts)
}

// Invoked by name from runIn() in emit(), so nothing calls it directly --
// leave it alone, a dead-code scan cannot see a scheduled call.
void flushPending() {
    Map pending = (state.pending instanceof Map) ? state.pending : [:]
    if (!pending) return
    state.pending = [:]
    Map last = (state.lastSentAt instanceof Map) ? state.lastSentAt : [:]
    Long nowMs = now()
    pending.each { String attr, def rec ->
        Map r = (Map) rec
        if (!isDuplicateUpdate(attr, r.value)) {
            last[attr] = nowMs
            doSend(attr, r.value, (Map) (r.opts ?: [:]))
        }
    }
    state.lastSentAt = last
}

private void doSend(String attr, def value, Map opts) {
    Map ev = [name: attr, value: value]
    if (opts?.unit) ev.unit = opts.unit
    ev.descriptionText = opts?.descriptionText ?:
        "${device.displayName} ${attr} is ${value}${opts?.unit ?: ''}"
    sendEvent(ev)
    if (settings?.txtEnable != false) log.info ev.descriptionText
}

// ---------------------------------------------------------------------------
// Availability
// ---------------------------------------------------------------------------

/**
 * Track which of this appliance's entities Home Assistant currently cannot
 * reach, and publish it so a rule can decline to act on stale readings.
 *
 *   online   every known entity is answering
 *   partial  some are unavailable
 *   offline  every known entity is unavailable
 *
 * Brief DTLS drops are normal on this hardware and LocalThings reconnects, so
 * expect this to flap occasionally rather than treating it as a fault. Only
 * "unavailable" counts -- "unknown" is a real answer meaning no reading.
 *
 * Call this once per entity, at the top of parseEntity, BEFORE any early
 * return, so an appliance going dark is still noticed.
 */
void noteEntityHealth(Map e) {
    String key = "${e.scope}:${e.suffix}"
    List known   = (state.knownEntities instanceof List) ? state.knownEntities : []
    List missing = (state.missingEntities instanceof List) ? state.missingEntities : []

    if (!known.contains(key)) {
        known << key
        state.knownEntities = known
    }

    boolean gone = isUnavailable(e.state)
    if (gone && !missing.contains(key)) {
        missing << key
        state.missingEntities = missing
    } else if (!gone && missing.contains(key)) {
        missing.remove(key)
        state.missingEntities = missing
    }

    if (!gone) {
        emit("lastUpdate", new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    }
    emit("healthStatus", !missing ? "online"
                                 : (missing.size() >= known.size() ? "offline" : "partial"))
}

// ---------------------------------------------------------------------------
// Entity bookkeeping
// ---------------------------------------------------------------------------

/**
 * Remember suffix -> entity_id so commands can find their target without a
 * hardcoded entity list. `scope` is "main" or a subdevice key such as "sub1".
 */
void rememberEntity(String scope, String suffix, String entityId) {
    if (suffix == null || !entityId) return
    String key = "entityMap_${scope}"
    Map m = (state[key] instanceof Map) ? state[key] : [:]
    if (m[suffix] != entityId) {
        m[suffix] = entityId
        state[key] = m
    }
}

String entityFor(String suffix, String scope = "main") {
    Map m = (state["entityMap_${scope}"] instanceof Map) ? state["entityMap_${scope}"] : [:]
    return m[suffix]
}

/**
 * HA tags diagnostic and config entities in entity_category. That is a good
 * first cut at which of the ~25 entities per appliance deserve to be real
 * Hubitat attributes versus HA-only reference data.
 */
Boolean isHiddenCategory(String category) {
    if (!category) return false
    return (settings?.exposeDiagnostics != true)
}

// ---------------------------------------------------------------------------
// Dynamic option dropdowns
// ---------------------------------------------------------------------------
//
// Hubitat cannot build a COMMAND dropdown from live data -- "preferences can
// be dynamic, but not commands" -- and which options an appliance offers is
// only knowable at run time. So the dropdowns are preferences, built from what
// this appliance actually reported, and the commands take a plain string so
// they still work from Rule Machine.

// ---------------------------------------------------------------------------
// Outbound: everything goes back to HA through the parent's single socket
// ---------------------------------------------------------------------------

void haTurnOn(String suffix, String scope = "main")  { haService("switch", "turn_on",  suffix, [:], scope) }
void haTurnOff(String suffix, String scope = "main") { haService("switch", "turn_off", suffix, [:], scope) }
void haPress(String suffix, String scope = "main")   { haService("button", "press",    suffix, [:], scope) }

void haSelect(String suffix, String option, String scope = "main") {
    haService("select", "select_option", suffix, [option: option], scope)
}

void haSetNumber(String suffix, def value, String scope = "main") {
    haService("number", "set_value", suffix, [value: value], scope)
}

/**
 * Set a select entity by suffix, validated against what Home Assistant reports.
 *
 * Drivers expose typed commands (setCycle, setSpinSpeed, ...) whose dropdowns
 * are ENUM constraints, and Hubitat can only build those from a STATIC list in
 * the code -- "preferences can be dynamic, but not commands". A different
 * appliance model may therefore offer options this driver was not written
 * against, because LocalThings reads every writable select's options from the
 * device itself rather than hardcoding them. This is the escape hatch for
 * those; the live list is in state.options_<scope>.
 */
/**
 * Emit a select entity under the name Home Assistant would show for it.
 *
 * The name is resolved by the LISTENER, which holds the translation table and
 * each entity's translation_key. Nothing model-specific lives in this driver:
 * upstream keys cycle names per board family and those tables conflict, so a
 * generated table would show a different appliance confidently wrong names.
 *
 * Falls back to the raw code when Home Assistant has no name for it, exactly
 * as Home Assistant itself does.
 */
void emitSelect(Map e, String prefix = "") {
    if (isHiddenCategory(e.entityCategory as String)) return
    String camel = snakeToCamel((String) e.suffix)
    String attr = prefix ? (prefix + camel.capitalize()) : camel
    emit(attr, (e.label ?: e.state)?.toString())
}

void setSelectOption(String suffix, String option, String scope = "main") {
    String entityId = entityFor(suffix, scope)
    if (!entityId) {
        log.warn "${device.displayName}: no '${suffix}' entity known (scope=${scope})"
        return
    }
    // Accepts either the friendly name or the raw code; the listener owns the
    // mapping because it is the thing that knows which name table applies.
    String code = parent?.selectCodeFor(entityId, option) ?: option
    List opts = optionsFor(suffix, scope)
    if (opts && !opts.contains(code)) {
        log.warn "${device.displayName}: '${option}' is not offered for ${suffix}. " +
                 "Available: ${(optionLabelsFor(suffix, scope) ?: opts).join(', ')} " +
                 "(also listed as '${optionVarName(scope, suffix)}' in State Variables)"
        return
    }
    haSelect(suffix, code, scope)
}

/**
 * Set a switch entity from an Off/On choice, so a config switch gets the same
 * two-option dropdown Home Assistant shows rather than a pair of commands.
 */
void setSwitchOption(String suffix, String value, String scope = "main") {
    if (onOff(value) == "on") haTurnOn(suffix, scope)
    else                      haTurnOff(suffix, scope)
}

/** Set a `time` entity, e.g. a delayed-start clock. Value is "HH:MM:SS". */
void haSetTime(String suffix, String value, String scope = "main") {
    haService("time", "set_value", suffix, [time: value], scope)
}

void haService(String domain, String service, String suffix, Map data = [:], String scope = "main") {
    String entityId = entityFor(suffix, scope)
    if (!entityId) {
        log.warn "${device.displayName}: no '${suffix}' entity known (scope=${scope}); cannot call ${domain}.${service}"
        return
    }
    if (settings?.logEnable) log.debug "${device.displayName}: ${domain}.${service} -> ${entityId} ${data}"
    parent?.callService(domain, service, entityId, data)
}

/**
 * Force HA to do a live read rather than serving cache. LocalThings honours
 * homeassistant.update_entity by hitting the appliance over CoAP.
 */
void haRefreshAll() {
    List<String> ids = []
    state.each { k, v ->
        if (k.toString().startsWith("entityMap_") && v instanceof Map) {
            ids.addAll(((Map) v).values().collect { it.toString() })
        }
    }
    ids = ids.unique()
    if (!ids) {
        log.warn "${device.displayName}: no entities known yet -- has the listener seeded state?"
        return
    }
    parent?.callService("homeassistant", "update_entity", ids, [:])
}

// ---------------------------------------------------------------------------
// Generic entity handling, shared by every child driver
// ---------------------------------------------------------------------------

/**
 * Default mapping for an entity a driver has no specific opinion about.
 * Attribute name is the camelCased suffix, so the mapping tracks whatever
 * LocalThings adds upstream instead of breaking on a hardcoded entity list.
 *
 * Returns the attribute name it emitted, or null if it emitted nothing.
 */
String emitGeneric(Map e, String prefix = "") {
    String suffix = e.suffix
    String domain = e.domain
    def raw = e.state

    if (isHiddenCategory(e.entityCategory as String)) return null

    String attr = prefix ? (prefix + snakeToCamel(suffix).capitalize()) : snakeToCamel(suffix)
    if (!attr) attr = prefix ?: domain

    switch (domain) {
        case "binary_sensor":
        case "switch":
        case "fan":
            emitOnOff(attr, raw)
            return attr
        case "button":
            return null                       // buttons carry no meaningful state
        case "number":
        case "sensor":
            // Send NOTHING rather than the string "unknown". These attributes
            // are declared as numbers, and a non-numeric value breaks numeric
            // comparisons in Rule Machine and dashboard tiles. Leaving the
            // attribute absent (or at its last real reading) is the lesser
            // evil -- the range setpoint sits at "unknown" whenever the oven
            // is off.
            if (isNullState(raw)) {
                if (settings?.logEnable) log.debug "${attr}: no reading (${raw}) -- not emitting"
                return null
            }
            emit(attr, coerce(raw), e.unit ? [unit: e.unit] : [:])
            return attr
        case "select":
            emit(attr, raw?.toString())
            return attr
        default:
            emit(attr, raw?.toString())
            return attr
    }
}

/**
 * Record what a select offers.
 *
 * `options_<scope>` keeps the raw codes, which is what a write has to be
 * validated against. Alongside it, each select gets its OWN readable state
 * variable naming everything this appliance will accept -- "Cycle", "Spin
 * Speed", "Flex Zone Mode" and so on.
 *
 * That variable is the whole answer to "what can I type here". A Hubitat
 * driver cannot build a dropdown from live data by any route, and a static
 * dropdown would have to list every option every board might have, most of
 * which this unit does not. So the commands take free text and the device page
 * tells you exactly what that text can be -- per appliance, in the same names
 * Home Assistant shows, and correct for whatever board it is.
 */
void rememberOptions(String scope, String suffix, def options, def labels = null) {
    if (!(options instanceof List)) return
    String key = "options_${scope}"
    Map m = (state[key] instanceof Map) ? state[key] : [:]
    m[suffix] = options
    state[key] = m

    List names = (labels instanceof List && ((List) labels).size() == ((List) options).size())
                     ? (List) labels : (List) options
    state[optionVarName(scope, suffix)] = names.join(", ")
    state.remove("optionLabels_${scope}")       // superseded by the per-select vars
}

/** "spin_speed" -> "Spin Speed"; a subdevice gets its scope in brackets. */
String optionVarName(String scope, String suffix) {
    String pretty = suffix.tokenize("_").collect { it.capitalize() }.join(" ")
    return (scope == "main") ? pretty : "${pretty} (${scope})"
}

List optionLabelsFor(String suffix, String scope = "main") {
    def v = state[optionVarName(scope, suffix)]
    return v ? v.toString().split(",\\s*").toList() : []
}

List optionsFor(String suffix, String scope = "main") {
    Map m = (state["options_${scope}"] instanceof Map) ? state["options_${scope}"] : [:]
    return (m[suffix] instanceof List) ? (List) m[suffix] : []
}
