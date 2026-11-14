package local.iconstudio

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import local.iconstudio.core.Matcher

private const val THEMED_APPS = "Themed apps"

class StudioModel(app:Application):AndroidViewModel(app){
    val store=ProjectStore(app)
    var projects by mutableStateOf(store.list());var current by mutableStateOf<Project?>(null)
    var apps by mutableStateOf<List<Matcher.App>>(emptyList())
    var appsLoaded by mutableStateOf(false)
    var message by mutableStateOf("");var busy by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
    private var job:Job?=null
    init {
        viewModelScope.launch {
            try {
                apps = withContext(Dispatchers.IO) { Catalog.load(app) }
                appsLoaded = true
            } catch (_: Exception) {
                message = "Could not load installed apps. Refresh automatic matches to try again."
            }
        }
    }
    fun save(){current?.let {store.save(it)};projects=store.list();revision++}
    fun run(block:suspend ()->Unit){if(busy)return;busy=true;job=viewModelScope.launch {try{withContext(Dispatchers.IO){block()}}catch(e:CancellationException){message="Cancelled. Saved projects are intact."}catch(e:Exception){message=e.message?:"Operation failed"}finally{busy=false;projects=store.list();revision++}}}
    fun import(uri:android.net.Uri,folder:Boolean){run {
        val p=Project(name=ImportNames.fromUri(getApplication(),uri,folder))
        try{
            val importer=Importer(getApplication(),store)
            val skipped=if(folder)importer.folder(uri,p){message=it}else importer.zip(uri,p){message=it}
            Catalog.match(p,apps,store.rules(),store.preferredIcons());store.save(p);current=p;message="Imported ${p.icons.size} images. $skipped unsupported or invalid files skipped. Wallpaper and widget folders are excluded by default."
        }catch(e:Exception){store.directory(p).deleteRecursively();throw e}
    }}
    fun build(){val p=current?:return;save();run {PackBuilder(getApplication(),store).build(p){message=it}}}
    fun rematch(){val p=current?:return;run {
        apps=Catalog.load(getApplication());appsLoaded=true;Catalog.match(p,apps,store.rules(),store.preferredIcons());store.save(p)
        message="Matching refreshed. Exact unique app names are assigned; conflicting names and artwork still need review."
    }}
    fun delete(p:Project){run {store.delete(p);if(current?.id==p.id)current=null;message="Collection deleted. Remembered matching choices were kept."}}
    fun goHome(){save();current=null;if(!busy)message=""}
    fun cancel(){job?.cancel()}
}

