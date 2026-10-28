# lech-map

Vlastná navigácia **lech-map** pre Android, ktorá funguje aj v **Android Auto**.

- vektorová mapa v štýle AMap v telefóne aj na obrazovke auta: tmavomodrá v noci,
  pri jazde tmavý asfalt so svetlými okrajmi, zelená trasa so šípkami, auto s kompasom, 3D budovy
- čierny panel s odbočkou a pruhmi, rýchlosť v kruhu, limit pri názve ulice,
  spodná lišta s časom, km, počtom semaforov a časom príchodu
- vyhľadanie adresy alebo miesta (aj **hlasom** – mikrofón pri vyhľadávaní) s kritériami: **v okolí 50 km / Slovensko / všade**,
  zoradenie **najbližšie / najlepšia zhoda**, **otvorené teraz** (otváracie hodiny);
  s kľúčom TomTom aj návrhy počas písania
- **Moje miesta** (tlačidlo pod vyhľadávaním): uloženie miesta, kde práve si (napr. v lese), s názvom;
  miesta sú na mape ako hviezdičky, zoradené od najbližšieho, dajú sa premenovať, zdieľať a odstrániť;
  k miestu sa dá **navigovať pešo** (po chodníkoch a lesných cestách, posledný kúsok priamo k miestu;
  bez internetu vzdušnou čiarou) alebo autom; prepínač Autom/Pešo je aj v náhľade trasy
- **Domov a Práca** (uložíš hviezdičkou v náhľade trasy alebo podržaním výsledku),
  nedávne ciele, adresa bodu po podržaní prsta na mape
- výpočet trasy autom a navigácia odbočka po odbočke
- **viac variantov trasy** v náhľade (čas, km, hlavná cesta) – vyberieš ťuknutím na kartu
  alebo na sivú trasu na mape; **možnosti trasy**: bez diaľnic, bez spoplatnených úsekov
  (diaľničná známka), bez trajektov
- slovenské hlasové pokyny, automatický prepočet trasy pri zídení z cesty
- **auto na mape podľa výberu** (Nastavenia → Auto na mape): sedan, SUV, športové auto, motorka
  alebo šípka vo farbe biela, Kawasaki zelená, čierna, strieborná, červená či modrá – v mobile aj v aute
- pohľad počas jazdy: **3D**, **2D v smere jazdy** alebo **2D sever hore** (tlačidlo 3D/2D/S);
  farby mapy **automaticky podľa slnka** (v aute podľa auta), stále denné alebo nočné
- jazdné pruhy pred odbočkou (ktorým pruhom ísť) a **pohľad na pruhy na mape**: pred odbočkou sa mapa
  priblíži, cesta sa rozdelí na pruhy, správne pruhy sú zelené, na pruhoch biele šípky a červené X
- **priechody pre chodcov** (zebry z OpenStreetMap) pri priblížení mapy: biele pruhy cez celú cestu, nakreslené
  nad zelenou trasou (trasa ich neprekrýva) a pri pohľade na pruhy aj cez zvýraznené pruhy
- zelená trasa ostáva **vnútri cesty** (pri priblížení sa cesta rozširuje, trasa má tmavú stredovú
  medzeru a žlté stredové čiary cesty sú nad ňou)
- **obraz križovatky** pred zjazdom, rozdvojením a kruhovým objazdom: nakreslený pohľad na cestu
  s tabuľou a zelenou šípkou (v mobile pod pokynom, v Android Auto v karte s pokynom)
- rýchlostný limit a aktuálna rýchlosť, pri prekročení červená a hlasové upozornenie
- radary na mape s hlasovým upozornením
- **úsekové merania rýchlosti** (podľa OpenStreetMap): upozornenie pred úsekom, v úseku
  priemerná rýchlosť (červeno nad limit) a hlásenie konca
- **živá premávka (TomTom)**: farby premávky na cestách, trasa zafarbená podľa zápch,
  výpočet trasy a času príchodu podľa premávky, každých 5 minút hľadanie rýchlejšej trasy
