package local.iconstudio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class Icon(val id: String, val name: String, val path: String, var component: String? = null, var reason: String = "Needs review", var excluded: Boolean = false)

enum class MissingIconStyle(val label: String) {
    UNASSIGNED("Leave unassigned"),
    ORIGINAL("Resize originals"),
    THEMED("Match this pack"),
}

data class Project(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    var name: String = "My icon pack",
    var version: Int = 0,
    val icons: MutableList<Icon> = mutableListOf(),
    var lastApk: String? = null,
    var shape: String = "Device",
    var scale: Float = 0.90f,
    var missingIconStyle: MissingIconStyle = MissingIconStyle.ORIGINAL,
    var lastBuildFingerprint: String? = null,
) {
    fun buildFingerprint(): String {
        val inputs = JSONArray(listOf(name, shape, scale.toDouble(), missingIconStyle.name))
        if (missingIconStyle == MissingIconStyle.THEMED) inputs.put("themed-prism-logo")
        icons.forEach { icon ->
            inputs.put(JSONArray(listOf(icon.id, icon.component ?: "", icon.excluded)))
        }
        return MessageDigest.getInstance("SHA-256").digest(inputs.toString().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

class ProjectStore(context: Context) {
    val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val db = SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, "studio.db"), null).apply {
        execSQL("CREATE TABLE IF NOT EXISTS projects (id TEXT PRIMARY KEY, body TEXT NOT NULL)")
        execSQL("CREATE TABLE IF NOT EXISTS rules (name TEXT PRIMARY KEY, component TEXT NOT NULL)")
        execSQL("CREATE TABLE IF NOT EXISTS preferred_icons (component TEXT PRIMARY KEY, name TEXT NOT NULL)")
        execSQL("INSERT OR IGNORE INTO preferred_icons(component,name) SELECT component,name FROM rules ORDER BY rowid DESC")
        execSQL("CREATE TABLE IF NOT EXISTS builds (project TEXT, version INTEGER, path TEXT, created INTEGER)")
    }
    fun directory(p: Project) = File(root,p.id).apply { mkdirs() }
    @Synchronized fun save(p: Project) {
        val json = JSONObject().put("id", p.id).put("name", p.name).put("version",p.version).put("lastApk",p.lastApk).put("shape",p.shape).put("scale",p.scale.toDouble())
            .put("missingIconStyle", p.missingIconStyle.name)
            .put("lastBuildFingerprint", p.lastBuildFingerprint ?: JSONObject.NULL)
        json.put("icons",JSONArray(p.icons.map { JSONObject().put("id",it.id).put("name",it.name).put("path",it.path).put("component",it.component).put("reason",it.reason).put("excluded",it.excluded) }))
        db.execSQL("INSERT OR REPLACE INTO projects VALUES (?,?)",arrayOf(p.id,json.toString()))
    }
    @Synchronized fun list(): List<Project> {
        val list=mutableListOf<Project>()
        db.rawQuery("SELECT body FROM projects ORDER BY rowid DESC",null).use { c -> while(c.moveToNext()) {
            val j=JSONObject(c.getString(0));val p=Project(j.getString("id"),j.getString("name"),j.getInt("version"),lastApk=j.optString("lastApk").takeIf { it.isNotEmpty() && it!="null" })
            p.shape=j.optString("shape","Original");p.scale=j.optDouble("scale",1.0).toFloat().coerceIn(0.6f,1f)
            p.missingIconStyle = MissingIconStyle.entries.firstOrNull { it.name == j.optString("missingIconStyle") }
                ?: if (j.optBoolean("includeOriginals", true)) MissingIconStyle.ORIGINAL else MissingIconStyle.UNASSIGNED
            val icons=j.getJSONArray("icons");for(i in 0 until icons.length()) { val a=icons.getJSONObject(i);p.icons.add(Icon(a.getString("id"),a.getString("name"),a.getString("path"),a.optString("component").takeIf { it.isNotEmpty()&&it!="null" },a.getString("reason"),a.optBoolean("excluded"))) }
            p.lastBuildFingerprint = j.optString("lastBuildFingerprint").takeIf { it.isNotBlank() && it != "null" }
            list.add(p)
        }}
        return list
    }
    @Synchronized fun remember(name: String, component: String) {
        val normalized=local.iconstudio.core.Matcher.normalize(name)
        db.beginTransaction();try {
            db.execSQL("INSERT OR REPLACE INTO rules VALUES (?,?)",arrayOf(normalized,component))
            db.execSQL("INSERT OR REPLACE INTO preferred_icons VALUES (?,?)",arrayOf(component,normalized))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    @Synchronized fun preferredIcons():Map<String,String> {
        val result=mutableMapOf<String,String>()
        db.rawQuery("SELECT component,name FROM preferred_icons",null).use {c->while(c.moveToNext())result[c.getString(0)]=c.getString(1)}
        return result
    }
    @Synchronized fun rules(): Map<String,String> { val r=mutableMapOf<String,String>();db.rawQuery("SELECT name, component FROM rules",null).use { c -> while(c.moveToNext())r[c.getString(0)]=c.getString(1) };return r }
    @Synchronized fun clearRules() { db.beginTransaction();try{db.execSQL("DELETE FROM rules");db.execSQL("DELETE FROM preferred_icons");db.setTransactionSuccessful()}finally{db.endTransaction()} }
    @Synchronized fun built(p: Project) { db.beginTransaction();try {save(p);db.execSQL("INSERT INTO builds VALUES (?,?,?,?)",arrayOf(p.id,p.version,p.lastApk,System.currentTimeMillis()));db.setTransactionSuccessful()}finally{db.endTransaction()} }
    @Synchronized fun delete(p:Project) {
        require(Regex("[a-f0-9]{32}").matches(p.id)){"Invalid project identity"}
        val folder=File(root,p.id).canonicalFile
        check(folder.parentFile==root.canonicalFile){"Invalid project directory"}
        check(!folder.exists() || folder.deleteRecursively()){"Some project files could not be deleted. Try again."}
        db.beginTransaction();try {
            db.execSQL("DELETE FROM builds WHERE project=?",arrayOf(p.id))
            db.execSQL("DELETE FROM projects WHERE id=?",arrayOf(p.id));db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun cleanBuilds(p: Project) { directory(p).listFiles()?.filter { it.extension in listOf("apk","tmp") && it.absolutePath!=p.lastApk }?.forEach { it.delete() } }
}
