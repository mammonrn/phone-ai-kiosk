# CODEMAP — แผนที่โค้ดสั้น

ผู้ช่วยเริ่มจากไฟล์นี้ + ไฟล์ที่ใบสั่งงานระบุ ห้ามค้นทั้ง repo (CLAUDE.md ข้อ 2)
**อัปเดตทุกครั้งที่เพิ่มหรือย้ายโมดูล** ไม่เกิน 150 บรรทัด ตัวหลักแก้คนเดียว
`K` = `app/src/main/java/com/mammonrn/phoneaikiosk/` · `B` = `server/kiosk_broker/`

## แอป Android (Kotlin, Device Owner + lock task)

**แกนกลาง**
- `K/MainActivity.kt` — หน้าแรก (การ์ด, taskbar, มุมทางออก 10 ครั้ง), เข้า lock task
- `K/KioskScreens.kt` — "Hey Jarvis" จบที่หน้าแรกเสมอ · `K/IdleScreen.kt` หน้าพัก
- `K/LockTaskAllowlist.kt` — แอปที่อยู่บนจอได้ระหว่าง lock (ขอบเขตของตู้) — แตะ = งาน `debugger`
- `K/KioskDeviceAdminReceiver.kt`, `K/PackageReplacedReceiver.kt`, `K/TapGate.kt`

**จุดเสียบที่ใช้ร่วม (แก้แล้วต้อง ui-check ทุกหน้าที่ใช้)**
- `K/ui/ToolWindow.kt` — หน้าต่างของแอปเล็กในแผงควบคุม (แถบหัว, X, "กลับหน้าหลัก")
- `K/ui/Origin.kt` — จำว่าเปิดแอปมาจากไหน ให้ปุ่ม X กลับไปถูกที่
- `K/calc/SlideDeck.kt` (+`SlideHost.kt`) — หน้าแบบปัด ใช้ในเครื่องคิดเลขทุกหมวดและแอปเวลา
- `K/ui/PagedPanel.kt`, `K/ui/Pages.kt` — การ์ดหลายหน้าแบบปัด (ไม่มีแท็บ)
- `K/ui/JarvisBadge.kt` — สัญลักษณ์สถานะจาร์วิส 6 รูปร่างบนทุกหน้า · `K/voice/JarvisStatus.kt`
- `K/ui/TaskbarButtons.kt`, `CardBoard.kt`, `Retro.kt`, `RetroType.kt`, `UiScale.kt`, `TouchAreas.kt`
- `K/ui/ScreenWords.kt` — ข้อความบนจอ (ภาษาเขียน มี test จับภาษาพูด) · `ScreenDate.kt` วันที่ พ.ศ.
- `K/media/MediaKinds.kt` → `object PlayerChoice` — ที่เดียวที่ตัดสินว่าไฟล์เล่นด้วย Media3/VLC/ไม่รองรับ
- `K/media/VideoRules.kt` — กติกาวิดีโอ (720p, AV1, หมุนจอ) แบบ Kotlin ล้วน

**แผงควบคุม / ตั้งค่า**
- `K/settings/SettingsActivity.kt` — แผงควบคุม: `Tile.One` / `Tile.Folder` (popup), ไฟฉาย, หน้ายืนยันตัวตน, ที่มาข้อมูล
- `LightsPage.kt` ไฟในบ้าน · `WifiPanel.kt` · `Bluetooth*.kt` (Activity/Link/Model/System/Tile/Voice)

**ยืนยันตัวตน (`K/auth/`) — งาน `debugger`**
- `VerifyActivity.kt` (`skipsCamera`, `fresh`) · `AccessGrant.kt` สิทธิ์ 1 ชม. · `IdentityGate.kt` ลบต้องยืนยัน
- `AuthStore.kt`, `FaceScanner.kt`, `FaceEmbedder.kt`, `FaceMath.kt`, `BlinkCheck.kt`, `PatternLock.kt`, `PatternPad.kt`, `SecretBox.kt` (Keystore)

