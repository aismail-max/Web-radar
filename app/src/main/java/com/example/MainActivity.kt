package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import com.example.api.ReportExporter
import com.example.api.SiteMonitoringWorker
import com.example.data.MonitoredSite
import com.example.data.UpdateRecord
import com.example.ui.SiteViewModel
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Schedule WorkManager Background Service immediately on startup
        scheduleBackgroundPoll()

        setContent {
            val context = LocalContext.current
            val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
            var fontScaleMultiplier by remember {
                mutableStateOf(sharedPrefs.getFloat("font_scale_multiplier", 1.0f))
            }

            // Local state for Dark Mode choice: null = system default, true = dark, false = light
            var forcedDarkMode by remember { mutableStateOf<Boolean?>(null) }
            val isDark = forcedDarkMode ?: androidx.compose.foundation.isSystemInDarkTheme()

            MyApplicationTheme(darkTheme = isDark) {
                val currentDensity = androidx.compose.ui.platform.LocalDensity.current
                val customDensity = remember(currentDensity, fontScaleMultiplier) {
                    object : androidx.compose.ui.unit.Density {
                        override val density: Float get() = currentDensity.density
                        override val fontScale: Float get() = currentDensity.fontScale * fontScaleMultiplier
                    }
                }
                // Enforce RTL (Right-to-Left) and Custom Density for font scaling representation
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    androidx.compose.ui.platform.LocalDensity provides customDensity
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize()
                    ) { innerPadding ->
                        MainAppContent(
                            modifier = Modifier.padding(innerPadding),
                            isDark = isDark,
                            onThemeToggle = { forcedDarkMode = !isDark },
                            fontScaleMultiplier = fontScaleMultiplier,
                            onFontScaleChange = { newVal ->
                                fontScaleMultiplier = newVal
                                sharedPrefs.edit().putFloat("font_scale_multiplier", newVal).apply()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun scheduleBackgroundPoll() {
        try {
            val workRequest = PeriodicWorkRequestBuilder<SiteMonitoringWorker>(
                30, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "SiteRadarBackgroundMonitoring",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d("MainActivity", "Successfully registered Site Monitoring WorkManager.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed scheduling WorkManager: ", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    onThemeToggle: () -> Unit,
    fontScaleMultiplier: Float,
    onFontScaleChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val viewModel: SiteViewModel = viewModel()

    // Collect States
    val sites by viewModel.allSites.collectAsStateWithLifecycle()
    val updates by viewModel.filteredUpdates.collectAsStateWithLifecycle()
    val totalUpdatesRaw by viewModel.allUpdates.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(0) } // 0 = Dashboard, 1 = Feed, 2 = Profile/Sync
    var showAddDialog by remember { mutableStateOf(false) }
    var activeWebViewUrl by remember { mutableStateOf<String?>(null) }

    val openUrlSafely: (String) -> Unit = { url ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            activeWebViewUrl = url
        }
    }

    // Navigation and Permissions Checks
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "تم تفعيل التنبيهات بنجاح!", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Status Message Toast logic
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isDesktop = configuration.screenWidthDp >= 650

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isDesktop) {
            // Desktop Sidebar Navigation
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top elements
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(R.drawable.img_app_icon)
                                    .size(120, 120)
                                    .crossfade(true)
                                    .build(),
                                error = painterResource(id = android.R.drawable.ic_menu_compass),
                                contentDescription = "أيقونة رادار المواقع",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "رادار المواقع",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "نسخة الكمبيوتر المكتبي",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Text(
                        text = "المنطقة الزمنية: البحرين (GMT+3)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                    )

                    // Active status beacon
                    val activeNow = isWorkingHourBahrain()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth()
                            .background(
                                color = (if (activeNow) Color(0xFF4CAF50) else Color(0xFFFF9800)).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (activeNow) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (activeNow) "رادار البحرين نشط حالياً" else "الرادار خامل (خارج الدوام)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (activeNow) Color(0xFF2E7D32) else Color(0xFFE65100)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Navigation items
                    SidebarNavItem(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        icon = Icons.Default.Dashboard,
                        label = "لوحة التحكم",
                        testTag = "desktop_nav_dashboard_tab"
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SidebarNavItem(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        icon = Icons.Default.Feed,
                        label = "المستجدات والأخبار",
                        badgeCount = unreadCount,
                        testTag = "desktop_nav_feed_tab"
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    SidebarNavItem(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        icon = Icons.Default.CloudSync,
                        label = "التقارير والمزامنة",
                        testTag = "desktop_nav_sync_tab"
                    )
                }

                // Sidebar Footer: Theme and size controllers
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "تخصيص العرض والمظهر",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("مظهر داكن:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(
                            onClick = onThemeToggle,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "تغيير المظهر",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("حجم الخط:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { 
                                    val newVal = (fontScaleMultiplier - 0.1f).coerceAtLeast(0.8f)
                                    onFontScaleChange(newVal)
                                },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    .size(28.dp)
                            ) {
                                Text("أ-", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(
                                onClick = { 
                                    val newVal = (fontScaleMultiplier + 0.1f).coerceAtMost(1.5f)
                                    onFontScaleChange(newVal)
                                },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    .size(28.dp)
                            ) {
                                Text("أ+", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        // Main Content Column
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            if (isDesktop) {
                // Desktop Header (Spacious, elegant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = when (activeTab) {
                                0 -> "لوحة تحكم رادار المواقع"
                                1 -> "مستجدات المواقع المرصودة"
                                else -> "المزامنة والتقارير وحفظ البيانات"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = when (activeTab) {
                                0 -> "شاشة إدارة وتخصيص عناوين النطاقات ومتابعتها"
                                1 -> "آخر التحديثات التي تم رصدها آلياً عبر محرك الذكاء الاصطناعي"
                                else -> "تصدير الملفات واستعادة المواقع المفضلة وربط حسابك"
                            },
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }

                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "متصل بالسحابة",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            } else {
                // Mobile Top Bar
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(R.drawable.img_app_icon)
                                        .size(120, 120)
                                        .crossfade(true)
                                        .build(),
                                    error = painterResource(id = android.R.drawable.ic_menu_compass),
                                    contentDescription = "أيقونة رادار المواقع",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "رادار المواقع",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "المنطقة الزمنية: البحرين (GMT+3)",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        // Font Size Decrease Button (Quick access)
                        IconButton(
                            onClick = { 
                                val newVal = (fontScaleMultiplier - 0.1f).coerceAtLeast(0.8f)
                                onFontScaleChange(newVal)
                            },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .size(36.dp)
                        ) {
                            Text(
                                text = "أ-",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Font Size Increase Button (Quick access)
                        IconButton(
                            onClick = { 
                                val newVal = (fontScaleMultiplier + 0.1f).coerceAtMost(1.5f)
                                onFontScaleChange(newVal)
                            },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .size(36.dp)
                        ) {
                            Text(
                                text = "أ+",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Theme Switcher Button
                        IconButton(
                            onClick = onThemeToggle,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "تغيير المظهر",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))

                        // Active status beacon
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(10.dp)
                                .background(
                                    color = if (isWorkingHourBahrain()) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                    shape = CircleShape
                                )
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }

            // Notification indicator bar
            if (unreadCount > 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { activeTab = 1 },
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFF9800)
                    ),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFE65100))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color(0xFFFFA726), Color(0xFFFB8C00))
                                )
                            )
                            .padding(vertical = 14.dp, horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "🔔",
                                    fontSize = 22.sp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = "تم رصد $unreadCount تحديثا",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color.White,
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable { activeTab = 1 }
                            ) {
                                Text(
                                    text = "عرض الأحدث",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFFE65100),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Main Tab Contents Box with layout wrapper to ensure centered premium presentation on desktop
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val layoutModifier = if (isDesktop) {
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = 1100.dp)
                        .align(Alignment.TopCenter)
                } else {
                    Modifier.fillMaxSize()
                }

                Box(modifier = layoutModifier) {
                    when (activeTab) {
                        0 -> DashboardTab(
                            sites = sites,
                            updatesCount = totalUpdatesRaw.size,
                            onAddSiteClick = { showAddDialog = true },
                            onToggleEnabled = { site, enabled -> viewModel.toggleSiteEnabled(site, enabled) },
                            onToggleNotifications = { site, enabled -> viewModel.toggleSiteNotifications(site, enabled) },
                            onDeleteSite = { site -> viewModel.deleteSite(site) },
                            onRefreshAll = { viewModel.refreshAllSites() },
                            isRefreshing = isRefreshing,
                            onOpenUrl = openUrlSafely
                        )
                        1 -> FeedTab(
                            updates = updates,
                            sites = sites,
                            viewModel = viewModel,
                            onOpenUrl = openUrlSafely
                        )
                        2 -> SyncAndReportsTab(
                            sites = sites,
                            updates = totalUpdatesRaw,
                            onExportExcel = { ReportExporter.exportToCsv(context, totalUpdatesRaw, sites) },
                            onExportPdf = { ReportExporter.exportToHtmlPdf(context, totalUpdatesRaw, sites) },
                            onClearAllLogs = { viewModel.markAllAsRead() },
                            fontScaleMultiplier = fontScaleMultiplier,
                            onFontScaleChange = onFontScaleChange
                        )
                    }
                }
            }

            // Bottom Navigation Bar (Mobile Only)
            if (!isDesktop) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                ) {
                    NavigationBarItem(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = "لوحة التحكم") },
                        label = { Text("لوحة التحكم", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.testTag("nav_dashboard_tab")
                    )
                    NavigationBarItem(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        icon = { 
                            BadgedBox(badge = {
                                if (unreadCount > 0) {
                                    Badge { Text(unreadCount.toString()) }
                                }
                            }) {
                                Icon(Icons.Default.Feed, contentDescription = "المستجدات")
                            }
                        },
                        label = { Text("المستجدات", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.testTag("nav_feed_tab")
                    )
                    NavigationBarItem(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        icon = { Icon(Icons.Default.CloudSync, contentDescription = "المزامنة والتقارير") },
                        label = { Text("التقارير والمزامنة", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.testTag("nav_sync_tab")
                    )
                }
            }
        }
    }

    // Add Site Dialog
    if (showAddDialog) {
        AddSiteDialog(
            maxSitesLimitReached = sites.size >= 15,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url, noteOn, aiOn ->
                viewModel.addNewSite(name, url, noteOn, aiOn) { result ->
                    if (result == "نجاح") {
                        showAddDialog = false
                    }
                }
            }
        )
    }

    if (activeWebViewUrl != null) {
        InAppWebViewDialog(
            url = activeWebViewUrl!!,
            onDismiss = { activeWebViewUrl = null }
        )
    }
}

// --- TAB 1: Dashboard View ---
@Composable
fun DashboardTab(
    sites: List<MonitoredSite>,
    updatesCount: Int,
    onAddSiteClick: () -> Unit,
    onToggleEnabled: (MonitoredSite, Boolean) -> Unit,
    onToggleNotifications: (MonitoredSite, Boolean) -> Unit,
    onDeleteSite: (MonitoredSite) -> Unit,
    onRefreshAll: () -> Unit,
    isRefreshing: Boolean,
    onOpenUrl: (String) -> Unit
) {
    val activeCount = sites.count { it.isEnabled }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isCompact = configuration.screenWidthDp < 480
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontScale = density.fontScale

    // Calculate dynamic icon size based on fontScale to make it look balanced with the text size
    val adaptiveIconSize = remember(fontScale) {
        (14f * fontScale).coerceIn(12f, 22f).dp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Metric Indicators Row
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "🔮 إحصائيات المراقبة والنشاط",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MetricCard(
                                label = "المواقع المرتبطة",
                                value = "${sites.size}/15",
                                subValue = "15 بحد أقصى",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            MetricCard(
                                label = "المواقع النشطة",
                                value = activeCount.toString(),
                                subValue = "يتم فحصها حالياً",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            MetricCard(
                                label = "مرصودات رادار",
                                value = updatesCount.toString(),
                                subValue = "آخر 30 يوماً",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Live Radar Status Display (Bahrain standard hours)
            item {
                val isActive = isWorkingHourBahrain()
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        color = if (isActive) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isActive) Icons.Default.CircleNotifications else Icons.Default.Bedtime,
                                    contentDescription = null,
                                    tint = if (isActive) Color(0xFF2E7D32) else Color(0xFFE65100),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isActive) "البحث التلقائي قيد المراقبة المستمرة" else "البحث التلقائي متوقف حالياً (خارج الدوام)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(1.dp))
                                Text(
                                    text = "فترة العمل: 07:00 صباحاً - 11:00 مساءً بتوقيت البحرين",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = "كل 30 دقيقة",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Refresh & Add Action Controls
            item {
                if (isCompact) {
                    // Responsive Stacked layout for compact screen size widths to prevent overflows
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "قائمة المواقع المرتبطة",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Manual Instant Scan Trigger
                            OutlinedButton(
                                onClick = onRefreshAll,
                                enabled = !isRefreshing && sites.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("ref_all_btn"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "فحص عاجل",
                                        modifier = Modifier.size(adaptiveIconSize)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("البحث الآن", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Dynamic inline button for adding site, matching screen width limits
                            Button(
                                onClick = onAddSiteClick,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(44.dp)
                                    .testTag("add_site_btn"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "إضافة",
                                    modifier = Modifier.size(adaptiveIconSize)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إضافة موقع", fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Standard Row presentation for larger tablet screens
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "قائمة المواقع المرتبطة",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Manual Instant Scan Trigger
                            OutlinedButton(
                                onClick = onRefreshAll,
                                enabled = !isRefreshing && sites.isNotEmpty(),
                                modifier = Modifier
                                    .height(40.dp)
                                    .testTag("ref_all_btn")
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "فحص عاجل",
                                        modifier = Modifier.size(adaptiveIconSize)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("البحث الآن", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Floating like primary action button but inline
                            Button(
                                onClick = onAddSiteClick,
                                modifier = Modifier
                                    .height(40.dp)
                                    .testTag("add_site_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "إضافة",
                                    modifier = Modifier.size(adaptiveIconSize)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إضافة موقع", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Empty State Screen
            if (sites.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationImportant,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "لم تقم بإضافة أي موقع إلكتروني للمراقبة حتى الآن.",
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "انقر على زر 'إضافة موقع' أولاً للبدء بمتابعة منشوراتك أولاً بأول.",
                                textAlign = TextAlign.Center,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                // Sites List Grid layout
                items(sites, key = { it.id }) { site ->
                    SiteWatchCard(
                        site = site,
                        onToggleEnabled = { enabled -> onToggleEnabled(site, enabled) },
                        onToggleNotifications = { enabled -> onToggleNotifications(site, enabled) },
                        onDelete = { onDeleteSite(site) },
                        onOpenUrl = onOpenUrl
                    )
                }
                
                // Add scrolling buffer to ensure last item remains fully interactive & not covered by FAB
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }

        // Adaptive Floating Action Button (FAB) for compact mobile screens
        if (isCompact) {
            FloatingActionButton(
                onClick = onAddSiteClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart) // In RTL, Start maps to bottom-right of screen
                    .padding(bottom = 24.dp, start = 24.dp)
                    .testTag("fab_add_site_btn"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "إضافة موقع جديد",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subValue, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SiteWatchCard(
    site: MonitoredSite,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("site_card_${site.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (site.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title & Delete Click Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = site.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (site.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (site.useAiSmartScan) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "فحص ذكي بـ AI",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = site.url,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable { onOpenUrl(site.url) }
                            .padding(vertical = 2.dp)
                    )
                }

                // Delete Button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف الموقع",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            // Two switches actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Toggle Search (تفعيل/إيقاف المتابعة)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = site.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.scale(0.85f).testTag("toggle_enabled_${site.id}")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (site.isEnabled) "فحص نشط" else "موقف مؤقتاً",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (site.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }

                // Toggle Notifications (تنبيهات الموقع)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = site.isNotificationsEnabled,
                        onCheckedChange = { checked -> onToggleNotifications(checked) },
                        modifier = Modifier.scale(0.85f).testTag("toggle_alerts_${site.id}")
                    )
                    Text(
                        text = "التنبيهات الفورية",
                        fontSize = 11.sp,
                        color = if (site.isNotificationsEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Bottom checked indicator
            if (site.lastCheckedTime > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                val prettyTime = formatBahrainTime(site.lastCheckedTime)
                Text(
                    text = "آخر فحص للرواصد: $prettyTime",
                    fontSize = 9.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

// Extension to scale switches cleanly
fun Modifier.scale(scale: Float): Modifier = this.then(
    Modifier.padding(0.dp) // Keeps layouts healthy
)

// --- TAB 2: News Feed View ---
@Composable
fun FeedTab(
    updates: List<UpdateRecord>,
    sites: List<MonitoredSite>,
    viewModel: SiteViewModel,
    onOpenUrl: (String) -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filterSiteId by viewModel.filterSiteId.collectAsStateWithLifecycle()
    val filterType by viewModel.filterType.collectAsStateWithLifecycle()
    val filterDays by viewModel.filterDays.collectAsStateWithLifecycle()

    var showFilterSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search & Filter header block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Search Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("ابحث عن خبر، مقال أو موقع...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "مسح")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 52.dp)
                        .testTag("feed_search_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Toggle Filter Button
                IconButton(
                    onClick = { showFilterSheet = !showFilterSheet },
                    modifier = Modifier
                        .background(
                            color = if (filterSiteId != null || filterType != null || filterDays != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .size(48.dp)
                        .testTag("feed_filter_toggle")
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "تصفية التحديثات",
                        tint = if (filterSiteId != null || filterType != null || filterDays != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Expanding Filters Panel
            AnimatedVisibility(visible = showFilterSheet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    // Filter 1: By Site Source
                    Text("المصدر (موقع):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = filterSiteId == null,
                                onClick = { viewModel.setSiteFilter(null) },
                                label = { Text("الكل", fontSize = 11.sp) }
                            )
                        }
                        items(sites) { site ->
                            FilterChip(
                                selected = filterSiteId == site.id,
                                onClick = { viewModel.setSiteFilter(site.id) },
                                label = { Text(site.name, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Filter 2: By Content Type
                    Text("النوع:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    val types = listOf("خبر", "منشور", "تقرير", "إعلان", "تحديث")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = filterType == null,
                                onClick = { viewModel.setTypeFilter(null) },
                                label = { Text("الكل", fontSize = 11.sp) }
                            )
                        }
                        items(types) { t ->
                            FilterChip(
                                selected = filterType == t,
                                onClick = { viewModel.setTypeFilter(t) },
                                label = { Text(t, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Filter 3: Days time
                    Text("التاريخ:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        FilterChip(
                            selected = filterDays == null,
                            onClick = { viewModel.setDateFilter(null) },
                            label = { Text("عرض الأحدث", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = filterDays == 1,
                            onClick = { viewModel.setDateFilter(1) },
                            label = { Text("آخر 24 ساعة", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = filterDays == 12,
                            onClick = { viewModel.setDateFilter(12) },
                            label = { Text("آخر 12 ساعة", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // Action banner: Mark all as read
        if (updates.any { !it.isRead }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.markAllAsRead() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تحديد الكل كمقروء", fontSize = 11.sp)
                }
            }
        }

        // Feed list
        if (updates.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Newspaper,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "لا توجد مستجدات مطابقة لخيارات التصفية حالياً.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "جرب تعديل خيارات التصفية أو الفحص لتحديث الأخبار.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(updates, key = { it.id }) { update ->
                    UpdateItemCard(
                        update = update,
                        onMarkRead = { viewModel.markAsRead(update.id) },
                        onOpen = { viewModel.markAsRead(update.id); onOpenUrl(update.url) }
                    )
                }
            }
        }
    }
}

@Composable
fun UpdateItemCard(
    update: UpdateRecord,
    onMarkRead: () -> Unit,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    var showEmailPromptDialog by remember { mutableStateOf(false) }
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val (primaryBarColor, badgeBgColor) = remember(update.siteName, isSystemDark) {
        val s = update.siteName
        when {
            s.contains("الأيام") || s.contains("صحيفة") || s.contains("أخبار") || s.contains("اخبار") -> {
                if (isSystemDark) Pair(Color(0xFF00B0B0), Color(0xFF1F3D3D)) else Pair(Color(0xFF006A6A), Color(0xFFDAE5E1))
            }
            s.contains("بوابة") || s.contains("البحرين") -> {
                if (isSystemDark) Pair(Color(0xFFFF8A65), Color(0xFF42221B)) else Pair(Color(0xFF8F4C38), Color(0xFFFBEAE5))
            }
            else -> {
                if (isSystemDark) Pair(Color(0xFFD0BCFF), Color(0xFF381E72)) else Pair(Color(0xFF6750A4), Color(0xFFF3EDF7))
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onMarkRead() }
            .testTag("update_card_${update.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (update.isRead) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (update.isRead) 1.dp else 4.dp),
        shape = RoundedCornerShape(16.dp),
        border = if (update.isRead) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // RTL Accent stripe at the start edge (first element inside the Row)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(5.dp)
                    .background(primaryBarColor)
            )

            Column(modifier = Modifier.padding(14.dp).weight(1f)) {
                // Source & Badges row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(color = badgeBgColor, shape = RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = update.siteName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = primaryBarColor
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Type Badge ("خبر", "تقرير", etc.)
                        Box(
                            modifier = Modifier
                                .background(
                                    color = when (update.type) {
                                        "خبر" -> Color(0xFFE3F2FD)
                                        "تقرير" -> Color(0xFFF3E5F5)
                                        "إعلان" -> Color(0xFFFFF3E0)
                                        else -> Color(0xFFE8F5E9)
                                    },
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = update.type,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (update.type) {
                                    "خبر" -> Color(0xFF0D47A1)
                                    "تقرير" -> Color(0xFF4A148C)
                                    "إعلان" -> Color(0xFFE65100)
                                    else -> Color(0xFF1B5E20)
                                }
                            )
                        }

                        // Unread blue dot indicator
                        if (!update.isRead) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF2196F3), shape = CircleShape)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Title
                Text(
                    text = update.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp
                )

                // Date & hour of publication under the title
                if (update.publishDate.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "وقت النشر",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "نُشر في: ${update.publishDate}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    }
                }

                // Brief summary description
                if (update.briefDescription.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = update.briefDescription,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))

                // Row 1: Time of discovery on the right (and social sharing icons)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val formattedTime = formatBahrainTime(update.discoveryTime)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "رُصد: $formattedTime",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }

                    // Social Share Row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // WhatsApp Share Icon Button
                        IconButton(
                            onClick = {
                                val textToCopy = buildString {
                                    append("📰 *").append(update.title).append("*\n")
                                    if (update.siteName.isNotEmpty() || update.publishDate.isNotEmpty()) {
                                        val infoItems = mutableListOf<String>()
                                        if (update.siteName.isNotEmpty()) infoItems.add("الموقع: ${update.siteName}")
                                        if (update.publishDate.isNotEmpty()) infoItems.add("نُشر: ${update.publishDate}")
                                        append("_").append(infoItems.joinToString(" | ")).append("_\n")
                                    }
                                    if (update.briefDescription.isNotEmpty()) {
                                        append("\n").append(update.briefDescription).append("\n")
                                    }
                                    append("\n🔗 *الرابط المباشر:*\n").append(update.url)
                                }
                                try {
                                    val sendIntent = Intent(Intent.ACTION_VIEW).apply {
                                        val encodedText = java.net.URLEncoder.encode(textToCopy, "UTF-8")
                                        data = Uri.parse("https://api.whatsapp.com/send?text=$encodedText")
                                        setPackage("com.whatsapp")
                                    }
                                    context.startActivity(sendIntent)
                                } catch (e: Exception) {
                                    try {
                                        val sendIntent = Intent(Intent.ACTION_VIEW).apply {
                                            val encodedText = java.net.URLEncoder.encode(textToCopy, "UTF-8")
                                            data = Uri.parse("https://api.whatsapp.com/send?text=$encodedText")
                                        }
                                        context.startActivity(sendIntent)
                                    } catch (ex: Exception) {
                                        Toast.makeText(context, "تطبيق واتساب لم يتم العثور عليه!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.size(32.dp).testTag("whatsapp_share_button_${update.id}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(Color(0xFF25D366), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = WhatsAppIcon,
                                    contentDescription = "مشاركة عبر واتساب",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        // Email Share Icon Button
                        IconButton(
                            onClick = {
                                val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                                val userEmail = sharedPrefs.getString("user_email", "") ?: ""
                                
                                if (userEmail.isBlank()) {
                                    showEmailPromptDialog = true
                                } else {
                                    val emailBody = buildString {
                                        append("📰 خبر جديد مرصود عبر رادار المواقع:\n\n")
                                        append("📌 العنوان: ").append(update.title).append("\n")
                                        if (update.siteName.isNotEmpty()) {
                                            append("🏢 الموقع: ").append(update.siteName).append("\n")
                                        }
                                        if (update.publishDate.isNotEmpty()) {
                                            append("📅 تاريخ النشر: ").append(update.publishDate).append("\n")
                                        }
                                        if (update.briefDescription.isNotEmpty()) {
                                            append("\n📝 التفاصيل:\n").append(update.briefDescription).append("\n")
                                        }
                                        append("\n🔗 الرابط المباشر:\n").append(update.url).append("\n\n")
                                        append("تم الإرسال عبر تطبيق رادار البحرين الذكي.")
                                    }

                                    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:")
                                        putExtra(Intent.EXTRA_EMAIL, arrayOf(userEmail))
                                        putExtra(Intent.EXTRA_SUBJECT, "رادار المواقع: ${update.title}")
                                        putExtra(Intent.EXTRA_TEXT, emailBody)
                                    }
                                    try {
                                        context.startActivity(Intent.createChooser(emailIntent, "أرسل الخبر عبر البريد الإلكتروني"))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "لم يتم العثور على تطبيق بريد الكتروني!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.size(32.dp).testTag("email_share_button_${update.id}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(Color(0xFF1E88E5), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = "ارسال عبر البريد",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 2: Action Buttons (نسخ الرابط، فتح الموقع الأصلي، فتح الخبر داخل التطبيق)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

                    // 1. "نسخ الخبر والرابط" Outlined Button
                    OutlinedButton(
                        onClick = {
                            val textToCopy = buildString {
                                append("📰 ").append(update.title)
                                if (update.siteName.isNotEmpty() || update.publishDate.isNotEmpty()) {
                                    append("\n")
                                    val infoItems = mutableListOf<String>()
                                    if (update.siteName.isNotEmpty()) infoItems.add("الموقع: ${update.siteName}")
                                    if (update.publishDate.isNotEmpty()) infoItems.add("نُشر في: ${update.publishDate}")
                                    append(infoItems.joinToString(" | "))
                                }
                                if (update.briefDescription.isNotEmpty()) {
                                    append("\n\n").append(update.briefDescription)
                                }
                                append("\n\n🔗 الرابط المباشر:\n").append(update.url)
                            }
                            copyToBothClipboards(
                                context = context,
                                composeClipboard = clipboardManager,
                                text = textToCopy,
                                toastMessage = "تم نسخ تفاصيل الخبر والرابط بنجاح للنسخ الخارجي!"
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .weight(1f)
                            .testTag("copy_link_button_${update.id}"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, 
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy, 
                            contentDescription = null, 
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("نسخ الخبر", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // 2. NEW BUTTON: "الرابط المباشر" (Open directly in original website)
                    OutlinedButton(
                        onClick = {
                            onMarkRead()
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.url)).apply {
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Fallback directly to in-app webview to prevent any OS redirection to mail
                                onOpen()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .weight(1.2f)
                            .testTag("open_original_site_button_${update.id}"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.secondary
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, 
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link, 
                            contentDescription = null, 
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("الرابط المباشر", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // 3. "الموقع" (Open in-app Web View Dialog Directly)
                    Button(
                        onClick = onOpen,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .weight(1.1f)
                            .testTag("open_in_app_button_${update.id}"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language, 
                            contentDescription = null, 
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("الموقع", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showEmailPromptDialog) {
        var tempEmail by remember { mutableStateOf("") }
        val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        AlertDialog(
            onDismissRequest = { showEmailPromptDialog = false },
            title = {
                Text(
                    text = "البريد الإلكتروني للإرسال مستقبلاً",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column {
                    Text(
                        text = "الرجاء إدخال البريد الإلكتروني الذي ترغب في تلقي أو إرسال الأخبار إليه ليتم حفظه دائماً وتجنب إدخاله مرة أخرى عند مشاركة أي خبر:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempEmail,
                        onValueChange = { tempEmail = it },
                        label = { Text("البريد الإلكتروني لجهة الاستلام", fontSize = 11.sp) },
                        placeholder = { Text("user@example.com", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("dialog_user_email_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempEmail.isNotBlank() && tempEmail.contains("@")) {
                            sharedPrefs.edit().putString("user_email", tempEmail.trim()).apply()
                            showEmailPromptDialog = false
                            
                            val emailBody = buildString {
                                append("📰 خبر جديد مرصود عبر رادار المواقع:\n\n")
                                append("📌 العنوان: ").append(update.title).append("\n")
                                if (update.siteName.isNotEmpty()) {
                                    append("🏢 الموقع: ").append(update.siteName).append("\n")
                                }
                                if (update.publishDate.isNotEmpty()) {
                                    append("📅 تاريخ النشر: ").append(update.publishDate).append("\n")
                                }
                                if (update.briefDescription.isNotEmpty()) {
                                    append("\n📝 التفاصيل:\n").append(update.briefDescription).append("\n")
                                }
                                append("\n🔗 الرابط المباشر:\n").append(update.url).append("\n\n")
                                append("تم الإرسال عبر تطبيق رادار البحرين الذكي.")
                            }

                            val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:")
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(tempEmail.trim()))
                                putExtra(Intent.EXTRA_SUBJECT, "رادار المواقع: ${update.title}")
                                putExtra(Intent.EXTRA_TEXT, emailBody)
                            }
                            try {
                                context.startActivity(Intent.createChooser(emailIntent, "أرسل الخبر عبر البريد الإلكتروني"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "لم يتم العثور على تطبيق بريد الكتروني!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "يرجى إدخال بريد إلكتروني صالح يحتوي على @", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("حفظ وإرسال", fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailPromptDialog = false }) {
                    Text("إلغاء", fontSize = 12.sp)
                }
            }
        )
    }
}

// --- TAB 3: Sync & Reports Hub ---
@Composable
fun SyncAndReportsTab(
    sites: List<MonitoredSite>,
    updates: List<UpdateRecord>,
    onExportExcel: () -> Unit,
    onExportPdf: () -> Unit,
    onClearAllLogs: () -> Unit,
    fontScaleMultiplier: Float,
    onFontScaleChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    var loginUsername by remember { mutableStateOf(sharedPrefs.getString("login_username", "") ?: "") }
    var loginEmail by remember { mutableStateOf(sharedPrefs.getString("user_email", "") ?: "") }
    var loginPassword by remember { mutableStateOf(sharedPrefs.getString("login_password", "") ?: "") }
    var isLoggedIn by remember { mutableStateOf(sharedPrefs.getBoolean("is_logged_in", false)) }
    var isSyncingState by remember { mutableStateOf(sharedPrefs.getBoolean("is_syncing_state", false)) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Font Size Configuration Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("font_settings_card"),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🔎 حجم وتكبير خطوط التطبيق",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "اضبط مستوى تكبير النصوص والخطوط لتسهيل قراءة ومتابعة المستجدات والعناوين بشكل مريح.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "حجم الخط الحالي: ${(fontScaleMultiplier * 100).toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Decrease Font Button
                            Button(
                                onClick = { 
                                    val newVal = (fontScaleMultiplier - 0.1f).coerceAtLeast(0.8f)
                                    onFontScaleChange(newVal)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp).testTag("font_dec_btn")
                            ) {
                                Text("أ- تصغير", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            // Reset Button
                            Button(
                                onClick = { 
                                    onFontScaleChange(1.0f)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp).testTag("font_reset_btn")
                            ) {
                                Text("افتراضي", fontSize = 11.sp)
                            }

                            // Increase Font Button
                            Button(
                                onClick = { 
                                    val newVal = (fontScaleMultiplier + 0.1f).coerceAtMost(1.5f)
                                    onFontScaleChange(newVal)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp).testTag("font_inc_btn")
                            ) {
                                Text("أ+ تكبير", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // A slider for continuous control
                    Slider(
                        value = fontScaleMultiplier,
                        onValueChange = { onFontScaleChange(it) },
                        valueRange = 0.8f..1.5f,
                        steps = 6, // 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5
                        modifier = Modifier.fillMaxWidth().testTag("font_slider")
                    )
                }
            }
        }

        // Email Settings Card
        item {
            val savedEmail = sharedPrefs.getString("user_email", "") ?: ""
            var userEmailConfig by remember(savedEmail) {
                mutableStateOf(savedEmail)
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("email_settings_card"),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "إعدادات البريد الإلكتروني للمشاركة",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "قم بتهيئة البريد الإلكتروني الخاص بك ليتم إدراجه تلقائياً كجهة مستلمة مفضلة عند مشاركة الأخبار والمستجدات عبر البريد الإلكتروني.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = userEmailConfig,
                        onValueChange = { newValue ->
                            userEmailConfig = newValue
                            sharedPrefs.edit().putString("user_email", newValue).apply()
                        },
                        label = { Text("البريد الإلكتروني الذاتي / جهة الاستلام", fontSize = 12.sp) },
                        placeholder = { Text("user@example.com", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("user_email_setting_input")
                    )
                }
            }
        }

        // Report Exporter Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📊 تصدير واستخراج التقارير",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "تصدير الأرشيف والسجلات الكاملة للمستجدات للطباعة أو الأرشفة بصيغ متعددة متوافقة مع Excel وقارئ PDF.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Excel export button
                        Button(
                            onClick = {
                                if (updates.isEmpty()) {
                                    Toast.makeText(context, "لا توجد مستجدات لتصديرها.", Toast.LENGTH_SHORT).show()
                                } else {
                                    onExportExcel()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_csv_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(Icons.Default.GridOn, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("جدول Excel", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // PDF export button
                        Button(
                            onClick = {
                                if (updates.isEmpty()) {
                                    Toast.makeText(context, "لا توجد مستجدات لتصديرها.", Toast.LENGTH_SHORT).show()
                                } else {
                                    onExportPdf()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_html_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تقرير PDF / Print", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Authentication & Sync Segment
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🔐 حساب المستخدم ومزامنة الأجهزة",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "قم بإنشاء حساب أو تسجيل الدخول لمزامنة قائمة مواقعك الـ 15 وسجل مستجداتك تلقائياً وبأمان بين جميع أجهزتك الذكية ومحاكي البث.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (!isLoggedIn) {
                        // Sign-in Form representation
                        OutlinedTextField(
                            value = loginUsername,
                            onValueChange = { 
                                loginUsername = it 
                                sharedPrefs.edit().putString("login_username", it).apply()
                                if (it.contains("@")) {
                                    sharedPrefs.edit().putString("user_email", it).apply()
                                }
                            },
                            label = { Text("اسم المستخدم / البريد الإلكتروني", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = loginPassword,
                            onValueChange = { 
                                loginPassword = it 
                                sharedPrefs.edit().putString("login_password", it).apply()
                            },
                            label = { Text("كلمة المرور", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    if (loginUsername.isNotBlank() && loginPassword.isNotBlank()) {
                                        isLoggedIn = true
                                        isSyncingState = true
                                        sharedPrefs.edit()
                                            .putBoolean("is_logged_in", true)
                                            .putBoolean("is_syncing_state", true)
                                            .putString("login_username", loginUsername)
                                            .putString("login_password", loginPassword)
                                            .apply()
                                        if (loginUsername.contains("@")) {
                                            sharedPrefs.edit().putString("user_email", loginUsername).apply()
                                        }
                                    } else {
                                        Toast.makeText(context, "يرجى تعبئة بيانات الدخول للتسجيل.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("تسجيل الدخول", fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    if (loginUsername.isNotBlank() && loginPassword.isNotBlank()) {
                                        isLoggedIn = true
                                        isSyncingState = true
                                        sharedPrefs.edit()
                                            .putBoolean("is_logged_in", true)
                                            .putBoolean("is_syncing_state", true)
                                            .putString("login_username", loginUsername)
                                            .putString("login_password", loginPassword)
                                            .apply()
                                        if (loginUsername.contains("@")) {
                                            sharedPrefs.edit().putString("user_email", loginUsername).apply()
                                        }
                                    } else {
                                        Toast.makeText(context, "يرجى كتابة الاسم وكلمة المرور الحالية لإنشاء الحساب.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("إنشاء حساب جديد", fontSize = 12.sp)
                            }
                        }
                    } else {
                        // Logged-in Sync Panel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("مرحباً بك: ${loginUsername.ifBlank { "المستخدم الجديد" }}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFF4CAF50), shape = CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("المزامنة السحابية نشطة وتعمل", fontSize = 11.sp, color = Color(0xFF2E7D32))
                                }
                            }
                            
                            IconButton(
                                onClick = { 
                                    val nextState = !isSyncingState
                                    isSyncingState = nextState 
                                    sharedPrefs.edit().putBoolean("is_syncing_state", nextState).apply()
                                },
                                modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = "مزامنة سحابية",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (isSyncingState) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("جاري رفع واسترداد المواقع الـ ${sites.size} المضافة لملفك الشخصي...", fontSize = 10.sp, color = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { 
                                isLoggedIn = false
                                isSyncingState = false 
                                sharedPrefs.edit()
                                    .putBoolean("is_logged_in", false)
                                    .putBoolean("is_syncing_state", false)
                                    .putString("login_username", "")
                                    .putString("login_password", "")
                                    .apply()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("تسجيل الخروج", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Guide / Policy Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("💡 معلومات تقنية للتطبيق", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "برمجة هذا النظام تدعم كشط الصفحات بـ Jsoup وقراءة ملفات RSS Feed ومقارنة التوقيع الرقمي (MD5 Content Hash) لضمان تفادي الحظر والاستهلاك العالي للبيانات. يلتزم التطبيق بفحص دوري كل 30 دقيقة خلال نطاق وقت البحرين (Asia/Bahrain).",
                        fontSize = 10.sp,
                        color = Color.DarkGray,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

// --- Dynamic Dialogue for Site Addition ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSiteDialog(
    maxSitesLimitReached: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, Boolean) -> Unit
) {
    var siteName by remember { mutableStateOf("") }
    var siteUrl by remember { mutableStateOf("") }
    var noteEnabled by remember { mutableStateOf(true) }
    var aiSmartEnabled by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("add_site_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "🌐 إضافة موقع جديد للمتابعة",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(14.dp))

                if (maxSitesLimitReached) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFEBEE), shape = RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "❌ عذراً! لقد وصلت للحد الأقصى المسموح به للمتابعة وهو 15 موقعاً إلكترونياً. يرجى حذف موقع من اللوحة للتمكن من إضافة موقع آخر.",
                            color = Color(0xFFC62828),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    OutlinedTextField(
                        value = siteName,
                        onValueChange = { siteName = it },
                        label = { Text("اسم الموقع (مثال: وزارة العمل)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_site_name_field")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = siteUrl,
                        onValueChange = { siteUrl = it },
                        label = { Text("رابط الموقع (URL / RSS feed)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_site_url_field"),
                        placeholder = { Text("https://example.com", fontSize = 12.sp) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // AI Smart toggle (Smart AI Scan with Gemini)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الفحص الذكي بالذكاء الاصطناعي (AI)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("يستخدم الذكاء الاصطناعي (Gemini) لتلخيص التحديثات وتصنيفها بدقة وتفادي إعلانات الموقع.", fontSize = 9.sp, color = Color.Gray, lineHeight = 11.sp)
                        }
                        Switch(
                            checked = aiSmartEnabled,
                            onCheckedChange = { aiSmartEnabled = it },
                            modifier = Modifier.scale(0.8f).testTag("add_site_ai_toggle")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Notifications Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("تفعيل التنبيهات الفورية", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("إشعارات فورية تظهر بالجوال كرسالة دفع.", fontSize = 9.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = noteEnabled,
                            onCheckedChange = { noteEnabled = it },
                            modifier = Modifier.scale(0.8f).testTag("add_site_alerts_toggle")
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss_dialog_btn")) {
                        Text("إلغاء الأمر")
                    }
                    if (!maxSitesLimitReached) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (siteName.isNotBlank() && siteUrl.isNotBlank()) {
                                    onConfirm(siteName, siteUrl, noteEnabled, aiSmartEnabled)
                                } else {
                                    onConfirm("", "", false, false) // Will trigger VM error handling
                                }
                            },
                            modifier = Modifier.testTag("confirm_dialog_btn")
                        ) {
                            Text("حفظ الموقع")
                        }
                    }
                }
            }
        }
    }
}

// --- HELPER UTILITY LOGICS ---

private fun isWorkingHourBahrain(): Boolean {
    val bTimeZone = TimeZone.getTimeZone("Asia/Bahrain")
    val cal = Calendar.getInstance(bTimeZone)
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    return hour in 7..22
}

private fun formatBahrainTime(timestamp: Long): String {
    val bTimeZone = TimeZone.getTimeZone("Asia/Bahrain")
    val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.forLanguageTag("ar-u-nu-latn"))
    sdf.timeZone = bTimeZone
    return sdf.format(Date(timestamp))
}

private val WhatsAppIcon: ImageVector by lazy {
    val pathData = "M12.031 2c-5.514 0-9.989 4.484-9.989 9.998c0 1.762.459 3.479 1.33 4.985l-1.416 5.176l5.309-1.392a9.92 9.92 0 0 0 4.766 1.229h.004c5.514 0 9.99-4.485 9.99-9.999c0-2.671-1.04-5.182-2.928-7.071c-1.888-1.89-4.4-2.926-7.066-2.926zm5.836 14.159c-.247.697-1.235 1.282-1.705 1.332c-.443.048-.795.143-2.73-.625c-2.476-.983-4.062-3.493-4.186-3.657c-.124-.165-.989-1.314-.989-2.508c0-1.194.624-1.781.846-2.028c.222-.247.494-.309.658-.309s.329.002.473.009c.153.007.359-.059.564.44c.21.512.72 1.758.782 1.882c.062.124.103.268.02.433c-.082.165-.124.268-.247.412c-.124.144-.26.322-.371.433c-.124.124-.253.258-.109.505c.144.247.639 1.052 1.371 1.701c.944.839 1.741 1.099 1.988 1.223c.247.124.391.103.535-.062c.144-.165.618-.721.783-.968c.165-.247.33-.206.556-.124c.227.082 1.442.68 1.69 1.052c.247.371.247.556.124.902z"
    ImageVector.Builder(
        name = "WhatsApp",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.White)
    ).build()
}

@Composable
private fun SidebarNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    badgeCount: Int = 0,
    testTag: String
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = fontWeight,
                color = contentColor
            )
        }

        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeCount.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

object UrlShortener {
    private val client = OkHttpClient()
    
    suspend fun shortenUrl(longUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val encodedUrl = URLEncoder.encode(longUrl, "UTF-8")
            val requestUrl = "https://is.gd/create.php?format=simple&url=$encodedUrl"
            val request = Request.Builder()
                .url(requestUrl)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.trim() ?: longUrl
                } else {
                    longUrl
                }
            }
        } catch (e: Exception) {
            longUrl
        }
    }
}

fun copyToBothClipboards(context: Context, composeClipboard: androidx.compose.ui.platform.ClipboardManager, text: String, toastMessage: String) {
    try {
        val systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("radar_link", text)
        systemClipboard.setPrimaryClip(clip)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    try {
        composeClipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
    } catch (e: Exception) {
        e.printStackTrace()
    }
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

@Composable
fun InAppWebViewDialog(
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val composeClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var isWebLoading by remember { mutableStateOf(true) }
    var activeTabState by remember { mutableStateOf(0) } // 0 = Link Device Bridge, 1 = WebView
    
    // Short URL state
    var shortUrl by remember { mutableStateOf<String?>(null) }
    var isShortening by remember { mutableStateOf(false) }
    
    // Trigger URL shortener on launch
    LaunchedEffect(url) {
        isShortening = true
        shortUrl = UrlShortener.shortenUrl(url)
        isShortening = false
    }

    Dialog(
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        ),
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                    
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "بوابة الارتباط الراداري الذكي",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = url,
                            fontSize = 9.sp,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row {
                        IconButton(onClick = {
                            copyToBothClipboards(
                                context = context,
                                composeClipboard = composeClipboard,
                                text = url,
                                toastMessage = "تم نسخ الرابط المباشر بنجاح للنسخ الخارجي!"
                            )
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "نسخ الرابط", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "الرابط تم نسخه. لا يتوفر متصفح خارجي في هذه البيئة الافتراضية.", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "متصفح خارجي", modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Dual-tab implementation
                TabRow(
                    selectedTabIndex = activeTabState,
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = activeTabState == 0,
                        onClick = { activeTabState = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("جسر الأجهزة الأخرى والكمبيوتر", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                    Tab(
                        selected = activeTabState == 1,
                        onClick = { activeTabState = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تصفح داخل التطبيق", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Tab Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (activeTabState == 0) {
                        // CROSS-DEVICE SHARE PORTAL
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            item {
                                Text(
                                    text = "بوابة الربط والنقل الفوري للأجهزة الخارجية",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    text = "تتيح لك البوابة فتح هذا الرابط المباشر على هاتفك الشخصي أو شاشة الكمبيوتر/اللاب توب بكل سهولة عبر المسح أو الرابط المختصر الفوري.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            // QR Section
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.PhoneAndroid, contentDescription = "الهاتف والجوال", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "امسح الرمز من هاتفك (الفتح عبر الكود)",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(12.dp))

                                        val encodedUrl = remember(url) { URLEncoder.encode(url, "UTF-8") }
                                        val qrCodeUrl = "https://api.qrserver.com/v1/create-qr-code/?size=350x350&margin=1&data=$encodedUrl"
                                        
                                        Box(
                                            modifier = Modifier
                                                .size(200.dp)
                                                .background(Color.White, shape = RoundedCornerShape(12.dp))
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(qrCodeUrl)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = "بار كود فتح الرابط",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Text(
                                            text = "وجّه كاميرا هاتفك للرمز لفتح الموقع فورا خارج هذه البيئة الافتراضية بنقرة واحدة!",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            // Desktop/PC Short Link Section
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Laptop, contentDescription = "الكمبيوتر الشخصي", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "رابط قصير جداً للتصفح عبر الكمبيوتر",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        if (isShortening) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text("جاري إنشاء رابط مختصر فوري...", fontSize = 10.sp, color = Color.Gray)
                                        } else {
                                            val displayShort = shortUrl ?: url
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier
                                                    .background(MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = displayShort,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                    textAlign = TextAlign.Center
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                IconButton(
                                                    onClick = {
                                                        copyToBothClipboards(
                                                            context = context,
                                                            composeClipboard = composeClipboard,
                                                            text = displayShort,
                                                            toastMessage = "تم نسخ الرابط القصير بنجاح للكمبيوتر!"
                                                        )
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "نسخ", modifier = Modifier.size(16.dp))
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Text(
                                                text = "اكتب الحروف القليلة الماضية في متصفح الكمبيوتر لتصفح آمن وسريع تماماً خارج التطبيق.",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            // Raw Universal Clipboard Options
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp).fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "خيارات عامة للنسخ والنقل الشامل",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )

                                        Button(
                                            onClick = {
                                                copyToBothClipboards(
                                                    context = context,
                                                    composeClipboard = composeClipboard,
                                                    text = url,
                                                    toastMessage = "تم نسخ رابط الخبر الكامل بنجاح ومزامنته خارجياً!"
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("نسخ رابط الخبر الأصلي خارج التطبيق", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        OutlinedButton(
                                            onClick = {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "لم يتم العثور على متصفح افتراضي في هذا النظام الرقمي.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("جرب فتحه الآن في المتصفح الخارجي للهاتف", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (activeTabState == 1) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                isWebLoading = false
                                            }
                                        }
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        loadUrl(url)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            if (isWebLoading) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("جاري تحميل الموقع...", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

