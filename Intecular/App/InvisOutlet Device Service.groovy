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
 *
 *  Version numbers below are left as originally assigned, not renumbered - a gap (e.g.
 *  1.6.8 jumping to 1.6.13) marks where an entry was removed entirely because it described
 *  an intermediate approach that was tried, real-world tested, found broken, and fully
 *  replaced by a later entry, with none of that code remaining today.
 *
 *  1.1.0 - Initial Working Version
 *  1.2.0 - Added automatic detection of Hubitat's built-in MQTT broker. The "MQTT Broker
 *          Settings" page runs a quick check against this hub's own IP on port 1883,
 *          using the same Listener device's testPort() method (no separate driver or
 *          temporary device needed - the Listener just gets created a step earlier than
 *          before, but isn't connected/configured until later in the wizard as usual).
 *          If detected, a "Use Hubitat's built-in MQTT Server" toggle appears (default
 *          on); when on, the broker URI is filled in automatically and only Username/
 *          Password are shown, with guidance pointing to Integrations > MQTT Export
 *          Integration for the actual generated credentials (not assumed/defaulted,
 *          since they're per-install values, not a fixed value). When off, or when
 *          nothing is detected, the original manual IP/Port/Username/Password fields
 *          are shown as before.
 *  1.2.1 - Fixed "DuplicateDNIException: A device with DNI 'invisoutlet-mqtt-listener'
 *          already exists" if a Listener device from an earlier install/reinstall was
 *          still present. ensureListenerDeviceExists() now falls back to reusing the
 *          existing device on a creation collision instead of failing - it never
 *          deletes or replaces an existing Listener, since real InvisOutlet devices may
 *          already depend on it. (This entry originally also described re-testing the
 *          broker only if the Listener didn't exist yet - that timing logic was fully
 *          reworked by 1.6.7 and no longer applies.)
 *  1.2.2 - The 1.2.1 fallback couldn't actually resolve a genuine orphan (a device with
 *          DNI 'invisoutlet-mqtt-listener' that exists on the hub but isn't a child of
 *          this app instance - there's no way for an app to adopt another app's device by
 *          DNI). Rather than crashing with a raw exception, the credentials page now
 *          catches this and shows a clear message with manual cleanup steps (check
 *          Devices for the orphan, delete it) plus a "try again" toggle.
 *  1.3.0 - Built-in and manual broker modes now use entirely separate, persistent fields
 *          (builtInMqttUser/builtInMqttPassword vs. the original mqttUser/mqttPassword) -
 *          switching the "Use Hubitat's built-in MQTT Server" toggle no longer clears or
 *          guesses at either mode's credentials; each just shows whatever was last saved
 *          for it. Password fields now explicitly redisplay their real saved value via
 *          defaultValue, rather than always appearing blank on a revisit.
 *  1.3.1 - Password fields changed from masked "password" type to plain "text" type, so
 *          the actual characters are visible on screen rather than dots - acceptable
 *          here since the hub itself is already login-protected.
 *  1.3.2 - One-time cleanup for manual mode's username field: before built-in/manual
 *          fields were split apart in 1.3.0, both modes briefly shared a single mqttUser
 *          setting - if that had been set to "hubitat" while testing built-in mode,
 *          manual mode would otherwise inherit that stale leftover now that it owns the
 *          field outright. Cleared once (state.clearedStaleManualUsername), never again
 *          after that, so a legitimate manual username of "hubitat" wouldn't keep getting
 *          wiped on every load.
 *  1.4.0 - Fixed recurring "LimitExceededException: ... generates excessive hub load"
 *          errors on the Pro/Aura devices that kept happening even with the Listener's
 *          own per-attribute throttle in place. Root cause: a Pro unit has ~10 distinct
 *          sensor attributes, each individually throttled to at most once every 1.5-5s -
 *          but nothing capped the COMBINED rate across all of them landing on the same
 *          device driver instance, so bursts of several different attributes updating
 *          within the same second or two could still trip Hubitat's per-device call-rate
 *          governor even though no single attribute was spamming on its own. Added a
 *          global pump/queue in forwardUpdateEntity(): instead of calling the child
 *          device's updateEntity() immediately, updates are queued (keeping only the
 *          latest value per dni+attribute, so a burst never grows unbounded) and drained
 *          one at a time via a repeating runInMillis, guaranteeing real wall-clock spacing
 *          between every single call into any device driver's updateEntity() - a hard
 *          ceiling on total call rate that's independent of how bursty the incoming MQTT
 *          traffic is. forwardUpdateLevel/forwardUpdateColor/forwardUpdateColorTemperature/
 *          forwardButtonEvent are unaffected (call directly) since those fire rarely.
 *  1.5.0 - The device-selection page now actively confirms each discovered device is still
 *          real, using the Listener's probeDevice() - briefly flashing its nightlight/
 *          colorlight on then off and checking for a genuine MQTT echo, one device at a
 *          time. Replaces the old passive "seen in the last 60 seconds" online/offline
 *          check, which couldn't distinguish a device that's simply quiet from one that's
 *          been fully removed from the InvisHome app - that app does not appear to clear a
 *          removed device's retained MQTT discovery message, so it can otherwise keep
 *          showing up as selectable indefinitely. Devices that don't respond are tagged
 *          "NO RESPONSE" rather than hidden outright, since a real device that's simply
 *          powered off or offline right now would look identical to a genuinely-removed
 *          one - it's a strong signal, not a certainty.
 *  1.5.1 - Split device confirmation into its own dedicated wizard page ("Confirm
 *          Devices") between Settings and the device picker, rather than combining the
 *          notice and the actual probing/flashing into a single page. Hubitat has no way
 *          to show live progress within one page's own load, but a real page-to-page
 *          transition works fine - this page just explains what's about to happen, and
 *          clicking its own "Next" is what triggers deviceList() to run the probe loop,
 *          with the browser's normal loading spinner filling that gap naturally.
 *  1.5.2 - Devices that fail an active probe are now hidden from the picker entirely,
 *          rather than shown with a "NO RESPONSE" tag - per feedback that a device known
 *          to no longer be real shouldn't be selectable at all. The zero-devices message
 *          now distinguishes "nothing was discovered on the broker at all" from "some
 *          were discovered but all were hidden after failing their probe", since those
 *          point to different problems. Devices with no nightlight/colorlight entity to
 *          probe (device_class "unsupported") are still shown plainly, since there's no
 *          evidence either way for those - only an explicit failed response hides one.
 *  1.6.0 - Probing was restructured to run as a single round trip instead of a per-device
 *          back-and-forth with the app: the Listener probes its whole device list
 *          internally (probeAllDevices), and this page fires that once, waits once for
 *          the estimated total duration (estimateProbeDurationMs), then reads the
 *          finished results back once via getProbeResults() - a plain method call return
 *          value, not an attribute read, which real testing showed was the only shape
 *          that reliably worked on this platform.
 *  1.6.1 - deviceList() had no cache: since the 'exposed' input uses submitOnChange,
 *          Hubitat re-ran this ENTIRE method (full 3s discovery pause + full re-probe of
 *          every device) on every checkbox click or click-away on that page, which is
 *          what made the UI appear to hang/keep thinking on every interaction. Discovery
 *          + probe results are now cached in state.deviceListCache on first load and
 *          reused for the page's own submitOnChange re-renders; the cache is cleared in
 *          confirmDevices() so a genuine fresh pass through the wizard still re-probes.
 *  1.6.2 - Added a manual "Toggle to rescan for devices" switch on the device list page,
 *          for cases where a real device is missing and the user doesn't want to leave
 *          the page and restart the whole wizard just to force a fresh discovery+probe.
 *          Toggling it bypasses state.deviceListCache for that one page render, then
 *          resets itself back off automatically via app.updateSetting() so it doesn't
 *          stay stuck "on". Note: Hubitat pages render synchronously, so there's no way
 *          to show a live "scanning..." message while leaving the page interactive - the
 *          whole rescan happens within one submit/reload cycle, same as the original
 *          confirmDevices() -> deviceList() transition.
 *  1.6.3 - Found a likely real culprit for "states never populate/update": forwardUpdateLevel()/
 *          forwardUpdateColor()/forwardUpdateColorTemperature() call the child device
 *          DIRECTLY and always worked; forwardUpdateEntity() (switch/binary_sensor/all
 *          sensors) instead queues into state.updateQueue and drains one item per 200ms
 *          via pumpUpdateQueue() - and that's everything that wasn't showing up. Root
 *          suspect: pumpUpdateQueue()'s attrs.remove(attrName) mutates a map nested two
 *          levels under state (state.updateQueue[dni]) with no explicit top-level
 *          reassignment afterward - a known Hubitat gotcha where such a mutation isn't
 *          reliably persisted across the runInMillis scheduling boundary. Added explicit
 *          `state.updateQueue = queue` reassignment after every mutation, in both
 *          forwardUpdateEntity() and pumpUpdateQueue().
 *  1.6.4 - Added markPendingStates(), called in finish() right after each device is
 *          created: asks the Listener's getEntityAttributesFor(dni) which attributes
 *          THIS specific unit actually has (Aura vs Pro differ), then calls the driver's
 *          markPending() directly for each one (bypassing forwardUpdateEntity()/the
 *          update queue entirely, since this is a one-time burst at creation, not
 *          ongoing telemetry) so the driver page shows "pending" immediately instead of
 *          blank. Requires Listener v1.5.2+ and Pro/Aura driver v1.1.1+.
 *  1.6.5 - Found the actual root cause of the queue never draining: real logs showed
 *          dozens of queued updates over several minutes and not one actually drained -
 *          pumpUpdateQueue() was never running at all. Cause: scheduleQueuePump() guarded
 *          itself with `if (state.pumpScheduled) return`, and if a previously-scheduled
 *          runInMillis("pumpUpdateQueue") job was ever invalidated without actually
 *          running (e.g. by an app code save while one was pending - something this
 *          project has done many times while iterating), nothing else would ever reset
 *          that flag back to false, permanently deadlocking every future
 *          scheduleQueuePump() call into a silent no-op. Removed the flag/guard entirely -
 *          runInMillis() already replaces any pending job for the same handler name, so
 *          calling it unconditionally is safe. updated() now also clears the stale flag
 *          from an existing app instance's state and immediately kicks the pump if
 *          anything was already stuck waiting.
 *  1.6.6 - Removed the diagnostic queue logging added while chasing 1.6.5's bug - now
 *          confirmed fixed and no longer needed, and it was spamming the logs on every
 *          single MQTT-driven update. The log.error on an actual forwarding failure is
 *          kept.
 *  1.6.7 - Broker detection on the credentials page no longer stays stuck on a stale
 *          "detected" result forever - e.g. if Hubitat's built-in broker was on during
 *          initial setup but got disabled later. Now auto-retests on a fresh visit to
 *          this page whenever the Listener ISN'T currently holding a live MQTT
 *          connection (safe - no live traffic to risk dropping), and always offers a
 *          manual "Toggle to retest" switch when not detected, which forces a retest
 *          even if something is connected. Cached per page-visit (cleared in about(),
 *          which always runs immediately before this page) so the page's own
 *          submitOnChange re-renders - e.g. toggling the broker guide open - don't
 *          trigger a repeat test every time.
 *  1.6.8 - Fixed the naming-options example text (and the actual device labels in
 *          buildLabel()) not reflecting a toggle's own displayed default until the user
 *          manually touched it at least once. Hubitat's input `defaultValue` only
 *          controls what the toggle visually shows before first interaction - it does
 *          NOT populate settings.xxx itself, which stays null until touched. Both
 *          places now fall back to the same default, but only when truly unset
 *          (!= null), so an explicit false the user chose is never overridden.
 *  1.6.13 - Added a real MQTT connection test, on its own dedicated "connectionTest" page
 *          inserted into the wizard between credentials() and otherSettings(). Several
 *          earlier approaches (a manual test toggle, fingerprint-based result caching,
 *          detecting a "first visit" via a state flag inside credentials() itself) were
 *          tried and abandoned after real testing kept surfacing problems - most
 *          fundamentally, Hubitat's exact page-method invocation timing during
 *          transitions isn't something app code can safely infer from within a single
 *          page's own method. Splitting the test into its own page removes the ambiguity
 *          entirely: connectionTest() is reachable ONLY via a real Next click from the
 *          credentials form, since there's no other way to arrive there. It also avoids
 *          ever setting nextPage to null, which shows Hubitat's "Done"/install button
 *          instead of a normal "Next" - both pages always set nextPage to a real page
 *          name (even "credentials" itself, to send the user back to fix something)
 *          rather than leaving it null. credentials() itself is back to being a plain
 *          form with no test logic at all, clearing any previous test result on its own
 *          submission so a one-character password edit is never silently skipped. On
 *          success, connectionTest() shows a brief confirmation before continuing to
 *          Settings; on failure, it shows the error with a "Next" button that
 *          functionally routes back to credentials() to fix things.
 *  1.6.14 - Moved the "If an MQTT broker is needed" intro plus the Hubitat-built-in and
 *          Raspberry Pi broker guides from the credentials page back to the initial
 *          "about" screen, so credentials() stays focused purely on entering/testing
 *          the actual connection. Added a new "Show Guide: How to enter MQTT Broker
 *          info into InvisOutlet" guide on that same screen, walking through the
 *          InvisHome app's Outlet Settings > Customizations > Advanced > MQTT screen -
 *          explicitly calling out that this has to be repeated for every physical
 *          InvisOutlet device, since it's a per-device setting, not app-wide. Guides are
 *          ordered to match the actual setup sequence (get a broker first, then tell
 *          each InvisOutlet device about it), and the key instructions paragraph is
 *          highlighted in red so it's not missed.
 *  2.0.0 - Changelog cleanup only, no functional changes from 1.6.14: removed several
 *          entries (1.5.3-1.5.5, 1.6.9-1.6.12) that described intermediate approaches to
 *          the device-probing and connection-test features which were tried, real-world
 *          tested, found broken, and fully replaced by later entries - none of that code
 *          exists anymore, so keeping their changelog text around would only mislead
 *          anyone reading this history looking for how the current code actually works.
 *  2.1.0 - Code-audit fixes (requires Listener 2.1.0+ and Pro/Aura 2.1.0+):
 *          - The update queue now lives in memory (a ConcurrentHashMap keyed by
 *            dni+attribute) instead of state. Reading and rewriting the whole queue map
 *            from two threads at once (the Listener's parse thread and the pump) could
 *            lose or re-send updates. The pump is only scheduled when one isn't already
 *            pending, so a steady stream of updates can no longer keep pushing it back
 *            (runInMillis replaces the pending job, which used to starve the drain), and
 *            each tick drains up to QUEUE_BATCH_SIZE items instead of one.
 *          - "pending" placeholders are only stamped on devices that were just CREATED,
 *            never on existing ones when the wizard is re-run, and only on the outlet/
 *            nightlight attributes - not on level, numeric sensors, motion/presence or
 *            buttons. The Listener now replays every known value right after setup, so
 *            the placeholders are replaced within a couple of seconds.
 *          - A device you already have that doesn't answer its probe (e.g. unplugged right
 *            now) is no longer hidden from the picker - it's listed as "NO RESPONSE" and
 *            stays selected. Finish only removes devices that were offered on the picker
 *            and deselected, so an existing device is never deleted just because it was
 *            missing from this scan.
 *          - The Listener-creation error page tells a missing Listener driver apart from
 *            an orphaned Listener device.
 *          - Unit fallbacks match what the Settings page shows (F and in).
 *          - Device removal is wrapped in try/catch so one failure doesn't abort the page,
 *            and a model change that would need a different driver is logged.
 *          - Added getMqttPassword() so the Listener doesn't keep the broker password in
 *            its device state.
 *          - Re-review follow-up: the queue drains in arrival order (FIFO), so a busy
 *            device can't keep another device's update waiting behind it.
 */
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

def appName() { "InvisOutlet Device Service" }
def appVersion() { "2.1.0" }
private def copyright() { return "<br>© 2026-" + new Date().format("yyyy") + " Albert Mulder. All rights reserved." }
private def getImagePath() { return "https://raw.githubusercontent.com/almulder/Hubitat-Drivers/refs/heads/main/Intecular/Pics/" }

// These aren't meaningfully user-configurable - InvisOutlet firmware always uses the
// standard Home Assistant MQTT Discovery prefix, and the filter just keeps the device
// list scoped to InvisOutlet units if the broker is shared with other MQTT devices.
@Field static final String DISCOVERY_PREFIX = "homeassistant"
@Field static final String DEVICE_FILTER = "invis"
@Field static final Integer BUILT_IN_BROKER_PORT = 1883

// How often the update queue drains, in milliseconds, and how many queued updates are
// delivered per tick - together the ceiling on how fast this integration calls into
// device drivers (QUEUE_BATCH_SIZE per QUEUE_DRAIN_INTERVAL_MS). Lower interval / bigger
// batch = more responsive devices but less headroom.
@Field static final Integer QUEUE_DRAIN_INTERVAL_MS = 200
@Field static final Integer QUEUE_BATCH_SIZE = 4

// 2.1.0: pending device updates, keyed by app id, then by "dni|attrName" (latest value
// wins). In memory rather than state: concurrent read-modify-write of a state map from
// the Listener's thread and the pump's thread was losing updates. Lost on a hub reboot
// or code save, which only drops values that are seconds old anyway.
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> UPDATE_QUEUES = new ConcurrentHashMap()
// Arrival order of the keys in UPDATE_QUEUES (each key appears once while queued), so the
// pump drains oldest-first.
@Field static final ConcurrentHashMap<String, ConcurrentLinkedQueue> UPDATE_ORDER = new ConcurrentHashMap()
// When the next pump run is due, per app id - absent when none is pending.
@Field static final ConcurrentHashMap<String, Long> PUMP_DUE = new ConcurrentHashMap()

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
    page(name: "credentials", title: "MQTT Broker Settings", nextPage: "connectionTest")
    page(name: "connectionTest", title: "MQTT Broker Settings", nextPage: "otherSettings")
    page(name: "otherSettings", title: "Settings", nextPage: "confirmDevices")
    page(name: "confirmDevices", title: "Confirm Devices", nextPage: "deviceList")
    page(name: "deviceList", title: "InvisOutlet Devices", content: "deviceList", nextPage: "finish")
    page(name: "finish", title: "Installation Complete", content: "finish", install: true)
}

