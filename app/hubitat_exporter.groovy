/**
 *
 *  Hubitat Exporter v0.0.1
 *
 *  Copyright © 2021 David O'Rourke
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a
 *  copy of this software and associated documentation files (the “Software”),
 *  to deal in the Software without restriction, including without limitation
 *  the rights to use, copy, modify, merge, publish, distribute, sublicense,
 *  and/or sell copies of the Software, and to permit persons to whom the
 *  Software is furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 *  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 *  DEALINGS IN THE SOFTWARE.
 *
 */
import groovy.transform.Field

definition(
    name:        "Hubitat Exporter",
    namespace:   "hubitatexporter",
    author:      "David O'Rourke",
    category:    "Monitoring",
    description: "Prometheus exporter for Hubitat Elevation C7",
    iconUrl:     "",
    iconX2Url:   "",
)

preferences {
    page(name: "setupScreen")
}

mappings {
    path("/metrics") {
        action: [
            GET: "exporter",
        ]
    }
}

// Constants
@Field final List<String> EXCLUDED_ATTRIBUTES = [
    'colorName',
    'colorTemperature',
]

// Constants, but not quite
// Local IP address to connect to hub
String getHUB_LOCAL_IP() {
    location.hubs[0].localIP
}

// Free OS memory information
// This URL returns a CSV
String getHUB_FREE_OS_MEMORY() {
    "http://${HUB_LOCAL_IP}:8080/hub/advanced/freeOSMemoryLast"
}

// Hub temperature information
// This URL returns a string
String getHUB_TEMPERATURE_URL() {
    "http://${HUB_LOCAL_IP}:8080/hub/advanced/internalTempCelsius"
}

// Hub messages, used for firmware upgrade detection
String getHUB_MESSAGES_URL() {
    "http://${HUB_LOCAL_IP}:8080/hub/messages"
}

// We don't have a real exporter library available, so this is all manually
// formatted.
def exporter() {
    log.info "Exporting metrics"

    // Render this later after it's full of info
    content = ""

    // Hub Information
    content += exportHubInfo()

    // Devices information
    content += exportDevices()

    render(
        contentType: "text/plain",
        data:        content,
        status:      200,
    )
}

// Export metrics for attached devices
String exportDevices() {
    logDebug "exportDevices()"

    // Content to return
    String content = ""

    Map<String, Object> devices = hubDevices()

    // device_id section
    logDebug "device_id section"

    content += """\
    # HELP hubitat_device_id device id
    # TYPE hubitat_device_id gauge
    """.stripIndent()

    devices.each { deviceId, deviceValues ->
        String deviceName  = deviceValues.name
        String deviceLabel = deviceValues.label

        content += """\
        hubitat_device_id{deviceid="${deviceId}",name="${deviceName}",label="${deviceLabel}"} ${deviceId}
        """.stripIndent()
    }

    logDebug "device_attribute section"

    content += """\
    # HELP hubitat_device_attribute device attribute
    # TYPE hubitat_device_attribute gauge
    """.stripIndent()

    // device_attribute section
    devices.each { deviceId, deviceValues ->
        Map<String, Object> attributes = deviceValues.attributes

        attributes.each { attributeId, attributeValues ->
            String attributeName = attributeValues.name
            def attributeValue   = attributeValues.value

            content += """\
            hubitat_device_attribute{deviceid="${deviceId}",attribute="${attributeName}",attributeid="${attributeId}"} ${attributeValue}
            """.stripIndent()
        }
    }

    content
}