class MainActivity:ComponentActivity(){
    private lateinit var model:StudioModel
    private val folder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){it?.let {uri->model.import(uri,true)}}
    private val zip=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let {uri->model.import(uri,false)}}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent {
        model=viewModel()
        val prefs=remember {getSharedPreferences("appearance",MODE_PRIVATE)}
        var theme by remember {mutableStateOf(prefs.getInt("theme",0))}
        val dark=when(theme){1->false;2->true;else->isSystemInDarkTheme()}
        val colors=if(dark)darkColorScheme(primary=Color(0xffc8b7ff),secondary=Color(0xff8ce4c9),background=Color(0xff101223),surface=Color(0xff1b1e34)) else lightColorScheme(primary=Color(0xff6747aa),secondary=Color(0xff176954),background=Color(0xfffaf7ff))
        MaterialTheme(colorScheme=colors){Surface(Modifier.fillMaxSize()){
            Column(Modifier.safeDrawingPadding().padding(horizontal=20.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    TextButton(onClick={if(!model.busy)model.goHome()}){Text("PRISM  /  Icon Studio")}
                    TextButton(onClick={theme=(theme+1)%3;prefs.edit().putInt("theme",theme).apply()}){Text(listOf("System","Light","Dark")[theme])}
                }
                if(model.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(model.message,Modifier.padding(12.dp));TextButton(onClick={model.cancel()}){Text("Cancel")}}
                else if(model.message.isNotBlank()){Text(model.message,Modifier.padding(vertical=4.dp),style=MaterialTheme.typography.bodySmall,maxLines=2)}
                var managing by remember {mutableStateOf<Project?>(null)}
                var deleting by remember {mutableStateOf<Project?>(null)}
                val p=model.current
                if(p==null){
                    Text("Make your home\nscreen yours.",style=MaterialTheme.typography.headlineLarge,modifier=Modifier.padding(vertical=20.dp))
                    Text("Local collections. Your choices. Entirely offline.",style=MaterialTheme.typography.bodyLarge)
                    Row(Modifier.padding(vertical=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Button(onClick={folder.launch(null)},enabled=!model.busy){Text("Import folder")}
                        OutlinedButton(onClick={zip.launch(arrayOf("application/zip","application/x-zip-compressed"))},enabled=!model.busy){Text("Import ZIP")}
                    }
                    Text("Your collections",style=MaterialTheme.typography.titleLarge)
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f)){
                        items(model.projects,key={it.id}){project->Card(onClick={model.current=project},modifier=Modifier.fillMaxWidth()){
                            Column(Modifier.padding(20.dp)){Text(project.name,style=MaterialTheme.typography.titleLarge);Text("${project.icons.size} images • revision ${project.version}");Row {project.icons.take(4).forEach {Thumbnail(it.path,Modifier.size(48.dp).padding(4.dp))}}
                                TextButton(onClick={managing=project},enabled=!model.busy){Text("Manage")}}
                        }}
                        item{Text("Compatibility preview • Theme Park support is awaiting an S25 test. Maximum 512 assignments per pack. Signing keys stay on this device; uninstalling Prism loses the ability to update its old packs.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=16.dp))}
                    }
                }else Editor(model,p,this@MainActivity)
                managing?.let {project->AlertDialog(onDismissRequest={managing=null},title={Text(project.name)},text={Text("Uninstall removes the generated Android pack. Delete removes this saved collection and its copied images and APK files.")},confirmButton={TextButton(onClick={managing=null;deleting=project}){Text("Delete collection")}},dismissButton={Row{
                    TextButton(onClick={managing=null;runCatching {Installer.uninstall(this@MainActivity,project)}.onFailure{model.message=it.message.orEmpty()}}){Text("Uninstall pack")}
                    TextButton(onClick={managing=null}){Text("Close")}
                }})}
                deleting?.let {project->AlertDialog(onDismissRequest={deleting=null},title={Text("Permanently delete collection?")},text={Text("${project.name}: copied images, assignments and saved builds will be removed. Downloaded source folders, other collections and remembered choices stay. An installed pack stays installed until you uninstall it separately.")},confirmButton={TextButton(onClick={deleting=null;model.delete(project)}){Text("Delete permanently")}},dismissButton={TextButton(onClick={deleting=null}){Text("Cancel")}})}
            }
        }}
    }}
}

