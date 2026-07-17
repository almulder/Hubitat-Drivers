/**
 *  InvisOutlet Device Service
 *
 *  THIS SOFTWARE IS NEITHER DEVELOPED, ENDORSED, OR ASSOCIATED WITH INTECULAR OR INVISOUTLET.
 *  Provided "AS IS", without warranties or conditions of any kind, either expressed or implied.
 *
 *  Connects InvisOutlet Aura/Pro smart outlets to Hubitat over MQTT. There is no published/
 *  proprietary API for these devices, but they publish standard Home Assistant MQTT Discovery
 *  messages to whatever broker they're configured to use (set in the InvisHome app under
 *  Outlet Settings > Customizations > Advanced > MQTT). This app speaks that protocol via a
 *  dedicated listener device it manages, and creates one real Hubitat device per physical
 *  outlet unit you select - not a child of the listener, a normal top-level device.
 * 1.1.0 - Initial Working Version
 */
import groovy.transform.Field

def appName() { "InvisOutlet Device Service" }
def appVersion() { "1.1.0" }
private def copyright() { return "<br>© 2026-" + new Date().format("yyyy") + " Albert Mulder. All rights reserved." }
private def getImagePath() { return "https://raw.githubusercontent.com/almulder/Hubitat-Drivers/refs/heads/main/Intecular/Pics/" }

// These aren't meaningfully user-configurable - InvisOutlet firmware always uses the
// standard Home Assistant MQTT Discovery prefix, and the filter just keeps the device
// list scoped to InvisOutlet units if the broker is shared with other MQTT devices.
@Field static final String DISCOVERY_PREFIX = "homeassistant"
@Field static final String DEVICE_FILTER = "invis"

definition(
    name: "InvisOutlet Device Service",
    namespace: "almulder",
    author: "Albert Mulder",
    description: "Connects your InvisOutlet Aura/Pro devices to Hubitat via MQTT.",
    category: "InvisOutlet",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    oauth: false
)

preferences {
    page(name: "about", title: "About", nextPage: "credentials")
    page(name: "credentials", title: "MQTT Broker Settings", nextPage: "otherSettings")
    page(name: "otherSettings", title: "Settings", nextPage: "deviceList")
    page(name: "deviceList", title: "InvisOutlet Devices", content: "deviceList", nextPage: "finish")
    page(name: "finish", title: "Installation Complete", content: "finish", install: true)
}

// ---------------- pages ----------------

def about() {
    dynamicPage(name: "about", title: pageTitle("About"), uninstall: true) {
        section("") {
            paragraph "<img src='${getImagePath()}intecular.png' alt='InvisOutlet' style='max-width: 15%; height: auto;'/>"
            paragraph boldTitle("${appName()} - Version ${appVersion()}")
            paragraph "This app connects your InvisOutlet Aura/Pro smart outlets to Hubitat over MQTT."
            paragraph blueTitle("This app is neither developed, endorsed, nor associated with Intecular or InvisOutlet." +
                "<br>Provided 'AS IS', without warranties or conditions of any kind, either expressed or implied.")
            paragraph ""
            paragraph boldRedTitle("WARNING: Removing this application will delete every InvisOutlet device it created, along with its MQTT listener device.")
            paragraph ""
            paragraph boldTitle(copyright())
        }
        section("") {
            input "debugging", "bool", title: boldTitle("Enable debug logging"), defaultValue: false
        }
    }
}

