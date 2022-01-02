/**
 *  Springs Window Fashions - Roller Shade Driver
 *  Author: DevTodd / Hubitat: DevOpsTodd
 *  Date: 2020-12-23
 *
 *  Copyright 2020 DevTodd
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  2020-12-23: Initial commit, port.  Added:
 *      -   Debug and Info Logging
 *      -   Fingerprinting
 *      -   Added capability for SetPosition and SetLevel to be used for controlling position of the blinds
 *
 *  2020-12-26: Cleanup / Initial Home Scene Controls
 *      -   Added the ability to set a home position to be called via Hubitat.  There's no known parameters to save this setting on the device.
 *      -   Removed unnecessary code
 *      -   Made debugging more descriptive
 *
 *      Todo: Clean up unnecessary code, add additional parameters, add info logging
 *
 */
metadata {
    definition (name: "Springs Window Fashions Shade", namespace: "DevTodd", author: "DevTodd") {
        capability "WindowShade"
        capability "Battery"
        capability "Refresh"
        capability "HealthCheck"
        capability "Actuator"
        capability "Sensor"

        command "stop"
        command "home"

        capability "SwitchLevel"

        fingerprint inClusters:"0x5E,0x26,0x85,0x59,0x72,0x86,0x5A,0x73,0x7A,0x6C,0x55,0x80", mfr: "026E", deviceId: "5A31", prod: "5253", deviceJoinName: "Springs Window Fashions - Blinds"
    }

    preferences {
        configParams.each { input it.value.input }
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "infoEnable", type: "bool", title: "Info logging", defaultValue: true
        input name: "homePosition", type: "number", title: "Set Home Position", description: "Set the position that you want the blinds to be set to when calling for the home position.  This doesn't save to the device and is only available to Hubitat or SmartHome Commands", defaultValue: 0
    }
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def parse(String description) {
    def result = null
    def cmd = zwave.parse(description, [0x20: 1, 0x26: 3])
    if (cmd) {
        result = zwaveEvent(cmd)
    }
    if (logEnable) log.debug "Parsed '$description' to ${result.inspect()}"
    return result
}

def getCheckInterval() {
    4 * 60 * 60
}

def installed() {
    sendEvent(name: "checkInterval", value: checkInterval, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
    response(refresh())
}

def updated() {
    if (device.latestValue("checkInterval") != checkInterval) {
        sendEvent(name: "checkInterval", value: checkInterval, displayed: false)
    }
    def cmds = []
    if (!device.latestState("battery")) {
        cmds << zwave.batteryV1.batteryGet().format()
    }

    if (!device.getDataValue("MSR")) {
        cmds << zwave.manufacturerSpecificV1.manufacturerSpecificGet().format()
    }

    log.warn "debug logging is: ${logEnable == true}"
    unschedule()
    if (logEnable) runIn(1800,logsOff)

    if (infoEnable) log.info("Updated with settings $settings")
    cmds << zwave.switchMultilevelV1.switchMultilevelGet().format()
    response(cmds)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    handleLevelReport(cmd)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
    handleLevelReport(cmd)
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd) {
    handleLevelReport(cmd)
}

private handleLevelReport(hubitat.zwave.Command cmd) {
    def descriptionText = null
    def shadeValue = null

    def level = cmd.value as Integer
    level = switchDirection ? 99-level : level
    if (level >= 99) {
        level = 100
        shadeValue = "open"
    } else if (level <= 0) {
        level = 0
        shadeValue = "closed"
    } else {
        shadeValue = "partially open"
        descriptionText = "${device.displayName} shade is ${level}% open"
    }
    def levelEvent = createEvent(name: "level", value: level, unit: "%", displayed: false)
    def positionEvent = createEvent(name: "position", value: level, unit: "%", displayed: false)
    def stateEvent = createEvent(name: "windowShade", value: shadeValue, descriptionText: descriptionText, isStateChange: levelEvent.isStateChange)

    def result = [stateEvent, levelEvent, positionEvent]
    if (!state.lastbatt || now() - state.lastbatt > 24 * 60 * 60 * 1000) {
        if (logEnable) log.debug "requesting battery"
        state.lastbatt = (now() - 23 * 60 * 60 * 1000)
        result << response(["delay 15000", zwave.batteryV1.batteryGet().format()])
    }
    result
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelStopLevelChange cmd) {
    [ createEvent(name: "windowShade", value: "partially open", displayed: false, descriptionText: "$device.displayName shade stopped"),
      response(zwave.switchMultilevelV1.switchMultilevelGet().format()) ]
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
    updateDataValue("MSR", msr)
    if (cmd.manufacturerName) {
        updateDataValue("manufacturer", cmd.manufacturerName)
    }
    createEvent([descriptionText: "$device.displayName MSR: $msr", isStateChange: false])
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    def map = [ name: "battery", unit: "%" ]
    if (cmd.batteryLevel == 0xFF || cmd.batteryLevel == 0) {
        map.value = 1
        map.descriptionText = "${device.displayName} has a low battery"
        map.isStateChange = true
    } else {
        map.value = cmd.batteryLevel
    }
    state.lastbatt = now()
    if (map.value <= 1 && device.latestValue("battery") - map.value > 20) {
        log.warn "Erroneous battery report dropped from ${device.latestValue("battery")} to $map.value. Not reporting"
    } else {
        createEvent(map)
    }
}

def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
}

def zwaveEvent(hubitat.zwave.Command cmd) {
    if (logEnable) log.debug "unhandled $cmd"
    return []
}

def open() {
    if (logEnable) log.debug "Opening the Blinds"
    def level = switchDirection ? 0 : 99
    zwave.basicV1.basicSet(value: level).format()
}

def close() {
    if (logEnable) log.debug "Closing the Blinds"
    def level = switchDirection ? 99 : 0
    zwave.basicV1.basicSet(value: level).format()
}

def setLevel(value, duration = null) {
    if (logEnable) log.debug "Setting the level of the blinds to (${value.inspect()})"
    Integer level = value as Integer
    level = switchDirection ? 99-level : level
    if (level < 0) level = 0
    if (level > 99) level = 99
    zwave.basicV1.basicSet(value: level).format()
}

def setPosition(value, duration = null) {
    if (logEnable) log.debug "Setting the position of the blinds to (${value.inspect()})"
    Integer level = value as Integer
    level = switchDirection ? 99-level : level
    if (level < 0) level = 0
    if (level > 99) level = 99
    zwave.basicV1.basicSet(value: level).format()
}

def presetPosition() {
    zwave.switchMultilevelV1.switchMultilevelSet(value: 0xFF).format()
}

def stop() {
    if (logEnable) log.debug "Stop Command Issued"
    zwave.switchMultilevelV3.switchMultilevelStopLevelChange().format()
}

def stopPositionChange() {
    stop()
}

def startPositionChange(direction) {
    direction == open ? open() : close()
}

def home() {
    if (logEnable) log.debug "Setting the blinds to home position - Level $homePosition"
    def level = switchDirection ? 0 : homePosition
    zwave.basicV1.basicSet(value: level).format()
}

def ping() {
    zwave.switchMultilevelV1.switchMultilevelGet().format()
}

def refresh() {
    if (logEnable) log.debug "Refreshing Blind Status"
    delayBetween([
            zwave.switchMultilevelV1.switchMultilevelGet().format(),
            zwave.batteryV1.batteryGet().format()
    ], 1500)
}
