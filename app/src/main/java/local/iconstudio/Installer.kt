package local.iconstudio
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import java.io.File

object Installer {
    fun uninstall(context:Context,p:Project){
        context.startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:local.prism.pack.p${p.id}")))
    }
    fun install(context:Context,file:File,expectedName:String){
        val info=context.packageManager.getPackageArchiveInfo(file.path,0) ?: error("Build the pack again before installing.")
        val app=info.applicationInfo ?: error("Pack metadata missing")
        app.sourceDir=file.path;app.publicSourceDir=file.path
        check(app.loadLabel(context.packageManager).toString()==expectedName.trim().ifEmpty {"New collection"}){"This saved APK has an older name. Build the pack again."}
        if(!context.packageManager.canRequestPackageInstalls()){
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")));return
        }
        val installer=context.packageManager.packageInstaller
        val id=installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {setAppLabel(expectedName);setAppPackageName(info.packageName)})
        installer.openSession(id).use { session ->
            file.inputStream().use { input -> session.openWrite("pack.apk",0,file.length()).use { output -> input.copyTo(output);session.fsync(output) } }
            val callback=PendingIntent.getBroadcast(context,id,Intent(context,InstallResult::class.java),PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(callback.intentSender)
        }
    }
}
class InstallResult:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            @Suppress("DEPRECATION") val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
        }else android.widget.Toast.makeText(context,if(status==PackageInstaller.STATUS_SUCCESS)"Pack installed. Select it in Theme Park." else "Installation cancelled or failed. Your project is saved. ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}",android.widget.Toast.LENGTH_LONG).show()
    }
}
