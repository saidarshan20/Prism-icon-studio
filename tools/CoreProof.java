import local.iconstudio.core.*;
import java.util.*;
import java.util.zip.*;
import java.nio.file.*;
public class CoreProof {
 public static void main(String[] args)throws Exception {
  var apps=List.of(new Matcher.App("Play Store","com.android.vending","com.android.vending/Main"),new Matcher.App("Internet","com.sec.android.app.sbrowser","com.sec.android.app.sbrowser/Main"),new Matcher.App("Dating","example","example/Main"));
  if(!Matcher.match("App-Store.PNG",apps,Map.of()).get(0).app.pkg.equals("com.android.vending"))throw new AssertionError("alias");
  if(!Matcher.match("Safari.webp",apps,Map.of()).get(0).app.pkg.equals("com.sec.android.app.sbrowser"))throw new AssertionError("alias");
  if(Matcher.match("Dating.png",apps,Map.of()).get(0).automatic)throw new AssertionError("generic");
  if(!Matcher.match("Safari.png",List.of(),Map.of()).isEmpty())throw new AssertionError("missing app");
  for(String name:List.of("Calendar","Clone","Maps","Weather","Phone")){
   var a=new Matcher.App(name,"test.app","test.app/Main");
   if(!Matcher.match(name+".png",List.of(a),Map.of()).get(0).automatic)throw new AssertionError(name);
   if(Matcher.match(name+".png",List.of(a,new Matcher.App(name,"other.app","other.app/Main")),Map.of()).stream().anyMatch(m->m.automatic))throw new AssertionError("Ambiguous "+name);
  }
  String[][] aliases={{"Twitter","com.twitter.android"},{"GMaps","com.google.android.apps.maps"},{"Google-Maps","com.google.android.apps.maps"},{"Reminders","com.samsung.android.app.reminder"},{"Shortcuts","com.samsung.android.app.routines"},{"FaceTime","com.google.android.apps.tachyon"},{"Find-My","com.samsung.android.app.find"},{"Game","com.samsung.android.game.gamehome"},{"Games","com.samsung.android.game.gamehome"},{"Files","com.sec.android.app.myfiles"}};
  for(var alias:aliases){
   var a=new Matcher.App("Renamed target",alias[1],alias[1]+"/Main");
   var m=Matcher.match(alias[0]+".PNG",List.of(a),Map.of());
   if(m.size()!=1||!m.get(0).automatic||!m.get(0).app.pkg.equals(alias[1]))throw new AssertionError(alias[0]);
   if(!Matcher.match(alias[0]+".PNG",List.of(),Map.of()).isEmpty())throw new AssertionError("Absent alias");
   var override=new Matcher.App("Custom","test.custom","test.custom/Main");
   if(!Matcher.match(alias[0]+".png",List.of(a,override),Map.of(Matcher.normalize(alias[0]),override.component)).get(0).app.component.equals(override.component))throw new AssertionError("Saved preference priority");
  }
  var mapPackage = "com.google.android.apps.maps";
  var maps = new Matcher.App("Maps", mapPackage, mapPackage + "/Main", true);
  var driving = new Matcher.App("Driving", mapPackage, mapPackage + "/Driving");
  if (!Matcher.match("Google-Maps.png", List.of(driving, maps), Map.of()).get(0).app.component.equals(maps.component))
   throw new AssertionError("Prefer primary Google Maps activity");
  if (Matcher.defaultArtworkPriority("Google-Maps.png", mapPackage) <= Matcher.defaultArtworkPriority("Maps.png", mapPackage))
   throw new AssertionError("Prefer dedicated map artwork");
  var gallery = new Matcher.App("Gallery", "com.sec.android.gallery3d", "com.sec.android.gallery3d/Main");
  var photos = new Matcher.App("Photos", "com.google.android.apps.photos", "com.google.android.apps.photos/Main");
  if (!Matcher.match("Photos.png", List.of(photos, gallery), Map.of()).get(0).app.component.equals(gallery.component))
   throw new AssertionError("Use source Photos artwork for Samsung Gallery");
  if (!Matcher.match("Photos.png", List.of(photos, gallery), Map.of("photos", photos.component)).get(0).app.component.equals(photos.component))
   throw new AssertionError("Preserve explicit Photos preference");
  if (!Matcher.match("Google-Photos.png", List.of(photos, gallery), Map.of()).get(0).app.component.equals(photos.component))
   throw new AssertionError("Honor dedicated Google Photos artwork");
  try(var zip=new ZipFile(args[0]);var out=new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))){
   var entries=zip.entries();while(entries.hasMoreElements()){
    var e=entries.nextElement();byte[] bytes=zip.getInputStream(e).readAllBytes();
    if(e.getName().equals("resources.arsc")||e.getName().equals("AndroidManifest.xml")||e.getName().equals("res/xml/appfilter.xml")) bytes=BinaryResources.rewrite(bytes,s->s.equals(BinaryResources.TEMPLATE_PACKAGE)?"local.prism.proof":s.equals("PRISM_TEMPLATE_LABEL")?"Prism 日本語 🌈":s.equals("ComponentInfo{prism.placeholder0000/prism.placeholder0000.Main}")?"ComponentInfo{com.android.vending/Main}":s,"local.prism.proof",2);
    out.putNextEntry(new ZipEntry(e.getName()));out.write(bytes);out.closeEntry();
   }
  }
  System.out.println("PASS: aliases, normalization, ambiguity, missing apps; patched APK written for independent aapt validation.");
 }
}
