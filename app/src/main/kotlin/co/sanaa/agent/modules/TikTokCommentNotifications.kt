package co.sanaa.agent.modules

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.service.notification.StatusBarNotification
import co.sanaa.agent.core.ContentHashing
import org.json.JSONObject

/** Own-video comment notifications only. Likes/follows/promotions never become reply work. */
object TikTokCommentNotifications {
    val packages = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    data class Comment(val author: String, val text: String)
    private data class Route(val intent: PendingIntent, val until: Long)
    private val routes=java.util.concurrent.ConcurrentHashMap<String,Route>()
    fun parse(title: String, text: String): Comment? {
        val combined=if(text.startsWith(title) || title.isBlank() || title.equals("TikTok",true)) text else "$title $text"
        val match=Regex("(?is)^(.{1,80}?)\\s+commented(?: on your (?:video|post))?:\\s*[\"“]?(.{1,600}?)[\"”]?$").matchEntire(combined.trim()) ?: return null
        val author=match.groupValues[1].trim();val body=match.groupValues[2].trim()
        if(author.equals("TikTok",true) || body.isBlank() || author.contains(" and ",true)) return null
        return Comment(author,body)
    }
    private fun register(sbn: StatusBarNotification): Pair<String, Comment>? {
        if(sbn.packageName !in packages || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0) return null
        val extras=sbn.notification.extras
        val comment=parse(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()) ?: return null
        val id=ContentHashing.hash("${sbn.key}|${comment.author}|${comment.text}")
        val now=System.currentTimeMillis();routes.entries.removeIf { it.value.until<now }
        val launch=sbn.notification.contentIntent
        if(launch!=null && launch.creatorPackage==sbn.packageName) routes[id]=Route(launch,now+24*3_600_000L)
        return id to comment
    }
    fun refreshRoute(sbn: StatusBarNotification) { register(sbn) }
    fun capture(sbn: StatusBarNotification, store: TikTokCommentInbox): String? {
        val (id,comment)=register(sbn) ?: return null
        return if(store.add(id,comment,sbn.postTime)) id else null
    }
    fun open(id: String): Boolean {
        if(routes[id]?.until?.let { it>System.currentTimeMillis() }!=true)
            co.sanaa.agent.services.AgentNotificationListenerService.instance?.refreshTikTokRoutes()
        val route=routes[id]?.takeIf { it.until>System.currentTimeMillis() } ?: return false
        return try { route.intent.send();true } catch(_:PendingIntent.CanceledException) { routes.remove(id);false }
    }
    fun worthReview(text: String): Boolean = text.any(Char::isLetter) &&
        (text.trim().length >= 12 || Regex("(?i)(\\?|\\b(price|cost|order|buy|book|size|where|how)\\b)").containsMatchIn(text)) &&
        !Regex("(?i)(follow.?for.?follow|follow me|https?://|www\\.)").containsMatchIn(text)
}

class TikTokCommentInbox(context: Context): SQLiteOpenHelper(context,"tiktok_comment_inbox.db",null,2) {
    override fun onCreate(db:SQLiteDatabase) { db.execSQL("CREATE TABLE comments(id TEXT PRIMARY KEY,author TEXT NOT NULL,body TEXT NOT NULL,observed_at INTEGER NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '',response TEXT NOT NULL DEFAULT '',reserved_at INTEGER NOT NULL DEFAULT 0)") }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        if(oldVersion<2) {
            db.execSQL("ALTER TABLE comments ADD COLUMN reserved_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE comments SET reserved_at=observed_at WHERE response!=''")
        }
    }
    @Synchronized fun add(id:String,comment:TikTokCommentNotifications.Comment,at:Long):Boolean {
        writableDatabase.delete("comments","observed_at<? AND state IN ('SKIPPED','VERIFIED')",arrayOf((System.currentTimeMillis()-30L*86400000).toString()))
        val values=android.content.ContentValues().apply { put("id",id);put("author",comment.author);put("body",comment.text);put("observed_at",at);put("state","NEW") }
        return writableDatabase.insertWithOnConflict("comments",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }
    fun get(id:String):JSONObject? = readableDatabase.rawQuery("SELECT author,body,state,response FROM comments WHERE id=?",arrayOf(id)).use {
        if(!it.moveToFirst()) null else JSONObject().put("author",it.getString(0)).put("body",it.getString(1)).put("state",it.getString(2)).put("response",it.getString(3))
    }
    @Synchronized fun reserve(id:String,response:String):Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val now=System.currentTimeMillis()
            val recent=db.rawQuery("SELECT count(*) FROM comments WHERE reserved_at>? AND response!=''",arrayOf((now-86400000).toString())).use { it.moveToFirst();it.getInt(0) }
            if(recent>=12) {
                db.execSQL("UPDATE comments SET state='NEEDS_REVIEW',reason='Daily own-ad reply limit reached' WHERE id=? AND state='NEW'",arrayOf(id))
                db.setTransactionSuccessful()
                return false
            }
            val values=android.content.ContentValues().apply { put("state","RESERVED");put("response",response);put("reserved_at",now) }
            val reserved=db.update("comments",values,"id=? AND state='NEW'",arrayOf(id))==1
            db.setTransactionSuccessful()
            return reserved
        } finally { db.endTransaction() }
    }
    fun outcome(id:String,state:String,reason:String) {
        require(state in setOf("SKIPPED","NEEDS_REVIEW","VERIFIED","UNCERTAIN","FAILED"))
        writableDatabase.execSQL("UPDATE comments SET state=?,reason=? WHERE id=?",arrayOf(state,reason.take(300),id))
    }
    fun summary():String = readableDatabase.rawQuery("SELECT state,count(*) FROM comments GROUP BY state",null).use { c ->
        buildList { while(c.moveToNext()) add("${c.getString(0).lowercase().replace('_',' ')}: ${c.getInt(1)}") }.joinToString(" · ").ifBlank { "No own-video comment notifications received yet" }
    }
}