// ---------------- pages ----------------
def about() {
    // Always runs immediately before credentials() on a fresh pass through the wizard -
    // clearing this here (rather than inside credentials() itself) means a genuine fresh
    // visit always gets an up-to-date broker check, while credentials() can still safely
    // reuse that result across its own submitOnChange re-renders (e.g. toggling the
    // broker guide) without re-testing every single time. Same pattern as
    // confirmDevices()/deviceList()'s state.deviceListCache.
    state.remove("brokerRetestedThisPass")
    dynamicPage(name: "about", title: "", uninstall: true) {
        section("") {
            paragraph "<img src='${getImagePath()}intecular.png' alt='InvisOutlet' style='max-width: 16.5%; height: auto;'/>"
            paragraph pageTitle("About")
            paragraph boldTitle("${appName()} - Version ${appVersion()}")
            paragraph "This app connects your InvisOutlet Aura/Pro smart outlets to Hubitat over an MQTT broker."
            paragraph "<span style='color:red;'>Both Hubitat and your InvisOutlet devices need to be pointed at the same MQTT broker. If you don't already have one, pick whichever guide below fits your situation. Once it's set up, each physical InvisOutlet device also needs to be told how to reach it from the InvisHome phone app (see the last guide below) - this should be done before running discovery in this app, or newly-added devices won't show up yet.</span>"
            paragraph blueTitle("This app is neither developed, endorsed, nor associated with Intecular or InvisOutlet." +
                "<br>Provided 'AS IS', without warranties or conditions of any kind, either expressed or implied.")
        }
        section("") {
            paragraph "<h3>If an MQTT broker is needed</h3>"
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
                paragraph "If detection above found the built-in broker, just toggle 'Use Hubitat's built-in MQTT Server' on and enter the Username/Password shown on that integration's page. Otherwise, use Hubitat's hub IP address and the port/credentials shown there as your MQTT Broker URI."
                paragraph "You'll also need to point your InvisOutlet's own MQTT settings at this same broker (see the last guide below) - same IP, port, and credentials."
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
                paragraph "You'll also need to point your InvisOutlet's own MQTT settings at this same broker (see the last guide below) - same IP, port, and credentials."
                paragraph ""
                paragraph "<span style='color:red;'>** If devices won't connect: double-check the Pi's IP hasn't changed, that port 1883 isn't blocked by a firewall on the Pi (<code>sudo ufw allow 1883</code> if ufw is enabled), and that credentials match exactly on both sides.</span>"
                paragraph ""
            }
        }

        section("") {
            input(name: "showInvisOutletMqttGuide", type: "bool", title: boldTitle("Show Guide: How to enter MQTT Broker info into InvisOutlet"), defaultValue: false, submitOnChange: true)
            if (settings.showInvisOutletMqttGuide) {
                paragraph ""
                paragraph "<u><b style='font-size:20px;'>Entering MQTT Broker Info into InvisOutlet</b></u>"
                paragraph ""
                paragraph "This is done from the InvisHome phone app itself, not from Hubitat or this app."
                paragraph "<b style='color:red;'>This needs to be done separately for every InvisOutlet device you own</b> - it's a per-device setting stored on each unit, not something you configure once for all of them."
                paragraph ""
                paragraph "<b>1. Open the device in InvisHome</b>"
                paragraph "Select the outlet you want to connect, then open its <b>Outlet Settings</b>."
                paragraph ""
                paragraph "<b>2. Find the MQTT settings</b>"
                paragraph "Go to <b>Customizations &gt; Advanced &gt; MQTT</b>."
                paragraph ""
                paragraph "<b>3. Enter your broker details</b>"
                paragraph "Fill in the same broker IP address, port, username, and password you're using (or plan to use) on this app's MQTT Broker Settings page."
                paragraph ""
                paragraph "<b>4. Save, then repeat for every other device</b>"
                paragraph "Save the settings on this outlet, then go back and repeat steps 1-3 for each additional InvisOutlet device before it will show up for discovery here."
                paragraph ""
            }
        }
        section("") {
            paragraph ""
            paragraph boldRedTitle("WARNING: Removing this application will delete every InvisOutlet device it created, along with its MQTT listener device.")
            paragraph boldTitle(copyright())
        }
        section("") {
            input "debugging", "bool", title: boldTitle("Enable debug logging"), defaultValue: false
        }
    }
}


