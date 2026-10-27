/**
 *  YoLink Device Local Service (Local API Edition)
 *  © 2025 Albert Mulder. All rights reserved.
 *  
 *  THIS SOFTWARE IS NEITHER DEVELOPED, ENDORSED, OR ASSOCIATED WITH YoLink OR YoSmart, Inc.
 *  
 *  Developer retains all rights, title, copyright, and interest, including patent rights and trade
 *  secrets in this software. Developer grants a non-exclusive perpetual license (License) to User to use
 *  this software under this Agreement. However, the User shall make no commercial use of the software without
 *  the Developer's written consent. Software Distribution is restricted and shall be done only with Developer's written approval.
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied. 
 * 
 * 1.3.0 - The app now appears under Integrations in the Hubitat sidebar instead of Apps (platform 2.5.0+;
 *         older firmware keeps it under Apps).
 *         Audit fixes: device-token refresh now updates the driver (was throwing on sensors) and no longer loops;
 *         access token refreshed before expiry and on HTTP 401; battery 0 reports 0% (was -1%) and a missing
 *         battery reading no longer reports 0%; scheduled reconciles no longer skipped by a few ms of drift;
 *         hub-offline errors handled on the setup pages; unsupported device types reported in diagnostics;
 *         tokens/Authorization headers no longer logged; client secret no longer copied into app or sensor state;
 *         token request body URL-encoded; LeakSensor3 uses the LeakSensor driver; no double poll on app save;
 *         debug logging turns itself off after 30 minutes. Re-review: the current access token is kept until
 *         a new one arrives, automatic token refreshes are limited to one per minute, and polls are skipped
 *         when no token is available; Diagnostics changes no devices when the hub's device list could not
 *         be read. After updating, open the app and click Done.
 * 1.2.0 - Support for Motion Devices
 * 1.1.1 - Moved Repo for better Forking and updated Links
 * 1.1.0 - Initial Working Version
*/
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.net.URLEncoder
import groovy.transform.Field

private def get_APP_VERSION() {return "1.3.0"}
private def copyright() {return "<br>© 2025-" + new Date().format("yyyy") + " Albert Mulder. All rights reserved."}
private def get_APP_NAME() {return "YoLink Device Local Service"}


definition(
    name: "YoLink Device Local Service",
    namespace: "almulder",
    author: "Albert Mulder",
    description: "Connects your YoLink devices to Hubitat.",
    oauth: false,    
    category: "Integrations",
    menu: "Integrations",    // places the app under the sidebar's Integrations section (platform 2.5.0+; older hubs keep it under Apps)
    singleInstance: true,
    iconUrl: "${getImagePath()}yolink.png",
    iconX2Url: "${getImagePath()}yolink.png",
	importUrl: "https://raw.githubusercontent.com/almulder/Hubitat-Drivers/main/Yolink%20Local%20Hub/App/YoLink%20Device%20Local%20Service.groovy"
)

preferences {
    page(name: "about", title: "About", nextPage: "credentials") 
    page(name: "credentials", title: "YoLink App User Access Credentials", content:"credentials", nextPage:"otherSettings")
    page(name: "otherSettings", title: "Other Settings", content:"otherSettings", nextPage: "deviceList")
    page(name: "deviceList",title: "YoLink Devices", content:"deviceList",nextPage: "diagnostics")  
    page(name: "diagnostics", title: "YoLink  Device Service and Driver Diagnostics", content:"diagnostics", nextPage: "finish")
    page(name: "finish", title: "Installation Complete", content:"finish", uninstall: false)
}

@Field static diagFile = "YoLink_Local_Service_Diagnostics.txt"
@Field static errFile = "YoLink_Local_Service_Errors.txt"
@Field static String diagsep = "-----------------------------------------------------------------------------------------------------------------------------------------------"
@Field static final List SUPPORTED_TYPES = ["DoorSensor", "LeakSensor", "MotionSensor", "PowerFailureAlarm", "THSensor"]
String diagData
String errData 

def about() {
    dynamicPage(name: "about", title: pageTitle("About"), uninstall: true) {
        section("") {
            paragraph "<img src='${getImagePath()}yolink.png' alt='YoLink' style='max-width: 15%; height: auto;'/>"
            paragraph boldTitle("${get_APP_NAME()} - Version ${get_APP_VERSION()}")
            paragraph boldTitle("This app connects your YoLink devices to Hubitat via MQTT & the YoLink Local Hub.")  
            paragraph blueTitle("The app is neither developed, endorsed, or associated with YoLink or YoSmart, Inc." + 
            "</br>Provided 'AS IS', without warranties or conditions of any kind, either expressed or implied.") 
            paragraph boldTitle ("")
            paragraph boldTitle ("")               
            paragraph ""                
            paragraph "     I want to give a huge thank you to <b>Steven Barcus</b> for his incredible work on the Cloud version. This app and these drivers simply wouldn’t exist without the foundation he built. In fact, <b>Steven Barcus</b> is the reason I first became involved in the Yolink ecosystem."
 			paragraph ""    
			paragraph "     I created these drivers and the app for the local hub out of necessity—since no one else was developing local API drivers for the new hub. With the help of AI, I was able to bring them to life, but it all started with the groundwork that <b>Steven Barcus</b> had already laid."
            paragraph boldTitle (copyright())  
            paragraph ""     
            paragraph boldRedTitle ("WARNING: Removing this application will delete all the Yolink devices in Hubitat that were created by this app!")
        }
        section("") {          
            input "debugging", "enum", title:boldTitle("Enable Debugging"), required: true, options:["True","False"],defaultValue: "False"  
        }  
    }        
}

def credentials() {
    state.authError = null
    dynamicPage(name: "credentials", title: pageTitle("YoLink Access Credentials"), uninstall: false) {
        section("") {
            paragraph "<b>This app assumes you have your YoLink Local Hub set up and running with devices connected to it, along with the Local API running. If you need help with this, please refer back to YoLink for directions.</b>"
          }    
        section(sectionTitle("User Access Credentials from the YoLink mobile app")) {
            input "localHubIP", "string", title: boldTitle("Local Hub IP Address:"), required: true
            input "subnetId", "string", title: boldTitle("Net ID:"), required: true
            input "Client_ID", "text", title: boldTitle("Client ID:"), required: true    
            input "Client_Secret", "text", title: boldTitle("Client Secret:"), required: true

        }
        section("") {
            input(name: "showInstructions", type: "bool", title: "<b>Show Guide: Obtaining Your User Credentials</b>", required: false, defaultValue: false, submitOnChange: true)
            if (settings.showInstructions) {
                paragraph ""                         
                paragraph "<u><b style='font-size: 24px;'>Obtaining Your User Credentials</b></u>"
                paragraph ""
                paragraph "<b>◉</b>  Please open your YoLink mobile app, navigate to the <b>YoLink Local Hub (3)</b>. Note: (1) Shows device is online. (2) Shows device is linked to Local Hub."
                paragraph "<img src='${getImagePath()}devices.png' alt='YoLink devices' style='max-width: 50%; height: auto; border: 2px solid gray; box-shadow: 4px 4px 6px rgba(0,0,0,0.5);'/>"
                paragraph ""
                paragraph ""
                paragraph ""
                paragraph "<b>◉</b>  Click on the 3 dots (1) in the top right corner."
                paragraph "<img src='${getImagePath()}hub.png' alt='YoLink devices' style='max-width: 50%; height: auto; border: 2px solid gray; box-shadow: 4px 4px 6px rgba(0,0,0,0.5);'/>"
                paragraph ""
                paragraph ""
                paragraph ""
                paragraph "<b>◉  Locate your IP address:</b> (Hard wired preferred), but you can use either one; just be sure you reserve the IP address in your router as this requires a static IP. (Refer to router manual for help with reserving ip address)"
                paragraph "<img src='${getImagePath()}details.png' alt='YoLink devices' style='max-width: 50%; height: auto; border: 2px solid gray; box-shadow: 4px 4px 6px rgba(0,0,0,0.5);'/>"
                paragraph ""
                paragraph ""
                paragraph ""
                paragraph "<b>◉</b>  Go back to the hub page and click on <b>Local Network (2)</b>."
                paragraph "<img src='${getImagePath()}hub.png' alt='YoLink devices' style='max-width: 50%; height: auto; border: 2px solid gray; box-shadow: 4px 4px 6px rgba(0,0,0,0.5);'/>"
                paragraph ""
                paragraph ""
                paragraph ""
                paragraph "<b>◉  Locate your Net ID (1)</b>"
                paragraph "<img src='${getImagePath()}local_network.png' alt='YoLink devices' style='max-width: 50%; height: auto; border: 2px solid gray; box-shadow: 4px 4px 6px rgba(0,0,0,0.5);'/>"
                paragraph ""
                paragraph ""
                paragraph ""
                paragraph "<b>◉  Client ID & Client Secret:</b> Go to the Integrations tab and locate the Client ID and Client Secret."
                paragraph "<img src='${getImagePath()}local_api.png' alt='YoLink devices' style='max-width: 50%; height: auto; border: 2px solid gray; box-shadow: 4px 4px 6px rgba(0,0,0,0.5);'/>"
                paragraph ""
                paragraph ""
                paragraph ""
		        paragraph "<span style='color:red;'>** If you have any errors, double-check the credentials; they must match perfectly.</span>"
            }
        }
    } 
}


