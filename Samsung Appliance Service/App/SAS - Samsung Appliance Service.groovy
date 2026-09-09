/**
 *  SAS - Samsung Appliance Service
 *
 *  Brings Samsung appliances into Hubitat via Home Assistant + LocalThings,
 *  grouped ONE HUBITAT DEVICE PER APPLIANCE rather than one per HA entity.
 *
 *  Version: 0.3.3
 *  Author:  Albert Mulder (almulder)
 *
 *      appliance --CoAP/DTLS--> Home Assistant + LocalThings --websocket--> Hubitat
 *
 *  Home Assistant is a hard dependency: Groovy has no DTLS-CoAP stack, so
 *  Hubitat cannot talk to the appliances directly.
 *
 *  This app only collects credentials and drives selection. The websocket work
 *  lives in the Samsung Appliance Listener device, because Hubitat apps cannot
 *  open websockets and HA exposes the device registry over websocket only.
 *
 *  Install order:
 *      1. libraries/SAS-samsung-appliance-common   (library SAS-Samsung-Appliance-Common)
 *      2. drivers/SAS-samsung-listener
 *      3. the SAS appliance child drivers you need
 *      4. this app
 *
 *  menu: "Integrations" puts this under Hubitat's Integrations sidebar section
 *  rather than Apps. category: is documented as unused. Requires platform
 *  2.5.0 or later -- before that everything appeared under Apps.
 */

import groovy.transform.Field

@Field static final String APP_VERSION = "0.3.3"

// Plain dotted-quad only. No scheme, no port, no hostname.
@Field static final String IPV4_RE =
    /^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$/

definition(
    name:        "SAS - Samsung Appliance Service",
    namespace:   "almulder",
    author:      "Albert Mulder",
    description: "Samsung appliances in Hubitat via Home Assistant + LocalThings, one device per appliance",
    category:    "Integrations",   // documented as "currently not used"
    menu:        "Integrations",   // this is what places it in the sidebar.
                                   // Values: Integrations | Automations | Apps
                                   // Default is "Apps". Needs platform 2.5.0+.
    iconUrl:     "",
    iconX2Url:   "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "discoveryPage")
}

// ---------------------------------------------------------------------------
// Page 1 -- connection
// ---------------------------------------------------------------------------

Map mainPage() {
    // Be forgiving about what gets pasted in, then say what was changed.
    String cleaned = cleanIp(settings?.haIp as String)
    Boolean wasCleaned = (cleaned != null && settings?.haIp != null && cleaned != settings.haIp)
    if (wasCleaned) app.updateSetting("haIp", [value: cleaned, type: "text"])

    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {

        section(sectionTitle("Home Assistant connection")) {
            input name: "haIp", type: "text", required: true, submitOnChange: true,
                  title: "IP address",
                  description: "192.168.1.158"
            input name: "haPort", type: "number", required: true, defaultValue: 8123,
                  title: "Port", description: "8123", range: "1..65535"
            input name: "haToken", type: "password", required: true, submitOnChange: true,
                  title: "Long-lived access token",
                  description: "Home Assistant > your profile > Security > Long-lived access tokens"
            input name: "useSSL", type: "bool", defaultValue: false, submitOnChange: true,
                  title: "Home Assistant uses HTTPS"
            if (useSSL) {
                input name: "ignoreSSL", type: "bool", defaultValue: false,
                      title: "Ignore certificate errors (self-signed cert)"
            }

            if (wasCleaned) {
                paragraph note("Tidied that to <b>${cleaned}</b> — this field takes the address on its own.")
            }
            // Use the cleaned value, not settings: app.updateSetting() above
            // does not refresh `settings` for this execution.
            String problem = ipProblem(cleaned ?: (settings?.haIp as String))
            if (problem) paragraph warn(problem)
        }

        if (connectionReady()) {
            section(sectionTitle("Test")) {
                input name: "btnTest", type: "button", title: "Test connection"
                if (state.testResult) {
                    paragraph state.testOk ? ok(state.testResult) : warn(state.testResult)
                }
            }

            section(sectionTitle("Appliances")) {
                def listener = getListener()
                if (listener) {
                    paragraph "Listener: <b>${listener.currentValue('connection') ?: 'unknown'}</b>" +
                              " &nbsp;•&nbsp; appliances: <b>${listener.currentValue('appliances') ?: 0}</b>"
                }
                href name: "toDiscovery", page: "discoveryPage",
                     title: "Find and select appliances",
                     description: selectionSummary(), state: (selectedAppliances ? "complete" : null)
            }
        } else {
            section(sectionTitle("Appliances")) {
                paragraph note("Fill in the connection details above to continue.")
            }
        }

        section(sectionTitle("Logging")) {
            input name: "logEnable", type: "bool", defaultValue: true, title: "Debug logging"
            input name: "txtEnable", type: "bool", defaultValue: true, title: "Description text logging"
        }

        section {
            // Hubitat's own "Remove" confirmation counts this app's direct
            // children only, so it always says 1. Spell out the real total.
            Integer kids = 0
            try { kids = (getListener()?.applianceCount() ?: 0) as Integer } catch (ignored) { }
            if (kids) {
                paragraph note("Removing this app deletes the listener <b>and its ${kids} " +
                               "appliance device${kids == 1 ? '' : 's'}</b>. Hubitat's " +
                               "confirmation only counts the listener, because the " +
                               "appliances belong to it rather than to the app.")
            }
            paragraph "<div style='font-size:0.85em;color:#888'>SAS - Samsung Appliance Service v${APP_VERSION}</div>"
        }
    }
}

