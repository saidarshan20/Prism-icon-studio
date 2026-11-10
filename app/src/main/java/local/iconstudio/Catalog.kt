package local.iconstudio
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import local.iconstudio.core.Matcher

object Catalog {
    fun load(context: Context): List<Matcher.App> = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { Matcher.App(it.loadLabel(context.packageManager).toString(),it.activityInfo.packageName,ComponentName(it.activityInfo.packageName,it.activityInfo.name).flattenToString()) }
        .distinctBy { it.component }.sortedBy { it.label.lowercase() }
    fun match(p:Project,apps:List<Matcher.App>,rules:Map<String,String>,preferredIcons:Map<String,String> = emptyMap()) {
        for(icon in p.icons){
            if(icon.component!=null) {
                if(apps.none { it.component==icon.component }){icon.component=null;icon.reason="App removed or launcher changed — review required"}
                continue
            }
            val matches=Matcher.match(icon.name.substringAfterLast('/'),apps,rules)
            val first=matches.firstOrNull()
            icon.reason=first?.reason ?: "No confident match — choose or skip"
            if(matches.size==1 && first!!.automatic)icon.component=first.app.component
        }
        // Different artwork for the same app is an alternate, never silently overwrite it.
        val grouped=p.icons.filter { !it.excluded && it.component!=null }.groupBy { it.component }
        for(group in grouped.values.filter { it.size>1 }){
            val manual=group.filter {it.reason=="Your choice"}
            val remembered=group.filter {it.reason=="Your remembered choice"}
            val preferred=remembered.filter {Matcher.normalize(it.name)==preferredIcons[it.component]}
            val chosen=when {
                manual.size==1->manual.single()
                manual.isNotEmpty()->null
                preferred.size==1->preferred.single()
                preferred.isNotEmpty()->null
                remembered.size==1->remembered.single()
                else->null
            }
            for(icon in group)if(icon!==chosen){icon.component=null;icon.reason="Multiple artwork choices - choose your preferred icon"}
        }
    }
}
