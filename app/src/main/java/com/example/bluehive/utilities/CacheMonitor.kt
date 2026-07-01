package com.example.bluehive.utilities

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import kotlin.io.walkTopDown
import kotlin.math.ln
import kotlin.math.pow

/**
 * Comprehensive cache and memory monitoring system
 *
 * Features:
 * - Memory cache tracking (Coil)
 * - Disk cache tracking (Coil)
 * - App memory usage
 * - Device storage info
 * - Per-screen tracking
 * - Beautiful formatted logging
 */
object CacheMonitor {
    private const val TAG = "CacheMonitor"

    // Track images per screen
    private val screenImageCounts = mutableMapOf<String, Int>()

    /**
     * Log comprehensive cache statistics for a screen
     *
     * @param context Android context
     * @param screenName Name of the screen (e.g., "MoviesDetailsScreen")
     * @param imageCount Number of images loaded on this screen
     */
    fun logCacheStats(
        context: Context,
        screenName: String,
        imageCount: Int = 0
    ) {
        // Update screen tracking
        if (imageCount > 0) {
            screenImageCounts[screenName] = imageCount
        }

        val stats = gatherStats(context)

        Log.d(TAG, "")
        Log.d(TAG, "╔═══════════════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║              📊 CACHE MONITOR - $screenName")
        Log.d(TAG, "╚═══════════════════════════════════════════════════════════════════════╝")
        Log.d(TAG, "")

        // Screen-specific info
        Log.d(TAG, "🖼️  SCREEN INFO:")
        Log.d(TAG, "   Screen: $screenName")
        Log.d(TAG, "   Images on screen: ${screenImageCounts[screenName] ?: 0}")
        Log.d(TAG, "")

        // Memory Cache (Coil)
        Log.d(TAG, "💾 MEMORY CACHE (Coil - RAM):")
        Log.d(TAG, "   Current size: ${stats.memoryCacheSize}")
        Log.d(TAG, "   Max size: ${stats.memoryCacheMaxSize}")
        Log.d(TAG, "   Usage: ${stats.memoryCachePercent}%")
        Log.d(TAG, "   Cached images: ${stats.memoryCacheCount}")
        Log.d(TAG, "")

        // Disk Cache (Coil)
        Log.d(TAG, "💿 DISK CACHE (Coil - Storage):")
        Log.d(TAG, "   Current size: ${stats.diskCacheSize}")
        Log.d(TAG, "   Max size: ${stats.diskCacheMaxSize}")
        Log.d(TAG, "   Usage: ${stats.diskCachePercent}%")
        Log.d(TAG, "   Cache directory: ${stats.diskCacheDir}")
        Log.d(TAG, "")

        // App Memory Usage
        Log.d(TAG, "🧠 APP MEMORY (RAM):")
        Log.d(TAG, "   Used: ${stats.appMemoryUsed}")
        Log.d(TAG, "   Max available: ${stats.appMemoryMax}")
        Log.d(TAG, "   Usage: ${stats.appMemoryPercent}%")
        Log.d(TAG, "   Native heap: ${stats.nativeHeapSize}")
        Log.d(TAG, "")

        // Device Storage
        Log.d(TAG, "📱 DEVICE STORAGE:")
        Log.d(TAG, "   App internal storage used: ${stats.appStorageUsed}")
        Log.d(TAG, "   Device free space: ${stats.deviceFreeSpace}")
        Log.d(TAG, "   Device total space: ${stats.deviceTotalSpace}")
        Log.d(TAG, "")

        // All Screens Summary
        if (screenImageCounts.isNotEmpty()) {
            Log.d(TAG, "📈 ALL SCREENS SUMMARY:")
            screenImageCounts.forEach { (screen, count) ->
                Log.d(TAG, "   $screen: $count images")
            }
            Log.d(TAG, "   Total images tracked: ${screenImageCounts.values.sum()}")
            Log.d(TAG, "")
        }

        // Estimated cache breakdown
        val avgImageSize: Long = if (stats.diskCacheFileCount > 0) {
            stats.diskCacheSizeBytes / stats.diskCacheFileCount.toLong()
        } else 0L

        if (avgImageSize > 0L) {
            Log.d(TAG, "📊 CACHE ANALYSIS:")
            Log.d(TAG, "   Total cached files: ${stats.diskCacheFileCount}")
            Log.d(TAG, "   Avg file size: ${formatBytes(avgImageSize)}")
            screenImageCounts.forEach { (screen, count) ->
                val estimatedSize = avgImageSize * count.toLong()
                Log.d(
                    TAG,
                    "   $screen est. cache: ${formatBytes(estimatedSize)} ($count images)"
                )
            }
            Log.d(TAG, "")
        }

        Log.d(TAG, "═══════════════════════════════════════════════════════════════════════")
        Log.d(TAG, "")
    }

