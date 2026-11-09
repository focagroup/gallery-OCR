package io.github.minuus69.galleryocr.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ImageEntity::class, OcrTextEntity::class, CollectionEntity::class, CollectionItemEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun images(): ImageDao
    abstract fun collections(): CollectionDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "index.db").build()
    }
}