def credentials() {
    // Re-tests the built-in broker automatically on a fresh visit to this page, UNLESS
    // the Listener already holds a live MQTT connection right now - testPort() shares
    // this device's parse() callback with real MQTT messages, so testing against an
    // actively-connected Listener risks a live message arriving during the ~1.5s test
    // window and being silently dropped (see the state.portTestActive guard at the top
    // of the Listener's parse()). If nothing is currently connected - never set up yet,
    // or the broker went away/got disabled since last time - there's no live traffic to
    // protect, so it's safe, and is exactly the case where showing stale "detected"
    // status would be misleading. A manual retest toggle further down always works
    // regardless of connection state, for when the user wants to force it anyway.
    def existingListener = getListenerDevice()
    String listenerError = null
    boolean listenerErrorIsMissingDriver = false
    boolean listenerConnected = existingListener?.currentValue("connectionStatus") == "connected"

    boolean forceRetest = settings.retestBroker == true
    if (forceRetest) {
        app.updateSetting("retestBroker", [type: "bool", value: false])
    }

    boolean shouldTest = forceRetest || (!listenerConnected && !state.brokerRetestedThisPass)

    if (shouldTest) {
        state.brokerRetestedThisPass = true
        try {
            state.brokerDetected = runBrokerDetection()
        } catch (e) {
            // Usually a device with this DNI exists on the hub but isn't a child of this
            // app instance - most likely an orphan left over from an earlier install/
            // reinstall. There's no way for the app to adopt someone else's device by
            // DNI, so this needs a manual cleanup step rather than a silent retry. The
            // other common cause is the Listener driver simply not being installed.
            listenerError = e.message ?: e.toString()
            listenerErrorIsMissingDriver = isMissingDriverError(e)
        }
    } else if (state.brokerDetected == null) {
        // Listener is connected but we've never recorded a detection result (shouldn't
        // normally happen) - infer from settings rather than risk testing a live link.
        state.brokerDetected = (settings.useHubitatMqtt != null)
    }

    if (listenerError) {
        return dynamicPage(name: "credentials", title: pageTitle("MQTT Broker Settings"), nextPage: "credentials", uninstall: false) {
            section("") {
                paragraph boldRedTitle("Could not set up the InvisOutlet MQTT Listener device.")
                if (listenerErrorIsMissingDriver) {
                    paragraph "The <b>InvisOutlet MQTT Listener</b> driver isn't installed on this hub."
                    paragraph "Go to <b>Drivers Code</b>, add the <b>InvisOutlet MQTT Listener</b> driver (and the <b>InvisOutlet Pro</b> and <b>InvisOutlet Aura</b> drivers) and save them - or install the package through Hubitat Package Manager. Then come back here."
                } else {
                    paragraph "A device with the network ID <code>invisoutlet-mqtt-listener</code> already exists on this hub, but isn't linked as a child of this app instance - most likely left over from an earlier install or reinstall."
                    paragraph "Go to <b>Devices</b>, find <b>InvisOutlet MQTT Listener</b>, confirm it's not one you need to keep (check its 'In use by' / parent app), and delete it. Then come back here."
                }
                paragraph "<i style='font-size:0.85em;'>Error detail: ${listenerError}</i>"
                input "retryListenerCheck", "bool", title: boldTitle("I've removed it - try again"), defaultValue: false, submitOnChange: true
                if (settings.retryListenerCheck) {
                    state.remove("brokerDetected")
                    state.remove("brokerRetestedThisPass")
                    app.updateSetting("retryListenerCheck", [value: "false", type: "bool"])
                }
            }
        }
    }

    def hubIp = location.hub.localIP
    boolean useBuiltIn = state.brokerDetected && (settings.useHubitatMqtt != false)

    // One-time cleanup: before built-in/manual fields were split apart, both modes
    // shared a single mqttUser setting - if that got set to "hubitat" while testing
    // built-in mode, manual mode would otherwise inherit that same stale leftover value
    // now that it owns the field outright. Clear it once, never again after that.
    if (!useBuiltIn && !state.clearedStaleManualUsername) {
        if (settings.mqttUser == "hubitat") {
            app.updateSetting("mqttUser", [value: "", type: "string"])
        }
        state.clearedStaleManualUsername = true
    }

    // This page just collects the broker/credential fields - the actual connection
    // test lives entirely on the dedicated connectionTest() page that always follows
    // it. That separation is deliberate: trying to detect "was this specifically a
    // Next click vs. just this page loading" from within a single page's own method
    // turned out to be unreliable (Hubitat's exact page-method invocation timing
    // during transitions isn't something app code can safely infer). Making the test
    // its own distinct page removes that ambiguity entirely - connectionTest() only
    // ever runs because the user actually clicked Next here, since that's the only
    // way to reach it. Every submission of this page clears any previous test result,
    // so connectionTest() always retests fresh - a one-character password edit is
    // never silently skipped.
    state.mqttConnectionTestResult = null

    dynamicPage(name: "credentials", title: pageTitle("MQTT Broker Settings"), uninstall: false) {
        section(sectionTitle("Broker Connection")) {
            if (state.brokerDetected) {
                paragraph "<b style='color:green;'>&#10004; Hubitat's built-in MQTT broker was detected at ${hubIp}:${BUILT_IN_BROKER_PORT}.</b>"
                input "useHubitatMqtt", "bool", title: boldTitle("Use Hubitat's built-in MQTT Server"), defaultValue: true, submitOnChange: true
            } else {
                paragraph "<b style='color:red;'>&#10008; No built-in MQTT broker was detected on this hub.</b> Enter your broker's details below, or see the guides further down this page."
                input "retestBroker", "bool", title: boldTitle("Toggle to retest for Hubitat's built-in MQTT broker"), defaultValue: false, submitOnChange: true
            }

            if (useBuiltIn) {
                paragraph "<b>Username</b> and <b>Password</b> are shown on <b>Integrations &gt; MQTT Export Integration &gt; Use built-in MQTT service</b> on this hub - check there and adjust below as needed."
                input "builtInMqttUser", "text", title: boldTitle("Username"), required: true, defaultValue: (settings.builtInMqttUser ?: "hubitat")
                input "builtInMqttPassword", "text", title: boldTitle("Password"), required: false, defaultValue: settings.builtInMqttPassword
            } else {
                input "mqttBroker", "text", title: boldTitle("MQTT Broker URI (e.g. tcp://192.168.1.50:1883)"), required: true
                input "mqttUser", "text", title: boldTitle("Username (blank if none)"), required: false
                input "mqttPassword", "text", title: boldTitle("Password (blank if none)"), required: false, defaultValue: settings.mqttPassword
            }
            paragraph "<i style='font-size:0.85em;'>Clicking Next will test this connection before continuing - if it fails, you'll be asked to come back and check these settings.</i>"
        }
    }
}

