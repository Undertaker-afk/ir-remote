package app.irremote.ir

import app.irremote.data.DiscoveryParsers
import app.irremote.data.ModelMatcher
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    @Test fun parsesSsdpHeaders() {
        val h = DiscoveryParsers.ssdpHeaders("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.20:9197/dmr\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
        assertEquals("http://192.168.1.20:9197/dmr", h["location"])
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", h["st"])
    }

    @Test fun readsSmartTvDescription() {
        val xml = """<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[TV] Living room</friendlyName><manufacturer>Samsung Electronics</manufacturer>
            <modelName>UE55NU7100</modelName><modelNumber>AllShare1.0</modelNumber></device></root>"""
        val d = DiscoveryParsers.upnp(xml)!!
        assertEquals("[TV] Living room", d.name)
        assertEquals("Samsung Electronics", d.manufacturer)
        assertEquals("UE55NU7100", d.model) // modelNumber "AllShare1.0" is a protocol tag, not the model
    }

    @Test fun ignoresRoutersAndEscapesEntities() {
        assertNull(DiscoveryParsers.upnp("<root><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType><friendlyName>Router</friendlyName></device></root>"))
        val d = DiscoveryParsers.upnp("<device><deviceType>x</deviceType><friendlyName>A &amp; B TV</friendlyName></device>", viaDial = true)!!
        assertEquals("A & B TV", d.name)
    }

    @Test fun castAndAirPlayRecords() {
        val c = DiscoveryParsers.googleCast("Chromecast-abc", mapOf("fn" to "Bedroom", "md" to "Chromecast Ultra"))
        assertEquals("Bedroom", c.name); assertEquals("Google", c.manufacturer)
        assertEquals("UN55", DiscoveryParsers.airPlay("Samsung TV", mapOf("model" to "UN55")).model)
    }

    @Test fun miracastPeersAreFilteredToDisplays() {
        assertEquals("Samsung 7 Series (55)", DiscoveryParsers.miracast("[TV] Samsung 7 Series (55)", "7-0050F204-1")?.name)
        assertNull(DiscoveryParsers.miracast("Pixel 8", "10-0050F204-5"))
    }

    @Test fun discoveredDeviceTextFeedsTheModelMatcher() {
        val d = DiscoveryParsers.upnp("<device><deviceType>MediaRenderer</deviceType><friendlyName>Den</friendlyName><manufacturer>Samsung Electronics</manufacturer><modelName>UE40D5000</modelName></device>")!!
        val tokens = ModelMatcher.tokens(d.text)
        assertTrue(ModelMatcher.score("Samsung", "Samsung UE40D5000", tokens) > ModelMatcher.score("Samsung", "Samsung AA59-00443A", tokens))
    }
}
