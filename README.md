# Ramu

Ramu adalah aplikasi Android untuk melihat kondisi perangkat dan kebiasaan pemakaian dalam satu tempat. Beranda menampilkan ringkasan baterai, memori, jaringan, penyimpanan, waktu layar, dan penggunaan internet. Karakter pendamping memberi jalan cepat ke obrolan AI lokal dan halaman rincian.

Proyek ini masih **beta 0.5**. Angka monitor bergantung pada data yang disediakan Android dan izin yang Anda berikan. Model AI tidak disertakan dalam APK.

## Unduh dan pasang

1. Buka [GitHub Releases Ramu](https://github.com/Terradjannah/Ramu/releases/tag/v0.5-beta), lalu unduh **Ramu.apk** dari bagian *Assets*. Jangan memasang berkas dari sumber lain.
2. Di ponsel Android 10 atau lebih baru, buka APK yang diunduh. Jika Android meminta izin memasang aplikasi dari sumber ini, izinkan untuk aplikasi pengelola berkas atau browser yang Anda pakai, lalu lanjutkan pemasangan.
3. Jalankan Ramu dan selesaikan pengaturan awal. Izin yang belum diberikan dapat dibuka kembali melalui **Pengaturan → Izin aplikasi** di dalam Ramu.

Tautan unduhan langsung yang dapat dipakai pada situs Ramu: [Ramu.apk](https://github.com/Terradjannah/Ramu/releases/download/v0.5-beta/Ramu.apk). Versi yang terpasang dapat dilihat melalui informasi aplikasi di pengaturan Android. Pembaruan berikutnya harus ditandatangani dengan kunci release yang sama agar dapat dipasang di atas versi lama.

## Fitur

| Bagian | Yang tersedia |
| --- | --- |
| Beranda | Ringkasan waktu layar, internet, baterai, RAM, jaringan, dan penyimpanan; karakter pendamping dan jalan cepat ke obrolan. |
| Aktivitas | Rincian waktu layar, pemakaian aplikasi, penggunaan internet, riwayat, serta batas yang dapat diatur pengguna. |
| Monitor perangkat | Rincian baterai, memori, jaringan, penyimpanan, dan sensor sesuai dukungan perangkat. |
| Notifikasi | Log notifikasi lokal jika akses baca notifikasi diaktifkan. |
| AI lokal | Obrolan dengan model Gemma yang diunduh terpisah dan dijalankan di perangkat melalui LiteRT-LM CPU. |
| Pengaturan | Izin, jadwal pencatatan, pengingat, ekspor CSV, serta cadangan dan pemulihan lokal. |

## Izin yang diminta

Ramu menampilkan status izin di **Pengaturan → Izin aplikasi**. Anda dapat menunda izin saat pengaturan awal; fitur terkait baru memperoleh datanya setelah izin diberikan.

| Akses | Cara memberi izin | Dipakai untuk |
| --- | --- | --- |
| Akses penggunaan | Ketuk **Akses penggunaan**, pilih Ramu di pengaturan Android, lalu izinkan. | Waktu layar dan pemakaian per aplikasi. |
| Akses baca notifikasi | Ketuk **Baca notifikasi**, pilih Ramu, lalu setujui dialog sistem. | Log notifikasi lokal. Izin ini dapat memperlihatkan isi notifikasi yang sensitif kepada aplikasi. |
| Kirim notifikasi | Ketuk **Kirim notifikasi** dan izinkan saat diminta pada Android 13 atau lebih baru. | Pengingat dan status layanan pemantauan. |
| Mulai otomatis dan pengaturan baterai | Jika pemantauan terjadwal tertunda, periksa kedua pilihan ini di **Izin aplikasi** dan pengaturan ponsel. | Membantu pencatatan/pengingat berjalan di latar belakang. Perilakunya berbeda antarperangkat. |

Ramu juga memakai akses internet untuk mengunduh model AI dan menjalankan fungsi jaringan yang Anda pilih. Pengecualian optimasi baterai bersifat opsional. Memberi izin saja tidak otomatis menyalakan semua bentuk pencatatan atau pengingat.

## Model AI dan data

APK tidak memuat model atau token Hugging Face. Buka **Model lokal** di Ramu untuk memilih model; unduhan dapat memerlukan beberapa gigabita ruang kosong, akun Hugging Face, persetujuan lisensi model, dan token baca untuk model yang dibatasi. Simpan token hanya melalui formulir di aplikasi, jangan di issue atau tangkapan layar publik. Kecepatan dan kebutuhan RAM bergantung pada ponsel.

Obrolan, catatan pemantauan, pengaturan, dan model yang telah diunduh disimpan di perangkat. Ekspor CSV dan cadangan lokal dibagikan hanya saat Anda memilihnya. Unduhan model dan pemeriksaan jaringan memakai koneksi internet. Sumber ini belum mengonfigurasi endpoint dan kunci untuk pembaruan katalog model jarak jauh; jangan menganggap katalog jarak jauh aktif pada build ini.

## Tangkapan layar

Gambar berikut diambil dari build Ramu pada emulator uji baru. Angka monitor pada emulator hanya contoh keadaan perangkat uji.

| Beranda | Perkenalan |
| --- | --- |
| ![Beranda Ramu dengan karakter Bao](screenshots/home.png) | ![Pilih nama dan karakter](screenshots/perkenalan.png) |

| Sambutan | Izin aplikasi |
| --- | --- |
| ![Layar sambutan Ramu](screenshots/onboarding.png) | ![Pilihan izin Ramu](screenshots/permissions.png) |
## Bangun dari source

Repositori ini memuat aplikasi Android dan berkas Gradle yang diperlukan. Siapkan Android Studio dengan Android SDK 36 dan JDK 21, lalu jalankan:

```powershell
git clone https://github.com/Terradjannah/Ramu.git
cd Ramu
.\gradlew.bat :app:assembleRelease
```

Gradle menghasilkan APK release **belum ditandatangani**. Untuk pemasangan atau distribusi, pengelola rilis harus menandatanganinya dengan keystore milik proyek, lalu memverifikasi tanda tangan dan checksum. Keystore, password, `local.properties`, model, data pengguna, hasil build, dan APK tidak disimpan di source. APK resmi tersedia di GitHub Releases.

## Struktur repo dan lisensi

`app/` berisi source aplikasi, resource, dan pengujian Android; berkas Gradle di root dipakai untuk build. Lisensi pihak ketiga yang menyertai ikon tersedia di [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/). Model Gemma memiliki ketentuan lisensi tersendiri di sumber unduhnya dan tidak didistribusikan di sini.

Kode Ramu diterbitkan untuk dilihat dan dibangun; belum ada lisensi penggunaan ulang umum untuk source proyek ini. Jika ingin memakai ulang kode atau aset Ramu di proyek lain, hubungi pemilik repo terlebih dahulu. Laporkan masalah keamanan secara privat melalui fitur **Report a vulnerability** GitHub bila tersedia; jangan lampirkan token, cadangan, atau data pribadi dalam issue publik.
