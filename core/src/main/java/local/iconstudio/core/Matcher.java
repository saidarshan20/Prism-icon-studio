package local.iconstudio.core;
import java.util.*;
import java.text.Normalizer;

public final class Matcher {
    public static String normalize(String value){
        String name=value.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        name=name.replaceFirst("(?i)\\.(png|jpe?g|webp)$", "");
        return Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    public static final class App {
        public final String label, pkg, component;
        public App(String label,String pkg,String component){this.label=label;this.pkg=pkg;this.component=component;}
    }
    public static final class Match {
        public final App app; public final String reason; public final boolean automatic;
        public Match(App app,String reason,boolean automatic){this.app=app;this.reason=reason;this.automatic=automatic;}
    }
    private static final Map<String,List<String>> ALIASES=Map.ofEntries(
        Map.entry("appstore",List.of("com.android.vending")),
        Map.entry("safari",List.of("com.sec.android.app.sbrowser")),
        Map.entry("twitter",List.of("com.twitter.android")),
        Map.entry("gmaps",List.of("com.google.android.apps.maps")),
        Map.entry("googlemaps",List.of("com.google.android.apps.maps")),
        Map.entry("reminders",List.of("com.samsung.android.app.reminder")),
        Map.entry("shortcuts",List.of("com.samsung.android.app.routines")),
        Map.entry("facetime",List.of("com.google.android.apps.tachyon","com.google.android.apps.meetings")),
        Map.entry("findmy",List.of("com.samsung.android.app.find")),
        Map.entry("game",List.of("com.samsung.android.game.gamehome")),
        Map.entry("files",List.of("com.sec.android.app.myfiles"))
    );
    private static final Set<String> GENERIC=Set.of("dating","books","music","photos","mail","email","browser","camera","calendar","clock","car","shopping","games","health","fitness","weather","notes","files","messages","phone","calculator","settings","wallet","maps","bank","banking","social","travel","tv","video");
    private static final Set<String> REVIEW_ONLY=Set.of("dating","car","shopping","games","bank","banking","social","travel","tv","video");
    public static List<Match> match(String filename,List<App> apps,Map<String,String> rules){
        String n=normalize(filename); List<Match> result=new ArrayList<>();
        String remembered=rules.get(n);
        if(remembered!=null){
            for(App a:apps)if(a.component.equals(remembered))return List.of(new Match(a,"Your remembered choice",true));
            return List.of(); // A removed/changed component must be reviewed, never silently replaced.
        }
        List<String> alias=ALIASES.get(n);
        if(alias!=null){
            for(App a:apps)if(alias.contains(a.pkg))result.add(new Match(a,"Default alias: "+filename+" -> "+a.label,true));
            return ambiguity(result);
        }
        String stem=filename.replaceFirst("(?i)\\.(png|jpe?g|webp)$", "");
        for(App a:apps)if(a.pkg.equals(stem)||a.component.equals(stem))result.add(new Match(a,"Exact package or launcher component",true));
        if(!result.isEmpty())return ambiguity(result);
        for(App a:apps)if(normalize(a.label).equals(n))result.add(new Match(a,REVIEW_ONLY.contains(n)?"Category name — choose an app":"Exact app label",!REVIEW_ONLY.contains(n)));
        if(!result.isEmpty())return ambiguity(result);
        if(GENERIC.contains(n))return result;
        for(App a:apps){String label=normalize(a.label);if(n.length()>3 && (label.contains(n)||n.contains(label)) && label.length()>3)result.add(new Match(a,"Similar name — review required",false));}
        return result;
    }
    private static List<Match> ambiguity(List<Match> list){if(list.size()<2)return list;List<Match> r=new ArrayList<>();for(Match m:list)r.add(new Match(m.app,m.reason+"; multiple launcher activities",false));return r;}
}
