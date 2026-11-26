package seeyuer.sayin.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import seeyuer.sayin.pairing.NativePairing
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
    val certificateDigest = decode(json.getString("sha256")).also { require(it.size == 32) }
    init {
        validId(hostId)
        require((tlsName == "vl-$hostId.local" || tlsName == "sayin-$hostId.local") && port in 1..65535)
        val ip = numericAddress(address)
        require(ip is Inet4Address && !ip.isAnyLocalAddress && !ip.isMulticastAddress && address != "255.255.255.255")
    }

    fun validateCertificate(certificate: X509Certificate) {
        val encoded = certificate.encoded
        require(encoded.size in 1..4096)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(encoded), certificateDigest))
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        require(certificate.subjectAlternativeNames?.any { it[0] == 2 && it[1] == tlsName } == true)
    }
}

internal class PairedHost(json: JSONObject) : HostEndpoint(json) {
    val certificate: X509Certificate
    init {
        require(json.getInt("v") in 2..3)
        val encoded = decode(json.getString("cert"))
        require(encoded.size in 1..4096)
        certificate = CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
        require(MessageDigest.isEqual(encoded, certificate.encoded))
        validateCertificate(certificate)
    }
    val pairId = validId(json.optString("pair_id").ifEmpty { json.getString("device_id") })
    /** Kept as a source-level alias for image/history code while v3 uses pair_id on the wire. */
    val deviceId: String get() = pairId
    val token = json.getString("token").also { require(decode(it).size == 32) }

    fun withAddress(address: String, port: String): PairedHost = PairedHost(JSONObject(json.toString()).apply {
        put("address", address.trim())
        put("port", port.toInt().also { require(it in 1..65535) })
    })
}

/** A stable target entry shown by the phone. Selection is keyed by host id so a
 * re-pair that rotates pair_id keeps the same computer selected. */
internal data class PairedTarget(
    val hostId: String,
    val pairId: String,
    val address: String,
    val port: Int,
)

internal fun PairedHost.target(): PairedTarget = PairedTarget(hostId, pairId, address, port)

internal class PairingInvitation(json: JSONObject) : HostEndpoint(json) {
    init { require(json.getInt("v") == 3) }
    val secret = json.getString("secret").also { require(decode(it).size == 16) }

    fun candidate(response: JSONObject, certificate: X509Certificate): PairedHost {
        require(response.getString("host_id") == hostId)
        validateCertificate(certificate)
        val saved = JSONObject(json.toString()).apply {
            remove("secret")
            put("cert", encode(certificate.encoded))
            put("pair_id", response.getString("pair_id"))
            put("token", response.getString("token"))
        }
        return PairedHost(saved)
    }

    companion object {
        fun fromQr(value: String): PairingInvitation {
            require(value.length == 114 && value.startsWith("SAYIN3:"))
            val result = strictJson(NativePairing.parseQr(value))
            require(result.getBoolean("ok"))
            return PairingInvitation(result.getJSONObject("invitation"))
        }
    }
}
