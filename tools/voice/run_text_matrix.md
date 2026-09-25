# ทดสอบคำสั่งเสียงแบบพิมพ์ (text matrix) บน A07

ส่งประโยคเป็น **ข้อความ** เข้าเส้นทางจริงของจาร์วิส (`/v1/chat` → พูดคำตอบ → ทำ action) โดยไม่ต้องพูด
ใช้ได้เฉพาะ **debug APK จาก CI** (ตัวรับ `TestTriggerReceiver` ไม่มีใน release)

## ไฟล์

| ไฟล์ | อยู่ที่ | คืออะไร |
|---|---|---|
| `matrix_rows.py` | repo (`tools/voice/`) | ประโยคทดสอบ 81 แถว ครอบทุกคำสั่งเสียง |
| `gen_matrix.py` | repo | รันทุกแถวผ่านตัวจับคำสั่งของ broker แบบออฟไลน์ ตามลำดับเดียวกับ `service.handle_chat` → `commands.tsv` |
| `full_route.py` | repo | รันทุกแถวผ่าน `handle_chat` ตัวจริง บน broker ชั่วคราวที่ไม่มี eWeLink/Google/สิทธิ์ยืนยันตัวตน และโมเดลปลอม เพิ่มคอลัมน์ `full_route_*` |
| `commands.tsv` | **นอก repo** (`%TEMP%\k61\stt4\`) | ผลลัพธ์: คาดหวัง / SAFE / วิธีทดสอบให้ปลอดภัย / ผลออฟไลน์ |

สร้างใหม่ (บน PC, venv ที่มีของแบบ CI + `tzdata`):
```bash
python tools/voice/gen_matrix.py "$TEMP/k61/stt4/commands.tsv"
python tools/voice/full_route.py "$TEMP/k61/stt4/commands.tsv"
```

คอลัมน์: `id feature thai_text expected_route expected_action_type expected_reply_gist SAFE how_to_test_safely speakable key_part offline_route offline_action offline_agrees offline_detail full_route_reply full_route_action model_called full_route_agrees`

- `expected_route` = `code:<feature>` (ตัดสินในโค้ด ไม่ถามโมเดล) หรือ `model`
- `SAFE=yes` = สั่งจริงแล้วไม่มีอะไรในโลกจริงเปลี่ยน (อ่าน, ถาม, ค้นหาที่ไม่เจอ, หยุด/เล่นเพลง)
- `SAFE=NO` = ต้องทำตาม `how_to_test_safely` (หรือดูผลออฟไลน์อย่างเดียว) — กฎ CLAUDE.md: คำสั่งที่มีผลกับของจริงทดสอบแบบจำลองเท่านั้น

## คำสั่ง adb

```bash
PKG=com.mammonrn.phoneaikiosk.debug
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "'เพิ่ม นม ในรายการซื้อของ'" -p $PKG
```
- `-p $PKG` **ต้องมี** (ไม่มีแล้ว Android ขึ้นว่าส่งสำเร็จ แต่แอปไม่ได้รับ)
- ข้อความที่มีช่องว่างต้องมี `'…'` ซ้อนใน `"…"` เพราะ `adb shell` แยกคำอีกรอบบนเครื่อง
- ยาว 1-200 ตัวอักษร เกินนั้นแอปไม่ส่ง
- ข้าม STT (ไม่ผ่านตัวถอดเสียง ไม่ผ่านด่าน speech gate) — ทดสอบเฉพาะ "เข้าใจคำสั่งไหม" ส่วนการถอดเสียงอยู่ในชุดเทียบข้างล่าง

**สิ่งที่เห็น / log (ความเป็นส่วนตัว: log ไม่มีคำพูด):**
- จอ: ประโยคและคำตอบขึ้นในการ์ดจาร์วิสบนหน้าแรก และจาร์วิสพูดคำตอบ — **อ่านคำตอบจากจอ**
- logcat `KioskVoice`: `typed turn chars=N`, `turn finished outcome=…`, `action <type> …` (ตัวเลขและสถานะเท่านั้น)
- สถานะล่าสุด:
  ```bash
  adb shell dumpsys activity service $PKG/com.mammonrn.phoneaikiosk.voice.VoiceService | grep -E "last-action|last-cancel|heard"
  ```
  `last-action` เช่น `set_alarm:ok`, `music:play:not-done`, `note_add:shopping:ok`, `verify_identity:asked`
- broker (VPS, Poom รันเอง): `sudo journalctl -u kiosk-broker -n 20 | grep intent` — บรรทัด `intent … camera= alarm= calendar= maps= calendar_add= notes=` เหตุผลล้วน ไม่มีคำพูด

## รันทีละแถว (Git Bash)

```bash
M="$TEMP/k61/stt4/commands.tsv"; PKG=com.mammonrn.phoneaikiosk.debug
ask()  { t=$(grep "^$1	" "$M" | cut -f3); echo "$1: $t"
         adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "'$t'" -p $PKG >/dev/null; }
show() { adb shell dumpsys activity service $PKG/com.mammonrn.phoneaikiosk.voice.VoiceService | grep -E "last-action|last-cancel"; }
ask C03; sleep 12; show
```
(ใน `grep "^$1	"` ช่องหลัง `$1` คือปุ่ม Tab)

รอให้พูดจบก่อนส่งแถวถัดไป (~10-15 วินาที) จดผลลงคอลัมน์ใหม่ `phone_reply`, `phone_action`, `pass`

## ลำดับที่แนะนำ

1. **แถว SAFE=yes** วนได้เลย ยกเว้น K01-K03 (เปิดหน้ายืนยันตัวตน — ทำด้วยมือ, **ห้ามแคปจอตอนกล้องเปิด**) และ D90/D91 (ใช้ตามข้อ 3):
   ```bash
   for id in C03 C04 A05 A09 A10 K04 D03 D04 D05 N05 N06 N07 N08 N09 N10 L04 L06 L07 \
             M03 M04 M06 M07 M08 M09 M10 M11 M01 M05 V02 V03 V04 V05 V04 \
             W01 W02 W03 T01 T02 G01 O01 F01 X01 X02 X04 X05 Q01 Q02; do ask $id; sleep 15; show; done
   ```
   M01/M02/M12/M13, V01/V02/V05/V06 อาจเล่นสื่อจริงถ้าเจอ → ปิดด้วย M05 / V04 · M09 เพิ่มเสียงหนึ่งขั้น → M10 คืน
2. **ปลุก (A01 A02 A04 A06 A07):** ทุกอันตั้งชื่อ "ทดสอบ" และเวลาห่างหลายชั่วโมง → ส่ง `TEST_ALARM_CLEAR` ทันทีหลังชุดนี้
   ```bash
   adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ALARM_CLEAR -p $PKG
   ```
   A03 (ชื่อ "ไปทำงาน") และ **A08 "ปิดปลุกทั้งหมด" (ปิดปลุกจริงของ Poom)** — ดูผลออฟไลน์อย่างเดียว
3. **ลงนัด (D01, D02):** จาร์วิสขอยืนยันตัวตนก่อน → ผ่านแล้วจะถาม "จะลงนัด … พูดว่า ยืนยัน" → ส่ง `ask D90` ("ยกเลิก") ทันที ต้องได้ "ยกเลิกแล้ว ไม่ได้ลงนัดครับ" **ห้ามส่ง "ยืนยัน"**
4. **ไฟ (L01 L02 L03 L05 X03):** Poom รันบน **VPS** ก่อน `python -m kiosk_broker ewelink-control off` → ทุกคำสั่งต้องได้ "ตอนนี้ปิดการสั่งไฟไว้ครับ" (หรือถามกลับ → ตอบ "ยกเลิก") → จบแล้ว `ewelink-control on`
   ชื่อไฟที่ไม่มีจริง (L05) **ไม่ปลอดภัยด้วยตัวเอง**: ถ้าบ้านมีไฟดวงเดียว กฎ only-one จะสั่งดวงนั้น
5. **โน้ต (N01-N04):** เพิ่มบรรทัดจริงในรายการของมือถือ → เปิด "โน้ต" ในแผงควบคุมแล้วเอาบรรทัดออก (ไม่ต้องยืนยันตัวตน)
6. **กล้อง / แผนที่ (C01 C02 P01-P05):** เปิดแอปอื่นทับ kiosk → `adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_HOME -p $PKG` ทันที แผนที่เสียค่าโมเดลแถวละครั้ง

**จบ:** TEST_HOME, lock task ต้อง `LOCKED`, `TEST_ALARM_CLEAR`, `ewelink-control on` (VPS), เอาบรรทัดโน้ตทดสอบออก, ปิดเพลง/วิดีโอ, คืนระดับเสียง

## ฟีเจอร์ที่ไม่มีคำสั่งเสียง (ห้ามเพิ่มในงานนี้)

วิทยุ, นับถอยหลัง/จับเวลา, ไฟฉาย, อัดเสียง, เครื่องคิดเลข/แปลงหน่วย/โซลาร์, ระดับน้ำ, WiFi, Google Drive, ตัวจัดการไฟล์, ราคาทอง (ทองอยู่บนการ์ดแต่ **ไม่อยู่ใน prompt** — ถามแล้วโมเดลไม่มีตัวเลข)
แถว X01-X05, G01 ตรวจว่าโมเดล **ไม่อ้างว่าทำ** สิ่งที่ระบบไม่มี

## ชุดเทียบตัวถอดเสียง (STT)

- `make_clips.py OUT_DIR` → เสียง edge-tts (ฟรี ไม่ต้องสมัคร) ทุกแถวที่ `speakable=yes` × ทุกเสียงไทย, WAV 16 kHz mono 16-bit + `manifest.tsv` (ไม่ commit เสียง)
- `run_stt_clips.sh CLIPS_DIR RESULTS.tsv groq-hints qwen` → ส่งทุกคลิปเข้า `/v1/stt` จริงด้วย `TEST_STT_PROVIDER` + `TEST_STT_FILE` ได้ `RESULTS.tsv` (file, provider, transcript, ms, gate)
- `score.py MANIFEST.tsv RESULTS.tsv` → ตรงทั้งประโยค %, คำสำคัญถูก %, CER, latency median/p90, แยกเพศ/เสียง/ฟีเจอร์, คู่ที่ต่างกัน, แย่สุด N อัน
- เทสต์ตัว normalise: `python -m pytest tools/voice -q`
