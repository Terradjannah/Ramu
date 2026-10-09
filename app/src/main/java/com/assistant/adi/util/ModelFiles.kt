package com.assistant.adi.util

import com.assistant.adi.data.catalog.CatalogVariant
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

object ModelFiles {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun lock(file: File) = locks.getOrPut(file.absolutePath) { Mutex() }
    fun partial(file: File) = File(file.path + ".part")
    fun partialIdentity(file: File) = File(file.path + ".part.identity")
    private fun marker(file: File) = File(file.path + ".complete")

    fun forVariant(directory: File, variant: CatalogVariant): File {
        DownloadValidation.validate(variant)
        return File(directory, "${variant.variantId}-${variant.sha256.take(16)}.litertlm")
    }

    private fun identity(variant: CatalogVariant) =
        "v1|${variant.variantId}|${variant.artifactVersion}|${variant.sha256}|${variant.sizeBytes}"

    fun isReady(file: File): Boolean = file.isFile && file.name.endsWith(".litertlm") &&
        file.length() > 0 && runCatching {
            val value = marker(file).readText().trim()
            value.toLongOrNull() == file.length() ||
                (value.startsWith("v1|") && value.substringAfterLast('|').toLongOrNull() == file.length())
        }.getOrDefault(false)

    fun isReady(file: File, variant: CatalogVariant): Boolean = file.isFile &&
        file.length() == variant.sizeBytes && runCatching { marker(file).readText().trim() == identity(variant) }.getOrDefault(false)

    /** A size-only legacy marker is never accepted for a catalog artifact without hashing the local file. */
    fun verifyLocal(file: File, variant: CatalogVariant): Boolean {
        if (!file.isFile || file.length() != variant.sizeBytes) return false
        if (isReady(file, variant)) return true
        if (sha256(file) != variant.sha256) return false
        markReady(file, variant)
        return true
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Invalidate readiness before removing data. Never recursively delete paths. Caller holds lock. */
    fun delete(file: File) {
        require(file.name.endsWith(".litertlm")) { "Berkas model tidak valid" }
        listOf(marker(file), file, partial(file), partialIdentity(file), File(file.path + ".etag")).forEach {
            if (it.exists() && !it.delete()) throw IOException("Tidak dapat menghapus ${it.name}. Periksa akses penyimpanan, lalu coba lagi.")
        }
    }

    fun markReady(file: File) { marker(file).writeText(file.length().toString()) }

    fun markReady(file: File, variant: CatalogVariant) {
        require(file.isFile && file.length() == variant.sizeBytes && sha256(file) == variant.sha256) { "Hash model tidak cocok" }
        val temporary = File(file.path + ".complete.tmp")
        temporary.writeText(identity(variant))
        if (marker(file).exists() && !marker(file).delete()) {
            temporary.delete()
            throw IOException("Tidak dapat mengganti penanda model lama")
        }
        if (!temporary.renameTo(marker(file))) {
            temporary.delete()
            throw IOException("Tidak dapat menyimpan penanda model terverifikasi")
        }
    }
}
