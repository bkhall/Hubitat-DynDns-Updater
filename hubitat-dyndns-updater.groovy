import groovy.xml.XmlUtil;

definition(
    name: "DynDns Updater",
    namespace: "hubitat-dyndns-bkhall",
    author: "Baron K. Hall - baron.k.hall@gmail.com",
    description: "This is a DynDns updater client to keep your domain names in sync with your dynamic IP address. You must have a DynDns account to use this app.",
    category: "General",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/bkhall/Hubitat-DynDns-Updater/main/hubitat-dyndns-updater.groovy"
)

preferences {
    page(name: "page1")
    page(name: "page2")
    page(name: "page3")
}

def installed() {
    updated()
    
    state.installed = true
}

def updated() {
    unsubscribe()
    unschedule()

    scheduleRegularIPChecks()
    checkIp()
    doUpdate()
}

def page1() {
    if (null == atomicState.lastCheck) {
        atomicState.lastCheck = 0
    }    
    if (null == atomicState.lastUpdate) {
        atomicState.lastUpdate = 0
    }    

    return dynamicPage(name: "page1", refreshInterval: 0, install: false, uninstall: true, nextPage: "page2") {
        buildCurrentIpSection()
    
        if (state.installed) {
            describeInstall()
        }

        section(state.installed ? "<b>Edit Configuration</b>" : "<b>Before We Begin...</b>") {
            paragraph "Please login to your DynDns account and determine if you are a DynDns Free or a DynDns Pro user.<br>"
            paragraph "If you are a DynDns Free user, you will need to provide your DynDns account username and password to update your domain IP address.<br>"
            paragraph "If you are a DynDns Pro user, we can use either your username and password (select Free), or your Updater Client Key. Your Updater Client Key is shown in your DynDns account settings and is the more secure option."
            input name: "accountType", type: "enum", title: "DynDns Account Type", multiple: false, options: ["Free","Pro"], required: true
        }
    }
}

def page2() {
    return dynamicPage(name: "page2", refreshInterval: 0, install: false, uninstall: true, nextPage: "page3") {
        buildPageContent(false)
    }
}

def page3() {
    if ("Free".equals(settings.accountType)) {
        state.auth = "${settings.username}:${settings.password}".bytes.encodeBase64().toString()
    } else {
        state.auth = "${settings.username}:${settings.updaterKey}".bytes.encodeBase64().toString()
    }
    
    return dynamicPage(name: "page3", refreshInterval: 0, install: true, uninstall: true) {
        buildPageContent(true)
    }
}

private buildCurrentIpSection() {
    section("<b>Current IPv4 Addresss</b>") {
        checkIp()
                                     
        paragraph atomicState.pendingIp
    }
}    

private buildPageContent(boolean showConfig) {
    buildCurrentIpSection()
    
    if (showConfig) {
        describeInstall()
    } else {
        section("<b>Configure Updater</b>") {
            paragraph "Provide your DynDns account details below"
            
            input name: "username", type: "text", title: "DynDns Username", required: true

            if ("Free".equals(settings.accountType)) {
                input name: "password", type: "password", title: "DynsDns Password", required: true
            } else {
                input name: "updaterKey", type: "password", title: "Updater Client Key (from your DynDns Pro account settings)", required: true
            }

            input name: "domains", type: "text", title: "Domains (up to 20, comma-separated domain names to update)", required: true
            input name: "interval", type: "enum", title: "Minimum update frequency", multiple: false, defaultValue: "1 Hour",
                options: ["15 Minutes", "30 Minutes", "1 Hour", "2 Hours", "4 Hours", "8 Hours", "12 Hours", "24 Hours", "48 Hours", "4 Days", "1 Week", "2 Weeks", "1 Month"],
                required: true
        }
    }
}

private describeInstall() {
    section("<b>Current Configuration</b>") {
        paragraph "Account Type: ${settings.accountType}"
        paragraph "Update Frequency: ${settings.interval}"

        def domains = settings.domains.split(",")

        def builder = new StringBuilder()

        domains.sort{domain -> domain}.each {
            domain ->
                builder << (
                    "<ul>${domain}</ul>"
                )
        }

        paragraph "Domains:<br>${builder.toString()}"
    }
}

