package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {

    // --- Monitored Sites Operations ---
    @Query("SELECT * FROM monitored_sites ORDER BY id DESC")
    fun getAllSitesFlow(): Flow<List<MonitoredSite>>

    @Query("SELECT * FROM monitored_sites WHERE isEnabled = 1")
    suspend fun getActiveSites(): List<MonitoredSite>

    @Query("SELECT * FROM monitored_sites WHERE id = :id")
    suspend fun getSiteById(id: Int): MonitoredSite?

    @Query("SELECT COUNT(*) FROM monitored_sites")
    fun getSitesCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSite(site: MonitoredSite): Long

    @Update
    suspend fun updateSite(site: MonitoredSite)

    @Delete
    suspend fun deleteSite(site: MonitoredSite)

    @Query("DELETE FROM monitored_sites WHERE id = :id")
    suspend fun deleteSiteById(id: Int)


    // --- Update Records (News & Posts) Operations ---
    @Query("SELECT * FROM update_records ORDER BY discoveryTime DESC")
    fun getAllUpdatesFlow(): Flow<List<UpdateRecord>>

    @Query("SELECT * FROM update_records WHERE siteId = :siteId ORDER BY discoveryTime DESC")
    fun getUpdatesBySiteFlow(siteId: Int): Flow<List<UpdateRecord>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUpdateRecord(record: UpdateRecord): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUpdateRecords(records: List<UpdateRecord>): List<Long>

    @Query("UPDATE update_records SET isRead = 1 WHERE id = :id")
    suspend fun markUpdateAsRead(id: Int)

    @Query("UPDATE update_records SET isRead = 1")
    suspend fun markAllUpdatesAsRead()

    @Query("DELETE FROM update_records WHERE siteId = :siteId")
    suspend fun deleteUpdatesBySiteId(siteId: Int)

    @Query("DELETE FROM update_records WHERE discoveryTime < :threshold")
    suspend fun deleteOldUpdates(threshold: Long)

    @Query("SELECT COUNT(*) FROM update_records WHERE isRead = 0")
    fun getUnreadCountFlow(): Flow<Int>
}
