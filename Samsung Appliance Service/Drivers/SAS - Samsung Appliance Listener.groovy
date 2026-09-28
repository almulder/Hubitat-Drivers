/**
 *  SAS - Samsung Appliance Listener
 *
 *  Holds the single Home Assistant websocket for the Samsung Appliance
 *  Service, discovers LocalThings appliances, creates one Hubitat child per
 *  appliance, and routes entity state to the right child.
 *
 *  Version: 0.4.6
 *  Author:  Albert Mulder (almulder)
 *
 *  Design notes
 *  ------------
 *  * ON CONNECT WE SEED EVERY ENTITY. This is the fix for HADB's biggest flaw:
 *    it only creates/updates a child when a state event happens to arrive, so
 *    anything that has not changed since HA started simply does not exist.
 *
 *  * The device registry is fetched over the websocket because HA exposes no
 *    REST equivalent. The ENTITY registry is deliberately NOT fetched: on a
 *    real install it is ~485 KB / 642 records covering every integration, and
 *    parsing that on-hub on every reconnect is not worth it. Instead,
 *    entity -> device is resolved by longest slugified-device-name prefix.
 *    That is exact, not a heuristic: HA builds object_id by slugifying the
 *    device name and appending the entity name. Longest-match is what makes
 *    the range's "Subdevice 1" resolve to the subdevice rather than the range,
 *    since its slug strictly extends the parent's.
 *
 *  * Initial state comes from REST /api/states rather than the websocket
 *    get_states. Same data, but a 158 KB HTTP body is friendlier to the hub
 *    than a 158 KB websocket frame.
 *
 *  * NOTHING HERE IS SPECIFIC TO ONE APPLIANCE MODEL. Select names come from
 *    Home Assistant's own translation table (frontend/get_translations) and
 *    each entity's translation_key comes from the entity registry, fetched for
 *    just this integration's entities via config/entity_registry/get_entries.
 *    Both are cached in state, so a reconnect costs one REST seed.
 *
 *    That matters because upstream keys cycle names per board family, and the
 *    tables CONFLICT -- washer_cycle_table_00 and _02 disagree on 15 of their
 *    16 shared codes. Generating names from one person's dump would show a
 *    different washer confidently wrong names.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.4.6"

// Reconnect backoff ladder, seconds.
@Field static final List<Integer> BACKOFF = [5, 10, 20, 40, 80, 160, 300]

metadata {
    definition(
        name:      "SAS - Samsung Appliance Listener",
        namespace: "almulder",
        author:    "Albert Mulder",
        singleThreaded: true
    ) {
        capability "Initialize"
        capability "Refresh"

        attribute "connection",  "string"   // connected | connecting | disconnected | auth failed
        attribute "appliances",  "number"   // child count
        attribute "lastMessage", "string"

        command "reconnect"
        command "discover"
    }

    preferences {
        input name: "haIp",     type: "text",     title: "Home Assistant IP address",
              description: "e.g. 192.168.1.158 -- address only, no http:// prefix", required: true
        input name: "haPort",   type: "number",   title: "Port", defaultValue: 8123, required: true
        input name: "haToken",  type: "password", title: "Long-lived access token", required: true
        input name: "useSSL",   type: "bool",     title: "Use SSL (https / wss)", defaultValue: false
        input name: "ignoreSSL", type: "bool",    title: "Ignore SSL certificate errors", defaultValue: false
        input name: "haLanguage", type: "text",    defaultValue: "en",
              title: "Language for appliance option names",
              description: "Matches Home Assistant. Falls back to the raw code where untranslated."
        input name: "stripModel", type: "bool",   defaultValue: true,
              title: "Drop the board family from appliance names",
              description: "\"Samsung Dishwasher (DA_DW_A51_20_COMMON)\" becomes \"Samsung Dishwasher\""
        input name: "logEnable", type: "bool",    title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool",    title: "Enable descriptionText logging", defaultValue: true
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
    log.info "SAS - Samsung Appliance Listener ${DRIVER_VERSION} installed"
    state.reqId = 0
    initialize()
}

void updated() {
    log.info "SAS - Samsung Appliance Listener ${DRIVER_VERSION} updated"
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void uninstalled() {
    closeSocket()
    removeAllAppliances()
}

/**
 * Remove every appliance device.
 *
 * The app calls this before deleting the listener. Hubitat's own cascade only
 * reaches the listener -- the appliances are children of IT, not of the app --
 * and while deleting the listener should run uninstalled() above, doing it
 * explicitly means removal never depends on that.
 */
