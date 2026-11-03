/**
*
*  WLED Controller
*
*  Author: Albert Mulder
*  Version: 2.0.0
*  Date: 2026-11-03
*
*  Based on "WLED Device Type" by Bryan Li (bryan@joyful.house), 2019-11-27
*  https://github.com/joyfulhouse/hubitat-wled
*
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is
*  distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
*  implied. See the License for the specific language governing permissions and limitations under the License.
*
*  Changes from the original (modified by Albert Mulder):
*  2.0.0 (2026-11-03)
*    - New "Control" preference. "Whole controller" (default) turns the controller and all segments
*      on/off and dims the master brightness, so it works no matter how many segments a preset uses.
*      "Single segment" keeps the original per-segment behavior.
*    - Fixed NullPointerException "Cannot get property 'on' on null object" in parseResp/synchronize
*      when the configured segment is not in the controller's reply (e.g. after a preset changes the layout).
*    - Segments are looked up by their WLED id, not by list position.
*    - Fixed "1 Hour" refresh interval polling every minute.
*    - Level is synchronized from the controller, not just on/off.
*    - Only the segment list is kept in state (was the whole JSON reply).
*    - Effect/palette list requests no longer reference an undefined variable.
*    - Debug logging defaults off and turns itself off after 30 minutes.
*    - Color commands no longer crash before the first refresh, set Solid in one request instead of two,
*      and setHue no longer sends out-of-range brightness.
*    - Set Preset refreshes the device two seconds later so on/off and level stay accurate.
*    - Removed the "Power Off" preference (replaced by "Control").
*/
import java.net.URLEncoder

