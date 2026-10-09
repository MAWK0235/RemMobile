package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConnectionProfileEntity::class,
        ProtocolPortDefaultEntity::class,
        SshKeyEntity::class,
        KeyMappingEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class RemminaDatabase : RoomDatabase() {
    abstract fun remminaDao(): RemminaDao

    companion object {
        @Volatile
        private var INSTANCE: RemminaDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN allowScreenshots INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): RemminaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RemminaDatabase::class.java,
                    "remmina_remote_local.db"
                )
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
