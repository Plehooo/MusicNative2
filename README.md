# IPTV Player — v2.0

Native Android IPTV player (Media3/ExoPlayer) dengan 21 channel default (diambil dari
daftar TV di app kamu), tampilan baru, dan sistem **update daftar channel dari GitHub
tanpa install ulang APK**.

## Apa yang diperbaiki dari versi sebelumnya

- **Bug utama build gagal**: workflow lama minta `platforms;android-37`, padahal API
  level 37 belum rilis (saat ini yang stabil API 36 / Android 16). `compileSdk`,
  `targetSdk`, dan workflow CI sudah diturunkan ke **36**.
- **`BuildConfig` dipakai tapi tidak pernah diaktifkan** (`buildFeatures.buildConfig`)
  — sebelumnya ini bikin build gagal begitu kode menyentuh `BuildConfig.VERSION_NAME`.
  Sekarang sudah diaktifkan.
- **Tidak ada Gradle wrapper** di project, sedangkan workflow memanggil `gradle`
  langsung — riskan ikut versi Gradle sistem yang belum tentu cocok dengan AGP 9.1.1.
  Workflow sekarang mengunci Gradle ke versi yang kompatibel (9.1.0).
- **Favorit & riwayat hilang tiap app ditutup** (cuma disimpan di memori) — sekarang
  disimpan permanen di penyimpanan lokal.
- **Tidak ada channel default** — dulu app buka dalam keadaan kosong sampai kamu
  masukkan URL playlist manual. Sekarang 21 channel dari app kamu sudah tertanam
  sebagai default, langsung bisa nonton begitu dibuka.
- **Player tidak auto-retry** saat stream live sempat putus sebentar — sekarang ada
  1x auto-retry otomatis.
- **Tombol UPDATE dulu cuma munculin teks penjelasan**, tidak benar-benar melakukan
  apa-apa — sekarang benar-benar menarik daftar channel terbaru dari URL yang kamu
  atur.

## Tampilan baru

- Kartu channel dengan logo/emoji, label kategori, indikator channel yang sedang
  diputar, dan tombol favorit langsung di list.
- Chip kategori (Hiburan, Berita, Islami, Anak, Sport, dst — otomatis dari data).
- Pencarian, tab Semua / Favorit / Riwayat.
- Badge "LIVE", tombol fullscreen (auto rotate ke landscape), swipe-to-refresh.

## Cara update channel tanpa install ulang (lewat Termux + GitHub)

Ide dasarnya: aplikasi membaca daftar channel dari sebuah file `channels.json` yang
kamu host di repo GitHub kamu sendiri (lewat *raw* URL). Setiap kali kamu ubah file
itu di GitHub, aplikasi akan mengambil versi terbaru — otomatis tiap dibuka, otomatis
tiap ±15 menit di latar belakang, atau langsung lewat tombol refresh (🔄) / swipe ke
bawah.

### 1. Siapkan repo di GitHub

Buat repo (boleh publik atau privat asal raw URL bisa diakses), lalu upload file
`app/src/main/assets/channels.json` dari project ini sebagai titik awal. Formatnya:

```json
[
  {
    "id": "rtv",
    "name": "RTV",
    "group": "Hiburan",
    "logo": "📺",
    "url": "https://contoh.com/stream.m3u8",
    "featured": false
  }
]
```

`logo` boleh diisi emoji, atau URL gambar (`https://...png`) untuk logo asli. File ini
juga boleh berupa playlist `.m3u`/`.m3u8` biasa (dengan `#EXTINF`, `group-title`,
`tvg-logo`) — aplikasi otomatis mendeteksi formatnya.

### 2. Edit dari Termux

```bash
pkg install git -y
git clone https://github.com/USERNAME/REPO.git
cd REPO
nano channels.json      # ubah/tambah channel di sini
git add channels.json
git commit -m "update channel"
git push
```

### 3. Arahkan aplikasi ke file itu

Buka aplikasi → ⚙ **Settings** → isi **Remote Channel URL** dengan raw URL-nya, contoh:

```
https://raw.githubusercontent.com/USERNAME/REPO/main/channels.json
```

Tekan **Simpan & Update**. Sejak saat ini, setiap `git push` yang kamu lakukan dari
Termux akan otomatis muncul di aplikasi — tanpa build APK baru, tanpa install ulang.

## Build APK

1. Upload seluruh folder ini ke GitHub.
2. Buka tab **Actions** di repo.
3. Jalankan workflow **"Build IPTV APK"**.
4. Unduh artifact `IPTVPlayer-debug`.

## Batasan yang masih ada

- Belum ada EPG (jadwal program) di UI.
- Auto-update APK (bukan cuma daftar channel) tetap butuh build & install manual,
  karena APK release harus ditandatangani dengan keystore yang sama — ini batasan dari
  Android sendiri, bukan sesuatu yang bisa dilewati dari sisi app.
- Aplikasi tidak membobol DRM, autentikasi, pembatasan geo, atau kontrol akses stream
  apa pun — hanya memutar URL stream yang kamu masukkan sendiri.