void removeAllAppliances() {
    List kids = getChildDevices() ?: []
    if (kids) log.info "removing ${kids.size()} appliance device(s)"
    kids.each { deleteChildDevice(it.deviceNetworkId) }
}

/** How many appliance devices exist, so the app can say so before removal. */
Integer applianceCount() {
    return (getChildDevices() ?: []).size()
}

void logsOff() {
    log.warn "debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void initialize() {
    unschedule()
    closeSocket()
    state.reqId       = 0
    state.pendingReq  = [:]
    state.authed      = false
    state.backoffIdx  = 0
    state.seeded      = false
    if (!haIp || !haToken) {
        log.warn "Home Assistant IP and token are not set yet -- open the app, fill them in " +
                 "and press Done. (If you just did, this line is harmless: it is the device " +
                 "being created a moment before the app hands over the settings.)"
        sendEvent(name: "connection", value: "disconnected")
        return
    }
    runIn(1, "connect")
    runEvery1Minute("healthCheck")
}

void refresh() {
    if (state.authed) seedStates()
    else initialize()
}

void reconnect() {
    state.backoffIdx = 0
    initialize()
}

// ---------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------

private String baseUrl() {
    return "${useSSL ? 'https' : 'http'}://${haIp}:${(haPort ?: 8123) as Integer}"
}

private String socketUrl() {
    return "${useSSL ? 'wss' : 'ws'}://${haIp}:${(haPort ?: 8123) as Integer}/api/websocket"
}

void connect() {
    state.lastConnectMs = now()
    sendEvent(name: "connection", value: "connecting")
    String url = socketUrl()
    if (logEnable) log.debug "connecting to ${url}"
    try {
        interfaces.webSocket.connect(url, ignoreSSLIssues: (ignoreSSL == true))
    } catch (e) {
        log.error "websocket connect failed: ${e.message}"
        scheduleReconnect()
    }
}

private void closeSocket() {
    state.closingOnPurpose = true
    try { interfaces.webSocket.close() } catch (ignored) { }
    state.authed = false
}

void webSocketStatus(String status) {
    if (logEnable) log.debug "webSocketStatus: ${status}"
    if (status.startsWith("status: open")) {
        state.lastRxMs   = now()
        state.backoffIdx = 0
        // A close we asked for with no socket open never reports back.
        state.closingOnPurpose = false
        // Do not mark connected yet -- HA will send auth_required first.
    } else if (status.startsWith("status: closing")) {
        state.authed = false
        sendEvent(name: "connection", value: "disconnected")
        // Home Assistant closes the socket cleanly when it restarts. Only a
        // close we asked for (initialize, uninstall) may end here.
        if (state.closingOnPurpose) {
            state.closingOnPurpose = false
        } else {
            log.warn "Home Assistant closed the connection"
            scheduleReconnect()
        }
    } else if (status.startsWith("failure")) {
        state.authed = false
        sendEvent(name: "connection", value: "disconnected")
        log.warn "websocket failure: ${status}"
        scheduleReconnect()
    }
}

private void scheduleReconnect() {
    Integer idx = (state.backoffIdx ?: 0) as Integer
    Integer wait = BACKOFF[Math.min(idx, BACKOFF.size() - 1)]
    state.backoffIdx = idx + 1
    log.warn "reconnecting in ${wait}s"
    runIn(wait, "connect")
}

void healthCheck() {
    Long last = (state.lastRxMs ?: 0L) as Long
    if (!state.authed) {
        // Longer than the longest backoff step, so this only catches a
        // listener that nothing else is going to reconnect.
        Long tried = (state.lastConnectMs ?: 0L) as Long
        if (now() - tried > 360000L) {
            log.warn "not connected to Home Assistant -- trying again"
            state.backoffIdx = 0
            connect()
        }
        return
    }
    if (now() - last > 120000L) {
        log.warn "no traffic from Home Assistant in 2 minutes -- reconnecting"
        state.backoffIdx = 0
        initialize()
        return
    }
    send([type: "ping"])
}

// ---------------------------------------------------------------------------
// Outbound
// ---------------------------------------------------------------------------

private Integer nextId() {
    Integer id = ((state.reqId ?: 0) as Integer) + 1
    state.reqId = id
    return id
}