// Export metrics relating to the hub itself, not the devices
String exportHubInfo() {
    logDebug "exportHubInfo()"

    // Basic hub info
    Map<String, String> info = hubInfo()

    String content = """\
    # HELP hubitat_device_info hub device information
    # TYPE hubitat_device_info gauge
    hubitat_device_info{name="${info.name}",type="${info.type}",version="${info.firmware_version}"} 1
    # HELP hubitat_hub_id hub device id
    # TYPE hubitat_hub_id gauge
    hubitat_hub_id ${info.id}
    # HELP hubitat_uptime_seconds hub uptime
    # TYPE hubitat_uptime_seconds counter
    hubitat_uptime_seconds ${info.uptime_seconds}
    """.stripIndent()

    // Temperature info
    // Also get temperature in F for places that haven't upgraded to C yet.
    float temperatureCelsius = new BigDecimal(temperature())
        .setScale(1, BigDecimal.ROUND_HALF_DOWN)
        .floatValue()
    float temperatureFahrenheit = celsiusToFahrenheit(temperatureCelsius)

    content += """\
    # HELP hubitat_cpu_temperature_celcius hub temperature
    # TYPE hubitat_cpu_temperature_celcius gauge
    hubitat_cpu_temperature_celcius ${temperatureCelsius}
    # HELP hubitat_cpu_temperature_fahrenheit hub temperature
    # TYPE hubitat_cpu_temperature_fahrenheit gauge
    hubitat_cpu_temperature_fahrenheit ${temperatureFahrenheit}
    """.stripIndent()

    // OS memory and CPU info
    Map<String, Object> osInfo = freeOsMemory()

    content += """\
    # HELP hubitat_load5 5m load average.
    # TYPE hubitat_load5 gauge
    hubitat_load5 ${osInfo.cpuAvg}
    # HELP hubitat_memory_jvm_free_bytes JVM free memory
    # TYPE hubitat_memory_jvm_free_bytes gauge
    hubitat_memory_jvm_free_bytes ${osInfo.freeJVM}
    # HELP hubitat_memory_jvm_total_bytes JVM total memory
    # TYPE hubitat_memory_jvm_total_bytes gauge
    hubitat_memory_jvm_total_bytes ${osInfo.totalJVM}
    # HELP hubitat_memory_os_free_bytes OS free memory
    # TYPE hubitat_memory_os_free_bytes gauge
    hubitat_memory_os_free_bytes ${osInfo.freeOS}
    """.stripIndent()

    // Firmware upgrade available
    int firmwareUpgradeAvailable = hubFirmwareUpgradeAvailable()

    content += """\
    # HELP hubitat_firmware_upgrade_available firmware upgrade available
    # TYPE hubitat_firmware_upgrade_available gauge
    hubitat_firmware_upgrade_available ${firmwareUpgradeAvailable}
    """.stripIndent()

    content
}

// Currently unused index page.
def index() {
    log.info "Index page"

    String metrics_url = getFullLocalApiServerUrl() + "/metrics?access_token=${state.accessToken}"

    String content = """\
    <!DOCTYPE html>
    <html lang="en">
        <head>
            <title>Hubitat Exporter</title>
        </head>
        <body>
            <h1>Hubitat Exporter</h1>
            <p><a href="${metrics_url}">Metrics</a></p>
        </body>
    </html>
    """.stripIndent()

    render(
        contentType: "text/html",
        data:        content,
        status:      200,
    )
}

// App configuration pages
def setupScreen() {
    if (!state.accessToken) {
        createAccessToken()
    }

    String url = getFullLocalApiServerUrl() + "/metrics?access_token=${state.accessToken}"

    def content = dynamicPage(
        name: "setupScreen",
        uninstall: true,
        install: true,
    ) {
        section() {
            paragraph("Use the following URL: <a href='${url}'>${url}</a>")
        }

        section() {
            input(
                "devices",
                "capability.*",
                title: "Select Devices",
                submitOnChange: true,
                required: true,
                multiple: true,
            )
        }

        section() {
            input(
                "debugEnable",
                "bool",
                title: "Enable Debug Logging?",
                defaultValue: true,
            )
        }
    }

    content
}

// Special handling for some attribute values that need turning into numbers
def handleAttributeValue(name, value) {
    def finalValue = value

    // Switches can be "on" or "off"
    // on: 1, off: 0
    if (name == "switch") {
        switch(value) {
            case "on":
                finalValue = 1
                break
            case "off":
                finalValue = 0
                break
            default:
                finalValue = 0
                break
        }

        return finalValue
    }

    // Contact sensors can be open or closed
    // open: 1, closed: 0
    if (name == "contact") {
        switch(value) {
            case "open":
                finalValue = 1
                break
            case "closed":
                finalValue = 0
                break
            default:
                finalValue = 0
                break
        }

        return finalValue
    }

    finalValue
}

