/**
 * InvisOutlet Pro
 *
 * A normal, top-level Hubitat device (not a child of anything) representing one
 * physical InvisOutlet Pro unit, created and managed by the "InvisOutlet Device
 * Service" app - you select which discovered units to add during that app's setup
 * wizard; you should not need to create this device manually. Every switch, sensor,
 * touch button, and the nightlight for that unit live as attributes/commands on
 * this single device.
 *
 * OUTLETS / NIGHTLIGHT - controlled explicitly by name, no ambiguous on()/off():
 *   - outlet1On() / outlet1Off()
 *   - outlet2On() / outlet2Off()
 *   - nightlightOn() / nightlightOff()
 *   - setLevel(0-100) dims the nightlight, if it supports dimming.
 *   (Named "1"/"2" rather than top/bottom so it stays correct even if the unit
 *   is installed upside down, and matches the touch buttons' own numbering.)
 *
 * TOUCH BUTTONS: if the InvisHome app has the physical touch controls set to act
 * as scene/automation triggers (rather than directly controlling the outlets),
 * presses show up as standard Hubitat button events ("pushed" 1 or 2) so they
 * work directly with Rule Machine/Button Controller. If the app instead has them
 * set to control the outlets, they'll simply show up as extra outlet activity
 * instead - no separate configuration needed here, it follows whatever mode is
 * set in the InvisHome app. Use the "Push" command to manually fire a test event.
 *
 * SENSORS: temperature (unit set in the app) and humidity/illuminance use the
 * standard capability attributes. Motion and occupancy/presence are kept separate.
 * Distance (unit set in the app), Air Quality Index, CO2, Air Pressure, and VOC
 * are explicitly declared attributes (not just ad-hoc sendEvent calls), so they're
 * visible to webCoRE and other automation tools, not only Hubitat's own UI.
 *
 * LOGGING: outlet on/off changes and button presses always log at info level.
 * Turn on "Enable debug logging" below for a raw play-by-play of what the app
 * is sending this device, useful for troubleshooting.
 *
 * INSTALL: Drivers Code -> New Driver -> paste this file -> Save. Also install the
 * "InvisOutlet MQTT Listener" driver and the "InvisOutlet Device Service" app.
 * 1.1.0 - Initial Working Version
 * 1.1.1 - Added markPending(attrName, platform, deviceClass), called by the app right
 * after this device is created: stamps a "pending" placeholder on each attribute so
 * the driver page shows something meaningful immediately, instead of blank until a
 * real MQTT value arrives. Unlike updateEntity(), does not collapse the value into
 * fixed on/off or active/inactive text.
 * 2.0.0 - Version alignment with the App and Listener's coordinated 2.0.0 release - no
 * functional changes in this file.
 */
def clientVersion() { "2.0.0" }
private def copyright() { return "<br>© 2026-" + new Date().format("yyyy") + " Albert Mulder. All rights reserved." }
def driverName() { "InvisOutlet Pro" }
def activeScale() { (parent?.getTemperatureScale() == "F") ? "Fahrenheit (°F)" : "Celsius (°C)" }
def activeMeasurementScale() { (parent?.getDistanceUnit() == "in") ? "Inches (in)" : "Centimeters (cm)" }

metadata {
    definition(name: "InvisOutlet Pro", namespace: "almulder", author: "Albert Mulder") {
        capability "SwitchLevel"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "IlluminanceMeasurement"
        capability "MotionSensor"
        capability "PresenceSensor"
        capability "PushableButton"
        capability "Refresh"
        capability "Actuator"

        command "outlet1On"
        command "outlet1Off"
        command "outlet2On"
        command "outlet2Off"
        command "nightlightOn"
        command "nightlightOff"

        // Explicitly declared (rather than just sendEvent'd ad hoc) so webCoRE and
        // other tools that read a device's declared attribute list can see them.
        attribute "outlet1", "enum", ["on", "off"]
        attribute "outlet2", "enum", ["on", "off"]
        attribute "nightlight", "enum", ["on", "off"]
        attribute "airQualityIndex", "number"
        attribute "co2", "number"
        attribute "distance", "number"
        attribute "airPressure", "number"
        attribute "voc", "number"
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
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    sendEvent(name: "numberOfButtons", value: 2)
}

def updated() {
    if (logEnable) {
        log.debug "${device.displayName}: debug logging enabled"
        runIn(1800, "logsOff")
    }
}

def logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "${device.displayName}: debug logging auto-disabled after 30 minutes"
}

def refresh() {
    // States arrive via MQTT push from the app; nothing to actively poll.
}

// ---------------- named outlet / nightlight commands ----------------

def outlet1On() { sendSwitch("outlet1", true) }
def outlet1Off() { sendSwitch("outlet1", false) }
def outlet2On() { sendSwitch("outlet2", true) }
def outlet2Off() { sendSwitch("outlet2", false) }
def nightlightOn() { sendSwitch("nightlight", true) }
def nightlightOff() { sendSwitch("nightlight", false) }

