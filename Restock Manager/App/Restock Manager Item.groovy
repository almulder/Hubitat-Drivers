/**
 *  Restock Manager Item (child app of Restock Manager)
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
 *  Watches one web page in one of two ways:
 *    1. Product stock & price - reads the store's product data (schema.org JSON-LD,
 *       Open Graph / product meta tags, or microdata). Alerts when it's in stock at or
 *       below your max price, with optional price-drop alerts and in-stock reminders.
 *    2. Text on the page - alerts when a phrase appears or disappears.
 *
 *  "Verify link & read info" loads the page right away, detects which mode fits, and fills
 *  in the name, SKU and max price (any of which can be overridden).
 *
 *  0.1.0 - Initial release
 */
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String APP_VERSION = "0.1.0"
@Field static final List IN_STOCK = ["InStock", "LimitedAvailability", "PossiblyInStock"]

definition(
    name: "Restock Manager Item",
    namespace: "almulder",
    author: "Albert Mulder",
    parent: "almulder:Restock Manager",
    description: "One product or web page for Restock Manager to watch.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/almulder/Hubitat-Drivers/main/Restock%20Manager/App/Restock%20Manager%20Item.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    boolean isProduct = (watchType ?: "product") == "product"
    dynamicPage(name: "mainPage", title: "Watched item", install: true, uninstall: true) {
        section("Link") {
            input "productUrl", "text", title: "Page URL", required: true, submitOnChange: true
            input "verifyLink", "button", title: "Verify link & read info"
            if (state.verify) paragraph verifySummary()
        }

        section("What to watch") {
            input "watchType", "enum", title: "Watch for", required: true, defaultValue: "product",
                submitOnChange: true,
                options: ["product": "Product stock & price (store product data)",
                          "text"   : "Text on the page"]
            input "itemName", "text", title: "Display name", required: false
            if (isProduct) {
                input "skuMatch", "text", required: false,
                    title: "SKU or name must contain (filled in automatically; blank = main product on the page)"
                input "maxPrice", "decimal", required: false,
                    title: "Max price — filled in with the site's current price. Change it to set your own limit, or clear it for any price."
                input "alertPriceDrops", "bool", defaultValue: false,
                    title: "Alert again if the price drops further while it's in stock"
            } else {
                input "watchText", "text", title: "Text to look for (not case-sensitive)", required: true
                input "textCondition", "enum", title: "Alert when that text", required: true,
                    defaultValue: "appears",
                    options: ["appears": "Appears on the page", "disappears": "Disappears from the page"]
            }
            input "enabled", "bool", title: "Watching enabled", defaultValue: true
        }

        section("When the alert triggers") {
            input "notifyDevices", "capability.notification", title: "Push/notification devices",
                multiple: true, required: false
            input "speakers", "capability.speechSynthesis", title: "Speak on these speakers",
                multiple: true, required: false
            input "switches", "capability.switch", title: "Turn on these switches (e.g. a light or virtual switch)",
                multiple: true, required: false
            input "alertMsg", "text", title: "Message", required: true,
                defaultValue: isProduct ? "%name% is in stock for \$%price%. %url%" : "%name%: %status%. %url%"
        }

        section("When it resets (optional)") {
            input "notifyReset", "bool", submitOnChange: true, defaultValue: false,
                title: isProduct ? "Also notify when it sells out or goes above your max price" :
                                   "Also notify when the page changes back"
            if (notifyReset) {
                input "resetMsg", "text", title: "Message", required: true,
                    defaultValue: isProduct ? "%name% is no longer available (%status%)." : "%name%: %status%."
            }
        }

        if (isProduct) {
            section("In-stock reminders") {
                paragraph "Reminders only go out after the in-stock alert, and only while it's still in stock. " +
                          "Each reminder re-checks the page first."
                input "dailyReminder", "bool", defaultValue: true,
                    title: "Daily reminder at the main app's time (${parent?.dailyReminderText() ?: 'not set'})"
                input "reminderInterval", "enum", title: "Also remind me", required: false, defaultValue: "0",
                    options: ["0"  : "No extra reminders",
                              "5"  : "Every 5 minutes",  "10" : "Every 10 minutes", "15": "Every 15 minutes",
                              "30" : "Every 30 minutes", "45" : "Every 45 minutes",
                              "60" : "Every hour",       "120": "Every 2 hours",    "180": "Every 3 hours",
                              "360": "Every 6 hours",    "720": "Every 12 hours"]
                input "reminderMsg", "text", title: "Reminder message", required: false,
                    defaultValue: "Reminder: %name% is still in stock for \$%price%. %url%"
            }
        }

        section("Message variables") {
            paragraph "%name% display name, %price% current price, %status% current status, %text% the text you're watching for, " +
                      "%url% page link, %time% time of the check. Links are left out of spoken messages."
        }

        section("Status") {
            String priceText = (isProduct && state.lastPrice != null) ? " — \$${fmtPrice(state.lastPrice)}" : ""
            paragraph "Status: ${humanStatus(state.lastStatus)}${priceText}<br>Last check: ${state.lastCheck ?: 'never'}"
            input "checkNow", "button", title: "Check now"
            input "testNotify", "button", title: "Send test notification (checks the page now and applies your rules)"
            paragraph "<small>Restock Manager Item v${APP_VERSION}</small>"
        }
    }
}

