# วิทยุ: ผลวัดสถานีไทย (2026-09-25, จากคอม ไม่ใช่จาก A07)

สคริปต์: `python tools/radio/measure.py [--list-top] [--json out.json]` (ต้องมี `requests`)
ตรวจแต่ละ stream ด้วย GET ครั้งเดียว timeout 8 วินาที อ่าน 4 KB แรก (HLS: ดึง playlist แล้วดึงลูกตัวแรก) ไม่ลองซ้ำ

## Radio Browser (api.radio-browser.info, ไม่ต้องใช้ key)

- server ที่ตอบ: `de1.api.radio-browser.info` (ชื่อที่ได้จาก DNS ของ `all.api.radio-browser.info` มีแค่ de1 ในวันที่วัด)
- **สถานี countrycode TH ทั้งหมด 131 สถานี, lastcheckok=1: 114, เป็น HLS: 29**
- ค้นชื่อสถานีดังทั่วทั้งฐานข้อมูล (ไม่จำกัดประเทศ) แล้ว **ไม่พบ** Eazy FM, Green Wave, Virgin Hitz / Hitz, Chill FM, Met 107, FM One, Retro, Sanook, Pynk, EFM

## สถานีดัง: อยู่ใน Radio Browser ไหม และเล่นได้จากคอมไหม

| สถานี | ใน Radio Browser | stream | codec / kbps | ตอบจากคอม | ที่มา |
|---|---|---|---|---|---|
| Cool Fahrenheit 93 | มี (5 รายการ, โหวตสูงสุด 4086) | `https://coolism-web.cdn.byteark.com/;stream/1` | MP3 128 | ✅ audio/mpeg + ICY | Radio Browser, homepage coolism.net (ยังไม่ได้ยืนยันจากหน้าเล่นของสถานีเอง) |
| Eazy FM 105.5 | ไม่มี | ยังไม่พบ | | | ❓ ต้องหา URL ทางการ |
| Green Wave 106.5 | ไม่มี | ยังไม่พบ | | | ❓ |
| Virgin Hitz / Hitz 95.5 | ไม่มี | ยังไม่พบ | | | ❓ |
| Chill FM 89 | ไม่มี | ยังไม่พบ | | | ❓ |
| Met 107 | ไม่มี | ยังไม่พบ | | | ❓ |
| FM One 103.5 | ไม่มี (ที่ตรง "103.5" คือ VYBE 103.5 ลำปาง ซึ่งเป็นคนละสถานี) | | | | ❓ |
| Retro FM | ไม่มี | | | | ❓ |
| Sanook | ไม่มี | | | | ❓ |
| Pynk | ไม่มี | | | | ❓ |
| EFM 94 | ไม่มี | | | | ❓ |
| สวท. (Radio Thailand) | มี: FM 88 (ภาษาอังกฤษ), FM 92.5, 93.5 | `https://cdn-th2.login.in.th/shoutcast/8420` / `8425` / `8430` | MP3 128 | ✅ ทั้ง 3 | Radio Browser, homepage prd.go.th (ยังไม่ได้ยืนยันจากหน้าเล่นทางการ) |
| MCOT / อสมท | มี 9 รายการ | ภูมิภาคเลย / ชุมพร: `https://lb-media.mcot.net/RegionRadio/<ชื่อ>.stream_aac/playlist.m3u8` | AAC 128 (HLS) | ✅ 2 สถานีภูมิภาค; ❌ MCOT 100.5 (HLS ลูกตอบ 404), ❌ rstream.mcot.net (ต่อไม่ได้) | Radio Browser, homepage radio.mcot.net |
| ลูกทุ่ง | มี: ลูกทุ่ง รักไทย ๙๐, ลูกทุ่งเน็ตเวิร์ค | `http://radio11.plathong.net:8896/;stream.mp3`, `http://media.login.in.th:8200/;stream.mp3` | MP3 96 / 320 | ✅ ตอบ แต่เป็น **http** แอปยังเล่นไม่ได้ | Radio Browser |

## URL ทางการ (หาจากเว็บหรือตัวเล่นของสถานีเอง แล้วตรวจด้วย `measure.py` ส่วน OFFICIAL)