// Dedicated page, entered ONLY via a real "Next" click from credentials() - that's the
// sole purpose of splitting this out as its own page rather than folding the test into
// credentials() itself: it removes any ambiguity about whether this specific invocation
// was a genuine form submission versus just the page loading, since there's no other
// way to arrive here. credentials() always clears state.mqttConnectionTestResult on its
// own submission, so a fresh test always runs here rather than trusting a stale result -
// except when re-clicking Next on this page's OWN success screen (see the guard below),
// which would otherwise force a redundant live reconnect just to advance one more step.
def connectionTest() {
    if (state.mqttConnectionTestResult != "success") {
        state.mqttConnectionTestResult = testMqttConnectionNow() ? "success" : "failed"
    }

    if (state.mqttConnectionTestResult == "success") {
        return dynamicPage(name: "connectionTest", title: pageTitle("MQTT Broker Settings"), nextPage: "otherSettings", uninstall: false) {
            section(sectionTitle("Connection Test")) {
                paragraph "<b style='color:green;'>&#10004; Successfully connected to the MQTT broker.</b>"
                paragraph "Click Next to continue."
            }
        }
    }

    // nextPage points back at "credentials" - this button will say "Next" (Hubitat's
    // standard label), but functionally takes the user back to the form to fix their
    // settings, rather than depending on Hubitat's own automatic Previous-button
    // behavior working the way we'd want in this specific context.
    return dynamicPage(name: "connectionTest", title: pageTitle("MQTT Broker Settings"), nextPage: "credentials", uninstall: false) {
        section(sectionTitle("Connection Test Failed")) {
            paragraph "<b style='color:red;'>&#10008; Could not connect to the MQTT broker with the settings you entered.</b>"
            paragraph "Click Next to go back and double-check the broker address/port and username/password, then try again."
        }
    }
}