/** Send a message that carries an id, and remember what the id was for. */
private Integer sendTracked(Map msg, String purpose) {
    Integer id = nextId()
    msg.id = id
    Map pend = (state.pendingReq instanceof Map) ? state.pendingReq : [:]
    // A request whose result never arrives would otherwise sit here forever.
    if (pend.size() > 50) {
        log.warn "discarding ${pend.size()} unanswered request(s)"
        pend = [:]
    }
    pend[id.toString()] = purpose
    state.pendingReq = pend
    send(msg)
    return id
}

private void send(Map msg) {
    // HA requires an id on ping, but the reply comes back as type "pong"
    // rather than "result", so it is not tracked -- tracking would leak.
    if (msg.type == "ping" && msg.id == null) msg.id = nextId()
    String json = JsonOutput.toJson(msg)
    if (logEnable && msg.type != "ping") {
        log.debug "-> ${msg.type == 'auth' ? '{auth}' : json.take(300)}"
    }
    try {
        interfaces.webSocket.sendMessage(json)
    } catch (e) {
        log.error "send failed: ${e.message}"
        scheduleReconnect()
    }
}

/**
 * Called by child drivers. entityId may be a single id or a list.
 * Commands go out as call_service: switch.turn_on / switch.turn_off,
 * button.press, select.select_option, number.set_value.
 */
void callService(String domain, String service, def entityId, Map data = [:]) {
    if (!state.authed) {
        log.warn "not connected to Home Assistant -- dropping ${domain}.${service}"
        return
    }
    Map msg = [type: "call_service", domain: domain, service: service,
               target: [entity_id: entityId]]
    if (data) msg.service_data = data
    sendTracked(msg, "call_service:${domain}.${service}")
}

// ---------------------------------------------------------------------------
// Inbound
// ---------------------------------------------------------------------------

void parse(String description) {
    state.lastRxMs = now()
    Map msg
    try {
        msg = (Map) new groovy.json.JsonSlurper().parseText(description)
    } catch (e) {
        log.error "could not parse message: ${e.message}"
        return
    }

    switch (msg.type) {
        case "auth_required":
            if (logEnable) log.debug "auth_required (HA ${msg.ha_version}) -- authenticating"
            send([type: "auth", access_token: haToken])
            break

        case "auth_ok":
            log.info "authenticated to Home Assistant ${msg.ha_version}"
            state.authed = true
            sendEvent(name: "connection", value: "connected")
            // Registry first: nothing can be routed until we know the devices.
            sendTracked([type: "config/device_registry/list"], "device_registry")
            break

        case "auth_invalid":
            log.error "Home Assistant rejected the access token: ${msg.message}"
            state.authed = false
            sendEvent(name: "connection", value: "auth failed")
            closeSocket()
            break

        case "result":
            handleResult(msg)
            break

        case "event":
            handleEvent(msg)
            break

        case "pong":
            break

        default:
            if (logEnable) log.debug "unhandled message type ${msg.type}"
    }
}

private void handleResult(Map msg) {
    Map pend = (state.pendingReq instanceof Map) ? state.pendingReq : [:]
    String purpose = pend.remove(msg.id?.toString())
    state.pendingReq = pend

    if (msg.success == false) {
        log.error "request ${purpose ?: msg.id} failed: ${msg.error?.message}"
        if (purpose == "entity_meta") {
            // Fill the devices in anyway rather than leave them empty;
            // without categories, diagnostic entities simply show.
            state.metaReady = true
            seedStates()
        }
        return
    }

    switch (purpose) {
        case "device_registry":
            storeDeviceRegistry((List) msg.result)
            sendTracked([type    : "frontend/get_translations",
                         language: (haLanguage ?: "en"),
                         category: "entity",
                         integration: ["localthings"]], "translations")
            break

        case "translations":
            storeTranslations((Map) msg.result?.resources)
            // Subscribe before seeding so nothing that changes mid-seed is lost.
            sendTracked([type: "subscribe_events", event_type: "state_changed"], "subscribe")
            break

        case "entity_meta":
            storeEntityMeta((Map) msg.result)
            // The first fill-in waits for this; deliver it now.
            seedStates()
            break

        case "subscribe":
            if (logEnable) log.debug "subscribed to state_changed"
            seedStates()
            break

        default:
            if (logEnable && purpose?.startsWith("call_service")) log.debug "${purpose} ok"
    }
}

private void handleEvent(Map msg) {
    Map data = (Map) msg.event?.data
    if (!data) return
    Map newState = (Map) data.new_state
    if (!newState) return
    if (state.metaReady != true) return      // the first fill-in is on its way
    routeEntity((String) data.entity_id, newState, true)
}

// ---------------------------------------------------------------------------
// Device registry
// ---------------------------------------------------------------------------

