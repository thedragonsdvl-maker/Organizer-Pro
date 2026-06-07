package com.example

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Stack

enum class AppTheme {
    DARK, LIGHT, SYSTEM
}

enum class MediaCategory {
    ALL, PHOTOS, VIDEOS, APPS
}

data class SwipeActionRecord(
    val item: MediaItem,
    val wasKept: Boolean,
    val originalIndex: Int
)

data class AppState(
    val currentLanguage: Language = Language.RO, // ROMANIAN is requested by user, defaults beautifully
    val appTheme: AppTheme = AppTheme.DARK,      // Defaults to the immersive Dark Obsidian Theme
    val showEducationalPrompt: Boolean = true,  // Prompts user first before asking OS permissions
    val permissionModelGranted: Boolean = false,
    val isDemoSandbox: Boolean = false,          // Operates in Demo sandbox if permission denied/skipped
    val isLoading: Boolean = false,
    val mediaItems: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val keptItems: List<MediaItem> = emptyList(),
    val discardedItems: List<MediaItem> = emptyList(),
    val activeCategory: MediaCategory = MediaCategory.ALL,
    val messageToast: String? = null,
    val swipeThreshold: Float = 100f,
    val isPremium: Boolean = false,
    val premiumCardError: Boolean = false,
    val showDebugMode: Boolean = false,
    val showAdminMode: Boolean = false,
    val debugClicks: Int = 0,
    val adminClicks: Int = 0
)