private checkIp() {
    long rightNow = now()
    
    // don't re-check for at least 10 minutes, per DynDns guidelines
    if (rightNow < atomicState.lastCheck + 10 * 60 * 1000) {
        log.info "Too soon, skipping Check IP"
        
        return
    }
    
    log.info "Checking for IP changes"
    try {
        // clear any scheduled ip check task
        unschedule("checkIp")
                   
        // yes, use a blocking call here so we can use the response on the status page
        httpGet("http://checkip.dyndns.com/") {response ->
            if (response.success) {
                String html = XmlUtil.serialize(response.data)
                int index = html.lastIndexOf(":") + 1
                
                html = html.substring(index)
                
                index = html.indexOf("<")
                
                atomicState.pendingIp = html.substring(0, index).trim()

                atomicState.lastCheck = rightNow
                
                if (atomicState.pendingIp.equals(atomicState.currentIp)) {
                    log.info "Check IP found no change."
                } else {
                    log.info "Check IP found a change."

                    doUpdate()
                }
                    
                scheduleRegularIPChecks()
            } else {
                log.info "Check IP Failed"

                retryIpCheckIn15Minutes()
            }
        }
    } catch (e) {
        log.error "Check IP Error: ${e} ${request}"

        retryIpCheckIn15Minutes()
    }
}

private retryIpCheckIn15Minutes() {
    runIn (900, "checkIp", [overwrite: true])                
}

private scheduleRegularIPChecks() {
    runIn (getInterval(), "checkIp", [overwrite: true])                
}

private int getInterval() {
    switch (settings.interval) {
        case "15 Minutes":
            return 900
        case "30 Minutes":
            return 1800
        case "1 Hour":
            return 3600
        case "2 Hours":
            return 2 * 3600
        case "4 Hours":
            return 4 * 3600
        case "8 Hours":
            return 8 * 3600
        case "12 Hours":
            return 12 * 3600
        case "24 Hours":
            return 24 * 3600
        case "48 Hours":
            return 48 * 3600
        case "4 Days":
            return 4 * 24 * 3600
        case "1 Week":
            return  7 * 24 * 3600
        case "2 Weeks":
            return  14 * 24 * 3600
        case "1 Month":
            return 30 * 24 * 3600
        default:
            return 3600
    }
}

private doUpdate() {
    if (null == settings.domains) {
        return 
    }
    
    // don't re-update for at least 10 minutes
    if (now() < atomicState.lastUpdate + 10 * 60 * 1000) {
        log.info "Too soon, skipping update"

        return
    }
    
    // make a weak attempt to remove the most common input errors
    String domainsStr = settings.domains.trim().replaceAll("\\s+",",")
    
    while (domainsStr.contains(",,")) {
       domainsStr = domainsStr.replace(",,", ",") 
    }

    while (domainsStr.contains("..")) {
       domainsStr = domainsStr.replace("..", ".") 
    }
    
    // dyndns only allows 20 domains to be updated in one call
    // break the stored domains into groups of 20
    String[] domains = domainsStr.split(",")
    for (int i = 0; i < domains.size(); i += 20) {
        String[] group = new String[Math.min(20, domains.size() - i)]
        
        for (int j = 0; j < group.size(); j++) {
            group[j] = domains[j + i]
        }
        
        updateDynDns(group)
    }
}

private updateDynDns(String[] domains) {
    log.info "Updating DynDns"
    
    Map headers = [
        "Authorization": "Basic ${state.auth}",
        "User-Agent": "Couch Potato - Hubitat Elevation - 0.0.1"
    ]
    
    Map query = [
        hostname: URLEncoder.encode(domains.join(","), "UTF-8"),
        myip: URLEncoder.encode(atomicState.pendingIp, "UTF-8")
    ]
    
    // we have to make the update call over HTTP right now.
    // Java-based clients (Groovy) suffer a fatal error due to
    // a server misconfiguration at DynDns, which makes an HTTPS
    // call fail
    //
    // a Google search on
    // javax.net.ssl.SSLProtocolException: handshake alert: unrecognized_name
    // will provide the details
    //
    // for now, we're stuck on HTTP
    Map params = [
        uri: "http://members.dyndns.org/v3/update",
        headers: headers,
        query: query 
    ]
    
    try {
        // clear any scheduled update task
        unschedule("doUpdate")
        
        Map data = [
            domains: domains,
            pendingIp: atomicState.pendingIp,
        ]

        asynchttpGet("handleUpdateResponse", params, data)       
    } catch (e) {
        log.error "error: ${e} ${params}"
        
        // retry in 15 minutes
        runIn(900, "doUpdate", [overwrite: true])
    }
}

def handleUpdateResponse(response, data) {
    if (response.status == 200) {
        log.info "DynDns updated ${data.domains} with ${data.pendingIp}"

        atomicState.currentIp = data.pendingIp
        atomicState.lastUpdate = now()
    } else {
        log.info "Failed to update ${data.domains} with ${data.pendingIp}"
    }        
}
