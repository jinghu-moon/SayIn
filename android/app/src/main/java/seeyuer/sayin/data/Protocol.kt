package seeyuer.sayin.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

internal fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
internal fun decode(value: String): ByteArray {
    require(value.length <= 8192)
    val bytes = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    require(encode(bytes) == value)
    return bytes
}

/** Reject duplicates, trailing documents, deep objects and malformed surrogates. */
internal fun strictJson(value: String): JSONObject {
    require(value.toByteArray(Charsets.UTF_8).size <= 65536)
    JsonReader(StringReader(value)).use { reader ->
        reader.isLenient = false
        fun read(depth: Int): Any {
            require(depth <= 8)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val result = JSONObject()
                    val keys = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        validUnicode(key)
                        require(keys.add(key))
                        result.put(key, read(depth + 1))
                    }
                    reader.endObject()
                    result
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    val result = JSONArray()
                    while (reader.hasNext()) result.put(read(depth + 1))
                    reader.endArray()
                    result
                }
                JsonToken.STRING -> reader.nextString().also(::validUnicode)
                JsonToken.NUMBER -> reader.nextString().let { number ->
                    require(number.length <= 24)
                    number.toLongOrNull() ?: error("Invalid integer")
                }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                else -> error("Invalid JSON")
            }
        }
        val result = read(0) as? JSONObject ?: error("Object required")
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return result
    }
}

private fun validUnicode(value: String) {
    var index = 0
    while (index < value.length) {
        val unit = value[index++]
        if (unit.isHighSurrogate()) {
            require(index < value.length && value[index++].isLowSurrogate())
        } else require(!unit.isLowSurrogate())
    }
}

internal fun normalizedText(value: String): String {
    validUnicode(value)
    val normalized = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
    require(normalized.none { it.code < 32 || it.code == 127 })
    require(normalized.isNotEmpty() && normalized.codePointCount(0, normalized.length) <= 4000)
    require(normalized.toByteArray(Charsets.UTF_8).size <= 16384)
    return normalized
}

internal fun numericAddress(value: String): InetAddress {
    // First release intentionally supports explicit IPv4 only. Never resolve
    // arbitrary user names on the unauthenticated bootstrap path.
    val segments = value.split('.')
    require(segments.size == 4 && segments.all { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } && it.length <= 3 && (it.length == 1 || it[0] != '0') && it.toInt() in 0..255 })
    return InetAddress.getByAddress(segments.map { it.toInt().toByte() }.toByteArray())
}

internal fun validId(id: String): String {
    val uuid = UUID.fromString(id)
    require(uuid.toString() == id && uuid.version() == 4 && uuid.variant() == 2)
    return id
}

internal open class HostEndpoint(val json: JSONObject) {
    val hostId: String = json.getString("host_id")
    val tlsName: String = json.getString("tls_name")
    val address: String = json.getString("address")
    val port: Int = json.getInt("port")
    val certificate: X509Certificate
    init {
        require(json.getInt("v") == 2)
        validId(hostId)
        require(tlsName == "vl-$hostId.local" && port in 1..65535)
        val ip = numericAddress(address)
        require(ip is Inet4Address && !ip.isAnyLocalAddress && !ip.isMulticastAddress)
        val cert = decode(json.getString("cert"))
        require(cert.size in 1..4096)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(cert), decode(json.getString("sha256"))))
        certificate = CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
        certificate.checkValidity()
        // Both self-signature and host-specific DNS SAN must be present before
        // building the dedicated trust store. OkHttp still verifies hostname.
        certificate.verify(certificate.publicKey)
        require(certificate.subjectAlternativeNames?.any { it[0] == 2 && it[1] == tlsName } == true)
    }
}

internal class PairedHost(json: JSONObject) : HostEndpoint(json) {
    val deviceId = validId(json.getString("device_id"))
    val token = json.getString("token").also { require(decode(it).size == 32) }
}

internal class PairingInvitation(json: JSONObject) : HostEndpoint(json) {
    val windowId = validId(json.getString("window_id"))
    val secret = json.getString("secret").also { require(decode(it).size == 16) }

    fun paired(response: JSONObject): PairedHost {
        require(response.getString("host_id") == hostId)
        val saved = JSONObject(json.toString()).apply {
            remove("window_id")
            remove("secret")
            put("device_id", response.getString("device_id"))
            put("token", response.getString("token"))
        }
        return PairedHost(saved)
    }

    companion object {
        fun fromQr(value: String): PairingInvitation {
            require(value.startsWith("voicelink://pair/"))
            return PairingInvitation(strictJson(decode(value.removePrefix("voicelink://pair/")).toString(Charsets.UTF_8)))
        }
    }
}
