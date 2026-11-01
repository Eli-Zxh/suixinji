package com.eli.suixinji

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.eli.suixinji.audio.CrashRecovery
import com.eli.suixinji.data.SettingsStore
import com.eli.suixinji.ui.screens.ChatScreen
import com.eli.suixinji.ui.screens.LibraryScreen
import com.eli.suixinji.ui.screens.LlmController
import com.eli.suixinji.ui.screens.NoteScreen
import com.eli.suixinji.ui.screens.SettingsScreen
import com.eli.suixinji.ui.screens.SrtScreen
import com.eli.suixinji.ui.screens.TranscribeController
import com.eli.suixinji.ui.screens.TranscribeScreen
import com.eli.suixinji.ui.theme.SuixinJiTheme
import com.eli.suixinji.workspace.WorkspaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private sealed interface Screen {
  data object Library : Screen
  data object Transcribe : Screen
  data object Chat : Screen
  data object Settings : Screen
  data class Note(val uri: Uri) : Screen
  data class Srt(val stem: String) : Screen
}

/** 应用壳：左侧 NavigationRail（笔记 / 转写 / 设置）+ 右侧内容区。 */
@Composable
fun SuixinJiApp() {
  SuixinJiTheme {
    val appContext = LocalContext.current.applicationContext
    val settingsStore = remember { SettingsStore(appContext) }
    val ws = remember { WorkspaceStore(appContext, settingsStore) }
    val transcribeController = remember {
      TranscribeController(
        context = appContext,
        scope = CoroutineScope(Dispatchers.Main.immediate),
        settings = settingsStore,
        ws = ws,
      )
    }
    val llmController = remember {
      LlmController(
        context = appContext,
        scope = CoroutineScope(Dispatchers.Main.immediate),
        ws = ws,
        settings = settingsStore,
      )
    }

    // 启动时：迁移旧目录结构 → 崩溃恢复录音（转写页显示恢复横幅）
    var recoveredMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
      runCatching { settingsStore.migrateCloudApiKey() }
      runCatching { ws.migrateLayout() }
        .onFailure { android.util.Log.w("SuixinJi", "目录迁移异常", it) }
      val r = CrashRecovery.recover(ws.audioDir(), appContext.contentResolver)
      if (r.recovered > 0) {
        recoveredMessage =
          "检测到上次异常退出，已恢复 ${r.recovered} 段录音：" + r.names.joinToString("、")
      }
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var tab by remember { mutableStateOf<Tab>(Tab.Library) }

    BackHandler(enabled = screen is Screen.Note || screen is Screen.Srt) {
      screen = Screen.Library
    }

    Scaffold { padding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
      ) {
        NavigationRail {
          NavigationRailItem(
            selected = tab == Tab.Library,
            onClick = { tab = Tab.Library; screen = Screen.Library },
            icon = { Icon(Icons.Filled.Description, contentDescription = "笔记") },
            label = { Text("笔记") },
          )
          NavigationRailItem(
            selected = tab == Tab.Transcribe,
            onClick = { tab = Tab.Transcribe; screen = Screen.Transcribe },
            icon = { Icon(Icons.Filled.Mic, contentDescription = "转写") },
            label = { Text("转写") },
          )
          NavigationRailItem(
            selected = tab == Tab.Chat,
            onClick = { tab = Tab.Chat; screen = Screen.Chat },
            icon = { Icon(Icons.Filled.Face, contentDescription = "对话") },
            label = { Text("对话") },
          )
          NavigationRailItem(
            selected = tab == Tab.Settings,
            onClick = { tab = Tab.Settings; screen = Screen.Settings },
            icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
            label = { Text("设置") },
          )
        }

        Box(modifier = Modifier.fillMaxSize()) {
          when (val s = screen) {
            is Screen.Note -> NoteScreen(
              noteUri = s.uri,
              ws = ws,
              onBack = { screen = Screen.Library },
              onOpenSrt = { stem -> screen = Screen.Srt(stem) },
            )
            is Screen.Srt -> SrtScreen(
              stem = s.stem,
              ws = ws,
              onBack = { screen = Screen.Library },
            )
            Screen.Chat -> ChatScreen(settings = settingsStore)
            Screen.Settings -> SettingsScreen(
              store = settingsStore,
              ws = ws,
            )
            Screen.Library -> LibraryScreen(
              ws = ws,
              llm = llmController,
              onOpenNote = { uri -> screen = Screen.Note(uri) },
              onOpenSrt = { stem -> screen = Screen.Srt(stem) },
              onGoTranscribe = { tab = Tab.Transcribe; screen = Screen.Transcribe },
            )
            Screen.Transcribe -> TranscribeScreen(
              controller = transcribeController,
              llm = llmController,
              ws = ws,
              recoveredMessage = recoveredMessage,
              onOpenSettings = { tab = Tab.Settings; screen = Screen.Settings },
              onOpenNote = { uri -> screen = Screen.Note(uri) },
            )
          }
        }
      }
    }
  }
}

private enum class Tab { Library, Transcribe, Chat, Settings }