// Methods for fetching information from the Hub
// Return free memory information
Map<String, Object> freeOsMemory() {
    Map<String, Object> memoryInfo = [:]

    httpGet(HUB_FREE_OS_MEMORY) { resp ->
        String text = resp.data.getText()

        logDebug "free OS memory response: '${text}'"

        // Get the second line with the interesting data
        List<String> data = text.split("\n")[1].split(",")
        logDebug "OS data: ${data}"

        // Turn KiB into MiB for memory numbers
        memoryInfo = [
            date:     data[0],
            freeOS:   (data[1] as int) * 1024,
            totalJVM: (data[2] as int) * 1024,
            freeJVM:  (data[3] as int) * 1024,
            cpuAvg:   data[4],
        ]
    }

    memoryInfo
}

// Return information about hub devices
Map<String, Object> hubDevices() {
    logDebug "Gathering Hub Devices"

    def devices = settings?.devices

    // Storage for what we find.
    Map<String, Object> deviceInfo = [:]

    for (device in devices) {
        // Storage for attribute values
        def attributeValues = [:]

        // Our deviceInfo map will be keyed off this
        String deviceId = device.getId()

        // List<Attribute>
        List<Object> attributes = device.getSupportedAttributes()

        for (attribute in attributes) {
            String name = attribute.name

            // Check if this is an excluded attribute
            if (name in EXCLUDED_ATTRIBUTES) {
                continue
            }

            long attributeId = attribute.id
            String dataType  = attribute.dataType

            // Object
            def value = handleAttributeValue(
                name,
                device.currentValue(name),
            )

            attributeValues[attributeId] = [
                dataType: dataType,
                name:     name,
                value:    value,
            ]
        }

        deviceInfo[deviceId] = [
            "attributes": attributeValues,
            "label":      device.getLabel(),
            "name":       device.getName(),
        ]
    }

    deviceInfo
}

// Return an int indicating if a firmware update is available.
// 1: yes, 0: no
// This is pretty basic, there's no good endpoint to tell us this, so we just
// try to parse the Hub messages.
// Message resembles:
// <span id='platformUpdateMessage'>Platform update 2.2.6.139 <a href='/hub/platformUpdate'>available to download</a> (<a href='#' onclick='return dismissFirmwareUpdate(this, \"2.2.6.139\");'>dismiss</a>)</span>
// and will be part of the messages[] list.
int hubFirmwareUpgradeAvailable() {
    Boolean firmwareUpgradeAvailable = false

    httpGet(HUB_MESSAGES_URL) { resp ->
        // httpGet turns the returned string into JSON for us automatically.
        Object json     = resp.data
        String messages = json.messages

        for (message in messages) {
            if ('platformUpdateMessage' in message) {
                firmwareUpgradeAvailable = true
                break
            }
        }
    }

    firmwareUpgradeAvailable ? 1 : 0
}

// Return the basic hub information
Map<String, String> hubInfo() {
    // Hub type
    def hub = location.hubs[0]

    Map<String, String> info = [
        firmware_version: hub.firmwareVersionString,
        id:               hub.id,
        name:             hub.name,
        type:             hub.type,
        uptime_seconds:   hub.uptime,
    ]

    info
}

// Return hub temperature
// Return this as float rather than text, it's easier for us to deal with
// numbers elsewhere
float temperature() {
    httpGet(HUB_TEMPERATURE_URL) { resp ->
        String text = resp.data.text()
        logDebug "temperature response: ${text}"

        return text as float
    }
}

// Convenience methods
// Log debug strings if debugging is enabled
void logDebug(String text) {
    Boolean enabled = settings?.debugEnabled

    if (enabled || enabled == null) {
        log.debug text
    }
}

// Hubitat methods
// Called when settings are updated.
void updated() {
    log.trace "updated()"

    if (debugEnable) {
        runIn(1800, logsOff)
    }
}

// Called when logging is toggled
void logsOff() {
    log.warn "debug logging disabled"

    Map<String, String> params = [
        type:  "bool",
        value: "false",
    ]

    device.updateSetting("debugEnable", params)
}
