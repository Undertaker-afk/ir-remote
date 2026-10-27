package app.irremote.data

/** A nearby screen-sharing / casting target, as shown by Cast, Smart View, AirPlay or Miracast pickers. */
data class FoundDevice(
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val source: String,
) {
    val key: String get() = "$name|${model.orEmpty()}".lowercase()

    /** Everything we know about the device as match text. */
    val text: String get() = listOfNotNull(manufacturer, model, name).joinToString(" ")
}

/** Pure parsers for the discovery protocols (kept free of Android types so they can be unit tested). */
object DiscoveryParsers {
    /** Headers of an SSDP response (keys lower-cased). */
    fun ssdpHeaders(response: String): Map<String, String> =
        response.lineSequence().drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i > 0) l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim() else null
        }.toMap()

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()?.let(::unescape)?.ifEmpty { null }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** UPnP device description (first, i.e. root, device). Returns null for devices that aren't screens/players. */
    fun upnp(xml: String, viaDial: Boolean = false): FoundDevice? {
        val type = tag(xml, "deviceType").orEmpty()
        val screenLike = viaDial || listOf("MediaRenderer", "roku", "Television", "tv").any { type.contains(it, ignoreCase = true) }
        if (!screenLike) return null
        val name = tag(xml, "friendlyName") ?: tag(xml, "modelName") ?: return null
        // modelName usually carries the real model ("UE55NU7100"); modelNumber is often a protocol tag ("AllShare1.0")
        val modelName = tag(xml, "modelName")
        val model = modelName?.takeIf { it.length >= 4 && it.any(Char::isDigit) } ?: tag(xml, "modelNumber") ?: modelName
        return FoundDevice(name, tag(xml, "manufacturer"), model, "DLNA / Smart View")
    }

    /** mDNS TXT records of _googlecast._tcp. */
    fun googleCast(serviceName: String, txt: Map<String, String>): FoundDevice {
        val model = txt["md"]
        val maker = if (model?.contains("Chromecast", ignoreCase = true) == true || model?.contains("Google", ignoreCase = true) == true) "Google" else null
        return FoundDevice(txt["fn"] ?: serviceName, maker, model, "Google Cast")
    }

    /** mDNS TXT records of _airplay._tcp. */
    fun airPlay(serviceName: String, txt: Map<String, String>) =
        FoundDevice(serviceName, txt["manufacturer"], txt["model"], "AirPlay")

    /** Wi-Fi Direct peers that advertise as displays (category 7), i.e. what the screen-mirroring list shows. */
    fun miracast(deviceName: String, primaryType: String?): FoundDevice? =
        if (primaryType?.startsWith("7-") == true) FoundDevice(deviceName.removePrefix("[TV]").trim().ifEmpty { deviceName }, null, null, "Miracast") else null
}
