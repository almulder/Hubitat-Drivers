/**
 *  Cat Litter Box (Tuya Local)
 *
 *  Controls a Tuya "Smart Cat Litter Box 04 ZY" (sold under several brands and
 *  usually set up in the Smart Life / Tuya Smart app) directly over your LAN, with
 *  no cloud. It speaks Tuya protocol 3.5, so the box can be blocked from the internet
 *  and still be controlled from Hubitat: clean, empty, auto mode, deodorize, infrared
 *  sensor, child lock, clumping time, cleaning interval, bin calibration and litter
 *  leveling.
 *
 *  Setup: each box needs its IP address, Device ID and Local Key. The preferences
 *  page explains how to get them. In short:
 *    1. Pair the box in the Smart Life (or Tuya Smart) app.
 *    2. Create a free account at https://platform.tuya.com, then
 *       Cloud > Development > Create Cloud Project, picking the data center
 *       for your region (e.g. Western America for the US).
 *    3. In the project, Devices > Link App Account > Add App Account, and scan the
 *       QR code with Smart Life (Me tab > scan icon, top right). The box shows up
 *       in the device list with its Device ID.
 *    4. Get the Local Key: Cloud > API Explorer > Device Management >
 *       "Query device details", enter the Device ID; the "local_key" field is the key.
 *       (Or run "python -m tinytuya wizard" with the project's Access ID/Secret.)
 *       If the API says the IoT Core subscription expired, renew the free trial
 *       under Cloud > Cloud Services > IoT Core.
 *    5. Give the box a fixed/reserved IP in your router.
 *  Re-pairing or resetting the box in the app changes its Local Key.
 *
 *  Requires Hubitat platform 2.2.9 or newer.
 *
 *  Author:    Albert Mulder (almulder)
 *  Copyright: © 2026 Albert Mulder
 *
 *  License:   PolyForm Noncommercial License 1.0.0
 *             https://polyformproject.org/licenses/noncommercial/1.0.0
 *             Free for personal / noncommercial use. Commercial use of any
 *             part of this code is not permitted.
 *
 *  Changelog (newest first)
 *  0.1.0 - 2026-11-04 - Initial build: local Tuya 3.5 control of the litter box.
 */

import groovy.transform.Field
import hubitat.helper.HexUtils
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

@Field static final String DRIVER_VERSION = "0.1.0"

// Setup help shown on the preferences page
@Field static final String HELP_IP = "The box's IP address on your network. Find it in your router's client list, and give it a fixed/reserved IP so it never changes."
@Field static final String HELP_KEYS = """<b>Getting the Device ID and Local Key</b> (one time, free):
<ol>
<li>Pair the box in the <b>Smart Life</b> (or Tuya Smart) app.</li>
<li>Create an account at <a href="https://platform.tuya.com" target="_blank">platform.tuya.com</a>, then <b>Cloud &gt; Development &gt; Create Cloud Project</b>. Pick the data center for your region (e.g. Western America for the US).</li>
<li>In the project: <b>Devices &gt; Link App Account &gt; Add App Account</b>, and scan the QR code with Smart Life (<b>Me</b> tab, scan icon top right). The box appears with its <b>Device ID</b>.</li>
<li><b>Local Key:</b> <b>Cloud &gt; API Explorer &gt; Device Management &gt; Query device details</b>, enter the Device ID; copy the <code>local_key</code> value. (Or run <code>python -m tinytuya wizard</code> with the project's Access ID/Secret.) If it says the IoT Core subscription expired, renew the free trial under <b>Cloud &gt; Cloud Services &gt; IoT Core</b>.</li>
</ol>
Copy the key exactly; it is 16 characters and often contains symbols. Re-pairing or resetting the box in the app changes the key."""

// Litter box data points (mapped from the SmartLife app, model "Smart Cat Litter Box 04 ZY")
@Field static final String DP_CLEAN      = "101"   // on starts a clean; switches Auto off
@Field static final String DP_EMPTY      = "102"   // dump all litter
@Field static final String DP_AUTO       = "105"   // Auto mode on/off
@Field static final String DP_INFRARED   = "111"   // infrared (cat detection) sensor on/off
@Field static final String DP_CHILD_LOCK = "114"   // child lock on/off
@Field static final String DP_CLUMP_MIN  = "117"   // clumping time, 0-60 min
@Field static final String DP_INTERVAL   = "118"   // cleaning interval, 0-120 min
@Field static final String DP_DEODORIZE  = "120"   // deodorize on/off
@Field static final String DP_BIN_CAL    = "124"   // waste bin calibration, 0-15
@Field static final String DP_LEVELING   = "125"   // litter leveling setting, 0-6