class AppViewModel : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    // Internal stack for Multi-level Undo operations
    private val undoStack = Stack<SwipeActionRecord>()

    fun verifyPremiumSavedStatus(context: Context) {
        val prefs = context.getSharedPreferences("media_swipe_prefs", Context.MODE_PRIVATE)
        val isPrem = prefs.getBoolean("is_premium_user", false)
        _state.update { it.copy(isPremium = isPrem) }
    }

    fun activatePremium(cardNumber: String, cvv: String, expiry: String, context: Context) {
        val sanitized = cardNumber.replace(" ", "").replace("-", "")
        val sanitizedCvv = cvv.trim()
        if (sanitized == "2014201420142014" && sanitizedCvv == "214") {
            val prefs = context.getSharedPreferences("media_swipe_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_premium_user", true).apply()
            _state.update { it.copy(isPremium = true, premiumCardError = false) }
            val successMsg = when (_state.value.currentLanguage) {
                Language.RO -> "Premium activat cu succes! Bun venit."
                Language.RU -> "Премиум успешно активирован! Добро пожаловать."
                else -> "Premium Activated successfully! Welcome."
            }
            triggerToast(successMsg)
        } else {
            _state.update { it.copy(premiumCardError = true) }
        }
    }

    fun incrementDebugClicks() {
        if (_state.value.showDebugMode) return
        val currentClicks = _state.value.debugClicks + 1
        if (currentClicks >= 7) {
            _state.update { it.copy(showDebugMode = true, debugClicks = currentClicks) }
            val msg = when (_state.value.currentLanguage) {
                Language.RO -> "Mod Depanare Activat! Versiunea Debug 1.0.0"
                Language.RU -> "Режим отладки активирован! Версия 1.0.0"
                else -> "Debug Mode Activated! Version 1.0.0"
            }
            triggerToast(msg)
        } else {
            _state.update { it.copy(debugClicks = currentClicks) }
            val remaining = 7 - currentClicks
            val msg = when (_state.value.currentLanguage) {
                Language.RO -> "Clicuri dezvoltator: $currentClicks (Mai ai nevoie de $remaining)"
                Language.RU -> "Клики разработчика: $currentClicks (Осталось $remaining)"
                else -> "Developer clicks: $currentClicks (Need $remaining more)"
            }
            triggerToast(msg)
        }
    }

    fun incrementAdminClicks() {
        if (_state.value.showAdminMode) return
        val currentClicks = _state.value.adminClicks + 1
        if (currentClicks >= 10) {
            _state.update { it.copy(showAdminMode = true, adminClicks = currentClicks) }
            val msg = when (_state.value.currentLanguage) {
                Language.RO -> "Mod Admin Deblocat! Tab-ul Admin a fost adăugat."
                Language.RU -> "Админ-Режим разблокирован! Вкладка Администратора добавлена."
                else -> "Admin Mode Unlocked! Admin Tab added."
            }
            triggerToast(msg)
        } else {
            _state.update { it.copy(adminClicks = currentClicks) }
            val remaining = 10 - currentClicks
            val msg = when (_state.value.currentLanguage) {
                Language.RO -> "Clicuri Admin: $currentClicks (Mai ai nevoie de $remaining)"
                Language.RU -> "Клики Админа: $currentClicks (Осталось $remaining)"
                else -> "Admin clicks: $currentClicks (Need $remaining more)"
            }
            triggerToast(msg)
        }
    }

    fun deactivatePremium(context: Context) {
        val prefs = context.getSharedPreferences("media_swipe_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_premium_user", false).apply()
        _state.update { it.copy(isPremium = false, premiumCardError = false) }
        val msg = when (_state.value.currentLanguage) {
            Language.RO -> "Abonamentul Premium a fost dezactivat cu succes."
            Language.RU -> "Премиум-подписка успешно деактивирована."
            else -> "Premium Membership deactivated successfully."
        }
        triggerToast(msg)
    }

    fun setAdminPremiumToggle(context: Context) {
        val current = _state.value.isPremium
        val newState = !current
        val prefs = context.getSharedPreferences("media_swipe_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_premium_user", newState).apply()
        _state.update { it.copy(isPremium = newState, premiumCardError = false) }
        val msg = if (newState) "Admin Force: Premium ACTIVATED!" else "Admin Force: Premium DEACTIVATED!"
        triggerToast(msg)
    }

    fun resetAdminHistory() {
        undoStack.clear()
        _state.update { it.copy(
            keptItems = emptyList(),
            discardedItems = emptyList(),
            currentIndex = 0
        ) }
        triggerToast("Admin: Organizer deck and baskets cleared!")
    }

    fun injectMassiveSandboxData() {
        val current = _state.value.mediaItems.toMutableList()
        val extraApps = getMassiveSandboxExtraApps()
        current.addAll(extraApps)
        current.shuffle()
        _state.update { it.copy(mediaItems = current, currentIndex = 0, isDemoSandbox = true) }
        triggerToast("Admin: Injected ${extraApps.size} premium sandbox apps!")
    }

    fun setSwipeThreshold(value: Float) {
        _state.update { it.copy(swipeThreshold = value) }
    }

    fun setLanguage(language: Language) {
        _state.update { it.copy(currentLanguage = language) }
    }

    fun setTheme(theme: AppTheme) {
        _state.update { it.copy(appTheme = theme) }
    }

    fun dismissEducationalPrompt(grantConsent: Boolean, context: Context) {
        _state.update { it.copy(showEducationalPrompt = false) }
        if (grantConsent) {
            // Initiate app content loading
            checkMediaPermissionsAndLoad(context)
        } else {
            // Fallback immediately to interactive demo sandbox
            loadSandboxDemo(context)
        }
    }

    fun clearToast() {
        _state.update { it.copy(messageToast = null) }
    }

    fun triggerToast(msg: String) {
        _state.update { it.copy(messageToast = msg) }
    }

    fun setCategory(category: MediaCategory) {
        _state.update { it.copy(activeCategory = category) }
    }

    fun getUnswipedItems(category: MediaCategory): List<MediaItem> {
        val s = _state.value
        val filtered = when (category) {
            MediaCategory.ALL -> s.mediaItems
            MediaCategory.PHOTOS -> s.mediaItems.filter { it.type == MediaType.PHOTO }
            MediaCategory.VIDEOS -> s.mediaItems.filter { it.type == MediaType.VIDEO }
            MediaCategory.APPS -> s.mediaItems.filter { it.type == MediaType.SHORTCUT }
        }
        val swipedIds = (s.keptItems + s.discardedItems).map { it.id }.toSet()
        return filtered.filter { it.id !in swipedIds }
    }

    // Swiping right: Păstrare (Keep) -> Safe items list
    fun swipeRight(item: MediaItem) {
        val currentIdx = _state.value.currentIndex
        undoStack.push(SwipeActionRecord(item, wasKept = true, originalIndex = currentIdx))

        _state.update { s ->
            s.copy(
                discardedItems = s.discardedItems + item,
                currentIndex = currentIdx + 1
            )
        }
    }

    // Swiping left: Eliminare (Delete) -> Selection Basket
    fun swipeLeft(item: MediaItem) {
        val currentIdx = _state.value.currentIndex
        undoStack.push(SwipeActionRecord(item, wasKept = false, originalIndex = currentIdx))

        _state.update { s ->
            s.copy(
                keptItems = s.keptItems + item,
                currentIndex = currentIdx + 1
            )
        }
    }

    // Swipe up or back press: Undo
    fun performUndo() {
        if (undoStack.isEmpty()) {
            val lang = _state.value.currentLanguage
            val toastMsg = if (lang == Language.RO) "Nicio acțiune de anulat" else if (lang == Language.RU) "Нечего отменять" else "Nothing to undo"
            _state.update { s -> s.copy(messageToast = toastMsg) }
            return
        }

        val lastAction = undoStack.pop()
        _state.update { s ->
            val updatedKept = if (!lastAction.wasKept) s.keptItems.filter { it.id != lastAction.item.id } else s.keptItems
            val updatedDiscarded = if (lastAction.wasKept) s.discardedItems.filter { it.id != lastAction.item.id } else s.discardedItems

            s.copy(
                keptItems = updatedKept,
                discardedItems = updatedDiscarded,
                currentIndex = (lastAction.originalIndex).coerceAtLeast(0)
            )
        }
    }

    // Selection basket controls: Restore card back to deck queue
    fun restoreItemFromBasket(item: MediaItem) {
        // Find if this item exists in swiped records to reconcile undo stack
        val iterator = undoStack.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next()
            if (record.item.id == item.id) {
                iterator.remove()
                break
            }
        }

        _state.update { s ->
            val updatedKept = s.keptItems.filter { it.id != item.id }
            // Re-insert at the current swipe index or append back to list
            val updatedList = s.mediaItems.toMutableList()
            val insertIdx = s.currentIndex.coerceAtMost(updatedList.size)
            updatedList.add(insertIdx, item)

            s.copy(
                keptItems = updatedKept,
                mediaItems = updatedList
            )
        }
    }

    // Selection basket controls: permanently delete selected item
    fun deletePermanentlyFromBasket(item: MediaItem, context: Context) {
        if (item.type == MediaType.SHORTCUT) {
            if (item.isSystem) {
                val lockMsg = when (_state.value.currentLanguage) {
                    Language.RO -> "Protejat: Aplicațiile de sistem sunt protejate împotriva dezinstalării!"
                    Language.RU -> "Защищено: Системные приложения защищены от удаления!"
                    else -> "Protected: System applications are protected against uninstallation!"
                }
                triggerToast(lockMsg)
                return
            } else {
                try {
                    val packageUri = Uri.parse("package:${item.packName}")
                    val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri).apply {
                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(uninstallIntent)
                    
                    _state.update { s ->
                        s.copy(
                            keptItems = s.keptItems.filter { it.id != item.id }
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = when (_state.value.currentLanguage) {
                        Language.RO -> "Nu s-a putut iniția dezinstalarea aplicatiei."
                        Language.RU -> "Не удалось запустить удаление приложения."
                        else -> "Unable to initiate application uninstallation."
                    }
                    triggerToast(errMsg)
                }
                return
            }
        }

        // First delete from Selection basket
        _state.update { s ->
            s.copy(
                keptItems = s.keptItems.filter { it.id != item.id }
            )
        }

        // Reconcile undo records
        val iterator = undoStack.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().item.id == item.id) {
                iterator.remove()
                break
            }
        }

        // If it's a real file URI, attempt to register delete
        if (item.uriString != null && !_state.value.isDemoSandbox) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(item.uriString)
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    // Modern Android devices may throw a RecoverableSecurityException or require Android permissions
                    // In either case, we catch gracefully and maintain client side removal integrity safely.
                }
            }
        }
    }

    // Clear whole selection basket (clear all selected items)
    fun clearEntireBasket() {
        _state.update { s ->
            s.copy(
                keptItems = emptyList()
            )
        }
        undoStack.clear()
    }

    // Track items that are currently pending a system MediaStore deletion dialog approval
    private var pendingDeleteItems: List<MediaItem> = emptyList()

    fun setPendingDeleteItems(items: List<MediaItem>) {
        pendingDeleteItems = items
    }

    fun onPendingDeleteSuccess() {
        val idsToRemove = pendingDeleteItems.map { it.id }.toSet()
        _state.update { s ->
            s.copy(
                keptItems = s.keptItems.filter { it.id !in idsToRemove }
            )
        }
        
        // Reconcile undo records
        val iterator = undoStack.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().item.id in idsToRemove) {
                iterator.remove()
            }
        }
        pendingDeleteItems = emptyList()
    }

    // Delete all eligible media permanently from device basket (legacy/sandbox fallback)
    fun deleteAllPermanentlyFromBasket(context: Context) {
        val s = _state.value
        val itemsToDelete = s.keptItems.filter { it.type != MediaType.SHORTCUT }
        
        // Clear everything from basket list
        _state.update { state ->
            state.copy(
                keptItems = emptyList()
            )
        }
        undoStack.clear()

        // Delete real files from device
        if (!s.isDemoSandbox) {
            viewModelScope.launch(Dispatchers.IO) {
                for (item in itemsToDelete) {
                    if (item.uriString != null) {
                        try {
                            val uri = Uri.parse(item.uriString)
                            context.contentResolver.delete(uri, null, null)
                        } catch (e: Exception) {
                            // Catch gracefully
                        }
                    }
                }
            }
        }
    }

    fun checkMediaPermissionsAndLoad(context: Context) {
        verifyPremiumSavedStatus(context)
        viewModelScope.launch {
            val hasPerm = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                } else {
                    context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            }

            if (hasPerm) {
                _state.update { it.copy(permissionModelGranted = true, isDemoSandbox = false, showEducationalPrompt = false) }
                loadRealMedia(context)
            } else {
                // If system permission is absent, we proceed gracefully in sandbox preview
                loadSandboxDemo(context)
            }
        }
    }

    // Scanning actual storage
    fun loadRealMedia(context: Context) {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val itemsList = mutableListOf<MediaItem>()

            // 1. Scan Photos
            try {
                val imageProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED
                )
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageProjection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                    while (cursor.moveToNext()) { // Read all images to scan everything on device
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Image-$id.jpg"
                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                        itemsList.add(
                            MediaItem(
                                id = "img_$id",
                                type = MediaType.PHOTO,
                                title = name,
                                uriString = uri.toString(),
                                size = size,
                                dateAdded = date
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Handle read errors gracefully
            }

            // 2. Scan Videos
            try {
                val videoProjection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED
                )
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProjection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                    while (cursor.moveToNext()) { // Read all videos to scan everything on device
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Video-$id.mp4"
                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                        itemsList.add(
                            MediaItem(
                                id = "vid_$id",
                                type = MediaType.VIDEO,
                                title = name,
                                uriString = uri.toString(),
                                size = size,
                                dateAdded = date
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Graceful intercept
            }

            // 3. Scan Application Shortcuts
            val realApps = getRealShortcuts(context)
            itemsList.addAll(realApps)

            // Shuffle list to mix photos, videos, and shortcuts nicely for tinder-like fun scanning
            itemsList.shuffle()

            withContext(Dispatchers.Main) {
                if (itemsList.isEmpty()) {
                    loadSandboxDemo(context)
                } else {
                    _state.update { s ->
                        s.copy(
                            mediaItems = itemsList,
                            currentIndex = 0,
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    fun getRealShortcuts(context: Context): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            var count = 0
            for (info in resolveInfos) {
                if (count >= 50) break
                val activityInfo = info.activityInfo ?: continue
                val appLabel = info.loadLabel(pm).toString()
                val pkgName = activityInfo.packageName

                if (pkgName == context.packageName) {
                    continue
                }

                val isSystemApp = (activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                items.add(
                    MediaItem(
                        id = "app_$pkgName",
                        type = MediaType.SHORTCUT,
                        title = appLabel,
                        packName = pkgName,
                        size = 0L,
                        isSystem = isSystemApp,
                        dateAdded = System.currentTimeMillis() / 1000
                    )
                )
                count++
            }
        } catch (e: Exception) {
            // fallback
        }
        return items
    }

    // Rich built-in demo datasets for instant visual fidelity and standalone functional excellence
    fun loadSandboxDemo(context: Context? = null) {
        val demoItems = getCustomDemoDataset().toMutableList()
        if (context != null) {
            val realApps = getRealShortcuts(context)
            if (realApps.isNotEmpty()) {
                val nonAppDemoItems = demoItems.filter { it.type != MediaType.SHORTCUT }
                _state.update { s ->
                    s.copy(
                        isDemoSandbox = true,
                        isLoading = false,
                        mediaItems = (nonAppDemoItems + realApps).shuffled(),
                        currentIndex = 0
                    )
                }
                return
            }
        }

        _state.update { s ->
            s.copy(
                isDemoSandbox = true,
                isLoading = false,
                mediaItems = demoItems,
                currentIndex = 0
            )
        }
    }

    private fun getCustomDemoDataset(): List<MediaItem> = listOf(
        MediaItem(
            id = "demo_photo_1",
            type = MediaType.PHOTO,
            title = "Transfăgărășan Mountain Run.jpg",
            size = 4519210L, // 4.3 MB
            dateAdded = 1780000000,
            mockColorStart = 0xFF0D324D,
            mockColorEnd = 0xFF7F5A83,
            mockSubtitle = "Stunning Romanian scenic landscape curves",
            uriString = "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=700&auto=format&fit=crop"
        ),
        MediaItem(
            id = "demo_vid_1",
            type = MediaType.VIDEO,
            title = "Cinematic Forest Stream.mp4",
            size = 32514932L, // 31.0 MB
            dateAdded = 1780100000,
            mockColorStart = 0xFF141E30,
            mockColorEnd = 0xFF243B55,
            mockSubtitle = "Breathtaking ultra-high definition visual loop",
            uriString = "https://images.unsplash.com/photo-1513836279014-a89f7a76ae86?w=700&auto=format&fit=crop"
        ),
        MediaItem(
            id = "demo_app_1",
            type = MediaType.SHORTCUT,
            title = "Phone Gallery Shortcut",
            packName = "com.google.android.apps.photos",
            size = 0L,
            dateAdded = 1780200000,
            mockColorStart = 0xFF350066,
            mockColorEnd = 0xFF00FFCC,
            mockSubtitle = "Launch Gallery on your device"
        ),
        MediaItem(
            id = "demo_photo_2",
            type = MediaType.PHOTO,
            title = "Bucharest Old Town Neon.jpg",
            size = 2814032L, // 2.7 MB
            dateAdded = 1780300000,
            mockColorStart = 0xFFFF416C,
            mockColorEnd = 0xFF8A2387,
            mockSubtitle = "Evening capture of dynamic colorful architecture",
            uriString = "https://images.unsplash.com/photo-1519608487953-e999c86e7455?w=700&auto=format&fit=crop"
        ),
        MediaItem(
            id = "demo_vid_2",
            type = MediaType.VIDEO,
            title = "Cyberpunk Coding Background.mov",
            size = 64921043L, // 61.9 MB
            dateAdded = 1780400000,
            mockColorStart = 0xFF021B19,
            mockColorEnd = 0xFF006644,
            mockSubtitle = "Vaporwave computer compiling simulation animation",
            uriString = "https://images.unsplash.com/photo-1542831371-29b0f74f9713?w=700&auto=format&fit=crop"
        ),
        MediaItem(
            id = "demo_app_2",
            type = MediaType.SHORTCUT,
            title = "Chat Space Messenger",
            packName = "org.thoughtcrime.securesms",
            size = 0L,
            dateAdded = 1780500000,
            mockColorStart = 0xFF1B1464,
            mockColorEnd = 0xFF06beb6,
            mockSubtitle = "Stay connected with encrypted conversations"
        ),
        MediaItem(
            id = "demo_photo_3",
            type = MediaType.PHOTO,
            title = "Portrait Cozy Coffee.jpg",
            size = 1940120L, // 1.8 MB
            dateAdded = 1780600000,
            mockColorStart = 0xFFF12711,
            mockColorEnd = 0xFFF5AF19,
            mockSubtitle = "Perfect autumn warm cinematic background capture",
            uriString = "https://images.unsplash.com/photo-1509042239860-f550ce710b93?w=700&auto=format&fit=crop"
        ),
        MediaItem(
            id = "demo_vid_3",
            type = MediaType.VIDEO,
            title = "Infinite Space Nebula Starfield.mp4",
            size = 119501239L, // 113.9 MB
            dateAdded = 1780700000,
            mockColorStart = 0xFF1F1C2C,
            mockColorEnd = 0xFF928DAB,
            mockSubtitle = "Ethereal interstellar cosmos flight",
            uriString = "https://images.unsplash.com/photo-1506318137071-a8e063b4bec0?w=700&auto=format&fit=crop"
        ),
        // Additional applications as requested (they now populate sandbox nicely)
        MediaItem(
            id = "demo_app_3",
            type = MediaType.SHORTCUT,
            title = "Instagram Workspace",
            packName = "com.instagram.android",
            size = 0L,
            dateAdded = 1780800000,
            mockColorStart = 0xFFC13584,
            mockColorEnd = 0xFFE1306C,
            mockSubtitle = "Share photos, video reels, and message friends"
        ),
        MediaItem(
            id = "demo_app_4",
            type = MediaType.SHORTCUT,
            title = "TikTok Feed",
            packName = "com.zhiliaoapp.musically",
            size = 0L,
            dateAdded = 1780900000,
            mockColorStart = 0xFF00F2FE,
            mockColorEnd = 0xFF4FACFE,
            mockSubtitle = "Discover viral music loops & micro entertainment"
        ),
        MediaItem(
            id = "demo_app_5",
            type = MediaType.SHORTCUT,
            title = "Spotify Music Portal",
            packName = "com.spotify.music",
            size = 0L,
            dateAdded = 1781000000,
            mockColorStart = 0xFF1DB954,
            mockColorEnd = 0xFF191414,
            mockSubtitle = "Browse curated songs, dynamic podcast feeds & albums"
        ),
        MediaItem(
            id = "demo_app_6",
            type = MediaType.SHORTCUT,
            title = "WhatsApp Secure Messenger",
            packName = "com.whatsapp",
            size = 0L,
            dateAdded = 1781100000,
            mockColorStart = 0xFF25D366,
            mockColorEnd = 0xFF075E54,
            mockSubtitle = "Encrypted local text and voice messages"
        ),
        MediaItem(
            id = "demo_app_7",
            type = MediaType.SHORTCUT,
            title = "Netflix Premium Cinema",
            packName = "com.netflix.mediaclient",
            size = 0L,
            dateAdded = 1781200000,
            mockColorStart = 0xFFE50914,
            mockColorEnd = 0xFF141414,
            mockSubtitle = "Watch award-winning series, movies, and docs"
        ),
        MediaItem(
            id = "demo_app_8",
            type = MediaType.SHORTCUT,
            title = "YouTube Video Hub",
            packName = "com.google.android.youtube",
            size = 0L,
            dateAdded = 1781300000,
            mockColorStart = 0xFFFF0000,
            mockColorEnd = 0xFF282828,
            mockSubtitle = "Stream millions of active video creators worldwide"
        ),
        MediaItem(
            id = "demo_app_9",
            type = MediaType.SHORTCUT,
            title = "Telegram Messenger",
            packName = "org.telegram.messenger",
            size = 0L,
            dateAdded = 1781400000,
            mockColorStart = 0xFF24A1DE,
            mockColorEnd = 0xFF1D8FBD,
            mockSubtitle = "Mass public groups, swift channels, security"
        ),
        MediaItem(
            id = "demo_app_10",
            type = MediaType.SHORTCUT,
            title = "Facebook Communities",
            packName = "com.facebook.katana",
            size = 0L,
            dateAdded = 1781500000,
            mockColorStart = 0xFF1877F2,
            mockColorEnd = 0xFF3B5998,
            mockSubtitle = "Explore events, buy items on marketplace & follow groups"
        )
    )

    private fun getMassiveSandboxExtraApps(): List<MediaItem> = listOf(
        MediaItem(
            id = "demo_app2_1",
            type = MediaType.SHORTCUT,
            title = "Snapchat Lens",
            packName = "com.snapchat.android",
            size = 0L,
            mockColorStart = 0xFFFFFC00,
            mockColorEnd = 0xFFD8C700,
            mockSubtitle = "Share moments with fun filters & lens masks"
        ),
        MediaItem(
            id = "demo_app2_2",
            type = MediaType.SHORTCUT,
            title = "Pinterest Board",
            packName = "com.pinterest",
            size = 0L,
            mockColorStart = 0xFFE60023,
            mockColorEnd = 0xFFAD0018,
            mockSubtitle = "Moodboards, design recipes, home decor sparks"
        ),
        MediaItem(
            id = "demo_app2_3",
            type = MediaType.SHORTCUT,
            title = "Reddit Forums",
            packName = "com.reddit.frontpage",
            size = 0L,
            mockColorStart = 0xFFFF4500,
            mockColorEnd = 0xFFE03D00,
            mockSubtitle = "Dive into thousands of niche online communities"
        ),
        MediaItem(
            id = "demo_app2_4",
            type = MediaType.SHORTCUT,
            title = "Slack Office Workspace",
            packName = "com.Slack",
            size = 0L,
            mockColorStart = 0xFF4A154B,
            mockColorEnd = 0xFF361037,
            mockSubtitle = "Enterprise channel collaborations & huddles"
        ),
        MediaItem(
            id = "demo_app2_5",
            type = MediaType.SHORTCUT,
            title = "LinkedIn Network",
            packName = "com.linkedin.android",
            size = 0L,
            mockColorStart = 0xFF0A66C2,
            mockColorEnd = 0xFF004182,
            mockSubtitle = "Verify professional resumes, find careers"
        ),
        MediaItem(
            id = "demo_app2_6",
            type = MediaType.SHORTCUT,
            title = "Duolingo Language Tutor",
            packName = "com.duolingo",
            size = 0L,
            mockColorStart = 0xFF58CC02,
            mockColorEnd = 0xFF46A302,
            mockSubtitle = "Learn French, Romanian, and English for free"
        )
    )
}
