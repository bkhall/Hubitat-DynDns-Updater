import groovy.transform.Field
import groovy.xml.XmlUtil;

@Field
String currentIp

definition(
    name: "DynDns Updater",
    namespace: "hubitat-dyndns-bkhall",
    author: "Baron K. Hall - baron.k.hall@gmail.com",
    description: "This is a DynDns updater client to keep your domain names in sync with your dynamic IP address. You must have a DynDns account to use this app.",
    category: "General",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "page1")
    page(name: "page2")
    page(name: "page3")
}

def installed() {
    updated()
}

def updated() {
    unsubscribe()
    unschedule()
    
    doUpdate()
}

def page1() {
    if (null == state.lastCheck) {
        state.lastCheck = 0
    }
    if (null == state.lastUpdate) {
        state.lastUpdate = 0
    }
    
    return dynamicPage(name: "page1", refreshInterval: 0, install: false, uninstall: state.installed, nextPage: "page2") {
        section("<b>Current IPv4 Addresss</b>") {
            if (null == currentIp) {
                checkIp()
            }
                                     
            paragraph state.currentIp
        }
    
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
    return dynamicPage(name: "page2", refreshInterval: 0, install: false, uninstall: state.installed, nextPage: "page3") {
        buildPageContent(false)
    }
}

def page3() {
    state.installed = true
    
    settings.domains = settings.domains.replace(" ", "")

    if ("Free".equals(settings.accountType)) {
        state.auth = "${settings.username}:${settings.password}".bytes.encodeBase64().toString()
    } else {
        state.auth = "${settings.username}:${settings.updaterKey}".bytes.encodeBase64().toString()
    }
    
    return dynamicPage(name: "page3", refreshInterval: 0, install: true, uninstall: state.installed) {
        buildPageContent(true)
    }
}

private buildPageContent(boolean showConfig) {
    section("<b>Current IPv4 Addresss</b>") {
        if (null == currentIp) {
            checkIp()
        }
                                     
        paragraph state.currentIp
    }
    
    if (state.installed && showConfig) {
        describeInstall()
    } else {
        section("<b>Configure Updater</b>") {
            paragraph "Provide your DynDns Pro account details below"
            
            input name: "username", type: "text", title: "DynDns Username", required: true

            if ("Free".equals(settings.accountType)) {
                input name: "password", type: "password", title: "DynsDns Password", required: true
            } else {
                input name: "updaterKey", type: "password", title: "Updater Client Key (from your DynDns Pro account settings)", required: true
            }

            input name: "domains", type: "text", title: "Domains (up to 20, comma-separated domain names to update)", required: true
            input name: "frequency", type: "enum", title: "Minimum update frequency", multiple: false, defaultValue: "6 Hours",
                options: ["1 Hour","3 Hours", "6 Hours", "12 Hours", "24 Hours", "48 Hours", "1 Week", "2 Weeks", "1 Month"], required: true
        }
    }
}

private describeInstall() {
    section("<b>Current Configuration</b>") {
        paragraph "Account Type: ${settings.accountType}"
        paragraph "Update Frequency: ${settings.frequency}"

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
    
    // don't re-check for at least 10 minutes
    if (rightNow < state.lastCheck + 10 * 60 * 1000) {
        log.info "Too soon, skipping Check IP"
        
        return
    }
    
    log.info "Checking for IP changes"
    try {
        httpGet("http://checkip.dyndns.com/") {response ->
            if (response.success) {
                String html = XmlUtil.serialize(response.data)
                int index = html.lastIndexOf(":") + 1
                
                html = html.substring(index)
                
                index = html.indexOf("<")
                
                currentIp = html.substring(0, index).trim()
                
                if (!currentIp.equals(state.currentIp)) {
                    state.currentIp = currentIp
                    
                    doUpdate()
                }
            } else {
                log.debug "Check IP Failed"
            }
        }
    } catch (e) {
        log.error "Check IP Error: ${e} ${request}"
    }
    
    runIn (getNormalizedFrequency(), 'checkIp', null)
    
    state.lastCheck = rightNow
}

private doUpdate() {
     long rightNow = now()
    
    // don't update for at least 10 minutes
    if (rightNow < state.lastUpdate + 10 * 60 * 1000) {
        log.info "Too soon, skipping Update"

        return
    }
    
    // dyndns only allows 20 domains to be updated in one call
    // break the stored domains into groups of 20
    String[] domains = settings.domains.split(",")
    
    for (int i = 0; i < domains.size(); i += 20) {
        String[] group = new String[Math.min(20, domains.size() - i * 20)]
        
        for (int j = 0; j < group.size(); j++) {
            group[0] = domains[j + i * 20]
        }
        
        updateDynDns(group)
    }
    
    state.lastUpdate = rightNow
}

private int getNormalizedFrequency() {
    switch (settings.frequency) {
        case "1 Hour":
            return 3600
        case "3 Hours":
            return 3 * 3600
        case "6 Hours":
            return 6 * 3600
        case "12 Hours":
            return 12 * 3600
        case "24 Hours":
            return 24 * 3600
        case "48 Hours":
            return 48 * 3600
        case "1 Week":
            return  7 * 24 * 3600
        case "2 Weeks":
            return  14 * 24 * 3600
        case "1 Month":
            return 30 * 24 * 3600
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
        myip: URLEncoder.encode(state.currentIp, "UTF-8")
    ]
    
    Map params = [
        uri: "http://members.dyndns.org/v3/update",
        headers: headers,
        query: query 
    ]
    
    log.debug headers
    log.debug query
    log.debug params
    
    try {
        httpGet(params) {response ->
            if (response.success) {
                log.info "DynDns updated ${domains}"
            } else {
                log.error "Failed to update ${domains}"
            }
        }
    } catch (e) {
        log.error "error: $e $request"
    }
}