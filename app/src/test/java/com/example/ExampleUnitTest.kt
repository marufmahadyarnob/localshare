package com.example

import com.example.utils.FileCategory
import com.example.utils.FormatUtils
import com.example.utils.MimeUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun formatBytes_formatsCorrectly() {
    assertEquals("0 B", FormatUtils.formatBytes(0))
    assertEquals("500 B", FormatUtils.formatBytes(500))
    assertEquals("1.0 KB", FormatUtils.formatBytes(1024))
    assertEquals("1.5 MB", FormatUtils.formatBytes(1572864))
  }

  @Test
  fun mimeUtils_categorizesCorrectly() {
    assertEquals(FileCategory.IMAGE, MimeUtils.getCategory("image/png"))
    assertEquals(FileCategory.VIDEO, MimeUtils.getCategory("video/mp4"))
    assertEquals(FileCategory.AUDIO, MimeUtils.getCategory("audio/mpeg"))
    assertEquals(FileCategory.DOCUMENT, MimeUtils.getCategory("application/pdf"))
  }
}
