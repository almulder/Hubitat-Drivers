/**
 *  SAS - Samsung Appliance Listener
 *
 *  Holds the single Home Assistant websocket for the Samsung Appliance
 *  Service, discovers LocalThings appliances, creates one Hubitat child per
 *  appliance, and routes entity state to the right child.
 *
 *  Version: 0.5.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.5.0
 *    - Subscribes only to this integration's entities (subscribe_trigger on a
 *      state trigger) instead of every state_changed event in Home Assistant;
 *      falls back to state_changed if Home Assistant refuses the trigger.
 *    - No state write per inbound message: receive bookkeeping is in memory.
 *    - Device registry, option names and categories are kept across
 *      reconnects; a reconnect is one subscription plus one REST seed.
 *    - Entities first seen later (a newly selected appliance) get their
 *      categories and names fetched before they are delivered.
 *    - A failed seed is retried with backoff, and a setup that stalls is
 *      restarted by healthCheck instead of leaving devices silently stale.
 *    - Rescan unsubscribes the old subscription before making a new one.
 *    - A rejected token is not retried until the settings change or
 *      Reconnect is pressed -- repeated bad logins can get the hub IP-banned.
 *    - A failed registry or subscribe step reconnects; a failed translation
 *      fetch carries on without names.
 *    - A seed snapshot no longer overwrites a newer live event.
 *    - Debug logging auto-off is no longer cancelled by initialize().
 *    - Backoff resets on auth_ok rather than on socket open.
 *    - Unanswered requests are evicted oldest first instead of all at once.
 *    - Pushes diagnostics visibility to the appliances; prunes entities
 *      Home Assistant no longer has after each full seed.
 *    - A seed taken while Home Assistant is still starting can no longer
 *      shrink the subscription: a reconnect subscribes to everything known
 *      plus anything new, and only Rescan, a selection change or a re-read of
 *      the registry may drop entities. A reconnect re-seeds once after 90s,
 *      and only that later seed prunes the devices.
 *    - Each reconnect is timed by the stall watchdog, which backs off after
 *      repeated restarts instead of reconnecting every two minutes forever.
 *    - A REST 401 is treated like a rejected token: no more retries.
 *    - Overlapping fill-ins no longer overwrite each other in memory.
 *    - A new Home Assistant address or port re-reads the registry.
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
 *    Both are cached in state, so a reconnect costs one subscription and one
 *    REST seed. Discover/Rescan, or saving this device's Preferences, fetches
 *    them again.
 *
 *  * ONLY OUR ENTITIES ARE SUBSCRIBED. subscribe_trigger with a state trigger
 *    listing this integration's entity_ids delivers the same old/new state
 *    objects as state_changed, but only for those entities -- a busy Home
 *    Assistant no longer streams every energy meter in the house to the hub.
 *    With no to/from given, a state trigger also fires on attribute-only
 *    changes, which is what the fan percentage needs. If Home Assistant
 *    refuses it, the listener falls back to subscribe_events state_changed.
 *
 *    That matters because upstream keys cycle names per board family, and the
 *    tables CONFLICT -- washer_cycle_table_00 and _02 disagree on 15 of their
 *    16 shared codes. Generating names from one person's dump would show a
 *    different washer confidently wrong names.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String DRIVER_VERSION = "0.5.0"

// Reconnect backoff ladder, seconds.
@Field static final List<Integer> BACKOFF = [5, 10, 20, 40, 80, 160, 300]

// Unanswered requests kept before the oldest are dropped.
@Field static final Integer MAX_PENDING_REQ = 50

// Authenticated but no delivered seed after this long: setup stalled.
@Field static final Long SETUP_STALL_MS = 120000L

// After a stalled setup is restarted, the next restart waits twice as long,
// up to 32 minutes, so a Home Assistant that never finishes is not hammered.
@Field static final Integer MAX_STALL_DOUBLINGS = 4

// A reconnect seed may be taken while Home Assistant is still starting and
// only has some entities. This later seed is the one allowed to prune.
@Field static final Integer CONFIRM_SEED_SECS = 90

// Per-message bookkeeping, keyed by device id. In memory rather than state:
// a state write re-persists everything in state -- the registry, ~10 KB of
// name tables -- and the old code did that for every inbound message.
@Field static final ConcurrentHashMap<String, Long> LAST_RX_MS = new ConcurrentHashMap<String, Long>()
// entity_id -> when a live event for it was last DELIVERED, so a seed reply
// that was snapshotted earlier does not overwrite it.
@Field static final ConcurrentHashMap<String, Map> LAST_EVENT_MS = new ConcurrentHashMap<String, Map>()
// Seed results waiting for registry metadata, so the first fill-in does not
// need a second 158 KB download: [requestedAt: Long, states: Map entityId -> state].
@Field static final ConcurrentHashMap<String, Map> PENDING_SEED = new ConcurrentHashMap<String, Map>()

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
        input name: "logEnable", type: "bool",    title: "Enable debug logging",
              description: "Turns itself off after 30 minutes", defaultValue: false
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
    if (logEnable) runIn(1800, "logsOff")
}

void updated() {
    log.info "SAS - Samsung Appliance Listener ${DRIVER_VERSION} updated"
    // Saving Preferences is the user's way to say "try again": the token or
    // language may have changed, so re-read everything from Home Assistant.
    state.authFailed = false
    state.forceSetup = true
    // The preference wins once it has been saved here; the app's pushed
    // value only stands until then.
    state.remove("stripModel")
    initialize()
    // After initialize(): it unschedules its own jobs, and this one must survive.
    if (logEnable) runIn(1800, "logsOff")
}

void uninstalled() {
    closeSocket()
    removeAllAppliances()
    LAST_RX_MS.remove(devKey())
    LAST_EVENT_MS.remove(devKey())
    clearPendingSeeds()
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
    // Only this driver's connection jobs: a blanket unschedule() also
    // cancelled the logsOff timer updated() had just set.
    unschedule("connect")
    unschedule("healthCheck")
    unschedule("seedStates")
    unschedule("confirmSeed")
    closeSocket()
    state.reqId       = 0
    state.pendingReq  = [:]
    state.authed      = false
    state.backoffIdx  = 0
    state.seeded      = false
    state.seedFails   = 0
    state.remove("subId")
    state.remove("oldSubId")           // ids restart at 1 on a new socket; a stale one would cancel the new subscription
    state.remove("subscribedIds")
    state.remove("metaReady")          // superseded by per-entity metadata in 0.5.0
    state.remove("lastRxMs")           // moved to memory in 0.5.0
    state.remove("seedRequestedAt")    // carried in the request since 0.5.0
    clearPendingSeeds()
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
    state.authFailed = false
    initialize()
}

private String devKey() { return device.id.toString() }

private void markRx() { LAST_RX_MS.put(devKey(), now()) }

private Long lastRx() { return LAST_RX_MS.get(devKey()) }

/** A fill-in held while its metadata is fetched, keyed by that request's id. */
private String seedKey(def reqId) { return "${devKey()}:${reqId}".toString() }

