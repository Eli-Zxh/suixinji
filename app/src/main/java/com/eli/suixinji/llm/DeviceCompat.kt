package com.eli.suixinji.llm

import android.os.Build

/**
 * 芯片/ROM 兼容判定（v1.1）。
 *
 * 麒麟（HiSilicon）设备在 litertlm 的 GPU/NPU 探活上表现不稳定：auto 链
 * GPU→NPU→CPU 会先让两个不可用后端各分配一次模型内存，麒麟 12GB 平板上
 * 容易出现 native OOM，还没走到 CPU 就被系统杀掉。这里把“是不是麒麟”
 * 收敛成纯函数，便于单测；auto 策略在麒麟上直接走 CPU，显式强制后端不受影响。
 */
object DeviceCompat {
  fun isKirin(
    socManufacturer: String?,
    hardware: String?,
    board: String?,
  ): Boolean {
    val soc = socManufacturer.orEmpty().lowercase()
    val hw = hardware.orEmpty().lowercase()
    val bd = board.orEmpty().lowercase()
    return soc.contains("hisilicon") ||
      soc.contains("kirin") ||
      hw.contains("kirin") ||
      bd.contains("kirin")
  }

  fun isKirin(): Boolean = isKirin(
    Build.SOC_MANUFACTURER,
    Build.HARDWARE,
    Build.BOARD,
  )

  fun describe(): String =
    "soc=${Build.SOC_MANUFACTURER} hw=${Build.HARDWARE} board=${Build.BOARD} " +
      "kirin=${isKirin()}"
}
