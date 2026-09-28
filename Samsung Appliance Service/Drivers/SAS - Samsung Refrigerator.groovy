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
 *  Version: 0.6.0
 *  Author:  Albert Mulder (almulder)
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
        command "setRapidCooling",  [[name: "Rapid Cooling*",  type: "ENUM", constraints: ["on", "off"]]]
        command "setRapidFreezing", [[name: "Rapid Freezing*", type: "ENUM", constraints: ["on", "off"]]]
        command "cubedIceOn"
        command "cubedIceOff"
        command "iceBitesOn"
        command "iceBitesOff"
        command "resetWaterFilter"
        command "setFlexZoneMode", [[name: "Mode*", type: "STRING", description: "See the 'Flex Zone Mode' state variable for this appliance's options"]]
        command "setPantryZoneMode", [[name: "Mode*", type: "STRING", description: "See the 'Pantry Zone Mode' state variable for this appliance's options"]]
        command "setAutofillPitcher", [[name: "Autofill pitcher*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setDefrostDelay", [[name: "Defrost delay*", type: "ENUM", constraints: ["Off", "On"]]]
        command "setSabbathMode", [[name: "Sabbath mode*", type: "ENUM", constraints: ["Off", "On"]]]
        command "startDiagnosis"
        command "setSelectOption", [[name: "Entity suffix*", type: "STRING"],
                                    [name: "Option*", type: "STRING"]]
    }

    preferences {
        input name: "primaryTemp", type: "enum", defaultValue: "cooler",
              title: "Which compartment drives 'temperature' and 'coolingSetpoint'",
              options: ["cooler": "Fridge compartment", "freezer": "Freezer compartment"]
        input name: "exposeDiagnostics", type: "bool", defaultValue: false,
              title: "Expose diagnostic entities"
        input name: "logEnable", type: "bool", defaultValue: true, title: "Debug logging"
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

void installed() { log.info "${device.displayName} installed" }
void updated() { log.info "${device.displayName} updated" }

void parseEntity(Map e) {
    rememberEntity((String) e.scope, (String) e.suffix, (String) e.entityId)
    noteEntityHealth(e)
    if (logEnable) log.debug "entity ${e.entityId} (${e.suffix}) = ${e.state}"
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
            if (isNullState(e.state)) return
            String attr = snakeToCamel((String) e.suffix)
            def v = coerce(e.state)
            emit(attr, v, [unit: "°${location.temperatureScale}"])
            String want = primaryCompartment() + "_temperature"
            if (e.suffix == want) emit("temperature", v, [unit: "°${location.temperatureScale}"])
            return

        case "cooler_setpoint":
        case "freezer_setpoint":
            if (isNullState(e.state)) return
            def sp = coerce(e.state)
            emit(snakeToCamel((String) e.suffix), sp, [unit: "°${location.temperatureScale}"])
            if (e.suffix == primaryCompartment() + "_setpoint") {
                emit("coolingSetpoint", sp, [unit: "°${location.temperatureScale}"])
            }
            return
        case "power":
            if (!isNullState(e.state)) emit("power", coerce(e.state), [unit: e.unit ?: "W"])
            return

        case "energy":
        case "energy_saved":
        case "energy_this_month":
        case "energy_last_month":
        case "power_energy":
            if (!isNullState(e.state)) {
                emit(snakeToCamel((String) e.suffix), coerce(e.state), [unit: e.unit ?: "Wh"])
            }
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

void setCoolerSetpoint(BigDecimal t)  { haSetNumber("cooler_setpoint", t) }
void setFreezerSetpoint(BigDecimal t) { haSetNumber("freezer_setpoint", t) }

/** Replica: the main device's setpoint is the primary compartment's. */
void setCoolingSetpoint(BigDecimal t) { haSetNumber(primaryCompartment() + "_setpoint", t) }

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

/** Diagnostic: only visible as an attribute when diagnostics are enabled. */
void startDiagnosis() { haPress("start_diagnosis") }

void refresh() { haRefreshAll() }