// ---------- Lifecycle ----------

def installed() { initialize() }

def updated() {
    // Changing what's watched starts fresh.
    String sig = "${productUrl}|${watchType}|${skuMatch}|${watchText}|${textCondition}"
    if (state.configSig != sig) {
        state.alerted = false
        state.alertedPrice = null
        state.configSig = sig
    }
    state.remove("lastAlertAt")   // from the earlier reminder version
    initialize()
}

def initialize() {
    if (state.alerted == null) state.alerted = false
    updateStatusLabel()
    // Keep (or stop) the interval reminder loop in line with current settings.
    if (state.alerted && enabled != false) scheduleIntervalReminder()
    else unschedule("intervalReminderCheck")
    if (enabled != false) runIn(3, "checkStock")
}

def appButtonHandler(String btn) {
    switch (btn) {
        case "verifyLink":
            verifyLinkNow()
            break
        case "checkNow":
            checkStock()
            break
        case "testNotify":
            testNotifyNow()
            break
    }
}

// Called by the parent on its schedule
def scheduleCheck(Integer delaySeconds) {
    if (enabled != false) runIn(delaySeconds ?: 1, "checkStock")
}

// Called by the parent at the daily reminder time
def dailyCheck(Integer delaySeconds) {
    if (enabled != false) runIn(delaySeconds ?: 1, "dailyCheckNow")
}

def dailyCheckNow() {
    fetchAsync("daily")
}

def intervalReminderCheck() {
    if (enabled == false || !state.alerted) return
    fetchAsync("interval")
}

private void scheduleIntervalReminder() {
    Integer mins = (reminderInterval ?: "0") as Integer
    if ((watchType ?: "product") != "product" || mins <= 0) {
        unschedule("intervalReminderCheck")
        return
    }
    runIn(mins * 60, "intervalReminderCheck")
}

// ---------- Verify & auto-fill ----------

private void verifyLinkNow() {
    if (!productUrl) {
        state.verify = [error: "Enter a URL first."]
        return
    }
    String html = null
    try {
        html = fetchSync()
    } catch (e) {
        state.verify = [error: "Couldn't load the page: ${e.message}"]
        return
    }
    if (!html) {
        state.verify = [error: "The page came back empty."]
        return
    }

    boolean newUrl = state.verifiedUrl != productUrl
    // On a new link, ignore any old SKU filter so we read the page's main product.
    Map r = parseProduct(html, newUrl ? "" : normalize(skuMatch))
    String title = pageTitle(html)

    if (r.found) {
        state.verify = [type: "product", name: r.name, price: r.price, status: r.status, title: title]
        if (newUrl || watchType == null) app.updateSetting("watchType", [value: "product", type: "enum"])
        if (r.name && (newUrl || !itemName)) app.updateSetting("itemName", [value: r.name, type: "text"])
        if (r.sku && (newUrl || !skuMatch)) app.updateSetting("skuMatch", [value: r.sku, type: "text"])
        if (r.price != null && newUrl) app.updateSetting("maxPrice", [value: r.price, type: "decimal"])
    } else if (looksBlocked(html)) {
        state.verify = [error: "The site returned a block, CAPTCHA, or near-empty page, so the hub can't read it. This site probably won't work."]
        return
    } else {
        Map v = [type: "text", title: title]
        if (newUrl) app.updateSetting("watchType", [value: "text", type: "enum"])
        if (title && (newUrl || !itemName)) app.updateSetting("itemName", [value: title, type: "text"])
        if (watchText) v.textFound = visibleText(html).toLowerCase().contains(watchText.toLowerCase())
        state.verify = v
    }
    state.verifiedUrl = productUrl
}

