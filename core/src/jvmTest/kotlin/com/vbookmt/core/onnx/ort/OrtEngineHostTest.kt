package com.vbookmt.core.onnx.ort

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrtEngineHostTest {

    @Test
    fun testMatchesModelDirWhenNotReady() {
        val host = OrtEngineHost()
        assertFalse(host.isReady())
        assertFalse(host.matchesModelDir(File("non_existent_dir")))
        assertFalse(host.matchesModelDir(null))
    }

    @Test
    fun testIsSamePathWithIdenticalStrings() {
        val path = "/models/hachimi-60-qt-onnx"
        assertTrue(OrtEngineHost.isSamePath(path, path))
    }

    @Test
    fun testIsSamePathWithNulls() {
        assertFalse(OrtEngineHost.isSamePath(null, "/models/foo"))
        assertFalse(OrtEngineHost.isSamePath("/models/foo", null))
        assertFalse(OrtEngineHost.isSamePath(null, null))
    }

    @Test
    fun testIsSamePathWithRelativeAndCanonicalEquivalence() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "vbookmt_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val subDir = File(tmpDir, "model_a").apply { mkdirs() }
            val directPath = subDir.absolutePath
            val relativePath = File(tmpDir, "./model_a").absolutePath

            assertTrue(
                OrtEngineHost.isSamePath(directPath, relativePath),
                "Direct path and relative path with ./ should resolve to the same canonical path"
            )

            val otherDir = File(tmpDir, "model_b").apply { mkdirs() }
            assertFalse(
                OrtEngineHost.isSamePath(directPath, otherDir.absolutePath),
                "Different model directories must not match"
            )
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun testIsSamePathWindowsCasing() {
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
        if (isWindows) {
            val pathLower = "c:\\users\\test\\model"
            val pathUpper = "C:\\Users\\Test\\Model"
            assertTrue(
                OrtEngineHost.isSamePath(pathLower, pathUpper),
                "On Windows, path comparison must be case-insensitive"
            )
        }
    }
}
