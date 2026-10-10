package com.azhan.qingying.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "qing_ying")

/**
 * 极简持久化：DataStore + JSON，不引 Room。
 *
 * 拆成三个 key 是有意的：
 * - `folders`  目录与视频清单，只在添加/重扫/补时长时写
 * - `progress` 播放进度，播放中每几秒写一次（高频）
 * - `prefs`    用户偏好，偶尔写
 *
 * 混在一个 key 里的话，播放进度的高频写入会把整个视频清单反复序列化一遍。
 */
class VideoStore(context: Context) {

    private val appContext = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val foldersKey = stringPreferencesKey("folders")
    private val progressKey = stringPreferencesKey("progress")
    private val prefsKey = stringPreferencesKey("prefs")

    private val foldersSerializer = ListSerializer(VideoFolder.serializer())
    private val progressSerializer =
        MapSerializer(String.serializer(), PlayProgress.serializer())

    val folders: Flow<List<VideoFolder>> = appContext.dataStore.data.map { prefs ->
        prefs[foldersKey]?.let {
            runCatching { json.decodeFromString(foldersSerializer, it) }.getOrNull()
        } ?: emptyList()
    }

    val progress: Flow<Map<String, PlayProgress>> = appContext.dataStore.data.map { prefs ->
        prefs[progressKey]?.let {
            runCatching { json.decodeFromString(progressSerializer, it) }.getOrNull()
        } ?: emptyMap()
    }

    val prefs: Flow<AppPrefs> = appContext.dataStore.data.map { prefs ->
        prefs[prefsKey]?.let {
            runCatching { json.decodeFromString(AppPrefs.serializer(), it) }.getOrNull()
        } ?: AppPrefs()
    }

    suspend fun saveFolders(list: List<VideoFolder>) {
        appContext.dataStore.edit {
            it[foldersKey] = json.encodeToString(foldersSerializer, list)
        }
    }

    suspend fun saveProgress(map: Map<String, PlayProgress>) {
        appContext.dataStore.edit {
            it[progressKey] = json.encodeToString(progressSerializer, map)
        }
    }

    suspend fun savePrefs(prefs: AppPrefs) {
        appContext.dataStore.edit {
            it[prefsKey] = json.encodeToString(AppPrefs.serializer(), prefs)
        }
    }
}
