# amap

Vlastná navigácia pre Android, ktorá funguje aj v **Android Auto**.

- vektorová mapa v štýle AMap v telefóne aj na obrazovke auta: tmavomodrá v noci,
  pri jazde tmavý asfalt so svetlými okrajmi, zelená trasa so šípkami, auto s kompasom, 3D budovy
- čierny panel s odbočkou a pruhmi, rýchlosť v kruhu, limit pri názve ulice,
  spodná lišta s časom, km, počtom semaforov a časom príchodu
- vyhľadanie adresy alebo miesta, nedávne ciele
- výpočet trasy autom a navigácia odbočka po odbočke
- slovenské hlasové pokyny, automatický prepočet trasy pri zídení z cesty
- jazdné pruhy pred odbočkou (ktorým pruhom ísť)
- rýchlostný limit a aktuálna rýchlosť, pri prekročení červená a hlasové upozornenie
- radary na mape s hlasovým upozornením
- **živá premávka (TomTom)**: farby premávky na cestách, trasa zafarbená podľa zápch,
  výpočet trasy a času príchodu podľa premávky, každých 5 minút hľadanie rýchlejšej trasy
- navigáciu spustenú v mobile vidno v aute a naopak
- dlhé podržanie prsta na mape v mobile = navigovať na to miesto

## Inštalácia

### 1. Stiahni APK

Každá zmena v repozitári automaticky zostaví novú verziu (GitHub Actions).
Hotový súbor je v **Releases** → najnovšie vydanie → `amap.apk`:

https://github.com/Virivkyonline/amap/releases/latest

Repozitár je súkromný, takže musíš byť prihlásený na GitHube.

### 2. Dostaň APK do mobilu

Vyber si jednu možnosť:

- **Priamo v mobile:** otvor odkaz vyššie v prehliadači v mobile a stiahni `amap.apk`.
- **Cez USB kábel z PC:** pripoj mobil, v mobile zvoľ „Prenos súborov“, skopíruj
  `amap.apk` napr. do priečinka Download a v mobile ho otvor cez aplikáciu Súbory.
- **Cez Google Drive:** nahraj `amap.apk` na Drive a v mobile ho otvor.
- **Cez adb** (ak máš zapnuté ladenie USB): `adb install -r amap.apk`

Pri otvorení APK mobil požiada o povolenie „Inštalovať neznáme aplikácie“ – povoľ ho.
Ak sa ozve Play Protect, zvoľ „Podrobnosti“ → „Napriek tomu nainštalovať“.

Novšie verzie sa inštalujú rovnako, cez starú verziu (nastavenia ostanú).

### 3. Prvé spustenie v mobile

Otvor **amap** a povoľ **presnú polohu** a **upozornenia** (pre navigáciu so zhasnutým displejom).

### 4. Zapnutie v Android Auto

Android Auto štandardne zobrazuje len aplikácie z Google Play. Pre vlastnú aplikáciu:

1. V mobile otvor nastavenia **Android Auto**
   (Nastavenia → Pripojené zariadenia → Predvoľby pripojenia → Android Auto).
2. Úplne dole ťukni **10× na „Verzia“** a potvrď zapnutie režimu pre vývojárov.
3. Vpravo hore cez **⋮** otvor **Nastavenia pre vývojárov**.
4. Zaškrtni **„Neznáme zdroje“** (Unknown sources).
5. Odpoj a znova pripoj mobil k autu. Ak amap nevidíš, v Android Auto nastaveniach
   otvor **Prispôsobiť spúšťač** a zapni ho tam.

### 5. Živá premávka (voliteľné)

1. Zaregistruj sa zdarma na [developer.tomtom.com](https://developer.tomtom.com) (stačí e-mail, bez karty).
2. V časti **Keys** skopíruj svoj API kľúč.
3. V apke ťukni na **ozubené koliesko** vedľa vyhľadávania, vlož kľúč a daj **Uložiť**.
   Apka kľúč hneď overí.

Kľúč je uložený len v telefóne. Bez neho apka funguje ďalej, len bez premávky.
Bezplatný program TomTom má denné limity, ktoré na bežné osobné jazdenie stačia.

## Ovládanie v aute

- **Hľadať** – zadanie cieľa (počas jazdy auto písanie nedovolí, preto sú hneď
  zobrazené nedávne ciele; ciele sa dajú vyhľadať aj v mobile pred jazdou)
- **Štart** – spustí navigáciu, **Ukončiť** ju zastaví
- tlačidlá na mape: posun, priblížiť, oddialiť, späť na moju polohu
- reproduktor – vypnutie/zapnutie hlasu

## Ako to funguje

| Časť | Služba |
|------|--------|
| mapa (vektorové dlaždice, písma) | OpenFreeMap (tiles.openfreemap.org), vykresľuje MapLibre |
| vyhľadávanie | Nominatim (nominatim.openstreetmap.org) |
| výpočet trasy, jazdné pruhy | TomTom (s kľúčom, podľa premávky), inak OSRM (router.project-osrm.org) |
| farby premávky na cestách | TomTom Traffic Flow (s kľúčom) |
| rýchlostné limity, radary, semafory | Overpass API (overpass-api.de) |
| hlas | prevod textu na reč v telefóne (slovenčina, inak čeština) |

Sú to bezplatné verejné služby OpenStreetMap určené na bežné osobné použitie.
Aplikácia potrebuje mobilné dáta; navštívené časti mapy si pamätá (do 50 MB).
Zápchy zobrazuje len s kľúčom TomTom. Limity, pruhy a radary sú len tam, kde ich má
OpenStreetMap zakreslené (na hlavných cestách väčšinou áno) a zatiaľ sa ukazujú len počas navigácie.

Ak hlas nehovorí po slovensky, v mobile nainštaluj slovenský hlas:
Nastavenia → Systém → Jazyky → Prevod textu na reč → Google → Inštalovať hlasové údaje → Slovenčina.

## Vývoj

Projekt je v Kotline. Knižnice: [Android for Cars App Library](https://developer.android.com/training/cars/apps)
a [MapLibre Native](https://maplibre.org/) na vykreslenie mapy. Grafika (štýl mapy, ikony) je vlastná,
kreslená v kóde – vzhľad je len inšpirovaný aplikáciou AMap.

```
app/src/main/java/online/virivky/amap/
  car/       obrazovky pre Android Auto (mapa, hľadanie, náhľad trasy)
  ui/        aplikácia v mobile
  map/       štýl mapy, ikony, ovládanie kamery a panely nad mapou (spoločné pre auto aj mobil)
  nav/       trasa, navigácia, hlasové pokyny
  net/       vyhľadávanie a výpočet trasy
  location/  GPS
```

Zostavenie lokálne (potrebné Android SDK): `./gradlew assembleRelease`