/**
 * Keep only LocalThings devices. identifiers come through as
 * [["localthings", "<uuid>"]].
 */
private void storeDeviceRegistry(List registry) {
    Map devices = [:]
    registry.each { Map d ->
        boolean mine = false
        d.identifiers?.each { ident ->
            if (ident instanceof List && ident.any { it?.toString()?.toLowerCase() == "localthings" }) mine = true
        }
        if (!mine) return
        String ident = null
        d.identifiers?.each { i ->
            if (i instanceof List && i.size() >= 2 && i[0]?.toString()?.toLowerCase() == "localthings") {
                ident = i[1]?.toString()
            }
        }
        // Use `name`, NOT `name_by_user`: after a rename name_by_user no longer
        // matches the entity_id prefix, and the prefix is how we route.
        String name = d.name as String
        devices[d.id as String] = [
            id:    d.id as String,
            name:  name,
            slug:  slugify(name),
            ident: ident,
            model: d.model as String,
            via:   d.via_device_id as String,
            type:  detectType(name, d.model as String)
        ]
    }

    // A subdevice's identifier is its parent's plus "_<key>", which never
    // changes, unlike its name: LocalThings 0.28 renamed a range's second
    // cavity from "... Subdevice 1" to "... Lower oven", and Home Assistant
    // keeps an entity's id when its device is renamed. So each subdevice
    // also answers to the prefix its older entities carry, and gets a scope
    // built from its key rather than its name.
    devices.each { k, v ->
        List<String> slugs = [(String) v.slug]
        Map parent = v.via ? (Map) devices[v.via] : null
        String pid = parent?.ident
        String sid = v.ident
        if (pid && sid && sid.startsWith(pid + "_")) {
            v.subKey = sid.substring(pid.length() + 1)
            slugs << slugify("${parent.name} Subdevice ${v.subKey}")
        }
        v.slugs = slugs.unique()
    }
    state.devices = devices
    log.info "device registry: ${devices.size()} LocalThings device(s) found"
    if (logEnable) devices.each { k, v -> log.debug "  ${v.name} -> type=${v.type} via=${v.via}" }
    sendEvent(name: "lastMessage", value: "discovered ${devices.size()} appliance(s)")
}

/**
 * Appliance type detection, ordered. Board family alone is not enough --
 * washer and dryer both report DA_WM_TP1_21_COMMON -- so this keys on the
 * device name LocalThings assigns.
 *
 * ORDER MATTERS. "Dishwasher" contains "washer", "induction cooktop" contains
 * "cooktop", "range hood" contains "range". More specific entries come first.
 *
 * Mirrors the upstream registry set in
 * mbillow/localthings/custom_components/localthings/registry/by_type/.
 * `driver` is the Hubitat driver name; null means LocalThings supports the
 * type but this package does not have a driver for it yet, which the app
 * reports as "driver not available" rather than "unrecognised".
 */
@Field static final String DRIVER_PREFIX = "SAS - Samsung "

@Field static final List<Map> TYPE_TABLE = [
    [tokens: ["dishwasher"],                     type: "Dishwasher"],
    [tokens: ["air dresser", "airdresser"],      type: "Air Dresser"],
    [tokens: ["dryer"],                          type: "Dryer"],
    [tokens: ["washer"],                         type: "Washer"],
    [tokens: ["refrigerator", "fridge"],         type: "Refrigerator"],
    [tokens: ["microwave"],                      type: "Microwave"],
    [tokens: ["range hood", "hood"],             type: "Range Hood"],
    [tokens: ["induction"],                      type: "Induction Cooktop"],
    [tokens: ["cooktop"],                        type: "Cooktop"],
    // "Range" before "oven": a range is the oven + cooktop combo, and its
    // name may mention the oven too. An oven on its own gets the Oven
    // driver; a cooktop on its own is caught by the cooktop rows above.
    [tokens: ["range"],                          type: "Range"],
    [tokens: ["oven"],                           type: "Oven"],
    [tokens: ["air conditioner", "airconditioner", "a/c"], type: "Air Conditioner"],
    [tokens: ["air purifier"],                   type: "Air Purifier"],
    [tokens: ["air monitor"],                    type: "Air Monitor"],
    [tokens: ["dehumidifier"],                   type: "Dehumidifier"],
    [tokens: ["water purifier"],                 type: "Water Purifier"],
    [tokens: ["vacuum", "clean station"],        type: "Vacuum Station"],
    [tokens: ["ehs", "heat pump"],               type: "Heat Pump"]
]

