package com.example.data

import android.content.Context
import android.util.Log
import com.example.api.WebParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SiteRepository(private val siteDao: SiteDao) {

    private val TAG = "SiteRepository"

    val allSites: Flow<List<MonitoredSite>> = siteDao.getAllSitesFlow()
    val allUpdates: Flow<List<UpdateRecord>> = siteDao.getAllUpdatesFlow()
    val unreadUpdatesCount: Flow<Int> = siteDao.getUnreadCountFlow()
    val sitesCount: Flow<Int> = siteDao.getSitesCountFlow()

    /**
     * Adds a new site to watch, enforcing a maximum limit of 15 websites.
     */
    suspend fun addSite(
        name: String, 
        url: String, 
        notificationsEnabled: Boolean = true,
        useAiSmartScan: Boolean = false
    ): AddSiteResult = withContext(Dispatchers.IO) {
        try {
            val currentCount = siteDao.getSitesCountFlow().first()
            if (currentCount >= 15) {
                return@withContext AddSiteResult.Error("لقد وصلت للحد الأقصى المسموح به وهو 15 موقعاً.")
            }

            // Simple URL format validation
            val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }

            val site = MonitoredSite(
                name = name.trim(),
                url = formattedUrl.trim(),
                isNotificationsEnabled = notificationsEnabled,
                useAiSmartScan = useAiSmartScan
            )

            val id = siteDao.insertSite(site)
            AddSiteResult.Success(id.toInt())
        } catch (e: Exception) {
            AddSiteResult.Error("حدث خطأ أثناء إضافة الموقع: ${e.message}")
        }
    }

    suspend fun updateSite(site: MonitoredSite) = withContext(Dispatchers.IO) {
        siteDao.updateSite(site)
    }

    suspend fun deleteSite(site: MonitoredSite) = withContext(Dispatchers.IO) {
        siteDao.deleteSite(site)
        siteDao.deleteUpdatesBySiteId(site.id)
    }

    suspend fun markUpdateAsRead(updateId: Int) = withContext(Dispatchers.IO) {
        siteDao.markUpdateAsRead(updateId)
    }

    suspend fun markAllUpdatesAsRead() = withContext(Dispatchers.IO) {
        siteDao.markAllUpdatesAsRead()
    }

    /**
     * Deletes records that are older than 48 hours.
     * Enforces the requirement "اقتصر التحديثات على فترة زمنية لا تتجاوز 48 ساعة".
     */
    suspend fun cleanOldUpdates() = withContext(Dispatchers.IO) {
        val fortyEightHoursInMillis = 48L * 60 * 60 * 1000
        val threshold = System.currentTimeMillis() - fortyEightHoursInMillis
        siteDao.deleteOldUpdates(threshold)
    }

    /**
     * Fetches current updates for a specific website, compares them to ensure no duplicates,
     * saves new discoveries to Room, and alerts the callback of new notifications.
     */
    suspend fun refreshSiteUpdates(
        site: MonitoredSite,
        onNewContentDetected: (UpdateRecord) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        if (!site.isEnabled) return@withContext 0

        try {
            val parseResult = WebParser.fetchUpdates(site.url, site.useAiSmartScan)
            
            // If the content hasn't changed at all at the raw level, skip scanning
            if (site.lastContentHash.isNotEmpty() && site.lastContentHash == parseResult.hash) {
                Log.d(TAG, "Content hash matched for site ${site.name}. No updates.")
                return@withContext 0
            }

            // Retrieve current updates from Room to manually crosscheck URLs
            val existingUpdates = siteDao.getAllUpdatesFlow().first()
            val existingUrls = existingUpdates.map { it.url }.toSet()
            val existingTitles = existingUpdates.map { it.title }.toSet()

            var newCount = 0
            val newRecords = mutableListOf<UpdateRecord>()

            for (parsedItem in parseResult.updates) {
                // Prevent duplicate notifications/entries (منع إرسال تنبيهات مكررة)
                val isNew = !existingUrls.contains(parsedItem.url) && !existingTitles.contains(parsedItem.title)
                
                if (isNew) {
                    val record = UpdateRecord(
                        siteId = site.id,
                        siteName = site.name,
                        title = parsedItem.title,
                        type = parsedItem.type,
                        briefDescription = parsedItem.briefDescription,
                        url = parsedItem.url,
                        isRead = false,
                        publishDate = parsedItem.publishDate
                    )
                    newRecords.add(record)
                    newCount++
                }
            }

            if (newRecords.isNotEmpty()) {
                val insertedIds = siteDao.insertUpdateRecords(newRecords)
                
                // If notifications are enabled for this site, trigger callbacks
                // We limit to max 3 notifications per refresh cycle to prevent flooding and OS silencing
                if (site.isNotificationsEnabled) {
                    newRecords.take(3).forEachIndexed { index, record ->
                        val savedId = insertedIds.getOrNull(index)?.toInt() ?: 0
                        val updatedRecord = if (savedId > 0) record.copy(id = savedId) else record
                        onNewContentDetected(updatedRecord)
                    }
                }
            }

            // Save the new hash and time
            val updatedSite = site.copy(
                lastCheckedTime = System.currentTimeMillis(),
                lastContentHash = parseResult.hash
            )
            siteDao.updateSite(updatedSite)

            cleanOldUpdates() // Clean old updates periodically on background refreshes too
            newCount

        } catch (e: Exception) {
            Log.e(TAG, "Failed refreshing site: ${site.name}", e)
            0
        }
    }
}

sealed class AddSiteResult {
    data class Success(val siteId: Int) : AddSiteResult()
    data class Error(val message: String) : AddSiteResult()
}