def credentials() {
    dynamicPage(name: "credentials", title: pageTitle("MQTT Broker Settings"), uninstall: false) {
        section(sectionTitle("Broker Connection")) {
            paragraph "Point this at the same MQTT broker your InvisOutlet devices are already configured to use."
            input "mqttBroker", "text", title: boldTitle("MQTT Broker URI (e.g. tcp://192.168.1.50:1883)"), required: true
            input "mqttUser", "text", title: boldTitle("Username (blank if none)"), required: false
            input "mqttPassword", "password", title: boldTitle("Password (blank if none)"), required: false
        }
        section("") {
            paragraph "<h3>If an MQTT broker is needed</h3>"
            paragraph "InvisOutlet devices need an MQTT broker to talk to Hubitat. If you don't already have one, pick whichever guide below fits your situation."
        }

        section("") {
            input(name: "showHubitatBrokerGuide", type: "bool", title: boldTitle("Show Guide: Enable and use Hubitat's built-in MQTT Broker"), defaultValue: false, submitOnChange: true)
            if (settings.showHubitatBrokerGuide) {
                paragraph ""
                paragraph "<u><b style='font-size:20px;'>Enabling Hubitat's Built-In MQTT Broker</b></u>"
                paragraph ""
                paragraph "As of recent Hubitat firmware, the hub itself can act as an MQTT broker - no extra hardware needed. This comes from Hubitat's own MQTT Export Integration."
                paragraph "<i style='font-size:0.85em;'>Per Hubitat's own documentation, this integration is currently in beta, and the built-in broker restarts whenever your hub reboots (which will briefly disconnect your InvisOutlet devices until it comes back up).</i>"
                paragraph ""
                paragraph "<b>1. Add the integration</b>"
                paragraph "From the hub's sidebar, go to <b>Integrations</b> > <b>Add Integration</b> (or <b>Add Built-In Integration</b>), then select <b>MQTT Export Integration</b> from the list."
                paragraph ""
                paragraph "<b>2. Enable it</b>"
                paragraph "Click the <b>Enable</b> link at the top of the MQTT Export Integration app page."
                paragraph ""
                paragraph "<b>3. Turn on the built-in broker</b>"
                paragraph "In the app's configuration, turn on <b>Use built-in MQTT service</b>. The integration will configure itself to use it automatically, and the broker becomes reachable on your network at the IP address and credentials Hubitat shows you there."
                paragraph ""
                paragraph "<b>4. Save</b>"
                paragraph "Select <b>Save app settings and reconnect</b> to apply everything."
                paragraph ""
                paragraph "<b>5. Point this app at it</b>"
                paragraph "Above, use Hubitat's hub IP address and the port/credentials shown in that integration's settings as your MQTT Broker URI."
                paragraph "You'll also need to point your InvisOutlet's own MQTT settings at this same broker (in the InvisHome app, under Outlet Settings > Customizations > Advanced > MQTT) - same IP, port, and credentials."
                paragraph ""
            }
        }

        section("") {
            input(name: "showPiGuide", type: "bool", title: boldTitle("Show Guide: Set up a Raspberry Pi as an MQTT Broker"), defaultValue: false, submitOnChange: true)
            if (settings.showPiGuide) {
                paragraph ""
                paragraph "<u><b style='font-size:20px;'>Setting Up a Raspberry Pi as an MQTT Broker</b></u>"
                paragraph ""
                paragraph "This takes a bare Pi and a blank SD card all the way through to a running Mosquitto MQTT broker. Skip Part 1 if you already have a Pi set up and reachable over SSH."
                paragraph "<i style='font-size:0.85em;'>Any Pi works for MQTT-only duty, even a Pi Zero W - Mosquitto itself is very lightweight. If using a Zero W, hardwire it (USB Ethernet adapter) rather than relying on its WiFi-only radio, and use a solid 5V/2A+ power supply - both reduce the odds of dropped connections or brownouts on a device you want running unattended 24/7. A Pi 3B+ or 4 costs a little more but includes real Ethernet and more forgiving power tolerances.</i>"
                paragraph ""
                paragraph "<h4>Part 1: Prepare the Pi</h4>"
                paragraph "<b>What you'll need</b>"
                paragraph "A Pi, a microSD card (8GB+ is plenty), a power supply, and another computer with an SD card slot (or a USB adapter) to flash the card."
                paragraph ""
                paragraph "<b>1. Download Raspberry Pi Imager</b>"
                paragraph "Get it from <code>raspberrypi.com/software</code> and install it on your main computer, not the Pi."
                paragraph ""
                paragraph "<b>2. Flash the SD card</b>"
                paragraph "Insert the SD card, open Imager, choose <b>Raspberry Pi OS Lite</b> (no desktop needed for a headless broker) as the OS, and select your SD card as the storage target."
                paragraph ""
                paragraph "<b>3. Pre-configure it before writing (important - avoids needing a monitor/keyboard)</b>"
                paragraph "In Imager, click <b>Edit Settings</b> (a gear/advanced icon) before hitting write. Set a hostname, enable SSH, set a username/password, and enter your WiFi network name/password if not using Ethernet. Then click Save and Write."
                paragraph ""
                paragraph "<b>4. Boot it up</b>"
                paragraph "Put the SD card in the Pi, connect Ethernet (recommended) or make sure the WiFi credentials were set in step 3, and power it on. Give it a minute or two for the first boot."
                paragraph ""
                paragraph "<b>5. Find it on your network</b>"
                paragraph "Try <code>ping &lt;hostname&gt;.local</code> using whatever hostname you set in step 3 (e.g. <code>ping mqttpi.local</code>). If that doesn't respond, check your router's connected-devices/DHCP client list for its IP address instead."
                paragraph ""
                paragraph "<b>6. Connect over SSH</b>"
                paragraph "<code>ssh yourusername@&lt;hostname&gt;.local</code>  (or use the IP address instead of the hostname)"
                paragraph ""
                paragraph "<b>7. Update the OS</b>"
                paragraph "<pre>sudo apt update && sudo apt full-upgrade -y\nsudo reboot</pre>"
                paragraph "Wait a minute for it to come back, then SSH in again the same way."
                paragraph ""
                paragraph "<b>8. Reserve its IP address in your router</b>"
                paragraph "Look for DHCP Reservation / Static Lease / Address Reservation in your router's settings, and bind the Pi's MAC address (<code>ip link show</code> on the Pi will show it) to a fixed IP, so it never changes later."
                paragraph ""
                paragraph "<h4>Part 2: Install Mosquitto</h4>"
                paragraph "<b>1. Update package lists</b>"
                paragraph "<code>sudo apt update</code>"
                paragraph ""
                paragraph "<b>2. Install Mosquitto and its command-line tools</b>"
                paragraph "<code>sudo apt install -y mosquitto mosquitto-clients</code>"
                paragraph ""
                paragraph "<b>3. Allow network connections</b>"
                paragraph "By default Mosquitto only listens on localhost, so nothing else on your network can reach it. Create a config file:"
                paragraph "<code>sudo nano /etc/mosquitto/conf.d/default.conf</code>"
                paragraph "Paste in the following, then save and exit (in nano: Ctrl+O, Enter, Ctrl+X):"
                paragraph "<pre>listener 1883\nallow_anonymous true</pre>"
                paragraph "<i>allow_anonymous true means no username/password is required to connect. That's generally fine for a broker only reachable on your own LAN behind your router's firewall - see step 7 if you'd rather require a login.</i>"
                paragraph ""
                paragraph "<b>4. Restart Mosquitto and enable it on boot</b>"
                paragraph "<pre>sudo systemctl restart mosquitto\nsudo systemctl enable mosquitto</pre>"
                paragraph ""
                paragraph "<b>5. Confirm it's running</b>"
                paragraph "<code>sudo systemctl status mosquitto</code>"
                paragraph "You should see <b>active (running)</b>."
                paragraph ""
                paragraph "<b>6. Find your Pi's IP address</b>"
                paragraph "<code>hostname -I</code>  (or use the IP you already reserved in Part 1, step 8)"
                paragraph ""
                paragraph "<b>7. (Optional but recommended) Require a username and password</b>"
                paragraph "<pre>sudo mosquitto_passwd -c /etc/mosquitto/passwd yourusername</pre>"
                paragraph "You'll be prompted for a password. Then edit the config file again:"
                paragraph "<code>sudo nano /etc/mosquitto/conf.d/default.conf</code>"
                paragraph "Change it to:"
                paragraph "<pre>listener 1883\nallow_anonymous false\npassword_file /etc/mosquitto/passwd</pre>"
                paragraph "Then restart Mosquitto again: <code>sudo systemctl restart mosquitto</code>"
                paragraph ""
                paragraph "<b>8. Test it</b>"
                paragraph "Open two terminal sessions to the Pi. In the first, subscribe to a test topic:"
                paragraph "<code>mosquitto_sub -h &lt;your-pi-ip&gt; -t test/topic -v</code>"
                paragraph "In the second, publish a message (add <code>-u username -P password</code> if you set up authentication in step 7):"
                paragraph "<code>mosquitto_pub -h &lt;your-pi-ip&gt; -t test/topic -m \"hello\"</code>"
                paragraph "You should see <b>test/topic hello</b> appear in the first terminal. If so, your broker is working."
                paragraph ""
                paragraph "<b>9. Point everything at this broker</b>"
                paragraph "Above, use <b>tcp://&lt;your-pi-ip&gt;:1883</b> as the MQTT Broker URI, plus the username/password from step 7 if you set them up."
                paragraph "You'll also need to point your InvisOutlet's own MQTT settings at this same broker (in the InvisHome app, under Outlet Settings > Customizations > Advanced > MQTT) - same IP, port, and credentials."
                paragraph ""
                paragraph "<span style='color:red;'>** If devices won't connect: double-check the Pi's IP hasn't changed, that port 1883 isn't blocked by a firewall on the Pi (<code>sudo ufw allow 1883</code> if ufw is enabled), and that credentials match exactly on both sides.</span>"
                paragraph ""
            }
        }
    }
}

