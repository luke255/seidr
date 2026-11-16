package eu.bradshaw.seidr.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OfflineSource::class, Claim::class, Download::class, PendingEdit::class, PendingScrobble::class, PlaylistVersion::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun offline(): OfflineDao
    abstract fun history(): HistoryDao

    companion object {
        fun build(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "seidr.db").build()
    }
}
