package local.iconstudio
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import local.iconstudio.core.Matcher

object Catalog {
    fun load(context: Context): List<Matcher.App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .filterNot { it.activityInfo.packageName == "local.iconstudio.preview" && context.packageName != "local.iconstudio.preview" }
            .groupBy { it.activityInfo.packageName }
            .flatMap { (pkg, activities) ->
                val primary = if (activities.size > 1) pm.getLaunchIntentForPackage(pkg)?.component else null
                activities.map { activity ->
                    val component = ComponentName(pkg, activity.activityInfo.name)
                    Matcher.App(activity.loadLabel(pm).toString(), pkg, component.flattenToString(), primary == component)
                }
            }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
    }

    fun withoutArtwork(project: Project, apps: List<Matcher.App>): List<Matcher.App> {
        val assigned = project.icons.filterNot { it.excluded }.mapNotNull { it.component }.toSet()
        return apps.filterNot { it.component in assigned }
    }

    fun match(p:Project,apps:List<Matcher.App>,rules:Map<String,String>,preferredIcons:Map<String,String> = emptyMap()) {
        val installed = apps.associateBy { it.component }
        for(icon in p.icons){
            if (icon.excluded) continue
            if (icon.component != null && icon.reason == "Your choice") {
                if (icon.component !in installed) {
                    icon.component = null
                    icon.reason = "App removed or launcher changed - review required"
                }
                continue
            }
            val filename = icon.name.substringAfterLast('/')
            val matches=Matcher.match(filename,apps,rules)
            val first=matches.firstOrNull()
            icon.reason=first?.reason ?: "No confident match — choose or skip"
            icon.component=first?.takeIf { matches.size == 1 && it.automatic }?.app?.component
        }
        // Different artwork for the same app is an alternate, never silently overwrite it.
        val grouped=p.icons.filter { !it.excluded && it.component!=null }.groupBy { it.component }
        for((component, group) in grouped.filterValues { it.size > 1 }){
            val manual=group.filter {it.reason=="Your choice"}
            val remembered=group.filter {it.reason=="Your remembered choice"}
            val preferred=remembered.filter {Matcher.normalize(it.name)==preferredIcons[component]}
            val packageName = installed.getValue(requireNotNull(component)).pkg
            val packDefaults = group.filter { Matcher.defaultArtworkPriority(it.name, packageName) > 0 }
            val chosen=when {
                manual.size==1->manual.single()
                manual.isNotEmpty()->null
                preferred.size==1->preferred.single()
                preferred.isNotEmpty()->null
                remembered.size==1->remembered.single()
                remembered.isNotEmpty()->null
                packDefaults.size==1->packDefaults.single()
                else->null
            }
            for(icon in group)if(icon!==chosen){icon.component=null;icon.reason="Multiple artwork choices - choose your preferred icon"}
        }
    }
}
