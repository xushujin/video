package com.azhan.qingying.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azhan.qingying.AppContainer
import com.azhan.qingying.data.PlayProgress
import com.azhan.qingying.data.VideoFolder
import com.azhan.qingying.data.VideoItem
import com.azhan.qingying.ui.common.VideoThumbnail
import com.azhan.qingying.ui.neu.NeuButton
import com.azhan.qingying.ui.neu.NeuIconButton
import com.azhan.qingying.ui.neu.NeuProgress
import com.azhan.qingying.ui.neu.NeuSurface
import com.azhan.qingying.ui.neu.neuInner
import com.azhan.qingying.ui.theme.LocalNeuPalette
import com.azhan.qingying.util.formatDate
import com.azhan.qingying.util.formatDuration
import com.azhan.qingying.util.formatSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页：一个本地文件夹 = 一个分组，分组名就是文件夹名（需求 1）。
 *
 * 目录通过系统文件选择器（SAF）授权，不申请任何存储权限。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val folders by container.library.folders.collectAsState()
    val progress by container.library.progress.collectAsState()
    val prefs by container.library.prefs.collectAsState()
    val probing by container.library.probing.collectAsState()

    // 展开状态：默认折叠，点分组头展开
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var pendingRemoval by remember { mutableStateOf<VideoFolder?>(null) }
    var scanning by remember { mutableStateOf(false) }

    // 搜索。用 rememberSaveable 而不是 remember：去设置页再回来时
    // Navigation Compose 会销毁 HOME 的组合，普通 remember 保不住搜索词。
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // 一次遍历把命中的 (分组, 视频) 摊平。上限 3000 个文件，纯内存过滤，
    // 快到不需要防抖 —— 加了反而让输入有延迟感。
    val results = remember(folders, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            folders.flatMap { folder ->
                folder.videos.filter { it.matches(q) }.map { SearchHit(folder, it) }
            }
        }
    }

    // 搜索时按系统返回键：先退出搜索，而不是直接退出应用。
    // 只在 searching 为 true 时接管，平时不影响导航。
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    val resumable = remember(folders, progress) { container.library.resumable() }
    val totalVideos = folders.sumOf { it.count }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            scanning = true
            val result = container.library.addFolder(uri)
            scanning = false
            val message = when {
                !result.isSuccess -> "添加失败：${result.error}"
                result.alreadyExists -> "「${result.folderName}」已重新扫描，共 ${result.addedCount} 个视频"
                result.addedCount == 0 -> "「${result.folderName}」里没有找到支持的视频文件"
                else -> "已添加「${result.folderName}」，${result.addedCount} 个视频"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // enableEdgeToEdge 之后内容会铺到状态栏下面，这里统一避开系统栏
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "topbar") {
                TopBar(
                    folderCount = folders.size,
                    videoCount = totalVideos,
                    scanning = scanning,
                    searching = searching,
                    query = query,
                    onQueryChange = { query = it },
                    onToggleSearch = {
                        searching = !searching
                        if (!searching) query = ""
                    },
                    onAddFolder = { picker.launch(null) },
                    onRescanAll = {
                        scope.launch {
                            scanning = true
                            val count = container.library.rescanAll()
                            scanning = false
                            Toast.makeText(context, "重新扫描完成，共 $count 个视频", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenSettings = onOpenSettings,
                )
            }

            if (searching) {
                val q = query.trim()
                if (q.isEmpty()) {
                    item(key = "search-hint") { SearchHint() }
                } else {
                    item(key = "search-count") {
                        SectionTitle(
                            if (results.isEmpty()) "没有匹配的视频" else "找到 ${results.size} 个视频",
                        )
                    }
                    if (results.isEmpty()) {
                        item(key = "search-empty") { NoResultState(q) }
                    } else {
                        itemsIndexed(results, key = { _, hit -> "s-${hit.video.id}" }) { _, hit ->
                            SearchResultRow(
                                container = container,
                                folderName = hit.folder.name,
                                video = hit.video,
                                query = q,
                                progress = progress[hit.video.id],
                                autoResume = prefs.autoResume,
                                playing = container.player.currentVideoId == hit.video.id,
                                onClick = {
                                    val index = hit.folder.videos
                                        .indexOfFirst { it.id == hit.video.id }
                                        .coerceAtLeast(0)
                                    val startAt = if (prefs.autoResume) {
                                        progress[hit.video.id]?.takeIf { it.isResumable }?.positionMs ?: 0L
                                    } else {
                                        0L
                                    }
                                    container.player.play(hit.folder.videos, index, startAt)
                                    onOpenPlayer()
                                },
                            )
                        }
                    }
                }
            } else {
                if (folders.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(onAddFolder = { picker.launch(null) })
                    }
                }

                if (resumable.isNotEmpty()) {
                    item(key = "resume-title") {
                        SectionTitle("继续观看")
                    }
                    item(key = "resume-row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(resumable, key = { it.first.id }) { (video, item) ->
                                ResumeCard(
                                    container = container,
                                    video = video,
                                    progress = item,
                                    onClick = {
                                        val folder = folders.firstOrNull { it.id == video.folderId }
                                        val queue = folder?.videos ?: listOf(video)
                                        val index = queue.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
                                        container.player.play(queue, index, item.positionMs)
                                        onOpenPlayer()
                                    },
                                )
                            }
                        }
                    }
                }

                if (folders.isNotEmpty()) {
                    item(key = "library-title") {
                        SectionTitle("视频库")
                    }
                }

                folders.forEach { folder ->
                    item(key = "folder-${folder.id}") {
                        FolderHeader(
                            folder = folder,
                            expanded = expanded[folder.id] == true,
                            probing = folder.id in probing,
                            onToggle = { expanded[folder.id] = expanded[folder.id] != true },
                            onRescan = {
                                scope.launch {
                                    val count = container.library.rescan(folder.id)
                                    Toast.makeText(context, "「${folder.name}」共 $count 个视频", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onRemove = { pendingRemoval = folder },
                        )
                    }

                    if (expanded[folder.id] == true) {
                        // 展开时才去补时长：上千个视频逐个开 MediaMetadataRetriever 太慢，
                        // 放在扫描阶段会让「添加目录」卡住。
                        // 注意：LaunchedEffect 不能直接写在 LazyListScope 的 builder 里
                        //（那里不是 @Composable 上下文），所以借一个零高度的 item 来挂。
                        item(key = "probe-${folder.id}") {
                            LaunchedEffect(folder.id) {
                                container.library.probeDurations(folder.id)
                            }
                        }
                        itemsIndexed(folder.videos, key = { _, video -> "v-${video.id}" }) { index, video ->
                            VideoRow(
                                container = container,
                                video = video,
                                progress = progress[video.id],
                                autoResume = prefs.autoResume,
                                playing = container.player.currentVideoId == video.id,
                                onClick = {
                                    val startAt = if (prefs.autoResume) {
                                        progress[video.id]?.takeIf { it.isResumable }?.positionMs ?: 0L
                                    } else {
                                        0L
                                    }
                                    container.player.play(folder.videos, index, startAt)
                                    onOpenPlayer()
                                },
                            )
                        }
                    }
                }
            }
        }

        MiniPlayerBar(
            container = container,
            onOpenPlayer = onOpenPlayer,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    pendingRemoval?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = { Text("移除「${folder.name}」？") },
            text = { Text("只会从列表里移除这个分组，不会删除手机上的任何文件。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = folder
                    pendingRemoval = null
                    scope.launch { container.library.removeFolder(target.id) }
                }) {
                    Text("移除", color = palette.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text("取消", color = palette.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun TopBar(
    folderCount: Int,
    videoCount: Int,
    scanning: Boolean,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onAddFolder: () -> Unit,
    onRescanAll: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    val focusRequester = remember { FocusRequester() }

    // 进搜索模式就自动聚焦、弹键盘。
    // 等一小会儿再 requestFocus：节点要先挂到窗口上，否则会抛
    // "FocusRequester is not initialized"。
    LaunchedEffect(searching) {
        if (searching) {
            delay(150)
            focusRequester.requestFocus()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        if (searching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeuIconButton(onClick = onToggleSearch, size = 42.dp) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "退出搜索",
                        tint = palette.textPrimary,
                    )
                }
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    focusRequester = focusRequester,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "轻影",
                        color = palette.textPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (scanning) "正在扫描…" else "$folderCount 个文件夹 · $videoCount 个视频",
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                    )
                }
                NeuIconButton(onClick = onToggleSearch, size = 42.dp) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = palette.textPrimary)
                }
                NeuIconButton(onClick = onRescanAll, size = 42.dp) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描", tint = palette.textPrimary)
                }
                NeuIconButton(onClick = onOpenSettings, size = 42.dp) {
                    Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = palette.textPrimary)
                }
            }

            NeuButton(
                onClick = onAddFolder,
                highlighted = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "添加视频文件夹",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 一个命中结果：视频 + 它所属的分组（列表里要标出「这是哪个文件夹的」）。 */
private data class SearchHit(val folder: VideoFolder, val video: VideoItem)

/**
 * 搜索输入框。用 BasicTextField 而不是 Material 的 TextField ——
 * 后者的容器、下划线、内边距都要一条条覆盖掉才能融进拟态风格，得不偿失。
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNeuPalette.current
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .background(palette.surface, shape)
            .neuInner(14.dp, palette, 4.dp)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("搜索文件名或子目录", color = palette.textSecondary, fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "清空",
                tint = palette.textSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun SearchHint() {
    val palette = LocalNeuPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("输入关键词开始搜索", color = palette.textPrimary, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "按文件名或子目录匹配，忽略大小写。\n搜索范围是全部已添加文件夹里的视频。",
            color = palette.textSecondary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun NoResultState(query: String) {
    val palette = LocalNeuPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.VideoLibrary,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("没有找到「$query」", color = palette.textPrimary, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "换个关键词试试。只匹配文件名与子目录，不匹配文件夹名。",
            color = palette.textSecondary,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * 搜索结果行。与 [VideoRow] 的区别：多一行「所属文件夹」，标题里高亮命中片段。
 * 播放队列仍是该视频所在的整个文件夹，所以播完会自动连播同目录的下一集。
 */
@Composable
private fun SearchResultRow(
    container: AppContainer,
    folderName: String,
    video: VideoItem,
    query: String,
    progress: PlayProgress?,
    autoResume: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    val fraction = if (autoResume) progress?.fraction ?: 0f else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumbnail(
            loader = container.thumbnails,
            uri = video.uri,
            modifier = Modifier.width(104.dp),
            cornerRadius = 8.dp,
            badge = video.durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            HighlightedTitle(
                text = video.title,
                query = query,
                color = if (playing) palette.accent else palette.textPrimary,
                highlightColor = palette.accent,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(folderName)
                    video.relPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                        ?.let { append(" / $it") }
                    append(" · ${video.ext.uppercase()}")
                    if (video.size > 0) append(" · ${formatSize(video.size)}")
                },
                color = palette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction > 0.01f) {
                Spacer(Modifier.height(5.dp))
                NeuProgress(progress = fraction, height = 3.dp)
            }
        }
    }
}

