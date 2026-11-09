# Chess Helper (Android)

chess.com taxtasını ekrandan oxuyub Stockfish ilə ən yaxşı gedişi göstərən üzən köməkçi.
Gediş belə göstərilir: çıxış xanasında halqa, ox və hədəf xanada parıldayan fiqur.

## İstifadə

1. APK-nı quraşdır, tətbiqi aç, **Başlat**a bas və icazələri ver (üstündə göstərmə, bildiriş, ekran tutma).
2. chess.com-da oyunu aç. **Yeni oyunun əvvəlində** (ilk 1-2 gedişdə) üzən paneldə bir dəfə **Başlat**a bas.
   Tətbiq taxta mövzusunu, fiqur görünüşünü və ağın aşağıda/yuxarıda olduğunu özü öyrənir və yadda saxlayır.
3. Sonra hər növbədə **Başlat**a bas: panel bir anlıq gizlənir, ekran oxunur və gediş 7 saniyə göstərilir.
4. **Yoxla** düyməsi tətbiqin taxtanı necə oxuduğunu ekranın üstündə çəkir. Qırmızı çərçivə şübhəli xanadır.
   Səhv fiqur görsən, yeni oyunun əvvəlində **Şablonu yenilə** bas.

Panelin yazısından tutub sürüşdürə bilərsən.

## Təhlil parametrləri

Üzən paneldə **Təhlil vaxtı** düyməsi ilə hər mövqe üçün 1, 2, 3, 5, 10, 15 və ya 30 saniyə seçilə bilər.
**Mühərrik ELO-su** düyməsi Stockfish-in gücünü 1320–3000 aralığında 100 ELO addımları ilə məhdudlaşdırır.
Seçilmiş parametrlər cihazda yadda saxlanılır və növbəti açılışda bərpa olunur.
Bu parametrlər mühərrikin təhlil gücünü və düşünmə müddətini dəyişir; göstərilən ELO real oyun reytinqinə zəmanət vermir.


## Fiqur tanıma necə işləyir

1. **Taxta tapma:** ekrandakı iki dominant rəng 8x8 şahmat düzülüşünə görə tapılır, mövzu siyahısı yoxdur.
   İnterfeysdə açıq xana rənginə bənzər elementlər olsa da taxta düzgün ayrılır.
2. **Siluet maskası:** hər xananın fon rəngi küncləri müqayisə edərək təyin olunur, fondan fərqli və xarici fondan
   ayrılan hər şey fiqurun siluetidir (konturun içi də). Ağ fiqur açıq xanada, qara fiqur tünd xanada eyni görünür.
3. **Növ:** siluet və daxili kənar xəritəsi şablonlarla müqayisə olunur. Ağ/qara rəng parlaqlıqdan təyin olunur.
4. **Qayda ilə düzəliş:** ikinci şah, son sırada piyada, 8-dən çox piyada kimi qeyri-mümkün nəticələr
   ikinci ən yaxşı variantla düzəldilir.
5. **Şablonlar** oyunun əvvəlindən öyrənilir, tətbiq yenidən açılanda da yadda qalır.
   Şablonlar mövzudan asılı deyil, ona görə mövzunu dəyişəndə də işləyir.

`tools/recognition-sim/` qovluğunda bu alqoritmin Python-dakı eyni nüsxəsi və sintetik taxta generatoru var.
Orada 4 mövzu, 2 fiqur dəsti, 1-4 piksel sürüşmə, bulanıqlıq, şah hücumu parıltısı, gediş və tutma işarələri
ilə yoxlama aparılıb: 100% düzgün xana (təhlükəsizlik üçün bunun real chess.com ekranı olmadığını nəzərə al).
İşə salmaq: `cd tools/recognition-sim && python3 run.py` (numpy, scipy, Pillow lazımdır).

## Avtomatik edilənlər

- **İstiqamət:** ağ aşağıda/yuxarıda avtomatik təyin olunur.
- **Kimin növbəsi:** chess.com-un son gediş işıqlandırmasından oxunur. İşıqlandırma tapılmasa, aşağıdakı rəngin növbəsi qəbul edilir.
- **En passant:** son gediş iki xanalıq piyada gedişidirsə FEN-ə yazılır.
- **Rokirovka:** oyunun əvvəlindən izlənir, kral və ya qala yerindən çıxanda hüquq silinir.
- **Fiqur şəkli:** parıldayan fiqur şriftdən asılı olmayaraq vektor şəkildə çəkilir.

## Qalan texniki hədlər

- Şablonu öyrənmək üçün oyunun başlanğıcına yaxın (hər tərəfdən ən çoxu bir gediş) olmaq lazımdır.
- Oyunun ortasından qoşulanda rokirovka hüququ bilinmir, ona görə rokirovka təklif olunmur.
- Taxtada əl ilə çəkilmiş oxlar, animasiya və ya yarımçıq sürüşdürülən fiqur oxumanı poza bilər.
- Taxta ekranda tam görünməlidir.
- Tanıma sintetik taxtalarda yoxlanılıb, real chess.com fiqur dəstlərində qeyri-dəqiqlik ola bilər.
  Şübhə olanda **Yoxla** düyməsi nəyin səhv oxunduğunu göstərir.

## Qurulum (GitHub)

1. Bu qovluğu GitHub repo-suna yüklə.
2. **Actions** bölməsində `Build APK` workflow-u işləyir: Stockfish-in ən son release-ini Android NDK ilə yığır, sonra APK qurur.
3. İş bitəndə **Artifacts** bölməsindən `ChessHelper-apk` yüklə (debug APK, birbaşa quraşdırılır).

Yerli quraşdırma üçün Stockfish-in arm64 binarisini `app/src/main/jniLibs/arm64-v8a/libstockfish.so` adı ilə qoy.

## Vacib xəbərdarlıq

Bu alət **bota qarşı oyun, öz oyunlarını təhlil etmək və qarşı tərəfin xəbəri olduğu dost oyunları** üçündür. chess.com-un fair-play qaydaları motor köməyini rated və unrated bütün oyunlarda qadağan edir. İnsanlara qarşı gizli istifadə hesabın bağlanmasına səbəb ola bilər.