// Tuya command codes
@Field static final int SESS_START  = 3
@Field static final int SESS_RESP   = 4
@Field static final int SESS_FINISH = 5
@Field static final int HEART_BEAT  = 9
@Field static final int STATUS_RPT  = 8     // unsolicited status report
@Field static final int CONTROL_NEW = 0x0d  // 13  (set)
@Field static final int DP_QUERY_NEW= 0x10  // 16  (status query)

@Field static final String PREFIX = "00006699"   // all-digit, case-independent
@Field static final String SUFFIX = "00009966"
// Commands that do NOT get the "3.5\0\0..." version header prepended
@Field static final List NO_HDR = [3, 4, 5, 9, 0x0a, 0x10, 0x12, 0x40]

metadata {
    definition(name: "Cat Litter Box (Tuya Local)", namespace: "almulder", author: "Albert Mulder") {
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"
        capability "Initialize"

        attribute "session", "string"      // negotiating / ready / offline
        attribute "cleaning", "enum", ["on", "off"]
        attribute "emptying", "enum", ["on", "off"]
        attribute "autoMode", "enum", ["on", "off"]
        attribute "infraredSensor", "enum", ["on", "off"]
        attribute "childLock", "enum", ["on", "off"]
        attribute "deodorizing", "enum", ["on", "off"]
        attribute "clumpingTime", "number"      // minutes
        attribute "cleaningInterval", "number"  // minutes
        attribute "binCalibration", "number"
        attribute "litterLeveling", "number"
        attribute "dps", "string"          // full raw DPS JSON (for unmapped points)
        attribute "lastUpdate", "string"

        command "clean"
        command "stopClean"
        command "empty"
        command "cancelEmpty"
        command "autoMode",       [[name:"Auto mode*", type:"ENUM", constraints:["on","off"]]]
        command "deodorize",      [[name:"Deodorize*", type:"ENUM", constraints:["on","off"]]]
        command "infraredSensor", [[name:"Infrared sensor*", type:"ENUM", constraints:["on","off"]]]
        command "childLock",      [[name:"Child lock*", type:"ENUM", constraints:["on","off"]]]
        command "setClumpingTime",     [[name:"Minutes (0-60)*", type:"NUMBER"]]
        command "setCleaningInterval", [[name:"Minutes (0-120)*", type:"NUMBER"]]
        command "setBinCalibration",   [[name:"Level (0-15)*", type:"NUMBER"]]
        command "setLitterLeveling",   [[name:"Level (0-6)*", type:"NUMBER"]]
        command "reconnect"
        command "selfTest"
    }
    preferences {
        input name: "ip",        type: "text",   title: "Device IP address", description: HELP_IP, required: true
        input name: "devId",     type: "text",   title: "Device ID", description: HELP_KEYS, required: true
        input name: "localKey",  type: "text",   title: "Local Key", description: "16 characters, from the Tuya cloud project (see Device ID above).", required: true
        input name: "pollSec",   type: "number", title: "Poll interval (seconds)", defaultValue: 60
        input name: "txtEnable", type: "bool",   title: "Enable description logging", defaultValue: true
        input name: "debugLog",  type: "bool",   title: "Enable debug logging", description: "Turns itself off after 30 minutes.", defaultValue: false
    }
}

// ---------- lifecycle ----------

def installed() {
    log.info "${device.displayName}: installed (Cat Litter Box ${DRIVER_VERSION})"
    initialize()
}

def updated() {
    log.info "${device.displayName}: preferences saved (Cat Litter Box ${DRIVER_VERSION})"
    unschedule()
    initialize()
    if (settings.debugLog) runIn(1800, "logsOff")
}

def logsOff() {
    device.updateSetting("debugLog", [value: "false", type: "bool"])
    log.info "${device.displayName}: debug logging turned off"
}

