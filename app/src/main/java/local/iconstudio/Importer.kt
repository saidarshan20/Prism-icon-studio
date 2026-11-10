package local.iconstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.*
import java.util.zip.ZipInputStream

/** Both adapters feed the same bounded decoder. Paths from imports never become disk paths. */
class Importer(private val context: Context, private val store: ProjectStore) {
    private var total=0L
    private var entries=0
    private var skipped=0
    private val maxFile=24L*1024*1024
    private val maxTotal=512L*1024*1024
    private fun hidden(path: String)=path.split('/','\\').any { it=="__MACOSX" || it.startsWith('.') }
    private fun excluded(path: String): Boolean = path.split('/','\\').dropLast(1).any {
        val n=it.lowercase().removeSuffix("-wallpapers-clan-com")
        Regex("(^|[-_ ])(wallpapers?|widgets?)($|[-_ ])").containsMatchIn(n)
    }
    private fun safe(path: String) { require(!path.startsWith('/') && !path.contains('\\') && !path.contains(':') && path.split('/').none { it==".." }) { "Unsafe ZIP path. Choose an archive without parent-directory entries." } }
    private suspend fun copyBounded(input: InputStream, out: OutputStream) {
        val b=ByteArray(32768);var size=0L
        while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;size+=n;total+=n;check(size<=maxFile && total<=maxTotal){"Import exceeds 24 MB per file or 512 MB expanded. Choose a smaller collection."};out.write(b,0,n)}
    }
    private suspend fun consume(p: Project, name: String, input: InputStream) {
        entries++;require(entries<=4096){"Too many files (limit 4,096). Choose an icon subfolder."}
        if(hidden(name)||!Regex("(?i).*\\.(png|jpe?g|webp)$").matches(name)){copyBounded(input,object:OutputStream(){ override fun write(b:Int){}; override fun write(b:ByteArray,off:Int,len:Int){} });skipped++;return}
        val id=java.util.UUID.randomUUID().toString();val raw=File(store.directory(p),"$id.tmp")
        try {
            raw.outputStream().use { copyBounded(input,it) }
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(raw.path,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000){"Invalid or oversized image: $name"}
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
            val bitmap=BitmapFactory.decodeFile(raw.path,BitmapFactory.Options().apply {inSampleSize=sample}) ?: error("Unreadable image: $name")
            val dest=File(store.directory(p),"$id.png")
            dest.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            bitmap.recycle()
            p.icons.add(Icon(id,name,dest.path,excluded=excluded(name)))
        } catch(e: IllegalArgumentException) {
            if(total>maxTotal || raw.length()>maxFile)throw e
            skipped++
        } finally {raw.delete()}
    }
    suspend fun folder(uri: Uri,p: Project,progress:(String)->Unit): Int {
        val root=DocumentFile.fromTreeUri(context,uri) ?: error("Cannot access this folder. Select it again.")
        suspend fun visit(dir: DocumentFile,path:String,depth:Int) {
            require(depth<=24){"Folder nesting exceeds 24 levels"}
            for(file in dir.listFiles()) {
                currentCoroutineContext().ensureActive()
                val name=path+(file.name?:"unnamed")
                if(hidden(name))continue
                if(file.isDirectory)visit(file,"$name/",depth+1)
                else { progress("Reading ${p.icons.size} images • ${file.name}");context.contentResolver.openInputStream(file.uri)?.use { consume(p,name,it) } ?: error("Folder access was lost. Select the folder again.") }
            }
        }
        visit(root,"",0);require(p.icons.isNotEmpty()){ "No readable PNG, JPEG or static WebP images found." };return skipped
    }
    suspend fun zip(uri: Uri,p:Project,progress:(String)->Unit):Int {
        val input=context.contentResolver.openInputStream(uri) ?: error("Cannot open ZIP")
        ZipInputStream(BufferedInputStream(input)).use { z ->
            while(true){currentCoroutineContext().ensureActive();val e=z.nextEntry?:break;safe(e.name);if(!e.isDirectory){progress("Reading ${p.icons.size} images • ${e.name}");consume(p,e.name,z)};z.closeEntry()}
        }
        require(p.icons.isNotEmpty()){ "No readable PNG, JPEG or static WebP images found." };return skipped
    }
}
