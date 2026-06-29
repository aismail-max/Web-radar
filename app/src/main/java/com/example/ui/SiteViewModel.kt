package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

class SiteViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "SiteViewModel"
    private val database = AppDatabase.getDatabase(application)
    val repository = SiteRepository(database.siteDao)

    init {
        viewModelScope.launch {
            try {
                // Wait/check if database has zero monitored sites
                val currentSites = repository.allSites.first()
                if (currentSites.isEmpty()) {
                    Log.d(TAG, "No sites found in database. Prepopulating default Bahrain government portals...")
                    val defaultSites = listOf(
                        Pair("مصرف البحرين المركزي", "https://www.cbb.gov.bh/ar/"),
                        Pair("مؤسسة تنظيم القطاع العقاري", "https://www.rera.gov.bh/ar"),
                        Pair("جهاز المساحة والتسجيل العقاري", "https://www.slrb.gov.bh/"),
                        Pair("المركز الإحصائي لدول الخليج", "https://www.gccstat.org/"),
                        Pair("المركز الإعلامي الأمني - الداخلية", "https://www.policemc.gov.bh/"),
                        Pair("وزارة العدل والشؤون الإسلامية", "https://services.bahrain.bh/wps/portal/ar/BSP/GSX-UI-MultipleEntitiesByEService/GSX-UI-EServicesByEntity?entityID=13"),
                        Pair("المجلس الأعلى للقضاء", "https://www.sjc.bh/index_16.php"),
                        Pair("الهيئة العامة للتأمين الاجتماعي", "https://www.sio.gov.bh/ar/"),
                        Pair("بوابة الحكومة الإلكترونية (الجهة 30)", "https://services.bahrain.bh/wps/portal/ar/BSP/GSX-UI-MultipleEntitiesByEService/GSX-UI-EServicesByEntity?entityID=30"),
                        Pair("هيئة الكهرباء والماء", "https://www.ewa.bh/ar/"),
                        Pair("هيئة المعلومات والحكومة الإلكترونية", "https://www.bahrain.bh/wps/portal/ar/BNP/GSX-UI-AllEntities/GSX-UI-EntityDetails?entityID=31")
                    )
                    
                    for ((name, url) in defaultSites) {
                        repository.addSite(
                            name = name,
                            url = url,
                            notificationsEnabled = true,
                            useAiSmartScan = false // fast and offline-ready default
                        )
                    }
                    
                    // Trigger an initial refresh of sites
                    refreshAllSites()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed seeding default sites", e)
            }
        }
    }

    // --- State Management ---
    val allSites: StateFlow<List<MonitoredSite>> = repository.allSites
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allUpdates: StateFlow<List<UpdateRecord>> = repository.allUpdates
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val unreadCount: StateFlow<Int> = repository.unreadUpdatesCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val sitesCount: StateFlow<Int> = repository.sitesCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // Refresh loading state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Status banner updates/messages in Arabic
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // --- Filters and Search ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterSiteId = MutableStateFlow<Int?>(null) // null means all
    val filterSiteId: StateFlow<Int?> = _filterSiteId.asStateFlow()

    private val _filterType = MutableStateFlow<String?>(null) // null means all
    val filterType: StateFlow<String?> = _filterType.asStateFlow()

    private val _filterDays = MutableStateFlow<Int?>(null) // null means unlimited, 1=today, 7=week, 30=month
    val filterDays: StateFlow<Int?> = _filterDays.asStateFlow()

    // Combine updates with search and filters in real time
    val filteredUpdates: StateFlow<List<UpdateRecord>> = combine(
        allUpdates,
        searchQuery,
        filterSiteId,
        filterType,
        filterDays
    ) { updates, query, siteId, type, days ->
        updates.filter { post ->
            // 1. Search filter
            val matchesSearch = query.isEmpty() || 
                post.title.contains(query, ignoreCase = true) || 
                post.briefDescription.contains(query, ignoreCase = true) || 
                post.siteName.contains(query, ignoreCase = true)

            // 2. Site filter
            val matchesSite = siteId == null || post.siteId == siteId

            // 3. Type filter
            val matchesType = type == null || post.type == type

            // 4. Date filter (strictly capped at 48 hours max)
            val maxLimitCutoff = System.currentTimeMillis() - (48L * 60L * 60L * 1000L)
            val matchesDate = if (days == null) {
                post.discoveryTime >= maxLimitCutoff
            } else if (days == 12) { // 12 represents 12 hours
                val timeCutoff = System.currentTimeMillis() - (12L * 60L * 60L * 1000L)
                post.discoveryTime >= timeCutoff
            } else {
                val timeCutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
                post.discoveryTime >= timeCutoff && post.discoveryTime >= maxLimitCutoff
            }

            matchesSearch && matchesSite && matchesType && matchesDate
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSiteFilter(id: Int?) {
        _filterSiteId.value = id
    }

    fun setTypeFilter(type: String?) {
        _filterType.value = type
    }

    fun setDateFilter(days: Int?) {
        _filterDays.value = days
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    // --- CRUD Actions ---

    fun addNewSite(
        name: String, 
        url: String, 
        notificationsEnabled: Boolean, 
        useAiSmartScan: Boolean,
        onResult: (String) -> Unit
    ) {
        if (name.isBlank() || url.isBlank()) {
            _statusMessage.value = "يرجى ملء جميع الحقول المطلوبة."
            onResult("يرجى ملء جميع الحقول.")
            return
        }

        viewModelScope.launch {
            val result = repository.addSite(name, url, notificationsEnabled, useAiSmartScan)
            when (result) {
                is AddSiteResult.Success -> {
                    _statusMessage.value = "تمت إضافة الموقع بنجاح!"
                    onResult("نجاح")
                    // Immediately trigger a scan for the newly added site to show initial content!
                    val newlyAddedSite = allSites.value.find { it.id == result.siteId }
                    if (newlyAddedSite != null) {
                        refreshSingleSiteQuietly(newlyAddedSite)
                    }
                }
                is AddSiteResult.Error -> {
                    _statusMessage.value = result.message
                    onResult(result.message)
                }
            }
        }
    }

    fun editSite(site: MonitoredSite) {
        viewModelScope.launch {
            repository.updateSite(site)
            _statusMessage.value = "تمت تحديث إعدادات الموقع بنجاح."
        }
    }

    fun deleteSite(site: MonitoredSite) {
        viewModelScope.launch {
            repository.deleteSite(site)
            _statusMessage.value = "تم حذف الموقع وسجله بالكامل."
        }
    }

    fun toggleSiteEnabled(site: MonitoredSite, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.updateSite(site.copy(isEnabled = isEnabled))
            _statusMessage.value = if (isEnabled) {
                "تم تفعيل متابعة الموقع: ${site.name}"
            } else {
                "تم إيقاف متابعة الموقع: ${site.name}"
            }
        }
    }

    fun toggleSiteNotifications(site: MonitoredSite, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.updateSite(site.copy(isNotificationsEnabled = isEnabled))
            _statusMessage.value = if (isEnabled) {
                "تم تفعيل تنبيهات الموقع."
            } else {
                "تم إلغاء تنبيهات الموقع."
            }
        }
    }

    fun markAsRead(id: Int) {
        viewModelScope.launch {
            repository.markUpdateAsRead(id)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            repository.markAllUpdatesAsRead()
            _statusMessage.value = "تم وضع علامة مقروء على كافة المستجدات."
        }
    }

    // --- Core Sync Engine ---

    fun refreshAllSites() {
        if (_isRefreshing.value) return

        viewModelScope.launch {
            _isRefreshing.value = true
            _statusMessage.value = "جاري فحص المواقع بشكل متوازي..."
            
            val sites = allSites.value.filter { it.isEnabled }
            if (sites.isEmpty()) {
                _statusMessage.value = "لا توجد مواقع مفعلة للفحص حالياً."
                _isRefreshing.value = false
                return@launch
            }

            try {
                val deferreds = sites.map { site ->
                    async(Dispatchers.IO) {
                        try {
                            repository.refreshSiteUpdates(site) { }
                        } catch (e: Exception) {
                            Log.e(TAG, "Parallel refresh failed for ${site.name}: ", e)
                            0
                        }
                    }
                }
                val results = deferreds.awaitAll()
                val totalNewUpdates = results.sum()

                _statusMessage.value = if (totalNewUpdates > 0) {
                    "تم الفحص بنجاح! تم رصد $totalNewUpdates تحديثات جديدة."
                } else {
                    "اكتمل الفحص. لا توجد تحديثات جديدة."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in parallel site refresh: ", e)
                _statusMessage.value = "حدث خطأ أثناء فحص المواقع."
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun refreshSingleSiteQuietly(site: MonitoredSite) {
        try {
            repository.refreshSiteUpdates(site) { }
        } catch (e: Exception) {
            Log.e(TAG, "Quiet refresh failed: ", e)
        }
    }
}
