package eu.bradshaw.seidr.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OfflineSource::class, Claim::class, Download::class, PendingEdit::class, PendingScrobble::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun offline(): OfflineDao

    companion object {
        fun build(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "seidr.db").build()
    }
}