def otherSettings() {    
    dynamicPage(name: "otherSettings", title: pageTitle("Settings"), uninstall: false) {
        section("<b><u>Naming Options</u></b>") {
            input "yolinkName", "bool", title: boldTitle("Add 'YoLink' prefix?"), defaultValue: true, submitOnChange: true
            input "includeType", "bool", title: boldTitle("Include device type in name?"), defaultValue: false, submitOnChange: true
            // Example rendering
            def prefix = yolinkName ? "YoLink - " : ""
            def suffix = includeType ? " - THSensor" : ""
            def exampleName = "${prefix}Outdoor Temp${suffix}"
			paragraph ""
            paragraph "Example: ${exampleName}"
            paragraph ""
            paragraph ""
        }
        section("<b><u>Other Options</u></b>") {
            input "temperatureScale", "enum", title:boldTitle("Temperature Scale"),
                  required: true, options:["C","F"], defaultValue: "F"

          // New: reconcile cadence (replaces poll interval)
            input name: "reconcileCadence",
                  type: "enum",
                  title: boldTitle("Periodic reconcile (HTTP getState)"),
                  options: ["Off", "Hourly", "Every 6 hours", "Every 12 hours", "Daily"],
                  defaultValue: "Every 6 hours",
                  required: true,
                  submitOnChange: true

            input name: "reconcileStepSeconds",
                type: "number",
                title: boldTitle("Reconcile per-device delay (seconds)"),
                description: "Lower is faster. Minimum 1 second enforced to protect local hub API.",
                defaultValue: 2,
                required: true,
                range: "1..30",
                submitOnChange: true

            input "dateTimeFormat", "enum",
                  title: boldTitle("Date/Time format for events"),
                  required: true,
                  options: dateFormatOptions(),
                  defaultValue: "MM/dd/yyyy hh:mm:ss a",
                  submitOnChange: true
        }
    }
}


private Map dateFormatOptions() {
    def tz  = location?.timeZone ?: TimeZone.getTimeZone('UTC')
    Date now = new Date()

    // pattern : human label
    Map<String,String> formats = [
        "MM/dd/yyyy hh:mm:ss a": "USA (12-hour)",
        "MM/dd/yyyy HH:mm:ss" : "USA (24-hour)",
        "dd/MM/yyyy HH:mm:ss" : "UK/Europe",
        "yyyy-MM-dd HH:mm:ss" : "ISO-like",
        "yyyy/MM/dd hh:mm:ss a": "Year-first (12-hour)",  // <-- hh + a (fixed)
        "yyyy/MM/dd HH:mm:ss" : "Year-first (24-hour)",
        "dd-MMM-yyyy HH:mm:ss": "Day-Mon-Year"
    ]

    def opts = [:]
    formats.each { fmt, label ->
        opts[fmt] = "${label} — ${now.format(fmt, tz)} (${tz?.ID})"
    }
    return opts
}


def deviceList() {
    if (!refreshAuthToken()) {
        dynamicPage(name: "deviceList", title: pageTitle("Error obtaining access token"), nextPage:"credentials", uninstall: false) {
            section(boldTitle("The YoLink Local Hub returned the following error:")) {
                paragraph "${state.token_error}"
            }    
            section("") {
                paragraph "Click Next to re-enter your Local Hub IP and credentials."
            }
        }
    } else {
        def devices = getDevices()
        int devicesCount = devices?.size() ?: 0
        log.info "$devicesCount YoLink devices were found"

        dynamicPage(name: "deviceList", title: sectionTitle("$devicesCount YoLink devices found. Select the devices you want available to Hubitat"), uninstall: false) {
            if (state.hubError) {
                section("") {
                    paragraph boldRedTitle("Could not read the device list from the Local Hub: ${state.hubError}")
                    paragraph "Clicking 'Next' will not change your existing devices until the hub can be reached."
                }
            }
            section("") {
                paragraph "Click below to see the list of devices available from your Local Hub"
                input(name: "exposed", title:"", type: "enum", description: "Click to choose", options: devices, multiple: true, submitOnChange: true)
                paragraph "Drivers published for: ${SUPPORTED_TYPES.join(', ')}. Other types are listed, but need a matching 'YoLink &lt;type&gt; Device Local' driver installed; any that fail are reported on the next page."
                paragraph "Note: Clicking 'Next' will create the selected devices and/or delete the deselected devices."
            }
        }
    }
}

