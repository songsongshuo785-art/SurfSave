package com.myAllVideoBrowser.util.downloaders.super_x_downloader.control

import java.io.File
import java.io.IOException

class FileBasedDownloadController(private val downloadDir: File) {
    enum class InterruptionReason {
        NONE,
        PAUSE,
        CANCEL,
        STOP_AND_SAVE
    }

    companion object {
        const val PAUSE_FLAG_FILENAME = "pause"
        const val CANCEL_FLAG_FILENAME = "cancel"
        const val STOP_AND_SAVE_FLAG_FILENAME = "stop_and_save"
    }

    private val pauseFlag = File(downloadDir, PAUSE_FLAG_FILENAME)
    private val cancelFlag = File(downloadDir, CANCEL_FLAG_FILENAME)
    private val stopAndSaveFlag = File(downloadDir, STOP_AND_SAVE_FLAG_FILENAME)

    /**
     * Initializes the controller for a new download, ensuring no old flags exist.
     */
    @Throws(IOException::class)
    fun start() {
        ensureDownloadDirectory()
        deleteFlag(pauseFlag)
        deleteFlag(cancelFlag)
        deleteFlag(stopAndSaveFlag)
    }

    /**
     * Signals the worker to pause by creating the pause flag.
     * @throws IOException if the flag file cannot be created.
     */
    @Throws(IOException::class)
    fun requestPause() {
        createFlag(pauseFlag)
    }

    /**
     * Signals the worker to cancel by creating the cancel flag.
     * @throws IOException if the flag file cannot be created.
     */
    @Throws(IOException::class)
    fun requestCancel() {
        createFlag(cancelFlag)
    }

    /**
     * Signals a live stream to stop and merge by creating the flag.
     * @throws IOException if the flag file cannot be created.
     */
    @Throws(IOException::class)
    fun requestStopAndSave() {
        createFlag(stopAndSaveFlag)
    }

    fun isPauseRequested(): Boolean = pauseFlag.exists()

    fun isCancelRequested(): Boolean = cancelFlag.exists()

    fun isStopAndSaveRequested(): Boolean = stopAndSaveFlag.exists()

    fun interruptionReason(): InterruptionReason {
        return when {
            isCancelRequested() -> InterruptionReason.CANCEL
            isPauseRequested() -> InterruptionReason.PAUSE
            isStopAndSaveRequested() -> InterruptionReason.STOP_AND_SAVE
            else -> InterruptionReason.NONE
        }
    }

    fun isPauseOrCancelRequested(): Boolean {
        return when (interruptionReason()) {
            InterruptionReason.PAUSE, InterruptionReason.CANCEL -> true
            InterruptionReason.NONE, InterruptionReason.STOP_AND_SAVE -> false
        }
    }

    /**
     * Checks if any stop-like action has been requested.
     * This is useful for breaking loops.
     */
    fun isInterrupted(): Boolean {
        return interruptionReason() != InterruptionReason.NONE
    }

    @Throws(IOException::class)
    private fun ensureDownloadDirectory() {
        if ((!downloadDir.exists() && !downloadDir.mkdirs()) || !downloadDir.isDirectory) {
            throw IOException("Unable to create download control directory: ${downloadDir.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun createFlag(flag: File) {
        ensureDownloadDirectory()
        if (!flag.exists() && !flag.createNewFile()) {
            throw IOException("Unable to create download control flag: ${flag.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun deleteFlag(flag: File) {
        if (flag.exists() && !flag.delete()) {
            throw IOException("Unable to clear download control flag: ${flag.absolutePath}")
        }
    }
}
