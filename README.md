# ChessHelper (Android)

ChessHelper Android ekrandakı şahmat taxtasını tanıyır və Stockfish-dən **mövcud mövqe üçün mühərrik tövsiyəsi** alır. Nəticə üzən paneldə ox və gediş işarəsi ilə göstərilir.

Tətbiq şəxsi təhlil, bot oyunları və bütün iştirakçıların əvvəlcədən razı olduğu oyunlar üçündür. Platforma və turnir qaydaları mühərrik köməyini qadağan edirsə, həmin oyunlarda istifadə etmə.

## İstifadə

1. Tətbiqi aç, **Başlat** düyməsinə bas və tələb olunan ekran tutma, overlay və bildiriş icazələrini ver.
2. Şahmat taxtasını tam ekranda göstər. Yeni oyunun başlanğıcında üzən paneldən **Avtomatik başlat** seç. Fiqur şablonları və taxtanın istiqaməti öyrənilib yadda saxlanır.
3. Tətbiq təxminən saniyədə bir dəfə ekranı oxuyur. Yeni mövqe iki ardıcıl oxunuşda eyni təsdiqlənəndən sonra Stockfish analiz edir. Hər gedişdə yenidən düyməyə basmaq lazım deyil.
4. **Dayandır** avtomatik oxuma dövrünü saxlayır. Dairəvi şahmat düyməsi paneli gizlədir/göstərir; onu sürüşdürərək yerini dəyişmək olar.
5. Eyni servis işləyərkən yeni oyuna keçəndə **Yeni oyun** düyməsini bas, sonra başlanğıc taxta göründükdə **Avtomatik başlat** seç. Bu, əvvəlki oyunun rokirovka və istiqamət vəziyyətini sıfırlayır.
6. **Yoxla** tanınmış taxtanı, 8×8 xana torunu və qırmızı şübhəli xanaları göstərir. Paneldə kadr ölçüsü, taxtanın `x/y` koordinatları, taxta ölçüsü və tanınmış fiqur sayı dəqiq piksel ilə göstərilir. Ox və fiqur işarələri ekran görüntüsünün ölçüsünə və overlay pəncərəsinin ekrandakı başlanğıc nöqtəsinə uyğun çevrilir. Lazım gəldikdə **Şablonu yenilə** ilə başlanğıc mövqedə yenidən kalibrə et.
7. Son gedişin işıqlandırılmasından növbə müəyyən edilə bilmirsə, **Növbə** düyməsindən Ağ və ya Qara seç. Əl ilə seçilən tərəf yalnız növbəti uğurlu analizə tətbiq olunur; sonrakı gedişdə yenidən avtomatik tanınma sınanır və yenə bilinməzsə seçim təkrarlanmalıdır.

## Analiz parametrləri

- **Fast:** 1 saniyə.
- **Normal:** 3 saniyə.
- **Deep:** 10 saniyə.
- **Custom:** 1–30 saniyə.
- **Mühərrik ELO-su:** 1320–3000 aralığında 20 vahid addımla.
- **Maksimum güc:** Stockfish ELO məhdudiyyətini söndürür.

Seçilmiş parametrlər cihazda yadda saxlanılır. ELO parametri mühərrikin məhdudlaşdırılmış güc hədəfidir; real oyun reytinqinə zəmanət deyil.

## Tanınma və vəziyyət məlumatları

- Taxta kəşfi və fiqur tanınması mövcud `BoardReader` alqoritmi ilə aparılır.
- Taxtanın istiqaməti ilk etibarlı kalibrasiyadan sonra sessiya ərzində sabit saxlanılır.
- Tanınma keyfiyyəti göstəricisi yalnız fiqur kimi tanınmış xanalar arasındakı güclü təsnifat payını göstərir. Bu, ehtimal və ya dəqiqlik zəmanəti deyil.
- Eyni mövqenin təkrar analiz edilməməsi üçün fiqur düzülüşü, növbə, rokirovka hüquqları və en passant vəziyyəti birlikdə müqayisə olunur.
- Roki­rovka hüquqları yalnız sessiya başlanğıc mövqedə müşahidə ediləndə tam məlum sayılır. Oyunun ortasında qoşulanda sistem naməlum tarixi hüquqları uydurmur və rokirovkanı söndürür.
- FEN-in yarımgediş və tam gediş sayğacları ekran görüntüsündən bərpa edilə bilmədiyi üçün hazırda standart başlanğıc dəyərləri ilə doldurulur. Bu, oyunun əvvəlindən tam PGN tarixçəsi demək deyil.