def otherSettings() {
    dynamicPage(name: "otherSettings", title: pageTitle("Settings"), uninstall: false) {
        section("<b><u>Naming Options</u></b>") {
            input "namePrefix", "bool", title: boldTitle("Add 'InvisOutlet' prefix?"), defaultValue: true, submitOnChange: true
            input "includeType", "bool", title: boldTitle("Include device type in name?"), defaultValue: false, submitOnChange: true
            def prefix = settings.namePrefix ? "InvisOutlet - " : ""
            def suffix = settings.includeType ? " - Pro" : ""
            paragraph ""
            paragraph "Example: ${prefix}Living Room${suffix}"
            paragraph ""
        }
        section("<b><u>Units</u></b>") {
            input "temperatureScale", "enum", title: boldTitle("Temperature Scale"), required: true, options: ["C", "F"], defaultValue: "C"
            input "distanceUnit", "enum", title: boldTitle("Measurement Scale (Distance)"), required: true, options: ["cm", "in"], defaultValue: "cm"
        }
    }
}

def deviceList() {
    def listener = ensureListenerDevice()
    listener.initialize()
    // Retained discovery messages arrive almost immediately on subscribe (observed
    // well under 1 second in testing) - this pause gives it comfortable room, plus
    // a moment to see whether each device is actively publishing right now.
    pauseExecution(3000)

    def devices = listener.getDiscoveredDevices() ?: []
    def options = [:]
    devices.each { d ->
        options[d.dni] = "${d.name} - ${d.model}${d.online ? '' : ' (offline)'}"
    }
    int count = options.size()

    dynamicPage(name: "deviceList", title: sectionTitle("${count} InvisOutlet device(s) found - select which to add to Hubitat"), uninstall: false) {
        section("") {
            if (count == 0) {
                paragraph boldRedTitle("No devices found. Double-check the broker address/credentials on the previous page, or that your InvisOutlet devices are configured to publish to this broker.")
            }
            paragraph "Devices currently publishing data are shown as online; others were seen previously (their settings are still on the broker) but no recent activity was detected."
            input(name: "exposed", title: "", type: "enum", description: "Click to choose", options: options, multiple: true, submitOnChange: true)
            paragraph "Note: clicking 'Next' will create the selected devices and remove any previously-created InvisOutlet devices that are no longer selected."
        }
    }
}

