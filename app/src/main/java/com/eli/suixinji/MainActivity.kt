package com.eli.suixinji

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    requestNotificationPermissionIfNeeded()
    setContent {
      SuixinJiApp()
    }
  }

  /** Android 13+ 通知需要运行时授权：后台任务的通知栏进度条依赖它（拒绝也只影响可见性）。 */
  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      runCatching {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
      }
    }
  }
}
