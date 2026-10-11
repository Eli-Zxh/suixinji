package com.eli.suixinji.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

enum class Tone { Neutral, Good, Warn, Bad }

/** 统一的卡片容器，带可选标题与副标题。 */
@Composable
fun SectionCard(
  modifier: Modifier = Modifier,
  title: String? = null,
  subtitle: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  ElevatedCard(modifier = modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (title != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          if (subtitle != null) {
            Text(
              text = subtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      content()
    }
  }
}

/** 标签-值行。 */
@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color? = null) {
  Row(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(92.dp),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color = valueColor ?: MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
  }
}

/** 状态小药丸。 */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
  val container: Color
  val content: Color
  when (tone) {
    Tone.Good -> {
      container = MaterialTheme.colorScheme.primaryContainer
      content = MaterialTheme.colorScheme.onPrimaryContainer
    }
    Tone.Warn -> {
      container = MaterialTheme.colorScheme.tertiaryContainer
      content = MaterialTheme.colorScheme.onTertiaryContainer
    }
    Tone.Bad -> {
      container = MaterialTheme.colorScheme.errorContainer
      content = MaterialTheme.colorScheme.onErrorContainer
    }
    Tone.Neutral -> {
      container = MaterialTheme.colorScheme.surfaceVariant
      content = MaterialTheme.colorScheme.onSurfaceVariant
    }
  }
  Surface(color = container, shape = MaterialTheme.shapes.small, modifier = modifier) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = content,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

/** 等宽小字块（事件日志 / 报告）。 */
@Composable
fun MonospaceBlock(text: String, modifier: Modifier = Modifier) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      modifier = Modifier.padding(12.dp),
    )
  }
}

/** 把 SAF 树 URI 转成可读路径（如 primary:Documents/workplace → Documents/workplace）。 */
fun prettyWorkplacePath(raw: String?): String {
  if (raw.isNullOrBlank()) return "未选择"
  return runCatching {
    val segment = Uri.parse(raw).lastPathSegment ?: raw
    segment.substringAfter(':')
  }.getOrDefault(raw)
}
