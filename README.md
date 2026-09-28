# USBCam — گوشی اندروید به وب‌کم USB برای PC (رایگان)

> **۱۰۰٪ رایگان و متن‌باز.** خروجی‌های آماده (APK اندروید + EXE ویندوز) در بخش [Releases](https://github.com/ciomid1097-arch/USBCam/releases/latest) قابل دانلود است.

دو برنامه‌ی ساده: اپ اندروید تصویر دوربین را به‌صورت JPEG از طریق USB می‌فرستد، و برنامه‌ی ویندوز آن را در یک پنجره‌ی **بدون هیچ لایه‌ی اضافه** نمایش می‌دهد — مخصوص Window Capture در OBS (مثل Windowed Projector، ولی با کنترل کامل روی اندازه).

- پنجره‌ی ویندوز فقط تصویر دوربین است: بدون منو، بدون آیکون، بدون واترمارک.
- بدون حاشیه (frameless)، تغییر اندازه‌ی کاملاً آزاد (تا 160×90، تا فول‌اسکرین).
- جابه‌جایی با درگ، تغییر اندازه از لبه‌ها/گوشه‌ها.
- همه‌ی کنترل‌ها فقط با **راست‌کلیک**: Always on top، Mirror، Fullscreen، Close.
- تصویر همیشه با حفظ نسبت ابعاد وسط پنجره.
- فقط USB (با `adb reverse`)، بدون Wi-Fi، بدون صدا، بدون درایور مجازی.
- **دوربین مجازی (اختیاری):** تصویر را مستقیم در Meet / Zoom / Discord به‌عنوان وب‌کم بفرست (منوی راست‌کلیک → vcam؛ نیازمند درایور OBS Virtual Camera).
- **چک آپدیت:** اپ اندروید اگر نسخه‌ی جدیدی منتشر شده باشد، با دکمه‌ی دانلود خبر می‌دهد (اگر اینترنت روشن باشد).

## دانلود

همه‌چیز رایگان است — از صفحه‌ی [Releases](https://github.com/ciomid1097-arch/USBCam/releases/latest) آخرین نسخه را بگیر:

| فایل | توضیح |
|---|---|
| `USBCam.exe` | برنامه‌ی ویندوز (بدون نصب، فقط اجرا کن) |
| `usbcam.apk` | اپ اندروید (نصب مستقیم) |


## ساختار پروژه

```
android/                 اپ اندروید (جاوا + camera2، بدون Gradle)
  build_apk.bat          بیلد APK فقط با SDK نصب‌شده
  app/build/usbcam.apk   خروجی بیلد
pc/
  receiver.py            برنامه‌ی ویندوز (PySide6)
  start_pc.bat           اجرا با پایتون سیستم
  test_stream.py         تست بدون GUI (fps و سلامت پروتکل)
  dist/USBCam.exe        خروجی نهایی (PyInstaller)
```

## استفاده‌ی روزمره

1. گوشی را با USB وصل کن (حالت انتقال فایل هم کافی است؛ USB debugging باید فعال باشد).
2. `pc\dist\USBCam.exe` را اجرا کن (خودش adb را پیدا و `adb reverse` را برقرار نگه می‌دارد).
3. اپ **USBCam** را روی گوشی باز کن — استریم خودکار شروع می‌شود.
4. در OBS: Sources → Window Capture → پنجره‌ی `USBCam` را انتخاب کن.

نکته‌ی OBS: چون پنجره frameless است، در OBS گزینه‌ی «Window» را انتخاب کن و «Client area» را بزن تا دقیقاً فقط تصویر گرفته شود.

## کنترل‌ها

| عمل | روش |
|---|---|
| منوی کنترل | راست‌کلیک روی پنجره |
| جابه‌جایی | درگ با چپ‌کلیک |
| تغییر اندازه | کشیدن لبه‌ها/گوشه‌ها (حداقل 160×90) |
| فول‌اسکرین | منو یا `F11`، خروج با `Esc` |
| آینه کردن (سلفی) | منو → Mirror |
| بستن | منو → Close |

عنوان پنجره (در نوار وظیفه/Alt-Tab) وضعیت را نشان می‌دهد:
`USBCam — phone connected — 1280x720 @ 17 fps`

## اپ اندروید

- صفحه‌ی اپ فقط کنترل است: انتخاب دوربین جلو/عقب، رزولوشن (480p/720p/1080p)، دکمه‌ی شروع/توقف.
- باز کردن اپ، استریم را خودکار شروع می‌کند.
- استریم در یک Foreground Service اجرا می‌شود + PARTIAL_WAKE_LOCK، پس با خاموش شدن صفحه یا رفتن به پس‌زمینه قطع نمی‌شود.
- گزینه‌ی «Use front camera» همان آینه‌سازی گوشی است؛ برای آینه‌ی نهایی از Mirror در ویندوز هم می‌توانی استفاده کنی.

پایین صفحه‌ی اپ، راه تماس با سازنده هست:
- **Developer: workspikestudio@gmail.com**
- **Telegram: @spike_c** — باز شدن مستقیم چت تلگرام

کنترل از راه دور (بدون لمس گوشی):

```bash
adb shell am start -n com.usb.cam/.MainActivity --ei facing 1      # دوربین جلو
adb shell am start -n com.usb.cam/.MainActivity --ei facing 0      # دوربین عقب
adb shell am start -n com.usb.cam/.MainActivity --ei size_index 2  # 1080p
```

## بیلد از سورس

### اندروید (بدون Gradle)

پیش‌نیاز: فقط Android SDK (platform-tools + build-tools 36 + platforms;android-36) و یک JDK.

```bat
cd android
build_apk.bat        →  android\app\build\usbcam.apk
adb install -r android\app\build\usbcam.apk
```

### ویندوز

```bat
python -m venv .venv
.venv\Scripts\pip install PySide6 pyinstaller
.venv\Scripts\pyinstaller --noconfirm --onefile --windowed --name USBCam --distpath pc/dist --workpath build pc/receiver.py
```

## معماری (خلاصه)

- **انتقال:** TCP روی USB با `adb reverse tcp:8420 tcp:8420`. گوشی کلاینت است، PC سرور روی `127.0.0.1:8420`. برنامه‌ی ویندوز هر ۵ ثانیه reverse را دوباره برقرار می‌کند (کابل دوباره وصل شود یا adb ری‌استارت شود، خودش جا می‌افتد).
- **پروتکل:** هر فریم = هدر ۴ بایتی little-endian (طول payload) + payload؛ payload = ۱ بایت شماره دوربین (0=عقب، 1=جلو) + بایت‌های JPEG. کافی است آینده هدر را به‌روز کنی تا انکودر عوض شود (نرم‌افزار فقط یک `QImage.fromData` با داده دارد).
- **تأخیر پایین:** گوشی هر بار فقط جدیدترین فریم را می‌فرستد (فریم‌های عقب‌مانده دور ریخته می‌شوند، `TCP_NODELAY`)؛ سمت PC هم با هر اتصال از سرِ جریان سینک می‌شود، پس تأخیر هیچ‌وقت انباشته نمی‌شود.
- **اندروید:** camera2 خروجی JPEG مستقیم می‌دهد (بدون تبدیل)، ImageReader با `acquireLatestImage` و ظرفیت ۱.
- **اندازه‌ی APK: حدود ۲۱ کیلوبایت.**

## تست

تست بدون GUI (فقط پروتکل و fps):

```bat
.venv\Scripts\python pc\test_stream.py
# listening on 127.0.0.1:8420 …
# phone connected
# first frame: cam_byte=0 jpeg_magic=ffd8 len=289842
# frames=51 fps=17.0 avg=310536B max=315733B
```

نتایج تست‌شده روی Redmi 12 / اندروید 15:
- استریم 720p: ۱۷–۲۰ fps (دوربین عقب ~30 fps بعد از گرم شدن)
- سوییچ جلو/عقب و رزولوشن بدون ری‌استارت اپ ✓
- قطع/وصل اتصال (kill کردن کلاینت/سرور) → اتصال خودکار ✓
- kill کردن adb server → برنامه‌ی ویندوز خودش reverse را برمی‌گرداند ✓

## سازنده

- ایمیل: workspikestudio@gmail.com
- تلگرام: [@spike_c](https://t.me/spike_c)

## عیب‌یابی

- **پنجره می‌گوید «no phone»**: کابل را چک کن، `adb devices` باید دستگاه را نشان بدهد؛ USB debugging مجاز باشد.
- **تصویر نمی‌آید ولی وصل است**: اپ گوشی را باز کن (استریم با باز شدن اپ شروع می‌شود) یا `am start` بالا را بزن.
- **MIUI**: در تنظیمات Battery، اپ USBCam را روی «No restrictions» بگذار تا در استریم طولانی مختل نشود.
- **تأخیر زیاد شد**: پنجره را ببند و باز کن (سینک از سرِ جریان) — در عمل نباید پیش بیاید.
