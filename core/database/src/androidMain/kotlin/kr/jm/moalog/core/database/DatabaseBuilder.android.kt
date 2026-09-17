package kr.jm.moalog.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun createDatabaseBuilder(context: Context): RoomDatabase.Builder<MoaLogDatabase> =
    Room.databaseBuilder(
        context.applicationContext,
        context.applicationContext.getDatabasePath("moalog.db").absolutePath,
    )
