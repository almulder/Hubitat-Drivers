/**
 *  SAS - Samsung Refrigerator
 *
 *  Child of Samsung Appliance Listener. Verified against TP2X_REF_20K
 *  (33 entities).
 *
 *  The fridge has three separate door sensors. `contact` is an aggregate --
 *  open when ANY compartment is open -- so it can drive a normal
 *  contact-sensor automation, while the individual doors stay available.
 *
 *  Attribute names follow the HubiThings Replica Samsung Refrigerator driver
 *  where it has one; see attrRenames().
 *
 *  Version: 0.7.0
 *  Author:  Albert Mulder (almulder)
 *
 *  0.7.0 - needs SAS-Samsung-Appliance-Common 0.7.0
 *    - Debug logging defaults off and turns itself off after 30 minutes.
 *    - Temperatures and setpoints are converted to the hub's scale, and setpoint commands convert back.
 *    - Changing the primary compartment updates temperature and coolingSetpoint straight away.
 *    - setFlexZoneMode and setPantryZoneMode take any mode this fridge offers, not one model's list; setSelectOption for any other select.
 *    - energy is in kWh, Hubitat's EnergyMeter unit; LocalThings' Wh is converted. The other energy readings too.
 *    - setRapidCooling and setRapidFreezing take Off/On like the other drivers; on and off still work.
 *    - A subdevice entity lands on its own subdevice1-prefixed attribute instead of overwriting the main one.
 *    - A compartment door reported on a subdevice still counts toward contact.
 *    - Setpoints are refused, not sent unconverted, until the appliance has reported its unit.
 */