private String verifySummary() {
    Map v = state.verify
    if (v.error) return "<b>⚠ ${v.error}</b>"
    if (v.type == "product") {
        String price = v.price != null ? "\$${fmtPrice(v.price)}" : "not shown"
        return "<b>✓ Found product data</b><br>Name: ${v.name ?: v.title ?: 'unknown'}<br>Price: ${price}<br>" +
               "Status: ${humanStatus(v.status)}<br>Fields below were filled in from this page; change any of them to override."
    }
    String s = "<b>No product data on this page</b>, so it's set up to watch for text instead.<br>Page title: ${v.title ?: 'none'}"
    if (v.textFound != null) {
        s += "<br>\"${watchText}\" is currently <b>${v.textFound ? 'on' : 'not on'}</b> the page."
    } else {
        s += "<br>Enter the text to look for below, then verify again to test it."
    }
    return s
}

// ---------- Test notification ----------

// Loads the page now, applies this item's current rules, and sends a [TEST]
// message showing exactly what would happen. Does not change alert state.
private void testNotifyNow() {
    String msg
    boolean wouldTrigger = false
    String html = null
    try {
        html = fetchSync()
    } catch (e) {
        msg = "[TEST] ${displayName()}: couldn't load the page (${e.message})."
    }

    if (html != null) {
        Map e = evaluateRules(html)
        state.lastCheck = new Date().format("yyyy-MM-dd h:mm a", location.timeZone)
        if (e.error) {
            msg = "[TEST] ${displayName()}: check failed, ${e.error}."
        } else {
            state.lastStatus = e.r.status
            if ((watchType ?: "product") == "product") state.lastPrice = e.r.price
            updateStatusLabel()
            if (e.triggered) {
                wouldTrigger = true
                msg = "[TEST] " + fillTemplate(alertMsg, e.r)
            } else {
                // Not triggered: send the "resets" message if one is set up,
                // otherwise a plain status notice (e.g. out of stock).
                if (notifyReset && resetMsg) {
                    Map rr = e.r + ((e.r.status in IN_STOCK) ? [status: "AbovePrice"] : [:])
                    msg = "[TEST] " + fillTemplate(resetMsg, rr)
                } else {
                    msg = "[TEST] ${displayName()}: ${e.reason}."
                }
            }
        }
    }
    deliver(msg ?: "[TEST] ${displayName()}: page came back empty.", wouldTrigger)
}

// Applies the current rules to a page without side effects.
private Map evaluateRules(String html) {
    if ((watchType ?: "product") == "text") {
        if (looksBlocked(html)) return [error: "the site returned a block, CAPTCHA, or near-empty page"]
        boolean found = visibleText(html).toLowerCase().contains((watchText ?: "").toLowerCase())
        boolean appears = (textCondition ?: "appears") == "appears"
        boolean triggered = appears ? found : !found
        String reason = appears ? "\"${watchText}\" isn't on the page" : "\"${watchText}\" is still on the page"
        return [r: [price: null, status: found ? "TextFound" : "TextMissing"], triggered: triggered, reason: reason]
    }

    Map r = parseProduct(html, normalize(skuMatch))
    if (!r.found) {
        return [error: looksBlocked(html) ? "the site returned a block, CAPTCHA, or near-empty page" :
                                            "no product data was found on the page"]
    }
    boolean inStock = r.status in IN_STOCK
    boolean priceOk = (maxPrice == null || r.price == null || (r.price as BigDecimal) <= (maxPrice as BigDecimal))
    String reason = null
    if (!inStock) {
        reason = "it's ${humanStatus(r.status).toLowerCase()}" + (r.price != null ? " (\$${fmtPrice(r.price)})" : "")
    } else if (!priceOk) {
        reason = "it's in stock but \$${fmtPrice(r.price)} is above your max of \$${fmtPrice(maxPrice)}"
    }
    return [r: r, triggered: inStock && priceOk, reason: reason]
}

private String fetchSync() {
    String html = null
    httpGet(requestParams()) { resp ->
        def d = resp.data
        html = (d instanceof String) ? d : d?.text
    }
    return html
}

// ---------- Checking ----------

def checkStock() {
    fetchAsync("check")
}

