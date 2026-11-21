package seeyuer.sayin.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ImageIssue { INVALID, NOT_READY, NOT_SUPPORTED, CONNECTION, STORAGE, QUOTA, CANCELLED }

/** A committed input spends the image intention: the next open starts from the picker. Cancelled,
 * failed and uncertain inputs keep the prepared image so the user can retry or inspect it. */
private const val IMAGE_CONSUMED_STATE = "injected"

internal data class LocalImageDraft(val image: ImageReference, val preview: ByteArray)
internal sealed interface ImageEvent {
    data class Select(val uri: Uri) : ImageEvent
    data class Prepared(val ticket: Long, val host: PairedHost, val image: ImageReference, val preview: ByteArray) : ImageEvent
    data class PreparationFailed(val ticket: Long) : ImageEvent
    data class Send(val resource: String, val reception: ReceptionScope?) : ImageEvent
    data class Ready(val run: ImageRun, val image: ImageReference) : ImageEvent
    data class Failed(val run: ImageRun) : ImageEvent
    data object StorageFailed : ImageEvent
    data object Cancel : ImageEvent
    data object Clear : ImageEvent
}
internal class ImageRun(val item: OutboxItem, val reception: ReceptionScope, val transfer: ImageTransfer, val active: AtomicBoolean) {
    var job: Job? = null
    var submitted = false
    fun stop() { active.set(false); transfer.close(); job?.cancel() }
}

/** Uses the repository's existing actor for all DB/reference/intent mutations.
 * Only bounded bitmap/file and socket work is delegated to child jobs. */
