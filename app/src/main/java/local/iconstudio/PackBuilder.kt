package local.iconstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.iconstudio.core.BinaryResources
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.*

class PackBuilder(private val context: Context, private val store: ProjectStore) {
    suspend fun build(p: Project, progress: (String)->Unit): File {
        val selected = p.icons.filter { !it.excluded && it.component != null }.toMutableList()
        val originals = if (p.missingIconStyle == MissingIconStyle.UNASSIGNED) emptyList()
            else Catalog.withoutArtwork(p, Catalog.load(context))
        val palette = if (p.missingIconStyle == MissingIconStyle.THEMED && originals.isNotEmpty()) {
            progress("Reading collection colors")
            PackPalette.fromArtwork(p.icons.filterNot { it.excluded }.map { it.path })
        } else null
        val appsByComponent = originals.associateBy { it.component }
        originals.forEach { app -> selected.add(Icon("native:${app.component}", app.label, "", app.component, "Fallback app icon")) }
        require(selected.isNotEmpty()){ "Assign at least one icon or enable original icons." }
        if (p.missingIconStyle == MissingIconStyle.UNASSIGNED) {
            val ownComponent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
                ?: error("Prism launcher entry is unavailable")
            val component = ownComponent.flattenToString()
            // Preserve the bundled launcher icon for partial packs, unless the user picked artwork.
            if (selected.none { it.component == component }) {
                selected.add(Icon("prism:brand", "Prism", "", component, "Bundled Prism logo"))
            }
        }
        require(selected.size<=512){ "This prototype supports 512 launcher activities including originals. Disable original-icon inclusion or reduce assignments." }
        require(selected.map { it.component }.distinct().size==selected.size){ "Choose one icon per launcher activity." }
        val pkg="local.prism.pack.p${p.id}"
        val version=p.version+1
        val buildName=p.name.trim().ifEmpty {"New collection"}.take(80)
        val raw=File(store.directory(p),"unsigned.tmp")
        val output=File(store.directory(p),"pack-$version.apk")
        val replacements=selected.mapIndexed { i,icon -> "ComponentInfo{prism.placeholder%04d/prism.placeholder%04d.Main}".format(i,i) to "ComponentInfo{${icon.component}}" }.toMap()
        fun replace(s:String)=when(s){BinaryResources.TEMPLATE_PACKAGE->pkg;"PRISM_TEMPLATE_LABEL"->buildName;else->replacements[s]?:s}
        try {
            ZipInputStream(context.assets.open("template.apk")).use { input -> CountingOutput(raw.outputStream()).use { counted -> ZipOutputStream(counted).use { out ->
                while(true){
                    currentCoroutineContext().ensureActive()
                    val entry=input.nextEntry?:break
                    var bytes=input.readBytes()
                    val slot=Regex("res/drawable-nodpi[^/]*/slot_(\\d+)\\.png").matchEntire(entry.name)?.groupValues?.get(1)?.toInt()
                    if(slot!=null && slot<selected.size){
                        progress("Preparing images ${slot+1}/${selected.size}")
                        val icon=selected[slot]
                        val app = appsByComponent[icon.component]
                        val bitmap = if (app != null) {
                            IconRenderer.renderFallback(IconRenderer.installedIcon(context, app.component),
                                p.shape, p.scale, palette?.forPackage(app.pkg))
                        } else {
                            val image = if (icon.id == "prism:brand") {
                                IconRenderer.loadPrismLogo(context)
                            } else BitmapFactory.decodeFile(icon.path) ?: error("Image missing. Import it again.")
                            try { IconRenderer.render(image, p.shape, p.scale) } finally { image.recycle() }
                        }
                        bytes = try {
                            java.io.ByteArrayOutputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                it.toByteArray()
                            }
                        } finally { bitmap.recycle() }
                    } else if(entry.name=="AndroidManifest.xml" || entry.name=="resources.arsc" || entry.name=="res/xml/appfilter.xml") {
                        progress("Generating resource mappings")
                        bytes=BinaryResources.rewrite(bytes,::replace,pkg,version)
                    } else if(entry.name=="assets/appfilter.xml") {
                        var xml=bytes.toString(Charsets.UTF_8);replacements.forEach { (a,b)->xml=xml.replace(a,b) };bytes=xml.toByteArray()
                    }
                    val dest=ZipEntry(entry.name)
                    dest.method=ZipEntry.STORED;dest.size=bytes.size.toLong();dest.compressedSize=dest.size
                    dest.crc=CRC32().apply { update(bytes) }.value
                    val offset=counted.count+30+entry.name.toByteArray(Charsets.UTF_8).size
                    val padding=((4-offset%4)%4).toInt()
                    if(padding!=0)dest.extra=ByteArray(4+padding).apply { this[0]=0x35;this[1]=0xd9.toByte();this[2]=padding.toByte() }
                    out.putNextEntry(dest);out.write(bytes);out.closeEntry()
                }
            }}}
            progress("Signing on this device")
            val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias="prism.pack.signing"
            if(!ks.containsAlias(alias)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build());generateKeyPair()
            }
            val config=ApkSigner.SignerConfig.Builder("Prism",ks.getKey(alias,null) as PrivateKey,listOf(ks.getCertificate(alias) as X509Certificate)).build()
            ApkSigner.Builder(listOf(config)).setInputApk(raw).setOutputApk(output).setMinSdkVersion(28).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).build().sign()
            check(ApkVerifier.Builder(output).setMinCheckedPlatformVersion(28).build().verify().isVerified){ "Generated signature failed verification." }
            val info=context.packageManager.getPackageArchiveInfo(output.path,0) ?: error("Android cannot read generated pack")
            val application=info.applicationInfo ?: error("Generated pack has no application metadata")
            application.sourceDir=output.path;application.publicSourceDir=output.path
            check(application.loadLabel(context.packageManager).toString()==buildName){"Generated pack name verification failed"}
            val built = p.copy(name = buildName, version = version, lastApk = output.path)
            built.lastBuildFingerprint = built.buildFingerprint()
            store.built(built)
            p.name = built.name
            p.version = built.version
            p.lastApk = built.lastApk
            p.lastBuildFingerprint = built.lastBuildFingerprint
            progress("Ready to install")
            return output
        } catch(e:Exception){output.delete();throw e}finally{raw.delete()}
    }
}

private class CountingOutput(out:java.io.OutputStream):java.io.FilterOutputStream(out){
    var count=0L
    override fun write(b:Int){out.write(b);count++}
    override fun write(b:ByteArray,off:Int,len:Int){out.write(b,off,len);count+=len}
}