- **dopravné udalosti (TomTom)**: nehody, uzávierky, práce na ceste, zápchy a nebezpečenstvá
  ako značky na mape; počas jazdy upozornenie na udalosť na trase (hlasom aj na obrazovke)
- **služby na mape**: nabíjačky pre elektromobily (s kľúčom TomTom aj počet voľných
  nabíjacích bodov), pumpy, parkovanie, odpočívadlá, jedlo, káva, potraviny, ubytovanie,
  lekárne a bankomaty – v okolí, počas navigácie pozdĺž trasy
- **zastávky po ceste** (až 3): počas navigácie vybraná nabíjačka či pumpa sa pridá ako zastávka,
  zastávky sa zoradia podľa trasy a potom trasa pokračuje do pôvodného cieľa; každú zastávku
  možno zrušiť zvlášť (tlačidlo Zastávky)
- **parkovanie pri cieli**: 1,5 km pred cieľom sa na mape a v zozname ukážu parkoviská okolo
  cieľa; vybrané parkovisko sa stane novým cieľom bez prerušenia navigácie
- **počasie v cieli** v čase príchodu (teplota, dážď, sneh, búrka) v náhľade trasy
- **zdieľanie príchodu**: ťuknutím na čas v spodnej lište (alebo ikonou v náhľade trasy)
  pošleš cieľ, čas príchodu a svoju polohu cez SMS, WhatsApp a pod.
- navigáciu spustenú v mobile vidno v aute a naopak
- dlhé podržanie prsta na mape v mobile = navigovať na to miesto
- **nastavenia** (ozubené koliesko): kľúč TomTom, premávka na mape, farby mapy, ktoré hlasové
  upozornenia chceš (radary a úsekové merania, dopravné udalosti, rýchlosť) a tolerancia
  rýchlosti (auto, +0, +5, +10 km/h)

## Inštalácia

### 1. Stiahni APK

Každá zmena v repozitári automaticky zostaví novú verziu (GitHub Actions).
Hotový súbor je v **Releases** → najnovšie vydanie → `lech-map.apk`:

https://github.com/Virivkyonline/amap/releases/latest

Repozitár je súkromný, takže musíš byť prihlásený na GitHube.

### 2. Dostaň APK do mobilu

Vyber si jednu možnosť:

- **Priamo v mobile:** otvor odkaz vyššie v prehliadači v mobile a stiahni `lech-map.apk`.
- **Cez USB kábel z PC:** pripoj mobil, v mobile zvoľ „Prenos súborov“, skopíruj
  `lech-map.apk` napr. do priečinka Download a v mobile ho otvor cez aplikáciu Súbory.
- **Cez Google Drive:** nahraj `lech-map.apk` na Drive a v mobile ho otvor.
- **Cez adb** (ak máš zapnuté ladenie USB): `adb install -r lech-map.apk`

Pri otvorení APK mobil požiada o povolenie „Inštalovať neznáme aplikácie“ – povoľ ho.
Ak sa ozve Play Protect, zvoľ „Podrobnosti“ → „Napriek tomu nainštalovať“.

Novšie verzie sa inštalujú rovnako, cez starú verziu (nastavenia ostanú).

### 3. Prvé spustenie v mobile

Otvor **lech-map** a povoľ **presnú polohu** a **upozornenia** (pre navigáciu so zhasnutým displejom).

### 4. Android Auto – len cez Google Play