def otherSettings() {
    dynamicPage(name: "otherSettings", title: pageTitle("Settings"), uninstall: false) {
        section("<b><u>Naming Options</u></b>") {
            input "namePrefix", "bool", title: boldTitle("Add 'InvisOutlet' prefix?"), defaultValue: false, submitOnChange: true
            input "includeType", "bool", title: boldTitle("Include device type in name?"), defaultValue: true, submitOnChange: true
            // Hubitat's input `defaultValue` only controls what the toggle visually shows
            // before it's ever been touched - it does NOT populate settings.xxx itself, so
            // settings.namePrefix/includeType are actually null on a fresh page load, even
            // though the toggles display as their defaultValue. Reading them directly (as
            // this paragraph used to) meant the example text didn't match what the toggles
            // showed until the user manually flipped each one at least once. Falling back
            // to the same default - but only when truly unset (!= null), so an explicit
            // false from the user is never wrongly overridden - keeps this in sync from
            // the very first load.
            def effectiveNamePrefix = (settings.namePrefix != null) ? settings.namePrefix : false
            def effectiveIncludeType = (settings.includeType != null) ? settings.includeType : true
            def prefix = effectiveNamePrefix ? "InvisOutlet - " : ""
            def suffix = effectiveIncludeType ? " - Pro" : ""
            paragraph ""
            paragraph "Example: ${prefix}Living Room${suffix}"
            paragraph ""
        }
        section("<b><u>Units</u></b>") {
            input "temperatureScale", "enum", title: boldTitle("Temperature Scale"), required: true, options: ["C", "F"], defaultValue: "F"
            input "distanceUnit", "enum", title: boldTitle("Measurement Scale (Distance)"), required: true, options: ["cm", "in"], defaultValue: "in"
        }
    }
}

// Static notice page shown BEFORE any probing happens - Hubitat can't show live progress
// within a single page's own load, but it CAN give a real page-to-page transition: this
// page just explains what's about to happen, and clicking its own "Next" button is what
// triggers deviceList() to actually run the probe loop, with the browser's normal loading
// spinner filling that gap. Keeps the flashing/probing work out of otherSettings entirely.
def confirmDevices() {
    // This page always runs exactly once, immediately before deviceList(), on every
    // fresh pass through the wizard - clearing the cache here (rather than inside
    // deviceList() itself) guarantees a real re-discovery/re-probe each time the user
    // walks through setup again, while letting deviceList() safely reuse its cached
    // results across the repeated submitOnChange re-renders that happen when the user
    // is just clicking checkboxes on that same page.
    state.remove("deviceListCache")
    state.remove("deviceListOffered")
    dynamicPage(name: "confirmDevices", title: pageTitle("Confirm Devices"), uninstall: false) {
        section("") {
            paragraph "Clicking <b>Next</b> will contact each discovered device to actively confirm it's still real and reachable, one at a time - InvisHome does not appear to clear a device's MQTT listing when it's removed from the phone app, so without this check, removed devices could keep showing up as selectable indefinitely."
            paragraph "For each device, its nightlight (or colorlight, on an Aura) will briefly flash on then off - this is how confirmation is done, there's no separate command channel to check on these units."
            paragraph "<i style='font-size:0.85em;'>This takes a few seconds per device - the next page will take a moment to load while this runs.</i>"
        }
    }
}

