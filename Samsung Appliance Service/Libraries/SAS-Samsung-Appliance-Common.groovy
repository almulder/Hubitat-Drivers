/**
 *  SAS - Samsung Appliance Common Library
 *
 *  Shared helpers for the Samsung Appliance Service child drivers: entity
 *  suffix mapping, per-attribute throttling with trailing flush, exact-value
 *  de-duplication, and Home Assistant service-call plumbing.
 *
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  Install this library BEFORE the child drivers -- they will not compile
 *  without it.
 *
 *  0.7.0
 *    - Trailing flush no longer delayed: one shared timer is only ever moved
 *      EARLIER, so lastUpdate's 5 minute hold cannot push progressPercent back.
 *    - A held value is dropped when a newer value is sent or matches the
 *      current one, so a stale held value can no longer land after a newer one.
 *    - power on/off is immediate; power as a wattage reading keeps the throttle.
 *    - Throttle entries for the kWh, watts and progressPercentage names.
 *    - Temperatures are converted to the hub scale: emitTemperature,
 *      emitTemperatureValue, toHubTemperature, toEntityTemperature and
 *      haSetTemperature. emitGeneric converts temperature sensors itself.
 *    - Shared logsOff, scheduleLogsOff, commonInstalled and commonUpdated.
 *    - capFirst replaces the per-driver cap helper.
 *    - Throttle bookkeeping is kept in memory, not written to state per event.
 *    - attrRenames is cached; coerce rejects NaN and Infinity; de-dup compares
 *      numbers by value, so 21.5 and 21.50 are the same reading.
 *    - pruneEntities lets the listener drop entities Home Assistant no longer has.
 *    - applyDiagnosticsSetting removes diagnostic attributes when hidden.
 *    - Driver helpers: scopePrefix, emitEnergy in kWh, emitBattery,
 *      emitCarbonDioxide, suffixInDomain, and the Oven and Range cooking
 *      helpers ovenStartCook, ovenSetCookMinutes, ovenCavityReady,
 *      ovenNoteCavity and ovenCavityScope.
 *    - beginEntity, the shared start of parseEntity; fanTarget; and
 *      commonUninstalled, which drops a removed device's memory.
 *    - A temperature entity's unit is remembered even before its first
 *      reading, and a setpoint is refused rather than sent unconverted while
 *      the unit is still unknown. ovenStartCook checks the time and setpoint
 *      before sending anything, and never presses Start after a refusal.
 *    - lastUpdate is sampled, not held, and select options are written to
 *      state only when they change: two state writes fewer per event.
 *
 *  HubiThings Replica names: see that section below. NO round brackets in
 *  this header -- Hubitat finds the settings block by the first bracket
 *  after the word library, and a bracket here makes every save fail with
 *  a bare Internal error.
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
import java.util.concurrent.ConcurrentHashMap

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
// per device + attribute. The policy lists below use the name BEFORE any
// Replica rename (the camelCased HA suffix), so one list serves every driver.

// Attributes that must never be delayed: anything an automation triggers on.
// NOTE: `power` is not listed here because it means two things: an on/off
// switch on the washer and dryer, renamed to `switch`, and a wattage meter on
// the refrigerator. See VALUE_TYPED_ATTRS -- the toggle is immediate, the
// reading keeps its THROTTLE_MS entry.
@Field static final List<String> IMMEDIATE_ATTRS = [
    "switch", "contact", "door", "running", "machineState",
    "alarmCode", "childLock", "detergentLow", "softenerLow", "cavityState",
    "cooktopRunningState", "activeBurners", "filterStatus",
    "doorCoolerOpen", "doorFreezerOpen", "doorCvroomOpen",
    "burner1", "burner2", "burner3", "burner4", "burner5",
    "lowerOvenRunning", "lowerOvenCavityState", "lowerOvenMachineState",
    "ovenCavityStatus", "doorState",
    "brightnessLevel", "lamp", "vent", "hoodFanSpeed",
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
    "ovenTemperature"    : 5000,
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
    "lowerOvenProgressPercent"  : 5000,
    "lowerOvenEstimatedFinish"  : 5000,
    "lowerOvenTemperature"      : 5000,
    "lowerOvenSetpoint"         : 5000,
    "lowerOvenCookTime"         : 5000,
    "lowerOvenOperationTime"    : 5000,
    // Names the generated drivers use for the same kinds of reading.
    "energyKwh"           : 5000,
    "energyThisMonthKwh"  : 5000,
    "energyLastMonthKwh"  : 5000,
    "energySavedKwh"      : 5000,
    "powerEnergyKwh"      : 5000,
    "powerWatts"          : 5000,
    "progressPercentage"  : 5000,
    "completionMinutes"   : 5000
]

// Attributes whose throttle depends on the value: a non-numeric value (an
// on/off toggle) is sent immediately, a number takes the THROTTLE_MS entry.
@Field static final List<String> VALUE_TYPED_ATTRS = ["power"]

@Field static final Integer DEFAULT_THROTTLE_MS = 2000

// Attributes that are sampled rather than held: inside the window the value is
// simply skipped, with no trailing flush. lastUpdate is only a heartbeat, and
// holding it wrote state.pending on nearly every event.
@Field static final List<String> SAMPLED_ATTRS = ["lastUpdate"]

// When each attribute was last sent, per device. In memory rather than state:
// writing state on every event was most of the cost of an event, and losing
// this on a reboot only means one value goes out unthrottled.
@Field static final ConcurrentHashMap<String, Map> LAST_SENT = new ConcurrentHashMap<String, Map>()

// attrRenames() is a constant table per driver; building it for every event
// was wasted work. Static fields belong to the driver class that includes
// this library, so each driver type has its own cache.
@Field static Map RENAMES_CACHE = null

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

/**
 * "subdevice1" -> "Subdevice1". Shared replacement for the private cap()
 * each generated driver used to carry; named differently so a driver that
 * still has its own cap() keeps compiling.
 */
