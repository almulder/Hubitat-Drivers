# Restock Manager for Hubitat (BETA)

Restock Manager watches **store product pages** and **any web page** for you, and sends a notification when something is back in stock, under your price, or when text on a page appears or disappears.

Add as many items as you like, each with its own notification devices, message wording and reminder settings. Remove them when you're done.

> **BETA:** Tested on my setup. Please expect rough edges and report anything you find in the
> [Hubitat community thread](https://community.hubitat.com/t/beta-restock-manager-get-notified-when-products-are-back-in-stock-or-any-web-page-changes/166909).
>
> Restock Manager only **reads** pages and **alerts** you. It never adds anything to a cart or buys anything.
> Provided "AS IS," without warranties of any kind.

---

## What you get

- **Two ways to watch a page**
  - **Product stock & price**: reads the product data most stores publish for search engines (schema.org JSON-LD, Open Graph / product meta tags, or microdata). Alerts when the item is in stock at or below your max price.
  - **Text on the page**: alerts when a phrase you choose appears (for example "Registration is open") or disappears (for example "Sold Out").
- **Verify link & read info**: paste a link and press one button. The page is read immediately, the right mode is chosen, and the **name, SKU and max price** are filled in from the site. Change any of them to override.
- **Your choice of notifications per item**: push / notification devices, speech on speakers (links are left out of spoken messages), and switches to turn on (a light, a virtual switch for Rule Machine, etc.).
- **Your own message wording** with variables: `%name%`, `%price%`, `%status%`, `%text%`, `%url%`, `%time%`.
- **One alert, not a flood**: you get one alert when an item comes in stock, then nothing more until it sells out and comes back (unless you turn on reminders).
- **Optional "reset" message** when an item sells out again or goes above your max price.
- **Optional price-drop alerts** when the price drops further while it's in stock.
- **In-stock reminders**, in case you didn't get to it right away:
  - a **daily reminder time** for everything (set in the main app), and
  - **extra reminders per item** every 5, 10, 15, 30 or 45 minutes, or every 1, 2, 3, 6 or 12 hours.
  - Every reminder **re-checks the page first**, so you're only reminded about things that are actually still in stock.
- **Test button that follows your rules**: it checks the page now and sends exactly what your rules would send to your notification devices and speakers. You get the in-stock message if it's in stock, or the out-of-stock / over-price notice if it isn't. The test never turns on your switches, so it can't set off automations.
- **Status at a glance**: each item's name in the list shows its latest status and price, e.g. `UPS 2U Pro — Out of stock $679.00`.
- **Protection against false alerts**: block pages, CAPTCHA pages and near-empty pages count as failed checks, not as changes. After 6 failures in a row you get one heads-up.
- **Pause** any item without deleting it.

---

## Installing

### Hubitat Package Manager (recommended)

1. In **Hubitat Package Manager**, choose **Install** → **Search by Keywords** and search for **Restock Manager**.
2. Install it. HPM adds both apps.
3. Go to **Apps** → **Add User App** → **Restock Manager**.

### Manual

1. **Apps Code** → **New App** → paste `App/Restock Manager.groovy` → **Save**.
2. **Apps Code** → **New App** → paste `App/Restock Manager Item.groovy` → **Save**.
3. **Apps** → **Add User App** → **Restock Manager**.

Only add **Restock Manager**. Items are added from inside it.

---

## Setting up

### Main app

| Setting | What it does |
|---|---|
| **Check all items every** | 15 minutes, 30 minutes, 1 hour or 3 hours. Items are checked a few seconds apart so sites aren't hit all at once. |
| **Daily reminder time** | At this time every day, every product item is checked fresh, and anything still in stock (after its alert) sends you a reminder. Leave blank for none. A regular check within 3 minutes of this time leaves product items to this run, so they aren't checked twice. Text items and **Check all items now** are never skipped. |
| **Debug logging** | Off by default. When turned on, it switches itself off after 30 minutes. |
| **Check all items now** | Runs a check of every item right away. |

### Adding an item

1. Click **Add an item to watch**.
2. Paste the page link and press **Verify link & read info**.
   - **Product data found**: you'll see the name, price and stock status, and the fields below are filled in. The **max price** is set to the site's current price; change it to your own limit, or clear it to be alerted at any price.
   - **No product data**: the item switches to **Text on the page**. Enter the text to look for and whether to alert when it appears or disappears, then verify again to see if it's on the page right now.
   - **Blocked**: the site refused the hub, so this site won't work (see [Limitations](#limitations)).
3. Choose your notification devices, speakers and/or switches, and write your message.
4. Optionally turn on the reset message, price-drop alerts and reminders.
5. Press **Send test notification** to see exactly what you'd get right now, then **Done**.

### How alerts work

For a product item:

1. **Out of stock**: nothing is sent.
2. **Comes in stock** (at or under your max price): **one** alert. Reminders start if you've set them.
3. **Stays in stock**: no repeat alerts. Only your reminders, and price-drop alerts if turned on.
4. **Sells out or goes above your max price**: reminders stop. You get the reset message if it's turned on.
5. **Comes back in stock**: a new alert, and the cycle repeats.

Text-on-the-page items work the same way: one alert when your condition is met, and an optional message when the page changes back. Reminders are for product items only.

Changing an item's link, mode, SKU or watched text starts it fresh, so it can alert again on the next check. Other changes, including the max price, don't: the next check simply applies the new settings.

If a max price is set and the store shows the item in stock but its price can't be read, the item shows **In stock, price unreadable**. It doesn't alert and doesn't reset (no reset message, reminders stay as they were) until a readable price comes back.

For products with several variants (sizes, colours) and no SKU filter, the lowest-priced variant that's in stock under your max answers, so the result stays the same from check to check.

---

## Limitations

- **Some big retailers block automated requests** (Amazon, Walmart, often Best Buy). The hub gets a block or CAPTCHA page instead of the product. **Verify** tells you up front.
- **Pages that build their content with JavaScript** after loading may not include stock information in what the hub sees.
- **Ubiquiti Store prices** are shown before Ubiquiti's checkout surcharge. Set your max price with that in mind.
- **Reminder timing**: interval reminders are accurate to the minute. Daily reminders go out within seconds of the set time, as each item's check comes back.
- Please be reasonable with check intervals. Checking a store every few minutes for weeks is the kind of thing that gets a hub's IP blocked.

---

## Troubleshooting

- **Item shows "Check failed"**: open **Logs** and look for the item's name. An HTTP 403 or a CAPTCHA/block message means the site is refusing the hub.
- **Item shows "No product data found"**: the page doesn't publish product data. Switch the item to **Text on the page** and watch for a phrase like "Add to Cart" (appears) or "Sold Out" (disappears).
- **"Possibly in stock"**: the store publishes a product and price but no stock status, and the page doesn't say it's sold out. This does **not** trigger an alert or your switches unless you turn on **Treat "possibly in stock" as in stock** for that item. Consider a text watch instead.
- Turn on **Debug logging** in the main app for more detail.

---

## Version history

- **0.1.1**: Fixed a repeat in-stock alert after the first Done on a new item, and alerts repeating when one notification device fails. "Possibly in stock" is now opt-in, and an unreadable price no longer counts as under your max. Better handling of product variants, SKU filtering, prices like 1.299,00 and ranges, and HTML entities. The test button no longer turns on switches. Debug logging defaults off and turns itself off after 30 minutes. Large item lists are always checked within the polling interval, and the daily run no longer doubles up with the regular check. Prices like 1.299 and 1 299,00 now read as thousands.
  - **Upgrading from 0.1.0:**
    - **Re-check your max prices.** The old parser could auto-fill a wrong max price from prices written with a decimal comma (for example 1.299 from "1.299,00").
    - **"Possibly in stock" no longer alerts by default.** If an item relied on it, turn on **Treat "possibly in stock" as in stock** for that item.
    - Open the main app and press **Done** once so the new schedule takes effect.
- **0.1.0**: Initial release.

---

© 2026 Albert Mulder. All rights reserved. Free for personal, non-commercial use; see the license header in each file.
