package com.assistant.adi.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantResolverTest {
    private val device = DeviceCapabilities(36, setOf("arm64-v8a"), 8_000, 10_000, null, null)

    private fun variant(
        id: String = "cpu",
        backends: List<String> = listOf("cpu"),
        minRuntime: String = "0.16.1",
        maxRuntime: String = "0.16.9",
        storage: Long = 100,
        socIds: List<String> = emptyList(),
        format: String = "litertlm",
        abis: List<String> = listOf("arm64-v8a")
    ) = CatalogVariant(id, 1, format, minRuntime, maxRuntime, backends, 29, abis,
        1_000, 6_000, storage, socIds, emptyList(), "owner/repo", "a".repeat(40),
        "$id.litertlm", 100, "b".repeat(64), "test", false)

    private fun model(vararg variants: CatalogVariant) = CatalogModel(
        "model", "family", "generation", "Model", "Description", "available", variants.toList()
    )

    @Test fun cpuOnlySelectsCpu() {
        val result = VariantResolver.resolve(model(variant()), device)
        assertEquals("cpu", result.selected?.variant?.variantId)
        assertEquals("cpu", result.selected?.backend)
    }

    @Test fun oneArtifactWithCpuAndGpuStaysOneCpuSelection() {
        val result = VariantResolver.resolve(model(variant(backends = listOf("gpu", "cpu"))), device)
        assertEquals(1, result.evaluations.size)
        assertEquals("cpu", result.selected?.backend)
    }

    @Test fun unsupportedAcceleratorsHaveConsistentBackendIssue() {
        for (backend in listOf("gpu", "npu")) {
            val result = VariantResolver.resolve(model(variant(backends = listOf(backend))), device)
            assertNull(result.selected)
            assertEquals(listOf(VariantIssue.BACKEND), result.evaluations.single().issues)
        }
    }

    @Test fun unknownSocAndUnsupportedNpuBothReported() {
        val result = VariantResolver.resolve(model(variant(backends = listOf("npu"), socIds = listOf("known-soc"))), device)
        assertEquals(listOf(VariantIssue.BACKEND, VariantIssue.SOC), result.evaluations.single().issues)
    }

    @Test fun newerRuntimeAndInsufficientStorageReject() {
        val result = VariantResolver.resolve(model(
            variant(id = "future", minRuntime = "0.17.0", maxRuntime = "0.17.9"),
            variant(id = "large", storage = 20_000)
        ), device)
        assertNull(result.selected)
        assertEquals(listOf(VariantIssue.RUNTIME), result.evaluations[0].issues)
        assertEquals(listOf(VariantIssue.STORAGE), result.evaluations[1].issues)
    }

    @Test fun noVariantMatchesAndHardRequirementsFailClosed() {
        assertNull(VariantResolver.resolve(model(), device).selected)
        val result = VariantResolver.evaluate(variant(format = "unknown", abis = listOf("x86")), device)
        assertEquals(listOf(VariantIssue.FORMAT, VariantIssue.ABI), result.issues)
    }

    @Test fun selectionIsDeterministicAndRamIsWarning() {
        val constrained = device.copy(totalRamBytes = 500)
        val result = VariantResolver.resolve(model(variant("z"), variant("a")), constrained)
        assertEquals("a", result.selected?.variant?.variantId)
        assertEquals(listOf(VariantWarning.RAM_BELOW_MINIMUM), result.selected?.warnings)
        assertTrue(VariantResolver.hasRamForInitialization(result.selected!!.variant, 1_000))
        assertTrue(!VariantResolver.hasRamForInitialization(result.selected!!.variant, 999))
    }
}