def initialize() {
    logd "initialize()"
    state.seq = 1
    state.phase = "offline"
    state.rx = ""
    state.pending = []
    sendEvent(name: "session", value: "offline")
    reconnect()
    Integer p = (settings.pollSec ?: 60) as Integer
    if (p > 0) runIn(p, "refresh")
}

def reconnect() {
    try {
        interfaces.rawSocket.close()
    } catch (ignored) {}
    state.phase = "offline"
    state.rx = ""
    try {
        logd "connecting to ${settings.ip}:6668"
        interfaces.rawSocket.connect(settings.ip, 6668, byteInterface: true)
        startNegotiation()
    } catch (e) {
        log.warn "${device.displayName}: connect failed: ${e}"
        sendEvent(name: "session", value: "offline")
        runIn(30, "reconnect")
    }
}

def socketStatus(String msg) {
    logd "socketStatus: ${msg}"
    if (msg?.toLowerCase()?.contains("error") || msg?.toLowerCase()?.contains("close")) {
        state.phase = "offline"
        sendEvent(name: "session", value: "offline")
        runIn(15, "reconnect")
    }
}

// ---------- high-level commands ----------

def refresh() {
    logd "refresh()"
    queueOrSend([type: "query"])
    Integer p = (settings.pollSec ?: 60) as Integer
    if (p > 0) runIn(p, "refresh")
}

def clean()     { queueOrSend([type: "set", dps: [(DP_CLEAN): true]]) }
def stopClean() { queueOrSend([type: "set", dps: [(DP_CLEAN): false]]) }

def autoMode(String onOff) {
    queueOrSend([type: "set", dps: [(DP_AUTO): (onOff == "on")]])
}

def deodorize(String onOff) {
    queueOrSend([type: "set", dps: [(DP_DEODORIZE): (onOff == "on")]])
}

def empty()       { queueOrSend([type: "set", dps: [(DP_EMPTY): true]]) }
def cancelEmpty() { queueOrSend([type: "set", dps: [(DP_EMPTY): false]]) }

def infraredSensor(String onOff) {
    queueOrSend([type: "set", dps: [(DP_INFRARED): (onOff == "on")]])
}

def childLock(String onOff) {
    queueOrSend([type: "set", dps: [(DP_CHILD_LOCK): (onOff == "on")]])
}

def setClumpingTime(minutes)     { setNumber(DP_CLUMP_MIN, minutes, 0, 60, "clumping time") }
def setCleaningInterval(minutes) { setNumber(DP_INTERVAL, minutes, 0, 120, "cleaning interval") }
def setBinCalibration(level)     { setNumber(DP_BIN_CAL, level, 0, 15, "bin calibration") }
def setLitterLeveling(level)     { setNumber(DP_LEVELING, level, 0, 6, "litter leveling") }

private setNumber(String dp, value, int min, int max, String label) {
    Integer v = value as Integer
    if (v == null || v < min || v > max) {
        log.warn "${device.displayName}: ${label} must be ${min}-${max}, got ${value}"
        return
    }
    queueOrSend([type: "set", dps: [(dp): v]])
}

private queueOrSend(Map action) {
    if (state.phase == "ready" && isStale()) {
        logd "session stale, reconnecting"
        state.phase = "offline"
    }
    if (state.phase == "ready") {
        doAction(action)
    } else {
        state.pending = (state.pending ?: []) + [action]
        if (state.phase == "offline") reconnect()
    }
}

private doAction(Map action) {
    if (action.type == "query") {
        sendFrame(DP_QUERY_NEW, "{}")
    } else if (action.type == "set") {
        Map body = [protocol: 5, t: (now() / 1000) as Integer, data: [dps: action.dps]]
        sendFrame(CONTROL_NEW, JsonOutput.toJson(body))
    }
}

// ---------- session handshake ----------

private startNegotiation() {
    state.phase = "negotiating"
    sendEvent(name: "session", value: "negotiating")
    byte[] ln = new byte[16]
    new SecureRandom().nextBytes(ln)
    state.localNonce = HexUtils.byteArrayToHexString(ln)
    state.seq = 1
    byte[] frame = packFrame(nextSeq(), SESS_START, ln, keyBytes())
    logd "-> SESS_START"
    sendHex(frame)
    runIn(8, "negotiationTimeout")
}

