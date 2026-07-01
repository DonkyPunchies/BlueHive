package com.example.bluehive.latestTrailersComponents.trailerViewer

import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

@UnstableApi
class NewPipeExtractorViewer() {

    companion object {
        private const val TAG = "NewPipeExtractorViewer"

        private var downloaderInstance: DownloaderImpl? = null

        fun getDownloader(): DownloaderImpl {
            if (downloaderInstance == null) downloaderInstance = DownloaderImpl()
            return downloaderInstance!!
        }

        @Volatile
        private var newPipeInitialized = false

        private fun ensureNewPipeInitialized() {
            if (newPipeInitialized) return
            synchronized(this) {
                if (newPipeInitialized) return
                try {
                    NewPipe.init(getDownloader())
                    newPipeInitialized = true
                    Log.d(TAG, "NewPipe initialized successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize NewPipe", e)
                }
            }
        }

        suspend fun resolveStreamUrl(youtubeUrl: String): String? {
            ensureNewPipeInitialized()
            return tryNewPipeExtractionUrl(youtubeUrl)
        }

        private suspend fun tryNewPipeExtractionUrl(youtubeUrl: String): String? =
            withContext(Dispatchers.IO) {
                return@withContext try {
                    val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl)

                    // Prefer manifests when available.
                    info.dashMpdUrl?.takeIf { it.isNotBlank() }?.let { return@withContext it }
                    info.hlsUrl?.takeIf { it.isNotBlank() }?.let { return@withContext it }

                    val streams = info.videoStreams ?: return@withContext null

                    val chosen =
                        streams.firstOrNull {
                            it.getResolution().contains("720") && it.content != null
                        }
                            ?: streams.firstOrNull {
                                it.getResolution().contains("480") && it.content != null
                            }
                            ?: streams.firstOrNull {
                                it.getResolution().contains("360") && it.content != null
                            }
                            ?: streams.firstOrNull { it.content != null }

                    chosen?.content
                } catch (e: Exception) {
                    Log.e(TAG, "NewPipe extraction failed: ${e.message}", e)
                    null
                }
            }
    }
}