**เสียง / จาร์วิส (`K/voice/`)**
- `VoiceService.kt` — บริการหลัก: คำปลุก → อัด → broker → พูด · `WakeWord.kt` · `WakePause.kt` (lease พักคำปลุก)
- `CaptureMachine.kt`, `Recorder.kt`, `PreRoll.kt`, `MicTap.kt`, `AudioHelpers.kt`
- `TurnPipeline.kt` — หนึ่งรอบถาม-ตอบ · `Broker.kt` — HTTP ไป broker (ส่ง `X-Kiosk-Screen`) · `TokenStore.kt`
- `Speaker.kt` · `VoiceState.kt` · `VoiceStats.kt` · `SoakProbe.kt` · `ScreenWaker.kt`
- `DashboardState.kt` — payload `/v1/dashboard` → ข้อความบนการ์ด (Kotlin ล้วน)
- `KioskLocation.kt` ตำแหน่งปัจจุบัน · `MapsLauncher.kt` · `CameraAppLauncher.kt` · `DeviceSttProbe.kt`

**แอปในแผงควบคุม** (แต่ละโฟลเดอร์: `*Activity` หน้าจอ · `*Voice` คำสั่งเสียงในหน้า · `*Store/*Book` เก็บข้อมูล · `*Rules/*Math` ตรรกะล้วนมี test)
- `media/` — เพลง (`MusicActivity/Service/Library/Voice`, `PlayQueue`, `Playlist*`), วิดีโอ (`VideoActivity/Service/Voice`, `VlcDeck`), `Mp4Sniff`, `MpegSniff`, `fx/` EQ+สเปกตรัม
- `files/` — ตัวจัดการไฟล์ Norton (`FilesActivity`, `FolderBrowser`, `FolderSource`, `FileOps`, `StorageAreas`), NAS (`Nas`, `NasSource`), `ImageViewerActivity`
- `drive/` — Google Drive ต่อตรง (`DriveAuth` token เข้ารหัส, `DriveApi`, `DriveModel`, `DriveSource`, `ConsentWindow` gms ชั่วคราว 5 นาที)
- `calc/` — เครื่องคิดเลข (`CalculatorActivity`, `CalcEngine`), หมวด Electrical/Solar/Units/Money/Rates (`*Pages.kt` = หน้าใน SlideDeck), `RateStore` ราคาจากเน็ต
- `timer/` แอปเวลา (ปลุก+จับเวลา+นับถอยหลัง) · `alarm/` ตั้งปลุกจริง (`AlarmScheduler`, `AlarmRinger`, `AlarmBook`)
- `calendar/` ปฏิทิน (`CalendarModel`, `Lunar` วันพระคำนวณ) · `notes/` โน้ต/รายการซื้อของ · `recorder/` อัดเสียง (`MemoWriter`, `RecorderRules`)
- `radio/` วิทยุ (`RadioBook` สถานีเริ่มต้น, `RadioBrowser`, `RadioService`) · `compass/` ระดับน้ำ · `camera/`
- `social/` Facebook/Instagram เข้าชั่วคราวหลังสแกน (`SocialVisit`) — งาน `debugger`
- `home/` การ์ดหน้าแรก (`HomeCard`, `HomeGate`, `HomeSettings`, `HomeSummary`)
- `weather/` การ์ดอากาศ (`WeatherAlerts` ⚠/◇/▸, `WeatherIcons`, `PlaceName`, `PlaceLookup`, `EmergencyNumbers`)

**debug เท่านั้น** `app/src/debug/.../TestTriggerReceiver.kt`, `uicheck/UiWalk.kt` — ตัวเดินหน้าจอของ `scripts/ui-check` (หน้าใหม่ต้องเพิ่มที่นี่)

**test** `app/src/test/java/com/mammonrn/phoneaikiosk/<Class>Test.kt` (แบนราบ ไม่แยกโฟลเดอร์ ยกเว้น `weather/`)

## Broker (Python, `server/`)

**แกนกลาง**
- `B/server.py` — HTTP listener (loopback) → `B/service.py` (ไม่มี socket)
- `service.handle_chat` — ลำดับตัวจับคำสั่งในโค้ดก่อนถามโมเดล: calendar_add → timers → radio_cmd → social_cmd → bluetooth_cmd → music → video → holidays_q → emergency → dams → flood_forecast → radar → local_rain → alerts → … → `llm.py`
- `service.handle_stt / handle_tts / handle_dashboard / handle_grant / handle_calendar_* / handle_home_*`
- `B/screen_context.py` — เติมคำให้คำสั่งสั้นตามหน้าที่ถาม (`X-Kiosk-Screen`) แล้วส่งให้ตัวจับเดิม
- `B/__main__.py` — คำสั่ง CLI บน VPS (`keys set`, `probe`, `forecast-score`, …) · `config.py` · `store.py` SQLite · `limits.py` · `auth.py` · `identity.py` (`GRANT_SECONDS`) · `vault.py` · `envfile.py`

