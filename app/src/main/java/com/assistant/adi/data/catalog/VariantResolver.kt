package com.assistant.adi.data.catalog

enum class VariantIssue {
    MODEL_UNAVAILABLE, FORMAT, RUNTIME, API, ABI, STORAGE, BACKEND, SOC, DRIVER
}

enum class VariantWarning { RAM_BELOW_MINIMUM, RAM_BELOW_RECOMMENDED }

data class VariantEvaluation(
    val variant: CatalogVariant,
    val backend: String?,
    val issues: List<VariantIssue>,
    val warnings: List<VariantWarning>
) {
    val compatible: Boolean get() = issues.isEmpty() && backend != null
}

data class VariantSelection(
    val selected: VariantEvaluation?,
    val evaluations: List<VariantEvaluation>
)

/** APK policy: only the CPU path has been validated with LiteRT-LM 0.16.1. */
object VariantResolver {
    private const val RUNTIME = "0.16.1"
    private const val FORMAT = "litertlm"
    private val availableBackends = listOf("cpu")

    fun resolve(model: CatalogModel, device: DeviceCapabilities): VariantSelection {
        val evaluations = model.variants.map { evaluate(it, device, model.status == "available") }
        val selected = evaluations.filter { it.compatible }.minWithOrNull(
            compareBy<VariantEvaluation> { it.variant.minRamBytes }
                .thenBy { it.variant.minStorageBytes }
                .thenBy { it.variant.variantId }
        )
        return VariantSelection(selected, evaluations)
    }

    fun evaluate(variant: CatalogVariant, device: DeviceCapabilities, modelAvailable: Boolean = true): VariantEvaluation {
        val issues = mutableListOf<VariantIssue>()
        if (!modelAvailable) issues += VariantIssue.MODEL_UNAVAILABLE
        if (variant.format != FORMAT) issues += VariantIssue.FORMAT
        val min = version(variant.minRuntime)
        val max = version(variant.maxRuntime)
        val runtime = version(RUNTIME)!!
        if (min == null || max == null || compare(runtime, min) < 0 || compare(runtime, max) > 0) issues += VariantIssue.RUNTIME
        if (device.apiLevel < variant.minApi) issues += VariantIssue.API
        if (device.abis.intersect(variant.abis.toSet()).isEmpty()) issues += VariantIssue.ABI
        if (device.availableStorageBytes < variant.minStorageBytes) issues += VariantIssue.STORAGE
        val backend = availableBackends.firstOrNull { it in variant.testedBackends }
        if (backend == null) issues += VariantIssue.BACKEND
        if (variant.socIds.isNotEmpty() && (device.socId == null || device.socId !in variant.socIds)) issues += VariantIssue.SOC
        if (variant.driverIds.isNotEmpty() && (device.driverId == null || device.driverId !in variant.driverIds)) issues += VariantIssue.DRIVER

        val warnings = when {
            device.totalRamBytes < variant.minRamBytes -> listOf(VariantWarning.RAM_BELOW_MINIMUM)
            device.totalRamBytes < variant.recommendedRamBytes -> listOf(VariantWarning.RAM_BELOW_RECOMMENDED)
            else -> emptyList()
        }
        return VariantEvaluation(variant, backend, issues, warnings)
    }

    /** Call immediately before engine initialization with a fresh ActivityManager reading. */
    fun hasRamForInitialization(variant: CatalogVariant, availableRamBytes: Long): Boolean =
        availableRamBytes >= variant.minRamBytes

    private fun version(value: String): List<Int>? {
        if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(value)) return null
        return value.split('.').map { it.toIntOrNull() ?: return null }
    }

    private fun compare(left: List<Int>, right: List<Int>): Int {
        for (i in left.indices) {
            val result = left[i].compareTo(right[i])
            if (result != 0) return result
        }
        return 0
    }
}