def diagnostics() {   
    // --- Clear previous error file (use a Map body, not a JSON string) ---
    try {
        def payload = [name: errFile, type: "file"]
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/hub/fileManager/delete",
            requestContentType: "application/json",
            body: payload,
            timeout: 10
        ]) { resp -> /* ignore */ }
    } catch (e) {
        logDebug { "Delete ${errFile} failed (ok if first run): ${e?.message}" }
    }

    def errData = ""
    state.errors = ""
    state.createErrors = []

    List keepDnis = []
    int countNewChildDevices = 0
    int devicesCount = exposed ? exposed.size() : 0

    log.info "$devicesCount devices were selected"

    // Refresh the device catalog if it is missing (e.g. after a hub reboot)
    if (!(state.deviceType instanceof Map) || state.deviceType.isEmpty()) {
        log.info "Device list missing, fetching devices from Local API..."
        getDevices()
    }

    // Without a fresh device list from the hub (the Devices page read failed, or the catalog is empty)
    // we cannot tell selected from unselected devices, so change nothing — whatever is selected
    if (state.hubError || !(state.deviceType instanceof Map) || state.deviceType.isEmpty()) {
        String msg = "The Local Hub device list could not be read (${state.hubError ?: 'no devices returned'}). No devices were created or deleted."
        log.error msg
        state.errors = msg
        return dynamicPage(name: "diagnostics", title: pageTitle("YoLink Diagnostics"), uninstall: false, nextPage: "finish") {
            section("") { paragraph boldRedTitle(msg) }
        }
    }

    if (devicesCount > 0) {
        exposed.each { dni ->
            def devname  = state.deviceName."${dni}"
            def devtype  = state.deviceType."${dni}"
            def devtoken = state.deviceToken."${dni}"
            def devId    = state.deviceId."${dni}"
            if (!devtype || !devId) {
                state.createErrors = (state.createErrors ?: []) + "Selected device ${dni} is no longer reported by the Local Hub; skipped."
                if (getChildDevice(dni.toString())) keepDnis << dni.toString()   // keep the existing device rather than deleting it
                return
            }
            def Hubitat_dni = "yolink_${devtype}_${dni}"

            Hubitat_dni = create_yolink_device(Hubitat_dni, devname, devtype, devtoken, devId)
            if (Hubitat_dni != null) {
                keepDnis << Hubitat_dni.toString()
                countNewChildDevices++
                logDebug { "Created $countNewChildDevices of ${exposed.size()} selected devices." }
            }
        }

        // Always create MQTT Listener device
        def mqttDni       = "yolink_MQTT Listener_${settings.Client_ID}MQTT1"
        def listenerDevId = "${settings.Client_ID}MQTT1"
        def listener = create_yolink_device(mqttDni, "YoLink Listener", "MQTT Listener", "${settings.Client_ID}MQTT1", listenerDevId)
        if (listener != null) keepDnis << listener.toString()
    }

    // Remove unselected devices (exact DNI match)
    getChildDevices().each {
        if (!keepDnis.contains(it.deviceNetworkId?.toString())) {
            log.debug "Device ${it} (${it.deviceNetworkId}) is no longer selected. Deleting."
            deleteChildDevice(it.deviceNetworkId)
        }
    }

    if (state.createErrors) {
        state.createErrors.each { errData = appendData(errData, it) }
    }

    // Write error data if any
    if (errData) {
        errData = appendData(errData, diagsep)
        log.error "Installation errors written to ${errFile}."
        writeFile(errFile, errData)
    }
    state.errors = errData

    // --- Return a page (no inputs) so preferences content:'diagnostics' is satisfied ---
    return dynamicPage(name: "diagnostics",
                       title: pageTitle("YoLink Diagnostics"),
                       uninstall: false,
                       nextPage: "finish") {
        section("") {
            paragraph "Processed ${devicesCount} selected device(s)."

            def ys6604Selections = (exposed ?: []).findAll { dni ->
                def model = state?.modelName?."${dni}"?.toString()?.toUpperCase()
                return model?.startsWith("YS6604")
            }
            if (ys6604Selections && !ys6604Selections.isEmpty()) {
                paragraph boldTitle("YS6604 driver probe results:")
                ys6604Selections.each { dni ->
                    def devLabel = state?.deviceName?."${dni}" ?: dni
                    def model = state?.modelName?."${dni}" ?: "YS6604"
                    def chosen = state?.outletFamilyChoice?."${dni}" ?: "Outlet (default)"
                    paragraph "${devLabel} (${model}) → ${chosen}"
                }
            }

            if (state.errors) {
                paragraph boldRedTitle("Some errors occurred. See ${errFile}.")
            } else {
                paragraph "No installation errors recorded."
            }
        }
    }
}


def finish() {
    try {
        def body = JsonOutput.toJson(name:diagFile,type:"file")
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/hub/fileManager/delete",
            contentType:"text/plain",
            requestContentType:"application/json",
            body: body,
            timeout: 10
        ]) { resp -> /* ignore */ }
    } catch (e) {
        logDebug { "Delete ${diagFile} failed (ok if first run): ${e?.message}" }
    }

        diagData = ""
        def date = new Date(now()).format("MM/dd/yyyy hh:mm:ss a")
        diagData = appendData(diagData,"YoLink Device Service Diagnostics - Collected $date")
        diagData = appendData(diagData, "App Version " + get_APP_VERSION())
        // Use the device list already fetched on the Devices page rather than hitting the hub again
        int deviceCount = (state.deviceName instanceof Map) ? state.deviceName.size() : 0
        diagData = appendData(diagData, "YoLink Hub (Subnet ID: ${settings.subnetId}) reported $deviceCount devices:")
        // populate and write file
        writeFile(diagFile, diagData)

    dynamicPage(name: "finish", title: pageTitle("Processing Complete"), install: true) {
        if (state.errors) {
            section(boldRedTitle("The installation returned the following error(s):")) {
                paragraph "${state.errors}"
            }  
        } 
        section("") { paragraph "Click 'Done' to exit." }       
    }
}

// lifecycle handlers
def installed() {
    log.info "${get_APP_NAME()} app installed."
    subscribe(location, "systemStart", systemStart)
    runIn(5, initialize)
}

def updated() {
    log.info "${get_APP_NAME()} app updated."
    unsubscribe()
    subscribe(location, "systemStart", systemStart)
    runIn(5, initialize)
}

void systemStart(evt) {
    log.info "${get_APP_NAME()} starting up."
    runIn(5, initialize)
}

def initialize() {
    unschedule()
    state.reconcileInProgress = false
    state.reconcileQueue = []
    state.unknownWarned = [:]   // reset the “warned” throttle map
    state.remove("Client_Secret")   // no longer kept in app state (read from settings when needed)

    // Scheduled after unschedule() above so it is not cancelled
    if (settings?.debugging == "True") runIn(1800, "debugOff")

    scheduleReconcile()
    runIn(3, "runImmediateReconcile")
}

void debugOff() {
    log.warn "${get_APP_NAME()}: debug logging disabled"
    app.updateSetting("debugging", [value: "False", type: "enum"])
}

   

def refresh() {
    runImmediateReconcile()
}     

def runImmediateReconcile() {
    reconcileJob(true)
}

def uninstalled() {    
    unschedule()
    log.debug "Uninstalling ${get_APP_NAME()} app"
    delete_child_devices(getChildDevices())   
}

// === Child Accessor: drivers call this to get the global date/time format ===
String getDateTimeFormat() {
    return settings?.dateTimeFormat ?: "MM/dd/yyyy hh:mm:ss a"
}

// === Shared timestamp formatter — delegates from all child drivers ===
String fmtTs(def ts) {
    String f = getDateTimeFormat()
    try {
        TimeZone tz = location?.timeZone ?: TimeZone.getDefault()
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
        return d ? d.format(f, tz) : (ts?.toString())
    } catch (e) {
        return ts?.toString()
    }
}

String getDeviceTypeFor(String devId) {
    return state?.deviceType?."${devId}"?.toString()
}

String getDeviceTokenFor(String devId) {
    return state?.deviceToken?."${devId}"?.toString()
}

String refreshDeviceTokenFor(String devId) {
    if (!devId) return null
    try {
        getDeviceToken(devId)
    } catch (Exception e) {
        log.error "refreshDeviceTokenFor(${devId}) failed: ${e}"
    }
    return state?.deviceToken?."${devId}"?.toString()
}

// === Child Accessor: MQTT Listener pulls broker credentials here instead of keeping the secret in device state ===
Map getMqttCredentials() {
    return [clientId: settings?.Client_ID?.trim(), clientSecret: settings?.Client_Secret?.trim(),
            subnetId: settings?.subnetId?.trim(), localHubIP: settings?.localHubIP?.trim()]
}

