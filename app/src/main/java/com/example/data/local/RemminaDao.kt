package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RemminaDao {
    // Connection Profiles
    @Query("SELECT * FROM connection_profiles ORDER BY isFavorite DESC, groupName ASC, name ASC")
    fun observeAllProfiles(): Flow<List<ConnectionProfileEntity>>

    @Query("SELECT * FROM connection_profiles ORDER BY isFavorite DESC, name ASC")
    suspend fun getAllProfilesSnapshot(): List<ConnectionProfileEntity>

    @Query("SELECT * FROM connection_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Long): ConnectionProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ConnectionProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ConnectionProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ConnectionProfileEntity)

    @Query("UPDATE connection_profiles SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE connection_profiles SET lastConnectedEpochMs = :timestamp, lastPingLatencyMs = :latencyMs WHERE id = :id")
    suspend fun updateConnectionStats(id: Long, timestamp: Long, latencyMs: Int)

    @Query("UPDATE connection_profiles SET resolution = :resolution, resolutionMode = :resolutionMode, scalingMode = :scalingMode, colorDepth = :colorDepth, dpiScalePercent = :dpiScale WHERE id = :id")
    suspend fun updateProfileDisplaySettings(
        id: Long,
        resolution: String,
        resolutionMode: String,
        scalingMode: String,
        colorDepth: Int,
        dpiScale: Int
    )

    @Query("UPDATE connection_profiles SET username = :username, password = :password, domain = :domain, securityMode = :securityMode WHERE id = :id")
    suspend fun updateProfileCredentials(
        id: Long,
        username: String,
        password: String,
        domain: String,
        securityMode: String
    )

    @Query("UPDATE connection_profiles SET pinnedCertSha256 = :sha256 WHERE id = :id")
    suspend fun updatePinnedCertificate(id: Long, sha256: String)

    // Protocol Default Ports
    @Query("SELECT * FROM protocol_port_defaults")
    fun observeProtocolDefaultPorts(): Flow<List<ProtocolPortDefaultEntity>>

    @Query("SELECT * FROM protocol_port_defaults")
    suspend fun getProtocolDefaultPortsSnapshot(): List<ProtocolPortDefaultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProtocolDefaultPort(entity: ProtocolPortDefaultEntity)

    // SSH Keys Vault
    @Query("SELECT * FROM ssh_keys ORDER BY createdAtEpochMs DESC")
    fun observeAllSshKeys(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys ORDER BY createdAtEpochMs DESC")
    suspend fun getAllSshKeysSnapshot(): List<SshKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSshKey(key: SshKeyEntity): Long

    @Delete
    suspend fun deleteSshKey(key: SshKeyEntity)

    // Hardware Keyboard Mappings
    @Query("SELECT * FROM key_mappings ORDER BY id ASC")
    fun observeAllKeyMappings(): Flow<List<KeyMappingEntity>>

    @Query("SELECT * FROM key_mappings ORDER BY id ASC")
    suspend fun getAllKeyMappingsSnapshot(): List<KeyMappingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeyMapping(mapping: KeyMappingEntity): Long

    @Update
    suspend fun updateKeyMapping(mapping: KeyMappingEntity)

    @Delete
    suspend fun deleteKeyMapping(mapping: KeyMappingEntity)

    @Query("UPDATE key_mappings SET isEnabled = :enabled WHERE id = :id")
    suspend fun setKeyMappingEnabled(id: Long, enabled: Boolean)
}
