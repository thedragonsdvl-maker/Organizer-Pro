package com.example

import android.net.Uri

enum class MediaType {
    PHOTO, VIDEO, SHORTCUT
}

data class MediaItem(
    val id: String,
    val type: MediaType,
    val title: String,
    val uriString: String? = null,
    val size: Long = 0L,         // Size in bytes
    val dateAdded: Long = 0L,    // Seconds since Epoch
    val packName: String? = null, // package name for app shortcuts
    val mockColorStart: Long = 0xFF121420L, // visual backgrounds for demo cards
    val mockColorEnd: Long = 0xFF1F2232L,
    val mockSubtitle: String = "",
    val isSystem: Boolean = false
) {
    val sizeFormatted: String
        get() {
            if (type == MediaType.SHORTCUT) return "System Action"
            val kb = size / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format("%.2f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$size Bytes"
            }
        }
}