private void clearPendingSeeds() {
    String prefix = devKey() + ":"
    PENDING_SEED.keySet().removeAll { it.startsWith(prefix) }
}

private List<Map> pendingSeeds() {
    String prefix = devKey() + ":"
    return PENDING_SEED.findAll { k, v -> k.startsWith(prefix) }.values().toList()
}

private Map lastEventMap() {
    Map m = LAST_EVENT_MS.get(devKey())
    if (m == null) {
        m = new ConcurrentHashMap()
        Map prior = LAST_EVENT_MS.putIfAbsent(devKey(), m)
        if (prior != null) m = prior
    }
    return m
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
    if (state.authFailed) {
        log.warn "not reconnecting: Home Assistant rejected the access token. Fix it in the " +
                 "app (or this device's Preferences), or press Reconnect."
        return
    }
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
        markRx()
        // backoffIdx is reset on auth_ok, not here: a server that accepts the
        // socket and then drops it before auth must still back off.
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
    if (!state.authed) {
        // A rejected token stays rejected: retrying it every few minutes only
        // fills Home Assistant's log, and with ip_ban_enabled bans the hub.
        if (state.authFailed) return
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
    // Authenticated, but the devices never got their first fill-in: a lost
    // reply or a failed step left setup half done while pings still answer.
    if (state.seeded != true) {
        Long authedAt = (state.authedAtMs ?: 0L) as Long
        Integer n = (state.stallRestarts ?: 0) as Integer
        Long limit = SETUP_STALL_MS * (1L << Math.min(n, MAX_STALL_DOUBLINGS))
        if (now() - authedAt > limit) {
            log.warn "connected but setup has not finished after ${(int) (limit / 1000)}s -- reconnecting"
            state.stallRestarts = n + 1
            state.forceSetup = true
            initialize()
            return
        }
    }
    Long last = lastRx()
    if (last == null) {
        // Memory is empty after a driver code save; start counting from now.
        markRx()
        last = now()
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
    // Drop the OLDEST ones only: clearing the lot also threw away requests
    // still in flight, and losing an entity_meta reply stalled setup.
    if (pend.size() >= MAX_PENDING_REQ) {
        List<String> oldest = pend.keySet().collect { it.toString() }
                                  .sort { it.isInteger() ? it.toInteger() : 0 }
                                  .take(pend.size() - MAX_PENDING_REQ + 10)
        log.warn "discarding ${oldest.size()} unanswered request(s)"
        oldest.each { pend.remove(it) }
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
    markRx()
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
            state.authed     = true
            state.authFailed = false
            state.backoffIdx = 0
            state.authedAtMs = now()
            // Not seeded until this connection's fill-in lands, so a reconnect
            // that stalls is caught by healthCheck too.
            state.seeded     = false
            sendEvent(name: "connection", value: "connected")
            // The registry, name tables and categories survive a reconnect;
            // only a first connect, Rescan or a Preferences save re-reads them.
            if (haveSetup()) startStream()
            else startSetup()
            break

        case "auth_invalid":
            log.error "Home Assistant rejected the access token: ${msg.message}. Not retrying " +
                      "until the token is changed or Reconnect is pressed."
            state.authed     = false
            state.authFailed = true
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

// ---------------------------------------------------------------------------
// Connect sequence
// ---------------------------------------------------------------------------
//
//   first connect:  device_registry -> translations -> seed -> subscribe
//                   -> entity_meta for the new entities -> deliver
//   reconnect:      subscribe (cached entity list) -> seed -> deliver
//
// A seed is never delivered for an entity whose registry metadata has not
// arrived, so nothing is routed on a guess about categories or names.

private Boolean haveSetup() {
    return (state.devices instanceof Map) && (state.labelTables instanceof Map) &&
           (state.forceSetup != true)
}

private void startSetup() {
    // A fresh registry read: this setup's seed may drop entities it no longer finds.
    state.fullSeedPending = true
    if (state.forceSetup == true) {
        // Re-read categories too, and give subscribe_trigger another chance.
        state.entityMeta = [:]
        state.remove("triggerUnsupported")
    }
    // Registry first: nothing can be routed until we know the devices.
    sendTracked([type: "config/device_registry/list"], "device_registry")
}

private void startStream() {
    state.remove("forceSetup")
    List ids = (state.entityIds instanceof List) ? (List) state.entityIds : []
    // Subscribe before seeding so nothing that changes mid-seed is lost. On a
    // first connect the entity list is not known yet, so the seed goes first
    // and subscribes for what it finds.
    if (ids || state.triggerUnsupported) subscribeEntities(ids, true)
    else seedStates()
}

/**
 * Subscribe to state changes for exactly these entities.
 *
 * The new subscription is made FIRST and the old one dropped once Home
 * Assistant confirms it, so changing the entity list never leaves a gap; an
 * event delivered by both is harmless, the children de-duplicate.
 */
private void subscribeEntities(List ids, boolean thenSeed) {
    if (state.subId != null) {
        if (state.oldSubId == null) {
            state.oldSubId = state.subId
        } else {
            // Two changes before the first was confirmed: the middle one goes.
            sendTracked([type: "unsubscribe_events", subscription: state.subId as Integer], "unsubscribe")
        }
        state.remove("subId")
    }
    if (state.triggerUnsupported) {
        state.remove("subscribedIds")
        state.subId = sendTracked([type: "subscribe_events", event_type: "state_changed"],
                                  thenSeed ? "suball_seed" : "suball")
        return
    }
    if (!ids) {
        state.subscribedIds = []
        dropOldSubscription()
        if (thenSeed) seedStates()
        return
    }
    state.subscribedIds = ids
    // No to/from: a state trigger then also fires on attribute-only changes.
    // `platform` rather than the newer `trigger` key, which older Home
    // Assistant releases do not accept.
    state.subId = sendTracked([type: "subscribe_trigger",
                               trigger: [platform: "state", entity_id: ids]],
                              thenSeed ? "sub_seed" : "sub")
}

private void dropOldSubscription() {
    if (state.oldSubId == null) return
    sendTracked([type: "unsubscribe_events", subscription: state.oldSubId as Integer], "unsubscribe")
    state.remove("oldSubId")
}

/** A step the connection cannot do without failed: say so and start over. */
private void restartAfterError(String what) {
    log.error "${what} -- reconnecting"
    sendEvent(name: "connection", value: "error")
    closeSocket()
    scheduleReconnect()
}

private void handleResult(Map msg) {
    Map pend = (state.pendingReq instanceof Map) ? state.pendingReq : [:]
    String purpose = pend.remove(msg.id?.toString())
    state.pendingReq = pend

    if (msg.success == false) {
        String why = msg.error?.message
        switch (purpose) {
            case "device_registry":
                restartAfterError("could not read the device registry: ${why}")
                break
            case "translations":
                // Names are a nicety: carry on with raw codes rather than stop.
                log.warn "could not read option names (${why}) -- options will show as raw codes"
                if (!(state.labelTables instanceof Map)) state.labelTables = [:]
                startStream()
                break
            case "sub":
            case "sub_seed":
                log.warn "Home Assistant refused subscribe_trigger (${why}) -- " +
                         "falling back to every state_changed event"
                if (state.subId == msg.id) state.remove("subId")
                state.triggerUnsupported = true
                subscribeEntities([], purpose == "sub_seed")
                break
            case "suball":
            case "suball_seed":
                if (state.subId == msg.id) state.remove("subId")
                restartAfterError("could not subscribe to state changes: ${why}")
                break
            case "entity_meta":
                // Fill the devices in anyway rather than leave them empty;
                // without categories, diagnostic entities simply show.
                log.error "could not read entity metadata: ${why} -- filling devices in without it"
                Map ps = PENDING_SEED.get(seedKey(msg.id))
                storeEntityMeta([:], (List) ps?.metaIds)
                deliverPendingSeed(msg.id)
                break
            case "unsubscribe":
                if (logEnable) log.debug "unsubscribe: ${why}"
                break
            default:
                log.error "request ${purpose ?: msg.id} failed: ${why}"
        }
        return
    }

    switch (purpose) {
        case "device_registry":
            storeDeviceRegistry((msg.result instanceof List) ? (List) msg.result : [])
            sendTracked([type    : "frontend/get_translations",
                         language: (haLanguage ?: "en"),
                         category: "entity",
                         integration: ["localthings"]], "translations")
            break

        case "translations":
            storeTranslations((msg.result instanceof Map) ? (Map) msg.result.resources : null)
            startStream()
            break

        case "entity_meta":
            storeEntityMeta((msg.result instanceof Map) ? (Map) msg.result : [:],
                            (List) PENDING_SEED.get(seedKey(msg.id))?.metaIds)
            deliverPendingSeed(msg.id)
            break

        case "sub":
        case "sub_seed":
        case "suball":
        case "suball_seed":
            if (logEnable) log.debug "subscribed (${purpose})"
            dropOldSubscription()
            if (purpose.endsWith("_seed")) seedStates()
            break

        default:
            if (logEnable && purpose?.startsWith("call_service")) log.debug "${purpose} ok"
    }
}

private void handleEvent(Map msg) {
    // Only our current subscription (or the one it is replacing).
    def mid = msg.id
    if (mid == null || (mid != state.subId && mid != state.oldSubId)) return
    Map ev = (Map) msg.event
    if (!ev) return

    String entityId = null
    Map newState = null
    if (ev.data instanceof Map) {                       // subscribe_events state_changed
        entityId = (String) ev.data.entity_id
        newState = (Map) ev.data.new_state
    } else if (ev.variables instanceof Map) {           // subscribe_trigger
        Map trig = (Map) ((Map) ev.variables).trigger
        entityId = (String) trig?.entity_id
        newState = (Map) trig?.to_state
    }
    if (!entityId || !newState) return

    Map meta = (state.entityMeta instanceof Map) ? (Map) state.entityMeta : [:]
    if (!meta.containsKey(entityId)) {
        // Not ours, or ours but still waiting for its metadata: keep the
        // newer state for the pending fill-in so it is not lost.
        pendingSeeds().each { Map ps ->
            if (((Map) ps.states).containsKey(entityId)) ((Map) ps.states)[entityId] = newState
        }
        return
    }
    if (routeEntity(entityId, newState, true) != null) lastEventMap().put(entityId, now())
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
 * `type` is the appliance type; driverNameFor() turns it into the Hubitat
 * driver name. A name that matches no row has no driver in this package, and
 * the app lists it as "no driver yet".
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

/** Appliance type for this device name, or null if this package has no driver for it. */
String detectType(String name, String model) {
    String n = (name ?: "").toLowerCase()
    Map hit = TYPE_TABLE.find { Map t -> t.tokens.any { n.contains(it) } }
    return hit ? (String) hit.type : null
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
private void storeEntityMeta(Map entries, List requested) {
    Map meta = (state.entityMeta instanceof Map) ? (Map) state.entityMeta : [:]
    // Every entity asked about gets an entry, even an empty one: an entry is
    // what says "metadata settled, this entity can be delivered".
    (requested ?: []).each { String id -> if (!meta.containsKey(id)) meta[id] = [:] }
    int carried = 0
    entries?.each { k, v ->
        Map rec = [:]
        if (v instanceof Map) {
            Map e = (Map) v
            if (e.translation_key) rec.tk = e.translation_key
            // Only diagnostic is hidden. `config` entities are real settings
            // (a fridge's zone modes, spin speed) and must reach the device.
            if (e.entity_category == "diagnostic") rec.cat = "diagnostic"
        }
        if (rec) carried++
        meta[k.toString()] = rec
    }
    state.entityMeta = meta
    log.info "entity metadata: ${carried} of ${entries?.size() ?: 0} entities carry a " +
             "translation key or category"
}

/** Ask for metadata on appliance entities we have not seen before. */
private Integer requestEntityMeta(List<String> entityIds) {
    if (!entityIds) return null
    if (logEnable) log.debug "requesting registry metadata for ${entityIds.size()} entities"
    return sendTracked([type: "config/entity_registry/get_entries", entity_ids: entityIds], "entity_meta")
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

/**
 * opts.confirm marks the re-seed a reconnect schedules: Home Assistant has had
 * time to load every entity, so this one may prune. A full seed (setup,
 * Rescan, a selection change) may also shrink the entity list.
 */
void seedStates(Map opts = null) {
    if (!state.authed) return
    Map params = [
        uri:     baseUrl(),
        path:    "/api/states",
        headers: ["Authorization": "Bearer ${haToken}", "Content-Type": "application/json"],
        timeout: 30
    ]
    if (ignoreSSL) params.ignoreSSLIssues = true
    if (logEnable) log.debug "seeding state from ${baseUrl()}/api/states"
    // A live event delivered after this moment is newer than the snapshot.
    // Carried in the request, so overlapping seeds each keep their own.
    Map cb = [requestedAt: now(), full: (state.fullSeedPending == true), confirm: (opts?.confirm == true)]
    asynchttpGet("seedResponse", params, cb)
}

/** Scheduled after a reconnect seed. */
void confirmSeed() { seedStates([confirm: true]) }

private void seedFailed(String why) {
    Integer n = ((state.seedFails ?: 0) as Integer) + 1
    state.seedFails = n
    Integer wait = BACKOFF[Math.min(n - 1, BACKOFF.size() - 1)]
    log.error "state seed failed: ${why} -- retrying in ${wait}s"
    sendEvent(name: "lastMessage", value: "state seed failed: ${why}")
    if (state.authed) runIn(wait, "seedStates")
}

void seedResponse(hubitat.scheduling.AsyncResponse resp, Map cbData) {
    if (resp.status == 401) {
        // Same token as the websocket: retrying only adds failed logins.
        log.error "Home Assistant rejected the token for REST (HTTP 401). Not retrying " +
                  "until the token is changed or Reconnect is pressed."
        state.authFailed = true
        sendEvent(name: "connection", value: "auth failed")
        sendEvent(name: "lastMessage", value: "REST seed rejected the token (HTTP 401)")
        closeSocket()
        return
    }
    if (resp.hasError()) {
        seedFailed(resp.getErrorMessage())
        return
    }
    List states
    try {
        states = (List) resp.json
    } catch (e) {
        seedFailed("could not parse /api/states: ${e.message}")
        return
    }
    if (states == null) { seedFailed("empty /api/states reply"); return }
    state.seedFails = 0
    Long requestedAt = (cbData?.requestedAt ?: 0L) as Long
    boolean full    = (cbData?.full == true)
    boolean confirm = (cbData?.confirm == true)
    if (full) state.remove("fullSeedPending")

    // Which entities are ours, i.e. belong to an appliance with a device.
    Map matched = [:]
    states.each { Map s ->
        String id = (String) s?.entity_id
        if (id && routeEntity(id, s, false) != null) matched[id] = s
    }
    List<String> ids = matched.keySet().collect { it.toString() }

    // Keep the cached list and the subscription in step with what we route.
    // Only a full seed may shrink them: a reconnect seed can arrive while Home
    // Assistant is still loading, and would otherwise unsubscribe entities
    // that are merely late.
    List cached = (state.entityIds instanceof List) ? (List) state.entityIds : []
    List keep = full ? ids : (cached + ids).unique()
    if ((cached as Set) != (keep as Set)) state.entityIds = keep
    if (full) lastEventMap().keySet().retainAll(ids)
    if (!state.triggerUnsupported) {
        List subscribed = (state.subscribedIds instanceof List) ? (List) state.subscribedIds : null
        if (subscribed == null || (subscribed as Set) != (keep as Set)) subscribeEntities(keep, false)
    }
    // A reconnect seed gets one more look once Home Assistant has settled;
    // that one may prune.
    boolean prune = full || confirm
    if (!prune) runIn(CONFIRM_SEED_SECS, "confirmSeed")

    // First connect, or a newly selected appliance: ask Home Assistant for
    // these entities' categories and name tables BEFORE handing anything to a
    // device, so nothing is routed on a guess. The snapshot waits in memory
    // instead of being downloaded a second time.
    Map meta = (state.entityMeta instanceof Map) ? (Map) state.entityMeta : [:]
    List<String> missing = ids.findAll { !meta.containsKey(it) }
    if (missing) {
        log.info "found ${missing.size()} new appliance entities -- asking Home Assistant which are diagnostic"
        Integer reqId = requestEntityMeta(missing)
        if (reqId != null) {
            PENDING_SEED.put(seedKey(reqId), [requestedAt: requestedAt, states: matched,
                                              metaIds: missing, prune: prune])
            return
        }
    }
    deliverSeed(matched, requestedAt, states.size(), prune)
}

/** Deliver a snapshot held while its metadata was fetched. */
private void deliverPendingSeed(def reqId) {
    Map ps = PENDING_SEED.remove(seedKey(reqId))
    if (ps == null) {
        // Lost (a driver code save clears memory): fetch it again.
        seedStates()
        return
    }
    deliverSeed((Map) ps.states, (ps.requestedAt ?: 0L) as Long, null, ps.prune == true)
}

private void deliverSeed(Map states, Long requestedAt, Integer total, boolean prune) {
    Map lastEv = lastEventMap()
    Map<String, List> keysByChild = [:]
    int routed = 0
    int newer = 0
    states.each { k, v ->
        String id = k.toString()
        // A live event that arrived after the snapshot was requested is
        // at least as new as the snapshot: keep it.
        Long ev = (lastEv.get(id) != null) ? (lastEv.get(id) as Long) : null
        boolean stale = (ev != null && requestedAt && ev > requestedAt)
        if (stale) newer++
        Map info = routeEntity(id, (Map) v, !stale)
        if (info == null) return
        routed++
        List keys = keysByChild[(String) info.dni]
        if (keys == null) { keys = []; keysByChild[(String) info.dni] = keys }
        keys << info.key
    }
    // Every entity Home Assistant still has was just seen: let each device
    // forget the ones it no longer has. Not on a reconnect seed, which may
    // predate Home Assistant finishing its own startup.
    if (prune) keysByChild.each { String dni, List keys ->
        def child = getChildDevice(dni)
        try { child?.pruneEntities(keys) } catch (e) {
            if (logEnable) log.debug "${child?.displayName}: pruneEntities unavailable (${e.message})"
        }
    }
    state.seeded = true
    state.remove("stallRestarts")
    String from = (total != null) ? " from ${total} Home Assistant entities" : ""
    log.info "seeded ${routed} appliance entities${from}" +
             (newer ? " (${newer} already newer from live events)" : "")
    sendEvent(name: "lastMessage", value: "seeded ${routed} entities")
}

// ---------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------

/**
 * Resolve an entity to its owning device by longest slug prefix, and when
 * `deliver` is set hand it to that appliance's child. Returns null when it
 * is not ours, otherwise [dni: child DNI, key: "scope:suffix"].
 */
private Map routeEntity(String entityId, Map stateObj, boolean deliver) {
    if (!entityId || stateObj == null) return null
    Map devices = (state.devices instanceof Map) ? state.devices : [:]
    if (!devices) return null

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
    if (best == null) return null

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

    String dni = childDni((String) owner.id)
    def child = getChildDevice(dni)
    if (!child) return null

    String suffix = (obj == bestSlug) ? "" : obj.substring(bestSlug.length() + 1)
    Map info = [dni: dni, key: "${scope}:${suffix}".toString()]
    if (!deliver) return info

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
        optionLabels: (attrs.options instanceof List) ? ((List) attrs.options).collect {
                          labelFor(entityId, it) ?: it?.toString() } : null
    ]

    try {
        child.parseEntity(payload)
    } catch (e) {
        log.error "child ${child.displayName} failed on ${entityId}: ${e.message}"
    }
    return info
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
    // Checked here too, not only when storing: before 0.4.7 every category
    // was stored, and a stored "config" would keep settings hidden.
    return (meta?.cat == "diagnostic") ? "diagnostic" : null
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

// KNOWN LIMIT: Home Assistant transliterates with unidecode, so "Café" slugs
// to "cafe"; this keeps the accented letter. A device name with non-ASCII
// letters will not route. LocalThings names its devices in English, so this
// has not come up, and the Java text normaliser that would fix it is not on
// Hubitat's allowed-class list as far as is known.
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
    // Another address or port may be another Home Assistant, with its own
    // registry, names and entity list: read them all again.
    if (cfg.ip?.toString() != haIp?.toString() || cfg.port?.toString() != haPort?.toString()) {
        state.forceSetup = true
        state.remove("entityIds")
    }
    device.updateSetting("haIp",     [value: cfg.ip,    type: "text"])
    device.updateSetting("haPort",   [value: cfg.port,  type: "number"])
    device.updateSetting("haToken",  [value: cfg.token, type: "password"])
    device.updateSetting("useSSL",   [value: cfg.ssl ? "true" : "false", type: "bool"])
    device.updateSetting("ignoreSSL",[value: cfg.ignoreSsl ? "true" : "false", type: "bool"])

    // MUST be deferred. updateSetting() does not refresh the `settings` map for
    // the execution that called it, so initialize() running inline would read
    // the OLD values -- null on a fresh install -- decide the credentials were
    // missing, and never retry. Two seconds lands in a new execution where
    // the settings are visible, and is the same slot setSelectedAppliances()
    // uses, so the two collapse into one restart instead of two.
    state.authFailed = false            // a new token deserves a try
    runIn(2, "initialize")
}

/**
 * Naming preference, pushed by the app.
 *
 * Kept in state rather than a setting for the same reason: the app sets this
 * and then immediately asks for the children to be created, and a setting
 * written in that execution would not be readable yet. The setting is
 * written too, so this device's Preferences show the same thing; saving them
 * clears the state copy and the preference takes over.
 */
void setNaming(Boolean strip) {
    boolean s = (strip != false)
    state.stripModel = s
    device.updateSetting("stripModel", [value: s ? "true" : "false", type: "bool"])
}

/**
 * Diagnostics visibility, pushed by the app. Applied to every appliance by
 * setSelectedAppliances(), which the app calls straight after, and which
 * re-seeds so newly visible entities fill in at once.
 */
void setDiagnostics(Boolean expose) {
    state.exposeDiagnostics = (expose == true)
}

private void applyDiagnosticsToChildren() {
    if (state.exposeDiagnostics == null) return
    Boolean expose = (state.exposeDiagnostics == true)
    (getChildDevices() ?: []).each { c ->
        try {
            c.applyDiagnosticsSetting(expose)
        } catch (e) {
            log.warn "${c.displayName}: could not apply the diagnostics setting (${e.message}) -- " +
                     "is its driver and the SAS library up to date?"
        }
    }
}

private Boolean stripModelActive() {
    if (state.stripModel != null) return (state.stripModel == true)
    return (stripModel != false)
}

void discover() {
    // Re-read names and categories too: the user may have added an appliance
    // of a different board family, needing a different name table. The
    // existing subscription is replaced, not added to, once setup re-runs.
    state.forceSetup = true
    if (!state.authed) { initialize(); return }
    state.seeded = false
    state.authedAtMs = now()           // the stall watchdog times this run
    startSetup()
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
    applyDiagnosticsToChildren()
    if (state.authed) {
        // Finds any new appliance's entities, fetches their metadata before
        // delivering them, and extends the subscription to cover them. A
        // selection change may also drop a removed appliance's entities.
        state.fullSeedPending = true
        seedStates()
    } else {
        // Connecting ends in a full seed, so new devices fill in either way.
        // Deferred, not inline: when the app has just pushed new connection
        // settings they are not readable in this execution, and an inline
        // initialize() here also cancelled that deferred restart -- so the
        // old token was used until the next healthCheck retry.
        log.info "not connected to Home Assistant -- connecting so the appliance devices fill in"
        runIn(2, "initialize")
    }
}