// The box drops an idle connection after ~31s; a heartbeat every 10s keeps it
// open so unsolicited DP reports keep arriving. If nothing has come back in 25s
// the socket is presumed dead and we reconnect.
def heartbeat() {
    if (state.phase != "ready") return
    if (isStale()) {
        log.warn "${device.displayName}: no traffic from ${settings.ip} in 25s, reconnecting"
        reconnect()
        return
    }
    // sent without the TX debug line so heartbeats don't flood the log
    interfaces.rawSocket.sendMessage(HexUtils.byteArrayToHexString(
        packFrame(nextSeq(), HEART_BEAT, "{}".getBytes("UTF-8"), sessionKeyBytes())))
    runIn(10, "heartbeat")
}

private boolean isStale() {
    return (now() - ((state.lastRx ?: 0) as Long)) > 25000
}

def negotiationTimeout() {
    if (state.phase == "negotiating") {
        log.warn "${device.displayName}: no handshake reply from ${settings.ip} after 8s — retrying in 30s (if it keeps failing, check the IP and Local Key)"
        state.phase = "offline"
        sendEvent(name: "session", value: "offline")
        runIn(30, "reconnect")
    }
}

// Verifies the hand-built AES-GCM against a known-good vector and prints a
// fingerprint of the stored local key (not the key itself) for comparison.
def selfTest() {
    byte[] k   = HexUtils.hexStringToByteArray("000102030405060708090A0B0C0D0E0F")
    byte[] iv  = HexUtils.hexStringToByteArray("101112131415161718191A1B")
    byte[] pt  = "litterbox-selftest-123".getBytes("UTF-8")
    byte[] aad = HexUtils.hexStringToByteArray("0000000000010000000300000032")
    String want = "A84777DB6A3DD4806FF02E90AB419F5B49C859B604C72881AC5434233B09E22E129B811C79EF"
    String got = HexUtils.byteArrayToHexString(gcmEncrypt(k, iv, pt, aad))
    log.info "${device.displayName}: selfTest GCM: ${got == want ? 'PASS' : 'FAIL'}"
    if (got != want) log.info "${device.displayName}: selfTest got  ${got}\nselfTest want ${want}"
    byte[] key = keyBytes()
    String fp = HexUtils.byteArrayToHexString(subBytes(hmacSha256(key, "fingerprint".getBytes("UTF-8")), 0, 4))
    log.info "${device.displayName}: selfTest local key: length=${key.length} fingerprint=${fp}"
}

// ---------- inbound ----------

def parse(String hexMsg) {
    logd "RX ${hexMsg}"
    state.rx = ((state.rx ?: "") + hexMsg).toUpperCase()
    // extract complete frames from the hex buffer
    while (true) {
        String buf = state.rx
        int idx = buf.indexOf(PREFIX)
        if (idx < 0) { if (buf.length() > 4096) state.rx = ""; break }
        if (idx > 0) buf = buf.substring(idx)             // drop junk before prefix
        if (buf.length() < 36) { state.rx = buf; break }  // need 18-byte header
        byte[] hdr = HexUtils.hexStringToByteArray(buf.substring(0, 36))
        long length = readU32(hdr, 14)
        int totalBytes = 18 + (int) length + 4
        if (buf.length() < totalBytes * 2) { state.rx = buf; break }
        String frameHex = buf.substring(0, totalBytes * 2)
        state.rx = buf.substring(totalBytes * 2)
        handleFrame(HexUtils.hexStringToByteArray(frameHex))
    }
}

