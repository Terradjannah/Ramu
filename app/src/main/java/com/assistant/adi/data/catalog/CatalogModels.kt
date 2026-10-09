package com.assistant.adi.data.catalog

data class CatalogSnapshot(
    val version: Long,
    val generations: List<CatalogGeneration>,
    val source: CatalogSource
)

enum class CatalogSource { VERIFIED, LOCAL_FALLBACK }

data class CatalogGeneration(
    val familyId: String,
    val generationId: String,
    val name: String,
    val status: String,
    val models: List<CatalogModel>
)

data class CatalogModel(
    val modelId: String,
    val familyId: String,
    val generationId: String,
    val name: String,
    val description: String,
    val status: String,
    val variants: List<CatalogVariant>
)

data class CatalogVariant(
    val variantId: String,
    val artifactVersion: Long,
    val format: String,
    val minRuntime: String,
    val maxRuntime: String,
    val testedBackends: List<String>,
    val minApi: Int,
    val abis: List<String>,
    val minRamBytes: Long,
    val recommendedRamBytes: Long,
    val minStorageBytes: Long,
    val socIds: List<String>,
    val driverIds: List<String>,
    val repoId: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val gated: Boolean
)

sealed interface CatalogState {
    val snapshot: CatalogSnapshot
    data class Loading(override val snapshot: CatalogSnapshot) : CatalogState
    data class Verified(override val snapshot: CatalogSnapshot) : CatalogState
    data class OfflineCached(override val snapshot: CatalogSnapshot) : CatalogState
    data class Failed(override val snapshot: CatalogSnapshot, val reason: String) : CatalogState
}
