/**
 *  King Of Fans Zigbee Fan Controller (Adapted for Hubitat)
 *
 *  To be used with Ceiling Fan Remote Controller Model MR101Z receiver by Chungear Industrial Co. Ltd
 *  at Home Depot Gardinier 52" Ceiling Fan, Universal Ceiling Fan/Light Premier Remote Model #99432
 *
 *  Copyright 2018 Stephan Hackett
 *  Original ST DTH developed in collaboration with Ranga Pedamallu, Dale Coffing
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
 *	06/02/18 - added cycle() command to cycle through fanSpeeds
 *
 *
 */
def version() {"v1.0.20180602"}

metadata {
	definition (name: "KOF Fan Controller", namespace: "stephack", author: "Stephan Hackett") {
		capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        //capability "Switch Level"
        capability "Light"
        capability "Sensor"
        capability "Fan Control"

        command "lightOn"
        command "lightOff"
        command "recreateChildDevices"
        command "setLightLevel", ["number"]
        command "cycle"
        
        attribute "lightLevel", "number"

	fingerprint profileId: "0104", inClusters: "0000, 0003, 0004, 0005, 0006, 0008, 0202", outClusters: "0003, 0019", model: "HDC52EastwindFan"
    }
    
    //preferences {
    //   input "refreshChildren", "bool", title: "Rebuild Child Devices?", description:"Warning!! Child Devices will have to be maually readded to any associated Apps!", required: true, defaultValue: false
    //}
}

def parse(String description) {
	//log.debug "Parse description $description"
    def event = zigbee.getEvent(description)
    //log.info event
    if (event) {
    	log.info "Status report received from controller: [Light ${event.name} is ${event.value}]"
    	def childDevice = getChildDevices()?.find {it.data.componentLabel == "Light"}
        if(childDevice) childDevice.sendEvent(event)
        if(event.value != "on" && event.value != "off") sendEvent(name: "lightLevel", value: event.value)
    }
	else {
		def map = [:]
		if (description?.startsWith("read attr -")) {
			def descMap = zigbee.parseDescriptionAsMap(description)
			if (descMap.cluster == "0202" && descMap.attrId == "0000") {
				map.name = "speed"
				map.value = descMap.value.toInteger()
                log.info "Status report received from controller: [Fan Speed is ${map.value}: ${getFanName()[map.value]}]"
			}
		}
		def result = null
        if (map) {
			result = createEvent(map)
         	fanSync(map.value)
		}
		return result
   	}
}

def getFanName() {
	[
    0:"Off",
    1:"Low",
    2:"Medium",
    3:"Medium High",
	4:"High",
    5:"Off",
    6:"Comfort Breeze™",
    //7:"Light"
	]
}

def installed() {
	log.info "Installing"
	initialize()
    log.info "Exiting Install"
}

def updated() {
	log.info "Updating"
	//if(state.oldLabel != device.label) {updateChildLabel()}
	initialize()
    response(refresh())
}

def initialize() {
	log.info "Initializing"
    createChildren()
    state.version = version()
}

def recreateChildDevices(){
    deleteChildren()
    createChildren()
}

def createChildren(){
	createFanChild()
    createLightChild()
}

def createFanChild() {
	//state.oldLabel = device.label  	//save the label for reference if it ever changes
	for(i in 1..6) {
    	def childDevice = getChildDevices()?.find {it.data.componentLabel == i}
        if (!childDevice && i != 5) {
           	log.info "Creating Fan Child [${getFanName()[i]}]"
        	childDevice = addChildDevice("stephack", "KOF Zigbee Fan Speed Child", "${device.deviceNetworkId}-${i}",[label: "${device.displayName} ${getFanName()[i]}", isComponent: true, componentLabel:i])
		}
       	else if (childDevice) {
        	log.info "Fan Child [${getFanName()[i]}] already exists"
		}
	}
}