## Tarixçə və ixrac

- Son 100 mühərrik tövsiyəsi saxlanılır: FEN, UCI gedişi, zaman möhürü, fiqur tanınma keyfiyyəti və ölçülmüş müddətlər.
- **PGN tövsiyə ixracı** hər tövsiyəni həmin FEN-dən başlayan ayrıca, bir gedişlik PGN mövqeyi kimi ixrac edir. Bu, istifadəçinin həqiqətən oynadığı gedişlərin tarixçəsi deyil.
- SAN çevirməsindən əvvəl UCI gedişi şahmat qaydaları kitabxanası ilə qanuni gedişlər siyahısına qarşı yoxlanılır. Qanunsuz gediş saxlanmır və göstərilmir.
- **UCI ixracı** hər tövsiyə üçün `position fen ...` və `bestmove ...` əmrlərini saxlayır.

## Performance Monitor

Panel son tanınma və Stockfish müddətini, son 100 ölçmənin ortasını, fiqur tanınma keyfiyyəti göstəricisini, tanınma/mühərrik xətalarının sayını və tarixçənin doluluğunu göstərir.

## Görünüş

Paneldə üç mövzu var: Dark, Midnight Blue və AMOLED. Mövzu seçimi cihazda saxlanılır. Dairəvi 48dp düymə paneli açıb-bağlayır; panel və düymə sürüşdürülə bilər.

## Texniki məlumatlar

- Android minimum SDK: 26.
- Hazırkı native Stockfish paketi: `arm64-v8a`.
- Kotlin/JVM target: Java 17.
- Stockfish UCI prosesi üçün timeout və təhlükəsiz bağlanma mexanizmi var.
- MediaProjection kadrları ayrıca bitmap-ə köçürülür; `Image` obyektləri oxunuşdan sonra bağlanır.
- SAN və PGN çevirməsi üçün chesslib istifadə edilir.

### GitHub Actions

`.github/workflows/build.yml` aşağıdakı işləri görür:

1. Stockfish-in son buraxılış etiketlərindən Android ARM64 executable qurur.
2. JVM unit testlərini işə salır.
3. Debug APK yaradır.
4. `ChessHelper-apk` artifact-ini yükləyir.

APK yalnız workflow uğurla bitdikdən sonra Actions → həmin run → Artifacts bölməsindən götürülməlidir. Debug APK debug açarı ilə imzalanır; Play Store buraxılışı üçün ayrıca release signing konfiqurasiyası lazımdır.

## Məlum məhdudiyyətlər

- Fiqur şablonları ən yaxşı halda oyunun başlanğıc mövqeyində öyrədilir. Oyunun ortasında ilk dəfə açıldıqda şablon çatışmazlığı ola bilər.
- Taxtada animasiya, yarımçıq sürüşdürülən fiqur, əl ilə çəkilmiş ox və ya pəncərə üst-üstə düşməsi tanınmanı poza bilər.
- Son gediş işarəsi görünmürsə növbə əl ilə seçilməlidir.
- Oyunun ortasında qoşulanda rokirovka hüquqları məlum olmadığından mühərrik rokirovka təklif etməyəcək.
- Sintetik tanınma simulyasiyası real cihaz və real şahmat saytlarının bütün mövzularını/fiqur dəstlərini əhatə etmir. Real cihaz sınağı ayrıca tələb olunur.
- CI build-in keçməsi overlay-in hər Android cihazında, ekran miqyasında və oyunun hər mövzusunda düzgün işlədiyini sübut etmir.

## Fair-play

Tətbiqi şəxsi oyun təhlili, botlara qarşı oyun və bütün iştirakçıların razı olduğu sessiyalarla məhdudlaşdır. Rəqibə və ya turnir/platforma qaydalarına zidd gizli mühərrik köməyindən istifadə etmə.
