package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCatalogTest {
    private fun manifest(
        versionCode: Long = 100L,
        schemaVersion: Int = 1,
        artifacts: String = """
            [
              {
                "name": "DiPlay-0.3.0.apk",
                "arch": "universal",
                "url": "https://dl.example.com/DiPlay-0.3.0.apk",
                "checksumsUrl": "https://dl.example.com/SHA256SUMS.txt"
              }
            ]
        """.trimIndent(),
    ) = """
        {
          "schemaVersion": $schemaVersion,
          "version": "0.3.0",
          "versionCode": $versionCode,
          "mandatory": false,
          "artifacts": $artifacts
        }
    """.trimIndent()

    @Test
    fun parsesTheLatestManifest() {
        val release = UpdateCatalog.parse(manifest())

        assertEquals("0.3.0", release?.tagName)
        assertEquals(100L, release?.versionCode)
        assertEquals("DiPlay-0.3.0.apk", release?.apkName)
        assertEquals("https://dl.example.com/DiPlay-0.3.0.apk", release?.apkUrl)
        assertEquals("https://dl.example.com/SHA256SUMS.txt", release?.checksumsUrl)
    }

    @Test
    fun prefersTheUniversalApkWhenSeveralArtifactsExist() {
        val artifacts = """
            [
              {
                "name": "DiPlay-arm64.apk",
                "arch": "arm64-v8a",
                "url": "https://dl.example.com/DiPlay-arm64.apk",
                "checksumsUrl": "https://dl.example.com/SHA256SUMS.txt"
              },
              {
                "name": "DiPlay-universal.apk",
                "arch": "universal",
                "url": "https://dl.example.com/DiPlay-universal.apk",
                "checksumsUrl": "https://dl.example.com/SHA256SUMS.txt"
              }
            ]
        """.trimIndent()

        val release = UpdateCatalog.parse(manifest(artifacts = artifacts))

        assertEquals("DiPlay-universal.apk", release?.apkName)
        assertEquals("https://dl.example.com/DiPlay-universal.apk", release?.apkUrl)
    }

    @Test
    fun requiresAValidVersionCode() {
        assertNull(UpdateCatalog.parse(manifest(versionCode = 0L)))
    }

    @Test
    fun returnsNullWithoutAnApkArtifact() {
        val artifacts = """
            [
              {
                "name": "notes.txt",
                "arch": "universal",
                "url": "https://dl.example.com/notes.txt",
                "checksumsUrl": "https://dl.example.com/SHA256SUMS.txt"
              }
            ]
        """.trimIndent()

        assertNull(UpdateCatalog.parse(manifest(artifacts = artifacts)))
    }

    @Test
    fun returnsNullWithoutChecksums() {
        val artifacts = """
            [
              {
                "name": "DiPlay-0.3.0.apk",
                "arch": "universal",
                "url": "https://dl.example.com/DiPlay-0.3.0.apk"
              }
            ]
        """.trimIndent()

        assertNull(UpdateCatalog.parse(manifest(artifacts = artifacts)))
    }

    @Test
    fun rejectsAnUnknownSchemaAndInsecureUrls() {
        assertNull(UpdateCatalog.parse(manifest(schemaVersion = 2)))
        val insecure = """
            [
              {
                "name": "DiPlay-0.3.0.apk",
                "arch": "universal",
                "url": "http://dl.example.com/DiPlay-0.3.0.apk",
                "checksumsUrl": "http://dl.example.com/SHA256SUMS.txt"
              }
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(manifest(artifacts = insecure)))
    }
}