internal class ImageInput(
    private val app: Context,
    private val store: ProtectedStore,
    private val dao: OutboxDao,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ClientState>,
    private val host: () -> PairedHost?,
    private val client: () -> HostClient?,
    private val session: () -> String?,
    private val emit: (ImageEvent) -> Boolean,
    private val submit: (OutboxItem) -> Unit,
    private val storageFailure: () -> Unit,
) {
    private val files = ImageFiles(File(app.noBackupFilesDir, "images/${app.getDatabasePath("outbox.db").nameWithoutExtension}"), store::encryptImage, store::decryptImage)
    private val preparationLimit = Semaphore(1)
    private var preparation: Job? = null
    private var ticket = 0L
    private var binding: String? = null
    private var run: ImageRun? = null

    suspend fun open() {
        files.cleanOrphans(dao.imageResources().toSet())
        loadDraft()
    }
    suspend fun hostChanged() { ticket++; preparation?.cancel(); preparation=null; interrupt(); loadDraft(); state.update { it.copy(imagePreparing=false) } }
    private suspend fun loadDraft() {
        val saved = host()
        var draft = saved?.let { dao.imageDraft(it.hostId, it.deviceId) }
        // A finished request must not resurface as the next upload. The resource row is retained
        // for history, but the input draft is single-use.
        if (saved != null && draft != null) {
            val request = dao.imageRequest(saved.hostId, saved.deviceId, draft.resourceId)
            if (request != null && request.payload == null && request.state == IMAGE_CONSUMED_STATE) {
                dao.deleteImageDraft(saved.hostId, saved.deviceId)
                if (!dao.imageReferenced(draft.resourceId)) files.remove(draft.resourceId)
                draft = null
            }
        }
        val displayed = if (saved != null && draft != null) {
            val image = decodeReference(draft.reference)
            require(image.resourceId == draft.resourceId)
            val bytes = files.read(saved.hostId, saved.deviceId, image)
            try { LocalImageDraft(image, preview(bytes)) } finally { bytes.fill(0) }
        } else null
        state.update { it.copy(imageDraft = displayed) }
    }
    fun welcome(value: JSONObject) {
        binding = null
        val capabilities = value.getJSONArray("capabilities")
        val supported = (0 until capabilities.length()).any { capabilities.getString(it) == "image" }
        if (supported) {
            val channel = value.getJSONObject("image_channel")
            require(channel.getString("path") == "/v2/images" && channel.getInt("max_image_bytes") == MAX_IMAGE_BYTES &&
                channel.getInt("max_pixels") == MAX_IMAGE_PIXELS.toInt() && channel.getInt("max_side") == MAX_IMAGE_SIDE && channel.getInt("chunk_bytes") == IMAGE_CHUNK_BYTES)
            binding = channel.getString("binding").also { require(decode(it).size == 32) }
        }
        state.update { it.copy(imageSupported = supported) }
    }
    fun invalidate() {
        binding = null
        interrupt()
        state.update { it.copy(imageSupported = false) }
    }
    fun interrupt() { run?.let { it.stop(); emit(ImageEvent.Failed(it)) } }
    fun scopeChanged() {
        val current = run ?: return
        if (state.value.receptionScope != current.reception) { current.stop(); emit(ImageEvent.Failed(current)) }
    }
    fun close() { preparation?.cancel(); run?.stop() }

    suspend fun handle(event: ImageEvent) {
        when (event) {
            is ImageEvent.Select -> select(event.uri)
            is ImageEvent.Prepared -> prepared(event)
            is ImageEvent.PreparationFailed -> if (event.ticket == ticket) state.update { it.copy(imagePreparing = false, imageIssue = ImageIssue.INVALID) }
            is ImageEvent.Send -> send(event)
            is ImageEvent.Ready -> ready(event)
            is ImageEvent.Failed -> failed(event.run, ImageIssue.CONNECTION)
            ImageEvent.StorageFailed -> { run?.stop(); storageFailure() }
            ImageEvent.Cancel -> {
                ticket++; preparation?.cancel(); preparation = null
                run?.let { failed(it, ImageIssue.CANCELLED) }
                state.update { it.copy(imagePreparing = false) }
            }
            ImageEvent.Clear -> {
                ticket++; preparation?.cancel(); preparation = null
                run?.let { failed(it, ImageIssue.CANCELLED) }
                host()?.let { saved ->
                    val previous = dao.imageDraft(saved.hostId, saved.deviceId)
                    dao.deleteImageDraft(saved.hostId, saved.deviceId)
                    if (previous != null && !dao.imageReferenced(previous.resourceId)) files.remove(previous.resourceId)
                }
                state.update { it.copy(imageDraft = null, imagePreparing = false, imageIssue = null) }
            }
        }
    }
    private fun select(uri: Uri) {
        val saved = host() ?: return
        if (run != null) return
        ticket++; val owner = ticket
        preparation?.cancel()
        state.update { it.copy(imagePreparing = true, imageIssue = null, imageRequest = null) }
        preparation = scope.launch { preparationLimit.withPermit {
            var image: ImageReference? = null
            var transferred = false
            try {
                require(uri.scheme == "content")
                val source = requireNotNull(app.contentResolver.openInputStream(uri)).use { boundedImageRead(it) }
                try {
                    prepareMobileImage(source).use { prepared ->
                        currentCoroutineContext().ensureActive()
                        val thumbnail = preview(prepared.bytes)
                        image = files.write(saved.hostId, saved.deviceId, prepared.metadata, prepared.bytes)
                        currentCoroutineContext().ensureActive()
                        transferred = emit(ImageEvent.Prepared(owner, saved, checkNotNull(image), thumbnail))
                    }
                } finally { source.fill(0) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { emit(ImageEvent.PreparationFailed(owner)) }
            finally { if (!transferred) image?.let { files.remove(it.resourceId) } }
        } }
    }
    private suspend fun prepared(event: ImageEvent.Prepared) {
        if (event.ticket != ticket || host()?.hostId != event.host.hostId || host()?.deviceId != event.host.deviceId) {
            files.remove(event.image.resourceId); return
        }
        val previous = dao.imageDraft(event.host.hostId, event.host.deviceId)
        try {
            dao.saveImageDraft(ImageDraftEntity(event.host.hostId, event.host.deviceId, event.image.resourceId,
                store.encrypt(event.image.json().toString().toByteArray()), System.currentTimeMillis()))
            if (previous != null && !dao.imageReferenced(previous.resourceId)) files.remove(previous.resourceId)
        } catch (_: Exception) {
            if (!dao.imageReferenced(event.image.resourceId)) files.remove(event.image.resourceId)
            storageFailure(); return
        }
        preparation = null
        state.update { it.copy(imageDraft = LocalImageDraft(event.image, event.preview), imagePreparing = false, imageIssue = null) }
    }
    private suspend fun send(event: ImageEvent.Send) {
        if (run != null || state.value.imagePreparing) return
        val saved = host() ?: return
        val draft = state.value.imageDraft ?: return
        if (event.reception == null || state.value.receptionScope != event.reception || draft.image.resourceId != event.resource) { issue(ImageIssue.NOT_READY); return }
        val token = binding ?: run { issue(ImageIssue.NOT_SUPPORTED); return }
        val transport = client() ?: return
        val sessionId = session() ?: return
        if (dao.pendingCount() >= 16 || dao.pendingImagesFor(saved.hostId, saved.deviceId) >= 1 ||
            dao.pendingImageBytes().toLong() + draft.image.metadata.bytes > 3L * MAX_IMAGE_BYTES) { issue(ImageIssue.QUOTA); return }
        val item = OutboxItem(UUID.randomUUID().toString(), saved.hostId, saved.deviceId, event.reception.target,
            store.encrypt(JSONObject().put("kind", "image_pending").toString().toByteArray()), 0, "uploading", null, null, System.currentTimeMillis(),
            kind = "image", imageResource = draft.image.resourceId, imageReference = store.encrypt(draft.image.json().toString().toByteArray()), imageByteCount = draft.image.metadata.bytes)
        try { cleanup(dao.enqueue(item)) } catch (_: Exception) { storageFailure(); return }
        val active = AtomicBoolean(true)
        val transfer = ImageTransfer(transport::openImages, saved.hostId, saved.deviceId, sessionId, token, event.reception,
            { active.get() && state.value.receptionScope == event.reception })
        val current = ImageRun(item, event.reception, transfer, active)
        run = current
        state.update { it.copy(imageBusy = true, imageSubmitted = false, imageProgress = 0, imageIssue = null, imageRequest = item.id) }
        current.job = scope.launch {
            try {
                val bytes = try { files.read(saved.hostId, saved.deviceId, draft.image) }
                    catch (error: Exception) { emit(ImageEvent.StorageFailed); throw error }
                val image = try { transfer.upload(draft.image.metadata, bytes) { received ->
                    state.update { if (it.imageRequest == item.id && it.imageBusy) it.copy(imageProgress = received) else it }
                } } finally { bytes.fill(0) }
                if (!emit(ImageEvent.Ready(current, image))) current.stop()
            } catch (cancel: CancellationException) { active.set(false); current.stop(); throw cancel }
            catch (_: Exception) { active.set(false); current.stop(); emit(ImageEvent.Failed(current)) }
        }
    }
    private suspend fun ready(event: ImageEvent.Ready) {
        val current = event.run
        if (run !== current || !current.active.get() || state.value.receptionScope != current.reception) { failed(current, ImageIssue.NOT_READY); return }
        val payload = store.encrypt(JSONObject().put("kind", "image").put("image", event.image.json()).toString().toByteArray())
        try { check(dao.imageReady(current.item.id, current.item.hostId, current.item.deviceId, payload) == 1) }
        catch (_: Exception) { storageFailure(); return }
        current.submitted = true
        state.update { it.copy(imageSubmitted = true) }
        submit(current.item.copy(payload = payload, state = "saved"))
        current.job = scope.launch { delay(8000); current.stop(); emit(ImageEvent.Failed(current)) }
    }
    private suspend fun failed(current: ImageRun, failure: ImageIssue) {
        current.stop()
        if (!current.submitted) {
            try { dao.status(current.item.id, current.item.hostId, current.item.deviceId, "cancelled", null, "image_not_submitted", true) }
            catch (_: Exception) { storageFailure(); return }
        }
        if (run === current) { run = null; state.update { it.copy(imageBusy = false, imageSubmitted = false, imageIssue = failure) } }
    }
    suspend fun status(item: OutboxItem, status: String, terminal: Boolean) {
        if (item.id == state.value.imageRequest && item.kind == "image") state.update { it.copy(imageIssue = null) }
        val current = run
        if (current != null && item.id == current.item.id && item.hostId == current.item.hostId && item.deviceId == current.item.deviceId) {
            // The immutable reference has now been consumed by a durable receipt.
            current.stop(); run = null
            state.update { it.copy(imageBusy = false, imageSubmitted = false, imageIssue = null) }
        }
        if (terminal && status == IMAGE_CONSUMED_STATE) dropFinishedDraft(item)
    }

    /** Removes the single-use input draft once its request can never be uploaded again. */
    private suspend fun dropFinishedDraft(item: OutboxItem) {
        val saved = host() ?: return
        val draft = dao.imageDraft(saved.hostId, saved.deviceId) ?: return
        if (item.imageResource != draft.resourceId) return
        dao.deleteImageDraft(saved.hostId, saved.deviceId)
        if (!dao.imageReferenced(draft.resourceId)) files.remove(draft.resourceId)
        state.update {
            it.copy(
                imageDraft = null, imagePreparing = false,
                imageRequest = if (it.imageRequest == item.id) null else it.imageRequest,
            )
        }
    }
    suspend fun preview(resourceId: String, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
        val saved = host() ?: return@withContext null
        val draft = dao.imageDraft(saved.hostId, saved.deviceId) ?: return@withContext null
        if (draft.resourceId != resourceId) return@withContext null
        val image = runCatching { decodeReference(draft.reference) }.getOrNull() ?: return@withContext null
        val bytes = runCatching { files.read(saved.hostId, saved.deviceId, image) }.getOrNull() ?: return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val maxEdge = edge.coerceIn(256, 1600)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
        } finally { bytes.fill(0) }
    }

    suspend fun cleanup(ids: List<String>) { for (id in ids) if (!dao.imageReferenced(id)) files.remove(id) }
    suspend fun abandon(event: ImageEvent) {
        if (event is ImageEvent.Prepared && !dao.imageReferenced(event.image.resourceId)) files.remove(event.image.resourceId)
    }
    private fun issue(issue: ImageIssue) { state.update { it.copy(imageIssue = issue) } }
    private fun decodeReference(value: ByteArray): ImageReference {
        val decoded = store.decrypt(value)
        return try { ImageReference.parse(strictJson(decoded.toString(Charsets.UTF_8))) } finally { decoded.fill(0) }
    }
}

private fun preview(bytes: ByteArray): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false }))
    try { return ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray().also { require(it.size <= 512 * 1024) } } }
    finally { bitmap.recycle() }
}