/** Hubitat driver name for this appliance, or null if we have none yet. */
String detectType(String name, String model) {
    String n = (name ?: "").toLowerCase()
    Map hit = TYPE_TABLE.find { Map t -> t.tokens.any { n.contains(it) } }
    if (!hit) return null
    if (!hit.type) {
        log.warn "'${name}' is a LocalThings type this package has no driver for yet"
        return null
    }
    return (String) hit.type
}

/** Hubitat driver name for a bare appliance type. */
String driverNameFor(String type) { return DRIVER_PREFIX + type }

/**
 * Drop a trailing board family from the display name:
 *   "Samsung Dishwasher (DA_DW_A51_20_COMMON)" -> "Samsung Dishwasher"
 *
 * Display only. The FULL name is still what gets slugified for entity
 * routing -- stripping it there would break every entity match.
 */
String shortName(String name) {
    if (!name) return name
    String s = name.replaceAll(/\s*\([^)]*\)\s*$/, "").trim()
    return s ?: name
}

// ---------------------------------------------------------------------------
// Names and categories, from Home Assistant rather than from a dump
// ---------------------------------------------------------------------------

/**
 * Store the select-option names Home Assistant would display.
 *
 * LocalThings reports raw codes -- a washer cycle is "01" or "5 B" -- and HA's
 * FRONTEND renders "Normal" by looking the code up in the integration's own
 * translation table. Neither the REST nor the websocket state API ever hands
 * out the name, which is why this has to be fetched separately.
 *
 * Resources arrive flattened:
 *   component.localthings.entity.select.<translation_key>.state.<code>
 *
 * Every LocalThings select table together is about 10 KB, so there is no need
 * to work out which ones this install uses -- keep the lot.
 */
private void storeTranslations(Map resources) {
    String prefix = "component.localthings.entity.select."
    Map tables = [:]
    int names = 0
    resources?.each { k, v ->
        String key = k.toString()
        if (!key.startsWith(prefix)) return
        String rest = key.substring(prefix.length())
        int at = rest.indexOf(".state.")
        if (at < 0) return
        String table = rest.substring(0, at)
        String code = rest.substring(at + ".state.".length())
        Map t = (Map) tables[table]
        if (t == null) { t = [:]; tables[table] = t }
        t[normCode(code)] = v?.toString()
        names++
    }
    state.labelTables = tables
    log.info "option names: ${names} across ${tables.size()} table(s) in ${haLanguage ?: 'en'}"
}

/**
 * Store each entity's translation_key and entity_category.
 *
 * translation_key says WHICH name table applies -- essential, because the
 * per-board tables conflict and guessing by best-match is unreliable.
 * entity_category replaces a hardcoded list of diagnostic suffixes with what
 * Home Assistant actually says.
 *
 * Fetched with config/entity_registry/get_entries, which takes a batch of
 * entity_ids, so this costs one round trip rather than the 485 KB the full
 * registry listing would.
 */
private void storeEntityMeta(Map entries) {
    Map meta = [:]
    entries?.each { k, v ->
        if (!(v instanceof Map)) return
        Map e = (Map) v
        Map rec = [:]
        if (e.translation_key) rec.tk = e.translation_key
        if (e.entity_category) rec.cat = e.entity_category
        if (rec) meta[k.toString()] = rec
    }
    state.entityMeta = meta
    state.metaReady = true
    log.info "entity metadata: ${meta.size()} of ${entries?.size() ?: 0} entities carry a " +
             "translation key or category"
}

/** Ask for metadata on the appliance entities we just routed. */
private void requestEntityMeta(List<String> entityIds) {
    if (!entityIds) return
    if (logEnable) log.debug "requesting registry metadata for ${entityIds.size()} entities"
    sendTracked([type: "config/entity_registry/get_entries", entity_ids: entityIds], "entity_meta")
}

/** The name Home Assistant would show for a raw select code. */
String labelFor(String entityId, def code) {
    if (code == null) return null
    Map meta = (state.entityMeta instanceof Map) ? (Map) state.entityMeta[entityId] : null
    String tk = meta?.tk
    if (!tk) return null
    Map table = (state.labelTables instanceof Map) ? (Map) state.labelTables[tk] : null
    def hit = table?.get(normCode(code))
    if (hit) return hit.toString()
    // Only select tables are worth reporting: a sensor with a translation key
    // has no table here, and neither does an unknown/unavailable state.
    if (table != null && !LABEL_NULL_STATES.contains(normCode(code))) {
        noteUnlabeled(tk, code.toString())
    }
    return null
}