// ---------------------------------------------------------------------------
// Page 2 -- appliance selection
// ---------------------------------------------------------------------------

Map discoveryPage() {
    def listener = ensureListener()

    // Fetch BEFORE dynamicPage: refreshInterval is evaluated when the page is
    // opened, not while its body runs, so deciding it from a flag the body sets
    // meant the very first visit never refreshed -- the page said "this page
    // refreshes itself" and then sat there.
    List found = []
    String failure = null
    try {
        found = listener?.getDiscoveredAppliances() ?: []
    } catch (e) {
        // Swallowing this made a real error look exactly like "still scanning".
        failure = e.message ?: e.toString()
        log.error "discovery failed: ${failure}"
    }
    String conn = listener?.currentValue("connection")

    dynamicPage(name: "discoveryPage", title: "", install: false, uninstall: false,
                refreshInterval: (found ? 0 : 4)) {

        section(sectionTitle("Discovered appliances")) {
            if (!listener) {
                paragraph warn("Could not create the listener device. Is the " +
                               "'SAS - Samsung Appliance Listener' driver installed?")
                return
            }

            if (found) {
                Map options = [:]
                found.each { Map a ->
                    options[a.id] = a.type ? "${a.name}" : "${a.name} — no driver yet"
                }
                paragraph "Found <b>${found.size()}</b> appliance(s). " +
                          "Each one becomes a single Hubitat device."
                input name: "selectedAppliances", type: "enum", multiple: true,
                      required: false, submitOnChange: true,
                      title: "Appliances to add", options: options

                List unknown = found.findAll { !it.type }
                if (unknown) {
                    paragraph warn("No driver available yet for: " +
                                   unknown.collect { it.name }.join(", ") +
                                   ". These will be skipped. Check the listener's logs — " +
                                   "it says whether the type is unrecognised or simply " +
                                   "not implemented in this version.")
                }
            } else if (failure) {
                paragraph warn("Could not read the appliance list from the listener: " +
                               "<b>${failure}</b>")
            } else if (conn == "auth failed") {
                paragraph warn("Home Assistant rejected the access token. Go back and check it.")
            } else {
                paragraph note("Scanning… the listener is connecting to Home Assistant and " +
                               "reading the device registry. This page refreshes every few " +
                               "seconds until it finds something.")
            }

            // Always visible: without it, "nothing here" gives no clue whether
            // the socket is down, the registry is empty, or the page is early.
            paragraph "Listener: <b>${conn ?: 'starting'}</b>" +
                      " &nbsp;•&nbsp; last message: <i>${listener?.currentValue('lastMessage') ?: '—'}</i>"

            input name: "btnRescan", type: "button", title: "Rescan"
        }

        section(sectionTitle("Naming")) {
            input name: "stripModel", type: "bool", defaultValue: true, submitOnChange: true,
                  title: "Drop the board family from appliance names",
                  description: "Home Assistant names these " +
                               "<i>Samsung Dishwasher (DA_DW_A51_20_COMMON)</i>. " +
                               "On, the Hubitat device is just <i>Samsung Dishwasher</i>."
            paragraph note("Only the display name changes — the full name is still what " +
                           "entities are matched on. Devices you have renamed yourself are " +
                           "left alone, and if two selected appliances would end up sharing " +
                           "a name, both keep their board family.")
        }

        section(sectionTitle("Diagnostic entities")) {
            input name: "exposeDiagnostics", type: "bool", defaultValue: false,
                  title: "Also expose diagnostic entities",
                  description: "Alarm codes, connection mode, firmware-update flags and similar. " +
                               "Off keeps each appliance to the attributes worth automating on."
        }
    }
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

/** Strip anything that is not the bare address, so a pasted URL still works. */
String cleanIp(String raw) {
    if (raw == null) return null
    String s = raw.trim()
    if (!s) return s
    s = s.replaceFirst(/(?i)^[a-z][a-z0-9+.-]*:\/\//, "")   // scheme
    s = s.replaceFirst(/\/.*$/, "")                          // path
    s = s.replaceFirst(/:\d+$/, "")                          // port
    return s.trim()
}

String ipProblem(String ip) {
    if (!ip) return null
    if (!(ip ==~ IPV4_RE)) {
        return "<b>${ip}</b> is not a valid IPv4 address. Enter it as four numbers, " +
               "for example <b>192.168.1.158</b> — no <code>http://</code>, no port, no hostname."
    }
    return null
}

Boolean connectionReady() {
    return settings?.haIp && (settings.haIp ==~ IPV4_RE) && settings?.haToken
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnTest":
            testConnection()
            break
        case "btnRescan":
            // The page now decides its own refresh from whether it found
            // anything, so there is no flag to set here.
            def l = ensureListener()
            if (l) l.discover()
            break
    }
}

/**
 * Pre-flight over REST before the websocket is involved -- it tells the user
 * whether the address, port and token are right, separately from whether the
 * socket came up.
 */
void testConnection() {
    String base = "${useSSL ? 'https' : 'http'}://${settings.haIp}:${(settings.haPort ?: 8123) as Integer}"
    Map params = [
        uri:     base,
        path:    "/api/",
        headers: ["Authorization": "Bearer ${settings.haToken}"],
        timeout: 10
    ]
    if (useSSL && ignoreSSL) params.ignoreSSLIssues = true
    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                state.testOk = true
                state.testResult = "Connected to Home Assistant at ${base}."
            } else {
                state.testOk = false
                state.testResult = "Home Assistant answered with HTTP ${resp.status}."
            }
        }
    } catch (e) {
        state.testOk = false
        String m = e.message ?: e.toString()
        if (m.contains("401")) {
            state.testResult = "Reached ${base} but the token was rejected. Check the long-lived access token."
        } else {
            state.testResult = "Could not reach ${base}: ${m}"
        }
    }
    if (logEnable) log.debug "test connection: ${state.testResult}"
}

