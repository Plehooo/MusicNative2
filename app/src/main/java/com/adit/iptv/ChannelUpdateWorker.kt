package com.adit.iptv

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs periodically in the background (scheduled from MainActivity) and refreshes the
 * local channel cache from the remote URL the user configured in Settings — so editing
 * channels.json on GitHub (e.g. via Termux + git push) shows up automatically, with no
 * need to reinstall or even open the app first.
 */
class ChannelUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = ChannelRepository(applicationContext)
        val url = repository.getRemoteUrl()
        if (url.isBlank()) return@withContext Result.success()

        try {
            val channels = repository.fetchRemote(url)
            if (channels.isNotEmpty()) {
                repository.saveCache(channels)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "channel_update_worker"
    }
}
