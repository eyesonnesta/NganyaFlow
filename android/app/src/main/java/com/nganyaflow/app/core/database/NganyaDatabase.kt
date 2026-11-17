package com.nganyaflow.app.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(primaryKeys = ["server", "key"])
data class CacheEntity(val server: String, val key: String, val payload: String, val updatedAt: Long)
@Entity(primaryKeys = ["server", "id"], indices = [Index(value = ["server", "kind", "sessionId"])])
data class RecordEntity(
    val server: String, val id: String, val kind: String, val payload: String,
    val state: String = "Queued", val createdAt: Long, val detail: String? = null,
    val sessionId: String = "",
)
@Entity(indices = [Index(value = ["server", "id"], unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val server: String, val id: String, val entityId: String, val action: String,
    val groupId: String, val payload: String, val state: String = "Queued",
    val attempted: Boolean = false, val detail: String? = null,
)
@Entity data class IdentityEntity(@PrimaryKey val server: String, val installId: String)

@Dao interface NganyaDao {
    @Query("SELECT * FROM CacheEntity WHERE server=:server AND `key`=:key") fun cacheFlow(server: String, key: String): Flow<CacheEntity?>
    @Query("SELECT * FROM CacheEntity WHERE server=:server AND `key`=:key") suspend fun cached(server: String, key: String): CacheEntity?
    @Upsert suspend fun cache(row: CacheEntity)
    @Query("DELETE FROM CacheEntity WHERE server=:server AND (`key` LIKE 'estimate:%' OR `key` LIKE 'estimates:%')") suspend fun clearEstimates(server: String)
    @Query("SELECT * FROM RecordEntity WHERE server=:server AND kind=:kind ORDER BY createdAt DESC") fun records(server: String, kind: String): Flow<List<RecordEntity>>
    @Query("SELECT * FROM RecordEntity WHERE server=:server AND id=:id") suspend fun record(server: String, id: String): RecordEntity?
    @Query("SELECT * FROM RecordEntity WHERE server=:server AND kind=:kind") suspend fun recordsNow(server: String, kind: String): List<RecordEntity>
    @Upsert suspend fun record(row: RecordEntity)
    @Query("DELETE FROM RecordEntity WHERE server=:server AND id=:id") suspend fun deleteRecord(server: String, id: String)
    @Query("DELETE FROM RecordEntity WHERE server=:server AND kind='report'") suspend fun deleteReports(server: String)
    @Query("UPDATE RecordEntity SET state=:state,detail=:detail WHERE server=:server AND id=:id") suspend fun state(server: String, id: String, state: String, detail: String? = null)
    @Insert suspend fun enqueue(row: OutboxEntity)
    @Query("SELECT * FROM OutboxEntity WHERE server=:server ORDER BY sequence") fun outboxFlow(server: String): Flow<List<OutboxEntity>>
    @Query("SELECT * FROM OutboxEntity WHERE server=:server ORDER BY sequence") suspend fun outbox(server: String): List<OutboxEntity>
    @Query("SELECT * FROM OutboxEntity WHERE server=:server AND entityId=:entityId ORDER BY sequence") suspend fun entityOutbox(server: String, entityId: String): List<OutboxEntity>
    @Query("DELETE FROM OutboxEntity WHERE sequence=:sequence") suspend fun deleteOutbox(sequence: Long)
    @Query("DELETE FROM OutboxEntity WHERE server=:server AND entityId=:id") suspend fun deleteEntityOutbox(server: String, id: String)
    @Query("DELETE FROM OutboxEntity WHERE server=:server AND action IN ('report','retract')") suspend fun deleteReportOutbox(server: String)
    @Query("UPDATE OutboxEntity SET state=:state,attempted=:attempted,detail=:detail WHERE sequence=:sequence") suspend fun outboxState(sequence: Long, state: String, attempted: Boolean, detail: String? = null)
    @Query("SELECT * FROM IdentityEntity WHERE server=:server") suspend fun identity(server: String): IdentityEntity?
    @Upsert suspend fun identity(row: IdentityEntity)
}
@Database(entities = [CacheEntity::class, RecordEntity::class, OutboxEntity::class, IdentityEntity::class], version = 1, exportSchema = true)
abstract class NganyaDatabase : RoomDatabase() { abstract fun dao(): NganyaDao }
