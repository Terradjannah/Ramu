package com.assistant.adi.data.model

sealed class DownloadState {
    object Idle : DownloadState()
    object Completed : DownloadState()
    data class Downloading(
        val progress: Float,
        val speedMBps: Float,
        val etaSeconds: Long,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState()
    data class Paused(
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