def deviceList() {
    // A manual "Rescan for devices" toggle lets the user force a fresh discovery+probe
    // pass without leaving this page - e.g. if a device they expect is missing. Reset
    // it back to false as soon as we've captured the intent, so the checkbox doesn't
    // stay stuck "on" once the rescan completes and the page redraws.
    def forceRescan = settings.rescan == true
    if (forceRescan) {
        app.updateSetting("rescan", [type: "bool", value: false])
    }

    def devices
    if (state.deviceListCache != null && !forceRescan) {
        // Reuse the discovery+probe results gathered on this page's first load.
        // Hubitat re-invokes this entire method every time submitOnChange fires on the
        // 'exposed' input below - i.e. on every checkbox click or click-away - and
        // without this cache that was silently re-running the full discovery pause AND
        // re-probing every device from scratch each time, which is what made the page
        // freeze/"keep thinking" on every interaction. The cache is cleared once, in
        // confirmDevices(), so a genuine fresh pass through the wizard still re-probes -
        // and it's also bypassed here on demand via the rescan toggle below.
        devices = state.deviceListCache
    } else {
        def listener = ensureListenerDevice()
        listener.initialize()
        // Retained discovery messages arrive almost immediately on subscribe (observed
        // well under 1 second in testing) - this pause gives it comfortable room, plus
        // a moment to see whether each device is actively publishing right now.
        pauseExecution(3000)

        devices = listener.getDiscoveredDevices() ?: []

        // Actively confirm every discovered device is still real, rather than relying on
        // passive "have we heard anything in the last 60s" timing - that can't tell a device
        // that's simply quiet apart from one that's been fully removed upstream, since
        // InvisHome does not appear to clear a device's retained MQTT discovery message when
        // it's removed from the phone app. Each nightlight/colorlight is briefly flashed
        // on/off and a real state echo is checked for.
        //
        // Probing itself now runs entirely on the Listener (probeAllDevices) - this only
        // fires it ONCE, waits ONCE for the estimated total duration, then reads the
        // finished results back ONCE via a plain method call (getProbeResults(), not
        // currentValue()/an attribute). Two earlier per-device designs that went back and
        // forth with the app mid-sequence both failed in real testing despite the underlying
        // MQTT probing logic working correctly every time - collapsing this down to a
        // single round trip sidesteps whatever was making those attribute reads unreliable.
        def dnis = devices.collect { it.dni }
        def probeResults = [:]
        if (dnis) {
            try {
                def estimatedMs = listener.estimateProbeDurationMs(dnis)
                listener.probeAllDevices(dnis)
                pauseExecution(estimatedMs + 1500)
                probeResults = listener.getProbeResults() ?: [:]
                log.info "Probe results read back: ${probeResults}"
            } catch (e) {
                log.warn "Probing devices failed: ${e}"
            }
        }
        devices.each { d -> d.probeResult = probeResults[d.dni] ?: "no_response" }

        // Cache for reuse across this page's own submitOnChange re-renders.
        state.deviceListCache = devices
    }

    def options = [:]
    devices.each { d ->
        switch (d.probeResult) {
            case "confirmed":
                options[d.dni] = "${d.name} - ${d.model}"
                break
            case "unsupported":
                // Could not actively verify (no nightlight/colorlight entity found for
                // it) - shown plainly rather than hidden, since we have no evidence
                // either way that it's actually gone.
                options[d.dni] = "${d.name} - ${d.model}"
                break
            default:
                if (getChildDevice(d.dni)) {
                    // Already a Hubitat device - it may just be unplugged right now.
                    // Hiding it would drop it from the selection and Finish would then
                    // delete it (and break every rule using it), so it stays listed.
                    options[d.dni] = "${d.name} - ${d.model} (NO RESPONSE - existing device)"
                    log.info "${d.name} (${d.dni}) didn't respond to its probe but is an existing device - keeping it in the list"
                } else {
                    // Failed to respond to an active probe - hidden entirely rather than
                    // shown with a tag, since InvisHome does not appear to clear a removed
                    // device's retained MQTT listing, and a device that fails to respond
                    // is far more likely to have been removed than to be a real unit that's
                    // simply temporarily unreachable.
                    log.info "Hiding ${d.name} (${d.dni}) from the device list - failed to respond to an active probe"
                }
        }
    }
    int count = options.size()
    // Finish only removes devices that were actually offered here and then deselected.
    state.deviceListOffered = options.keySet().collect { it.toString() }

    dynamicPage(name: "deviceList", title: sectionTitle("${count} InvisOutlet device(s) found - select which to add to Hubitat"), uninstall: false) {
        section("") {
            if (count == 0 && devices.isEmpty()) {
                paragraph boldRedTitle("No devices found. Double-check the broker address/credentials on the previous page, or that your InvisOutlet devices are configured to publish to this broker.")
            } else if (count == 0) {
                paragraph boldRedTitle("${devices.size()} device(s) were discovered on the broker, but none responded to an active probe just now, so all were hidden. If you expect a real device here, try again in a moment (it may just be temporarily offline), or check Devices for a stuck InvisOutlet MQTT Listener from an earlier setup attempt.")
            }
            paragraph "Each discovered device's nightlight was just flashed on/off to actively confirm it's still reachable. New devices that didn't respond have been hidden from the list below - most likely they've been removed from the InvisHome app but their old broker listing hasn't been cleared. Devices you've already added are always listed, marked NO RESPONSE if they didn't answer."
            input(name: "exposed", title: "", type: "enum", description: "Click to choose", options: options, multiple: true, submitOnChange: true)
            paragraph "Note: clicking 'Next' will create the selected devices and remove any previously-created InvisOutlet devices listed here that you've deselected."
        }
        section("") {
            paragraph "<i style='font-size:0.85em;'>Don't see a device you expect? Toggle below to check again - this takes a few seconds.</i>"
            input(name: "rescan", type: "bool", title: boldTitle("Toggle to rescan for devices"), submitOnChange: true, defaultValue: false)
        }
    }
}

