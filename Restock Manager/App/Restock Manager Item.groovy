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
 *  0.1.1 - Fixed a repeat in-stock alert after the first Done on a new item. The alert is now
 *          marked sent before notifying, and one failing device no longer blocks the others or
 *          causes the alert to repeat every check. Overlapping checks run one at a time.
 *          "Possibly in stock" (no stock info published) no longer alerts unless you turn it on.
 *          A price that can't be read no longer counts as under your max price. Product groups
 *          answer from their variants; the SKU filter also applies to meta-tag data. Better
 *          price parsing (1.299,00 and ranges) and HTML entity decoding. The test button no
 *          longer turns on your switches. Message defaults follow the watch mode. Paused items
 *          skip queued checks, the failure heads-up also goes to speakers, and the daily run
 *          skips text items and replaces that item's pending regular check.
 *          In stock with a max price set but a price that can't be read now shows "price
 *          unreadable" and neither alerts nor resets. Variant choice is stable (lowest-priced
 *          qualifying variant) and honours the "possibly in stock" setting. Prices like
 *          1.299 and 1 299,00 read as thousands; more currency entities are decoded; the page
 *          is only stripped to text when that's actually needed.
 */
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String APP_VERSION = "0.1.1"
@Field static final List IN_STOCK = ["InStock", "LimitedAvailability"]
@Field static final String PRODUCT_ALERT = "%name% is in stock for \$%price%. %url%"
@Field static final String TEXT_ALERT    = "%name%: %status%. %url%"
@Field static final String PRODUCT_RESET = "%name% is no longer available (%status%)."
@Field static final String TEXT_RESET    = "%name%: %status%."

// Per-execution cache of the parent's debug flag.
@Field Boolean debugFlag = null

definition(
    name: "Restock Manager Item",
    namespace: "almulder",
    author: "Albert Mulder",
    parent: "almulder:Restock Manager",
    description: "One product or web page for Restock Manager to watch.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/almulder/Hubitat-Drivers/main/Restock%20Manager/App/Restock%20Manager%20Item.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    boolean isProduct = (watchType ?: "product") == "product"
    syncDefaultMessages(isProduct)
    dynamicPage(name: "mainPage", title: "Watched item", install: true, uninstall: true) {
        section("Link") {
            input "productUrl", "text", title: "Page URL", required: true, submitOnChange: true
            input "verifyLink", "button", title: "Verify link & read info"
            // Only show the summary for the link it was made for.
            if (state.verify && state.verify.url == productUrl) paragraph verifySummary()
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
                input "possiblyInStock", "bool", defaultValue: false,
                    title: "Treat \"possibly in stock\" (the store publishes no stock status) as in stock"
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
                defaultValue: isProduct ? PRODUCT_ALERT : TEXT_ALERT
        }

        section("When it resets (optional)") {
            input "notifyReset", "bool", submitOnChange: true, defaultValue: false,
                title: isProduct ? "Also notify when it sells out or goes above your max price" :
                                   "Also notify when the page changes back"
            if (notifyReset) {
                input "resetMsg", "text", title: "Message", required: true,
                    defaultValue: isProduct ? PRODUCT_RESET : TEXT_RESET
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
            input "testNotify", "button", title: "Send test notification (checks the page now and applies your rules; switches aren't turned on)"
            paragraph "<small>Restock Manager Item v${APP_VERSION}</small>"
        }
    }
}

// ---------- Lifecycle ----------

def installed() {
    state.configSig = configSignature()
    initialize()
}

def updated() {
    // Changing what's watched starts fresh.
    String sig = configSignature()
    if (state.configSig != sig) {
        state.alerted = false
        state.alertedPrice = null
        state.failCount = 0
        state.configSig = sig
    }
    state.remove("lastAlertAt")   // from the earlier reminder version
    syncDefaultMessages((watchType ?: "product") == "product")
    initialize()
}

private String configSignature() {
    return "${productUrl}|${watchType}|${skuMatch}|${watchText}|${textCondition}"
}

// If a message still holds the other mode's default wording, swap in this mode's default
// (e.g. Verify switched the item from product to text). Custom wording is left alone.
private void syncDefaultMessages(boolean isProduct) {
    if (alertMsg == (isProduct ? TEXT_ALERT : PRODUCT_ALERT)) {
        app.updateSetting("alertMsg", [value: isProduct ? PRODUCT_ALERT : TEXT_ALERT, type: "text"])
    }
    if (resetMsg == (isProduct ? TEXT_RESET : PRODUCT_RESET)) {
        app.updateSetting("resetMsg", [value: isProduct ? PRODUCT_RESET : TEXT_RESET, type: "text"])
    }
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
            fetchAsync("check")   // manual check works on paused items too
            break
        case "testNotify":
            testNotifyNow()
            break
    }
}

