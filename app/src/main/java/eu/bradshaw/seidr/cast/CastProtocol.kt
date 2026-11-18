package eu.bradshaw.seidr.cast

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Cast v2 JSON messages Seiðr uses, after pychromecast and Google's
 * published receiver docs. Pure: builders and parsers only.
 */
object CastProtocol {
    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    const val PLATFORM = "receiver-0"
    /** Google's Default Media Receiver: needs no registration. */
    const val DEFAULT_RECEIVER = "CC1AD845"

    /** The receiver starts preloading the next item this many seconds before the end. */
    const val PRELOAD_S = 20

    fun type(type: String, requestId: Long? = null, body: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("type", type)
        if (requestId != null) put("requestId", requestId)
        body()
    }

    /** What the receiver plays for one queue slot. [key] comes back as `contentId`, so status names the slot. */
    data class Item(
        val key: String,
        val url: String,
        val contentType: String,
        val title: String,
        val artist: String,
        val album: String,
        val art: String?,
        val durationS: Int,
    )

    fun item(i: Item, autoplay: Boolean = true, startS: Double = 0.0): JsonObject = buildJsonObject {
        putJsonObject("media") {
            put("contentId", i.key)
            put("contentUrl", i.url)
            put("streamType", "BUFFERED")
            put("contentType", i.contentType)
            if (i.durationS > 0) put("duration", i.durationS)
            putJsonObject("metadata") {
                put("metadataType", 3) // MusicTrackMediaMetadata
                put("title", i.title)
                put("artist", i.artist)
                put("albumName", i.album)
                if (i.art != null) putJsonArray("images") { add(buildJsonObject { put("url", i.art) }) }
            }
        }
        put("autoplay", autoplay)
        put("preloadTime", PRELOAD_S)
        if (startS > 0) put("startTime", startS)
    }

    fun queueLoad(requestId: Long, items: List<Item>, play: Boolean, startS: Double, repeatOne: Boolean) =
        type("QUEUE_LOAD", requestId) {
            put("items", buildJsonArray {
                items.forEachIndexed { n, it -> add(item(it, autoplay = n > 0 || play, startS = if (n == 0) startS else 0.0)) }
            })
            put("startIndex", 0)
            put("repeatMode", repeatMode(repeatOne))
        }

    fun queueInsert(requestId: Long, session: Long, items: List<Item>) = type("QUEUE_INSERT", requestId) {
        put("mediaSessionId", session)
        put("items", buildJsonArray { items.forEach { add(item(it)) } })
    }

    fun queueRemove(requestId: Long, session: Long, itemIds: List<Int>) = type("QUEUE_REMOVE", requestId) {
        put("mediaSessionId", session)
        put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it)) } })
    }

    fun queueRepeat(requestId: Long, session: Long, repeatOne: Boolean) = type("QUEUE_UPDATE", requestId) {
        put("mediaSessionId", session)
        put("repeatMode", repeatMode(repeatOne))
    }

    /** Plays [itemId], already in the receiver's queue, from [positionS]. */
    fun queueJump(requestId: Long, session: Long, itemId: Int, positionS: Double) = type("QUEUE_UPDATE", requestId) {
        put("mediaSessionId", session)
        put("currentItemId", itemId)
        if (positionS > 0) put("currentTime", positionS)
    }

    fun queueItemIds(requestId: Long, session: Long) = type("QUEUE_GET_ITEM_IDS", requestId) { put("mediaSessionId", session) }

    fun queueItems(requestId: Long, session: Long, itemIds: List<Int>) = type("QUEUE_GET_ITEMS", requestId) {
        put("mediaSessionId", session)
        put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it)) } })
    }

    fun mediaCommand(type: String, requestId: Long, session: Long) = type(type, requestId) { put("mediaSessionId", session) }

    fun seek(requestId: Long, session: Long, positionS: Double) = type("SEEK", requestId) {
        put("mediaSessionId", session)
        put("currentTime", positionS)
    }

    fun setVolume(requestId: Long, level: Double) = type("SET_VOLUME", requestId) {
        putJsonObject("volume") { put("level", level.coerceIn(0.0, 1.0)) }
    }

    fun launch(requestId: Long, appId: String = DEFAULT_RECEIVER) = type("LAUNCH", requestId) { put("appId", appId) }

    fun stop(requestId: Long, sessionId: String) = type("STOP", requestId) { put("sessionId", sessionId) }

    // Seiðr's repeat-all is its own: it keeps feeding the receiver from the top.
    private fun repeatMode(one: Boolean) = if (one) "REPEAT_SINGLE" else "REPEAT_OFF"

    // --- Parsing ---------------------------------------------------------------------

    data class App(val appId: String, val sessionId: String, val transportId: String)

    data class ReceiverStatus(val apps: List<App>, val volume: Double?, val muted: Boolean, val step: Double?)

    data class MediaStatus(
        val mediaSessionId: Long,
        /** IDLE, PLAYING, PAUSED or BUFFERING. */
        val playerState: String,
        /** With IDLE: FINISHED, CANCELLED, INTERRUPTED or ERROR. */
        val idleReason: String?,
        val currentTimeS: Double,
        val currentItemId: Int?,
        /** The playing slot's key; absent when the receiver didn't resend the media. */
        val contentId: String?,
        val durationS: Double?,
    )

    fun receiverStatus(msg: JsonObject): ReceiverStatus? {
        val status = msg["status"] as? JsonObject ?: return null
        val apps = (status["applications"] as? JsonArray).orEmpty().mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            App(o.str("appId") ?: return@mapNotNull null, o.str("sessionId") ?: return@mapNotNull null,
                o.str("transportId") ?: return@mapNotNull null)
        }
        val volume = status["volume"] as? JsonObject
        return ReceiverStatus(
            apps,
            volume?.get("level")?.jsonPrimitive?.doubleOrNull,
            volume?.get("muted")?.jsonPrimitive?.booleanOrNull == true,
            volume?.get("stepInterval")?.jsonPrimitive?.doubleOrNull,
        )
    }

    /** Null when the media session is gone (an empty status list). */
    fun mediaStatus(msg: JsonObject): MediaStatus? {
        val s = (msg["status"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val media = s["media"] as? JsonObject
        return MediaStatus(
            mediaSessionId = s["mediaSessionId"]?.jsonPrimitive?.longOrNull ?: return null,
            playerState = s.str("playerState") ?: "IDLE",
            idleReason = s.str("idleReason"),
            currentTimeS = s["currentTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            currentItemId = s["currentItemId"]?.jsonPrimitive?.intOrNull,
            contentId = media?.str("contentId"),
            durationS = media?.get("duration")?.jsonPrimitive?.doubleOrNull,
        )
    }

    /** QUEUE_ITEM_IDS: the receiver's queue order. */
    fun itemIds(msg: JsonObject): List<Int> =
        (msg["itemIds"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }

    /** QUEUE_ITEMS: item id → the slot key it plays. */
    fun itemKeys(msg: JsonObject): Map<Int, String> =
        (msg["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["itemId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val key = o["media"]?.jsonObject?.str("contentId") ?: return@mapNotNull null
            id to key
        }.toMap()

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
}
