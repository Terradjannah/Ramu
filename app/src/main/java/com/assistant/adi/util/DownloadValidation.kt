package com.assistant.adi.util

import com.assistant.adi.data.catalog.CatalogVariant

object DownloadValidation {
    private val repo = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*")
    private val pathSegment = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
    private val commit = Regex("[0-9a-f]{40}")
    private val hash = Regex("[0-9a-f]{64}")
    private val id = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    fun validate(variant: CatalogVariant) {
        require(id.matches(variant.variantId) && variant.artifactVersion > 0)
        require(repo.matches(variant.repoId) && commit.matches(variant.revision))
        require(variant.path.endsWith(".litertlm") &&
            variant.path.split('/').all { pathSegment.matches(it) })
        require(variant.sizeBytes > 0 && hash.matches(variant.sha256))
    }

    fun partialIdentity(variant: CatalogVariant): String =
        "${variant.variantId}|${variant.revision}|${variant.sha256}|${variant.sizeBytes}"

    data class Range(val start: Long, val end: Long, val total: Long)
    fun range(header: String?): Range? {
        val m = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header.orEmpty()) ?: return null
        val (start, end, total) = m.destructured
        val r = Range(start.toLongOrNull() ?: return null, end.toLongOrNull() ?: return null, total.toLongOrNull() ?: return null)
        return r.takeIf { it.start <= it.end && it.end < it.total }
    }
    fun complete(actual: Long, expected: Long) = expected > 0 && actual == expected
}