// Called by the parent on its schedule. nearDaily is true when this pass lands within a
// few minutes of the daily reminder time; product items skip it because the daily run
// checks them anyway. Text items aren't part of the daily run, so they always check.
def scheduleCheck(Integer delaySeconds, Boolean nearDaily = false) {
    if (enabled == false) return
    if (nearDaily && (watchType ?: "product") == "product") return
    runIn(delaySeconds ?: 1, "checkStock")
}

// Called by the parent at the daily reminder time. Text items have no reminders, so they
// just keep their regular schedule. For product items the daily fetch is a full check, so
// it replaces any regular check still queued for this item.
def dailyCheck(Integer delaySeconds) {
    if (enabled == false || (watchType ?: "product") != "product") return
    unschedule("checkStock")
    runIn(delaySeconds ?: 1, "dailyCheckNow")
}

def dailyCheckNow() {
    if (enabled == false) return
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
        setVerify([error: "Enter a URL first."])
        return
    }
    String html = null
    try {
        html = fetchSync()
    } catch (e) {
        setVerify([error: "Couldn't load the page: ${e.message}"])
        return
    }
    if (!html) {
        setVerify([error: "The page came back empty."])
        return
    }

    boolean newUrl = state.verifiedUrl != productUrl
    // On a new link, ignore any old SKU filter so we read the page's main product.
    Map r = parseProduct(html, newUrl ? "" : normalize(skuMatch), null)
    String title = pageTitle(html)
    String vis = r.found ? null : visibleText(html)

    if (r.found) {
        setVerify([type: "product", name: r.name, price: r.price, status: r.status, title: title])
        if (newUrl || watchType == null) app.updateSetting("watchType", [value: "product", type: "enum"])
        if (r.name && (newUrl || !itemName)) app.updateSetting("itemName", [value: r.name, type: "text"])
        if (r.sku && (newUrl || !skuMatch)) app.updateSetting("skuMatch", [value: r.sku, type: "text"])
        if (r.price != null && newUrl) app.updateSetting("maxPrice", [value: r.price, type: "decimal"])
    } else if (looksBlocked(vis)) {
        setVerify([error: "The site returned a block, CAPTCHA, or near-empty page, so the hub can't read it. This site probably won't work."])
        return
    } else {
        Map v = [type: "text", title: title]
        if (newUrl) app.updateSetting("watchType", [value: "text", type: "enum"])
        if (title && (newUrl || !itemName)) app.updateSetting("itemName", [value: title, type: "text"])
        if (watchText) v.textFound = vis.toLowerCase().contains(watchText.toLowerCase())
        setVerify(v)
    }
    state.verifiedUrl = productUrl
}

private void setVerify(Map v) {
    v.url = productUrl
    state.verify = v
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
// message showing exactly what would happen. Does not change alert state, and
// doesn't turn on switches, so a test can't set off automations.
private void testNotifyNow() {
    String msg
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
                msg = "[TEST] " + fillTemplate(alertMsg, e.r)
            } else {
                // Not triggered: send the "resets" message if one is set up,
                // otherwise a plain status notice (e.g. out of stock).
                if (notifyReset && resetMsg && !e.priceUnknown) {
                    Map rr = e.r + (isInStock(e.r.status) ? [status: "AbovePrice"] : [:])
                    msg = "[TEST] " + fillTemplate(resetMsg, rr)
                } else {
                    msg = "[TEST] ${displayName()}: ${e.reason}."
                }
            }
        }
    }
    deliver(msg ?: "[TEST] ${displayName()}: page came back empty.", false)
}

