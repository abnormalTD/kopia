# Kópia

Android appka, ktorá stiahne hudbu alebo video z YouTube **v pôvodnej kvalite** — nič neprekódúva, takže výsledok je presná kópia toho, čo YouTube posiela.

APK má necelý **1 MB**.

## Inštalácia

1. Otvor [posledné vydanie](https://github.com/abnormalTD/kopia/releases/latest) a stiahni súbor `kopia-….apk`.
2. Otvor ho v telefóne. Android sa spýta, či povolíš inštaláciu z tohto zdroja — povoľ.
3. Hotovo. Keď vyjde nová verzia, appka ti sama ponúkne tlačidlo na jej stiahnutie.

Potrebuješ Android 10 alebo novší.

## Používanie

- **Zdieľaj** video alebo playlist priamo z YouTube appky → vyber Kópiu. Alebo odkaz vlož ručne.
- Prepínač **Stiahnuť celé video** — vypnutý = hudba, zapnutý = video (vyberieš kvalitu, pri každej vidíš veľkosť súboru).
- **Playlisty** sa stiahnu celé do vlastného priečinka, skladby sa očíslujú a v hudobnom prehrávači sa zoskupia ako album.
- Keď playlist stiahneš znova, Kópia **dotiahne len nové pesničky** — nič nesťahuje dvakrát.
- Rozbehnuté sťahovanie sa dá **zrušiť** v appke aj v notifikácii.
- Sťahovanie beží na pozadí s notifikáciou — môžeš appku zavrieť, otočiť telefón alebo ho zamknúť.

Súbory nájdeš v `Music/Kopia` a `Movies/Kopia` (playlisty v podpriečinku s ich názvom, napr. `Music/Kopia/Spain`) a hudobné prehrávače ich vidia hneď.

## Čo appka nevie

- **Niektoré videá sa stiahnuť nedajú** — hlavne nové oficiálne klipy veľkých vydavateľstiev (napr. VEVO). YouTube ich zámerne zamyká a appka vtedy rovno napíše dôvod.
- Video najviac v **1080p**. Vyššie rozlíšenia YouTube dáva len vo formátoch, ktoré by sa museli prekódovať.
- Playlist najviac **1000 skladieb** (ak je dlhší, appka to povie).
- Hudba je vo formáte **M4A (AAC)**, nie MP3. Je to formát, v ktorom ju posiela YouTube — prevod do MP3 by kvalitu len zhoršil.

## Upozornenie

Kópia je osobný hobby projekt, určený **len na súkromné, osobné použitie**.

Sťahovanie obsahu z YouTube môže byť v rozpore s podmienkami používania YouTube a autorské právo sa v rôznych krajinách líši. Za to, že tvoje používanie je v súlade s podmienkami YouTube a s autorským právom, ktoré sa na teba vzťahuje, zodpovedáš ty. Sťahuj len obsah, na ktorý máš právo — napríklad vlastné videá, voľné diela alebo obsah, ktorý smieš uložiť.

Projekt nie je nijako spojený s YouTube ani so spoločnosťou Google, ani ním nie je podporovaný.

## Ako to funguje

Bez Pythonu, bez ffmpegu, bez vlastného JavaScript enginu:

- Adresy streamov získava jedným HTTP dotazom na YouTube API v úlohe klienta, ktorý ich ešte dostáva priamo (webový prehrávač už používa protokol SABR a priame adresy nedáva).
- Sťahuje po 1 MB kúskoch, štyri naraz — jedno dlhé spojenie YouTube spomalí a zasekne, a súbežné kúsky sú zhruba dvakrát rýchlejšie.
- Video a zvuk spája systémový `MediaMuxer` bez prekódovania; ukladá cez `MediaStore`, takže netreba žiadne povolenie k úložisku.

## Vývoj

```sh
./gradlew assembleDebug                      # debug APK
./gradlew assembleRelease                    # potrebuje podpisový kľúč v ~/.kopia
KOPIA_LIVE=1 ./gradlew testDebugUnitTest     # overí extrakciu proti živému YouTube
```

Ten posledný test je „kanárik": spustí skutočný kód appky proti YouTube. Keď YouTube niečo zmení, ukáže to práve on.
