package seeyuer.sayin.data

import org.json.JSONObject

/** Authenticated execution snapshot; deliberately separate from the live foreground hint. */
internal data class HistoryApplication(val name: String, val iconPng: String?) {
    fun display(): ForegroundApplication? = ForegroundApplication.from(ForegroundApplicationHint(name = name, iconPng = iconPng))
    fun protectedJson(item: OutboxItem): ByteArray = JSONObject().put("id", item.id).put("host", item.hostId)
        .put("device", item.deviceId).put("application", json()).toString().toByteArray(Charsets.UTF_8)
    private fun json() = JSONObject().put("name", name).apply { iconPng?.let { put("icon_png", it) } }

    companion object {
        fun parse(value: JSONObject): HistoryApplication? {
            val name = (value.opt("name") as? String)?.takeIf {
                it.isNotBlank() && it.codePointCount(0, it.length) <= 96 && it.none(Char::isISOControl)
            } ?: return null
            val icon = (value.opt("icon_png") as? String)?.takeIf { ForegroundApplicationHint.iconBytes(it) != null }
            return HistoryApplication(name, icon)
        }
        fun restore(bytes: ByteArray, item: OutboxItem): HistoryApplication? {
            // This envelope was produced locally and authenticated by Keystore.
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            require(value.getString("id") == item.id && value.getString("host") == item.hostId && value.getString("device") == item.deviceId)
            return parse(value.getJSONObject("application"))
        }
    }
}