/**
 * Reverse of labelFor, so a child can accept the friendly name in a command.
 * Falls through unchanged when handed a raw code.
 */
String selectCodeFor(String entityId, String value) {
    if (value == null) return null
    Map meta = (state.entityMeta instanceof Map) ? (Map) state.entityMeta[entityId] : null
    String tk = meta?.tk
    if (!tk) return value
    Map table = (state.labelTables instanceof Map) ? (Map) state.labelTables[tk] : [:]
    if (table?.containsKey(normCode(value))) return value  // already a raw code
    def hit = table?.find { k, v -> v.toString() == value }
    return hit ? hit.key.toString() : value
}

// ---------------------------------------------------------------------------
// Seeding: pull every entity's current state so children populate immediately
// ---------------------------------------------------------------------------

void seedStates() {
    Map params = [
        uri:     baseUrl(),
        path:    "/api/states",
        headers: ["Authorization": "Bearer ${haToken}", "Content-Type": "application/json"],
        timeout: 30
    ]
    if (ignoreSSL) params.ignoreSSLIssues = true
    if (logEnable) log.debug "seeding state from ${baseUrl()}/api/states"
    asynchttpGet("seedResponse", params)
}

void seedResponse(hubitat.scheduling.AsyncResponse resp, Map cbData) {
    if (resp.hasError()) {
        log.error "state seed failed: ${resp.getErrorMessage()}"
        return
    }
    List states
    try {
        states = (List) resp.json
    } catch (e) {
        log.error "could not parse /api/states: ${e.message}"
        return
    }
    // First connect, or after a rediscovery: learn which entities are ours,
    // then ask Home Assistant for their categories and name tables BEFORE
    // handing anything to a device, so nothing is routed on a guess. The
    // registry's answer triggers this seed again, which then delivers.
    boolean deliver = (state.metaReady == true)
    int routed = 0
    List<String> mine = []
    states.each { Map s ->
        if (routeEntity((String) s.entity_id, s, deliver)) {
            routed++
            mine << (String) s.entity_id
        }
    }
    if (!deliver) {
        if (mine) {
            log.info "found ${routed} appliance entities -- asking Home Assistant which are diagnostic"
            requestEntityMeta(mine)
        }
        return
    }
    state.seeded = true
    log.info "seeded ${routed} appliance entities from ${states.size()} Home Assistant entities"
    sendEvent(name: "lastMessage", value: "seeded ${routed} entities")

}

// ---------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------

/**
 * Resolve an entity to its owning device by longest slug prefix, then hand it
 * to that appliance's child. Returns true when it belonged to us.
 */
private Boolean routeEntity(String entityId, Map stateObj, boolean deliver) {
    if (!entityId) return false
    Map devices = (state.devices instanceof Map) ? state.devices : [:]
    if (!devices) return false

    String obj = entityId.contains(".") ? entityId.substring(entityId.indexOf(".") + 1) : entityId

    // Longest matching prefix wins, over every name a device answers to --
    // that is what sends "<range> Subdevice 1 ..." to the cavity, not the range.
    Map best = null
    String bestSlug = null
    devices.each { k, v ->
        List slugs = (v.slugs instanceof List) ? (List) v.slugs : [v.slug]
        slugs.each { s ->
            String slug = s as String
            if (!slug) return
            if ((obj == slug || obj.startsWith(slug + "_")) &&
                    (bestSlug == null || slug.length() > bestSlug.length())) {
                best = (Map) v
                bestSlug = slug
            }
        }
    }
    if (best == null) return false

    // A subdevice (range second cavity) belongs to its parent appliance, not
    // to a Hubitat device of its own.
    Map owner = best
    String scope = "main"
    if (best.via && devices[best.via]) {
        owner = (Map) devices[best.via]
        if (best.subKey) {
            scope = snakeToCamel("subdevice_${best.subKey}")     // "subdevice1"
        } else {
            String os = (String) owner.slug
            String bs = (String) best.slug
            String remainder = bs.startsWith(os) ? bs.substring(os.length()).replaceAll(/^_+/, "") : bs
            scope = snakeToCamel(remainder) ?: "sub"
        }
    }

    def child = getChildDevice(childDni((String) owner.id))
    if (!child) return false
    if (!deliver) return true

    String suffix = (obj == bestSlug) ? "" : obj.substring(bestSlug.length() + 1)
    Map attrs = (stateObj.attributes instanceof Map) ? (Map) stateObj.attributes : [:]

    Map payload = [
        entityId : entityId,
        domain   : entityId.substring(0, entityId.indexOf(".")),
        suffix   : suffix,
        scope    : scope,
        state    : stateObj.state,
        unit     : attrs.unit_of_measurement,
        deviceClass : attrs.device_class,
        options  : attrs.options,
        // Fan speed: HA reports a percentage in steps of 100/speed_count
        // (25 for the microwave vent's four speeds).
        percentage     : attrs.percentage,
        percentageStep : attrs.percentage_step,
        entityCategory : categoryFor(entityId, suffix),
        // Resolved here, not in the child: only the listener holds the
        // translation table and the entity's translation_key.
        label       : labelFor(entityId, stateObj.state),
        optionLabels: attrs.options ? ((List) attrs.options).collect {
                          labelFor(entityId, it) ?: it.toString() } : null
    ]

    try {
        child.parseEntity(payload)
    } catch (e) {
        log.error "child ${child.displayName} failed on ${entityId}: ${e.message}"
    }
    return true
}

