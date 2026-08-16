package com.dwk.flowmoney

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "simplefin_profile")
data class SimpleFinProfileEntity(
    @PrimaryKey @ColumnInfo(defaultValue = "'default'") val id: String = "default",
    @ColumnInfo(defaultValue = "'legacy'") val connectionId: String = "legacy",
    val connectedAtEpochMillis: Long? = null,
    val lastSyncAttemptAtEpochMillis: Long? = null,
    val lastSuccessfulSyncAtEpochMillis: Long? = null,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0") val isPaused: Boolean = false,
    @ColumnInfo(defaultValue = "1") val automaticSyncsPerDay: Int = 1,
)

@Entity(tableName = "simplefin_accounts")
data class SimpleFinAccountEntity(
    @PrimaryKey val accountId: String,
    val name: String,
    val currency: String?,
    val institutionName: String?,
    val balanceAmount: String?,
    val availableBalanceAmount: String?,
    val balanceDateEpochSeconds: Long?,
    val lastSeenAtEpochMillis: Long,
)

@Entity(
    tableName = "simplefin_ignored_transactions",
    indices = [Index(value = ["occurredAtEpochMillis"])],
)
data class SimpleFinIgnoredTransactionEntity(
    @PrimaryKey val transactionId: String,
    val ignoredAtEpochMillis: Long = System.currentTimeMillis(),
    val occurredAtEpochMillis: Long? = null,
)

@Dao
interface SimpleFinDao {
    @Query("SELECT * FROM simplefin_profile WHERE id = 'default'")
    fun observeProfile(): Flow<SimpleFinProfileEntity?>

    @Query("SELECT * FROM simplefin_accounts ORDER BY institutionName, name")
    fun observeAccounts(): Flow<List<SimpleFinAccountEntity>>

    @Query("SELECT * FROM simplefin_profile WHERE id = 'default'")
    suspend fun getProfile(): SimpleFinProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: SimpleFinProfileEntity)

    @Query(
        "UPDATE simplefin_profile SET lastSyncAttemptAtEpochMillis = :time, lastError = NULL WHERE id = 'default' AND connectionId = :connectionId",
    )
    suspend fun recordAttempt(
        connectionId: String,
        time: Long,
    ): Int

    @Query(
        "UPDATE simplefin_profile SET lastSuccessfulSyncAtEpochMillis = :time, lastError = :error WHERE id = 'default' AND connectionId = :connectionId",
    )
    suspend fun recordSuccess(
        connectionId: String,
        time: Long,
        error: String?,
    ): Int

    @Query("UPDATE simplefin_profile SET lastError = :error WHERE id = 'default' AND connectionId = :connectionId")
    suspend fun recordFailure(
        connectionId: String,
        error: String,
    ): Int

    @Query(
        "UPDATE simplefin_profile SET lastError = 'SimpleFIN reconnect required', isPaused = 1 WHERE id = 'default' AND connectionId = :connectionId",
    )
    suspend fun pauseForReconnect(connectionId: String): Int

    @Query("UPDATE simplefin_profile SET automaticSyncsPerDay = :count WHERE id = 'default' AND connectionId = :connectionId")
    suspend fun updateAutomaticSyncsPerDay(
        connectionId: String,
        count: Int,
    ): Int

    @Query("DELETE FROM simplefin_profile WHERE id = 'default' AND connectionId = :connectionId")
    suspend fun clearProfile(connectionId: String): Int

    @Query("DELETE FROM simplefin_profile")
    suspend fun clearProfile()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(accounts: List<SimpleFinAccountEntity>)

    @Query("DELETE FROM simplefin_accounts")
    suspend fun clearAccounts()

    @Query("SELECT transactionId FROM simplefin_ignored_transactions")
    suspend fun ignoredTransactionIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnored(ignored: SimpleFinIgnoredTransactionEntity)

    @Query("DELETE FROM simplefin_ignored_transactions WHERE transactionId = :id")
    suspend fun deleteIgnored(id: String)
}
