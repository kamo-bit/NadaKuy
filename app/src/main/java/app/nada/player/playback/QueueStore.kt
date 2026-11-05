package app.nada.player.playback

import android.content.Context
import android.util.AtomicFile
import app.nada.player.data.readBounded
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

/** One application-owned writer survives service recreation; each file replacement is atomic. */
internal class QueueStore(directory: File) {
    private val base = AtomicFile(File(directory, "queue.json"))
    private val checkpoint = AtomicFile(File(directory, "position.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Pending(val value: SavedQueue?, val sequence: Long, val error: String? = null)
    private val writes = Channel<Pending>(Channel.CONFLATED)
    private val monitor = Any()
    private var loaded = false
    private var memory: SavedQueue? = null
    private var submitted = 0L
    private var diskStructure: SavedQueue? = null
    private var revision = ""
    private var validatedStructure: SavedQueue? = null
    private val written = MutableStateFlow(0L)
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    init {
        scope.launch {
            for (pending in writes) {
                try {
                    write(pending.value)
                    mutableError.value = pending.error
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) {
                    mutableError.value = "Antrean belum tersimpan. Periksa ruang perangkat; perubahan berikutnya akan dicoba lagi."
                } finally { written.value = pending.sequence }
            }
        }
    }
    suspend fun load(): SavedQueue? {
        val generation = synchronized(monitor) { if (loaded) return memory; submitted }
        val value = withContext(Dispatchers.IO) { try {
            if (!base.baseFile.exists() && !File(base.baseFile.path + ".bak").exists()) return@withContext null
            val bytes = base.openRead().use { it.readBounded(SavedQueueCodec.MAX_BYTES) }
            require(bytes.size <= SavedQueueCodec.MAX_BYTES)
            val (id, saved) = SavedQueueCodec.decode(bytes.toString(Charsets.UTF_8))
            runCatching {
                val position = checkpoint.openRead().use { it.readBounded(4096) }
                require(position.size <= 4096)
                val state = JsonParser.parseString(position.toString(Charsets.UTF_8)).asJsonObject
                require(state.get("revision").asString == id)
                val index = saved.entries.indexOfFirst { it.entryId == state.get("entryId").asString }
                require(index >= 0)
                saved.copy(current = index, positionMs = state.get("positionMs").asString.toLong()).also(SavedQueueCodec::validate)
            }.getOrDefault(saved)
        } catch (_: Exception) {
            mutableError.value = "Antrean terakhir belum bisa dipulihkan. Pilih lagu untuk memulai antrean baru."
            null
        } }
        return synchronized(monitor) {
            if (submitted == generation) { memory = value; loaded = true }
            memory
        }
    }
    fun save(queue: SavedQueue?) {
        try {
            queue?.let {
                val shape = it.structure()
                if (shape != validatedStructure) { SavedQueueCodec.validate(it); validatedStructure = shape }
                require(it.current in it.entries.indices && it.positionMs in 0..Long.MAX_VALUE / 2)
                require(it.station == null || it.station.queueId == it.entries[it.current].queueId)
            }
        } catch (_: IllegalArgumentException) {
            val sequence = synchronized(monitor) { memory = null; loaded = true; ++submitted }
            val message = "Antrean ini belum bisa disimpan. Pilih lagu dari katalog NadaKuy untuk memulai antrean baru."
            writes.trySend(Pending(null, sequence, message))
            mutableError.value = message
            return
        }
        val sequence = synchronized(monitor) { memory = queue; loaded = true; ++submitted }
        writes.trySend(Pending(queue, sequence))
    }
    internal suspend fun awaitWrites() { val sequence = synchronized(monitor) { submitted }; written.first { it >= sequence } }
    internal suspend fun close() { awaitWrites(); writes.close(); scope.cancel() }
    private fun write(value: SavedQueue?) {
        if (value == null) { base.delete(); checkpoint.delete(); diskStructure = null; revision = ""; return }
        val shape = value.structure()
        if (shape != diskStructure) {
            val next = UUID.randomUUID().toString()
            val data = SavedQueueCodec.encode(value, next).toByteArray(Charsets.UTF_8)
            replace(base, data)
            revision = next
            diskStructure = shape
        }
        val data = JsonObject().apply {
            addProperty("revision", revision); addProperty("entryId", value.entries[value.current].entryId)
            addProperty("positionMs", value.positionMs)
        }.toString().toByteArray(Charsets.UTF_8)
        replace(checkpoint, data)
    }
    private fun replace(file: AtomicFile, bytes: ByteArray) {
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    companion object {
        @Volatile private var instance: QueueStore? = null
        fun get(context: Context): QueueStore = instance ?: synchronized(this) {
            instance ?: QueueStore(File(context.noBackupFilesDir, "playback")).also { instance = it }
        }
    }
}
