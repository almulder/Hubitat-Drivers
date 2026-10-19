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
 */
import groovy.transform.Field

@Field static final String APP_VERSION = "0.1.0"

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
            paragraph "At this time every day, every item is checked fresh, and anything still in stock " +
                      "(after its alert) sends you a reminder. Leave blank for no daily reminder. " +
                      "Items can also add their own extra reminder intervals."
            input "dailyReminderTime", "time", title: "Daily reminder time", required: false
        }
        section("Logging") {
            input "logDebug", "bool", title: "Debug logging", defaultValue: true
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

def initialize() {
    Map crons = [
        "15" : "0 0/15 * * * ?",
        "30" : "0 0/30 * * * ?",
        "60" : "0 7 * * * ?",
        "180": "0 7 0/3 * * ?"
    ]
    schedule(crons[pollMinutes ?: "30"], "checkAll")
    if (dailyReminderTime) schedule(dailyReminderTime, "dailyReminderRun")
}

def appButtonHandler(String btn) {
    if (btn == "checkAllNow") checkAll()
}

def dailyReminderRun() {
    // Fresh check of every item; each one sends its reminder as soon as its result is in.
    getChildApps().eachWithIndex { child, i ->
        child.dailyCheck(i * 2 + 1)
    }
}

def checkAll() {
    // Stagger each item by 15 seconds so sites aren't hit all at once.
    getChildApps().eachWithIndex { child, i ->
        child.scheduleCheck(i * 15 + 1)
    }
}

// Called by child apps
Boolean isDebug() { return logDebug != false }

String dailyReminderText() {
    if (!dailyReminderTime) return null
    return toDateTime(dailyReminderTime).format("h:mm a", location.timeZone)
}
