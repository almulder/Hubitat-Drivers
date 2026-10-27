/**
 *  Restock Manager (parent app)
 *  © 2026 Albert Mulder. All rights reserved.
 *
 *  Developer retains all rights, title, copyright, and interest, including patent rights and trade
 *  secrets in this software. Developer grants a non-exclusive perpetual license (License) to User to use
 *  this software under this Agreement. However, the User shall make no commercial use of the software without
 *  the Developer's written consent. Software Distribution is restricted and shall be done only with Developer's written approval.
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 *
 *  Watches store products or any web page and notifies you when an item is back in stock,
 *  under your price, or when text on a page appears or disappears. This app holds the list
 *  of watched items (child "Restock Manager Item" apps), the check schedule and the daily
 *  reminder time.
 *
 *  Install BOTH this app and "Restock Manager Item" (or use Hubitat Package Manager),
 *  then add only this one via Apps > Add User App.
 *
 *  0.1.0 - Initial release
 *  0.1.1 - Debug logging now defaults off and turns itself off after 30 minutes. The check
 *          stagger shrinks to fit the polling interval so large lists are always checked.
 *          A scheduled check that lands within 3 minutes of the daily reminder time skips
 *          product items (the daily run already checks them), so sites aren't hit twice and
 *          alerts can't double up. Text items and "Check all items now" are never skipped.
 *          Debug logging left on from an earlier version is also turned off after 30 minutes.
 */
import groovy.transform.Field

@Field static final String APP_VERSION = "0.1.1"

private String copyright() { return "<br>© 2026-" + new Date().format("yyyy") + " Albert Mulder. All rights reserved." }

definition(
    name: "Restock Manager",
    namespace: "almulder",
    author: "Albert Mulder",
    description: "Watch store products or any web page and get notified when stock, price, or page text changes.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    installOnOpen: true,
    importUrl: "https://raw.githubusercontent.com/almulder/Hubitat-Drivers/main/Restock%20Manager/App/Restock%20Manager.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Restock Manager", install: true, uninstall: true) {
        section("Watched items") {
            paragraph "Each item shows its latest status in its name. Open an item to edit it, or use Remove inside it to delete it."
            app(name: "items", appName: "Restock Manager Item", namespace: "almulder",
                title: "Add an item to watch", multiple: true)
        }
        section("Checking") {
            input "pollMinutes", "enum", title: "Check all items every", required: true, defaultValue: "30",
                options: ["15": "15 minutes", "30": "30 minutes", "60": "1 hour", "180": "3 hours"]
            input "checkAllNow", "button", title: "Check all items now"
        }
        section("Daily reminder") {
            paragraph "At this time every day, every product item is checked fresh, and anything still in stock " +
                      "(after its alert) sends you a reminder. Leave blank for no daily reminder. " +
                      "Items can also add their own extra reminder intervals."
            input "dailyReminderTime", "time", title: "Daily reminder time", required: false
        }
        section("Logging") {
            input "logDebug", "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false
        }
        section() {
            paragraph "<small>Restock Manager v${APP_VERSION}${copyright()}</small>"
        }
    }
}

def installed() { initialize() }

def updated() {
    unschedule()
    initialize()
}

def logsOff() {
    log.info "Restock Manager: debug logging turned off"
    app.updateSetting("logDebug", [value: "false", type: "bool"])
    state.remove("debugOnAt")
}

// Debug logging turns itself off 30 minutes after it was turned on. rescheduled is true right
// after unschedule() (save/install), so the remaining time is scheduled again. Otherwise this
// only starts the timer when none is running, e.g. debug left on from an earlier version.
private void scheduleLogsOff(boolean rescheduled) {
    if (logDebug != true) {
        state.remove("debugOnAt")
        return
    }
    if (state.debugOnAt && !rescheduled) return
    if (!state.debugOnAt) state.debugOnAt = now()
    long left = 1800L - (now() - (state.debugOnAt as Long)).intdiv(1000)
    if (left <= 0) logsOff()
    else runIn(left, "logsOff")
}

def initialize() {
    Map crons = [
        "15" : "0 0/15 * * * ?",
        "30" : "0 0/30 * * * ?",
        "60" : "0 7 * * * ?",
        "180": "0 7 0/3 * * ?"
    ]
    schedule(crons[pollMinutes] ?: crons["30"], "scheduledCheckAll")
    if (dailyReminderTime) schedule(dailyReminderTime, "dailyReminderRun")
    scheduleLogsOff(true)
}

def appButtonHandler(String btn) {
    if (btn == "checkAllNow") checkAll()
}

def dailyReminderRun() {
    // Fresh check of every product item; each one sends its reminder as soon as its result
    // is in. Text items have no reminders and keep their regular schedule.
    getChildApps().eachWithIndex { child, i ->
        child.dailyCheck(i * 2 + 1)
    }
}

// The polling schedule. Near the daily reminder time, product items skip this pass because
// the daily run checks them anyway; the window comes from the configured time, so it doesn't
// depend on which of the two jobs fires first.
def scheduledCheckAll() {
    boolean nearDaily = nearDailyReminder()
    if (nearDaily) logD "Scheduled check is near the daily reminder time; product items are left to the daily run"
    checkAll(nearDaily)
}

// Also the "Check all items now" button, which always checks everything. (Installs from
// 0.1.0 keep calling this from their old schedule until the app is next saved.)
def checkAll(Boolean nearDaily = false) {
    scheduleLogsOff(false)
    // Stagger each item by up to 15 seconds so sites aren't hit all at once, but keep the
    // whole pass inside the polling interval so every item is checked before the next one.
    List kids = getChildApps()
    if (!kids) return
    Integer windowSecs = ((pollMinutes ?: "30") as Integer) * 60 - 60
    Integer step = Math.max(1, Math.min(15, (int) (windowSecs / kids.size())))
    kids.eachWithIndex { child, i ->
        child.scheduleCheck(i * step + 1, nearDaily == true)
    }
}

// True when now is within 3 minutes of the daily reminder time (either side, across midnight).
private boolean nearDailyReminder() {
    if (!dailyReminderTime) return false
    Date daily = toDateTime(dailyReminderTime)
    Date nowDate = new Date()
    int dailyMin = (daily.format("H", location.timeZone) as Integer) * 60 + (daily.format("m", location.timeZone) as Integer)
    int nowMin = (nowDate.format("H", location.timeZone) as Integer) * 60 + (nowDate.format("m", location.timeZone) as Integer)
    int diff = Math.abs(nowMin - dailyMin)
    return Math.min(diff, 1440 - diff) <= 3
}

// Called by child apps
Boolean isDebug() { return logDebug == true }

private void logD(String msg) {
    if (logDebug == true) log.debug msg
}

String dailyReminderText() {
    if (!dailyReminderTime) return null
    return toDateTime(dailyReminderTime).format("h:mm a", location.timeZone)
}
