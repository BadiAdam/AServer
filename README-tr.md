🌍 *Diğer dillerde oku: [English](README.md)*

Herkese merhaba! 👋 **AServer**'a hoş geldiniz.

"Android telefonumda terminal ekranında aklımı kaçırmadan, tam teşekküllü bir Minecraft Java sunucusu çalıştırabilir miyim?" gibi çılgınca bir fikirle başlayan bu serüven; Android için tamamen yerel (native) olarak kodlanmış, modern, zırhlı ve eksiksiz bir node kontrol paneline dönüştü.

AServer sadece basit bir arayüz (wrapper) değildir; hem **Eklenti tabanlı (PaperMC)** hem de **Modlu (Fabric)** sunucuları doğrudan cebinizden çalıştırabilen çift motorlu bir mimaridir. Arka plandaki tüm ağır işleri (Java, API yönlendirmeleri, Playit.gg tünelleri) Termux (PRoot Ubuntu) üzerinden hallederken, size Jetpack Compose ile güçlendirilmiş pürüzsüz bir yönetim paneli sunar.

## 🚀 Temel Özellikler

* **Çift Motorlu Kurulum (PaperMC & Fabric):** Altyapınızı siz seçin. AServer, en güncel stabil sürümleri otomatik olarak çeker veya o karmaşık 2 aşamalı Fabric Installer ortamını sizin için tek tuşla inşa eder. Klasik 1.8.8 PvP'den en yeni 1.21.x mod paketlerine kadar her şey elinizin altında.
* **Bağlam Duyarlı Mağazalar (Modrinth & Spiget):** Yerleşik dosya yöneticimiz oldukça zekidir. `plugins` klasörüne girdiğinizde **Spiget Mağazası** belirir. `mods` klasörüne geçtiğinizde ise kontrolü **Modrinth Mağazası** devralır.
* **Keskin Nişancı Sürüm Algoritması:** "Uyumsuz Mod" (Incompatible Mod) çökmelerine elveda deyin. Modrinth motorumuz, sunucunuzun özel `mc_version.txt` dosyasını okur ve *sadece* sizin sunucu sürümünüz ve altyapınızla %100 uyumlu modları karşınıza çıkarır.
* **Canlı Konsol ve Hızlı Makrolar:** ANSI renk kodlamasına sahip gerçek zamanlı log okuyucu. Yatay kaydırılabilir **Makro Barı** sayesinde klavyeye bile dokunmadan (Sabah Yap, Havayı Temizle, Yaratıkları Sil gibi) hayat kurtaran komutları anında gönderin.
* **"Adalet Sarayı" (Oyuncu Yöneticisi):** Oyuncu kitlenizi yönetmek için özel bir arayüz. Aktif oyunculara OP verin, atın (kick) veya yasaklayın (ban). `banned-players.json` dosyasını yönetin ve Whitelist (Beyaz Liste) korumasını tek tuşla açıp kapatın.
* **Akıllı Dosya Yöneticisi ve IDE Benzeri Editör:** Dosyalar arasında gezinin, yeni klasörler oluşturun ve `.properties`, `.yml` veya `.json` dosyalarını yerleşik kod editörüyle anında düzenleyin.
* **Zaman Makinesi (Güvenli Yedekler):** Tüm sunucu mimarinizi güvenle ZIP'leyin. Geri yükleme işlemi, bozuk sunucuyu tamamen temizler ve yedeğinizi saniyeler içinde güvenle (Zip-Slip açıklarına karşı yamalanmış şekilde) dışarı aktarır.
* **Kalıcı Otomasyon (Zamanlanmış Görevler):** Arka plan servisi tarafından yürütülen ve dahili hafızaya kaydedilen tekrarlayan komutlar (Örn: `/save-all` veya otomatik duyurular) oluşturun. Uygulama arayüzü kapalı olsa bile çalışmaya devam eder.
* **Çift Dil Desteği:** Hem **Türkçe** hem de **İngilizce** dillerinde tam yerelleştirme.

## 🛡️ Kaputun Altındakiler (Pro-Seviye Güvenlik ve Kararlılık)

Biz sadece bir arayüz tasarlamadık; sunucunun etrafına bir zırh ördük:
* **Dinamik RCON Güvenliği:** Sabit şifreler tarihe karıştı. AServer, Playit tünelleri üzerinden yapılabilecek yetkisiz RCON sızmalarını engellemek için kurulan her yeni sunucuya özel, kriptografik ve tamamen rastgele bir UUID şifresi üretir.
* **Zombi Süreç Avcısı:** Sunucu kapatıldığında arka planda açık kalan `playit.gg` tünellerinin acımasızca sonlandırılmasını sağlar; böylece RAM sızıntılarını ve batarya sömürüsünü engeller.
* **OOM (Bellek Aşımı) Kalkanı:** Konsol logları sınırlandırılmış ve güçlü bir şekilde önbelleğe alınmıştır. Uygulama, `latest.log` dosyasının sadece en son kısmını okuyarak günlerce açık kalsa bile 60 FPS kaydırma hızı sunar ve RAM şişmesini sıfıra indirir.
* **Gerçek Zamanlı Donanım Metrikleri:** Ayrılan RAM'i, cihazın gerçek RAM kullanımını ve anlık disk boyutu kaplamasını yerel (native) olarak ve kusursuz bir şekilde hesaplar.

## 🛠️ Teknoloji Yığını (Tech Stack)
* **Dil:** Kotlin
* **Arayüz (UI) Mimarisi:** Jetpack Compose (Material 3)
* **Arka Plan Motoru:** Termux (proot-distro Ubuntu, OpenJDK 8/17/21)
* **Ağ (Networking):** Yerel Java Socket'leri (RCON entegrasyonu) & Global tünelleme için Playit.gg.

## ⚙️ Nasıl Çalışır?
1.  **Beyin (AServer):** Özellikleri siz seçersiniz. AServer doğru Java sürümünü hesaplar, PaperMC jar dosyasını veya Fabric Yükleyicisini (Installer) indirir, EULA/server.properties dosyalarını enjekte eder, güvenli bir RCON şifresi üretir ve başlatma betiğini hazırlar.
2.  **Kas Gücü (Termux):** AServer, devasa ve otomatikleştirilmiş bir betiği panonuza kopyalar ve Termux'u başlatır. Termux otomatik olarak Ubuntu, Java ve (eğer açıksa) Playit'i kurar, ardından sunucunuzu (node) ayağa kaldırır.
3.  **Kontrol:** AServer'a geri döndüğünüzde, ön planda çalışan "Zırh" servisi 25575 portu üzerinden RCON ile sunucuya bağlanır ve çalışan sunucu üzerinde size tam ve gerçek zamanlı kontrol sağlar.

## ⚠️ Uyarı / Sorumluluk Reddi
Cihazınızda yerel bir Minecraft sunucusu çalıştırmak ciddi miktarda RAM ve güçlü bir CPU gerektirir. Bu uygulama modern Android cihazlar için tasarlanmıştır. Telefonunuz ısınacaktır—eğer sunucuyu 7/24 açık bırakacaksanız cihazınızı serin tutmayı unutmayın!

Kodları çatallamakta (fork), katkıda bulunmakta veya bir hata bulduğunuzda "issue" açmakta özgürsünüz. İyi oyunlar! ⛏️