String capFirst(String s) {
    return s ? s[0].toUpperCase() + s.substring(1) : s
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

/**
 * Return a Number when the state parses as one, otherwise the original String.
 * "NaN" and "Infinity" parse as doubles but are not readings; they come back
 * as null, which emit() ignores, rather than landing in a numeric attribute.
 */
def coerce(def v) {
    if (v == null) return null
    String s = v.toString()
    if (s.isInteger()) return s.toInteger()
    if (s.isDouble()) {
        Double d = s.toDouble()
        return (d.isNaN() || d.isInfinite()) ? null : d
    }
    return s
}

private Boolean isNumeric(def v) {
    if (v == null) return false
    if (v instanceof Number) return true
    return v.toString().isNumber()
}

// ---------------------------------------------------------------------------
// Temperatures
// ---------------------------------------------------------------------------
//
// Home Assistant reports each temperature in the entity's own unit, which
// need not be the hub's: a fridge in Celsius on a Fahrenheit hub. Labelling
// "3" as °F made a "temperature > 45" rule never fire. These convert on the
// way in, and setpoint commands convert back on the way out using the unit
// the entity last reported.

/** "°C", "C", "celsius" -> "C"; "°F", "fahrenheit" -> "F"; anything else -> null. */
String tempScaleOf(def unit) {
    if (unit == null) return null
    String u = unit.toString().toUpperCase().replace("°", "").trim()
    if (u == "C" || u == "CELSIUS")    return "C"
    if (u == "F" || u == "FAHRENHEIT") return "F"
    return null
}

String hubTempScale() {
    return (location?.temperatureScale ?: "F").toString().toUpperCase()
}

private def roundTemp(double v, int places) {
    BigDecimal r = BigDecimal.valueOf(v).setScale(places, BigDecimal.ROUND_HALF_UP)
    return (places == 0) ? r.intValue() : r
}

/**
 * A reading in `fromUnit` as the hub's scale, to one decimal place. A value
 * with no recognisable unit is assumed to already be in the hub's scale and
 * passes through unchanged. Returns null when the value is not a number.
 */
def toHubTemperature(def value, def fromUnit) {
    def n = (value instanceof Number) ? value : coerce(value)
    if (!(n instanceof Number)) return null
    String from = tempScaleOf(fromUnit)
    String to   = hubTempScale()
    if (!from || from == to) return n
    double v = ((Number) n).doubleValue()
    double out = (to == "F") ? (v * 9.0d / 5.0d + 32.0d) : ((v - 32.0d) * 5.0d / 9.0d)
    return roundTemp(out, 1)
}

/** Remember the unit an entity reports in, so a command can convert back. */
void rememberUnit(String scope, String suffix, def unit) {
    if (suffix == null || unit == null) return
    String key = "units_${scope ?: 'main'}"
    Map m = (state[key] instanceof Map) ? (Map) state[key] : [:]
    if (m[suffix] != unit.toString()) {
        m[suffix] = unit.toString()
        state[key] = m
    }
}

String unitFor(String suffix, String scope = "main") {
    Map m = (state["units_${scope ?: 'main'}"] instanceof Map) ? (Map) state["units_${scope ?: 'main'}"] : [:]
    return m[suffix]
}

/**
 * A hub-scale value, typically a setpoint from a command, in the unit the
 * entity reports in. Converted values are rounded to whole degrees, which is
 * what these appliances accept; an unconverted value passes through as given.
 * If the entity has not reported a unit yet, the value is sent unchanged.
 */
def toEntityTemperature(def hubValue, String suffix, String scope = "main") {
    def n = (hubValue instanceof Number) ? hubValue : coerce(hubValue)
    if (!(n instanceof Number)) return hubValue
    String to   = tempScaleOf(unitFor(suffix, scope))
    String from = hubTempScale()
    if (!to || to == from) return n
    double v = ((Number) n).doubleValue()
    double out = (to == "F") ? (v * 9.0d / 5.0d + 32.0d) : ((v - 32.0d) * 5.0d / 9.0d)
    return roundTemp(out, 0)
}

/**
 * number.set_value for a temperature entity, converting from the hub's scale.
 * Refused, sending nothing, when the entity is unknown, the value is not a
 * number, or the entity has not reported its unit yet: guessing the unit
 * could set an oven to 350 C. Returns whether it was sent.
 */
boolean haSetTemperature(String suffix, def hubValue, String scope = "main") {
    if (!entityFor(suffix, scope)) {
        log.warn "${device.displayName}: no '${suffix}' entity known (scope=${scope}); setpoint not sent"
        return false
    }
    def n = (hubValue instanceof Number) ? hubValue : coerce(hubValue)
    if (!(n instanceof Number)) {
        log.warn "${device.displayName}: '${hubValue}' is not a temperature; ${suffix} not set"
        return false
    }
    if (!tempScaleOf(unitFor(suffix, scope))) {
        log.warn "${device.displayName}: ${suffix} has not reported its unit yet, so ${hubValue} " +
                 "can't be converted safely -- not sent. Try again after a refresh."
        return false
    }
    haSetNumber(suffix, toEntityTemperature(n, suffix, scope), scope)
    return true
}

/**
 * Emit a temperature entity's state under `attr`, converted to the hub's scale
 * and labelled with it. Nothing is sent when there is no reading.
 */
void emitTemperature(String attr, Map e, Map opts = [:]) {
    if (attr == null || e == null) return
    // Before the null check: an idle oven's setpoint reads "unknown" but still
    // reports its unit, and a setpoint command needs that unit to convert.
    rememberUnit((String) (e.scope ?: "main"), (String) e.suffix, e.unit)
    if (isNullState(e.state)) { holdPrevious(attr, e.state); return }
    emitTemperatureValue(attr, e.state, e.unit, opts)
}

/** As emitTemperature, for a value a driver has already picked out itself. */
void emitTemperatureValue(String attr, def value, def fromUnit, Map opts = [:]) {
    if (attr == null || isNullState(value)) return
    def v = toHubTemperature(value, fromUnit)
    if (v == null) return
    Map o = [:]
    if (opts) o.putAll(opts)
    o.unit = "°${hubTempScale()}"
    emit(attr, v, o)
}

// ---------------------------------------------------------------------------
// Event emission: de-dup, then throttle with a trailing flush
// ---------------------------------------------------------------------------

private Integer throttleMsFor(String attr, def value = null) {
    if (IMMEDIATE_ATTRS.contains(attr)) return 0
    if (VALUE_TYPED_ATTRS.contains(attr) && value != null && !isNumeric(value)) return 0
    Integer ms = THROTTLE_MS[attr]
    return (ms != null) ? ms : DEFAULT_THROTTLE_MS
}

private Map lastSentMap() {
    String k = device.id.toString()
    Map m = LAST_SENT.get(k)
    if (m == null) {
        m = new ConcurrentHashMap()
        Map prior = LAST_SENT.putIfAbsent(k, m)
        if (prior != null) m = prior
    }
    return m
}

// ---------------------------------------------------------------------------
// HubiThings Replica names
// ---------------------------------------------------------------------------
//
// So Rule Machine rules written against the HubiThings Replica drivers keep
// working, each driver's attrRenames() maps the name this library would emit
// (the camelCased HA suffix, e.g. "childLock") to the Replica name
// ("lockState"). An entry is either the new name as a String, or a Map:
//
//   [name: "lockState", values: RV_LOCK]      translate values too
//   [name: "timeRemaining", format: "hms"]    minutes -> "HH:mm:ss"
//   [name: "completionTime", format: "isoZ"]  "...+00:00" -> "...Z"
//
// Values only change where Replica's values are known to differ, and a value
// missing from a table passes through untouched.

// machineState on washer/dryer (SmartThings washer/dryerOperatingState).
@Field static final Map<String, String> RV_MACHINE_STATE = ["idle": "stop", "active": "run", "pause": "pause"]
// operatingState on ovens and the dishwasher (samsungce.*OperatingState).
@Field static final Map<String, String> RV_OPERATING_STATE = ["idle": "ready", "active": "running", "pause": "paused"]
@Field static final Map<String, String> RV_LOCK   = ["on": "locked", "off": "unlocked"]
@Field static final Map<String, String> RV_BOOL   = ["on": "true", "off": "false"]
@Field static final Map<String, String> RV_PROBE  = ["on": "connected", "off": "disconnected"]
// LocalThings lowercases job states; SmartThings spells a few differently.
@Field static final Map<String, String> RV_WASHER_JOB = ["idle": "none", "delaywash": "delayWash",
    "weightsensing": "weightSensing", "airwashing": "airWash"]
@Field static final Map<String, String> RV_DRYER_JOB = ["idle": "none", "delaywash": "delayWash",
    "weightsensing": "weightSensing", "finish": "finished"]

private String pad2(int n) { return (n < 10 ? "0" : "") + n }

private Map renamesTable() {
    Map c = RENAMES_CACHE
    if (c != null) return c
    try {
        c = attrRenames() ?: [:]
    } catch (Exception ignored) {
        // A driver from before 0.6.0 has no attrRenames(): keep its own names
        // rather than failing every event until it is updated.
        c = [:]
    }
    RENAMES_CACHE = c
    return c
}

private Map replicaRule(String attr) {
    def r = renamesTable().get(attr)
    if (r == null) return null
    return (r instanceof Map) ? (Map) r : [name: r.toString()]
}

private Object replicaValue(Map rule, Object value) {
    if (value == null) return null
    if (rule.values instanceof Map) {
        def v = ((Map) rule.values)[value.toString()]
        if (v != null) return v
    }
    switch (rule.format) {
        case "hms":
            if (!(value instanceof Number) && !value.toString().isNumber()) return value
            int mins = new BigDecimal(value.toString()).intValue()
            return pad2(Math.floorDiv(mins, 60)) + ":" + pad2(mins % 60) + ":00"
        case "isoZ":
            String s = value.toString()
            return s.endsWith("+00:00") ? s.substring(0, s.length() - 6) + "Z" : s
    }
    return value
}

/**
 * Exact-value dedup. A separate concern from time throttling. Numbers compare
 * by value, so a stored 21.5 and an incoming "21.50" are the same reading.
 */
private Boolean isDuplicateUpdate(String attr, def value) {
    def cur = device.currentValue(attr)
    if (cur == null && value == null) return true
    if (cur == null || value == null) return false
    String a = cur.toString()
    String b = value.toString()
    if (a == b) return true
    if (a.isNumber() && b.isNumber()) return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0
    return false
}

/**
 * Central event path. EVERY sendEvent in a child driver goes through here --
 * in the InvisOutlet Listener the paths that got missed were binary_sensor and
 * the generic numeric sensor branches.
 *
 * Throttling is per attribute with a trailing flush: a value inside its
 * attribute's window is held and sent when the window ends, so the LAST value
 * always lands -- a plain drop would leave progressPercent frozen at 88 when
 * a cycle stops updating. A held value is discarded as soon as a newer value
 * is sent or the current value already matches, so it can never land late.
 */
void emit(String attr, def value, Map opts = [:]) {
    if (attr == null) return
    // Throttle policy follows the pre-rename name; the event carries the
    // Replica name and value.
    String policy = attr
    Map rule = replicaRule(attr)
    if (rule) {
        attr = (String) rule.name
        value = replicaValue(rule, value)
        if (rule.format == "hms") opts = (opts ?: [:]).findAll { k, v -> k != "unit" }
    }
    if (value == null) return
    if (isDuplicateUpdate(attr, value)) { dropPending(attr); return }

    Integer ms = throttleMsFor(policy, value)
    if (ms > 0) {
        Map last = lastSentMap()
        Long nowMs = now()
        Long prev = (last[attr] != null) ? (last[attr] as Long) : null
        if (prev != null && (nowMs - prev) < ms) {
            if (!SAMPLED_ATTRS.contains(policy)) holdForFlush(attr, value, opts, prev + ms)
            return
        }
        last[attr] = nowMs
    }
    dropPending(attr)
    doSend(attr, value, opts)
}

private void holdForFlush(String attr, def value, Map opts, Long dueMs) {
    Map pending = (state.pending instanceof Map) ? (Map) state.pending : [:]
    pending[attr] = [value: value, opts: (opts ?: [:]), due: dueMs]
    state.pending = pending
    scheduleFlush(dueMs)
}

/**
 * One timer serves every held attribute, and it is only ever moved EARLIER.
 * Overwriting it on every hold let lastUpdate's 5 minute window push a held
 * progressPercent out by 5 minutes; flushPending() re-arms itself for
 * whatever is still waiting, so a later due time never needs the timer.
 */
private void scheduleFlush(Long dueMs) {
    Long nowMs = now()
    Long scheduled = (state.flushDueAt != null) ? (state.flushDueAt as Long) : null
    // A timer more than a few seconds overdue was lost (a reboot): replace it.
    if (scheduled != null && scheduled > nowMs - 5000L && scheduled <= dueMs) return
    state.flushDueAt = dueMs
    runInMillis(Math.max(250L, dueMs - nowMs), "flushPending", [overwrite: true])
}

private void dropPending(String attr) {
    def p = state.pending
    if (p instanceof Map && ((Map) p).containsKey(attr)) {
        Map m = (Map) p
        m.remove(attr)
        state.pending = m
    }
}

// Invoked by name from runInMillis() in scheduleFlush(), so nothing calls it
// directly -- leave it alone, a dead-code scan cannot see a scheduled call.
void flushPending() {
    state.remove("flushDueAt")
    if (state.lastSentAt != null) state.remove("lastSentAt")   // pre-0.7.0 bookkeeping
    Map pending = (state.pending instanceof Map) ? (Map) state.pending : [:]
    if (!pending) return
    Long nowMs = now()
    Map last = lastSentMap()
    Map keep = [:]
    pending.each { String attr, def rec ->
        Map r = (Map) rec
        // Entries held before 0.7.0 carry no due time: send them now.
        Long due = (r.due != null) ? (r.due as Long) : 0L
        if (due > nowMs + 250L) { keep[attr] = r; return }
        if (!isDuplicateUpdate(attr, r.value)) {
            last[attr] = nowMs
            doSend(attr, r.value, (Map) (r.opts ?: [:]))
        }
    }
    state.pending = keep
    if (keep) {
        Long next = (Long) keep.values().collect { Map r -> (r.due ?: nowMs) as Long }.min()
        scheduleFlush(next)
    }
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
    emit("healthStatus", healthOf(known, missing))
}

private String healthOf(List known, List missing) {
    return !missing ? "online" : (missing.size() >= known.size() ? "offline" : "partial")
}

/**
 * Forget entities Home Assistant no longer has. The listener calls this after
 * a full seed with every "scope:suffix" key it delivered to this device;
 * without it an entity removed while unavailable kept healthStatus at
 * "partial" for good.
 */
void pruneEntities(List keys) {
    if (!(keys instanceof List) || !keys) return
    Set live = keys.collect { it.toString() } as Set
    List known   = (state.knownEntities instanceof List) ? state.knownEntities : []
    List missing = (state.missingEntities instanceof List) ? state.missingEntities : []
    List keptKnown   = known.findAll { live.contains(it.toString()) }
    List keptMissing = missing.findAll { live.contains(it.toString()) }
    if (keptKnown.size() != known.size()) {
        if (settings?.logEnable) log.debug "${device.displayName}: forgetting ${known.size() - keptKnown.size()} entities Home Assistant no longer has"
        state.knownEntities = keptKnown
    }
    if (keptMissing.size() != missing.size()) state.missingEntities = keptMissing
    emit("healthStatus", healthOf(keptKnown, keptMissing))
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
    // state wins while it is set: applyDiagnosticsSetting() writes it because
    // a setting changed in this execution is not readable until the next one.
    def expose = (state.exposeDiagnostics != null) ? state.exposeDiagnostics : settings?.exposeDiagnostics
    return (expose != true)
}

/** Note an attribute that came from a diagnostic entity, so hiding can remove it. */
private void noteDiagnosticAttr(String attr) {
    if (!attr) return
    String name = (String) (replicaRule(attr)?.name ?: attr)
    List l = (state.diagnosticAttrs instanceof List) ? state.diagnosticAttrs : []
    if (!l.contains(name)) {
        l << name
        state.diagnosticAttrs = l
    }
}

/**
 * Show or hide diagnostic entities. Called by the listener when the app's
 * toggle changes. Hiding deletes the attributes they had already created,
 * which otherwise sat on the device page with their last values for good.
 * Showing takes effect on the listener's next seed, which follows at once.
 */
void applyDiagnosticsSetting(Boolean expose) {
    boolean want = (expose == true)
    device.updateSetting("exposeDiagnostics", [value: want ? "true" : "false", type: "bool"])
    state.exposeDiagnostics = want
    if (!want) removeDiagnosticAttrs()
}

private void removeDiagnosticAttrs() {
    List l = (state.diagnosticAttrs instanceof List) ? state.diagnosticAttrs : []
    if (!l) return
    l.each { a ->
        dropPending(a.toString())
        try { device.deleteCurrentState(a.toString()) } catch (ignored) { }
    }
    state.remove("diagnosticAttrs")
    log.info "${device.displayName}: removed ${l.size()} diagnostic attribute(s)"
}

// ---------------------------------------------------------------------------
// Lifecycle and logging, shared by every child driver
// ---------------------------------------------------------------------------

/** Turn debug logging off. Scheduled by scheduleLogsOff(); called by name. */
void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/** Debug logging switches itself off after 30 minutes. */
void scheduleLogsOff() {
    if (settings?.logEnable) runIn(1800, "logsOff")
}

/** Call from the driver's installed(). */
void commonInstalled() {
    scheduleLogsOff()
}

/** Call from the driver's updated(), i.e. when the user saves Preferences. */
void commonUpdated() {
    scheduleLogsOff()
    // A preference saved by hand wins over the app's last push.
    state.remove("exposeDiagnostics")
    if (settings?.exposeDiagnostics != true) removeDiagnosticAttrs()
    if (state.lastSentAt != null) state.remove("lastSentAt")   // pre-0.7.0 bookkeeping
    RENAMES_CACHE = null
}

/** Call from the driver's uninstalled(): drop this device's in-memory bookkeeping. */
void commonUninstalled() {
    LAST_SENT.remove(device.id.toString())
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
    if (e.entityCategory) noteDiagnosticAttr(attr)
    emit(attr, (e.label ?: e.state)?.toString())
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
 *
 * Returns true when the request was sent, false when it was refused (no such
 * entity, or an option the appliance does not offer), so a caller such as
 * startCook can stop rather than start the appliance on the wrong setting.
 */
boolean setSelectOption(String suffix, String option, String scope = "main") {
    String entityId = entityFor(suffix, scope)
    if (!entityId) {
        log.warn "${device.displayName}: no '${suffix}' entity known (scope=${scope})"
        return false
    }
    // Accepts either the friendly name or the raw code; the listener owns the
    // mapping because it is the thing that knows which name table applies.
    String code = parent?.selectCodeFor(entityId, option) ?: option
    List opts = optionsFor(suffix, scope)
    // The listener's name tables are keyed lowercase with no spaces, so match
    // loosely and send Home Assistant the option exactly as it spells it.
    def offered = opts?.find { sameCode(it, code) }
    if (offered != null) code = offered.toString()
    if (opts && offered == null) {
        log.warn "${device.displayName}: '${option}' is not offered for ${suffix}. " +
                 "Available: ${(optionLabelsFor(suffix, scope) ?: opts).join(', ')} " +
                 "(also listed as '${optionVarName(scope, suffix)}' in State Variables)"
        return false
    }
    haSelect(suffix, code, scope)
    return true
}

/** "5B", "5b" and "5 B" are the same select code. */
boolean sameCode(def a, def b) {
    if (a == null || b == null) return false
    return a.toString().replaceAll(/\s+/, "").equalsIgnoreCase(b.toString().replaceAll(/\s+/, ""))
}

/**
 * Set a switch entity from an Off/On choice, so a config switch gets the same
 * two-option dropdown Home Assistant shows rather than a pair of commands.
 */
void setSwitchOption(String suffix, String value, String scope = "main") {
    if (onOff(value) == "on") haTurnOn(suffix, scope)
    else                      haTurnOff(suffix, scope)
}

/**
 * A Replica-style time argument -- "hh:mm:ss", "hh:mm", or a plain number of
 * SECONDS -- as the whole minutes a LocalThings cook_time takes, rounded up.
 */
Integer replicaTimeToMinutes(def t) {
    String s = t?.toString()?.trim()
    if (!s) return null
    if (s.contains(":")) {
        List<Integer> p = s.tokenize(":").collect { it.isInteger() ? it.toInteger() : 0 }
        if (p.size() == 2) p << 0                  // hh:mm
        if (p.size() != 3) { log.warn "${device.displayName}: can't read time '${t}'"; return null }
        return (int) Math.ceil((p[0] * 3600 + p[1] * 60 + p[2]) / 60.0d)
    }
    if (s.isNumber()) return (int) Math.ceil(new BigDecimal(s).doubleValue() / 60.0d)
    log.warn "${device.displayName}: can't read time '${t}' -- use hh:mm:ss or seconds"
    return null
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
    if (e.entityCategory) noteDiagnosticAttr(attr)

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
            // A temperature entity's unit is remembered even with no reading,
            // so a setpoint command can convert before the first one arrives.
            if (tempScaleOf(e.unit)) rememberUnit((String) (e.scope ?: "main"), (String) suffix, e.unit)
            if (isNullState(raw)) {
                if (settings?.logEnable) log.debug "${attr}: no reading (${raw}) -- not emitting"
                return null
            }
            // A temperature in the entity's unit becomes one in the hub's.
            if (tempScaleOf(e.unit)) {
                emitTemperature(attr, e)
                return attr
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
    // Every select event carries its options; write state only when they change.
    if (m[suffix] != options) {
        m[suffix] = options
        state[key] = m
    }

    List names = (labels instanceof List && ((List) labels).size() == ((List) options).size())
                     ? (List) labels : (List) options
    String varName = optionVarName(scope, suffix)
    String joined = names.join(", ")
    if (state[varName] != joined) state[varName] = joined
    if (state["optionLabels_${scope}"] != null) state.remove("optionLabels_${scope}")   // superseded by the per-select vars
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

// ---------------------------------------------------------------------------
// Driver helpers: scope prefix, energy, battery, CO2, domain lookup
// ---------------------------------------------------------------------------

/**
 * The attribute prefix for an entity's scope: "" on the main device, else the
 * scope itself ("subdevice1"), so a subdevice reading never overwrites the
 * main attribute of the same name. Both emitGeneric and emitSelect take it.
 */
String scopePrefix(Map e) {
    String s = e?.scope as String
    return (!s || s == "main") ? "" : s
}

/**
 * An energy reading in kWh, the unit Hubitat's EnergyMeter uses. Home
 * Assistant's Wh or MWh is converted; a reading with no unit is taken as Wh,
 * which is what LocalThings reports. Any other unit passes through as given.
 */
void emitEnergy(String attr, Map e) {
    if (attr == null || e == null || isNullState(e.state)) return
    def n = coerce(e.state)
    if (!(n instanceof Number)) return
    String u = (e.unit ?: "Wh").toString().trim()
    double v = ((Number) n).doubleValue()
    if (u.equalsIgnoreCase("Wh"))       v = v / 1000.0d
    else if (u.equalsIgnoreCase("MWh")) v = v * 1000.0d
    else if (!u.equalsIgnoreCase("kWh")) { emit(attr, n, [unit: u]); return }
    emit(attr, BigDecimal.valueOf(v).setScale(3, BigDecimal.ROUND_HALF_UP), [unit: "kWh"])
}

/**
 * A battery percentage for capability Battery. Sent whatever the entity's
 * category: upstream tags some batteries diagnostic, but a capability
 * attribute that stays empty is no use to a rule. A non-numeric state is
 * not sent, since battery is a number.
 */
void emitBattery(Map e, String attr = "battery") {
    if (e == null || isNullState(e.state)) return
    def n = coerce(e.state)
    if (n instanceof Number) emit(attr, n, [unit: "%"])
}

/**
 * A CO2 entity: the existing co2 attribute as before, plus carbonDioxide for
 * capability CarbonDioxideMeasurement, which is sent whatever the category
 * for the same reason as the battery.
 */
void emitCarbonDioxide(Map e, String prefix = "") {
    emitGeneric(e, prefix)
    if (prefix || isNullState(e?.state)) return
    def n = coerce(e.state)
    if (n instanceof Number) emit("carbonDioxide", n, [unit: e.unit ?: "ppm"])
}

/**
 * The suffix to command for an entity of `domain` ("fan", "climate"):
 * `preferred` when that entity is in the domain, else the entity named for the
 * device itself (suffix ""), else the first known entity in the domain, else
 * null. A fan or climate entity named exactly for its device has an empty
 * suffix, which a hardcoded suffix would miss.
 */
String suffixInDomain(String domain, String preferred = null, String scope = "main") {
    String prefix = domain + "."
    if (preferred != null && entityFor(preferred, scope)?.startsWith(prefix)) return preferred
    if (entityFor("", scope)?.startsWith(prefix)) return ""
    Map m = (state["entityMap_${scope}"] instanceof Map) ? (Map) state["entityMap_${scope}"] : [:]
    def hit = m.find { k, v -> v?.toString()?.startsWith(prefix) }
    return hit ? hit.key as String : null
}

/**
 * The fan a setFan command should drive: the one named for the device
 * (suffix "") first, since that is the appliance's main fan, then `preferred`,
 * then any fan entity. Says once, at info, which one it picked.
 */
String fanTarget(String preferred = "fan") {
    String s = entityFor("")?.startsWith("fan.") ? "" : suffixInDomain("fan", preferred)
    if (s != null && state.fanTargetLogged != s) {
        log.info "${device.displayName}: setFan drives ${entityFor(s)}"
        state.fanTargetLogged = s
    }
    return s
}

/**
 * The first three lines of every driver's parseEntity: remember the entity,
 * note its health, and log it.
 */
void beginEntity(Map e) {
    rememberEntity((String) e.scope, (String) e.suffix, (String) e.entityId)
    noteEntityHealth(e)
    if (settings?.logEnable) log.debug "entity ${e.entityId} (${e.suffix}, scope=${e.scope}) = ${e.state}"
}

// ---------------------------------------------------------------------------
// Oven and Range: cooking helpers shared by the two drivers
// ---------------------------------------------------------------------------
//
// Both drivers fold a second oven cavity into one device. The listener names
// its scope after the subdevice ("subdevice1"); it is remembered as it is seen
// rather than assumed, so commands reach whatever this oven calls it.

/** Remember the scope a second-cavity entity arrived under. */
void ovenNoteCavity(String scope) {
    if (scope && scope != "main" && state.cavityScope != scope) state.cavityScope = scope
}

/** The second cavity's scope; "subdevice1" until one has been seen. */
String ovenCavityScope() { return (state.cavityScope ?: "subdevice1") as String }

/** Replica refused cavity commands unless ovenCavityStatus was "on". An unknown status is let through. */
boolean ovenCavityReady(String what) {
    if (device.currentValue("ovenCavityStatus") == "off") {
        log.warn "${device.displayName}: ${what} ignored -- the divider is out " +
                 "(ovenCavityStatus: off), so there is no lower oven"
        return false
    }
    return true
}

/** Set cook_time from a Replica-style time. Returns false, sending nothing, when the time can't be read. */
boolean ovenSetCookMinutes(String scope, String time) {
    Integer mins = replicaTimeToMinutes(time)
    if (mins == null) return false
    haSetNumber("cook_time", mins, scope)
    return true
}

/**
 * Replica's start(mode, time, setpoint); every argument is optional. A mode
 * the oven does not offer, or a time that can't be read, stops here rather
 * than pressing Start on whatever mode and time the oven last held. The
 * setpoint is converted from the hub's scale to the oven's.
 */
void ovenStartCook(String scope, String mode, String time, def setpoint) {
    // Check everything that can be checked before sending anything, so a
    // refused argument never leaves the oven half set up.
    Integer mins = null
    if (time) {
        mins = replicaTimeToMinutes(time)
        if (mins == null) {
            log.warn "${device.displayName}: not started -- can't read cook time '${time}'"
            return
        }
    }
    if (setpoint != null && !entityFor("setpoint", scope)) {
        log.warn "${device.displayName}: not started -- no setpoint entity is known for this oven"
        return
    }
    if (setpoint != null && !tempScaleOf(unitFor("setpoint", scope))) {
        log.warn "${device.displayName}: not started -- the oven has not reported its " +
                 "setpoint unit yet, so ${setpoint} can't be converted safely"
        return
    }
    if (mode && !setSelectOption("cooking_mode", mode, scope)) {
        log.warn "${device.displayName}: not started -- cooking mode '${mode}' was not accepted"
        return
    }
    if (mins != null) haSetNumber("cook_time", mins, scope)
    if (setpoint != null && !haSetTemperature("setpoint", setpoint, scope)) {
        log.warn "${device.displayName}: not started -- setpoint ${setpoint} was not sent"
        return
    }
    haPress("start_cooking", scope)
}