def finish() {
    def listener = ensureListenerDevice()
    def discovered = listener.getDiscoveredDevices() ?: []
    def keepDnis = []

    (settings.exposed ?: []).each { dni ->
        def info = discovered.find { it.dni == dni }
        if (!info) {
            log.warn "No discovery info found for ${dni} - skipping"
            return
        }
        if (createInvisOutletDevice(dni, info)) keepDnis << dni
    }

    getChildDevices().each { cd ->
        if (cd.deviceNetworkId != listenerDni() && !keepDnis.contains(cd.deviceNetworkId)) {
            log.info "Removing ${cd.displayName} (no longer selected)"
            deleteChildDevice(cd.deviceNetworkId)
        }
    }

    listener.registerActiveDevices(keepDnis)

    if (keepDnis.isEmpty()) {
        removeListenerDevice()
    }

    dynamicPage(name: "finish", title: pageTitle("Installation Complete"), install: true) {
        section("") {
            paragraph "Created/updated ${keepDnis.size()} device(s)."
            paragraph "Click 'Done' to exit."
        }
    }
}

// ---------------- lifecycle ----------------

def installed() {
    log.info "InvisOutlet Device Service installed"
}

def updated() {
    log.info "InvisOutlet Device Service updated"
    def listener = getListenerDevice()
    if (listener) {
        // Unit preferences may have changed - re-display already-known readings
        // immediately instead of waiting for the device's next MQTT publish.
        try { listener.recomputeTemperatures() } catch (ignored) { }
        try { listener.recomputeDistances() } catch (ignored) { }
    }
}