private handleFrame(byte[] data) {
    int cmd = (int) readU32(data, 10)
    byte[] pt
    try {
        pt = unpackFrame(data, (state.phase == "ready") ? sessionKeyBytes() : keyBytes())
    } catch (e) {
        // during negotiation the device encrypts with real key; after, session key
        try { pt = unpackFrame(data, keyBytes()) } catch (e2) {
            log.warn "${device.displayName}: decrypt failed cmd=${cmd}: ${e2}"; return
        }
    }
    state.lastRx = now()
    if (cmd == HEART_BEAT) return
    logd "<- cmd=${cmd} len=${pt?.length}"

    if (cmd == SESS_RESP) {
        onSessResp(pt)
        return
    }
    // data frames (status report 8, query reply 16)
    String json = new String(pt, "UTF-8")
    int b = json.indexOf("{")
    int e = json.lastIndexOf("}")
    if (b < 0 || e < 0) { logd "no json in frame"; return }
    json = json.substring(b, e + 1)
    try {
        def obj = new JsonSlurper().parseText(json)
        def dps = obj?.dps ?: obj?.data?.dps
        if (dps != null) {
            sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
            updateAttributesFromDps(dps)
        }
    } catch (ex) {
        log.warn "${device.displayName}: json parse failed: ${ex} raw=${json.take(200)}"
    }
}

private onSessResp(byte[] pt) {
    byte[] ln = HexUtils.hexStringToByteArray(state.localNonce)
    byte[] rn = subBytes(pt, 0, 16)
    byte[] gotHmac = subBytes(pt, 16, 48)
    byte[] wantHmac = hmacSha256(keyBytes(), ln)
    if (!bytesEqual(gotHmac, wantHmac)) {
        log.warn "${device.displayName}: handshake rejected, the Local Key is wrong"
        state.phase = "offline"; sendEvent(name:"session", value:"offline")
        return
    }
    state.remoteNonce = HexUtils.byteArrayToHexString(rn)
    // finish
    byte[] finHmac = hmacSha256(keyBytes(), rn)
    sendHex(packFrame(nextSeq(), SESS_FINISH, finHmac, keyBytes()))
    // derive session key: AES-GCM(realKey, iv=ln[:12], XOR(ln,rn))[:16]
    byte[] xored = new byte[16]
    for (int i = 0; i < 16; i++) xored[i] = (byte) (ln[i] ^ rn[i])
    byte[] iv = subBytes(ln, 0, 12)
    byte[] out = gcmEncrypt(keyBytes(), iv, xored, null)   // returns ct||tag
    byte[] sk = subBytes(out, 0, 16)
    state.sessionKey = HexUtils.byteArrayToHexString(sk)
    state.phase = "ready"
    sendEvent(name: "session", value: "ready")
    logd "session ready"
    runIn(10, "heartbeat")
    // flush queued actions (default to a status query)
    def pend = (state.pending ?: [])
    state.pending = []
    if (pend.isEmpty()) pend = [[type: "query"]]
    pend.each { doAction(it) }
}

// ---------- DP → attribute mapping (fill in as DPs are identified) ----------

private updateAttributesFromDps(Map dps) {
    // Log every DP that changed so controls can be mapped by pressing buttons
    // in the SmartLife app and watching the hub log.
    Map last = state.lastDps ?: [:]
    dps.each { k, v ->
        String key = k.toString()
        if (last.containsKey(key) && last[key] != v && txtEnable != false) {
            log.info "${device.displayName}: DP ${key} ${last[key]} → ${v}"
        }
        last[key] = v
    }
    state.lastDps = last
    sendEvent(name: "dps", value: JsonOutput.toJson(last))
    [(DP_CLEAN): "cleaning", (DP_EMPTY): "emptying", (DP_AUTO): "autoMode",
     (DP_INFRARED): "infraredSensor", (DP_CHILD_LOCK): "childLock", (DP_DEODORIZE): "deodorizing"].each { dp, attr ->
        if (last[dp] != null) sendEvent(name: attr, value: last[dp] ? "on" : "off")
    }
    [(DP_CLUMP_MIN): "clumpingTime", (DP_INTERVAL): "cleaningInterval",
     (DP_BIN_CAL): "binCalibration", (DP_LEVELING): "litterLeveling"].each { dp, attr ->
        if (last[dp] != null) sendEvent(name: attr, value: last[dp] as Integer)
    }
}

// ---------- frame pack/unpack ----------