**จาร์วิส / เสียง**
- `persona.py` prompt · `llm.py` · `brevity.py`, `shorten.py`, `register.py`, `redact.py`, `voicetext.py`, `wordcut.py`, `pronounce.py`
- STT: `stt_router.py` (Qwen หลัก, Groq สำรอง) · `qwen_stt.py` · `stt.py` (Groq) · `stt_hints.py` · `speech_gate.py` · `thai_numbers.py` · `maps_rescue.py`
- TTS: `tts.py` · `tail_check.py` · `oggopus.py` · ทดลอง: `botnoi.py`, `tts_ab.py`, `google_stt.py`, `qwen31_stt.py`, `stt_compare.py`
- ค่าใช้จ่าย: `pricing.py`, `free_tier.py`, `measure.py` · `analysis.py`, `soak.py`

**ตัวจับคำสั่งเสียง (หนึ่งไฟล์ต่อเรื่อง มี `match()`)**
`alarms.py` `timers.py` `music.py` `video.py` `radio_cmd.py` `notes.py` `social_cmd.py` `bluetooth_cmd.py` `lights.py` `home_control.py` `calendar_add.py` `calendar_read.py` `holidays_q.py` `emergency.py` `clock.py` `actions.py` (ลิงก์/scheme ที่ห้าม)

**หน้าจอ / ข้อมูล**
- `dashboard.py` — payload ของการ์ดหน้าแรก (ทอง น้ำมัน `oil.py` คริปโต อากาศ)
- อากาศ: `forecast.py` → `blend.py` (Open-Meteo+NWP) → `forecast_text.py` · `nwp.py` (TMD NWP, key `tmd-nwp`) · `uv_check.py` · `weather_checks.py`
- ค่าวัดจริง: `obs.py` (รวม) ← `metar.py`, `station_met.py` (ThaiWater) · ฝน: `thaiwater_rain.py`, `radar.py`, `local_rain.py`
- เตือนภัย: `alerts.py` (⚠) · `flood_forecast.py` (◇) · `gistda_flood.py` · `dams.py` · `verify.py` ให้คะแนนพยากรณ์ตามพื้นที่
- `tls.py` + `certs/` ใบกลางสำหรับเว็บที่ส่งสายใบรับรองไม่ครบ (ห้ามปิดการตรวจ)
- `probe.py` — `probe tmd|gistda` · `local_facts.py`
- บ้าน: `ewelink.py`, `ewelink_cli.py`, `tuya.py`, `tuya_cli.py`, `home_settings.py` · Google: `google_auth.py`, `calendar_app.py`

**test** `server/tests/test_<module>.py`, ข้อมูลจริงใน `server/tests/data/<source>/` · ติดตั้ง `server/install/`

## สคริปต์และเครื่องมือ
- `scripts/ui-check` — เดินหน้าจอบน A07 (`ONLY=<หน้า,...>`), สรุปผลด้วย `tools/ui/report.py`
- `scripts/social-check`, `scripts/video-frames-repeat`
- `tools/voice/` — matrix คำสั่งเสียง (`matrix_rows.py`, `gen_matrix.py`, `full_route.py`), คลิป STT (`make_clips.py`, `run_stt_clips.sh`, `score.py`)
- `tools/radio/measure.py` · `tools/libvlc/build-lgpl.sh` · `server/tools/` (`build_flood_freq.py`, `build_provinces.py`)
- CI: `.github/workflows/` — Broker tests (pytest + /healthz), Build Android APK (unit test + เพดาน 120 MiB)

## เอกสาร
`DESIGN.md` กติกาหน้าจอ · `TESTING.md` วิธีทดสอบ + deploy · `docs/QUEUE.md` คิวงาน · `docs/research/` ผลค้นคว้า · `VOICE.md` `WAKEWORD.md` `SOAK.md` `GOOGLE_HOME.md`