// Use the YoLink deviceId as the child DNI (required for MQTT routing).
// Also migrates any legacy child whose DNI != deviceId by deleting/recreating it.
private create_yolink_device(Hubitat_dni, devname, devtype, devtoken, devId) {
    // --- driver name resolution ---
    def drivername = devtype
    def modelName = state?.modelName?."${devId}"?.toString()
        if (devtype == "COSmokeSensor") drivername = "COSmokeSensor"
    if (devtype == "Dimmer") drivername = "Dimmer"
    if (devtype == "DoorSensor") drivername = "DoorSensor"
    if (devtype == "Finger") drivername = "Finger"
    if (devtype == "GarageDoor") drivername = "GarageDoor"
    if (devtype == "LeakSensor") drivername = "LeakSensor"
    if (devtype == "LeakSensor3") drivername = "LeakSensor"   // LeakSensor driver handles both (supportChangeMode)
    if (devtype == "Lock") drivername = "Lock"
    if (devtype == "LockV2") drivername = "Lock"
    if (devtype == "Manipulator") drivername = "Manipulator"
    if (devtype == "MotionSensor") drivername = "MotionSensor"
    if (devtype == "MultiOutlet") drivername = "MultiOutlet"
    if (devtype == "Outlet") drivername = "Outlet"
    if (devtype == "PowerFailureAlarm") drivername = "PowerFailureAlarm"
    if (devtype == "Siren") drivername = "Siren"
    if (devtype == "SmartOutdoorPlug") drivername = "SmartOutdoorPlug"
    if (devtype == "SmartRemoter") drivername = "SmartRemoter"
    if (devtype == "Sprinkler") drivername = "Sprinkler"
    if (devtype == "Switch") drivername = "Switch"
    if (devtype == "THSensor") drivername = "THSensor"
    if (devtype == "TemperatureSensor") drivername = "TemperatureSensor"
    if (devtype == "Thermostat") drivername = "Thermostat"
    if (devtype == "VibrationSensor") drivername = "VibrationSensor"
    if (devtype == "WaterDepthSensor") drivername = "WaterDepthSensor"
    if (devtype == "WaterMeterController") drivername = "WaterMeterController"

    // Relay family can vary by hub/firmware: MultiOutlet / SmartOutdoorPlug / Outlet / Switch.
    if (["MultiOutlet", "SmartOutdoorPlug", "Outlet", "Switch"].contains(devtype) ||
        modelName?.toUpperCase()?.startsWith("YS6604") ||
        isPowerMonitoringModel(modelName)) {
        drivername = getRelayFamilyDriver(devname, devtype, devtoken, devId, modelName)
        if (isPowerMonitoringModel(modelName) && ["Outlet", "Switch"].contains(drivername)) {
            drivername = "Outlet Power Monitor"
        }
        log.info "${devname} (${modelName ?: devtype}) mapped to ${drivername} driver"
    }

drivername = getYoLinkDriverName(drivername)
    if (!drivername.endsWith("Local")) drivername += " Local"

    // desired child DNI is the raw YoLink deviceId
    String desiredDni = devId

    // Try to find an existing child with the desired DNI
    def dev = getChildDevice(desiredDni)

    // If not found, look for a legacy child using the old pattern(s) and migrate
    if (!dev) {
        def legacy = getChildDevice(Hubitat_dni) ?:
                     allChildDevices?.find { it?.deviceNetworkId?.endsWith("_${devId}") }
        if (legacy) {
            log.debug "Found legacy child DNI '${legacy.deviceNetworkId}' for deviceId ${devId}; deleting and recreating with DNI='${desiredDni}' so MQTT can route."
            try {
                deleteChildDevice(legacy.deviceNetworkId)
            } catch (Exception e) {
                log.error "Failed to delete legacy child ${legacy.displayName}: ${e}"
                return null
            }
        }
    }

    // Label rules
    def prefix = (settings.yolinkName) ? "YoLink - " : ""
    def suffix = (settings.includeType) ? " - ${devtype}" : ""
    def labelName = (devtype == "MQTT Listener") ? "YoLink MQTT Listener" : "${prefix}${devname}${suffix}"

    // Create child if needed
    if (!dev) {
        try {
            log.info "Creating new YoLink child device: ${labelName}"
            dev = addChildDevice("almulder", drivername, desiredDni, null, [label: labelName, isComponent: false])
        } catch (Exception e) {
            log.error "Error adding device '${labelName}': ${e}"
            String why = (e?.toString()?.contains("UnknownDeviceType")) ?
                "driver '${drivername}' is not installed (device type ${devtype} may not be supported yet)" : "${e}"
            state.createErrors = (state.createErrors ?: []) + "Could not create '${labelName}': ${why}"
            return null
        }
    }

    // Initialize/update child. Only the MQTT Listener needs broker credentials, and it reads
    // them from the app (getMqttCredentials) rather than keeping them in device state.
    try {
        dev.ServiceSetup(
            desiredDni,
            settings.subnetId,
            devname,
            devtype,
            devtoken,
            devId,
            settings.localHubIP,
            null,
            null
        )
        try { dev.temperatureScale(settings.temperatureScale) } catch (ignored) {}
        // Do NOT push date format; drivers pull via parent.getDateTimeFormat()
    } catch (Exception e) {
        log.error "Error during ServiceSetup for ${dev.displayName}: ${e}"
    }

    if (dev && devtype == "MQTT Listener") dev.initialize()

    return desiredDni
}

// Returns the device's current token (or null), and pushes it into the app's cache and the child driver.
def getDeviceToken(dni, boolean pushToChild = true) {
    def body = [:]
    body.put("method", "Home.getDeviceList")

    log.info "Polling Local API to retrieve device tokens..."

    try {
        def object = pollAPI(body,"YoLink Device Local Service","App")

        if (object?.code == "000000" && object?.data?.devices != null) {
            def responseValues = (object.data.devices instanceof Collection) ? object.data.devices : [object.data.devices]
            logDebug { "Parsing ${responseValues.size()} device(s) for token of ${dni}" }

            for (def device : responseValues) {
                if (device?.deviceId == dni) {
                    logDebug { "Located ${device.name}" }
                    Map tokens = (state.deviceToken instanceof Map) ? state.deviceToken : [:]
                    tokens[dni] = device.token
                    state.deviceToken = tokens
                    // Skipped when called from pollAPI: that runs inside the child's own poll, and the
                    // child picks the new token up via getDeviceTokenFor() when pollAPI returns.
                    def child = pushToChild ? findChild(dni) : null
                    if (child) {
                        try {
                            child.setDeviceToken(device.token)
                        } catch (Exception e) {
                            log.warn "getDeviceToken: could not update token on ${child.displayName}: ${e}"
                        }
                    }
                    return device.token
                }
            }
            log.warn "getDeviceToken: device ${dni} was not found in the Local Hub device list"
        } else {
            log.error "getDeviceToken failed (code=${object?.code})"
        }
    } catch (Exception e) {
        log.error "Error executing getDeviceToken, Exception: $e"
    }
    return null
}


def pollDevices() {
    def kids = getChildDevices()
    if (!kids || kids.isEmpty()) {
        logDebug { "Reconcile: no child devices found." }
        return
    }
    if (state.reconcileInProgress) {
        logDebug { "Reconcile already in progress; skipping new start." }
        return
    }
    // Build a queue of DNIs to process
    state.reconcileQueue = kids*.deviceNetworkId
    state.reconcileInProgress = true
    logDebug { "Reconcile: queued ${state.reconcileQueue.size()} device(s)." }
    runIn(1, "reconcileNextDevice")
}

def reconcileNextDevice() {
    // Guard: reject stale or duplicate runIn callbacks that arrive after reconcile is done/reset
    if (!state.reconcileInProgress) {
        logDebug { "Reconcile: reconcileNextDevice() called but reconcile is not in progress — ignoring." }
        return
    }

    List q = (state.reconcileQueue ?: []) as List
    if (!q || q.isEmpty()) {
        state.reconcileInProgress = false
        state.reconcileQueue = []
        logDebug { "Reconcile: finished all devices." }
        return
    }

    String dni = q.remove(0)
    state.reconcileQueue = q

    def dev = getChildDevice(dni)
    if (dev) {
        try {
            boolean invoked = false

            // Try the most specific call your drivers support. Only fall through to the next
            // method when the driver does not have this one; any other failure counts as "invoked"
            // so the device is not polled twice.
            invoked = tryReconcileCall(dev) { dev.pollDevice(0) }
            if (!invoked) invoked = tryReconcileCall(dev) { dev.poll(true) }
            if (!invoked) invoked = tryReconcileCall(dev) { dev.poll() }
            if (!invoked) invoked = tryReconcileCall(dev) { dev.refresh() }

            logDebug { "Reconcile: ${dev.displayName} -> ${invoked ? 'invoked' : 'no suitable method'}" }
        } catch (e) {
            log.error "reconcileNextDevice() exception for ${dev}: ${e}"
            // Clear flag so future reconcile runs are not permanently blocked
            state.reconcileInProgress = false
            state.reconcileQueue = []
            return
        }
    } else {
        log.warn "Reconcile: child with DNI ${dni} not found."
    }

    Integer stepSec = 2
    try { stepSec = Math.max(1, (settings?.reconcileStepSeconds ?: 2) as Integer) } catch (ignored) { stepSec = 2 }

    // Stagger the next device to avoid bursts (minimum 1s floor to protect local hub API)
    if (state.reconcileQueue && !state.reconcileQueue.isEmpty()) {
        runIn(stepSec, "reconcileNextDevice")
    } else {
        state.reconcileInProgress = false
        logDebug { "Reconcile: finished all devices." }
    }
}