/**
 * Entity category, straight from Home Assistant's entity registry. Nothing is
 * routed before the registry has answered (see seedResponse), so there is no
 * guessing here.
 *
 * Only `diagnostic` is gated. HA's `config` entities are real user settings
 * (rapid_fridge, spin_speed, freezer_setpoint) and stay first-class.
 */
private String categoryFor(String entityId, String suffix) {
    Map meta = (state.entityMeta instanceof Map) ? (Map) state.entityMeta[entityId] : null
    return (String) meta?.cat
}

/**
 * The form codes are compared in. Home Assistant stores translation keys
 * lowercase, while appliances report them uppercase ("5B") and an older
 * LocalThings display helper split them ("5 B").
 */
private String normCode(def code) {
    return code == null ? null : code.toString().replaceAll(/\s+/, "").toLowerCase()
}

// Duplicated from the library's NULL_STATES, for the same reason as the
// naming helpers below.
@Field static final List<String> LABEL_NULL_STATES = ["unknown", "unavailable", "none", "null", ""]

/**
 * Say once per code, per install, that Home Assistant has no name for it and
 * the raw code is shown. The list this builds in the logs is the list to send
 * upstream to LocalThings -- names live there, not in this package.
 */
private void noteUnlabeled(String tk, String code) {
    String key = "${tk}|${normCode(code)}"
    Map seen = (state.unlabeledCodes instanceof Map) ? (Map) state.unlabeledCodes : [:]
    if (seen[key]) return
    seen[key] = true
    state.unlabeledCodes = seen
    log.warn "option ${tk} '${code}': Home Assistant has no name for it -- showing the raw " +
             "code. Worth reporting to LocalThings."
}

// ---------------------------------------------------------------------------
// Naming helpers (duplicated from the library -- the parent does not #include
// it, so that a library problem can never take the listener itself down)
// ---------------------------------------------------------------------------

String slugify(String name) {
    if (!name) return ""
    StringBuilder out = new StringBuilder()
    boolean prevUs = false
    name.toLowerCase().each { ch ->
        if (Character.isLetterOrDigit(ch as char)) {
            out.append(ch); prevUs = false
        } else if (!prevUs) {
            out.append("_"); prevUs = true
        }
    }
    String s = out.toString()
    while (s.startsWith("_")) { s = s.substring(1) }
    while (s.endsWith("_"))   { s = s.substring(0, s.length() - 1) }
    return s
}

String suffixOf(String entityId, String deviceName) {
    if (!entityId) return ""
    String obj = entityId.contains(".") ? entityId.substring(entityId.indexOf(".") + 1) : entityId
    if (deviceName) {
        String prefix = slugify(deviceName)
        if (obj == prefix) return ""
        if (obj.startsWith(prefix + "_")) return obj.substring(prefix.length() + 1)
    }
    return obj
}

String snakeToCamel(String s) {
    if (!s) return s
    List<String> parts = s.tokenize("_")
    if (!parts) return s
    StringBuilder sb = new StringBuilder(parts[0])
    parts.drop(1).each { sb.append(it.capitalize()) }
    return sb.toString()
}

// ---------------------------------------------------------------------------
// App-facing API
// ---------------------------------------------------------------------------

private String childDni(String haDeviceId) { return "samsung-${haDeviceId}" }