metadata {
    definition(name: "SAS - Samsung Refrigerator", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "ContactSensor"
        capability "TemperatureMeasurement"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Refresh"

        // HubiThings Replica names (Samsung Refrigerator). temperature and
        // coolingSetpoint follow the compartment chosen under Preferences.
        attribute "coolingSetpoint",     "number"
        attribute "rapidCooling",        "string"
        attribute "rapidFreezing",       "string"
        attribute "waterFilterStatus",   "string"
        attribute "waterFilterUsage",    "number"

        // No Replica equivalent (Replica split these across Cavity devices)
        attribute "coolerTemperature",   "number"
        attribute "freezerTemperature",  "number"
        attribute "coolerSetpoint",      "number"
        attribute "freezerSetpoint",     "number"
        attribute "doorCoolerOpen",      "string"
        attribute "doorFreezerOpen",     "string"
        attribute "doorCvroomOpen",      "string"
        attribute "cubedIceEnabled",     "string"
        attribute "iceBitesEnabled",     "string"
        attribute "cubedIceMakingStatus","string"
        attribute "iceBitesMakingStatus","string"
        attribute "autofillPitcher",     "string"
        attribute "sabbathMode",         "string"
        attribute "defrostDelay",        "string"
        attribute "flexZoneMode",        "string"
        attribute "pantryZoneMode",      "string"
        attribute "energyThisMonth",     "number"
        attribute "energyLastMonth",     "number"
        attribute "energySaved",         "number"
        attribute "powerEnergy",         "number"

        attribute "healthStatus", "string"
        attribute "lastUpdate",   "string"

        command "setCoolerSetpoint",  [[name: "Temperature*", type: "NUMBER"]]
        command "setFreezerSetpoint", [[name: "Temperature*", type: "NUMBER"]]
        command "setCoolingSetpoint", [[name: "Temperature*", type: "NUMBER", description: "Sets the compartment chosen under Preferences"]]
        command "setRapidCooling",  [[name: "Rapid Cooling*",  type: "ENUM", constraints: ["Off", "On"]]]
        command "setRapidFreezing", [[name: "Rapid Freezing*", type: "ENUM", constraints: ["Off", "On"]]]
        command "cubedIceOn"
        command "cubedIceOff"
        command "iceBitesOn"
        command "iceBitesOff"
        command "resetWaterFilter"
        // Free text, like every other select: zone modes differ by model, and
        // a fixed dropdown could only list one model's. The listener turns the
        // name back into the code.
        command "setFlexZoneMode", [[name: "Mode*", type: "STRING", description: "See the 'Flex Zone Mode' state variable for this appliance's options"]]
        command "setPantryZoneMode", [[name: "Mode*", type: "STRING", description: "See the 'Pantry Zone Mode' state variable for this appliance's options"]]
        command "setAutofillPitcher", [[name: "Autofill pitcher*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDefrostDelay", [[name: "Defrost delay*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setSabbathMode", [[name: "Sabbath mode*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setSelectOption", [[name: "Entity suffix*", type: "STRING"],
                                    [name: "Option*", type: "STRING"]]
    }

    preferences {
        input name: "primaryTemp", type: "enum", defaultValue: "cooler",
              title: "Which compartment drives 'temperature' and 'coolingSetpoint'",
              options: ["cooler": "Fridge compartment", "freezer": "Freezer compartment"]
        input name: "exposeDiagnostics", type: "bool", defaultValue: false,
              title: "Expose diagnostic entities"
        input name: "logEnable", type: "bool", defaultValue: false, title: "Debug logging",
              description: "Turns itself off after 30 minutes"
        input name: "txtEnable", type: "bool", defaultValue: true, title: "Description text logging"
    }
}

#include almulder.SAS-Samsung-Appliance-Common

/** Library hook: this driver's names -> HubiThings Replica names. */
Map attrRenames() {
    return [
        "rapidFridge" : "rapidCooling",
        "filterStatus": "waterFilterStatus",
        "filterUsage" : "waterFilterUsage"
    ]
}

void installed() { log.info "${device.displayName} installed"; commonInstalled() }
void updated() {
    log.info "${device.displayName} updated"
    commonUpdated()
    followPrimaryCompartment()
}

void uninstalled() { commonUninstalled() }

/**
 * temperature and coolingSetpoint follow the compartment chosen under
 * Preferences. Copy that compartment's current readings across now, rather
 * than leaving the other compartment's until this one next changes -- a
 * settled fridge can go hours without a new reading.
 */
private void followPrimaryCompartment() {
    String c = primaryCompartment()
    def t  = device.currentValue("${c}Temperature")
    def sp = device.currentValue("${c}Setpoint")
    String unit = "°${hubTempScale()}"
    if (t != null)  emit("temperature", t, [unit: unit])
    if (sp != null) emit("coolingSetpoint", sp, [unit: unit])
}

void parseEntity(Map e) {
    beginEntity(e)

    // A subdevice reading gets its scope as a prefix, so it can never
    // overwrite the main attribute of the same name.
    String scope = scopePrefix(e)
    if (scope) {
        if (e.domain == "select") {
            rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
            emitSelect(e, scope)
            return
        }
        String attr = emitGeneric(e, scope)
        // A compartment door on a subdevice still counts toward `contact`.
        if (attr && e.domain == "binary_sensor" && e.suffix?.toString()?.startsWith("door") && !isNullState(e.state)) {
            updateAggregateDoor(attr, openClosed(e.state))
        }
        return
    }
    if (e.domain == "select") {
        rememberOptions((String) e.scope, (String) e.suffix, e.options, e.optionLabels)
        emitSelect(e, "")
        return
    }

    switch (e.suffix) {
        case "door_cooler_open":
        case "door_freezer_open":
        case "door_cvroom_open":
            if (isNullState(e.state)) {
                // Feeding the aggregate a door state we do not have would make
                // `contact` claim closed while the fridge is unreachable.
                if (logEnable) log.debug "${e.suffix}: no reading -- aggregate left alone"
                return
            }
            String doorAttr = snakeToCamel((String) e.suffix)
            String doorVal  = openClosed(e.state)
            emit(doorAttr, doorVal)
            updateAggregateDoor(doorAttr, doorVal)
            return

        case "cooler_temperature":
        case "freezer_temperature":
            emitTemperature(snakeToCamel((String) e.suffix), e)
            if (e.suffix == primaryCompartment() + "_temperature") emitTemperature("temperature", e)
            return

        case "cooler_setpoint":
        case "freezer_setpoint":
            emitTemperature(snakeToCamel((String) e.suffix), e)
            if (e.suffix == primaryCompartment() + "_setpoint") emitTemperature("coolingSetpoint", e)
            return
        case "power":
            if (!isNullState(e.state)) emit("power", coerce(e.state), [unit: e.unit ?: "W"])
            return

        case "energy":
        case "energy_saved":
        case "energy_this_month":
        case "energy_last_month":
        case "power_energy":
            emitEnergy(snakeToCamel((String) e.suffix), e)
            return

        default:
            emitGeneric(e)
    }
}

/**
 * contact = open when ANY compartment is open, so the fridge works as a normal
 * contact sensor in a "door left open" rule while the three individual doors
 * stay available.
 *
 * Door states are tracked in `state`, NOT read back with currentValue().
 * sendEvent() has not committed by the time this runs, so a read-back still
 * returns the PREVIOUS value -- which made the aggregate latch open after the
 * first door was opened and closed, and never clear.
 */
private void updateAggregateDoor(String attr, String value) {
    Map doors = (state.doorStates instanceof Map) ? state.doorStates : [:]
    doors[attr] = value
    state.doorStates = doors
    boolean anyOpen = doors.any { k, v -> v == "open" }
    emit("contact", anyOpen ? "open" : "closed")
}

void setCoolerSetpoint(BigDecimal t)  { haSetTemperature("cooler_setpoint", t) }
void setFreezerSetpoint(BigDecimal t) { haSetTemperature("freezer_setpoint", t) }

/** Replica: the main device's setpoint is the primary compartment's. */
void setCoolingSetpoint(BigDecimal t) { haSetTemperature(primaryCompartment() + "_setpoint", t) }

void setRapidCooling(String v)  { setSwitchOption("rapid_fridge", v) }
void setRapidFreezing(String v) { setSwitchOption("rapid_freezing", v) }

private String primaryCompartment() { return settings?.primaryTemp ?: "cooler" }
void cubedIceOn()     { haTurnOn("cubed_ice_enabled") }
void cubedIceOff()    { haTurnOff("cubed_ice_enabled") }
void iceBitesOn()     { haTurnOn("ice_bites_enabled") }
void iceBitesOff()    { haTurnOff("ice_bites_enabled") }

void resetWaterFilter() { haPress("reset_water_filter") }

void setFlexZoneMode(String v)   { setSelectOption("flex_zone_mode", v) }
void setPantryZoneMode(String v) { setSelectOption("pantry_zone_mode", v) }

// --- settings -----------------------------------------------------------

void setAutofillPitcher(String v) { setSwitchOption("autofill_pitcher", v) }
void setDefrostDelay(String v) { setSwitchOption("defrost_delay", v) }
void setSabbathMode(String v) { setSwitchOption("sabbath_mode", v) }

void refresh() { haRefreshAll() }