// true = the method exists and was called (even if it then failed); false = driver has no such method
private boolean tryReconcileCall(dev, Closure call) {
    try {
        call()
        return true
    } catch (MissingMethodException ignored) {
        return false
    } catch (IllegalArgumentException ignored) {
        return false      // how Hubitat can report an unknown command on a device wrapper
    } catch (Exception e) {
        String m = e?.message ?: ""
        if (m.contains("No signature of method") || m.contains("not supported")) return false
        log.warn "Reconcile: ${dev?.displayName} poll failed: ${e}"
        return true
    }
}


def passMQTT(topic) {
    // topic.payload is a JSON string from the MQTT driver
    String payloadStr = topic?.payload
    if (!payloadStr) return null

    String devId = topic?.deviceId as String
    if (!devId) {
        try {
            def payload = new JsonSlurper().parseText(payloadStr)
            devId = payload?.deviceId as String
        } catch (e) {
            logDebug { "passMQTT: malformed JSON payload: ${e?.message}" }
            return null
        }
    }
    if (!devId) return null

    def dev = getChildDevice(devId)
    if (dev) {
        try {
            logDebug { "Passing MQTT to ${dev.displayName} (${dev.deviceNetworkId})" }
            if (dev.respondsTo('processStateData')) {
                dev.processStateData(payloadStr)   // preferred
            } else {
                dev.parse(topic)                   // fallback
            }
            return "${dev}"
        } catch (e) {
            log.error "passMQTT: exception delivering to ${dev.displayName}: ${e}"
            return null
        }
    }

    // No child found. Decide whether to log or ignore.
    boolean isExposed = (settings?.exposed instanceof Collection) && settings.exposed.contains(devId)
    if (!isExposed) {
        // The user did NOT add this device in the app — ignore quietly (debug only).
        logDebug { "Ignoring MQTT for unselected device ${devId}" }
        return null
    }

    // Selected in the app but child missing — warn, but rate-limit so we don’t spam.
    // Prune expired entries before writing to keep the map from growing unbounded.
    long nowMs = now()
    Map warned = (state.unknownWarned ?: [:]) as Map
    warned = warned.findAll { k, v -> (nowMs - ((v ?: 0L) as long)) < 60000 }
    long lastWarn = (warned[devId] ?: 0L) as long
    if (nowMs - lastWarn > 60000) {  // 1 minute throttle
        log.warn "MQTT received for selected device ${devId}, but child device not found. Did it get deleted? Recreate from the app."
        warned[devId] = nowMs
    } else {
        logDebug { "Suppressed repeat warning for missing selected device ${devId}" }
    }
    state.unknownWarned = warned
    return null
}


private def delete_child_devices(delete=null) {
    if (delete) {
        delete.each {
            log.warn "Deleting ${it.displayName}"
            deleteChildDevice(it.deviceNetworkId)        
        }            
        return        
    }           
}

def getYoLinkDriverName(devtype) {
    def driver = "YoLink ${devtype} Device Local" 
    log.info "Driver name for type ${devtype} is '${driver}'"
    return driver
}

private def apiURL() { return "http://${settings.localHubIP}:1080/open/yolink/v2/api"} 
private def mqqtURL() { return "tcp://${settings.localHubIP}:18080"} 
private def tokenURL() {return "http://${settings.localHubIP}:1080/open/yolink/token"}
private def getStandardImagePath() {return "http://cdn.device-icons.smartthings.com/"}
private int SUCCESS() {return 200}
private int UNAUTHORIZED() {return 401}
private def getImagePath() {return "https://raw.githubusercontent.com/almulder/Hubitat-Drivers/refs/heads/main/Yolink%20Local%20Hub/Pics/"}   

def AuthToken() {state.access_token}  

