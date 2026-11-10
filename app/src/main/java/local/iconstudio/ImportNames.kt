package local.iconstudio
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

object ImportNames {
    fun clean(name:String?,zip:Boolean):String {
        val value=name?.trim().orEmpty().let {if(zip)it.replace(Regex("(?i)\\.zip$"),"") else it}.trim().take(80)
        return value.ifEmpty {"New collection"}
    }
    fun fromUri(context:Context,uri:Uri,folder:Boolean):String {
        val name=runCatching {
            if(folder)DocumentFile.fromTreeUri(context,uri)?.name
            else context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {c->if(c.moveToFirst())c.getString(0) else null}
        }.getOrNull()
        return clean(name,!folder)
    }
}
