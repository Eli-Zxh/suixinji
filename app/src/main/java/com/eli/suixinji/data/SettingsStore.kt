package com.eli.suixinji.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "suixinji_settings"
)

/** 应用设置存储（DataStore Preferences）。 */
class SettingsStore(context: Context) {
  private val appContext = context.applicationContext

  /** 用户授权的 workplace 目录树 URI（SAF）。 */
  val workplaceTreeUri: Flow<String?> =
    appContext.settingsDataStore.data.map { it[KEY_WORKPLACE_TREE_URI] }

  suspend fun setWorkplaceTreeUri(uri: String) {
    appContext.settingsDataStore.edit { it[KEY_WORKPLACE_TREE_URI] = uri }
  }

  suspend fun clearWorkplaceTreeUri() {
    appContext.settingsDataStore.edit { it.remove(KEY_WORKPLACE_TREE_URI) }
  }

  // ---- WebDAV 同步 ----

  /** 服务器地址，如 https://dav.example.com/dav/obsidian/ */
  val webdavUrl: Flow<String?> = appContext.settingsDataStore.data.map { it[KEY_WEBDAV_URL] }

  /** 用户名。 */
  val webdavUser: Flow<String?> = appContext.settingsDataStore.data.map { it[KEY_WEBDAV_USER] }

  /** 密码/应用密码（仅本机存储，不上传日志）。 */
  val webdavPassword: Flow<String?> =
    appContext.settingsDataStore.data.map { it[KEY_WEBDAV_PASSWORD] }

  /** 远端库目录名（同步目标，相对 WebDAV 根）。 */
  val webdavRemoteDir: Flow<String?> =
    appContext.settingsDataStore.data.map { it[KEY_WEBDAV_REMOTE_DIR] }

  suspend fun setWebdavConfig(url: String, user: String, password: String, remoteDir: String) {
    appContext.settingsDataStore.edit {
      it[KEY_WEBDAV_URL] = url
      it[KEY_WEBDAV_USER] = user
      it[KEY_WEBDAV_PASSWORD] = password
      it[KEY_WEBDAV_REMOTE_DIR] = remoteDir
    }
  }

  suspend fun clearWebdavConfig() {
    appContext.settingsDataStore.edit {
      it.remove(KEY_WEBDAV_URL)
      it.remove(KEY_WEBDAV_USER)
      it.remove(KEY_WEBDAV_PASSWORD)
      it.remove(KEY_WEBDAV_REMOTE_DIR)
    }
  }

  // ---- 归纳推理后端策略（M2.10）----

  /**
   * 推理后端策略：auto = GPU→NPU→CPU 逐级探活降级（默认，省电优先）；
   * cpu / npu / gpu = 强制指定单后端（失败即报错，不降级）。
   */
  val llmBackend: Flow<String> =
    appContext.settingsDataStore.data.map { it[KEY_LLM_BACKEND] ?: "auto" }

  suspend fun setLlmBackend(backend: String) {
    appContext.settingsDataStore.edit { it[KEY_LLM_BACKEND] = backend }
  }

  // ---- 笔记语言（M2.10）----

  /** 笔记语言：zh = 中文（默认）；en = English（提示词与 ASR 语种同步切换）。 */
  val noteLanguage: Flow<String> =
    appContext.settingsDataStore.data.map { it[KEY_NOTE_LANGUAGE] ?: "zh" }

  suspend fun setNoteLanguage(lang: String) {
    appContext.settingsDataStore.edit { it[KEY_NOTE_LANGUAGE] = lang }
  }

  private companion object {
    val KEY_WORKPLACE_TREE_URI = stringPreferencesKey("workplace_tree_uri")
    val KEY_WEBDAV_URL = stringPreferencesKey("webdav_url")
    val KEY_WEBDAV_USER = stringPreferencesKey("webdav_user")
    val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
    val KEY_WEBDAV_REMOTE_DIR = stringPreferencesKey("webdav_remote_dir")
    val KEY_LLM_BACKEND = stringPreferencesKey("llm_backend")
    val KEY_NOTE_LANGUAGE = stringPreferencesKey("note_language")
  }
}
