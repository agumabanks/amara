package co.sanaa.agent.core

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement

/** SQLite change counting without deprecated APIs: bind + executeUpdateDelete. */
fun SQLiteDatabase.execSQLWithCount(sql: String, args: Array<out Any?>): Int {
    var statement: SQLiteStatement? = null
    return try {
        statement = compileStatement(sql)
        args.forEachIndexed { index, arg ->
            when (arg) {
                null -> statement!!.bindNull(index + 1)
                is Long -> statement!!.bindLong(index + 1, arg)
                is Int -> statement!!.bindLong(index + 1, arg.toLong())
                is String -> statement!!.bindString(index + 1, arg)
                is Boolean -> statement!!.bindLong(index + 1, if (arg) 1L else 0L)
                is Double -> statement!!.bindDouble(index + 1, arg)
                else -> statement!!.bindString(index + 1, arg.toString())
            }
        }
        statement!!.executeUpdateDelete()
    } finally {
        statement?.close()
    }
}