Android Auto spustí navigáciu s mapou (apku z knižnice Android for Cars) **len vtedy, keď je
nainštalovaná z Google Play**. „Neznáme zdroje“ v nastaveniach pre vývojárov Android Auto platia
len pre hudobné apky a správy, nie pre navigácie (pozri
[návod Google](https://developer.android.com/training/cars/testing)). Preto sa lech-map do auta
dostane cez **interné testovanie** v Google Play – bez zverejnenia a bez kontroly Googlom:

1. V [Play Console](https://play.google.com/console) **Vytvoriť aplikáciu**: názov `lech-map`,
   jazyk slovenčina, Aplikácia, Zadarmo, potvrdiť vyhlásenia.
2. **Testovanie → Interné testovanie → Testeri**: vytvor zoznam s Gmailom, ktorý je v mobile,
   ulož ho a skopíruj **odkaz na pripojenie**.
3. **Vytvoriť nové vydanie**: podpisový kľúč nechaj na Google (predvolené), nahraj súbor
   **`lech-map.aab`** z najnovšieho vydania na GitHube, ďalej **Uložiť** a **Spustiť vydanie
   pre interné testovanie**. Ak Play pýta vyhlásenie k službe v popredí, vyber **Poloha –
   navigácia odbočka po odbočke**.
4. **Rozšírené nastavenia → Formáty → Pridať formát → Android Auto** a potvrď podmienky.
5. V mobile **odinštaluj** lech-map z GitHubu (Play ju nevie aktualizovať, je inak podpísaná),
   otvor odkaz z bodu 2 → **Stať sa testerom** → nainštaluj z Obchodu Play.
6. Pripoj auto – lech-map je medzi aplikáciami (inak v Android Auto **Prispôsobiť spúšťač**).

Ďalšie verzie: z každého vydania na GitHube nahraj nový `lech-map.aab` ako nové vydanie
interného testovania; mobil ho stiahne z Obchodu Play.

### 5. Živá premávka (voliteľné)

1. Zaregistruj sa zdarma na [developer.tomtom.com](https://developer.tomtom.com) (stačí e-mail, bez karty).
2. V časti **Keys** skopíruj svoj API kľúč.
3. V apke ťukni na **ozubené koliesko** vedľa vyhľadávania, vlož kľúč a daj **Uložiť**.
   Apka kľúč hneď overí.

Kľúč je uložený len v telefóne. Bez neho apka funguje ďalej, len bez premávky.
Bezplatný program TomTom má denné limity, ktoré na bežné osobné jazdenie stačia.

Pre služby (nabíjačky s obsadenosťou) musí mať kľúč v my.tomtom.com zapnuté produkty
**Search API** a **EV Charging Stations Availability API** (kľúč → Edit key). Bez nich
sa služby hľadajú v OpenStreetMap – nájdu sa tiež, len bez obsadenosti nabíjačiek.

## Ovládanie v aute

- **Hľadať** – zadanie cieľa (počas jazdy auto písanie nedovolí, preto sú hneď
  zobrazené nedávne ciele; ciele sa dajú vyhľadať aj v mobile pred jazdou)
- **Štart** – spustí navigáciu, **Ukončiť** ju zastaví; v náhľade sa dá vybrať variant trasy
  a cez **Možnosti** vypnúť diaľnice, spoplatnené úseky či trajekty
- **Služby** (ikona nabíjačky) – vyber druh (nabíjačky, pumpy, …), zoznam sa ukáže vľavo
  a miesta na mape. Mimo navigácie ťuk na miesto = trasa k nemu; počas navigácie sa
  pridá ako zastávka. Zastávku zrušíš v Službách tlačidlom **Zrušiť zastávku**.
- tlačidlá na mape: posun, priblížiť, oddialiť, späť na moju polohu
- reproduktor – vypnutie/zapnutie hlasu

## Ako to funguje

| Časť | Služba |
|------|--------|
| mapa (vektorové dlaždice, písma) | OpenFreeMap (tiles.openfreemap.org), vykresľuje MapLibre |
| vyhľadávanie, adresa bodu | TomTom Search (s kľúčom), inak Nominatim (nominatim.openstreetmap.org) |
| výpočet trasy, jazdné pruhy | TomTom (s kľúčom, podľa premávky), inak OSRM (router.project-osrm.org) |
| farby premávky na cestách | TomTom Traffic Flow (s kľúčom) |
| dopravné udalosti | TomTom Traffic Incidents (s kľúčom) |
| služby, nabíjačky a ich obsadenosť | TomTom Search a EV Availability (s kľúčom), inak Overpass API |
| rýchlostné limity, radary, semafory | Overpass API (overpass-api.de) |
| počasie v cieli | Open-Meteo (api.open-meteo.com) |
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