def uninstalled() {
    log.warn "Uninstalling InvisOutlet Device Service - removing all devices it created"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// ---------------- listener device management ----------------

def listenerDni() { "invisoutlet-mqtt-listener" }

def getListenerDevice() { getChildDevice(listenerDni()) }

def ensureListenerDevice() {
    def listener = getListenerDevice()
    if (!listener) {
        listener = addChildDevice("almulder", "InvisOutlet MQTT Listener", listenerDni(), [label: "InvisOutlet MQTT Listener"])
        log.info "Created InvisOutlet MQTT Listener device"
    }
    listener.configure([
        broker  : settings.mqttBroker,
        username: settings.mqttUser,
        password: settings.mqttPassword,
        prefix  : DISCOVERY_PREFIX,
        filter  : DEVICE_FILTER
    ])
    return listener
}

def removeListenerDevice() {
    def listener = getListenerDevice()
    if (listener) {
        log.info "Removing InvisOutlet MQTT Listener device - no InvisOutlet devices remain"
        deleteChildDevice(listener.deviceNetworkId)
    }
}

// ---------------- InvisOutlet Pro/Aura device creation ----------------

def createInvisOutletDevice(String dni, Map info) {
    def driverType = (info.model == "Aura") ? "InvisOutlet Aura" : "InvisOutlet Pro"
    def cd = getChildDevice(dni)
    if (!cd) {
        try {
            cd = addChildDevice("almulder", driverType, dni, [label: buildLabel(info)])
            log.info "Created ${cd.displayName} using driver '${driverType}'"
        } catch (e) {
            log.error "Failed to create device for ${dni} using driver '${driverType}': ${e}"
            return false
        }
    } else {
        cd.setLabel(buildLabel(info))
    }
    return true
}

def buildLabel(Map info) {
    def rawName = info.name ?: "InvisOutlet"
    // The device's own reported name already starts with "InvisOutlet" (e.g. "InvisOutlet
    // B3E0") - strip that off first so the prefix toggle doesn't produce a duplicated name.
    def baseName = rawName.replaceFirst(/(?i)^invisoutlet\s*/, "").trim()
    if (!baseName) baseName = rawName
    def prefix = (settings.namePrefix) ? "InvisOutlet - " : ""
    def suffix = (settings.includeType) ? " - ${info.model}" : ""
    return "${prefix}${baseName}${suffix}"
}

// ---------------- called by the Listener to relay MQTT updates to the right device ----------------

def forwardUpdateEntity(String dni, String attrName, String platform, deviceClass, value, unit) {
    getChildDevice(dni)?.updateEntity(attrName, platform, deviceClass, value, unit)
}

def forwardUpdateLevel(String dni, level) {
    getChildDevice(dni)?.updateLevel(level)
}

def forwardUpdateColor(String dni, huePercent, satPercent) {
    getChildDevice(dni)?.updateColor(huePercent, satPercent)
}

def forwardUpdateColorTemperature(String dni, kelvin) {
    getChildDevice(dni)?.updateColorTemperature(kelvin)
}

def forwardButtonEvent(String dni, String attrName) {
    getChildDevice(dni)?.buttonEvent(attrName)
}

// ---------------- called by InvisOutlet Pro/Aura devices to send commands ----------------

def sendSwitchCommand(childDevice, String attrName, boolean turnOn) {
    getListenerDevice()?.publishSwitch(childDevice.deviceNetworkId, attrName, turnOn)
}

def sendLevelCommand(childDevice, String attrName, level) {
    getListenerDevice()?.publishLevel(childDevice.deviceNetworkId, attrName, level)
}

def sendHueSatCommand(childDevice, String attrName, hue, sat) {
    getListenerDevice()?.publishHueSat(childDevice.deviceNetworkId, attrName, hue, sat)
}

def sendColorTempCommand(childDevice, String attrName, kelvin) {
    getListenerDevice()?.publishColorTemp(childDevice.deviceNetworkId, attrName, kelvin)
}

// ---------------- settings accessors for the Listener ----------------

def getTemperatureScale() { settings.temperatureScale ?: "C" }
def getDistanceUnit() { settings.distanceUnit ?: "cm" }
def isDebugEnabled() { settings.debugging == true }

// ---------------- small HTML helpers (page styling) ----------------

String pageTitle(String txt) { return '<h2>' + txt + '</h2>' }
String sectionTitle(String txt) { return '<h3>' + txt + '</h3>' }
String blueTitle(String txt) { return '<span style="color:#0000ff">' + txt + '</span>' }
String boldTitle(String txt) { return '<b>' + txt + '</b>' }
String boldRedTitle(String txt) { return '<span style="color:#ff0000"><b>' + txt + '</b></span>' }
