package app.nada.player.playback

import app.nada.player.data.Track
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Writer

internal data class RadioStation(val anchor: Track, val queueId: String?, val title: String?)
internal data class SavedQueueEntry(val entryId: String, val queueId: String?, val automatic: Boolean, val track: Track)
internal data class SavedQueue(val entries: List<SavedQueueEntry>, val order: List<Int>, val current: Int,
    val positionMs: Long, val shuffle: Boolean, val repeat: Int, val station: RadioStation?) {
    fun structure() = copy(current = 0, positionMs = 0)
}

/** Only stable identities and metadata are stored; media URLs are resolved again by the player. */
internal object SavedQueueCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    private val gson = Gson()
    fun encode(queue: SavedQueue, revision: String): String {
        validate(queue)
        val text = StringBuilder()
        val writer = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                require(length <= MAX_BYTES - text.length) { "Queue snapshot exceeds storage budget" }
                text.append(chars, offset, length)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        gson.toJson(mapOf("version" to 1, "revision" to revision, "queue" to queue), writer)
        return text.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }
    fun decode(text: String): Pair<String, SavedQueue> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JsonParser.parseString(text).asJsonObject
        require(root.get("version").asString == "1")
        val revision = root.string("revision", 100)
        val value = root.getAsJsonObject("queue")
        val raw = value.getAsJsonArray("entries")
        require(raw.size() in 1..MAX_ENTRIES)
        val entries = raw.map { item -> item.asJsonObject.let { entry ->
            SavedQueueEntry(entry.string("entryId", 100), entry.nullableString("queueId", 100),
                entry.bool("automatic"), track(entry.getAsJsonObject("track")))
        } }
        val order = value.getAsJsonArray("order")
        require(order.size() == entries.size)
        val station = value.get("station")?.takeUnless { it.isJsonNull }?.asJsonObject?.let {
            RadioStation(track(it.getAsJsonObject("anchor")), it.nullableString("queueId", 100), it.nullableString("title", 1024))
        }
        val queue = SavedQueue(entries, order.map { it.asString.toInt() }, value.get("current").asString.toInt(),
            value.get("positionMs").asString.toLong(), value.bool("shuffle"), value.get("repeat").asString.toInt(), station)
        validate(queue)
        return revision to queue
    }
    fun validate(queue: SavedQueue) {
        require(queue.entries.size in 1..MAX_ENTRIES && queue.current in queue.entries.indices)
        require(queue.order.size == queue.entries.size && queue.order.toSet() == queue.entries.indices.toSet())
        require(queue.positionMs in 0..Long.MAX_VALUE / 2 && queue.repeat in 0..2)
        require(queue.entries.map { it.entryId }.distinct().size == queue.entries.size)
        queue.entries.forEach {
            require(it.entryId.isNotBlank() && it.entryId.length <= 100 && (it.queueId?.length ?: 0) <= 100)
            validateTrack(it.track)
        }
        queue.station?.let {
            validateTrack(it.anchor)
            require(it.anchor.source == queue.entries[queue.current].track.source)
            require(it.queueId == queue.entries[queue.current].queueId && (it.title?.length ?: 0) <= 1024)
        }
    }
    private fun validateTrack(track: Track) {
        require(track.playableInNada && track.id.length <= 100 && track.title.length <= 1024 && track.artist.length <= 1024)
        require(track.artwork.length <= 4096 && track.permalink.length <= 4096 && track.genre.length <= 256 && track.duration >= 0)
    }
    private fun track(item: JsonObject) = Track(item.string("id", 100), item.string("title", 1024, true),
        item.string("artist", 1024, true), item.string("artwork", 4096, true), item.get("duration").asString.toInt(),
        item.string("genre", 256, true), item.string("permalink", 4096, true), item.string("source", 30))
    private fun JsonObject.string(key: String, limit: Int, empty: Boolean = false): String {
        val value = get(key).asJsonPrimitive
        require(value.isString)
        return value.asString.also { require(it.length <= limit && (empty || it.isNotBlank())) }
    }
    private fun JsonObject.nullableString(key: String, limit: Int): String? = get(key)?.takeUnless { it.isJsonNull }?.let { string(key, limit, true) }
    private fun JsonObject.bool(key: String): Boolean = get(key).asJsonPrimitive.let { require(it.isBoolean); it.asBoolean }
}