/** Called by the app after the user edits connection settings. */
void setConnection(Map cfg) {
    device.updateSetting("haIp",     [value: cfg.ip,    type: "text"])
    device.updateSetting("haPort",   [value: cfg.port,  type: "number"])
    device.updateSetting("haToken",  [value: cfg.token, type: "password"])
    device.updateSetting("useSSL",   [value: cfg.ssl ? "true" : "false", type: "bool"])
    device.updateSetting("ignoreSSL",[value: cfg.ignoreSsl ? "true" : "false", type: "bool"])

    // MUST be deferred. updateSetting() does not refresh the `settings` map for
    // the execution that called it, so initialize() running inline would read
    // the OLD values -- null on a fresh install -- decide the credentials were
    // missing, and never retry. One second is enough to land in a new
    // execution where the settings are visible.
    runIn(1, "initialize")
}

/**
 * Naming preference, pushed by the app.
 *
 * Kept in state rather than a setting for the same reason: the app sets this
 * and then immediately asks for the children to be created, and a setting
 * written in that execution would not be readable yet.
 */
void setNaming(Boolean strip) {
    state.stripModel = (strip != false)
}

private Boolean stripModelActive() {
    if (state.stripModel != null) return (state.stripModel == true)
    return (stripModel != false)
}

void discover() {
    // Re-read names and categories too: the user may have added an appliance
    // of a different board family, needing a different name table.
    state.metaReady = false
    state.entityMeta = [:]
    if (!state.authed) { initialize(); return }
    sendTracked([type: "config/device_registry/list"], "device_registry")
}

Boolean isConnected() { return state.authed == true }

/**
 * Appliance-level list for the app's selection page: 7 items, not 183.
 * Subdevices are folded into their parent and never offered separately.
 */
List getDiscoveredAppliances() {
    Map devices = (state.devices instanceof Map) ? state.devices : [:]
    List out = []
    devices.each { k, v ->
        if (v.via && devices[v.via]) return          // subdevice: belongs to its parent
        out << [id       : v.id,
                name     : stripModelActive() ? shortName((String) v.name) : v.name,
                fullName : v.name,
                model    : v.model,
                type     : v.type]
    }
    return out.sort { it.name }
}

/**
 * Label for an appliance's Hubitat device, honouring the strip-board-family
 * preference. If stripping would make two SELECTED appliances share a label --
 * two dishwashers, say -- both keep their board family so they stay tellable
 * apart.
 */
private String applianceLabel(Map d, Map devices, List wanted) {
    if (!stripModelActive()) return (String) d.name
    String shortLabel = shortName((String) d.name)
    int clashes = wanted.count { String id ->
        devices[id] && shortName((String) ((Map) devices[id]).name) == shortLabel
    }
    return (clashes > 1) ? (String) d.name : shortLabel
}

/** Create children for the selected appliances and remove any deselected. */
void setSelectedAppliances(List selectedIds) {
    Map devices = (state.devices instanceof Map) ? state.devices : [:]
    List wanted = (selectedIds ?: []).collect { it.toString() }
    state.selected = wanted

    wanted.each { String id ->
        Map d = (Map) devices[id]
        if (!d) { log.warn "appliance ${id} is no longer in the registry"; return }
        if (!d.type) {
            log.warn "cannot determine appliance type for '${d.name}' -- skipping"
            return
        }
        String dni    = childDni(id)
        String label  = applianceLabel(d, devices, wanted)
        String driver = driverNameFor((String) d.type)

        def existing = getChildDevice(dni)
        if (existing) {
            // Relabel when the preference changed, but never stomp a rename the
            // user made themselves -- only relabel if the current label is still
            // one of the two forms we generate.
            String cur = existing.label
            if (cur != label && (cur == d.name || cur == shortName((String) d.name))) {
                log.info "relabelling '${cur}' to '${label}'"
                existing.setLabel(label)
            }
            return
        }
        try {
            addChildDevice("almulder", driver, dni, [name: driver, label: label, isComponent: false])
            log.info "created ${driver} for ${label}"
        } catch (e) {
            log.error "could not create '${driver}' for ${d.name}: ${e.message}. Is the driver installed?"
        }
    }

    getChildDevices().each { c ->
        String id = c.deviceNetworkId.replace("samsung-", "")
        if (!wanted.contains(id)) {
            log.info "removing ${c.displayName}"
            deleteChildDevice(c.deviceNetworkId)
        }
    }

    sendEvent(name: "appliances", value: getChildDevices().size())
    if (state.authed) {
        seedStates()
    } else {
        // Connecting ends in a full seed, so new devices fill in either way.
        log.info "not connected to Home Assistant -- connecting so the appliance devices fill in"
        initialize()
    }
}