/** 把标题里命中 [query] 的那一段染成强调色，只高亮第一处。 */
@Composable
private fun HighlightedTitle(
    text: String,
    query: String,
    color: androidx.compose.ui.graphics.Color,
    highlightColor: androidx.compose.ui.graphics.Color,
    fontWeight: FontWeight,
) {
    val annotated = remember(text, query, color, highlightColor, fontWeight) {
        buildAnnotatedString {
            val index = text.indexOf(query, ignoreCase = true)
            if (query.isBlank() || index < 0) {
                append(text)
                return@buildAnnotatedString
            }
            append(text.substring(0, index))
            withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                append(text.substring(index, index + query.length))
            }
            append(text.substring(index + query.length))
        }
    }
    Text(
        text = annotated,
        color = color,
        fontSize = 14.sp,
        fontWeight = fontWeight,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SectionTitle(text: String) {
    val palette = LocalNeuPalette.current
    Text(
        text = text,
        color = palette.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyState(onAddFolder: () -> Unit) {
    val palette = LocalNeuPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.VideoLibrary,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("还没有视频", color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "点上面的按钮选一个本地文件夹，\n列表会按文件夹名分组显示。",
            color = palette.textSecondary,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun FolderHeader(
    folder: VideoFolder,
    expanded: Boolean,
    probing: Boolean,
    onToggle: () -> Unit,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    var menuOpen by remember { mutableStateOf(false) }

    NeuSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        elevation = 6.dp,
        onClick = onToggle,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    color = palette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("${folder.count} 个视频")
                        if (folder.totalSize > 0) append(" · ${formatSize(folder.totalSize)}")
                        if (probing) append(" · 正在读取时长…")
                    },
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                )
            }

            Box {
                NeuIconButton(onClick = { menuOpen = true }, size = 34.dp, elevation = 4.dp) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "更多",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重新扫描") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRescan()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("移除分组") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }

            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun VideoRow(
    container: AppContainer,
    video: VideoItem,
    progress: PlayProgress?,
    autoResume: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current
    val fraction = if (autoResume) progress?.fraction ?: 0f else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumbnail(
            loader = container.thumbnails,
            uri = video.uri,
            modifier = Modifier.width(104.dp),
            cornerRadius = 8.dp,
            badge = video.durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = if (playing) palette.accent else palette.textPrimary,
                fontSize = 14.sp,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(video.ext.uppercase())
                    video.qualityLabel?.let { append(" · $it") }
                    if (video.size > 0) append(" · ${formatSize(video.size)}")
                    if (video.modified > 0) append(" · ${formatDate(video.modified)}")
                },
                color = palette.textSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction > 0.01f) {
                Spacer(Modifier.height(5.dp))
                NeuProgress(progress = fraction, height = 3.dp)
            }
        }
    }
}

@Composable
private fun ResumeCard(
    container: AppContainer,
    video: VideoItem,
    progress: PlayProgress,
    onClick: () -> Unit,
) {
    val palette = LocalNeuPalette.current

    NeuSurface(
        modifier = Modifier.width(196.dp),
        cornerRadius = 16.dp,
        elevation = 6.dp,
        onClick = onClick,
        contentAlignment = Alignment.TopStart,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            VideoThumbnail(
                loader = container.thumbnails,
                uri = video.uri,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 10.dp,
                badge = video.durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = video.title,
                color = palette.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            NeuProgress(progress = progress.fraction, height = 3.dp)
            Spacer(Modifier.height(3.dp))
            Text(
                text = "看到 ${formatDuration(progress.positionMs)}",
                color = palette.textSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * 底部常驻条：只要播放器里有东西（哪怕切后台在放音频），
 * 就能一眼看到当前在播什么，点一下回到播放页。
 */
@Composable
private fun MiniPlayerBar(
    container: AppContainer,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalNeuPalette.current
    val player = container.player
    val videoId = player.currentVideoId ?: return
    val folders by container.library.folders.collectAsState()
    val video = remember(folders, videoId) {
        folders.asSequence().flatMap { it.videos.asSequence() }.firstOrNull { it.id == videoId }
    }

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
        NeuSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 18.dp,
            elevation = 8.dp,
            onClick = onOpenPlayer,
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoThumbnail(
                    loader = container.thumbnails,
                    uri = video?.uri.orEmpty(),
                    modifier = Modifier.width(56.dp),
                    cornerRadius = 6.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video?.title ?: "正在播放",
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (player.videoTrackEnabled) "正在播放" else "后台播放音频中",
                        color = palette.textSecondary,
                        fontSize = 10.sp,
                    )
                }
                NeuIconButton(onClick = { player.toggle() }, size = 36.dp, elevation = 4.dp) {
                    Icon(
                        imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (player.isPlaying) "暂停" else "播放",
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
