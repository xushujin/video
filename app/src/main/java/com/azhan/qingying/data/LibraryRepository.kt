package com.azhan.qingying.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.azhan.qingying.util.NaturalOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AddFolderResult(
    val folderId: String?,
    val folderName: String,
    val addedCount: Int,
    val alreadyExists: Boolean = false,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}

/**
 * 视频库：目录（分组）与视频清单的唯一入口。
 *
 * 目录授权走 SAF（ACTION_OPEN_DOCUMENT_TREE）并 takePersistableUriPermission，
 * 因此不需要任何存储权限，重启后依然可读。
 *
 * 内存里保留一份快照（StateFlow）供 UI 直接读，写路径统一过 [mutate] 串行化，
 * 避免「扫描中又点了删除」这类并发写把 DataStore 里的清单冲掉。
 */
class LibraryRepository(
    private val context: Context,
    private val store: VideoStore,
    private val scanner: VideoScanner,
    private val scope: CoroutineScope,
) {

    private val mutex = Mutex()

    private val _folders = MutableStateFlow<List<VideoFolder>>(emptyList())
    val folders: StateFlow<List<VideoFolder>> = _folders.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, PlayProgress>>(emptyMap())
    val progress: StateFlow<Map<String, PlayProgress>> = _progress.asStateFlow()

    private val _prefs = MutableStateFlow(AppPrefs())
    val prefs: StateFlow<AppPrefs> = _prefs.asStateFlow()

    /** 正在补时长的目录 id，UI 用它显示「正在读取时长…」。 */
    private val _probing = MutableStateFlow<Set<String>>(emptySet())
    val probing: StateFlow<Set<String>> = _probing.asStateFlow()

    private var progressWriteJob: Job? = null

    init {
        // 只做一次「开机装载」，之后本进程就是这份数据的唯一写入方。
        //
        // 不用 collect 持续回流：DataStore 的写是异步的，乐观更新之后
        // 回流可能先送来旧值，把刚写进去的清单盖回去（扫描/补时长时尤其明显）。
        scope.launch {
            _folders.value = store.folders.first()
            _progress.value = store.progress.first()
            _prefs.value = store.prefs.first()
        }
    }

    // ------------------------------------------------------------ 目录管理

    /** 添加一个本地文件夹为分组。重复添加同一目录会直接重扫，而不是建第二条记录。 */
    suspend fun addFolder(treeUri: Uri): AddFolderResult = withContext(Dispatchers.IO) {
        val uriString = treeUri.toString()
        val existing = mutex.withLock { _folders.value.firstOrNull { it.treeUri == uriString } }
        if (existing != null) {
            val added = scanInto(existing.id, treeUri)
            return@withContext AddFolderResult(
                folderId = existing.id,
                folderName = existing.name,
                addedCount = added,
                alreadyExists = true,
            )
        }

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext AddFolderResult(null, "", 0, error = "无法读取该目录")

        val name = root.name?.takeIf { it.isNotBlank() } ?: "未命名目录"
        val folder = VideoFolder(
            id = uriString,
            name = name,
            treeUri = uriString,
            addedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        mutate { it + folder }
        val added = scanInto(folder.id, treeUri)
        AddFolderResult(folderId = folder.id, folderName = name, addedCount = added)
    }

    /** 重扫一个目录：新增文件入库，已删除的从清单里摘掉。 */
    suspend fun rescan(folderId: String): Int = withContext(Dispatchers.IO) {
        val folder = mutex.withLock { _folders.value.firstOrNull { it.id == folderId } }
            ?: return@withContext 0
        scanInto(folderId, Uri.parse(folder.treeUri))
    }

    suspend fun rescanAll(): Int = withContext(Dispatchers.IO) {
        var total = 0
        for (folder in mutex.withLock { _folders.value }) {
            total += scanInto(folder.id, Uri.parse(folder.treeUri))
        }
        total
    }

    suspend fun removeFolder(folderId: String) = withContext(Dispatchers.IO) {
        val folder = mutex.withLock { _folders.value.firstOrNull { it.id == folderId } }
        mutate { list -> list.filterNot { it.id == folderId } }
        folder?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(it.treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    /**
     * 扫描并覆盖该目录的视频清单。
     *
     * 保留已有视频的 `durationMs` / `width` / `height` —— 这些是后台探测补上的，
     * 每次重扫都丢掉的话，用户每重扫一次就要重新等一遍。
     */
    private suspend fun scanInto(folderId: String, treeUri: Uri): Int {
        val raw = scanner.scan(treeUri)
        val previous = mutex.withLock {
            _folders.value.firstOrNull { it.id == folderId }?.videos.orEmpty()
                .associateBy { it.id }
        }
        val now = System.currentTimeMillis()
        val videos = raw.map { item ->
            val old = previous[item.uri.toString()]
            VideoItem(
                id = item.uri.toString(),
                uri = item.uri.toString(),
                folderId = folderId,
                fileName = item.fileName,
                relPath = item.relPath,
                size = item.size,
                modified = item.modified,
                durationMs = old?.durationMs ?: 0L,
                width = old?.width ?: 0,
                height = old?.height ?: 0,
            )
        }.sortedWith(compareBy(NaturalOrder) { it.relPath })

        mutate { list ->
            list.map { folder ->
                if (folder.id == folderId) {
                    folder.copy(videos = videos, updatedAt = now)
                } else {
                    folder
                }
            }
        }
        return videos.size
    }

    // ------------------------------------------------------------ 时长探测

    /** 列表展开时调用：给还没探测过时长的视频补上时长与分辨率。 */
    suspend fun probeDurations(folderId: String) {
        val pending = mutex.withLock {
            _folders.value.firstOrNull { it.id == folderId }
                ?.videos.orEmpty()
                .filter { it.durationMs <= 0L }
        }
        if (pending.isEmpty()) return
        _probing.value = _probing.value + folderId
        try {
            val resolved = HashMap<String, VideoScanner.ProbeResult>(pending.size)
            pending.chunked(PROBE_BATCH).forEach { batch ->
                for (video in batch) {
                    val result = scanner.probe(Uri.parse(video.uri)) ?: continue
                    if (result.durationMs > 0L) resolved[video.id] = result
                }
                if (resolved.isEmpty()) return@forEach
                applyProbeResults(folderId, resolved)
            }
        } finally {
            _probing.value = _probing.value - folderId
        }
    }

    private suspend fun applyProbeResults(
        folderId: String,
        resolved: Map<String, VideoScanner.ProbeResult>,
    ) {
        mutate { list ->
            list.map { folder ->
                if (folder.id != folderId) return@map folder
                folder.copy(
                    videos = folder.videos.map { video ->
                        val result = resolved[video.id] ?: return@map video
                        video.copy(
                            durationMs = result.durationMs,
                            width = result.width,
                            height = result.height,
                        )
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------ 播放进度

    /**
     * 记录播放进度。播放中每几秒调一次，所以先在内存里改，再防抖落盘 ——
     * 否则 DataStore 每秒写一次文件。
     */
    fun saveProgress(videoId: String, positionMs: Long, durationMs: Long) {
        if (videoId.isBlank()) return
        val snapshot = _progress.value.toMutableMap()
        snapshot[videoId] = PlayProgress(
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis(),
        )
        _progress.value = snapshot
        progressWriteJob?.cancel()
        progressWriteJob = scope.launch {
            kotlinx.coroutines.delay(PROGRESS_DEBOUNCE_MS)
            store.saveProgress(_progress.value)
        }
    }

    suspend fun clearProgress(videoId: String) {
        _progress.value = _progress.value - videoId
        store.saveProgress(_progress.value)
    }

    fun clearAllProgress() {
        _progress.value = emptyMap()
        progressWriteJob?.cancel()
        progressWriteJob = scope.launch { store.saveProgress(emptyMap()) }
    }

    /** 「继续观看」用：按最近播放时间倒序，且过滤掉刚开始看和已经看完的。 */
    fun resumable(): List<Pair<VideoItem, PlayProgress>> {
        val all = _folders.value.flatMap { it.videos }.associateBy { it.id }
        return _progress.value
            .filterValues { it.isResumable }
            .entries
            .sortedByDescending { it.value.updatedAt }
            .mapNotNull { entry -> all[entry.key]?.let { it to entry.value } }
    }

    // ------------------------------------------------------------ 偏好

    suspend fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        store.savePrefs(next)
    }

    // ------------------------------------------------------------ 内部

    /** 串行化所有对清单的写入：改内存快照 → 落盘。 */
    private suspend fun mutate(transform: (List<VideoFolder>) -> List<VideoFolder>) {
        val updated = mutex.withLock {
            val next = transform(_folders.value)
            _folders.value = next
            next
        }
        store.saveFolders(updated)
    }

    companion object {
        /** 每批探测这么多视频就落一次盘，进度可见、又不至于频繁写文件。 */
        private const val PROBE_BATCH = 24

        /** 进度写盘防抖：快速拖动进度条时不会疯狂写库。 */
        private const val PROGRESS_DEBOUNCE_MS = 800L
    }
}