def createLightChild() {
    def childDevice = getChildDevices()?.find {it.data.componentLabel == "Light"}
    if (!childDevice) {
        log.info "Creating Light Child"
		childDevice = addChildDevice("stephack", "KOF Zigbee Fan Light Child", "${device.deviceNetworkId}-Light",[label: "${device.displayName} Light", isComponent: true, componentLabel: "Light"])
    }
	else {
        log.info "Light Child already exists"
	}
}

def deleteChildren() {
	log.info "Deleting children"
	def children = getChildDevices()
    children.each {child->
  		deleteChildDevice(child.deviceNetworkId)
    }
}

def configure() {
	log.info "Configuring Reporting and Bindings."
    state.lastRunningSpeed = 1
    state.version = version()
	return 	zigbee.configureReporting(0x0006, 0x0000, 0x10, 1, 600, null)+
			zigbee.configureReporting(0x0008, 0x0000, 0x20, 1, 600)+
			zigbee.configureReporting(0x0202, 0x0000, 0x30, 1, 600, null)
}

def on() {
	log.info "Resuming Previous Fan Speed"
    return setSpeed(state.lastRunningSpeed)
}

def off() {
	log.info "Turning Fan Off"
    def fanNow = device.currentValue("speed")    //save fanspeed before turning off so it can be resumed when turned back on
    log.info "fanNow1: "+fanNow
    if(fanNow != "0") state.lastRunningSpeed = fanNow	//do not save lastfanSpeed if fan is already off
    log.info "lastRun: "+state.lastRunningSpeed
    zigbee.writeAttribute(0x0202, 0x0000, 0x30, 00)
}

def lightOn()  {
	log.info "Turning Light On"
	zigbee.on()
}

def lightOff() {
	log.info "Turning Light Off"
	zigbee.off()
}

def setLightLevel(val) {
    log.info "Adjusting Light Brightness Level to ${val}"
    return zigbee.setLevel(val.toInteger())
    //val?.toInteger() == 0 ? zigbee.off() : zigbee.setLevel(val)
    //zigbee.setLevel(val) + (val?.toInteger() == 0 ? zigbee.off() : [])
    //zigbee.command(0x0008, 0x04, zigbee.convertToHexString(val.toInteger(),1) + "ffff")
}

def setSpeed(speed) {
    def mySpeed = speed.toInteger()
    if(mySpeed == 5 || mySpeed > 6 || mySpeed < 0) return
	log.info "Adjusting Fan Speed to "+ getFanName()[mySpeed]
    return zigbee.writeAttribute(0x0202, 0x0000, 0x30, mySpeed)
}

def cycle(){
    def speedNow = device.currentSpeed.toInteger()
    if(speedNow !=4) newSpeed = speedNow + 1
    else(newSpeed=0)
    setSpeed(newSpeed)
}

def fanSync(whichFan) {
	def children = getChildDevices()
   	children.each {child->
       	def childSpeedVal = child.data.componentLabel
        if(childSpeedVal == whichFan) {
            sendEvent(name:"switch",value:"on")
           	child.sendEvent(name:"switch",value:"on")
        }
        else {
           	if(childSpeedVal!=null){
           		child.sendEvent(name:"switch",value:"off")
           	}
        }
   	}
    if(whichFan == 0) sendEvent(name:"switch",value:"off")
}

def refresh() {
    log.info "Refreshing Device Values"
	//return zigbee.onOffRefresh()// + zigbee.levelRefresh() + zigbee.readAttribute(0x0202, 0x0000)
    zigbee.readAttribute(0x0202, 0x0000)+zigbee.readAttribute(0x0008, 0x0000)+zigbee.readAttribute(0x0006, 0x0000)
}

/*
def ping() {
    return zigbee.onOffRefresh()
}

def updateChildLabel() {
	log.info "Updating Device Labels"
	for(i in 1..6) {
    	def childDevice = getChildDevices()?.find {it.data.componentLabel == i}
        if (childDevice && i != 5) {childDevice.label = "${device.displayName} ${getFanName()[i]}"}
    }
    def childDeviceL = getChildDevices()?.find {it.data.componentLabel == "Light"}
    if (childDeviceL) {childDeviceL.label = "${device.displayName} Light"}
}
*/