def finish() {
    def listener = ensureListenerDevice()
    def discovered = listener.getDiscoveredDevices() ?: []
    def keepDnis = []
    def offered = (state.deviceListOffered ?: []) as List

    (settings.exposed ?: []).each { dni ->
        def info = discovered.find { it.dni == dni }
        if (!info) {
            // Keep an existing device even without fresh discovery info - only
            // creating a new one needs it.
            if (getChildDevice(dni)) {
                keepDnis << dni
            } else {
                log.warn "No discovery info found for ${dni} - skipping"
            }
            return
        }
        def result = createInvisOutletDevice(dni, info)
        if (result) {
            keepDnis << dni
            // Only brand-new devices get placeholders - stamping them over an existing
            // device's real values made it look broken until the next state change.
            if (result == "created") markPendingStates(listener, dni)
        }
    }

    getChildDevices().each { cd ->
        def cdDni = cd.deviceNetworkId
        if (cdDni == listenerDni() || keepDnis.contains(cdDni)) return
        if (!offered.contains(cdDni)) {
            // Not on the picker this time at all (not discovered during this scan) -
            // never delete a device the user didn't get the chance to keep.
            log.warn "Keeping ${cd.displayName} - it wasn't discovered during this scan, so it couldn't be offered for selection"
            keepDnis << cdDni
            return
        }
        log.info "Removing ${cd.displayName} (no longer selected)"
        try {
            deleteChildDevice(cdDni)
        } catch (e) {
            log.error "Could not remove ${cd.displayName}: ${e}"
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
    state.remove("pumpScheduled") // no longer used - see scheduleQueuePump()
    state.remove("updateQueue")   // 2.1.0: the queue lives in memory now (UPDATE_QUEUES)
    if (!updateQueue().isEmpty()) scheduleQueuePump()
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
    getChildDevices().each { cd ->
        try {
            deleteChildDevice(cd.deviceNetworkId)
        } catch (e) {
            log.error "Could not remove ${cd.displayName}: ${e}"
        }
    }
}

// ---------------- MQTT broker detection ----------------

// Runs a quick raw-TCP check against this hub's own IP on the built-in broker's typical
// port, using the same Listener device that will handle the real MQTT connection later -
// no separate driver or temporary device needed. This only happens before the Listener's
// initialize()/connect() are ever called for real, so there's no conflict with actual
// MQTT traffic. Result is cached by the caller (credentials()) in state.brokerDetected
// so this only actually runs once per install.
def runBrokerDetection() {
    def listener = ensureListenerDeviceExists()
    def hubIp = location.hub.localIP
    listener.testPort(hubIp, BUILT_IN_BROKER_PORT)
    pauseExecution(1500)
    def result = listener.currentValue("portTestResult")
    return result == "open"
}

// Attempts a real MQTT connect/handshake using whatever broker/credential settings are
// CURRENTLY entered on the credentials page - unlike runBrokerDetection() above (which
// only proves the port is open), this proves the broker actually accepts these exact
// credentials. Reuses ensureListenerDevice(), which both creates the Listener if needed
// and pushes the current effective settings into it via configure(), then forces a
// fresh disconnect/reconnect and waits briefly for the async mqttClientStatus()
// callback to report success or failure. Only ever called from an explicit user action
// (the test toggle on that page), never automatically, since this disrupts whatever
// connection the Listener currently holds.
def testMqttConnectionNow() {
    def listener = ensureListenerDevice()
    try { listener.disconnect() } catch (ignored) { }
    listener.initialize()
    pauseExecution(2500)
    return listener.currentValue("connectionStatus") == "connected"
}

// Resolves the broker URI actually used to configure the Listener - either the
// auto-detected built-in broker, or whatever was entered manually.
def effectiveBrokerUri() {
    boolean useBuiltIn = state.brokerDetected && (settings.useHubitatMqtt != false)
    if (useBuiltIn) {
        return "tcp://${location.hub.localIP}:${BUILT_IN_BROKER_PORT}"
    }
    return settings.mqttBroker
}

// Built-in and manual modes each keep their own separate username/password fields
// (builtInMqttUser/builtInMqttPassword vs. mqttUser/mqttPassword), so toggling between
// them never clears or overwrites the other mode's saved credentials - whichever mode
// is active just resolves to its own set here.
def effectiveMqttUser() {
    boolean useBuiltIn = state.brokerDetected && (settings.useHubitatMqtt != false)
    return useBuiltIn ? settings.builtInMqttUser : settings.mqttUser
}

def effectiveMqttPassword() {
    boolean useBuiltIn = state.brokerDetected && (settings.useHubitatMqtt != false)
    return useBuiltIn ? settings.builtInMqttPassword : settings.mqttPassword
}

// ---------------- listener device management ----------------

def listenerDni() { "invisoutlet-mqtt-listener" }

def getListenerDevice() { getChildDevice(listenerDni()) }

// Creates the Listener device if it doesn't exist yet, without configuring or connecting
// it - safe to call as early as the credentials page (for broker detection), before any
// broker details are known. NEVER deletes or replaces an existing Listener - if one is
// already present (even if getChildDevice() misses it due to a state-commit timing race
// and addChildDevice() collides), the existing device is reused as-is, since real
// InvisOutlet devices may already depend on it.
def ensureListenerDeviceExists() {
    def listener = getListenerDevice()
    if (listener) return listener
    try {
        listener = addChildDevice("almulder", "InvisOutlet MQTT Listener", listenerDni(), [label: "InvisOutlet MQTT Listener"])
        log.info "Created InvisOutlet MQTT Listener device"
    } catch (e) {
        listener = getChildDevice(listenerDni())
        if (!listener) {
            log.error "Could not create or find the InvisOutlet MQTT Listener device: ${e}"
            throw e
        }
        log.warn "InvisOutlet MQTT Listener already existed - reusing it instead of creating a new one."
    }
    return listener
}

def ensureListenerDevice() {
    def listener = ensureListenerDeviceExists()
    listener.configure([
        broker  : effectiveBrokerUri(),
        username: effectiveMqttUser(),
        password: effectiveMqttPassword(),
        prefix  : DISCOVERY_PREFIX,
        filter  : DEVICE_FILTER
    ])
    return listener
}

def removeListenerDevice() {
    def listener = getListenerDevice()
    if (listener) {
        log.info "Removing InvisOutlet MQTT Listener device - no InvisOutlet devices remain"
        try {
            deleteChildDevice(listener.deviceNetworkId)
        } catch (e) {
            log.error "Could not remove the InvisOutlet MQTT Listener device: ${e}"
        }
    }
}

// True when a child-device creation failed because the driver itself isn't installed,
// rather than because of a DNI collision.
private boolean isMissingDriverError(e) {
    def text = "${e?.class?.name} ${e?.message}".toString()
    return text.contains("UnknownDeviceType") || text.toLowerCase().contains("not found")
}

// ---------------- InvisOutlet Pro/Aura device creation ----------------

// Returns "created" for a new device, "updated" for an existing one, or null on failure.
def createInvisOutletDevice(String dni, Map info) {
    def driverType = (info.model == "Aura") ? "InvisOutlet Aura" : "InvisOutlet Pro"
    def cd = getChildDevice(dni)
    if (!cd) {
        try {
            cd = addChildDevice("almulder", driverType, dni, [label: buildLabel(info)])
            log.info "Created ${cd.displayName} using driver '${driverType}'"
        } catch (e) {
            log.error "Failed to create device for ${dni} using driver '${driverType}': ${e}"
            return null
        }
        return "created"
    }
    cd.setLabel(buildLabel(info))
    // An app can't change an existing device's driver, and deleting/recreating it here
    // would break every rule using it - so just point it out.
    if (cd.typeName && cd.typeName != driverType) {
        log.warn "${cd.displayName} uses the '${cd.typeName}' driver but was detected as '${driverType}' - if that's right, change its Type on the device page"
    }
    return "updated"
}

// Asks the Listener exactly which attributes THIS device actually has (Aura vs Pro
// differ) and lets the driver stamp a "pending" placeholder on the ones where that's
// valid (the outlet/nightlight on/off attributes), so a brand-new device's page isn't
// blank. Only called for newly created devices. Calls the driver DIRECTLY rather than
// through the update queue, since it's a one-time handful of calls. The Listener replays
// every known value right after setup, which replaces the placeholders.
def markPendingStates(listener, String dni) {
    def cd = getChildDevice(dni)
    if (!cd) return
    def attrs = listener.getEntityAttributesFor(dni) ?: []
    attrs.each { entry ->
        try {
            cd.markPending(entry.attr, entry.platform, entry.deviceClass)
        } catch (e) {
            log.warn "Could not mark '${entry.attr}' pending on ${dni}: ${e}"
        }
    }
}

def buildLabel(Map info) {
    def rawName = info.name ?: "InvisOutlet"
    // The device's own reported name already starts with "InvisOutlet" (e.g. "InvisOutlet
    // B3E0") - strip that off first so the prefix toggle doesn't produce a duplicated name.
    def baseName = rawName.replaceFirst(/(?i)^invisoutlet\s*/, "").trim()
    if (!baseName) baseName = rawName
    // Same null-vs-default fix as otherSettings()'s example paragraph: settings.xxx is
    // actually null until the user has touched that toggle at least once, even though it
    // visually displays as its defaultValue - so a device created before ever touching
    // "Include device type in name?" (default ON) would otherwise silently be named
    // without the suffix it appeared to promise.
    def effectiveNamePrefix = (settings.namePrefix != null) ? settings.namePrefix : false
    def effectiveIncludeType = (settings.includeType != null) ? settings.includeType : true
    def prefix = effectiveNamePrefix ? "InvisOutlet - " : ""
    def suffix = effectiveIncludeType ? " - ${info.model}" : ""
    return "${prefix}${baseName}${suffix}"
}

// ---------------- called by the Listener to relay MQTT updates to the right device ----------------

// 1.4.0: no longer calls the child device directly. Instead queues the update (keyed by
// dni+attrName so a burst of repeated readings for the same attribute only ever keeps the
// LATEST value, never grows unbounded) and makes sure the drain pump is running. This
// caps the combined rate of calls into device drivers - several different attributes on
// the same device landing in the same narrow window used to stack up past Hubitat's
// per-device call-rate governor.
def forwardUpdateEntity(String dni, String attrName, String platform, deviceClass, value, unit) {
    String key = "${dni}|${attrName}".toString()
    def previous = updateQueue().put(key,
        [dni: dni, attrName: attrName, platform: platform, deviceClass: deviceClass, value: value, unit: unit])
    // Only a newly queued key joins the line - a newer value for one already waiting just
    // replaces it in place.
    if (previous == null) updateOrder().add(key)
    scheduleQueuePump()
}

private Map updateQueue() {
    String key = app.id.toString()
    Map q = UPDATE_QUEUES.get(key)
    if (q == null) {
        UPDATE_QUEUES.putIfAbsent(key, new ConcurrentHashMap())
        q = UPDATE_QUEUES.get(key)
    }
    return q
}

private ConcurrentLinkedQueue updateOrder() {
    String key = app.id.toString()
    ConcurrentLinkedQueue q = UPDATE_ORDER.get(key)
    if (q == null) {
        UPDATE_ORDER.putIfAbsent(key, new ConcurrentLinkedQueue())
        q = UPDATE_ORDER.get(key)
    }
    return q
}

// 2.1.0: only schedules a pump if one isn't already pending. runInMillis() REPLACES a
// pending job for the same handler, so calling it on every update (as 1.6.5 did) kept
// pushing the drain back - with updates arriving more often than every 200ms, it never
// ran at all. PUMP_DUE is in memory, so a code save resets it along with the job it
// tracked (the 1.6.5 deadlock), and a due time more than 2s in the past is treated as a
// lost job and rescheduled.
def scheduleQueuePump() {
    String key = app.id.toString()
    long nowMs = now()
    Long due = PUMP_DUE.get(key)
    if (due != null && due > nowMs - 2000) return
    PUMP_DUE.put(key, nowMs + QUEUE_DRAIN_INTERVAL_MS)
    runInMillis(QUEUE_DRAIN_INTERVAL_MS, "pumpUpdateQueue")
}

// Delivers up to QUEUE_BATCH_SIZE queued updates - across ALL devices, oldest first -
// then reschedules itself if anything is left. Each item is removed atomically before
// it's delivered, so an update that arrives meanwhile for the same attribute is queued
// again rather than lost.
def pumpUpdateQueue() {
    PUMP_DUE.remove(app.id.toString())
    Map queue = updateQueue()
    ConcurrentLinkedQueue order = updateOrder()
    int delivered = 0
    while (delivered < QUEUE_BATCH_SIZE) {
        String key = order.poll()
        if (key == null) break
        def item = queue.remove(key)
        if (item == null) continue
        delivered++
        try {
            getChildDevice(item.dni)?.updateEntity(item.attrName, item.platform, item.deviceClass, item.value, item.unit)
        } catch (e) {
            log.error "Error forwarding '${item.attrName}' to device ${item.dni}: ${e}"
        }
    }
    if (!queue.isEmpty()) {
        // Self-heal: a key whose order entry was lost (e.g. a race around a code save)
        // gets back in line rather than sitting in the map forever.
        if (order.isEmpty()) queue.keySet().each { order.add(it) }
        scheduleQueuePump()
    }
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

// Fallbacks match the defaults the Settings page shows.
def getTemperatureScale() { settings.temperatureScale ?: "F" }
def getDistanceUnit() { settings.distanceUnit ?: "in" }
// Read by the Listener at connect time, so the password isn't stored in its device state.
def getMqttPassword() { effectiveMqttPassword() }
def isDebugEnabled() { settings.debugging == true }

// ---------------- small HTML helpers (page styling) ----------------

String pageTitle(String txt) { return '<h2>' + txt + '</h2>' }
String sectionTitle(String txt) { return '<h3>' + txt + '</h3>' }
String blueTitle(String txt) { return '<span style="color:#0000ff">' + txt + '</span>' }
String boldTitle(String txt) { return '<b>' + txt + '</b>' }
String boldRedTitle(String txt) { return '<span style="color:#ff0000"><b>' + txt + '</b></span>' }
