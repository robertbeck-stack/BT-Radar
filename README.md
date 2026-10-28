# BLE Radar 3D (frumgerð)

Android forrit sem metur **3D staðsetningu** (þar á meðal hæð) Bluetooth LE tækja. Það notar ARCore til að vita hvar síminn er staddur hverju sinni og tengir hverja RSSI mælingu við þá staðsetningu.

## Hvernig það virkar
1. **ARCore** rekur staðsetningu símans í rúmi (x, y = upp, z) með cm-nákvæmni, út frá myndavél og hreyfiskynjurum.
2. **BLE skanni** grípur hvert auglýsingapakka (advertisement) og RSSI gildi þess. Hver mæling er merkt með staðsetningu símans á nákvæmlega þeirri stundu (brúað á milli ramma).
3. Mælingum er safnað í **30 cm teninga** og miðgildi RSSI tekið í hverjum teningi.
4. **Levenberg–Marquardt** með Huber vigtun leysir líkanið `RSSI = A − 10·n·log10(d)` fyrir x, y, z og A, ræst frá 7 upphafspunktum.
5. Ef tvær ólíkar lausnir eru næstum jafngóðar er niðurstaðan merkt **tvíræð**. Algengasta dæmið er hæðar-spegilmynd þegar síminn hefur verið í sömu hæð allan tímann.

## Notkun
1. Settu upp `app-debug.apk`. Leyfa þarf uppsetningu úr óþekktum uppruna. Síminn verður að styðja ARCore (Google Play Services for AR).
2. Veittu heimild fyrir myndavél, „Tæki í nágrenninu“ og **nákvæma** staðsetningu, og hafðu kveikt á staðsetningu í símanum. Án þess finnur BLE skönnunin ekkert.
3. Pikkaðu á tæki í listanum neðst.
4. Gakktu um í 1–2 mínútur og **sveiflaðu símanum upp og niður**, frá gólfi og upp fyrir höfuð. Án lóðréttrar hreyfingar er ekki hægt að greina hæðina.
5. Rauður punktur sýnir mat á staðsetningu og hringurinn óvissuna. Litlu punktarnir eru þínar mælingar (rautt = sterkt merki, blátt = veikt).
6. **Vista CSV** vistar gögnin í `Downloads/BLE3D/`. Þau má greina nánar á tölvu:
   ```
   pip install numpy scipy matplotlib
   python tools/analyze.py ble3d_….csv            # sterkasta tækið + 3D mynd
   python tools/analyze.py --simulate             # prófun á hermdum gögnum
   ```

## Tegundir og sía
- Hvert tæki fær tákn og tegund (📱 sími, 💻 tölva, 🎧 heyrnartól, 🏷️ leitarmerki o.s.frv.) út frá Appearance reit, framleiðandagögnum (Apple, Microsoft o.fl.), þjónustu-UUID og nafni. „?“ merkir ágiskun.
- Tæki sem senda ekki nafn heita t.d. „Tölva 3“ eða „Tæki 7“ í stað MAC vistfangs (það er enn í CSV og dagbók).
- **Sía**: flokkar (t.d. bara tölvur), leit og felun eftir nafni/tegund/framleiðanda, merkisstyrkur, fjarlægð og óvissa. Vistast milli ræsinga.

## Betri staðsetning
- Í hermun er skekkjan 3–4× minni þegar gengið er **í kringum** tækið (~0.5 m) en þegar allar mælingar eru öðrum megin (~1.5–2 m). Þegar tæki er valið sýnir appið **grænan hring „Gakktu hingað“** hinum megin við tækið ef of lítið horn hefur verið mælt.
- ARCore leitar að gólfinu; þegar það finnst er hæð sýnd **frá gólfi** og strikalína dregin frá tækinu niður á gólf.
- Prófað var að meta dempunarstuðulinn n fyrir hvert tæki; það gaf engan áreiðanlegan ávinning í hermun (`PathLossComparisonTest`) svo n = 2.2 er áfram fast.

## Mæla nákvæmni: raunstaðir og endurspilun
1. Ýttu á **Hreinsa**, veldu tæki sem þú veist hvar er, ýttu á **📍** og pikkaðu nákvæmlega á tækið í myndinni. ARCore finnur 3D punktinn (flötur eða dýpt) og appið sýnir skekkjuna (`📍 Skekkja 0.8 m …`) og grænan punkt á raunstaðnum.
2. Gakktu um eins og venjulega. Merktu fleiri tæki ef þú vilt.
3. Ýttu á **Vista**: `Downloads/BLE3D/ble3d_<tími>.csv` (mælingar), `_session.txt` (gönguleið, gólf, mat appsins, raunstaðir) og `_log.txt`.
4. Á tölvu, endurspilað í gegnum sama Localizer og appið notar:
   ```
   adb pull /sdcard/Download/BLE3D/ .
   ./gradlew testDebugUnitTest --tests "*SessionReplay*" --rerun -Psession=BLE3D/ble3d_<tími>_session.txt
   ```
   Skýrslan (skekkja eftir 25/50/75/100% gagna, án hæðarforsendu, án gólfs, mat appsins) er í `app/build/test-results/`, myndir ofan frá og frá hlið í `app/build/replay/`.

## Villuleit
- Hnappurinn **Log** sýnir dagbókina á skjánum. Viðvaranir eru gular.
- Á 10 s fresti kemur stöðulína: hvort verið sé að skanna, fjöldi auglýsinga (og hve margar duttu út vegna þess að ARCore rakti ekki), fjöldi tækja og staða ARCore.
- **Vista CSV** vistar líka dagbókina sem `ble3d_…_log.txt` í `Downloads/BLE3D/`.
- Með USB: `adb logcat -s BLE3D`
- Ef enginn auglýsing berst í 30 s er skönnunin endurræst sjálfkrafa, og líka á 20 mín fresti (Android stöðvar annars langar skannanir hljóðlaust).

## Raunhæfar væntingar
- Einingapróf, þ.e. einfaldur hávaði upp á 4 dB: skekkja ~0.2 m.
- Raunsærri hermun með fylgnandi skuggadeyfingu, líkamsskugga og tækið utan gönguleiðar: miðgildi 3D skekkju **~1.8 m**, þar af hæð ~0.4 m.
- Í raunheimi ræður endurkast mestu. Best gengur að ganga **í kringum** tækið frekar en bara á einni hlið þess.
- σ er margfaldað með 3 (`SIGMA_INFLATION`) því hrátt σ var 3–5× of bjartsýnt í hermun.

## Takmarkanir
- **Slembin MAC vistföng**: símar, AirTag og fleiri skipta um vistfang á ~15 mín fresti. Feril tækisins slitnar þá og það birtist sem nýtt tæki.
- Lega símans og líkami þinn hafa áhrif á RSSI (loftnetsmynstur). Haltu símanum eins og þú getur.
- `n = 2.2` er fast. Inni í byggingum með veggjum passar oft betur 2.5–3.5.
- Hnit eru miðuð við staðinn þar sem appið ræstist (y = hæð miðað við símann þá).

## Byggja
```
export ANDROID_HOME=/slóð/á/android-sdk
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # prófar matsreikniritið
```