private byte[] packFrame(int seq, int cmd, byte[] payload, byte[] key) {
    byte[] pt = payload
    if (!NO_HDR.contains(cmd)) {
        // prepend version header "3.5" + 12 nulls
        byte[] vh = versionHeader()
        byte[] merged = new byte[vh.length + payload.length]
        copyBytes(vh, 0, merged, 0, vh.length)
        copyBytes(payload, 0, merged, vh.length, payload.length)
        pt = merged
    }
    byte[] iv = new byte[12]
    new SecureRandom().nextBytes(iv)
    int length = 12 + pt.length + 16
    ByteArrayOutputStream hdr = new ByteArrayOutputStream()
    hdr.write(HexUtils.hexStringToByteArray(PREFIX))   // 4
    writeU16(hdr, 0)                                    // 2 unknown
    writeU32(hdr, seq)                                  // 4
    writeU32(hdr, cmd)                                  // 4
    writeU32(hdr, length)                               // 4
    byte[] header = hdr.toByteArray()                   // 18 bytes
    byte[] aad = subBytes(header, 4, 18)      // 14 bytes
    byte[] ctTag = gcmEncrypt(key, iv, pt, aad)         // ct||tag(16)
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    out.write(header)
    out.write(iv)
    out.write(ctTag)
    out.write(HexUtils.hexStringToByteArray(SUFFIX))
    return out.toByteArray()
}

private byte[] unpackFrame(byte[] data, byte[] key) {
    long length = readU32(data, 14)
    byte[] body = subBytes(data, 18, 18 + (int) length)
    byte[] iv = subBytes(body, 0, 12)
    byte[] ctTag = subBytes(body, 12, body.length)   // ct||tag
    byte[] aad = subBytes(data, 4, 18)
    byte[] pt = gcmDecrypt(key, iv, ctTag, aad)
    // strip 4-byte retcode
    if (pt.length >= 4) pt = subBytes(pt, 4, pt.length)
    // strip version header if present
    if (pt.length >= 15 && pt[0] == (byte)'3' && pt[1] == (byte)'.' && pt[2] == (byte)'5')
        pt = subBytes(pt, 15, pt.length)
    return pt
}

// ---------- crypto ----------

// Hubitat's sandbox blocks GCMParameterSpec, so AES-GCM is built by hand from
// AES-ECB (counter mode) plus GHASH for the authentication tag.

private byte[] aesEcb(byte[] key, byte[] block) {
    Cipher c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"))
    return c.doFinal(block)
}

// GF(2^128) multiply, NIST GCM bit ordering
private byte[] gfMul(byte[] x, byte[] y) {
    byte[] z = new byte[16]
    byte[] v = subBytes(y, 0, 16)
    for (int i = 0; i < 128; i++) {
        if ((x[i >> 3] & (0x80 >> (i & 7))) != 0) {
            for (int j = 0; j < 16; j++) z[j] = (byte) (z[j] ^ v[j])
        }
        boolean lsb = (v[15] & 1) != 0
        for (int j = 15; j > 0; j--) v[j] = (byte) (((v[j] & 0xff) >> 1) | ((v[j - 1] & 1) << 7))
        v[0] = (byte) ((v[0] & 0xff) >> 1)
        if (lsb) v[0] = (byte) (v[0] ^ 0xe1)
    }
    return z
}

private byte[] ghash(byte[] h, byte[] aad, byte[] ct) {
    ByteArrayOutputStream d = new ByteArrayOutputStream()
    d.write(aad); d.write(new byte[(16 - aad.length % 16) % 16])
    d.write(ct);  d.write(new byte[(16 - ct.length % 16) % 16])
    writeU32(d, 0); writeU32(d, aad.length * 8L)   // 64-bit AAD length in bits
    writeU32(d, 0); writeU32(d, ct.length * 8L)    // 64-bit ciphertext length in bits
    byte[] data = d.toByteArray()
    byte[] y = new byte[16]
    for (int off = 0; off < data.length; off += 16) {
        for (int j = 0; j < 16; j++) y[j] = (byte) (y[j] ^ data[off + j])
        y = gfMul(y, h)
    }
    return y
}

private void inc32(byte[] c) {
    for (int i = 15; i >= 12; i--) { c[i] = (byte) (c[i] + 1); if (c[i] != 0) break }
}

private byte[] gcmJ0(byte[] iv) {
    byte[] j = new byte[16]
    copyBytes(iv, 0, j, 0, 12)
    j[15] = 1
    return j
}