    /**
     * Get detailed cache statistics
     */
    private fun gatherStats(context: Context): CacheStats {
        val imageLoader = context.imageLoader

        // Memory Cache (Coil)
        val memoryCache = imageLoader.memoryCache
        val memoryCacheSize = (memoryCache?.size ?: 0).toLong()
        val memoryCacheMaxSize = (memoryCache?.maxSize ?: 0).toLong()
        val memoryCachePercent = if (memoryCacheMaxSize > 0L) {
            (memoryCacheSize * 100L / memoryCacheMaxSize).toInt()
        } else 0

        // Disk Cache (Coil)
        val diskCache = imageLoader.diskCache
        val diskCacheSize = diskCache?.size ?: 0L
        val diskCacheMaxSize = diskCache?.maxSize ?: 0L
        val diskCachePercent = if (diskCacheMaxSize > 0L) {
            (diskCacheSize * 100L / diskCacheMaxSize).toInt()
        } else 0
        val diskCacheDir = diskCache?.directory?.toString() ?: "Unknown"

        // Count files in disk cache (directory may not be a java.io.File, so convert)
        val diskCacheFileCount = try {
            val dir = diskCache?.directory
            if (dir != null) {
                File(dir.toString())
                    .walkTopDown()
                    .count { it.isFile }
            } else 0
        } catch (e: Exception) {
            0
        }

        // App Memory
        val runtime = Runtime.getRuntime()
        val appMemoryUsed = runtime.totalMemory() - runtime.freeMemory()
        val appMemoryMax = runtime.maxMemory()
        val appMemoryPercent =
            if (appMemoryMax > 0L) ((appMemoryUsed * 100L) / appMemoryMax).toInt() else 0

        // Native heap (for images)
        val nativeHeapSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Runtime.getRuntime().totalMemory()
        } else {
            0L
        }

        // Device storage
        val internalDir = context.filesDir
        val stat = StatFs(internalDir.path)
        val deviceFreeSpace = stat.availableBytes
        val deviceTotalSpace = stat.totalBytes

        // App internal storage usage
        val appStorageUsed = calculateDirectorySize(context.cacheDir) +
                calculateDirectorySize(context.filesDir)

        // Memory cache item count (if available)
        val memoryCacheCount = try {
            // Use reflection to get size since it's not exposed
            val sizeField = memoryCache?.javaClass?.getDeclaredField("cache")
            sizeField?.isAccessible = true
            val cache = sizeField?.get(memoryCache) as? Map<*, *>
            cache?.size ?: 0
        } catch (e: Exception) {
            0
        }

        return CacheStats(
            memoryCacheSize = formatBytes(memoryCacheSize),
            memoryCacheMaxSize = formatBytes(memoryCacheMaxSize),
            memoryCachePercent = memoryCachePercent,
            memoryCacheCount = memoryCacheCount,
            diskCacheSize = formatBytes(diskCacheSize),
            diskCacheMaxSize = formatBytes(diskCacheMaxSize),
            diskCachePercent = diskCachePercent,
            diskCacheDir = diskCacheDir,
            diskCacheSizeBytes = diskCacheSize,
            diskCacheFileCount = diskCacheFileCount,
            appMemoryUsed = formatBytes(appMemoryUsed),
            appMemoryMax = formatBytes(appMemoryMax),
            appMemoryPercent = appMemoryPercent,
            nativeHeapSize = formatBytes(nativeHeapSize),
            deviceFreeSpace = formatBytes(deviceFreeSpace),
            deviceTotalSpace = formatBytes(deviceTotalSpace),
            appStorageUsed = formatBytes(appStorageUsed)
        )
    }

    /**
     * Calculate total size of a directory recursively
     */
    private fun calculateDirectorySize(directory: File): Long {
        return try {
            directory.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Format bytes to human-readable string
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt()

        return DecimalFormat("#,##0.##")
            .format(bytes / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }

    /**
     * Clear all tracked screen data
     */
    fun clearScreenTracking() {
        screenImageCounts.clear()
        Log.d(TAG, "🗑️  Screen tracking cleared")
    }

    /**
     * Clear Coil caches (useful for testing)
     */
    suspend fun clearAllCaches(context: Context) = withContext(Dispatchers.IO) {
        val imageLoader = context.imageLoader
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        screenImageCounts.clear()
        Log.d(TAG, "🗑️  All caches cleared")
    }

    /**
     * Data class holding all statistics
     */
    private data class CacheStats(
        val memoryCacheSize: String,
        val memoryCacheMaxSize: String,
        val memoryCachePercent: Int,
        val memoryCacheCount: Int,
        val diskCacheSize: String,
        val diskCacheMaxSize: String,
        val diskCachePercent: Int,
        val diskCacheDir: String,
        val diskCacheSizeBytes: Long,
        val diskCacheFileCount: Int,
        val appMemoryUsed: String,
        val appMemoryMax: String,
        val appMemoryPercent: Int,
        val nativeHeapSize: String,
        val deviceFreeSpace: String,
        val deviceTotalSpace: String,
        val appStorageUsed: String
    )
}
