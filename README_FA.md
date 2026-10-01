# کلاینت اندروید GooseRelayVPN

این مخزن، کلاینت اندروید GooseRelayVPN است که هسته GooseRelay را از طریق Go mobile اجرا می‌کند و رابط کاربری کامل برای مدیریت VPN، پروفایل‌ها، لاگ‌ها و تنظیمات ارائه می‌دهد.

- پروژه اصلی (هسته): https://github.com/kianmhz/GooseRelayVPN
- این مخزن: پیاده‌سازی کلاینت اندروید

## این اپ چه کاری انجام می‌دهد؟

این اپ یک SOCKS5 محلی روی اندروید ایجاد می‌کند و ترافیک TCP را از معماری GooseRelay عبور می‌دهد:

1. ترافیک برنامه/مرورگر -> SOCKS5
2. فریم‌بندی رمزنگاری‌شده GooseRelay (با کلید AES)
3. عبور HTTPS از مسیر endpointهای گوگل (Apps Script)
4. سرور VPS شما خروجی واقعی را برقرار می‌کند

این مسیر با `VpnService` اندروید یکپارچه شده تا ترافیک کامل یا انتخابی از تونل عبور کند.

## امکانات اصلی

- یکپارچه‌سازی VPN اندروید (`VpnService` + `tun2socks`)
- پیکربندی مبتنی بر پروفایل
- وضعیت و تله‌متری در صفحه Home
- تب Logs برای عیب‌یابی Android/Core
- حالت اتصال `VPN` یا `PROXY`
- تانلینگ انتخابی اپ‌ها (`Split Tunneling`، پیش‌فرض: خاموش و بدون لیست پیش‌فرض) و اشتراک اینترنت (`Internet Sharing`)
- رهگیری محلی DNS با `Fake DNS` و امکان DNS سفارشی
- اشتراک‌گذاری پروفایل با `goose-relay://` و کلیپ‌بورد
- سابسکریپشن راه‌دور پروفایل (فقط با Refresh دستی)

## مدل پیکربندی پروفایل

فیلدهای پروفایل با مدل کلاینت GooseRelay هماهنگ است:

```json
{
  "name": "My VPN Profile",
  "debug_timing": false,
  "socks_host": "127.0.0.1",
  "socks_port": 1080,
  "socks_user": "",
  "socks_pass": "",
  "google_host": "216.239.38.120",
  "sni": ["www.google.com", "mail.google.com", "accounts.google.com"],
  "script_keys": [
    "REPLACE_WITH_DEPLOYMENT_ID",
    "OPTIONAL_SECOND_DEPLOYMENT_ID|account@example.com"
  ],
  "tunnel_key": "REPLACE_WITH_OUTPUT_OF_scripts_gen-key.sh",
  "coalesce_step_ms": 0,
  "idle_slots_per_bucket": 2
}
```

نکات:
- در UI، `script_keys` باید خط‌به‌خط وارد شود؛ هر خط `ID` یا `ID|account` است.
- هر دو فیلد `socks_user` و `socks_pass` باید با هم خالی یا با هم پر باشند.
- فیلد `tunnel_key` باید دقیقاً `64` کاراکتر hex باشد و با سمت سرور یکی باشد.
- مقدار `socks_port` بین `1` تا `65535` و مقدار `idle_slots_per_bucket` بین `1` تا `3` است.
- روش‌های Import/Export در اپ: فایل JSON، کلیپ‌بورد (`JSON` یا `goose-relay://`) و سابسکریپشن از URL راه‌دور.
- اشتراک `goose-relay://` فقط سه فیلد `name` و `script_keys` و `tunnel_key` را منتقل می‌کند.
- سابسکریپشن راه‌دور فقط با دکمه Refresh دستی به‌روز می‌شود؛ همگام‌سازی خودکار وجود ندارد.

## جریان راه‌اندازی اصلی (الزامی)

قبل از استفاده از کلاینت اندروید، زیرساخت پروژه اصلی باید آماده باشد:

1. آماده‌سازی VPS و اجرای `goose-server`
2. دیپلوی `apps_script/Code.gs` و گرفتن Deployment ID
3. ساخت کلید با `scripts/gen-key.sh`
4. وارد کردن `script_keys` و `tunnel_key` در پروفایل اندروید
5. اتصال در اپ از صفحه Home (در حالت `VPN` نیازی به تنظیم دستی پراکسی نیست؛ فقط در حالت `PROXY` باید پراکسی را دستی ست کنید)

راهنمای کامل زیرساخت در پروژه اصلی:
- https://github.com/kianmhz/GooseRelayVPN

## ساخت محلی اندروید

پیش‌نیازها:
- Android Studio
- JDK 17
- Go 1.25+
- Android SDK / NDK

ساخت AAR (پل Go mobile):

```bash
bash android/build_go_mobile.sh
```

ساخت APK دیباگ:

```bash
cd android
./gradlew :app:assembleDebug
```

## انتشار / CI

Workflowهای این مخزن:
- `.github/workflows/android-ci.yml`
- `.github/workflows/release-manual.yml`

> فایل `release.yml` وجود ندارد؛ انتشار با اجرای دستی `release-manual.yml` انجام می‌شود.

Secretهای لازم برای انتشار دستی امضاشده:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## عیب‌یابی

- اگر بعد از قطع اتصال، اتصال مجدد با خطای اشغال بودن پورت SOCKS مواجه شد:
  - چند ثانیه صبر کنید و دوباره وصل شوید.
  - مطمئن شوید اپ دیگری از همان پورت استفاده نمی‌کند.
- اگر اتصال روی حالت آماده‌سازی ماند:
  - مقادیر `script_keys` و `tunnel_key` را بررسی کنید.
  - لاگ‌ها را در تب Logs ببینید.
- اگر ترافیک عبور نمی‌کند:
  - مجوز VPN اندروید را بررسی کنید.
  - تنظیمات Split Tunnel را بررسی کنید.

## Credits

1. پروژه اصلی:
   - https://github.com/kianmhz/GooseRelayVPN

2. پروژه‌ای که ایده پروژه اصلی از آن الهام گرفته شده است:
   - https://github.com/masterking32/MasterHttpRelayVPN
