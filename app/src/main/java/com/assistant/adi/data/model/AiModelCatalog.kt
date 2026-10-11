package com.assistant.adi.data.model

import com.assistant.adi.data.catalog.CatalogSnapshot
import com.assistant.adi.data.catalog.CatalogSource
import com.assistant.adi.data.catalog.CatalogVariant
import com.assistant.adi.data.catalog.DeviceCapabilities
import com.assistant.adi.data.catalog.VariantResolver
import com.assistant.adi.util.ModelFiles
import java.io.File

data class AiModelItem(
    val id: String,
    val name: String,
    val shortName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val sizeLabel: String,
    val ramUsageLabel: String,
    val badge: String,
    val description: String,
    val isNpuSupported: Boolean,
    val variant: CatalogVariant? = null,
    val familyName: String = "",
    val generationName: String = "",
    val incompatibility: String? = null,
    val backend: String = "CPU",
    val catalogStatus: String = "Lokal",
    val requiredStorageBytes: Long = 0L,
    val displayVariant: CatalogVariant? = null
)

object AiModelCatalog {
    val MODEL_E2B = AiModelItem(
        id = "gemma-3n-e2b",
        name = "Gemma 3n E2B (IT - INT4)",
        shortName = "Gemma 3n E2B",
        fileName = "gemma-3n-E2B-it-int4.litertlm",
        downloadUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
        expectedSizeBytes = 3655827456L,
        sizeLabel = "3.40 GiB",
        ramUsageLabel = "Perangkat min. 8 GB RAM",
        badge = "CPU • Lokal",
        description = "Model lokal ringkas. Backend CPU; kecepatan dan kebutuhan RAM bergantung perangkat.",
        isNpuSupported = false
    )

    val MODEL_E4B = AiModelItem(
        id = "gemma-3n-e4b",
        name = "Gemma 3n E4B (IT - INT4)",
        shortName = "Gemma 3n E4B",
        fileName = "gemma-3n-E4B-it-int4.litertlm",
        downloadUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
        expectedSizeBytes = 4919541760L,
        sizeLabel = "4.58 GiB",
        ramUsageLabel = "Perangkat min. 12 GB RAM",
        badge = "CPU • Lokal",
        description = "Model lokal lebih besar. Backend CPU; kecepatan dan kebutuhan RAM bergantung perangkat.",
        isNpuSupported = false
    )

    val AVAILABLE_MODELS: List<AiModelItem> = listOf(MODEL_E2B, MODEL_E4B)

    fun getModelById(id: String): AiModelItem? {
        return AVAILABLE_MODELS.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun getModelByFileName(fileName: String): AiModelItem? {
        return AVAILABLE_MODELS.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
    }

    fun getDefaultModel(): AiModelItem = MODEL_E2B

    fun fromSnapshot(snapshot: CatalogSnapshot, device: DeviceCapabilities, directory: File): List<AiModelItem> {
        val items = snapshot.generations.flatMap { generation ->
            generation.models.map { model ->
                val installedBytes = model.variants.filter { variant ->
                    runCatching { ModelFiles.isReady(ModelFiles.forVariant(directory, variant), variant) }.getOrDefault(false)
                }.maxOfOrNull { it.sizeBytes } ?: 0L
                val selection = VariantResolver.resolve(model,
                    device.copy(availableStorageBytes = device.availableStorageBytes + installedBytes))
                val selected = selection.selected
                val variant = selected?.variant
                val displayVariant = variant ?: model.variants.firstOrNull()
                val legacy = getModelById(model.modelId)
                val legacyFile = legacy?.let { File(directory, it.fileName) }
                val useLegacy = legacyFile?.let(ModelFiles::isReady) == true
                val backendLabel = when {
                    useLegacy -> "CPU"
                    selected?.backend != null -> selected.backend.uppercase()
                    else -> "Belum diverifikasi"
                }
                val ramLabel = if (legacy != null && (useLegacy || displayVariant == null)) legacy.ramUsageLabel else
                    displayVariant?.recommendedRamBytes?.let { "%.1f GB RAM perangkat".format(it / 1_000_000_000.0) }
                        ?: "Persyaratan RAM belum diverifikasi"
                val fileName = if (useLegacy) legacy!!.fileName else
                    variant?.let { ModelFiles.forVariant(directory, it).name } ?: legacy?.fileName.orEmpty()
                val issue = when {
                    useLegacy -> null
                    snapshot.source != CatalogSource.VERIFIED -> "Katalog offline awal; varian unduhan belum tersedia"
                    generation.status != "available" || model.status != "available" -> "Belum tersedia di katalog"
                    selected == null -> selection.evaluations.flatMap { it.issues }.distinct().joinToString { it.name.lowercase().replace('_', ' ') }
                        .ifBlank { "Belum ada varian terverifikasi" }
                    else -> null
                }
                AiModelItem(model.modelId, model.name, model.name, fileName,
                    displayVariant?.let { "https://huggingface.co/${it.repoId}/resolve/${it.revision}/${it.path}" }.orEmpty(),
                    displayVariant?.sizeBytes ?: 0L,
                    displayVariant?.sizeBytes?.let { "%.2f GB".format(it / 1073741824.0) } ?: "—",
                    ramLabel,
                    "$backendLabel • Lokal", model.description, false,
                    variant.takeUnless { useLegacy }, generation.familyId, generation.name, issue,
                    backendLabel,
                    when {
                        snapshot.source != CatalogSource.VERIFIED -> "Katalog offline"
                        model.status == "available" -> "Katalog terverifikasi"
                        else -> "Kandidat katalog"
                    },
                    model.variants.minOfOrNull { it.minStorageBytes } ?: 0L, displayVariant)
            }
        }
        val knownFiles = items.flatMap { item -> listOfNotNull(item.fileName.takeIf { it.isNotBlank() }, getModelById(item.id)?.fileName) }.toSet()
        val local = directory.listFiles()?.filter { ModelFiles.isReady(it) && it.name !in knownFiles }.orEmpty()
        val missingLegacy = AVAILABLE_MODELS.filter { legacy ->
            val file = File(directory, legacy.fileName)
            ModelFiles.isReady(file) && items.none { it.id == legacy.id && it.fileName == legacy.fileName }
        }.map { it.copy(id = "local-${it.id}", variant = null, incompatibility = null) }
        return items + missingLegacy + local.map { file ->
            AiModelItem("local-${file.name}", file.name, file.name, file.name, "", file.length(),
                "%.2f GB".format(file.length() / 1073741824.0), "RAM bergantung model", "CPU • Lokal",
                "Model yang tersimpan di perangkat", false)
        }
    }

    fun forFile(items: List<AiModelItem>, fileName: String): AiModelItem? =
        items.firstOrNull { it.fileName.equals(File(fileName).name, true) }
}