metadata {
    definition (name: "WLED Controller", namespace: "almulder", author: "Albert Mulder",
                importUrl: "https://raw.githubusercontent.com/almulder/Hubitat-Drivers/main/WLED/Drivers/WLED%20Controller.groovy") {
        capability "Color Control"
        capability "Color Temperature"
        capability "Refresh"
        capability "Switch"
        capability "Switch Level"
        capability "Light"
        capability "ColorMode"

        capability "Alarm"

        attribute "colorName", "string"
        attribute "effectName", "string"
        attribute "paletteName", "string"
        attribute "presetValue", "Number"

        command "setEffect",
            [
                [name:"FX ID", type: "NUMBER", description: "Effect ID", constraints: []],
                [name:"FX Speed", type: "NUMBER", description: "Relative Effect Speed (0-255)", constraints: []],
                [name:"FX Intensity", type: "NUMBER", description: "Effect Intensity(0-255)", constraints: []],
                [name:"Color Palette", type: "NUMBER", description: "Color Palette", constraints: []]
            ]
        command "setPreset",
            [
                [name:"Preset", type: "NUMBER", description: "Preset Number", constraints: []],
            ]
    }

    // Preferences
    preferences {
        input "uri", "text", title: "URI", description: "(eg. http://[wled_ip_address])", required: true, displayDuringSetup: true
        input name: "controlScope", type: "enum", title: "Control", description: "What On, Off and Level control", options: [
            ["controller": "Whole controller (all segments)"],["segment": "Single segment"]],
            defaultValue: "controller"
        input name: "ledSegment", type: "number", title: "LED Segment", description: "Segment id used by Single segment mode and by color/effect commands", defaultValue: 0
        input name: "transitionTime", type: "enum", description: "", title: "Transition time", options: [[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 1000
        input name: "refreshInterval", type: "enum", description: "", title: "Refresh interval", options: [
            [30: "30 Seconds"],[60:"1 Minute"],[300:"5 Minutes"],[600:"10 Minutes"],[1800:"30 Minutes"],[3600:"1 Hour"],[0:"Disabled"]],
            defaultValue: 3600
        input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def initialize() {
     installed()
}

def installed() {
    setSchedule()
    refresh()
}

def updated() {
    // Drop the full JSON reply older versions kept in state
    def effects = state.effects
    def palettes = state.palettes
    state.clear()
    state.effects = effects
    state.palettes = palettes

    unschedule()
    setSchedule()
    if (logEnable) runIn(1800, logsOff)
    getEffects()
    getPalettes()
    refresh()
}

def setSchedule() {
  logDebug "Setting refresh interval to ${settings.refreshInterval}s"
  unschedule(refresh)
  switch(settings.refreshInterval){
    case "0":
      break
    case "30":
      schedule("0/30 * * ? * * *", refresh)
      break
    case "60":
      schedule("0 * * ? * * *", refresh)
      break
    case "300":
      schedule("0 0/5 * ? * * *", refresh)
      break
    case "600":
      schedule("0 0/10 * ? * * *", refresh)
      break
    case "1800":
      schedule("0 0/30 * ? * * *", refresh)
      break
    case "3600":
      schedule("0 0 * ? * * *", refresh)
      break
    default:
      break
  }
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def parseResp(resp) {
    // Handle Effects and Palettes
    if(!state.effects)
        getEffects()

    if(!state.palettes)
        getPalettes()

    // Update State
    logDebug resp
    state.seg = resp?.seg

    synchronize(resp)
}

// Handle async callback
def parseResp(resp, data) {
    if(resp.getStatus() == 200) {
        def json
        try {
            json = resp.getJson()
        } catch (e) {
            log.error "WLED returned a reply that is not JSON: ${e}"
            return
        }
        parseResp(json)
    }
    else if(resp.getStatus() == 408)
        log.error "HTTP Request Timeout"
    else
        log.error "Unhandled HTTP Error ${resp.getStatus()}"
}

def parsePostResp(resp){
    // TODO
}

def parsePostResp(resp, data) {
    if(resp.getStatus() == 200)
        parsePostResp(resp.getJson())
    else if(resp.getStatus() == 408)
        log.error "HTTP Request Timeout"
    else
        log.error "Unhandled HTTP Error"
}

def synchronize(data){
    def isOn
    def bri
    if (segmentMode()) {
        def seg = findSeg(data?.seg)
        if (!seg) {
            log.warn "WLED: segment ${segmentId()} not found, controller has segments ${data?.seg?.collect { it.id }}. Check the LED Segment preference."
            return
        }
        isOn = data.on && seg.on
        bri = seg.bri
    } else {
        isOn = data?.on
        bri = data?.bri
    }
    if (isOn == null) return
    logDebug "Synchronizing status: on=${isOn} bri=${bri}"

    // Power
    if(isOn){
        if(device.currentValue("switch") != "on")
            sendEvent(name: "switch", value: "on")
    }
    else {
        if(device.currentValue("switch") == "on")
            sendEvent(name: "switch", value: "off")
    }

    // Level
    if(isOn && bri != null){
        def level = Math.round(bri.toInteger() / 2.55)
        if(device.currentValue("level") != level)
            sendEvent(name: "level", value: level, unit: "%")
    }

    //TODO: Synchronize everything else
}

// Segment helpers
def segmentMode() {
    return settings.controlScope == "segment"
}

def segmentId() {
    return settings.ledSegment?.toInteger() ?: 0
}

def findSeg(segs) {
    def id = segmentId()
    return segs?.find { it.id == id }
}

// Switch Capabilities
def on() {
    if (segmentMode())
        sendEthernetPost("/json/state","{\"on\":true, \"seg\": [{\"id\": ${segmentId()}, \"on\":true}]}")
    else
        sendEthernetPost("/json/state","{\"on\":true}")
    sendEvent(name: "switch", value: "on")
}

def off() {
    if (segmentMode())
        sendEthernetPost("/json/state","{\"pl\":0,\"ps\":0,\"seg\": [{\"id\": ${segmentId()}, \"on\":false}]}")
    else
        sendEthernetPost("/json/state","{\"on\":false,\"pl\":0,\"ps\":0}")
    sendEvent(name: "switch", value: "off")
}

// Color Names
def setGenericTempName(temp){
    if (!temp) return
    def genericName
    def value = temp.toInteger()
    if (value <= 2000) genericName = "Sodium"
    else if (value <= 2100) genericName = "Starlight"
    else if (value < 2400) genericName = "Sunrise"
    else if (value < 2800) genericName = "Incandescent"
    else if (value < 3300) genericName = "Soft White"
    else if (value < 3500) genericName = "Warm White"
    else if (value < 4150) genericName = "Moonlight"
    else if (value <= 5000) genericName = "Horizon"
    else if (value < 5500) genericName = "Daylight"
    else if (value < 6000) genericName = "Electronic"
    else if (value <= 6500) genericName = "Skylight"
    else if (value < 20000) genericName = "Polar"
    def descriptionText = "${device.getDisplayName()} color is ${genericName}"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "colorName", value: genericName ,descriptionText: descriptionText)
}

def setGenericName(hue){
    def colorName
    hue = hue.toInteger()
    if (!settings.hiRezHue) hue = (hue * 3.6)
    switch (hue.toInteger()){
        case 0..15: colorName = "Red"
            break
        case 16..45: colorName = "Orange"
            break
        case 46..75: colorName = "Yellow"
            break
        case 76..105: colorName = "Chartreuse"
            break
        case 106..135: colorName = "Green"
            break
        case 136..165: colorName = "Spring"
            break
        case 166..195: colorName = "Cyan"
            break
        case 196..225: colorName = "Azure"
            break
        case 226..255: colorName = "Blue"
            break
        case 256..285: colorName = "Violet"
            break
        case 286..315: colorName = "Magenta"
            break
        case 316..345: colorName = "Rose"
            break
        case 346..360: colorName = "Red"
            break
    }
    def descriptionText = "${device.getDisplayName()} color is ${colorName}"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "colorName", value: colorName ,descriptionText: descriptionText)
}

// Dimmer function
def setLevel(value) {
    setLevel(value, (transitionTime?.toBigDecimal() ?: 1000) / 1000)
}

def setLevel(value, rate) {
    // TODO: implement transition rate

    if(value > 0){
        def setValue
        if(value >= 100) {
            setValue = 255
            value = 100
        }
        else {
            setValue = (value.toInteger() * 2.55).toInteger()
        }

        def msg
        if (segmentMode())
            msg = "{\"on\":true, \"seg\": [{\"id\": ${segmentId()}, \"on\":true, \"bri\": ${setValue}}]}"
        else
            msg = "{\"on\":true, \"bri\": ${setValue}}"
        sendEthernetPost("/json/state", msg)
        sendEvent(name: "switch", value: "on")
        sendEvent(name: "level", value: value, descriptionText: "${device.displayName} is ${value}%", unit: "%")
    } else {
        off()
    }

    refresh()
}

// Color Functions
def setColor(value){
    if (value.hue == null || value.saturation == null) return

    // Turn off if level is set to 0/black
    if (value.level == 0) {
        off()
        return
    }

    // Convert to RGB from HSV
    def rgbValue = hsvToRgb(value.hue, value.saturation, value.level ?: 100)

    // Send to WLED
    logDebug("Setting RGB Color to ${rgbValue}")
    setRgbColor(rgbValue)
    setGenericName(value.hue)
}

def setColorTemperature(temp, level, transitionTime){
    if (level) {
      setLevel(level, transitionTime)
    }

    setColorTemperature(temp)
}

def setColorTemperature(temp, level){
    if (level) {
      setLevel(level)
    }

    setColorTemperature(temp)
}

def setColorTemperature(temp){
    on()
    def rgbValue = colorTempToRgb(temp)
    setRgbColor(rgbValue)
    setGenericTempName(temp)
}

def setHue(value){
    //TODO: Fix color conversion
    def color = [:]
    color.hue = value
    color.level = 100
    color.saturation = 100

    setColor(color)
}

def setRgbColor(rgbValue){
    // Send Color with effects turned off (fx 0 = Solid)
    def body = "{\"on\":true, \"seg\": [{\"id\": ${segmentId()}, \"on\":true, \"fx\": 0, \"col\": [${rgbValue}]}]}"
    logDebug("Setting color: ${body}")
    sendEthernetPost("/json/state", body)
    sendEvent(name: "switch", value: "on")
    refresh()
}

// Device Functions
def refresh() {
    sendEthernet("/json/state")
}

def sendEthernet(path) {
    if(settings.uri != null){
        def params = [
            uri: "${settings.uri}",
            path: "${path}",
            requestContentType: 'application/json',
            contentType: 'application/json',
            headers: [:],
            timeout: 5
        ]

        try {
            asynchttpGet('parseResp',params)
        } catch (e) {
            log.error "something went wrong: $e"
        }
    }
}

def sendEthernetPost(path, body) {
    if(settings.uri != null){

        def params = [
            uri: "${settings.uri}",
            path: "${path}",
            requestContentType: 'application/json',
            contentType: 'application/json',
            body: "${body}",
            timeout: 5
        ]

        try {
            asynchttpPut(null, params)
        } catch (e) {
            log.error "something went wrong: $e"
        }
    }
}

// Helper Functions
def logDebug(message){
    if(logEnable) log.debug(message)
}

def hsvToRgb(float hue, float saturation, float value) {
    if(hue==100) hue = 99
    hue = hue/100
    saturation = saturation/100
    value = value/100

    int h = (int)(hue * 6)
    float f = hue * 6 - h
    float p = value * (1 - saturation)
    float q = value * (1 - f * saturation)
    float t = value * (1 - (1 - f) * saturation)

    switch (h) {
      case 0: return rgbToString(value, t, p)
      case 1: return rgbToString(q, value, p)
      case 2: return rgbToString(p, value, t)
      case 3: return rgbToString(p, q, value)
      case 4: return rgbToString(t, p, value)
      case 5: return rgbToString(value, p, q)
      default: log.error "Something went wrong when converting from HSV to RGB. Input was " + hue + ", " + saturation + ", " + value
    }
}

def colorTempToRgb(kelvin){
    def temp = kelvin/100
    def red, green, blue

    if( temp <= 66 ){
        red = 255
        green = temp
        green = 99.4708025861 * Math.log(green) - 161.1195681661
        if( temp <= 19){
            blue = 0
        } else {
            blue = temp-10
            blue = 138.5177312231 * Math.log(blue) - 305.0447927307
        }
    } else {
        red = temp - 60
        red = 329.698727446 * Math.pow(red, -0.1332047592)
        green = temp - 60
        green = 288.1221695283 * Math.pow(green, -0.0755148492 )
        blue = 255
    }

    def rs = clamp(red,0, 255).toInteger()
    def gs = clamp(green,0,255).toInteger()
    def bs = clamp(blue,0, 255).toInteger()

    return "[" + rs + "," + gs + "," + bs + "]";
}

def rgbToString(float r, float g, float b) {
    String rs = (int)(r * 255)
    String gs = (int)(g * 255)
    String bs = (int)(b * 255)
    return "[" + rs + "," + gs + "," + bs + "]";
}

def clamp( x, min, max ) {
    if(x<min){ return min; }
    if(x>max){ return max; }
    return x;
}

// FastLED FX and Palletes

def getEffects(){
    if(settings.uri == null) return
    logDebug "Getting Effects List"
    def params = [
        uri: "${settings.uri}",
        path: "/json/effects",
        headers: [
            "Content-Type": "application/json",
            "Accept": "*/*"
        ],
        timeout: 5
    ]

    try {
        httpGet(params) {
            resp ->
                state.effects = resp.data
        }
    } catch (e) {
        log.error "something went wrong: $e"
    }
}

def getPalettes(){
    if(settings.uri == null) return
    logDebug "Getting Palettes List"
    def params = [
        uri: "${settings.uri}",
        path: "/json/palettes",
        headers: [
            "Content-Type": "application/json",
            "Accept": "*/*"
        ],
        timeout: 5
    ]

    try {
        httpGet(params) {
            resp ->
                state.palettes = resp.data
        }
    } catch (e) {
        log.error "something went wrong: $e"
    }
}

// Current speed/intensity/palette of the configured segment, with WLED defaults if unknown
def currentSeg() {
    return findSeg(state.seg) ?: [sx: 128, ix: 128, pal: 0]
}

def setEffect(fx){
    def seg = currentSeg()
    setEffect(fx, seg.sx, seg.ix, seg.pal)
}

def setEffect(fx, pal){
    def seg = currentSeg()
    setEffect(fx, seg.sx, seg.ix, pal)
}

def setEffect(fx,sx,ix){
    setEffect(fx, sx, ix, currentSeg().pal)
}

def setEffect(fx, sx, ix, pal){
    def seg = currentSeg()
    if (sx == null) sx = seg.sx
    if (ix == null) ix = seg.ix
    if (pal == null) pal = seg.pal
    def body = "{\"on\":true, \"seg\": [{\"id\": ${segmentId()},\"fx\": ${fx},\"sx\": ${sx},\"ix\": ${ix},\"pal\": ${pal}}]}"
    logDebug("Setting Effect: ${body}")

    sendEthernetPost("/json/state", body)
    sendEvent(name: "switch", value: "on")

    // Effect Name
    def effectName = state.effects?.getAt(fx.intValue())
    def descriptionText = "${device.getDisplayName()} effect is ${effectName}"
    if (txtEnable) log.info "${descriptionText}"
        sendEvent(name: "effectName", value: effectName, descriptionText: descriptionText)

    // Palette Name
    def paletteName = state.palettes?.getAt(pal.intValue())
    descriptionText = "${device.getDisplayName()} color palette is ${paletteName}"
    if (txtEnable) log.info "${descriptionText}"
        sendEvent(name: "paletteName", value: paletteName, descriptionText: descriptionText)

    if(fx > 0){
        // Color Name
        descriptionText = "${device.getDisplayName()} color is defined by palette"
        sendEvent(name: "colorName", value: "Palette", descriptionText: descriptionText)
    }

    // Refresh
    refresh()
}

def setEffectCustom(fx, sx, ix, pal){
    // support for webCORE
    setEffect(fx, sx, ix, pal)
}

def setPreset(preset)
{
    logDebug("${device.getDisplayName()} setting preset to ${preset}")

    def msg = "{\"on\":true, \"ps\": ${preset}}"
    sendEthernetPost("/json/state", msg)
    sendEvent(name: "switch", value: "on")
    sendEvent(name: "presetValue", value: preset, descriptionText: "${device.displayName} preset is set to ${preset}")
    runIn(2, refresh)
}

// Alarm Functions
def siren(){
    // Play "Siren" effect
    logDebug("Alarm \"siren\" activated")
    setEffect(38,255,255,0)
}

def strobe(){
    // Set Effect to Strobe
    logDebug("Alarm strobe activated")
    setEffect(23,255,255,0)
}

def both(){
    //Cannot do both, default to strobe
    strobe()
}