// ---------------------------------------------------------------------------
// Listener device
// ---------------------------------------------------------------------------

private String listenerDni() { return "samsung-listener-${app.id}" }

def getListener() { return getChildDevice(listenerDni()) }

def ensureListener() {
    def d = getListener()
    if (!d) {
        try {
            d = addChildDevice("almulder", "SAS - Samsung Appliance Listener", listenerDni(),
                               [name: "SAS - Samsung Appliance Listener",
                                label: "SAS - Samsung Appliance Listener", isComponent: false])
            log.info "created Samsung Appliance Listener"
        } catch (e) {
            log.error "could not create the listener device: ${e.message}"
            return null
        }
    }
    pushConnection(d)
    return d
}

/**
 * Only push when something actually changed. setConnection() restarts the
 * listener's websocket, and the discovery page re-renders every few seconds
 * while scanning -- pushing unconditionally would restart the socket on every
 * refresh and the scan would never complete.
 */
private void pushConnection(d) {
    if (!d || !connectionReady()) return
    String fingerprint = [settings.haIp, (settings.haPort ?: 8123) as Integer,
                          settings.haToken, (useSSL == true), (ignoreSSL == true)].join("|")
    if (state.connFingerprint == fingerprint) return
    state.connFingerprint = fingerprint
    if (logEnable) log.debug "connection settings changed -- restarting listener"
    d.setConnection([
        ip:        settings.haIp,
        port:      (settings.haPort ?: 8123) as Integer,
        token:     settings.haToken,
        ssl:       (useSSL == true),
        ignoreSsl: (ignoreSSL == true)
    ])
}

private String selectionSummary() {
    List sel = (selectedAppliances instanceof List) ? selectedAppliances : (selectedAppliances ? [selectedAppliances] : [])
    if (!sel) return "No appliances selected yet"
    return "${sel.size()} appliance(s) selected"
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
    log.info "SAS - Samsung Appliance Service ${APP_VERSION} installed"
    initialize()
}

void updated() {
    log.info "SAS - Samsung Appliance Service ${APP_VERSION} updated"
    initialize()
}

void uninstalled() {
    // Take the appliance devices out first. They are children of the LISTENER,
    // not of this app, so Hubitat's own removal only sees the listener -- which
    // is also why its confirmation dialog says "1 child device" no matter how
    // many appliances are installed.
    getChildDevices().each { d ->
        try { d.removeAllAppliances() } catch (e) {
            log.warn "could not remove appliance devices: ${e.message}"
        }
        deleteChildDevice(d.deviceNetworkId)
    }
}

void initialize() {
    if (!connectionReady()) {
        log.warn "connection details incomplete -- nothing to start"
        return
    }
    def d = ensureListener()
    if (!d) return

    // Naming preference has to land before children are created or relabelled.
    // Sent as a method call, not updateSetting: a setting written here would
    // not be readable by setSelectedAppliances in this same execution.
    try { d.setNaming(stripModel != false) } catch (ignored) { }

    List sel = (selectedAppliances instanceof List) ? selectedAppliances :
               (selectedAppliances ? [selectedAppliances] : [])
    d.setSelectedAppliances(sel)

    // Push the diagnostics preference down to every appliance child.
    d.getChildDevices().each { c ->
        try {
            c.updateSetting("exposeDiagnostics", [value: (exposeDiagnostics == true) ? "true" : "false", type: "bool"])
        } catch (ignored) { }
    }
}

// ---------------------------------------------------------------------------
// Small presentation helpers
// ---------------------------------------------------------------------------

private String sectionTitle(String t) { return "<b>${t}</b>" }
private String warn(String t) { return "<div style='color:#b00020'>${t}</div>" }
private String ok(String t)   { return "<div style='color:#00701a'>${t}</div>" }
private String note(String t) { return "<div style='color:#555'>${t}</div>" }