// Applies the current rules to a page without side effects.
private Map evaluateRules(String html) {
    if ((watchType ?: "product") == "text") {
        String vis = visibleText(html)
        if (looksBlocked(vis)) return [error: "the site returned a block, CAPTCHA, or near-empty page"]
        boolean found = vis.toLowerCase().contains((watchText ?: "").toLowerCase())
        boolean appears = (textCondition ?: "appears") == "appears"
        boolean triggered = appears ? found : !found
        String reason = appears ? "\"${watchText}\" isn't on the page" : "\"${watchText}\" is still on the page"
        return [r: [price: null, status: found ? "TextFound" : "TextMissing"], triggered: triggered, reason: reason]
    }

    Map r = parseProduct(html, normalize(skuMatch), null)
    if (!r.found) {
        return [error: looksBlocked(visibleText(html)) ? "the site returned a block, CAPTCHA, or near-empty page" :
                                                         "no product data was found on the page"]
    }
    boolean inStock = isInStock(r.status)
    if (priceUnknown(inStock, r.price)) {
        return [r: r + [status: "PriceUnknown"], triggered: false, priceUnknown: true,
                reason: "it's in stock but the price couldn't be read, so your max of \$${fmtPrice(maxPrice)} can't be checked"]
    }
    boolean priceOk = priceWithinMax(r.price)
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

// Scheduled check (from the parent or initialize). Skipped while paused, so a check
// queued before the item was paused doesn't run.
def checkStock() {
    if (enabled == false) return
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
        String vis = visibleText(html)
        if (looksBlocked(vis)) {
            handleFailure("blocked, CAPTCHA, or near-empty page")
            return
        }
        state.failCount = 0
        processText(vis)
    } else {
        // The page is only stripped to text when it's needed: here for the block check,
        // or inside parseProduct for the no-stock-status fallback.
        Map r = parseProduct(html, normalize(skuMatch), null)
        if (!r.found && looksBlocked(visibleText(html))) {
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
        deliver(msg, false)
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
    boolean inStock = isInStock(r.status)
    if (priceUnknown(inStock, r.price)) {
        // In stock but the price can't be read, so the max can't be checked. Don't alert
        // and don't reset: keep the current state until a readable price comes back.
        state.lastStatus = "PriceUnknown"
        state.lastPrice = null
        logD "${displayName()}: in stock but the price couldn't be read; keeping the current alert state"
        if (purpose == "interval" && state.alerted) scheduleIntervalReminder()
        return
    }
    state.lastStatus = r.status
    state.lastPrice = r.price
    boolean priceOk = priceWithinMax(r.price)

    if (inStock && priceOk) {
        if (!state.alerted) {
            // First time in stock: mark it alerted before notifying, so a device that
            // throws can't make the alert repeat on every check. Then start reminders.
            state.alerted = true
            state.alertedPrice = r.price
            sendAlert(alertMsg, r)
            scheduleIntervalReminder()
        } else if (alertPriceDrops && r.price != null && state.alertedPrice != null &&
                   (r.price as BigDecimal) < (state.alertedPrice as BigDecimal)) {
            state.alertedPrice = r.price
            sendAlert(alertMsg, r + [status: "PriceDrop"])
            if (purpose == "interval") scheduleIntervalReminder()
        } else if (purpose == "interval") {
            sendReminder(r)
            scheduleIntervalReminder()
        } else if (purpose == "daily" && dailyReminder != false) {
            sendReminder(r)
        }
    } else {
        if (inStock && !priceOk) logD "${displayName()}: in stock but \$${fmtPrice(r.price)} is above max \$${fmtPrice(maxPrice)}"
        boolean wasAlerted = state.alerted
        state.alerted = false
        state.alertedPrice = null
        if (wasAlerted && notifyReset && resetMsg) {
            sendAlert(resetMsg, r + [status: inStock ? "AbovePrice" : r.status])
        }
        unschedule("intervalReminderCheck")   // no reminders while out of stock
    }
}

private void sendReminder(Map r) {
    String tmpl = reminderMsg ?: "Reminder: %name% is still in stock for \$%price%. %url%"
    deliver(fillTemplate(tmpl, r), false)
}

// vis is the page's visible text (already stripped of tags).
private void processText(String vis) {
    boolean found = vis.toLowerCase().contains((watchText ?: "").toLowerCase())
    boolean triggered = ((textCondition ?: "appears") == "appears") ? found : !found
    state.lastStatus = found ? "TextFound" : "TextMissing"
    logD "${displayName()}: text found=${found}, triggered=${triggered}"

    Map r = [price: null, status: state.lastStatus]
    if (triggered) {
        if (!state.alerted) {
            state.alerted = true
            sendAlert(alertMsg, r)
        }
    } else {
        boolean wasAlerted = state.alerted
        state.alerted = false
        if (wasAlerted && notifyReset && resetMsg) sendAlert(resetMsg, r)
    }
}

// ---------- Page parsing ----------

// vis is the page's visible text if the caller already has it, or null to strip the page
// only if the no-stock-status fallback needs it.
private Map parseProduct(String html, String needle, String vis) {
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
        for (item in items) {
            if (!(item instanceof Map)) continue
            found = productFrom((Map) item, needle)
            if (found) break
        }
    }

    // 2) Open Graph / product meta tags and microdata (only scanned when JSON-LD left gaps)
    if (found && found.price != null && found.status) return found
    Map meta = metaTags(html)
    def metaPrice = parsePrice(meta["product:price:amount"] ?: meta["og:price:amount"] ?: meta["price"])
    String metaAvail = normalizeAvail(meta["product:availability"] ?: meta["og:availability"] ?: meta["availability"])
    if (!found && (metaPrice != null || metaAvail)) {
        String metaName = meta["og:title"] ?: pageTitle(html)
        String metaSku = meta["product:retailer_item_id"] ?: meta["sku"]
        // With a SKU filter set, page-level data only counts if it's that product.
        if (needle && !(normalize(metaSku).contains(needle) || normalize(metaName).contains(needle))) return [found: false]
        found = [found: true, name: metaName, sku: metaSku, price: metaPrice, status: metaAvail]
    }
    if (!found) return [found: false]

    if (found.price == null) found.price = metaPrice
    if (!found.status) found.status = metaAvail

    // 3) Last resort when no availability is published
    if (!found.status) {
        String lower = (vis != null ? vis : visibleText(html)).toLowerCase()
        boolean outText = ["out of stock", "sold out", "expected restock", "currently unavailable", "coming soon"].any { lower.contains(it) }
        found.status = outText ? "OutOfStock" : "PossiblyInStock"
    }
    return found
}

// One JSON-LD item -> product result, or null. A product group with variants answers from
// its variants, falling back to its own offer. See pickVariant for which variant wins.
private Map productFrom(Map item, String needle) {
    List variants = (item.hasVariant instanceof List) ? item.hasVariant.findAll { it instanceof Map } : []
    if (variants) {
        List hits = variants.collect { productFrom((Map) it, needle) }.findAll { it }
        if (hits) return pickVariant(hits)
    }
    String type = item["@type"]?.toString() ?: ""
    if (!type.contains("Product")) return null
    String name = item.name?.toString()
    String sku = item.sku?.toString()
    if (needle && !(normalize(sku).contains(needle) || normalize(name).contains(needle))) return null
    Map offer = pickOffer(item.offers)
    if (!offer && variants) return null   // product group with no usable variant or offer
    def priceRaw = offer ? firstPresent([offer.price, offer.lowPrice, specPrice(offer.priceSpecification)]) : null
    return [found : true, name: name, sku: sku,
            price : parsePrice(priceRaw),
            status: normalizeAvail(offer?.availability?.toString())]
}

// Prefer the lowest-priced variant that's in stock and under the max, then the lowest-priced
// in-stock one, then the first. Picking by price keeps the choice stable between checks, so
// a different variant answering doesn't look like a price drop.
private Map pickVariant(List hits) {
    Closure byPrice = { a, b ->
        if (a.price == null) return (b.price == null) ? 0 : 1
        if (b.price == null) return -1
        return (a.price as BigDecimal) <=> (b.price as BigDecimal)
    }
    List qualifying = hits.findAll { isInStock(it.status) && priceWithinMax(it.price) }
    if (qualifying) return qualifying.sort(false, byPrice)[0]
    List inStock = hits.findAll { isInStock(it.status) }
    if (inStock) return inStock.sort(false, byPrice)[0]
    return hits[0]
}

// First value that isn't null or blank (a real price of 0 counts).
private def firstPresent(List vals) {
    return vals.find { it != null && it.toString().trim() != "" }
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
    // Not preceded by "-" or a word char, so name= doesn't match data-name=. Each quote
    // style is matched on its own, so content="Joe's Widget" is read whole.
    def m = (tag =~ /(?i)(?<![-\w])${name}\s*=\s*(?:"([^"]*)"|'([^']*)')/)
    if (!m.find()) return null
    return m.group(1) != null ? m.group(1) : m.group(2)
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

@Field static final Map ENTITIES = [
    "&nbsp;": " ", "&quot;": "\"", "&apos;": "'", "&lt;": "<", "&gt;": ">",
    "&rsquo;": "'", "&lsquo;": "'", "&rdquo;": "\"", "&ldquo;": "\"",
    "&ndash;": "-", "&mdash;": "-", "&hellip;": "...", "&trade;": "\u2122", "&reg;": "\u00AE", "&copy;": "\u00A9",
    "&euro;": "\u20AC", "&pound;": "\u00A3", "&yen;": "\u00A5", "&cent;": "\u00A2", "&curren;": "\u00A4",
    "&deg;": "\u00B0", "&times;": "x", "&frac12;": "1/2", "&thinsp;": " ", "&ensp;": " ", "&emsp;": " "
]

private String decode(String s) {
    String out = (s ?: "")
    ENTITIES.each { k, v -> out = out.replace(k, v) }
    out = out.replaceAll(/&#[xX]([0-9a-fA-F]{1,6});/) { String all, String hex -> codePointText(Integer.parseInt(hex, 16)) }
    out = out.replaceAll(/&#(\d{1,7});/) { String all, String dec -> codePointText(Integer.parseInt(dec)) }
    return out.replace("&amp;", "&")   // last, so "&amp;lt;" stays "&lt;"
}

private String codePointText(int cp) {
    // Curly quotes become plain ones so watched text like "Don't" still matches.
    if (cp == 0x2018 || cp == 0x2019) return "'"
    if (cp == 0x201C || cp == 0x201D) return "\""
    if (cp == 0xA0) return " "
    if (cp <= 0 || cp > 0xFFFF) return " "
    return ((char) cp).toString()
}

// vis is the page's visible text (see visibleText).
private boolean looksBlocked(String vis) {
    vis = vis ?: ""
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
    if (p instanceof Number) return p as BigDecimal
    // First number in the text, so a range like "19.99 - 29.99" reads as its low end. Space
    // or no-break-space thousands groups ("1 299,00") are read as one number.
    def m = (p.toString() =~ /\d{1,3}(?:[ \u00A0\u202F]\d{3})+(?:[.,]\d+)?|\d[\d.,]*/)
    if (!m.find()) return null
    String s = m.group().replaceAll(/[ \u00A0\u202F]/, "").replaceAll(/[.,]+$/, "")
    int dot = s.lastIndexOf(".")
    int comma = s.lastIndexOf(",")
    if (dot >= 0 && comma >= 0) {
        // Both present: whichever comes last is the decimal point (1,299.00 / 1.299,00).
        s = (comma > dot) ? s.replace(".", "").replace(",", ".") : s.replace(",", "")
    } else if (comma >= 0) {
        // Only commas: thousands groups (1,299) or a decimal comma (12,99).
        s = (s ==~ /\d{1,3}(,\d{3})+/) ? s.replace(",", "") : s.replace(",", ".")
    } else if (s ==~ /\d{1,3}(\.\d{3})+/) {
        s = s.replace(".", "")   // dot thousands groups: 1.299 / 1.299.000
    }
    try {
        return new BigDecimal(s)
    } catch (e) {
        return null
    }
}

private boolean isInStock(String status) {
    return status in IN_STOCK || (status == "PossiblyInStock" && possiblyInStock == true)
}

// In stock with a max price set, but no readable price: the max can't be checked.
private boolean priceUnknown(boolean inStock, def price) {
    return inStock && maxPrice != null && price == null
}

// With a max price set, the price has to be known and at or under it.
private boolean priceWithinMax(def price) {
    if (maxPrice == null) return true
    return price != null && (price as BigDecimal) <= (maxPrice as BigDecimal)
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

// Each device is tried on its own, so one that fails doesn't stop the rest.
private void deliver(String msg, boolean useSwitches) {
    log.info "${displayName()}: ${msg}"
    notifyDevices?.each { d -> tryDevice(d, "notify") { d.deviceNotification(msg) } }
    if (speakers) {
        String spoken = msg.replaceAll(/https?:\/\/\S+/, "").trim()
        speakers.each { d -> tryDevice(d, "speak on") { d.speak(spoken) } }
    }
    if (useSwitches) switches?.each { d -> tryDevice(d, "turn on") { d.on() } }
}

private void tryDevice(d, String what, Closure action) {
    try {
        action()
    } catch (e) {
        log.warn "${displayName()}: couldn't ${what} ${d}: ${e}"
    }
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
        case "PriceUnknown": return "In stock, price unreadable"
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
    if (debugFlag == null) debugFlag = (parent?.isDebug() == true)
    if (debugFlag) log.debug msg
}