def refreshAuthToken() {   
    logDebug { "Refreshing access token (Local API)" }
    boolean rc = false

    String clientId     = settings?.Client_ID?.trim()
    String clientSecret = settings?.Client_Secret?.trim()
    state.Client_ID     = clientId
    state.remove("Client_Secret")   // never keep the secret in app state
    state.localHubIP    = settings?.localHubIP?.trim()

    // Keep the current token until a new one arrives, so a failed refresh doesn't strand callers
    state.lastTokenAttemptMs = now()

    if (!state.localHubIP || !clientId || !clientSecret) {
        state.token_error = "Missing Local Hub IP, Client ID, or Client Secret."
        log.error state.token_error
        return false
    }
    def url = "http://${state.localHubIP}:1080/open/yolink/token"
    def headers = ["Content-Type": "application/x-www-form-urlencoded"]
    def body = "grant_type=client_credentials&client_id=${URLEncoder.encode(clientId, 'UTF-8')}&client_secret=${URLEncoder.encode(clientSecret, 'UTF-8')}"

    logDebug { "Attempting to get local access token from ${url}" }

    try {
        httpPost([
            uri: url,
            headers: headers,
            body: body,
            requestContentType: "application/x-www-form-urlencoded",
            timeout: 10
        ]) { resp ->
            if (resp.status == 200 && resp.data?.access_token) {
                logDebug { "Local API Response: SUCCESS" }
                state.access_token     = resp.data.access_token
                state.access_token_ttl = resp.data?.expires_in
                Long ttl = null
                try { ttl = (resp.data?.expires_in as Long) } catch (ignored) {}
                if (ttl && ttl > 0) state.tokenExpiresAt = now() + (ttl * 1000L)
                logDebug { "New local access token received (expires in ${ttl ?: 'unknown'}s)" }
                state.remove("token_error")
                rc = true
            } else {
                state.token_error = "Local API token request failed (HTTP ${resp.status})"
                log.error state.token_error
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e?.statusCode == 401) {
            state.token_error = "Unauthorized (HTTP 401). Ensure Client ID/Client Secret are correct."
            log.warn state.token_error
        } else {
            state.token_error = "Error refreshing access token, Exception: ${e.message}"
            log.error state.token_error
        }
    } catch (Exception e) {
        state.token_error = "General Error: ${e.message}"
        log.error state.token_error
    }  
    
    logDebug { "refreshAuthToken() RC = ${rc}" }
    return rc
}

def findChild(dni) {
    return getChildDevice("${dni}") ?: allChildDevices.find { it.deviceNetworkId == "${dni}" }
}

// Refresh the access token shortly before it expires, or if we have none.
// Returns false when there is no usable token (caller should not send the request).
private boolean ensureAccessToken() {
    Long exp = state.tokenExpiresAt as Long
    if (!state.access_token || (exp && now() > exp - 60000L)) {
        logDebug { "Access token missing or about to expire; refreshing" }
        refreshAuthTokenThrottled()
    }
    return state.access_token != null
}

// Automatic refreshes are limited to one attempt per minute, so a hub that is down doesn't
// get a token request on every poll. The setup pages call refreshAuthToken() directly.
private boolean refreshAuthTokenThrottled() {
    Long last = state.lastTokenAttemptMs as Long
    if (last && now() - last < 60000L) {
        logDebug { "Token refresh skipped (last attempt ${Math.round((now() - last) / 1000)}s ago)" }
        return false
    }
    return refreshAuthToken()
}

String pageTitle (String txt) { return '<h2>'+txt+'</h2>'}
String sectionTitle(String txt) { return '<h3>'+txt+'</h3>'}
String blueTitle(String txt){ return '<span style="color:#0000ff">'+txt+'</span>'} 
String smallTitle(String txt){ return '<h3 style="color:#8c8c8c"><b>'+txt+'</b></h3>'} 
String boldTitle(String txt) { return '<b>'+txt+'</b>'}
String boldRedTitle(String txt) { return '<span style="color:#ff0000"><b>'+txt+'</b></span>'}

def getDevices() {
    state.remove("hubError")
    if (!settings.localHubIP || !state.access_token) {
        state.hubError = "Missing Local Hub IP or access token."
        log.error state.hubError
        return [:]
    }

    def body = [method: "Home.getDeviceList"]
    def url = "http://${settings.localHubIP}:1080/open/yolink/v2/api"
    def headers = [
        "Content-Type": "application/json",
        "Authorization": "Bearer ${state.access_token}"
    ]
    def jsonBody = groovy.json.JsonOutput.toJson(body)

    def respData = null
    try {
        httpPost([
            uri: url,
            headers: headers,
            body: jsonBody,
            requestContentType: "application/json",
            timeout: 10
        ]) { resp ->
            respData = resp?.data
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        state.hubError = "HTTP ${e.statusCode} ${e.message}"
        log.error "Error polling hub: ${state.hubError}"
        if (e?.statusCode == 401) log.warn "Unauthorized - check your access token"
        return [:]
    } catch (Exception e) {
        // Hub offline, timeout, connection refused, etc.
        state.hubError = "${e.message ?: e}"
        log.error "Error polling hub: ${state.hubError}"
        return [:]
    }

    if (!respData?.data?.devices) {
        state.hubError = "No devices returned from hub" + (respData?.code ? " (code ${respData.code})" : "")
        log.error state.hubError
        return [:]
    }

    state.deviceName  = [:]
    state.deviceType  = [:]
    state.deviceToken = [:]
    state.deviceId    = [:]
    state.modelName   = [:]

    def devices = respData.data.devices instanceof Collection ? respData.data.devices : [respData.data.devices]

    devices.each { device ->
        if (!device.deviceId) return

        def dni = device.deviceId
        state.deviceName[dni]  = device.name
        state.deviceType[dni]  = device.type
        state.deviceToken[dni] = device.token
        state.deviceId[dni]    = device.deviceId
        state.modelName[dni]   = device.modelName

        // Capture hub info for mirror driver
//        if (device.type in ["Hub", "CellularHub"]) {
//            state.hubInfo = [
//                devId   : device.deviceId,
//                name    : device.name,
//                type    : device.type,
//                model   : device.modelName,
//                firmware: device.version,
//                ip      : settings.localHubIP,
//                mac     : device.mac,
//                online  : device.online
//            ]
//            log.info "Captured hub info: ${state.hubInfo}"
//        }

        log.info "Found device: ${device.name} | Type: ${device.type} | Model: ${device.modelName}"
    }

    return state.deviceName
}

def pollAPI(body, name=null, type=null){
    def rc=null
    def retry=3
    boolean deviceTokenRefreshed = false
    boolean authRetried = false
    // Never log the body/params as-is: they carry the device token and the bearer token
    logDebug { "pollAPI(method=${body?.method}, target=${body?.targetDevice ?: '-'})" }

    if (!ensureAccessToken()) {
        log.warn "pollAPI(${body?.method}): no access token available (${state.token_error ?: 'token request failed'}); skipping request"
        return null
    }

    while ((rc == null) && (retry>0)) {
        def headers = ["Authorization": "Bearer ${state.access_token}"]
        def Params = [
            uri     : apiURL(),
            headers : headers,
            body    : body,
            timeout : 15
        ]

        try {
            httpPostJson(Params) { resp ->
                if (resp.data) {                    
                    // Home.getDeviceList responses carry every device token, so don't dump those
                    logDebug { (body?.method == "Home.getDeviceList") ? "API Response: code=${resp.data?.code}" : "API Response: ${resp.data}" }
                    def object = resp.data
                    def code = object.code                  
                    def desc = object.desc  
                    
                    if ((!desc) || (code==desc)) {
                        desc = translateCode(code)
                        logDebug { "Translated Response: ${desc}" }
                    }  
                    
                    switch (code) {
                        case "000000": 
                            logDebug { "Polling of Local API completed successfully" }
                            rc = object
                            break;
                        
                        case "020104":
                            log.warn "Device '${name}' busy — will retry on next poll cycle."
                            retry = -1
                            rc = object
                            break;    
                    
                        case "000201":
                        case "000203":
                             log.warn "Device '${name}' (Type=${type}) is offline"  
                             rc = object
                             break;
                        
                        case "000103":
                             // Refresh the device token once; give up (returning the error) if it is still invalid
                             def newToken = null
                             if (!deviceTokenRefreshed && body?.targetDevice) {
                                  deviceTokenRefreshed = true
                                  log.warn "${name}'s device token is invalid, refreshing..."
                                  newToken = getDeviceToken(body.targetDevice, false)
                             }
                             if (newToken) {
                                  body.token = newToken
                                  retry--
                             } else {
                                  log.error "${name}'s device token is invalid and could not be refreshed."
                                  rc = object
                                  retry = -1
                             }
                             break;
                        
                        case "010104":
                             if (retry>0) {
                                retry--
                                logDebug { 'Request token expired. Refreshing and retry...' }
                                if (!refreshAuthTokenThrottled()) { log.error "Request token expired and could not be refreshed."; rc = object; retry = -1 }
                             } else {          
                                log.error "Request token expired and retry failed."
                                retry = -1
                             }                          
                             break;    
                        
                        default:
                             log.error "Polling of Local API failed: $desc"
                             rc = object
                             break;
                   }
                } else {
                     log.error "Polling of Local API failed: No response returned"
                     retry = -1
                }
            }
        } catch (groovyx.net.http.HttpResponseException e) {
            if (e?.statusCode == UNAUTHORIZED() && !authRetried) {
                // Access token expired/revoked: refresh once and retry
                authRetried = true
                log.warn "Local API returned HTTP 401; refreshing access token and retrying..."
                if (!refreshAuthTokenThrottled()) retry = -1
            } else {
                log.error "pollAPI() HTTP error: ${e?.statusCode} ${e?.message}"
                retry = -1
            }
        } catch (Exception e) {
            log.error "pollAPI() Exception: $e"
            retry = -1
        }
    }
    return rc
}

@Field static final Map APIErrorCodes = [
    "000000": "Success",
    "000101": "Cannot connect to the Hub",
    "000102": "Hub cannot respond to this command",
    "000103": "Device token is invalid",
    "000104": "Hub token is invalid",
    "000106": "client_id is invalid",
    "000201": "Cannot connect to the device",
    "000202": "Device cannot respond to this command",
    "000203": "Cannot connect to the device",
    "010000": "Service is not available, try again later",
    "010001": "Internal connection is not available, try again later",
    "010101": "Invalid request: CSID is invalid",
    "010102": "Invalid request: SecKey is invalid",
    "010103": "Invalid request: Authorization is invalid",
    "010104": "Invalid request: The token is expired",
    "010200": "Invalid data packet: params is not valid",
    "010201": "Invalid data packet: time can not be null",
    "010202": "Invalid data packet: method can not be null",
    "010203": "Invalid data packet: method is not supported",
    "010204": "Invalid data packet",
    "010300": "This interface is restricted to access",
    "010301": "Access denied due to limits reached. Please retry later",
    "020100": "The device is already bound by another user",
    "020101": "The device does not exist",
    "020102": "Device mask error",
    "020103": "The device is not supported",
    "020104": "Device is busy, try again later",
    "020105": "Unable to retrieve device",
    "020201": "No devices were searched",
    "030101": "No data found",
    "999999": "Unknown Error, Please email to yaochi@yosmart.com"
]

def translateCode(code) {
    def translation = APIErrorCodes["$code"]
    if (translation) {
        return translation
    } else {
        log.error "${code} is an undefined Local API error code"
        return "${code} is an undefined Local API error code"
    }
}

@Field static final Map batteryLevels = [
    "0":0,
    "1":25,
    "2":50,
    "3":75,
    "4":100
]

// YoLink reports battery as a 0-4 level. Returns the percentage, or null when the level is
// missing or not recognised (callers then leave the battery attribute unchanged).
def batterylevel(level) {
    if (level == null || level.toString().trim() == "") return null
    Integer idx = null
    try { idx = (level.toString().trim() as BigDecimal).intValue() } catch (ignored) { return null }
    return batteryLevels["${idx}"]
}

def relayState(value) {    
    switch (value) {
       case "open":   return "on"
       case "closed": return "off"
       case "close":  return "off"
       default:       return value
    }
}
 
def convertTemperature(temperature, scale=null) {
    if (temperature == null) return null
    def temperatureScale = scale ?: temperatureScale()
    BigDecimal t
    try { t = temperature as BigDecimal } catch (e) {
        log.error "convertTemperature(): '${temperature}' is not a number"
        return null
    }

    if (temperatureScale == "C") return t
    if (temperatureScale != "F") log.warn "convertTemperature(): Temperature scale (${temperatureScale}) is invalid; using F"
    return celsiustofahrenheit(t).toDouble().round(1)
}

// Converts a temperature *difference* (e.g. a calibration offset): no +32 offset
def convertTemperatureDelta(delta, scale=null) {
    if (delta == null) return null
    def temperatureScale = scale ?: temperatureScale()
    BigDecimal d
    try { d = delta as BigDecimal } catch (e) { return null }
    if (temperatureScale == "C") return d
    return (d * 9 / 5).toDouble().round(1)
}

def temperatureScale() {
    return settings?.temperatureScale ?: "F"
}
    
def celsiustofahrenheit(celsius) {return ((celsius * 9 / 5) + 32)} 

def scheduledDays(weekdays) {
   def days    
    
   def daysB = Integer.toBinaryString(weekdays.toInteger())                   
   def ndx = daysB.length()    
    
   while (ndx >0) {
     def bit = daysB.substring(ndx-1,ndx)     
     
     if (bit == "1") {  
        days = commaConcat(days,scheduledDay(daysB.length()-ndx))
     }    
       
     ndx--  
   }    
    
   logDebug { "Scheduled Days: ${days}" }
   return days 
}

private NameToDNI(nameDNI) {
   log.info nameDNI 
   def DNI = nameDNI.substring(0,nameDNI.indexOf("="))  
   log.debug DNI      
   return DNI 
}

@Field static final Map weekdays = [
    "0":"Sun",
    "1":"Mon",
    "2":"Tue",
    "3":"Wed",
    "4":"Thu",
    "5":"Fri",
    "6":"Sat"
]

def scheduledDay(dayndx) {
    def weekday = weekdays["${dayndx}"]

    if (weekday) {
        return weekday
    } else {
        log.error" ${dayndx} is an invalid weekday index"
        return "???"
    }
}

private void scheduleReconcile() {
    String cad = settings?.reconcileCadence ?: "Every 6 hours"
    if (settings?.debugging == "True") log.debug "Scheduling reconcile: ${cad}"

    // Always clear any prior schedules for this job
    unschedule("reconcileJob")

    switch (cad) {
        case "Off":
            // no schedule
            break
        case "Hourly":
            runEvery1Hour("reconcileJob")
            break
        case "Every 6 hours":
            schedule("0 0 */6 * * ?", "reconcileJob")     // top of every 6th hour
            break
        case "Every 12 hours":
            schedule("0 0 */12 * * ?", "reconcileJob")
            break
        case "Daily":
            schedule("0 0 3 * * ?", "reconcileJob")       // 03:00 hub local time
            break
    }

    logDebug { "Reconcile cadence '${cad}' | next scheduled run: ${nextReconcileRunText(cad)}" }
}

private String nextReconcileRunText(String cad) {
    if (cad == "Off") return "disabled"

    def tz = location?.timeZone ?: TimeZone.getTimeZone('UTC')
    Calendar cal = Calendar.getInstance(tz)
    Date nowDate = new Date()
    cal.setTime(nowDate)

    switch (cad) {
        case "Hourly":
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.HOUR_OF_DAY, 1)
            break

        case "Every 6 hours":
        case "Every 12 hours":
            int step = (cad == "Every 6 hours") ? 6 : 12
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            int hoursToAdd = (step - (cal.get(Calendar.HOUR_OF_DAY) % step)) % step
            if (hoursToAdd == 0) hoursToAdd = step   // already on boundary — next occurrence is step hours away
            cal.add(Calendar.HOUR_OF_DAY, hoursToAdd)
            break

        case "Daily":
            cal.set(Calendar.HOUR_OF_DAY, 3)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (!cal.getTime().after(nowDate)) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
            break

        default:
            return "unknown"
    }

    return cal.getTime().format(getDateTimeFormat(), tz)
}

/** Periodic reconcile: call each child’s poll(true) (HTTP getState) with small staggering */
def reconcileJob(Boolean force = false) {
    if (!shouldRunReconcile(force)) return
    state.lastReconcileRunMs = now()
    if (settings?.debugging == "True") log.debug "Reconcile job: HTTP getState across children"
    pollDevices()   // uses the drop-in version below (no reschedule)
}

private boolean shouldRunReconcile(Boolean force = false) {
    if (force) return true

    String cad = settings?.reconcileCadence ?: "Every 6 hours"
    if (cad == "Off") {
        logDebug { "Reconcile skipped: cadence is Off" }
        return false
    }

    Integer minSeconds = cadenceToSeconds(cad)
    if (minSeconds <= 0) return true

    long lastMs = (state.lastReconcileRunMs ?: 0L) as long
    if (lastMs <= 0L) return true

    // The cron/runEvery job already sets the cadence; this guard only skips a scheduled run that lands
    // close behind another reconcile (e.g. one forced by an app save). Using half the cadence means a
    // few ms of scheduler drift can no longer make a run skip its slot.
    long elapsedMs = now() - lastMs
    long minMs = ((minSeconds as long) * 1000L).intdiv(2L)
    if (elapsedMs < minMs) {
        logDebug { "Reconcile skipped: elapsed ${Math.round(elapsedMs/1000)}s < cadence ${minSeconds}s (${cad})" }
        return false
    }
    return true
}

private Integer cadenceToSeconds(String cad) {
    switch (cad) {
        case "Hourly":
            return 3600
        case "Every 6 hours":
            return 21600
        case "Every 12 hours":
            return 43200
        case "Daily":
            return 86400
        case "Off":
            return Integer.MAX_VALUE
        default:
            return 21600
    }
}


def commaConcat(oldvalue,newvalue) {
    if (!oldvalue) {
      oldvalue = newvalue  
    } else {
      oldvalue = oldvalue + "," + newvalue    
    }    
    return oldvalue
}

boolean validBoolean(setting,value) {            // Allow any one of ON, OFF, TRUE, FALSE, YES, NO
   value = value ?: ""   
   value=value.toUpperCase()
   def rc = null
           
   if (("${value}" == "TRUE") || ("${value}" == "ON") || ("${value}" == "YES")) {
       rc = true 
   } else {     
       if (("${value}" == "FALSE") || ("${value}" == "OFF") || ("${value}" == "NO")) {
         rc = false
       } else {    
         log.error "${setting}(${value}) is invalid. Use 'FALSE', 'TRUE', 'OFF', 'ON', 'NO' or 'YES'"         
       }    
   } 
   return rc
}

def getTHSensorDriver(name, type, token, devId) {
    def driver = "THSensor"  // Always return THSensor driver name!
    try {
        def request = [
            method: "THSensor.getState",
            targetDevice: "${devId}",
            token: "${token}"
        ]
        def object = pollAPI(request, name, type)

        if (object) {
            logDebug { "getTHSensorDriver()> pollAPI() response: ${object}" }

            if (object.code == "000000") {
                def state = object.data?.state ?: [:]
                def humidity = state.humidity
                if (humidity == null) {
                    log.info "$name is a temperature-only sensor (but will use the THSensor driver)."
                } else {
                    log.info "$name is a temperature and humidity sensor."
                }
            } else {
                log.error "Local API polling returned error: $object.code - " + translateCode(object.code)
            }
        } else {
            log.error "No response from Local API request"
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e?.statusCode == UNAUTHORIZED()) {
            log.error("getTHSensorDriver() - Unauthorized Exception")
        } else {
            log.error("getTHSensorDriver() - Exception $e")
        }
    }
    return driver   // Always return "THSensor"!
}

// Devices YoLink LeakSensor (YS7903-UC) and YoLink LeakSensor3 (YS7904-UC) are both reported as "LeakSensor"
// If the device doesn't support 'supportChangeMode' then it's a YS7903-UC
def getLeakSensorDriver(name,type,token,devId) {
    def driver = "LeakSensor"
    try {  
        def request = [:]
        request.put("method", "LeakSensor.getState")                   
        request.put("targetDevice", "${devId}") 
        request.put("token", "${token}") 
        
        def object = pollAPI(request, name, type)
         
        if (object) {
            logDebug { "getLeakSensorDriver()> pollAPI() response: ${object}" }     
            
            if (object.code == "000000") {             
                def supportChangeMode = object.data.state.supportChangeMode                            
                                
                if (supportChangeMode == true) {  
                    log.info "$name appears to be a LeakSensor3 (YS7904-UC) sensor."
                    driver = "LeakSensor3"
                } else {
                    log.info "$name appears to be a LeakSensor (YS7903-UC) sensor."
                }    
            } else {  //Error
                log.error "Local API polling returned error: $object.code - " + translateCode(object.code)               
            }     
        } else {
            log.error "No response from Local API request"
        } 
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e?.statusCode == UNAUTHORIZED()) { 
            log.error("getLeakSensorDriver() - Unauthorized Exception")
        } else {
            log.error("getLeakSensorDriver() - Exception $e")
        }                 
    }
    return driver
}  

