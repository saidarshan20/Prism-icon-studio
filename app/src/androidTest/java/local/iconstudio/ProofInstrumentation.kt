package local.iconstudio
import android.app.Instrumentation
import android.os.Bundle
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import local.iconstudio.core.Matcher
import java.io.File
import java.util.zip.ZipFile

class ProofInstrumentation:Instrumentation(){
    private var reviewOnly=false
    private var logoOnly=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);logoOnly=arguments?.getString("logoOnly")=="true";reviewOnly=arguments?.getString("reviewOnly")=="true";start()}
    override fun onStart(){
        if(reviewOnly){
            val result=Bundle()
            try {
                val c=targetContext;val store=ProjectStore(c)
                val component="com.google.android.apps.maps/TestFixture"
                val maps=listOf(Matcher.App("Maps","com.google.android.apps.maps",component))
                fun fixture()=Project(name="Maps preference regression",icons=mutableListOf(Icon("google","Google-Maps.png","fixture"),Icon("maps","Maps.png","fixture")))
                store.remember("Google-Maps.png",component)
                val p=fixture();Catalog.match(p,maps,store.rules(),store.preferredIcons())
                check(p.icons[0].component==component && p.icons[1].component==null){"Remembered Google Maps artwork lost to automatic Maps match"}
                store.save(p)
                val reopened=ProjectStore(c);val saved=reopened.list().first {it.id==p.id}
                Catalog.match(saved,maps,reopened.rules(),reopened.preferredIcons())
                check(saved.icons[0].component==component && saved.icons[1].component==null){"Preference lost after reopen/rematch"}
                store.remember("Maps.png",component)
                val changed=fixture();val next=ProjectStore(c)
                Catalog.match(changed,maps,next.rules(),next.preferredIcons())
                check(changed.icons[1].component==component && changed.icons[0].component==null){"Latest preferred name did not win on new import"}
                changed.icons[0].component=component;changed.icons[0].reason="Your choice";changed.icons[1].component=null
                Catalog.match(changed,maps,next.rules(),next.preferredIcons())
                check(changed.icons[0].component==component && changed.icons[1].component==null){"Manual assignment overridden"}
                val duplicate=Project(icons=mutableListOf(Icon("a","a/Maps.png","fixture"),Icon("b","b/Maps.PNG","fixture")))
                Catalog.match(duplicate,maps,next.rules(),next.preferredIcons())
                check(duplicate.icons.all {it.component==null}){"Indistinguishable alternates must remain reviewable"}
                android.database.sqlite.SQLiteDatabase.openDatabase(File(c.filesDir,"studio.db").path,null,0).use {db->db.execSQL("DELETE FROM preferred_icons WHERE component=?",arrayOf(component))}
                check(ProjectStore(c).preferredIcons()[component]=="maps"){"Legacy rule preference migration failed"}
                store.delete(p)
                result.putString("result","PASS: remembered-vs-automatic Maps collision, reopen/rematch persistence, latest choice on new imports, manual priority, duplicate artwork review, and legacy preference migration")
                finish(0,result)
            }catch(t:Throwable){result.putString("error",android.util.Log.getStackTraceString(t));finish(1,result)}
            return
        }
        if(logoOnly){
            val result=Bundle()
            try {
                val c=targetContext;val pm=c.packageManager
                val intent=pm.getLaunchIntentForPackage(c.packageName) ?: error("Launcher missing")
                val activity=pm.resolveActivity(intent,0) ?: error("Launcher does not resolve")
                val direct=c.getDrawable(local.iconstudio.R.drawable.prism_logo) ?: error("Logo missing")
                val icons=listOf("resource" to direct,"application" to pm.getApplicationIcon(c.packageName),"launcher" to activity.loadIcon(pm))
                val folder=c.getExternalFilesDir("icon-diagnostic")!!.apply{mkdirs()}
                val pixels=mutableListOf<IntArray>()
                for((name,icon) in icons){
                    val bitmap=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888)
                    icon.setBounds(0,0,384,384);icon.draw(android.graphics.Canvas(bitmap))
                    File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    val data=IntArray(384*384);bitmap.getPixels(data,0,384,0,0,384,384);pixels.add(data);bitmap.recycle()
                }
                check(pixels[0].contentEquals(pixels[1])){"Application icon differs from logo"}
                check(pixels[0].contentEquals(pixels[2])){"Launcher icon differs from logo"}
                result.putString("result","PASS: installed application and resolved launcher icons exactly match logo pixels. Launcher=${activity.activityInfo.name}; output=$folder")
                finish(0,result)
            }catch(t:Throwable){result.putString("error",android.util.Log.getStackTraceString(t));finish(1,result)}
            return
        }
        val result=Bundle()
        try{runBlocking {
            val c=targetContext;val store=ProjectStore(c)
            val apps=Catalog.load(c)
            check(apps.isNotEmpty()){"Launcher catalog empty"}
            val fixture=listOf(Matcher.App("Play Store","com.android.vending","com.android.vending/Main"),Matcher.App("Internet","com.sec.android.app.sbrowser","com.sec.android.app.sbrowser/Main"),Matcher.App("Dating","example.dating","example.dating/Main"))
            check(Matcher.match("App-Store.PNG",fixture,emptyMap()).single().app.pkg=="com.android.vending")
            check(Matcher.match("SAFARI.jpg",fixture,emptyMap()).single().app.pkg=="com.sec.android.app.sbrowser")
            check(!Matcher.match("Dating.png",fixture,emptyMap()).single().automatic)
            val solid=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.RED)}
            for(shape in listOf("Rounded","Circle","Device")){
                val rendered=IconRenderer.render(solid,shape,0.8f)
                check(android.graphics.Color.alpha(rendered.getPixel(0,0))==0)
                check(android.graphics.Color.alpha(rendered.getPixel(20,20))==0)
                check(android.graphics.Color.alpha(rendered.getPixel(96,96))==255)
                rendered.recycle()
            }
            val padded=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(padded).drawRect(32f,32f,160f,160f,android.graphics.Paint().apply{color=android.graphics.Color.RED})
            val full=IconRenderer.render(solid,"Rounded",0.90f)
            val normalized=IconRenderer.render(padded,"Rounded",0.90f,true)
            val first=IntArray(192*192);val second=IntArray(192*192)
            full.getPixels(first,0,192,0,0,192,192);normalized.getPixels(second,0,192,0,0,192,192)
            fun extent(pixels:IntArray):android.graphics.Rect {
                var l=192;var t=192;var r=-1;var b=-1
                pixels.forEachIndexed {i,color->if(android.graphics.Color.alpha(color)>=128){val x=i%192;val y=i/192;l=minOf(l,x);t=minOf(t,y);r=maxOf(r,x);b=maxOf(b,y)}}
                return android.graphics.Rect(l,t,r+1,b+1)
            }
            val a=extent(first);val b=extent(second)
            check(kotlin.math.abs(a.width()-b.width())<=1 && kotlin.math.abs(a.height()-b.height())<=1 && kotlin.math.abs(a.left-b.left)<=1 && kotlin.math.abs(a.top-b.top)<=1){"Visible sizes differ: $a vs $b"}
            check(a.width()>=170 && a.width()<=174){"90% footprint incorrect: $a"}
            full.recycle();normalized.recycle();padded.recycle()
            solid.recycle()
            val export=c.getExternalFilesDir("proof")!!.apply{mkdirs()}
            val ids=mutableListOf<String>()
            fun verifyBrand(apk:File,p:Project){
                val own=c.packageManager.getLaunchIntentForPackage(c.packageName)!!.component!!.flattenToString()
                ZipFile(apk).use {z->
                    val parser=android.util.Xml.newPullParser()
                    parser.setInput(z.getInputStream(z.getEntry("assets/appfilter.xml")),"UTF-8")
                    var name:String?=null
                    while(parser.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
                        if(parser.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && parser.name=="item" && parser.getAttributeValue(null,"component")=="ComponentInfo{$own}")name=parser.getAttributeValue(null,"drawable")
                        parser.next()
                    }
                    check(name!=null){"Prism missing from generated pack"}
                    val actual=z.getInputStream(z.getEntry("res/drawable-nodpi-v4/$name.png")).use {android.graphics.BitmapFactory.decodeStream(it)}
                    val original=c.assets.open("prism-logo.png").use {android.graphics.BitmapFactory.decodeStream(it)}
                    val expected=IconRenderer.render(original,p.shape,p.scale)
                    val a=IntArray(192*192);val b=IntArray(192*192)
                    actual.getPixels(a,0,192,0,0,192,192);expected.getPixels(b,0,192,0,0,192,192)
                    check(a.contentEquals(b)){"Pack logo differs from pristine bundled image"}
                    actual.recycle();expected.recycle();original.recycle()
                }
            }
            for(n in 1..2){
                val p=Project(name="Prism Runtime Proof $n",shape="Rounded",scale=0.84f,includeOriginals=n!=1)
                val image=File(store.directory(p),"proof.png")
                Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff8957e5.toInt());image.outputStream().use {compress(Bitmap.CompressFormat.PNG,100,it)};recycle()}
                p.icons.add(Icon("proof","App-Store.png",image.path,apps.first().component,"Runtime proof"))
                val builder=PackBuilder(c,store)
                val first=builder.build(p){}
                verifyBrand(first,p)
                first.copyTo(File(export,"proof-$n-v1.apk"),true)
                check(c.packageManager.getPackageArchiveInfo(first.path,0)?.packageName=="local.prism.pack.p${p.id}"){"Android rejected generated package"}
                ZipFile(first).use{z->check(z.getInputStream(z.getEntry("res/drawable-nodpi-v4/slot_0000.png")).readBytes().size>100)}
                if(n==1){p.name="Renamed Runtime Proof";val second=builder.build(p){};second.copyTo(File(export,"proof-1-v2.apk"),true);check(p.version==2)}
                check(store.list().first {it.id==p.id}.shape=="Rounded")
                check(store.list().first {it.id==p.id}.scale==0.84f)
                ids.add(p.id)
            }
            val fallback=Project(name="Prism All Apps Proof")
            val fallbackApk=PackBuilder(c,store).build(fallback){}
            verifyBrand(fallbackApk,fallback)
            ZipFile(fallbackApk).use {z->
                val filter=z.getInputStream(z.getEntry("assets/appfilter.xml")).readBytes().toString(Charsets.UTF_8)
                apps.forEach {check(filter.contains("ComponentInfo{${it.component}}")) {"Missing fallback: ${it.component}"}}
            }
            check(store.list().first {it.id==fallback.id}.includeOriginals)
            val ui=Project(name="Scrolling review proof")
            val uiImage=File(store.directory(ui),"fixture.png")
            Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff8957e5.toInt());uiImage.outputStream().use {compress(Bitmap.CompressFormat.PNG,100,it)};recycle()}
            repeat(30){ui.icons.add(Icon("ui-$it","Review image $it.png",uiImage.path))}
            store.save(ui)
            val names=listOf("Calendar","Clone","Maps","Weather","Phone","Camera","Calculator","Settings")
            val named=names.map {Matcher.App(it,"test.${it.lowercase()}","test.${it.lowercase()}/Main")}
            val matched=Project(name="Matching regression")
            names.forEachIndexed {i,n->matched.icons.add(Icon("match-$i","$n.png",uiImage.path))}
            Catalog.match(matched,named,emptyMap())
            check(matched.icons.all {it.component!=null})
            matched.icons[0].reason="Your choice"
            matched.icons.add(Icon("alternate","Calendar.png",uiImage.path))
            Catalog.match(matched,named,emptyMap())
            check(matched.icons[0].component!=null && matched.icons.last().component==null)
            check(ImportNames.clean("  Demon Slayer.zip  ",true)=="Demon Slayer")
            check(ImportNames.clean("Icons",false)=="Icons")
            check(ImportNames.clean(null,false)=="New collection")
            check(ImportNames.clean("  .ZIP ",true)=="New collection")
            val disposable=Project(name="Delete fixture")
            val deleteFile=File(store.directory(disposable),"only-this-project.txt").apply {writeText("fixture")}
            store.save(disposable)
            store.delete(disposable)
            check(!deleteFile.exists() && store.list().none {it.id==disposable.id})
            check(uiImage.exists() && store.list().any {it.id==ui.id})
            check(java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias("prism.pack.signing"))
            val icon=c.packageManager.getApplicationIcon(c.packageName)
            val rendered=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888)
            icon.setBounds(0,0,384,384);icon.draw(android.graphics.Canvas(rendered))
            File(export,"prism-app-icon.png").outputStream().use {rendered.compress(Bitmap.CompressFormat.PNG,100,it)};rendered.recycle()
            store.remember("Test persistence.png",fixture[0].component)
            check(ProjectStore(c).rules()["testpersistence"]==fixture[0].component)
            result.putString("result","PASS: aliases, ambiguity, installed catalog, resource patching, Android archive parsing, on-device signing and verification, two identities, rebuild versions, SQLite persistence. IDs=$ids; APKs=$export")
        };finish(0,result)}catch(t:Throwable){result.putString("error",android.util.Log.getStackTraceString(t));finish(1,result)}
    }
}
