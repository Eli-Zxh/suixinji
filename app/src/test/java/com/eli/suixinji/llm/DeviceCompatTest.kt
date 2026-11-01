package com.eli.suixinji.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCompatTest {
  @Test
  fun `detects kirin from soc manufacturer`() {
    assertTrue(DeviceCompat.isKirin("HiSilicon", "KirinT90", "HWBKYN"))
  }

  @Test
  fun `detects kirin from hardware or board`() {
    assertTrue(DeviceCompat.isKirin(null, "kirin9000", "unknown"))
    assertTrue(DeviceCompat.isKirin("", "", "kirin990"))
  }

  @Test
  fun `does not flag qualcomm or mediatek`() {
    assertFalse(DeviceCompat.isKirin("Qualcomm", "qcom", "sm8750"))
    assertFalse(DeviceCompat.isKirin("MediaTek", "mt6989", "rocinante"))
    assertFalse(DeviceCompat.isKirin(null, null, null))
  }
}