// purpose: "check" (scheduled/manual), "daily" (daily reminder time), "interval" (item reminder)
private void fetchAsync(String purpose) {
    if (!productUrl) return
    logD "Checking ${productUrl} (${purpose})"
    asynchttpGet("handleResponse", requestParams(), [purpose: purpose])
}

def handleResponse(resp, data) {
    String purpose = data?.purpose ?: "check"
    try {
        processResponse(resp, purpose)
    } catch (ex) {
        log.error "${displayName()}: error reading the page: ${ex}"
        state.lastStatus = "Error"
        updateStatusLabel()
        if (purpose == "interval" && state.alerted) scheduleIntervalReminder()
    }
}

private void processResponse(resp, String purpose) {
    state.lastCheck = new Date().format("yyyy-MM-dd h:mm a", location.timeZone)

    if (resp.hasError() || resp.status != 200) {
        handleFailure("HTTP ${resp.status} ${resp.hasError() ? resp.getErrorMessage() : ''}")
        if (purpose == "interval" && state.alerted) scheduleIntervalReminder()   // keep trying
        return
    }
    String html = resp.getData()?.toString() ?: ""

    if ((watchType ?: "product") == "text") {
        if (looksBlocked(html)) {
            handleFailure("blocked, CAPTCHA, or near-empty page")
            return
        }
        state.failCount = 0
        processText(html)
    } else {
        Map r = parseProduct(html, normalize(skuMatch))
        if (!r.found && looksBlocked(html)) {
            handleFailure("blocked, CAPTCHA, or near-empty page")
            if (purpose == "interval" && state.alerted) scheduleIntervalReminder()
            return
        }
        state.failCount = 0
        processProduct(r, purpose)
    }
    updateStatusLabel()
}

private void handleFailure(String reason) {
    state.failCount = (state.failCount ?: 0) + 1
    log.warn "${displayName()}: check failed (${reason}) — failure #${state.failCount}"
    state.lastStatus = "Error"
    updateStatusLabel()
    if (state.failCount == 6) {
        String msg = "Restock Manager can't read the page for ${displayName()} (6 failures in a row). Check the hub logs."
        notifyDevices?.each { it.deviceNotification(msg) }
    }
}

private void processProduct(Map r, String purpose) {
    logD "${displayName()}: parsed ${r}"
    if (!r.found) {
        state.lastStatus = "Unknown"
        log.warn "${displayName()}: no product data found on the page. Try 'Text on the page' mode instead."
        if (purpose == "interval" && state.alerted) scheduleIntervalReminder()
        return
    }
    state.lastStatus = r.status
    state.lastPrice = r.price

    boolean inStock = r.status in IN_STOCK
    boolean priceOk = (maxPrice == null || r.price == null || (r.price as BigDecimal) <= (maxPrice as BigDecimal))

    if (inStock && priceOk) {
        if (!state.alerted) {
            // First time in stock: the main alert, then start the reminder loop.
            sendAlert(alertMsg, r)
            state.alerted = true
            state.alertedPrice = r.price
            scheduleIntervalReminder()
        } else if (alertPriceDrops && r.price != null && state.alertedPrice != null &&
                   (r.price as BigDecimal) < (state.alertedPrice as BigDecimal)) {
            sendAlert(alertMsg, r + [status: "PriceDrop"])
            state.alertedPrice = r.price
            if (purpose == "interval") scheduleIntervalReminder()
        } else if (purpose == "interval") {
            sendReminder(r)
            scheduleIntervalReminder()
        } else if (purpose == "daily" && dailyReminder != false) {
            sendReminder(r)
        }
    } else {
        if (inStock && !priceOk) logD "${displayName()}: in stock but \$${fmtPrice(r.price)} is above max \$${fmtPrice(maxPrice)}"
        if (state.alerted && notifyReset && resetMsg) {
            sendAlert(resetMsg, r + [status: inStock ? "AbovePrice" : r.status])
        }
        state.alerted = false
        state.alertedPrice = null
        unschedule("intervalReminderCheck")   // no reminders while out of stock
    }
}

private void sendReminder(Map r) {
    String tmpl = reminderMsg ?: "Reminder: %name% is still in stock for \$%price%. %url%"
    deliver(fillTemplate(tmpl, r), false)
}

