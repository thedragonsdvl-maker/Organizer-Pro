package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.app.Activity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.MyApplicationTheme
import com.example.Language
import com.example.TranslationPack
import com.example.translations
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appViewModel: AppViewModel = viewModel()
            val state by appViewModel.state.collectAsStateWithLifecycle()
            val context = LocalContext.current

            // Dynamically evaluate theme mode (Light, Dark, or System)
            val isSystemDark = isSystemInDarkTheme()
            val finalDarkTheme = when (state.appTheme) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.SYSTEM -> isSystemDark
            }

            MyApplicationTheme(darkTheme = finalDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppLayout(
                        state = state,
                        viewModel = appViewModel,
                        context = context
                    )
                }
            }
        }
    }
}

// Global UI Navigation states representing screens
enum class AppScreen {
    SWIPE, BASKET, SETTINGS, ADMIN
}

@Composable
fun MainAppLayout(
    state: AppState,
    viewModel: AppViewModel,
    context: Context
) {
    var activeTab by remember { mutableStateOf(AppScreen.SWIPE) }
    var activePlayingVideo by remember { mutableStateOf<com.example.MediaItem?>(null) }
    val strings = translations[state.currentLanguage] ?: translations[Language.EN]!!

    // Launcher to handle user confirmation of real file deletions (Android 11+ / API 30+)
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onPendingDeleteSuccess()
            val successMsg = when (state.currentLanguage) {
                com.example.Language.RO -> "Fișierele au fost șterse de pe dispozitiv!"
                com.example.Language.RU -> "Файлы успешно удалены!"
                else -> "Files deleted successfully!"
            }
            viewModel.triggerToast(successMsg)
        } else {
            val cancelMsg = when (state.currentLanguage) {
                com.example.Language.RO -> "Ștergere anulată de utilizator."
                com.example.Language.RU -> "Удаление отменено пользователем."
                else -> "Deletion cancelled by user."
            }
            viewModel.triggerToast(cancelMsg)
        }
    }

    val requestDeleteItems = { items: List<com.example.MediaItem> ->
        val itemsToDelete = items.filter { it.type != com.example.MediaType.SHORTCUT }
        if (itemsToDelete.isEmpty()) {
            val ignoreMsg = when (state.currentLanguage) {
                com.example.Language.RO -> "Niciun fișier de pe stocare nu a fost selectat."
                com.example.Language.RU -> "Нет подходящих файлов для удаления."
                else -> "No eligible media files to delete from storage."
            }
            viewModel.triggerToast(ignoreMsg)
        } else if (state.isDemoSandbox) {
            viewModel.setPendingDeleteItems(itemsToDelete)
            viewModel.onPendingDeleteSuccess()
            val successMsg = when (state.currentLanguage) {
                com.example.Language.RO -> "Toate fișierele au fost șterse!"
                com.example.Language.RU -> "Все выбранные файлы удалены!"
                else -> "All selected media files deleted permanently!"
            }
            viewModel.triggerToast(successMsg)
        } else {
            val uris = itemsToDelete.mapNotNull { it.uriString?.let { uriStr -> Uri.parse(uriStr) } }
            if (uris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    viewModel.setPendingDeleteItems(itemsToDelete)
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    val intentSenderRequest = androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    deleteLauncher.launch(intentSenderRequest)
                } catch (e: Exception) {
                    viewModel.setPendingDeleteItems(itemsToDelete)
                    itemsToDelete.forEach { viewModel.deletePermanentlyFromBasket(it, context) }
                }
            } else {
                viewModel.setPendingDeleteItems(itemsToDelete)
                itemsToDelete.forEach { viewModel.deletePermanentlyFromBasket(it, context) }
            }
        }
    }

    // Auto-check permissions on launch to bypass onboarding if already granted
    LaunchedEffect(Unit) {
        viewModel.checkMediaPermissionsAndLoad(context)
    }

    // Toast and notifier handler
    LaunchedEffect(state.messageToast) {
        state.messageToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // Permission launcher to handle system queries
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        if (granted) {
            viewModel.triggerToast(strings.restoredToast.replace("...", "") + " (Access Granted)")
            viewModel.checkMediaPermissionsAndLoad(context)
        } else {
            viewModel.triggerToast("Demo Mode Enabled")
            viewModel.dismissEducationalPrompt(grantConsent = false, context = context)
        }
    }

    // Secure Premium Gate Control Frame (Blocking access unless payment is activated)
    if (!state.isPremium) {
        PremiumGateScreen(
            state = state,
            viewModel = viewModel,
            strings = strings,
            context = context
        )
    } else if (state.showEducationalPrompt) {
        EducationalOnboardingPage(
            state = state,
            strings = strings,
            onDismiss = { trySystemPermission ->
                if (trySystemPermission) {
                    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO
                        )
                    } else {
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    mediaPermissionLauncher.launch(permissions)
                } else {
                    viewModel.dismissEducationalPrompt(grantConsent = false, context = context)
                }
            }
        )
    } else {
        Scaffold(
            topBar = {
                ProfessionalTopAppBar(
                    state = state,
                    strings = strings
                )
            },
            bottomBar = {
                // Polished modern bottom bar with navigation
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier.navigationBarsPadding().graphicsLayer {
                        shadowElevation = 12.dp.toPx()
                    }
                ) {
                    NavigationBarItem(
                        selected = activeTab == AppScreen.SWIPE,
                        onClick = { activeTab = AppScreen.SWIPE },
                        icon = { Icon(Icons.Default.Swipe, contentDescription = strings.selectTabSwipe) },
                        label = { Text(strings.selectTabSwipe, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    )

                    NavigationBarItem(
                        selected = activeTab == AppScreen.BASKET,
                        onClick = { activeTab = AppScreen.BASKET },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (state.keptItems.isNotEmpty()) {
                                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                                            Text(state.keptItems.size.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = strings.selectTabBasket)
                            }
                        },
                        label = { Text(strings.selectTabBasket, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    )

                    NavigationBarItem(
                        selected = activeTab == AppScreen.SETTINGS,
                        onClick = { activeTab = AppScreen.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = strings.selectTabSettings) },
                        label = { Text(strings.selectTabSettings, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    )

                    if (state.showAdminMode) {
                        val adminLabel = if (state.currentLanguage == com.example.Language.RO) "Mod Admin" else if (state.currentLanguage == com.example.Language.RU) "Админ" else "Admin Mode"
                        NavigationBarItem(
                            selected = activeTab == AppScreen.ADMIN,
                            onClick = { activeTab = AppScreen.ADMIN },
                            icon = { Icon(Icons.Default.Build, contentDescription = adminLabel) },
                            label = { Text(adminLabel, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFFFFD700),
                                selectedTextColor = Color(0xFFFFD700),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Crossfade screen transitions
                AnimatedContent(
                    targetState = activeTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "AppScreenTransition"
                ) { targetScreen ->
                    when (targetScreen) {
                        AppScreen.SWIPE -> SwipeOrganizerScreen(
                            state = state,
                            viewModel = viewModel,
                            strings = strings,
                            onTriggerPermission = {
                                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    arrayOf(
                                        Manifest.permission.READ_MEDIA_IMAGES,
                                        Manifest.permission.READ_MEDIA_VIDEO
                                    )
                                } else {
                                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                                mediaPermissionLauncher.launch(permissions)
                            },
                            onPlayVideo = { activePlayingVideo = it }
                        )
                        AppScreen.BASKET -> SelectionBasketScreen(
                            state = state,
                            viewModel = viewModel,
                            context = context,
                            strings = strings,
                            onDeleteItems = requestDeleteItems,
                            onPlayVideo = { activePlayingVideo = it }
                        )
                        AppScreen.SETTINGS -> SettingsScreen(
                            state = state,
                            viewModel = viewModel,
                            strings = strings
                        )
                        AppScreen.ADMIN -> AdminModeScreen(
                            state = state,
                            viewModel = viewModel,
                            context = context
                        )
                    }
                }
                
                activePlayingVideo?.let { item ->
                    VideoPlayerDialog(
                        item = item,
                        onDismiss = { activePlayingVideo = null }
                    )
                }
            }
        }
    }
}

@Composable
fun EducationalOnboardingPage(
    state: AppState,
    strings: TranslationPack,
    onDismiss: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Top Accent Graphic Illustration
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = strings.permissionTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = strings.permissionReason,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = strings.infoStorageCheck,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Action CTAs
            Button(
                onClick = { onDismiss(true) },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("onboarding_grant_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    strings.grantPermissionBtn,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = { onDismiss(false) },
                modifier = Modifier.testTag("onboarding_sandbox_button")
            ) {
                Text(
                    strings.useSandboxBtn,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun ProfessionalTopAppBar(
    state: AppState,
    strings: TranslationPack
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .border(width = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(0.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left App Logo & Title block
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Circular icon frame
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Collections,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = strings.appTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "ORGANIZER PRO",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            letterSpacing = 1.5.sp
                        ),
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }

            // Right active language capsule
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = state.currentLanguage.displayName.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun FloatingAnalysisBadge(
    scannedCount: Int,
    isDemo: Boolean,
    strings: TranslationPack
) {
    // Pulse animation logic
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scaleFactor by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, color = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .padding(bottom = 12.dp, top = 8.dp)
            .height(38.dp)
            .wrapContentWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Emerald pulsing dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scaleFactor)
                    .background(Color(0xFF00FF87), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            val textToDisplay = if (isDemo) {
                "${scannedCount} DEMO FILES SCANNED"
            } else {
                "${scannedCount} LOCAL IMAGES ANALYZED"
            }
            Text(
                text = textToDisplay,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun CategorySelectorBar(
    activeCategory: MediaCategory,
    onCategorySelected: (MediaCategory) -> Unit,
    language: com.example.Language
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.example.MediaCategory.values().forEach { cat ->
            val isSelected = activeCategory == cat
            val label = when (language) {
                com.example.Language.RO -> when (cat) {
                    com.example.MediaCategory.ALL -> "Toate"
                    com.example.MediaCategory.PHOTOS -> "Imagini"
                    com.example.MediaCategory.VIDEOS -> "Videoclipuri"
                    com.example.MediaCategory.APPS -> "Aplicații"
                }
                com.example.Language.RU -> when (cat) {
                    com.example.MediaCategory.ALL -> "Все"
                    com.example.MediaCategory.PHOTOS -> "Изображения"
                    com.example.MediaCategory.VIDEOS -> "Видео"
                    com.example.MediaCategory.APPS -> "Приложения"
                }
                else -> when (cat) {
                    com.example.MediaCategory.ALL -> "All"
                    com.example.MediaCategory.PHOTOS -> "Images"
                    com.example.MediaCategory.VIDEOS -> "Videos"
                    com.example.MediaCategory.APPS -> "Applications"
                }
            }
            val icon = when (cat) {
                com.example.MediaCategory.ALL -> Icons.Default.Layers
                com.example.MediaCategory.PHOTOS -> Icons.Default.Image
                com.example.MediaCategory.VIDEOS -> Icons.Default.PlayCircle
                com.example.MediaCategory.APPS -> Icons.Default.Apps
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                ),
                modifier = Modifier
                    .height(38.dp)
                    .clickable { onCategorySelected(cat) }
                    .testTag("cat_selector_${cat.name}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SwipeOrganizerScreen(
    state: AppState,
    viewModel: AppViewModel,
    strings: TranslationPack,
    onTriggerPermission: () -> Unit,
    onPlayVideo: (MediaItem) -> Unit
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val swipeList = viewModel.getUnswipedItems(state.activeCategory)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Separate Sections filter tabs UI
        CategorySelectorBar(
            activeCategory = state.activeCategory,
            onCategorySelected = { cat -> viewModel.setCategory(cat) },
            language = state.currentLanguage
        )

        // 2. Storage Permission Warning (Inline if demo mode is enabled and permission not granted)
        if (state.isDemoSandbox && !state.permissionModelGranted) {
            val titleText = if (state.currentLanguage == com.example.Language.RO) "Acces necesar la stocare" else if (state.currentLanguage == com.example.Language.RU) "Требуется доступ к файлам" else "Storage Access Required"
            val bodyText = if (state.currentLanguage == com.example.Language.RO) "Avem nevoie de permisiune doar pentru a vă afișa fotografiile și videoclipurile pentru a le putea organiza eficient." else if (state.currentLanguage == com.example.Language.RU) "Разрешение требуется для отображения локальных фото и видео, чтобы вы могли удобно их сортировать." else "We need file permission to view local photos and videos so you can sort them smoothly."

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF)),
                border = BorderStroke(1.dp, Color(0xFFD0BCFF)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = Color(0xFF21005D),
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF21005D)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = bodyText,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF49454F),
                                lineHeight = 15.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onTriggerPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF21005D),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val buttonText = if (state.currentLanguage == com.example.Language.RO) "Permite accesul & Scanează tot" else if (state.currentLanguage == com.example.Language.RU) "Сканировать память телефона" else "Scan Device Storage"
                        Text(buttonText)
                    }
                }
            }
        }

        // 3. Main Tinder Deck Frame
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (state.isLoading) {
                CircularProgressIndicator()
            } else if (swipeList.isEmpty()) {
                // Out of Cards View
                NoMoreCardsLayout(state, viewModel, strings, onTriggerPermission)
            } else {
                // Interactive swipe stack
                val nextIndex = 1
                if (nextIndex < swipeList.size) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.92f)
                            .padding(top = 16.dp)
                            .scale(0.95f)
                            .alpha(0.6f)
                    ) {
                        CompactDeckCard(
                            item = swipeList[nextIndex]
                        )
                    }
                }

                // Main Draggable Card
                val currentCard = swipeList[0]
                InteractiveSwipeCard(
                    item = currentCard,
                    onSwipedLeft = {
                        viewModel.swipeLeft(currentCard)
                    },
                    onSwipedRight = {
                        viewModel.swipeRight(currentCard)
                    },
                    onSwipedUp = {
                        viewModel.performUndo()
                    },
                    strings = strings,
                    onPlayVideo = onPlayVideo,
                    swipeThreshold = state.swipeThreshold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Actions Quick Touchpad Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Undo button (Size 48.dp)
            IconButton(
                onClick = { viewModel.performUndo() },
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(1.dp, color = MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .testTag("action_btn_undo")
            ) {
                Icon(
                     imageVector = Icons.Default.Undo,
                     contentDescription = strings.swipeUpUndo,
                     tint = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            // Discard Swipe Left Button (Size 64.dp) - Container #FFDAD6, Text #410002
            IconButton(
                onClick = {
                    if (swipeList.isNotEmpty()) {
                        viewModel.swipeLeft(swipeList[0])
                    }
                },
                enabled = swipeList.isNotEmpty(),
                modifier = Modifier
                    .size(64.dp)
                    .background(if (swipeList.isNotEmpty()) Color(0xFFFFDAD6) else Color.LightGray.copy(alpha = 0.2f), CircleShape)
                    .shadow(elevation = if (swipeList.isNotEmpty()) 3.dp else 0.dp, shape = CircleShape)
                    .testTag("action_btn_discard")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = strings.swipeLeftAction,
                    tint = if (swipeList.isNotEmpty()) Color(0xFF410002) else Color.Gray,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            // Keep Swipe Right Button (Size 64.dp) - Container #DDE1FF, Text #001453
            IconButton(
                onClick = {
                    if (swipeList.isNotEmpty()) {
                        viewModel.swipeRight(swipeList[0])
                    }
                },
                enabled = swipeList.isNotEmpty(),
                modifier = Modifier
                    .size(64.dp)
                    .background(if (swipeList.isNotEmpty()) Color(0xFFDDE1FF) else Color.LightGray.copy(alpha = 0.2f), CircleShape)
                    .shadow(elevation = if (swipeList.isNotEmpty()) 3.dp else 0.dp, shape = CircleShape)
                    .testTag("action_btn_keep")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = strings.swipeRightAction,
                    tint = if (swipeList.isNotEmpty()) Color(0xFF001453) else Color.Gray,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            // Info button (Size 48.dp)
            IconButton(
                onClick = { showInfoDialog = true },
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(1.dp, color = MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .testTag("action_btn_info")
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "App Information",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Floating Analyzer Badge Capsule
        FloatingAnalysisBadge(
            scannedCount = state.mediaItems.size,
            isDemo = state.isDemoSandbox,
            strings = strings
        )
    }

    // Modal dialog
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.appTitle, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.infoStorageCheck, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = strings.statsTitle,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // Display statistics beautifully
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.statsSwiped + ":")
                        Text(state.mediaItems.size.toString(), fontWeight = FontWeight.Bold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.statsKept + ":")
                        Text(state.discardedItems.size.toString(), fontWeight = FontWeight.Bold, color = Color(0xFF005314))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.statsDiscarded + ":")
                        Text(state.keptItems.size.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun NoMoreCardsLayout(
    state: AppState,
    viewModel: AppViewModel,
    strings: TranslationPack,
    onTriggerPermission: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = strings.emptyGalleryTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = strings.emptyGalleryDesc,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(30.dp))

            // Stats Breakdown Widget
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        strings.statsTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.statsSwiped, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            state.mediaItems.size.toString(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.statsKept, color = MaterialTheme.colorScheme.tertiary)
                        Text(
                            state.discardedItems.size.toString(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.statsDiscarded, color = MaterialTheme.colorScheme.error)
                        Text(
                            state.keptItems.size.toString(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.checkMediaPermissionsAndLoad(context) },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (state.currentLanguage == com.example.Language.RO) "Reîncarcă" else "Reload")
                }

                if (state.isDemoSandbox && !state.permissionModelGranted) {
                    Button(
                        onClick = onTriggerPermission,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (state.currentLanguage == com.example.Language.RO) "Scanează tot" else "Scan All")
                    }
                }
            }
        }
    }
}

@Composable
fun InteractiveSwipeCard(
    item: MediaItem,
    onSwipedLeft: () -> Unit,
    onSwipedRight: () -> Unit,
    onSwipedUp: () -> Unit,
    strings: TranslationPack,
    onPlayVideo: (MediaItem) -> Unit,
    swipeThreshold: Float
) {
    var dragOffset by remember(item.id) { mutableStateOf(Offset.Zero) }
    val animatedOffset = remember(item.id) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val coroutineScope = rememberCoroutineScope()

    // Match real drag offset to animatable source
    LaunchedEffect(dragOffset) {
        animatedOffset.snapTo(dragOffset)
    }

    // Interactive Swipe Modifiers
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset {
                IntOffset(
                    animatedOffset.value.x.roundToInt(),
                    animatedOffset.value.y.roundToInt()
                )
            }
            .graphicsLayer {
                // Dynamic rotation mimicking Tinder real physics
                val maxRotation = 15f
                val screenWidthPx = 1000f
                val rotation = (animatedOffset.value.x / screenWidthPx) * maxRotation
                rotationZ = rotation.coerceIn(-maxRotation, maxRotation)
            }
            .pointerInput(item.id) {
                detectDragGestures(
                    onDragStart = {
                        dragOffset = animatedOffset.value
                    },
                    onDragEnd = {
                        val limitHorizontal = swipeThreshold
                        val limitVertical = -80f

                        when {
                            animatedOffset.value.x > limitHorizontal -> {
                                // Swipe Right -> Save
                                coroutineScope.launch {
                                    animatedOffset.animateTo(
                                        targetValue = Offset(1000f, animatedOffset.value.y),
                                        animationSpec = tween(150)
                                    )
                                    onSwipedRight()
                                    dragOffset = Offset.Zero
                                }
                            }
                            animatedOffset.value.x < -limitHorizontal -> {
                                // Swipe Left -> Discard
                                coroutineScope.launch {
                                    animatedOffset.animateTo(
                                        targetValue = Offset(-1000f, animatedOffset.value.y),
                                        animationSpec = tween(150)
                                    )
                                    onSwipedLeft()
                                    dragOffset = Offset.Zero
                                }
                            }
                            animatedOffset.value.y < limitVertical -> {
                                // Swipe Up -> Undo last
                                coroutineScope.launch {
                                    animatedOffset.animateTo(
                                        targetValue = Offset(animatedOffset.value.x, -1000f),
                                        animationSpec = tween(150)
                                    )
                                    onSwipedUp()
                                    dragOffset = Offset.Zero
                                }
                            }
                            else -> {
                                // Return back to standard grid coordinates
                                coroutineScope.launch {
                                    animatedOffset.animateTo(Offset.Zero, spring(dampingRatio = 0.65f, stiffness = 400f))
                                    dragOffset = Offset.Zero
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            animatedOffset.animateTo(Offset.Zero, spring(dampingRatio = 0.65f, stiffness = 400f))
                            dragOffset = Offset.Zero
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount * 1.8f
                    }
                )
            }
            .testTag("interactive_swipe_card")
    ) {
        // High fidelity inner card layout
        CardLayoutFrame(
            item = item,
            strings = strings,
            overlayOpacityRight = (animatedOffset.value.x / swipeThreshold).coerceIn(0f, 1f),
            overlayOpacityLeft = (-animatedOffset.value.x / swipeThreshold).coerceIn(0f, 1f),
            overlayOpacityUp = (-animatedOffset.value.y / 80f).coerceIn(0f, 1f),
            onPlayVideo = onPlayVideo
        )
    }
}

@Composable
fun rememberVideoThumbnail(uriString: String?, context: android.content.Context): android.graphics.Bitmap? {
    if (uriString == null) return null
    var bitmap by remember(uriString) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uriString) {
        withContext(Dispatchers.IO) {
            try {
                if (uriString.startsWith("http")) {
                    // It's a web url, allow Coil to handle it
                } else {
                    val uri = android.net.Uri.parse(uriString)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        bitmap = context.contentResolver.loadThumbnail(uri, android.util.Size(512, 512), null)
                    } else {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(context, uri)
                        val frame = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        bitmap = frame
                        retriever.release()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    return bitmap
}

@Composable
fun CardLayoutFrame(
    item: MediaItem,
    strings: TranslationPack,
    overlayOpacityRight: Float = 0f,
    overlayOpacityLeft: Float = 0f,
    overlayOpacityUp: Float = 0f,
    onPlayVideo: (MediaItem) -> Unit = {}
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .shadow(12.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // CARD MEDIA VISUALIZATION
            when {
                // 1. App shortcut layout
                item.type == MediaType.SHORTCUT -> {
                    AppShortcutCardPreview(item = item, strings = strings, context = context)
                }

                // 2. Real photo file
                item.type == MediaType.PHOTO && item.uriString != null -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(Uri.parse(item.uriString))
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                // 3. Real video file / Demo video file with thumbnail preview
                item.type == MediaType.VIDEO && item.uriString != null -> {
                    val thumbnail = rememberVideoThumbnail(item.uriString, context)
                    if (thumbnail != null) {
                        androidx.compose.foundation.Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(if (item.uriString.startsWith("http")) item.uriString else Uri.parse(item.uriString))
                                .crossfade(true)
                                .build(),
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    // Centered Video Badge overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color.White.copy(alpha = 0.85f), CircleShape)
                                .clickable { onPlayVideo(item) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play Video",
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                // 4. Default Demo/Sandbox dynamic falling backgrounds with abstract lines and icons
                else -> {
                    SandboxDemoCardPreview(item = item, onPlayVideo = onPlayVideo)
                }
            }

            // Top gradient protecting header labels
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)
                        )
                    )
            )

            // Header Meta-Label Group
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category Tag
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val label = when (item.type) {
                        MediaType.PHOTO -> strings.cardTypePhoto
                        MediaType.VIDEO -> strings.cardTypeVideo
                        MediaType.SHORTCUT -> strings.cardTypeShortcut
                    }
                    val icon = when (item.type) {
                        MediaType.PHOTO -> Icons.Default.Image
                        MediaType.VIDEO -> Icons.Default.Videocam
                        MediaType.SHORTCUT -> Icons.Default.OpenInNew
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // File Size Tag
                if (item.type != MediaType.SHORTCUT) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = item.sizeFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bottom metadata gradient backdrop
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(130.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
            )

            // Bottom description title
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.mockSubtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.mockSubtitle,
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                } else if (item.packName != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.packName,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // GESTURE ACTION INDICATORS OVERLAYS
            // 1. Keep (Right Swipe Green Overlay Indicator)
            if (overlayOpacityRight > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF00FF87).copy(alpha = overlayOpacityRight * 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .border(6.dp, Color(0xFF00FF87), RoundedCornerShape(12.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = strings.swipeRightAction.uppercase(),
                            color = Color(0xFF00FF87),
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp
                        )
                    }
                }
            }

            // 2. Discard (Left Swipe Red Overlay Indicator)
            if (overlayOpacityLeft > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFF416C).copy(alpha = overlayOpacityLeft * 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .border(6.dp, Color(0xFFFF416C), RoundedCornerShape(12.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = strings.swipeLeftAction.uppercase(),
                            color = Color(0xFFFF416C),
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp
                        )
                    }
                }
            }

            // 3. Undo / System Back (Swipe Up Violet Overlay Indicator)
            if (overlayOpacityUp > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFA07CFF).copy(alpha = overlayOpacityUp * 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .border(6.dp, Color(0xFFA07CFF), RoundedCornerShape(12.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "UNDO",
                            color = Color(0xFFA07CFF),
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppShortcutCardPreview(
    item: MediaItem,
    strings: TranslationPack,
    context: Context
) {
    val pm = context.packageManager
    var appIconDrawable by remember { mutableStateOf<android.graphics.drawable.Drawable?>(null) }

    LaunchedEffect(item.packName) {
        if (item.packName != null) {
            try {
                appIconDrawable = pm.getApplicationIcon(item.packName)
            } catch (e: Exception) {
                // Not found locally
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(item.mockColorStart), Color(item.mockColorEnd))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            if (appIconDrawable != null) {
                // Real installed application icon loaded dynamically
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(Color.White, CircleShape)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = appIconDrawable,
                        contentDescription = "App Icon",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = item.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = item.mockSubtitle,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (item.packName != null) {
                        try {
                            val intent = pm.getLaunchIntentForPackage(item.packName)
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(context, "Cannot launch app", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error opening app shortcut", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (strings.appTitle.contains("Media")) "Open App" else "Deschide",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SandboxDemoCardPreview(
    item: MediaItem,
    onPlayVideo: (MediaItem) -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(item.mockColorStart), Color(item.mockColorEnd))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Draw elegant modern lines in background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            // Diagonal line decoration
            drawLine(
                color = Color.White.copy(alpha = 0.05f),
                start = Offset(0f, 0f),
                end = Offset(canvasWidth, canvasHeight),
                strokeWidth = 10f
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.03f),
                radius = canvasWidth * 0.3f,
                center = Offset(canvasWidth * 0.8f, canvasHeight * 0.2f)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            if (item.type == MediaType.VIDEO) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.White.copy(alpha = 0.9f), CircleShape)
                        .clickable { onPlayVideo(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Demo Video",
                        tint = Color.Black,
                        modifier = Modifier.size(48.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = if (item.type == MediaType.PHOTO) Icons.Default.Wallpaper else Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(72.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = if (item.type == MediaType.VIDEO) "TAP PLAY TO WATCH VIDEO" else "SANDBOX FALLBACK PREVIEW",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CompactDeckCard(item: MediaItem) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Gray.copy(alpha = 0.6f))
        )
    }
}

@Composable
fun SelectionBasketScreen(
    state: AppState,
    viewModel: AppViewModel,
    context: Context,
    strings: TranslationPack,
    onDeleteItems: (List<com.example.MediaItem>) -> Unit,
    onPlayVideo: (com.example.MediaItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // Section Header
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.basketTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (state.keptItems.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.clearEntireBasket() },
                            modifier = Modifier.testTag("basket_clear_all_button")
                        ) {
                            Text(
                                strings.clearBasketBtn,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        val bulkDeleteLabel = when (state.currentLanguage) {
                            com.example.Language.RO -> "Șterge Tot"
                            com.example.Language.RU -> "Удалить всё"
                            else -> "Delete All"
                        }

                        Button(
                            onClick = {
                                onDeleteItems(state.keptItems)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("basket_delete_all_permanently_button"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = bulkDeleteLabel,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = bulkDeleteLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = strings.basketDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.keptItems.isEmpty()) {
            // Empty state view
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = strings.basketEmpty,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            // Grid of kept items
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.keptItems, key = { it.id }) { item ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .testTag("basket_item_${item.id}")
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Thumbnail Preview
                            when {
                                item.uriString != null -> {
                                    AsyncImage(
                                        model = Uri.parse(item.uriString),
                                        contentDescription = item.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.linearGradient(
                                                    colors = listOf(Color(item.mockColorStart), Color(item.mockColorEnd))
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (item.type == MediaType.SHORTCUT) Icons.Default.Launch else Icons.Default.Wallpaper,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.25f),
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }
                            }

                            // Dark scrim overlay for grid buttons
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f))
                            )

                            if (item.type == com.example.MediaType.VIDEO) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color.White.copy(alpha = 0.9f), CircleShape)
                                        .align(Alignment.Center)
                                        .clickable { onPlayVideo(item) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Video",
                                        tint = Color.Black,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            // Title overlay
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .fillMaxWidth(0.7f)
                            )

                            // Operations corner icons
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Restore / Deselect item back to primary deck
                                IconButton(
                                    onClick = {
                                        viewModel.restoreItemFromBasket(item)
                                        viewModel.triggerToast(strings.restoredToast)
                                    },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            Color.White.copy(alpha = 0.85f),
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Undo,
                                        contentDescription = "Restore",
                                        tint = Color.Black,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                // Delete Permanently Action
                                val isShortcut = item.type == com.example.MediaType.SHORTCUT
                                 val isLocked = isShortcut && item.isSystem
                                IconButton(
                                    onClick = {
                                        if (isShortcut) {
                                            val lockMsg = when (state.currentLanguage) {
                                                com.example.Language.RO -> "Protejat: Aplicațiile de sistem și scurtăturile nu pot fi șterse!"
                                                com.example.Language.RU -> "Защищено: Системные приложения и ярлыки защищены от удаления!"
                                                else -> "Protected: System apps and application shortcuts cannot be deleted!"
                                            }
                                            viewModel.triggerToast(lockMsg)
                                        } else {
                                            onDeleteItems(listOf(item))
                                        }
                                    },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            if (isShortcut) Color(0xFF625B71) else MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = if (isShortcut) Icons.Default.Lock else Icons.Default.DeleteForever,
                                        contentDescription = if (isShortcut) "Protected" else strings.deletePermanentlyConfirm,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: AppState,
    viewModel: AppViewModel,
    strings: TranslationPack
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = strings.selectTabSettings,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 1. Language switcher Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.incrementDebugClicks() }
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = strings.languageLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.example.Language.values().forEach { lang ->
                        val selected = state.currentLanguage == lang
                        OutlinedButton(
                            onClick = { viewModel.setLanguage(lang) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lang_btn_${lang.code}"),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Text(
                                lang.displayName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Theme Mode Selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ColorLens,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = strings.themeMode,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppTheme.values().forEach { themeOption ->
                        val selected = state.appTheme == themeOption
                        val themeLabel = when (themeOption) {
                            AppTheme.DARK -> strings.themeDark
                            AppTheme.LIGHT -> strings.themeLight
                            AppTheme.SYSTEM -> strings.themeSystem
                        }
                        val icon = when (themeOption) {
                            AppTheme.DARK -> Icons.Default.DarkMode
                            AppTheme.LIGHT -> Icons.Default.LightMode
                            AppTheme.SYSTEM -> Icons.Default.SettingsSuggest
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) else Color.Transparent
                                )
                                .clickable { viewModel.setTheme(themeOption) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = themeLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.setTheme(themeOption) },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.secondary)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Swipe Sensitivity Modifier Card
        Card(
            modifier = Modifier.fillMaxWidth().testTag("swipe_sensitivity_settings_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = strings.swipeSensitivityTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = strings.swipeSensitivityDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                val thresholdValue = state.swipeThreshold.toInt()
                val sensitivityLabel = when {
                    thresholdValue <= 75 -> strings.swipeSensitivityHigh
                    thresholdValue <= 140 -> strings.swipeSensitivityMedium
                    else -> strings.swipeSensitivityLow
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = strings.swipeSensitivityValue.replace("%d", thresholdValue.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Text(
                        text = sensitivityLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = state.swipeThreshold,
                    onValueChange = { newValue ->
                        viewModel.setSwipeThreshold(newValue)
                    },
                    valueRange = 40f..260f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                        thumbColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("sensitivity_slider")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        Triple(60f, "High", strings.swipeSensitivityHigh.substringBefore(" (")),
                        Triple(110f, "Medium", strings.swipeSensitivityMedium.substringBefore(" (")),
                        Triple(200f, "Low", strings.swipeSensitivityLow.substringBefore(" ("))
                    )

                    presets.forEach { (valFloat, nameKey, nameLabel) ->
                        val isSelected = abs(state.swipeThreshold - valFloat) < 15f
                        OutlinedButton(
                            onClick = { viewModel.setSwipeThreshold(valFloat) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sensitivity_preset_$nameKey"),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = BorderStroke(
                                1.5.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text(
                                text = nameLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        if (state.showDebugMode) {
            val context = LocalContext.current
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth().testTag("debug_test_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.incrementAdminClicks() }
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Debug Version 1.0.0",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Debug Version 1.0.0",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state.currentLanguage == com.example.Language.RO) 
                            "Aplicațiile de sistem nu pot fi dezinstalate! (Sistem protejat activ)"
                            else "System applications cannot be uninstalled! (Protected mode active)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { viewModel.deactivatePremium(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().testTag("deactivate_premium_btn")
                    ) {
                        Text(
                            text = if (state.currentLanguage == com.example.Language.RO) "Dezactivează Premium" else "Deactivate Premium",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    if (!state.showAdminMode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (state.currentLanguage == com.example.Language.RO)
                                "Apasă pe titlul debug de 10 ori pentru Mod Admin."
                                else "Click debug title 10 times for Admin Mode.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayerDialog(
    item: MediaItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // Native android VideoView player
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val mediaController = MediaController(ctx)
                            mediaController.setAnchorView(this)
                            setMediaController(mediaController)
                            
                            val videoUri = if (item.uriString != null) {
                                Uri.parse(item.uriString)
                            } else {
                                // Default fallback streaming mp4 URL for sandbox/mock demo videos
                                Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                            }
                            setVideoURI(videoUri)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                start()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center)
                )

                // Top Floating Control Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Player",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(44.dp)) // Visual balance
                }
            }
        }
    }
}

@Composable
fun PremiumGateScreen(
    state: AppState,
    viewModel: AppViewModel,
    strings: TranslationPack,
    context: Context
) {
    var cardInput by remember { mutableStateOf("") }
    var expiryInput by remember { mutableStateOf("") }
    var cvvInput by remember { mutableStateOf("") }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF130F26), // Cosmic Dark Indigo
                        Color(0xFF08070F)  // Pure deep black
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Language Switcher Bar for accessibility
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                com.example.Language.values().forEach { lang ->
                    val isSelected = state.currentLanguage == lang
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f))
                            .clickable { viewModel.setLanguage(lang) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = lang.code.uppercase(),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Crown Glow Ornament
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFFD700).copy(alpha = 0.28f),
                                Color.Transparent
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = "Premium Mode Locked",
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Header titles
            Text(
                text = strings.premiumTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = strings.premiumSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Premium Benefits List
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.04f)
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val benefitItems = listOf(
                        Pair(Icons.Default.Speed, strings.premiumBenefits.split("\n").getOrElse(0) { "✦ Full high-speed local processing" }),
                        Pair(Icons.Default.Tune, strings.premiumBenefits.split("\n").getOrElse(1) { "✦ Customizable swipe thresholds" }),
                        Pair(Icons.Default.Security, strings.premiumBenefits.split("\n").getOrElse(2) { "✦ Secure device content deletion" }),
                        Pair(Icons.Default.Stars, strings.premiumBenefits.split("\n").getOrElse(3) { "✦ Immersive Obsidian premium dark themes" })
                    )

                    benefitItems.forEach { (icon, desc) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.08f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD700),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = desc.replace("✦ ", ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Card Input Form Screen
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.05f)
                ),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.5.dp, if (state.premiumCardError) Color(0xFFFF5252) else Color(0xFFFFD700).copy(alpha = 0.7f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "SECURE PAYMENT GATEWAY",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD700),
                            fontSize = 10.sp
                        )
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Custom styled text entry field
                    OutlinedTextField(
                        value = cardInput,
                        onValueChange = {
                            val digits = it.filter { char -> char.isDigit() }.take(16)
                            val formatted = digits.chunked(4).joinToString(" ")
                            cardInput = formatted
                        },
                        placeholder = { 
                            Text(
                                text = strings.premiumCardPlaceholder, 
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.4f)
                            ) 
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.Black.copy(alpha = 0.3f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.2f),
                            focusedBorderColor = Color(0xFFFFD700),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            cursorColor = Color(0xFFFFD700)
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.CreditCard,
                                contentDescription = null,
                                tint = Color(0xFFFFD700)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("premium_card_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Expiry & CVV side-by-side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = expiryInput,
                            onValueChange = {
                                val clean = it.filter { char -> char.isDigit() }.take(4)
                                expiryInput = if (clean.length >= 3) {
                                    "${clean.substring(0, 2)}/${clean.substring(2)}"
                                } else {
                                    clean
                                }
                            },
                            placeholder = { 
                                Text(
                                    text = strings.premiumExpiryPlaceholder, 
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.4f),
                                    maxLines = 1
                                ) 
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.2f),
                                focusedBorderColor = Color(0xFFFFD700),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                cursorColor = Color(0xFFFFD700)
                            ),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.CalendarToday,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD700).copy(alpha = 0.8f),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("premium_expiry_input")
                        )

                        OutlinedTextField(
                            value = cvvInput,
                            onValueChange = {
                                cvvInput = it.filter { char -> char.isDigit() }.take(3)
                            },
                            placeholder = { 
                                Text(
                                    text = strings.premiumCvvPlaceholder, 
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.4f),
                                    maxLines = 1
                                ) 
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.2f),
                                focusedBorderColor = Color(0xFFFFD700),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                cursorColor = Color(0xFFFFD700)
                            ),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD700).copy(alpha = 0.8f),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("premium_cvv_input")
                        )
                    }

                    if (state.premiumCardError) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF521C1C), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.premiumCardError,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFCCCC),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Purchase/Unlock Button
                    Button(
                        onClick = {
                            viewModel.activatePremium(cardInput, cvvInput, expiryInput, context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFD700),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("activate_premium_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.premiumActivateBtn,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Encryption Confidence Note
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Encrypted Local Activation — Powered by Google AI Studio Build",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
fun AdminModeScreen(
    state: AppState,
    viewModel: AppViewModel,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, Color(0xFFFFD700))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Admin Mode Controls",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFFD700)
                )
                Text(
                    text = "Organizer Pro System Engine & Swipes Controller",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // System state statistics
        Text(
            text = "SYSTEM METRICS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Total items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = state.mediaItems.size.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Kept Swipes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = state.keptItems.size.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("User Premium Status", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (state.isPremium) "ACTIVE (OVERRIDE BY ADMIN)" else "INACTIVE",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isPremium) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(if (state.isPremium) Color(0xFFFFD700) else Color.Gray, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "ADMIN CONTROLS & OVERRIDES",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Actions
        Button(
            onClick = { viewModel.setAdminPremiumToggle(context) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700), contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("admin_premium_toggle")
        ) {
            Icon(Icons.Default.Star, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.isPremium) "FORCE DEACTIVATE PREMIUM" else "FORCE ACTIVATE PREMIUM",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = { viewModel.injectMassiveSandboxData() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("admin_inject_extra")
        ) {
            Icon(Icons.Default.AddCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("INJECT MORE TESTING SHORTCUTS", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = { viewModel.resetAdminHistory() },
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("admin_reset_history")
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("RESET ALL SWIPED HISTORY", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Footer disclaimer
        Text(
            text = "Internal testing tools v1.0.0. Powered by Dumitru Boldurescu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        )
    }
}