private Map probeRelayMethod(String method, name, type, token, devId) {
    def request = [method: method, targetDevice: "${devId}", token: "${token}"]
    def object = pollAPI(request, name, type)
    logDebug { "probeRelayMethod(${method}) -> ${object}" }
    return object
}

private String classifyRelayDriverFromProbe(String method, object, name) {
    if (method?.startsWith("MultiOutlet.")) {
        def delays = object?.data?.delays
        def delay2 = null
        if (delays instanceof List && delays.size() > 2) {
            delay2 = delays[2]
        } else if (delays instanceof Map) {
            delay2 = delays[2]
        }
        if (delay2 == null) {
            log.info "$name appears to be a Smart Outdoor Plug."
            return "SmartOutdoorPlug"
        }
        log.info "$name appears to be a MultiOutlet Device."
        return "MultiOutlet"
    }
    if (method?.startsWith("Switch.")) {
        log.info "$name appears to use Switch API namespace."
        return "Switch"
    }
    log.info "$name appears to use Outlet API namespace."
    return "Outlet"
}

private Boolean isPowerMonitoringModel(String modelName) {
    String model = (modelName ?: "").toString().toUpperCase()
    return model.startsWith("YS6614") || model.startsWith("YS6602")
}

// Generic relay-family probe: supports MultiOutlet, SmartOutdoorPlug, Outlet, and Switch.
def getRelayFamilyDriver(name, type, token, devId, modelName=null) {
    state.outletFamilyChoice = (state.outletFamilyChoice ?: [:])

    String t = (type ?: "").toString()
    String model = (modelName ?: "").toString().toUpperCase()

    List<String> probeOrder
    if (["MultiOutlet", "SmartOutdoorPlug"].contains(t) || model.startsWith("YS680")) {
        probeOrder = ["MultiOutlet.getState", "Outlet.getState", "Switch.getState"]
    } else if (t == "Switch") {
        probeOrder = ["Switch.getState", "Outlet.getState", "MultiOutlet.getState"]
    } else {
        probeOrder = ["Outlet.getState", "Switch.getState", "MultiOutlet.getState"]
    }

    String fallback = (["MultiOutlet", "SmartOutdoorPlug"].contains(t) || model.startsWith("YS680")) ? "MultiOutlet" : "Outlet"

    try {
        for (String method : probeOrder) {
            def obj = probeRelayMethod(method, name, type, token, devId)
            if (obj?.code == "000000") {
                String selected = classifyRelayDriverFromProbe(method, obj, name)
                state.outletFamilyChoice["${devId}"] = selected
                return selected
            }
            logDebug { "${name} did not respond to ${method} (code=${obj?.code})" }
        }

        log.warn "$name did not respond successfully to relay-family probes; defaulting to ${fallback}."
        state.outletFamilyChoice["${devId}"] = "${fallback} (default)"
    } catch (Exception e) {
        log.error("getRelayFamilyDriver() - Exception $e")
        state.outletFamilyChoice["${devId}"] = "${fallback} (default)"
    }
    return fallback
}