private byte[] gctr(byte[] key, byte[] j0, byte[] data) {
    byte[] ctr = subBytes(j0, 0, 16)
    inc32(ctr)
    byte[] out = new byte[data.length]
    for (int off = 0; off < data.length; off += 16) {
        byte[] ks = aesEcb(key, ctr)
        int n = (data.length - off < 16) ? (data.length - off) : 16
        for (int j = 0; j < n; j++) out[off + j] = (byte) (data[off + j] ^ ks[j])
        inc32(ctr)
    }
    return out
}

private byte[] gcmEncrypt(byte[] key, byte[] iv, byte[] pt, byte[] aad) {
    byte[] a = (aad != null) ? aad : new byte[0]
    byte[] j0 = gcmJ0(iv)
    byte[] ct = gctr(key, j0, pt)
    byte[] s = ghash(aesEcb(key, new byte[16]), a, ct)
    byte[] ek = aesEcb(key, j0)
    byte[] out = new byte[ct.length + 16]
    copyBytes(ct, 0, out, 0, ct.length)
    for (int i = 0; i < 16; i++) out[ct.length + i] = (byte) (s[i] ^ ek[i])
    return out   // ciphertext || 16-byte tag
}

private byte[] gcmDecrypt(byte[] key, byte[] iv, byte[] ctTag, byte[] aad) {
    byte[] a = (aad != null) ? aad : new byte[0]
    int n = ctTag.length - 16
    byte[] ct = subBytes(ctTag, 0, n)
    byte[] j0 = gcmJ0(iv)
    byte[] s = ghash(aesEcb(key, new byte[16]), a, ct)
    byte[] ek = aesEcb(key, j0)
    int diff = 0
    for (int i = 0; i < 16; i++) diff |= ((s[i] ^ ek[i]) ^ ctTag[n + i]) & 0xff
    if (diff != 0) throw new Exception("GCM tag mismatch")
    return gctr(key, j0, ct)
}

private byte[] hmacSha256(byte[] key, byte[] msg) {
    Mac mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg)
}

// ---------- helpers ----------

// Hubitat's sandbox also blocks java.util.Arrays and System.arraycopy, so
// byte-array copying and comparison are done with plain loops.
private byte[] subBytes(byte[] b, int from, int to) {
    byte[] r = new byte[to - from]
    for (int i = from; i < to; i++) r[i - from] = b[i]
    return r
}
private void copyBytes(byte[] src, int sp, byte[] dst, int dp, int len) {
    for (int i = 0; i < len; i++) dst[dp + i] = src[sp + i]
}
private boolean bytesEqual(byte[] a, byte[] b) {
    if (a.length != b.length) return false
    int diff = 0
    for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]) & 0xff
    return diff == 0
}

private byte[] keyBytes()        { return (settings.localKey ?: "").getBytes("ISO-8859-1") }
private byte[] sessionKeyBytes() { return HexUtils.hexStringToByteArray(state.sessionKey) }
private byte[] versionHeader() {
    byte[] vh = new byte[15]
    vh[0] = (byte)'3'; vh[1] = (byte)'.'; vh[2] = (byte)'5'
    return vh
}
private int nextSeq() { int s = (state.seq ?: 1) as Integer; state.seq = s + 1; return s }

private void writeU16(ByteArrayOutputStream o, int v) { o.write((v>>8)&0xff); o.write(v&0xff) }
private void writeU32(ByteArrayOutputStream o, long v) {
    o.write((int)((v>>24)&0xff)); o.write((int)((v>>16)&0xff))
    o.write((int)((v>>8)&0xff));  o.write((int)(v&0xff))
}
private long readU32(byte[] b, int off) {
    return ((b[off]&0xffL)<<24)|((b[off+1]&0xffL)<<16)|((b[off+2]&0xffL)<<8)|(b[off+3]&0xffL)
}

private void sendHex(byte[] frame) {
    String hex = HexUtils.byteArrayToHexString(frame)
    logd "TX ${hex}"
    interfaces.rawSocket.sendMessage(hex)
}
private void sendFrame(int cmd, String payload) {
    sendHex(packFrame(nextSeq(), cmd, payload.getBytes("UTF-8"), sessionKeyBytes()))
}
private void logd(String m) { if (settings.debugLog) log.debug "${device.displayName}: ${m}" }
