package dubrowgn.microtimer.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Alarm::class], version = 2, exportSchema = false)
@TypeConverters(Dec6DurationConverter::class)
abstract class Database : RoomDatabase() {
    abstract fun AlarmDao(): AlarmDao

    companion object {
        // v1 -> v2: timers can have a name
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarm ADD COLUMN name TEXT")
            }
        }
    }
}