private void processText(String html) {
    boolean found = visibleText(html).toLowerCase().contains((watchText ?: "").toLowerCase())
    boolean triggered = ((textCondition ?: "appears") == "appears") ? found : !found
    state.lastStatus = found ? "TextFound" : "TextMissing"
    logD "${displayName()}: text found=${found}, triggered=${triggered}"

    Map r = [price: null, status: state.lastStatus]
    if (triggered) {
        if (!state.alerted) {
            sendAlert(alertMsg, r)
            state.alerted = true
        }
    } else {
        if (state.alerted && notifyReset && resetMsg) sendAlert(resetMsg, r)
        state.alerted = false
    }
}

// ---------- Page parsing ----------

private Map parseProduct(String html, String needle) {
    Map found = null

    // 1) schema.org JSON-LD
    def slurper = new JsonSlurper()
    def m = (html =~ /(?is)<script[^>]*application\/ld\+json[^>]*>(.*?)<\/script>/)
    while (!found && m.find()) {
        def json
        try {
            json = slurper.parseText(m.group(1).trim())
        } catch (e) {
            continue
        }
        List items = (json instanceof List) ? json :
                     ((json instanceof Map && json["@graph"]) ? (json["@graph"] instanceof List ? json["@graph"] : [json["@graph"]]) : [json])
        List cands = []
        items.each { it ->
            if (it instanceof Map) {
                cands << it
                if (it.hasVariant instanceof List) it.hasVariant.each { v -> if (v instanceof Map) cands << v }
            }
        }
        for (item in cands) {
            String type = item["@type"]?.toString() ?: ""
            if (!type.contains("Product")) continue
            String name = item.name?.toString()
            String sku = item.sku?.toString()
            if (needle && !(normalize(sku).contains(needle) || normalize(name).contains(needle))) continue
            Map offer = pickOffer(item.offers)
            if (!offer && item.hasVariant) continue   // product group: let its variants answer
            def priceRaw = offer ? (offer.price ?: offer.lowPrice ?: specPrice(offer.priceSpecification)) : null
            found = [found : true, name: name, sku: sku,
                     price : parsePrice(priceRaw),
                     status: normalizeAvail(offer?.availability?.toString())]
            break
        }
    }

    // 2) Open Graph / product meta tags and microdata
    Map meta = metaTags(html)
    def metaPrice = parsePrice(meta["product:price:amount"] ?: meta["og:price:amount"] ?: meta["price"])
    String metaAvail = normalizeAvail(meta["product:availability"] ?: meta["og:availability"] ?: meta["availability"])
    if (!found && (metaPrice != null || metaAvail)) {
        found = [found: true, name: meta["og:title"] ?: pageTitle(html), sku: meta["product:retailer_item_id"] ?: meta["sku"],
                 price: metaPrice, status: metaAvail]
    }
    if (!found) return [found: false]

    if (found.price == null) found.price = metaPrice
    if (!found.status) found.status = metaAvail

    // 3) Last resort when no availability is published
    if (!found.status) {
        String lower = visibleText(html).toLowerCase()
        boolean outText = ["out of stock", "sold out", "expected restock", "currently unavailable", "coming soon"].any { lower.contains(it) }
        found.status = outText ? "OutOfStock" : "PossiblyInStock"
    }
    return found
}

private Map pickOffer(def o) {
    if (o instanceof List) return pickOffer(o.find { it instanceof Map })
    if (o instanceof Map && o.offers) return pickOffer(o.offers)
    return (o instanceof Map) ? o : null
}

private def specPrice(def spec) {
    if (spec instanceof List) spec = spec.find { it instanceof Map }
    return (spec instanceof Map) ? spec.price : null
}

private Map metaTags(String html) {
    Map out = [:]
    def m = (html =~ /(?i)<meta\b[^>]*>|<link\b[^>]*itemprop[^>]*>|<[a-z]+\b[^>]*\bitemprop\s*=[^>]*>/)
    while (m.find()) {
        String tag = m.group()
        String key = attr(tag, "property") ?: attr(tag, "name") ?: attr(tag, "itemprop")
        String val = attr(tag, "content") ?: attr(tag, "href")
        if (key && val != null && !out.containsKey(key.toLowerCase())) out[key.toLowerCase()] = val
    }
    return out
}

private String attr(String tag, String name) {
    def m = (tag =~ /(?i)\b${name}\s*=\s*["']([^"']*)["']/)
    return m.find() ? m.group(1) : null
}

private String pageTitle(String html) {
    def m = (html =~ /(?is)<title[^>]*>(.*?)<\/title>/)
    return m.find() ? decode(m.group(1)).replaceAll(/\s+/, " ").trim() : null
}