// Backward-compatible wrappers
def getMultiOutletDriver(name, type, token, devId) {
    return getRelayFamilyDriver(name, type, token, devId, null)
}

def getOutletFamilyDriver(name, type, token, devId) {
    return getRelayFamilyDriver(name, type, token, devId, null)
}

def logDebug(Closure msg) {
    if (debugging == "True") {
       log.debug msg()
    }
}    

def appendData(olddata, newdata) {
    if (olddata == null) {
       olddata = newdata.plus("\r\n")
    } else {
       olddata = olddata.plus(newdata).plus("\r\n") 
    } 
    return olddata
}

Boolean writeFile(String fName, String fData) {
    String boundary = "HubitatBoundary_${now()}"   // <-- sandbox-safe uniqueness
    String CRLF = "\r\n"

    ByteArrayOutputStream os = new ByteArrayOutputStream()
    // Part 1: the file
    os.write(("--${boundary}${CRLF}").getBytes("UTF-8"))
    os.write(("Content-Disposition: form-data; name=\"uploadFile\"; filename=\"${fName}\"${CRLF}").getBytes("UTF-8"))
    os.write(("Content-Type: text/plain${CRLF}${CRLF}").getBytes("UTF-8"))
    os.write(fData.getBytes("UTF-8"))

    // Part 2: the folder
    os.write(("${CRLF}--${boundary}${CRLF}").getBytes("UTF-8"))
    os.write(("Content-Disposition: form-data; name=\"folder\"${CRLF}${CRLF}/${CRLF}").getBytes("UTF-8"))

    // Closing boundary
    os.write(("--${boundary}--${CRLF}").getBytes("UTF-8"))
    byte[] bodyBytes = os.toByteArray()

    boolean ok = false
    try {
        def params = [
            uri: 'http://127.0.0.1:8080',
            path: '/hub/fileManager/upload',
            headers: ['Content-Type': "multipart/form-data; boundary=${boundary}"],
            requestContentType: "application/octet-stream",
            body: bodyBytes,
            timeout: 120
        ]
        httpPost(params) { resp ->
            ok = (resp?.data?.success == 'true')
        }
    } catch (e) {
        log.error "Error writing file ${fName}: ${e}"
    }
    return ok
}