| สถานี | URL | ชนิด | ตอบจากคอม | ที่มา |
|---|---|---|---|---|
| Cool Fahrenheit 93 | `https://coolism-web.cdn.byteark.com/;stream/1` | MP3 128 (SHOUTcast) | ✅ | JS ตัวเล่นของ coolism.net (ค่าเริ่มต้น linkWeb + `/;stream/1`) ตรงกับ Radio Browser |
| EFM 94 | `https://atimeonline.smartclick.co.th/efm` | AAC+ 96 (Icecast) | 🔶 curl บน Windows ✅ / Python ❌ SSLError (เซิร์ฟเวอร์รับแค่ cipher TLS1.2 แบบ RSA รุ่นเก่า) ต้องตรวจบน A07 | JSON ในหน้า atime.live ช่อง `radio_feed_desktop` |
| Green Wave 106.5 | `https://atimeonline2.smartclick.co.th/green` | AAC+ 128 | 🔶 เหมือน EFM | atime.live เหมือนกัน |
| Hotwave (icy-name "Chill Online") | `https://atimeonline.smartclick.co.th/hotwave` | AAC+ 64 | 🔶 เหมือน EFM | atime.live; หมวด "Chill On" ของ A-Time ไม่มี feed จึงใช้ตัวนี้แทน Chill (🔶 เดา) |
| Met 107 | `https://play-fm107.mcot.net/fm107/fm107.m3u8` | HLS AAC | ✅ | `api-service.mcot.net/api/web/v1/met107/setting/` (API ตั้งค่าของเว็บ met107.mcot.net ไม่ต้อง login) |
| MCOT News FM 100.5 | `https://play-fm1005.mcot.net/fm1005/fm1005.m3u8` | HLS AAC | ✅ | API เดียวกัน `news1005` |
| ลูกทุ่งมหานคร FM 95 | `https://play-fm95.mcot.net/fm95/fm95.m3u8` | HLS AAC | ✅ | `ltmfm95` |
| FM 99 Active / Smooth FM 105.5 / คลื่นความคิด 96.5 | `https://play-fm99…`, `play-fm1055…`, `play-fm965.mcot.net/…m3u8` | HLS AAC | ✅ ทั้ง 3 | `fm99activeradio`, `smoothfm`, `thinkingradio` |
| สวท. FM 92.5 / AM 891 | `https://cdn-edge.iiptvcdn.com/radio_edge/<id>/playlist.m3u8` | HLS AAC 128 | ✅ | nbtplus.prd.go.th/radio → `api-gw-ott.prd.go.th …/radio/detail` ช่อง `stream_url` (ไม่ต้อง login, CDN ของผู้ให้บริการของ กรมประชาสัมพันธ์) |
| ลูกทุ่งรักไทย FM 90 | `https://radio11.plathong.net/8896/;stream.mp3` | MP3 96 | ✅ | `<audio><source>` ในหน้า 90rakthai.com |
| Eazy FM | — | | | ปิดแล้ว: ย้ายไป 102.5 ปี 2024 แล้ว Tero Radio ปิดปลายปี 2025 (mgronline, 27 ธ.ค. 2025) |
| Virgin Hitz / Hitz 95.5 | — | | | FM หยุด 31 มี.ค. 2023 ส่วนออนไลน์เป็นของ Tero Radio ซึ่งปิดแล้ว |
| FM One 103.5 | — | | | หยุดออกอากาศ 1 ม.ค. 2025 (telecomlover) |
| Chill FM 89 | — | | | ไม่พบ stream ทางการ (ดู Hotwave) |
| Pynk, Retro FM, Sanook | — | | | ไม่พบสถานีหรือ stream ทางการ (Sanook เป็นหน้ารวมลิงก์ของคนอื่น) |

ไม่มี URL ไหนที่มี token หรือวันหมดอายุ ไม่มีสถานีไหนที่ต้องสมัครหรือจ่ายเงิน

## สรุป

- รายการตั้งต้นของแอป (`app/src/main/assets/radio_stations.json`) มี 13 สถานีจากตาราง URL ทางการ ทุกสถานีเป็น https
- สถานี A-Time (EFM, Green Wave, Hotwave) ใส่ไว้แต่ 🔶 ยังไม่แน่ว่า A07 จะต่อ TLS รุ่นเก่านั้นได้ ถ้าเล่นไม่ได้ จอจะบอกเหตุผลและไม่ค้าง
- สถานี http ใน Radio Browser (เช่น ลูกทุ่งเน็ตเวิร์ค) แอปยังเล่นไม่ได้ เพราะไม่อนุญาต cleartext (DESIGN.md 5ฏ, รอ Poom ตัดสิน)
- ยังไม่ได้วัดจาก A07 (ห้ามแตะเครื่องในรอบนี้)
