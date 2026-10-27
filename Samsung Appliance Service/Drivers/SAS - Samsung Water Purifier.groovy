/**
 *  SAS - Samsung Water Purifier
 *
 *  Child of SAS - Samsung Appliance Listener.
 *
 *  UNTESTED: generated from upstream's registry source
 *  (`registry/by_type/water_purifier.py`) because none of this hardware was on hand.
 *  Entity inventory at generation time: 3 binary_sensor, 2 number, 5 select, 13 sensor, 5 switch.
 *
 *  Nothing here is specific to one appliance model. Option names and option
 *  lists are resolved per install by the listener, so this driver should show
 *  the right cycles and names on whatever board it meets. An entity upstream
 *  adds later still appears, named from its suffix, via emitGeneric.
 *
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - Subdevice selects are named like every other subdevice reading,
 *      e.g. subdevice1CookingMode, no longer Subdevice1CookingMode.
 *    - Select command descriptions point at the state variable listing the options.
 */

metadata {
    definition(name: "SAS - Samsung Water Purifier", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"

        attribute "alarmInMute",                 "string"   // diagnostic
        attribute "filterDoorStatus",            "string"   // diagnostic
        attribute "pouring",                     "string"
        attribute "dispenseCapacity",            "number"
        attribute "soundVolume",                 "number"
        attribute "dispenseType",                "string"
        attribute "favoriteCapacity",            "string"
        attribute "favoriteHotwaterTemperature", "string"
        attribute "hotWaterTemperature",         "string"
        attribute "soundMode",                   "string"
        attribute "coffeeBrewStatus",            "string"   // diagnostic
        attribute "cupState",                    "string"   // diagnostic
        attribute "filterCleanRemainTime",       "number"   // diagnostic
        attribute "filterStatus",                "string"
        attribute "filterUsage",                 "number"
        attribute "lastPourCapacity",            "number"   // diagnostic
        attribute "lastPourType",                "string"   // diagnostic
        attribute "soundOutput",                 "string"   // diagnostic
        attribute "sterilizeLastTime",           "number"   // diagnostic
        attribute "sterilizePeriod",             "string"   // diagnostic
        attribute "sterilizePlanTime",           "number"   // diagnostic
        attribute "sterilizeRunTime",            "number"   // diagnostic
        attribute "waterpurifierStatus",         "string"   // diagnostic
        attribute "buzzLock",                    "string"
        attribute "coldwaterLock",               "string"
        attribute "favoriteCapacityEnabled",     "string"
        attribute "favoriteCoffeeEnabled",       "string"
        attribute "hotwaterLock",                "string"
        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "setBuzzLock", [[name: "Buzz lock*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setColdwaterLock", [[name: "Coldwater lock*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setFavoriteCapacityEnabled", [[name: "Favorite capacity enabled*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setFavoriteCoffeeEnabled", [[name: "Favorite coffee enabled*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setHotwaterLock", [[name: "Hotwater lock*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDispenseType", [[name: "Dispense type*", type: "STRING", description: "See the 'Dispense Type' state variable for this appliance's options"]]
        command "setFavoriteCapacity", [[name: "Favorite capacity*", type: "STRING", description: "See the 'Favorite Capacity' state variable for this appliance's options"]]
        command "setFavoriteHotwaterTemperature", [[name: "Favorite hotwater temperature*", type: "STRING", description: "See the 'Favorite Hotwater Temperature' state variable for this appliance's options"]]
        command "setHotWaterTemperature", [[name: "Hot water temperature*", type: "STRING", description: "See the 'Hot Water Temperature' state variable for this appliance's options"]]
        command "setSoundMode", [[name: "Sound mode*", type: "STRING", description: "See the 'Sound Mode' state variable for this appliance's options"]]
        command "setDispenseCapacity", [[name: "Dispense capacity*", type: "NUMBER"]]
        command "setSoundVolume", [[name: "Sound volume*", type: "NUMBER"]]
        command "setSelectOption", [[name: "Entity suffix*", type: "STRING"],
                                    [name: "Option*", type: "STRING"]]
    }

    preferences {
        input name: "exposeDiagnostics", type: "bool", defaultValue: false,
              title: "Expose diagnostic entities"
        input name: "logEnable", type: "bool", defaultValue: false, title: "Debug logging",
              description: "Turns itself off after 30 minutes"
        input name: "txtEnable", type: "bool", defaultValue: true, title: "Description text logging"
    }
}

#include almulder.SAS-Samsung-Appliance-Common

/** Library hook: no HubiThings Replica driver to match for this type. */
Map attrRenames() { return [:] }

void installed() { log.info "${device.displayName} installed"; commonInstalled() }

void updated() { log.info "${device.displayName} updated"; commonUpdated() }

void uninstalled() { commonUninstalled() }

void parseEntity(Map e) {
    beginEntity(e)
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, scopePrefix(e))
        return
    }
    if (e.scope != "main") {
        emitGeneric(e, scopePrefix(e))
        return
    }

    switch (e.suffix) {
        // no suffix needs special handling on this type
        default:
            emitGeneric(e)
    }
}

// --- commands ---------------------------------------------------------------

void setBuzzLock(String v) { setSwitchOption("buzz_lock", v) }
void setColdwaterLock(String v) { setSwitchOption("coldwater_lock", v) }
void setFavoriteCapacityEnabled(String v) { setSwitchOption("favorite_capacity_enabled", v) }
void setFavoriteCoffeeEnabled(String v) { setSwitchOption("favorite_coffee_enabled", v) }
void setHotwaterLock(String v) { setSwitchOption("hotwater_lock", v) }
void setDispenseType(String v) { setSelectOption("dispense_type", v) }
void setFavoriteCapacity(String v) { setSelectOption("favorite_capacity", v) }
void setFavoriteHotwaterTemperature(String v) { setSelectOption("favorite_hotwater_temperature", v) }
void setHotWaterTemperature(String v) { setSelectOption("hot_water_temperature", v) }
void setSoundMode(String v) { setSelectOption("sound_mode", v) }
void setDispenseCapacity(BigDecimal v) { haSetNumber("dispense_capacity", v) }
void setSoundVolume(BigDecimal v) { haSetNumber("sound_volume", v) }

void refresh() { haRefreshAll() }