@Composable fun Thumbnail(path:String,modifier:Modifier){
    val bitmap by produceState<android.graphics.Bitmap?>(null,path){value=withContext(Dispatchers.IO){BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=4})}}
    bitmap?.let {Image(it.asImageBitmap(),"Replacement icon",modifier)}?:Box(modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ColumnScope.Editor(model:StudioModel,p:Project,activity:MainActivity){
    val revision=model.revision
    var query by remember(p.id){mutableStateOf("")};var filter by remember(p.id){mutableStateOf("All")};var editing by remember {mutableStateOf<Icon?>(null)}
    var name by remember(p.id){mutableStateOf(p.name)}
    var shape by remember(p.id){mutableStateOf(p.shape)}
    var scale by remember(p.id){mutableFloatStateOf(p.scale)}
    var missingStyle by remember(p.id) { mutableStateOf(p.missingIconStyle) }
    val themed = missingStyle == MissingIconStyle.THEMED
    BackHandler(enabled = editing == null) { if (!model.busy) model.goHome() }
    val missingApps = remember(p.id, revision, model.apps) { Catalog.withoutArtwork(p, model.apps) }
    val missingMatches = remember(missingApps, query) {
        missingApps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }
    val paletteInputs = remember(p.id, revision) { p.icons.filterNot { it.excluded }.map { it.path } }
    val readPalette = themed && missingApps.isNotEmpty()
    val palette by produceState<PaletteResult?>(null, paletteInputs, readPalette) {
        value = null
        if (readPalette) {
            value = PaletteResult(withContext(Dispatchers.IO) { PackPalette.fromArtwork(paletteInputs) })
        }
    }
    val gridState = rememberLazyGridState()
    val themedTab = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val visible=remember(p.id,revision,query,filter){p.icons.filter {it.name.contains(query,true) && when(filter){"Matched"->it.component!=null&&!it.excluded;"Needs review"->it.component==null&&!it.excluded;"Excluded"->it.excluded;else->true}}}
    LazyVerticalGrid(columns=GridCells.Adaptive(145.dp),state=gridState,modifier=Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
        item(key="settings",span={GridItemSpan(maxLineSpan)}){Column{
    OutlinedTextField(name,{name=it.take(80);p.name=name;model.save()},label={Text("Pack name")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!model.busy)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        listOf("Device","Original","Rounded","Circle").forEach { choice->FilterChip(selected=shape==choice,onClick={shape=choice;p.shape=choice;model.save()},enabled=!model.busy,label={Text(choice)}) }
    }
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        p.icons.firstOrNull { !it.excluded }?.let {icon->
            val preview by produceState<android.graphics.Bitmap?>(null,icon.path,shape,scale){value=withContext(Dispatchers.Default){BitmapFactory.decodeFile(icon.path)?.let { source->IconRenderer.render(source,shape,scale).also {source.recycle()} }}}
            preview?.let {Image(it.asImageBitmap(),"Generated shape and size preview",Modifier.size(64.dp))}
        }
        Column(Modifier.weight(1f).padding(start=12.dp)){
            Text("Artwork size ${(scale*100).toInt()}%",style=MaterialTheme.typography.labelLarge)
            Slider(value=scale,onValueChange={scale=it;p.scale=it},onValueChangeFinished={model.save()},valueRange=0.6f..1f,enabled=!model.busy)
        }
    }
        Text("Apps without artwork", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MissingIconStyle.entries.forEach { style ->
                FilterChip(selected = missingStyle == style, enabled = !model.busy && model.appsLoaded, onClick = {
                    missingStyle = style
                    p.missingIconStyle = style
                    model.save()
                    if (style == MissingIconStyle.THEMED) {
                        query = ""
                        filter = THEMED_APPS
                        scope.launch { gridState.animateScrollToItem(1); themedTab.bringIntoView() }
                    } else if (filter == THEMED_APPS) {
                        filter = "All"
                    }
                }, label = { Text(style.label) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { model.rematch() }, enabled = !model.busy) { Text("Refresh automatic matches") }
            TextButton(enabled = !model.busy, onClick = {
                shape = "Device"
                scale = 0.90f
                missingStyle = MissingIconStyle.ORIGINAL
                if (filter == THEMED_APPS) filter = "All"
                p.shape = shape
                p.scale = scale
                p.missingIconStyle = missingStyle
                model.save()
            }) { Text("Reset") }
        }
        }}
        stickyHeader(key="categories") { Surface(color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        val categories = buildList {
            addAll(listOf("All", "Matched", "Needs review"))
            if (themed) add(THEMED_APPS)
            addAll(listOf("Excluded", "Missing apps"))
        }
        categories.forEach { label ->
            val modifier = if (label == THEMED_APPS) Modifier.bringIntoViewRequester(themedTab) else Modifier
            FilterChip(selected = filter == label, onClick = { filter = label }, modifier = modifier, label = { Text(label) })
        }
    }
        }}
        item(key="search",span={GridItemSpan(maxLineSpan)}){OutlinedTextField(query,{query=it},label={Text("Search images or apps")},singleLine=true,modifier=Modifier.fillMaxWidth())}
        if (filter == THEMED_APPS) {
            val colors = palette
            when {
                missingApps.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (model.appsLoaded) "Every app in Prism’s catalog has artwork." else "Reading installed apps",
                        Modifier.padding(12.dp))
                }
                colors == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Reading collection colors")
                    }
                }
                else -> {
                    if (colors.palette == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Prism could not detect background colors. The pack will use resized originals.", Modifier.padding(12.dp))
                    }
                    if (missingMatches.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("No matching apps", Modifier.padding(12.dp))
                    }
                    items(missingMatches, key = { "themed:" + it.component }) { app ->
                        ThemedAppCard(activity, app, shape, scale, colors.palette?.forPackage(app.pkg))
                    }
                }
            }
        } else if (filter=="Missing apps"){
            items(missingMatches,
                key = { "missing:${it.component}" }, span = { GridItemSpan(maxLineSpan) }) { app ->
                Text("${app.label}\n${app.component}", Modifier.padding(12.dp))
            }
        }else {
        items(visible,key={it.id}){icon->Card(onClick={editing=icon},enabled=!model.busy,shape=RoundedCornerShape(20.dp)){
            Column(Modifier.padding(12.dp)){
                Row {Thumbnail(icon.path,Modifier.size(64.dp));icon.component?.let {OriginalIcon(activity,it)}}
                Text(icon.name.substringAfterLast('/').substringBeforeLast('.'),maxLines=2,style=MaterialTheme.typography.titleSmall)
                Text(if(icon.excluded)"Excluded" else model.apps.find { it.component==icon.component }?.label?:"Needs review",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                Text(icon.reason,style=MaterialTheme.typography.bodySmall,maxLines=3)
            }
        }}
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Button(onClick={model.build()},enabled=!model.busy){Text("Build pack")}
        p.lastApk?.let { path ->
            val currentBuild = remember(p.id, revision, scale) { p.lastBuildFingerprint == p.buildFingerprint() }
            OutlinedButton(onClick = {
                runCatching { Installer.install(activity, java.io.File(path), p.name) }
                    .onFailure { model.message = it.message.orEmpty() }
            }, enabled = !model.busy && currentBuild) { Text(if (currentBuild) "Install" else "Rebuild to install") }
        }
        TextButton(onClick={val intent=activity.packageManager.getLaunchIntentForPackage("com.samsung.android.themedesigner");if(intent!=null)activity.startActivity(intent)else model.message="Open Good Lock → Theme Park → Icon → Create new → icon-pack selector. Choose your installed Prism pack, save the theme, then apply it. After updates, reselect the pack or create a new icon theme."}){Text("Theme Park")}
    }
    editing?.let {icon->
        var appQuery by remember(icon.id){mutableStateOf("")};var rememberChoice by remember {mutableStateOf(true)}
        AlertDialog(onDismissRequest={editing=null},title={Text("Choose an app")},text={Column{
            Text(icon.name.substringAfterLast('/'),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            OutlinedTextField(appQuery,{appQuery=it},label={Text("App name or package")})
            Row(
                Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=rememberChoice,role=androidx.compose.ui.semantics.Role.Checkbox,onValueChange={rememberChoice=it}),
                verticalAlignment=androidx.compose.ui.Alignment.CenterVertically
            ){
                Checkbox(checked=rememberChoice,onCheckedChange=null,modifier=Modifier.padding(horizontal=12.dp))
                Text("Remember this icon name",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(min=80.dp,max=240.dp),contentPadding=PaddingValues(vertical=8.dp)){
                items(model.apps.filter {it.label.contains(appQuery,true)||it.pkg.contains(appQuery,true)},key={it.component}){app->TextButton(modifier=Modifier.fillMaxWidth(),onClick={
                    p.icons.filter {it.id!=icon.id && it.component==app.component}.forEach {it.component=null;it.reason="Replaced by another artwork choice"}
                    icon.component=app.component;icon.excluded=false;icon.reason="Your choice";if(rememberChoice)model.store.remember(icon.name.substringAfterLast('/'),app.component);model.save();editing=null
                }){Column(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalAlignment=androidx.compose.ui.Alignment.Start){
                    Text(app.label,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                    Text(app.pkg,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                }}}
            }
        }},confirmButton={TextButton(onClick={icon.excluded=!icon.excluded;model.save();editing=null}){Text(if(icon.excluded)"Include image" else "Skip image")}},dismissButton={TextButton(onClick={editing=null}){Text("Close")}})
    }
}

@Composable fun OriginalIcon(context:android.content.Context,component:String){
    val bitmap by produceState<android.graphics.Bitmap?>(null,component){value=withContext(Dispatchers.IO){runCatching {
        IconRenderer.snapshot(IconRenderer.installedIcon(context, component))
    }.getOrNull()}}
    bitmap?.let {Image(it.asImageBitmap(),"Original app icon",Modifier.size(48.dp).padding(6.dp))}
}

private data class PaletteResult(val palette: PackPalette?)

@Composable
private fun ThemedAppCard(
    activity: MainActivity, app: Matcher.App, shape: String, scale: Float, background: Int?,
) {
    val images by produceState<Pair<Bitmap, Bitmap>?>(null, app.component, shape, scale, background) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val icon = IconRenderer.installedIcon(activity, app.component)
                val after = IconRenderer.renderFallback(icon, shape, scale, background)
                IconRenderer.snapshot(IconRenderer.installedIcon(activity, app.component)) to after
            }.getOrNull()
        }
    }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Before" to images?.first, "After" to images?.second).forEach { (label, bitmap) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val iconSize = Modifier.widthIn(max = 52.dp).fillMaxWidth().aspectRatio(1f)
                        if (bitmap == null) Box(iconSize)
                        else Image(bitmap.asImageBitmap(), "${app.label} ${label.lowercase()}", iconSize)
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