private sendSwitch(String attrName, boolean turnOn) {
    if (logEnable) log.debug "${device.displayName}: requesting ${turnOn ? 'ON' : 'OFF'} for '${attrName}'"
    parent?.sendSwitchCommand(device, attrName, turnOn)
}

def setLevel(level, duration = null) {
    if (logEnable) log.debug "${device.displayName}: requesting nightlight level ${level}"
    parent?.sendLevelCommand(device, "nightlight", level)
}

// Manual test command for the PushableButton capability - fires a button event
// locally without needing the physical hardware. Does not control the outlets.
def push(buttonNumber) {
    def num = buttonNumber as Integer
    log.info "${device.displayName}: button ${num} pushed (manual test)"
    sendEvent(name: "pushed", value: num, isStateChange: true, descriptionText: "${device.displayName} button ${num} pushed (manual test)")
}

// ---------------- called by the app as MQTT state updates arrive ----------------

def updateEntity(String attrName, String platform, deviceClass, value, unit = null) {
    if (logEnable) log.debug "${device.displayName}: updateEntity(attr=${attrName}, platform=${platform}, class=${deviceClass}, value=${value}, unit=${unit})"

    switch (platform) {
        case "switch":
        case "light":
            def stateText = value ? "on" : "off"
            def label = friendlyOutletLabel(attrName)
            def descriptionText = "${device.displayName} ${label} turned ${stateText}"
            log.info descriptionText
            sendEvent(name: attrName, value: stateText, descriptionText: descriptionText)
            break
        case "binary_sensor":
            if (deviceClass == "motion") {
                sendEvent(name: "motion", value: value ? "active" : "inactive")
            } else if (deviceClass == "occupancy") {
                sendEvent(name: "presence", value: value ? "present" : "not present")
            } else {
                sendEvent(name: attrName, value: value ? "open" : "closed")
            }
            break
        case "sensor":
            switch (deviceClass) {
                case "temperature":
                    sendEvent(name: "temperature", value: value, unit: unit)
                    break
                case "humidity":
                    sendEvent(name: "humidity", value: value, unit: unit)
                    break
                case "illuminance":
                    sendEvent(name: "illuminance", value: value, unit: unit)
                    break
                default:
                    sendEvent(name: attrName, value: value, unit: unit)
            }
            break
        default:
            sendEvent(name: attrName, value: value, unit: unit)
    }
}

def updateLevel(level) {
    sendEvent(name: "level", value: level)
}

// Called by the app right after this device is created, to stamp a "pending"
// placeholder on every attribute the app knows this specific unit has - unlike
// updateEntity() above, this does NOT collapse the value into fixed on/off or
// active/inactive text, since "pending" needs to actually be visible as-is.
def markPending(String attrName, String platform, deviceClass) {
    switch (platform) {
        case "switch":
        case "light":
            sendEvent(name: attrName, value: "pending")
            break
        case "binary_sensor":
            if (deviceClass == "motion") {
                sendEvent(name: "motion", value: "pending")
            } else if (deviceClass == "occupancy") {
                sendEvent(name: "presence", value: "pending")
            } else {
                sendEvent(name: attrName, value: "pending")
            }
            break
        default:
            sendEvent(name: attrName, value: "pending")
    }
}

// Called for touch-button presses (MQTT device_automation trigger, HA "event"
// platform, or a binary_sensor button entity transitioning to active).
// attrName is expected to be "button1"/"button2" for the two physical buttons;
// anything else just gets a raw "pushed" event under its own name.
def buttonEvent(String attrName) {
    if (logEnable) log.debug "${device.displayName}: buttonEvent(attr=${attrName})"

    def matcher = (attrName =~ /(\d+)$/)
    def num = matcher.find() ? (matcher.group(1) as Integer) : null

    def descriptionText = "${device.displayName} ${friendlyButtonLabel(attrName, num)} pressed"
    log.info descriptionText

    if (num != null) {
        def currentMax = (device.currentValue("numberOfButtons") ?: 0) as Integer
        if (num > currentMax) sendEvent(name: "numberOfButtons", value: num)
        sendEvent(name: "pushed", value: num, isStateChange: true, descriptionText: descriptionText)
    } else {
        sendEvent(name: attrName, value: "pushed", isStateChange: true, descriptionText: descriptionText)
    }
}

private friendlyOutletLabel(String attrName) {
    switch (attrName) {
        case "outlet1": return "outlet 1"
        case "outlet2": return "outlet 2"
        case "nightlight": return "nightlight"
        default: return attrName
    }
}

private friendlyButtonLabel(String attrName, num) {
    if (num == 1) return "button 1"
    if (num == 2) return "button 2"
    return attrName
}
