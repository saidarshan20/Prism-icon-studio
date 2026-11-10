"""Create conventional aapt resource packs locally. Never sends collection data anywhere."""
from pathlib import Path
import argparse, os, subprocess, shutil, json
from PIL import Image, ImageOps, ImageDraw, ImageChops

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'AppData/Local/Android/Sdk')
BUILD = SDK / 'build-tools/35.0.0'
AAPT = BUILD / ('aapt.exe' if os.name == 'nt' else 'aapt')
PACKAGE = 'local.prism.pack.' + 'a'*32
LABEL = 'PRISM_TEMPLATE_LABEL'
CAPACITY = 512

def run(*args):
    subprocess.run([str(a) for a in args], check=True)

def prepare(name, count, proof=False, rounded=False):
    base = ROOT / 'build' / name
    (base/'res/drawable-nodpi').mkdir(parents=True, exist_ok=True)
    (base/'res/xml').mkdir(parents=True, exist_ok=True)
    (base/'assets').mkdir(exist_ok=True)
    mappings = [
        ('App-Store', 'com.android.vending/com.google.android.finsky.activities.MainActivity'),
        ('Safari', 'com.sec.android.app.sbrowser/com.sec.android.app.sbrowser.SBrowserMainActivity'),
        ('Camera', 'com.sec.android.app.camera/com.sec.android.app.camera.Camera'),
        ('Settings', 'com.android.settings/com.android.settings.Settings'),
        ('YouTube', 'com.google.android.youtube/com.google.android.youtube.HomeActivity'),
        ('Gmail', 'com.google.android.gm/com.google.android.gm.ConversationListActivityGmail'),
        ('Instagram', 'com.instagram.android/com.instagram.mainactivity.InstagramMainActivity'),
        ('WhatsApp', 'com.whatsapp/com.whatsapp.Main'),
    ]
    xml = ['<?xml version="1.0" encoding="utf-8"?><resources>']
    source = ROOT.parent / 'attack-on-titan-app-icons-pack-wallpapers-clan-com/Icons'
    for i in range(count):
        path = base/f'res/drawable-nodpi/slot_{i:04d}.png'
        img = Image.new('RGBA', (8, 8), (0, 0, 0, 0))
        component = f'ComponentInfo{{prism.placeholder{i:04d}/prism.placeholder{i:04d}.Main}}'
        if proof:
            stem, target = mappings[i]
            candidate = next((p for p in source.iterdir() if p.stem.lower() == stem.lower()), None)
            if candidate:
                original = Image.open(candidate).convert('RGBA')
                original.thumbnail((192,192))
                img = Image.new('RGBA',(192,192))
                img.alpha_composite(original,((192-original.width)//2,(192-original.height)//2))
            component = 'ComponentInfo{' + target + '}'
        if rounded:
            # Supersampling gives transparent antialiased edges; no source art is modified.
            canvas=Image.new('RGBA',(768,768))
            artwork=img.resize((644,644),Image.Resampling.LANCZOS)
            mask=Image.new('L',(644,644));ImageDraw.Draw(mask).rounded_rectangle((0,0,643,643),radius=155,fill=255)
            artwork.putalpha(ImageChops.multiply(artwork.getchannel('A'),mask))
            canvas.alpha_composite(artwork,(62,62))
            img=canvas.resize((192,192),Image.Resampling.LANCZOS)
            assert img.getpixel((0,0))[3]==0 and img.getpixel((96,96))[3]>0
        img.save(path)
        xml.append(f'<item component="{component}" drawable="slot_{i:04d}"/>')
    xml.append('</resources>')
    text = '\n'.join(xml)
    (base/'res/xml/appfilter.xml').write_text(text, encoding='utf-8')
    (base/'assets/appfilter.xml').write_text(text, encoding='utf-8')
    (base/'res/xml/drawable.xml').write_text('<resources><category title="Prism"/>' + ''.join(f'<item drawable="slot_{i:04d}"/>' for i in range(count)) + '</resources>',encoding='utf-8')
    pkg = 'local.prism.rounded' if rounded else ('local.prism.compatibility' if proof else PACKAGE)
    label = "Prism Rounded - 84 percent" if rounded else ("Prism Compatibility \u2022 8 icons" if proof else LABEL)
    (base/'AndroidManifest.xml').write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}" android:versionCode="123456789" android:versionName="1.0">
<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35"/>
<application android:label="{label}" android:icon="@drawable/slot_0000" android:hasCode="false" android:allowBackup="false">
<activity android:name="android.app.Activity" android:exported="true">
<intent-filter><action android:name="org.adw.launcher.THEMES"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>
<intent-filter><action android:name="com.novalauncher.THEME"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>
<intent-filter><action android:name="com.anddoes.launcher.THEME"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>
</activity></application></manifest>''', encoding='utf-8')
    output = ROOT/'build/rounded-unsigned.apk' if rounded else (ROOT/'delivery/compatibility-unsigned.apk' if proof else ROOT/'app/src/main/assets/template.apk')
    output.parent.mkdir(parents=True, exist_ok=True)
    run(AAPT, 'package','-f','-M',base/'AndroidManifest.xml','-S',base/'res','-A',base/'assets','-I',SDK/'platforms/android-35/android.jar','-F',output)

def inventory():
    report=[]
    for folder in ROOT.parent.iterdir():
        if not folder.is_dir() or folder == ROOT: continue
        record={'collection':folder.name,'images':0,'excluded':0,'invalid':[],'formats':{}}
        for file in folder.rglob('*'):
            if not file.is_file():continue
            if '__MACOSX' in file.parts or file.name.startswith('.') or any(x in file.parent.name.lower().removesuffix('-wallpapers-clan-com') for x in ('wallpaper','widget')):
                record['excluded']+=1;continue
            try:
                with Image.open(file) as im:
                    im.verify()
                    record['images']+=1
                    record['formats'][im.format]=record['formats'].get(im.format,0)+1
            except Exception: record['invalid'].append(str(file.relative_to(folder)))
        report.append(record)
    (ROOT/'delivery/collection-inventory.json').write_text(json.dumps(report,indent=2))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proof', action='store_true', help='Also build the optional compatibility fixture from local artwork outside this repository.')
    parser.add_argument('--inventory', action='store_true', help='Also inventory local collection folders outside this repository.')
    args = parser.parse_args()
    prepare('template',CAPACITY)
    if args.proof:
        prepare('compatibility',8,True)
    if args.inventory:
        inventory()
