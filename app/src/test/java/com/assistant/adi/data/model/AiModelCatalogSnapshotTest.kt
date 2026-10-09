package com.assistant.adi.data.model

import com.assistant.adi.data.catalog.CatalogGeneration
import com.assistant.adi.data.catalog.CatalogModel
import com.assistant.adi.data.catalog.CatalogSnapshot
import com.assistant.adi.data.catalog.CatalogSource
import com.assistant.adi.data.catalog.CatalogVariant
import com.assistant.adi.data.catalog.DeviceCapabilities
import com.assistant.adi.util.ModelFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AiModelCatalogSnapshotTest {
    private val device = DeviceCapabilities(36, setOf("arm64-v8a"), 8_000, 10_000, null, null)
    private val variant = CatalogVariant("e2b-cpu", 1, "litertlm", "0.16.1", "0.16.9", listOf("cpu"),
        29, listOf("arm64-v8a"), 1_000, 6_000, 100, emptyList(), emptyList(),
        "owner/repo", "a".repeat(40), "model.litertlm", 100, "b".repeat(64), "gemma", false)

    private fun snapshot(variants: List<CatalogVariant>, source: CatalogSource = CatalogSource.VERIFIED) =
        CatalogSnapshot(1, listOf(CatalogGeneration("gemma", "gemma-3", "Gemma 3n", "available", listOf(
            CatalogModel("gemma-3n-e2b", "gemma", "gemma-3", "Gemma E2B", "Description", "available", variants)
        ))), source)

    @Test fun verifiedVariantUsesCatalogIdentityAndPinnedFile() {
        val directory = Files.createTempDirectory("catalog-models").toFile()
        try {
            val item = AiModelCatalog.fromSnapshot(snapshot(listOf(variant)), device, directory).single()
            assertEquals(ModelFiles.forVariant(directory, variant).name, item.fileName)
            assertEquals("e2b-cpu", item.variant?.variantId)
            assertEquals("CPU", item.backend)
            assertNull(item.incompatibility)
            assertEquals(item, AiModelCatalog.forFile(listOf(item), item.fileName))
        } finally { directory.deleteRecursively() }
    }

    @Test fun incompatibleModelRemainsVisibleWithoutDownloadVariant() {
        val directory = Files.createTempDirectory("catalog-models").toFile()
        try {
            val item = AiModelCatalog.fromSnapshot(snapshot(listOf(variant.copy(minApi = 99))), device, directory).single()
            assertNull(item.variant)
            assertNotNull(item.incompatibility)
        } finally { directory.deleteRecursively() }
    }

    @Test fun localLegacyFileSurvivesCatalogRefreshAndOfflineFallback() {
        val directory = Files.createTempDirectory("catalog-models").toFile()
        try {
            val file = File(directory, AiModelCatalog.MODEL_E2B.fileName)
            file.writeBytes(byteArrayOf(1, 2, 3))
            ModelFiles.markReady(file)
            val current = AiModelCatalog.fromSnapshot(snapshot(listOf(variant)), device, directory)
            assertNotNull(AiModelCatalog.forFile(current, file.name))
            val offline = AiModelCatalog.fromSnapshot(snapshot(emptyList(), CatalogSource.LOCAL_FALLBACK), device, directory)
            assertNotNull(AiModelCatalog.forFile(offline, file.name))
            assertEquals(file.name, AiModelCatalog.forFile(current, file.name)?.fileName)
        } finally { directory.deleteRecursively() }
    }
}