private String visibleText(String html) {
    return decode((html ?: "")
        .replaceAll(/(?is)<script\b.*?<\/script>/, " ")
        .replaceAll(/(?is)<style\b.*?<\/style>/, " ")
        .replaceAll(/(?s)<[^>]+>/, " "))
        .replaceAll(/\s+/, " ").trim()
}

private String decode(String s) {
    return (s ?: "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
}

private boolean looksBlocked(String html) {
    String vis = visibleText(html)
    String l = vis.toLowerCase()
    return vis.length() < 200 ||
        l.contains("verify you are human") || l.contains("are you a robot") ||
        l.contains("enable javascript and cookies") ||
        (l.contains("access denied") && vis.length() < 3000)
}

private String normalizeAvail(String s) {
    if (!s) return null
    String t = (s.tokenize("/") ? s.tokenize("/").last() : s).toLowerCase().replaceAll(/[^a-z]/, "")
    switch (t) {
        case "instock": case "onlineonly": case "available": return "InStock"
        case "limitedavailability": return "LimitedAvailability"
        case "outofstock": case "oos": case "soldout": case "unavailable": return "OutOfStock"
        case "preorder": case "presale": return "PreOrder"
        case "backorder": return "BackOrder"
        case "discontinued": return "Discontinued"
        case "instoreonly": return "InStoreOnly"
        default: return s.tokenize("/") ? s.tokenize("/").last() : s
    }
}

private BigDecimal parsePrice(def p) {
    if (p == null) return null
    String s = p.toString().replaceAll(/[^0-9.]/, "")
    if (!s) return null
    try {
        return new BigDecimal(s)
    } catch (e) {
        return null
    }
}

// ---------- Notifications & display ----------

private Map requestParams() {
    return [
        uri       : productUrl,
        timeout   : 30,
        textParser: true,
        headers   : [
            "User-Agent"     : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
            "Accept"         : "text/html,application/xhtml+xml",
            "Accept-Language": "en-US,en;q=0.9"
        ]
    ]
}

private void sendAlert(String template, Map r) {
    deliver(fillTemplate(template, r), true)
}

private void deliver(String msg, boolean useSwitches) {
    log.info "${displayName()}: ${msg}"
    notifyDevices?.each { it.deviceNotification(msg) }
    if (speakers) {
        String spoken = msg.replaceAll(/https?:\/\/\S+/, "").trim()
        speakers.each { it.speak(spoken) }
    }
    if (useSwitches) switches?.each { it.on() }
}

private String fillTemplate(String template, Map r) {
    return (template ?: "")
        .replace("%name%", displayName())
        .replace("%price%", r.price != null ? fmtPrice(r.price) : "unknown")
        .replace("%status%", humanStatus(r.status))
        .replace("%text%", watchText ?: "")
        .replace("%url%", productUrl ?: "")
        .replace("%time%", new Date().format("h:mm a", location.timeZone))
}

private void updateStatusLabel() {
    boolean isProduct = (watchType ?: "product") == "product"
    String priceText = (isProduct && state.lastPrice != null) ? " \$${fmtPrice(state.lastPrice)}" : ""
    String paused = (enabled == false) ? " [paused]" : ""
    app.updateLabel("${displayName()} — ${humanStatus(state.lastStatus)}${priceText}${paused}")
}

private String displayName() {
    return itemName ?: "New watched item"
}

private String humanStatus(String s) {
    switch (s) {
        case null: return "not checked"
        case "InStock": return "In stock"
        case "LimitedAvailability": return "In stock (limited)"
        case "PossiblyInStock": return "Possibly in stock"
        case "OutOfStock": return "Out of stock"
        case "BackOrder": return "Backorder"
        case "PreOrder": return "Pre-order"
        case "Discontinued": return "Discontinued"
        case "InStoreOnly": return "In-store only"
        case "PriceDrop": return "Price dropped"
        case "AbovePrice": return "Above your max price"
        case "TextFound": return "\"${watchText}\" is on the page"
        case "TextMissing": return "\"${watchText}\" is not on the page"
        case "Unknown": return "No product data found"
        case "Error": return "Check failed"
        default: return s
    }
}

private String normalize(String s) {
    return (s ?: "").toLowerCase().replaceAll(/[^a-z0-9]/, "")
}

private String fmtPrice(def p) {
    return String.format("%.2f", (p as BigDecimal))
}

private void logD(String msg) {
    if (parent?.isDebug()) log.debug msg
}
