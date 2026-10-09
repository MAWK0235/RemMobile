package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConnectionProfileEntity::class,
        ProtocolPortDefaultEntity::class,
        SshKeyEntity::class,
        KeyMappingEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class RemminaDatabase : RoomDatabase() {
    abstract fun remminaDao(): RemminaDao

    companion object {
        @Volatile
        private var INSTANCE: RemminaDatabase? = null

        fun getInstance(context: Context): RemminaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RemminaDatabase::class.java,
                    "remmina_remote_local.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
