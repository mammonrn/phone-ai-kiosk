# ติดตั้ง kiosk broker (เฟส 2)

> **เฟส 2 ขึ้น production แล้ว** ทดสอบจริงบน VPS 45.76.157.64 เมื่อ 22 ก.ย. 2569
>
> | ข้อ | ผล |
> |---|---|
> | `install.sh` | ผ่าน · thaitrack/monthreport ไม่เปลี่ยนสถานะ |
> | คีย์ Haiku | ใส่แล้ว · `linuxuser` อ่านไฟล์ไม่ได้ (Permission denied) |
> | `selftest` ยิง Anthropic จริง | ผ่าน in=1107 out=52 cost=$0.001367 |
> | service | `active (running)` · listening 127.0.0.1:8770 budget $5.00/month |
> | DNS | `kiosk.xn--l3cgts1b3bzcvf.com` → 45.76.157.64 |
> | ใบรับรอง | `certonly --webroot` สำเร็จ หมดอายุ 21 ธ.ค. 2569 |
> | `nginx -t` + reload | ผ่าน · thaitrack **200** · monthreport **302** |
> | HTTPS `/healthz` | 200 ทั้งผ่านชื่อโดเมนจริงและ `--resolve` |
> | จากคอม Windows | token ถูก → reply ไทย + `action=null` · ไม่มี token → 401 · token ผิด → 401 · `/` → 404 · http → 301 |
>
> **รอบ deploy ที่สอง (22 ก.ย. 2569):** service active · `/healthz` ตอบ ok ·
> **INPUT TOKENS ลดจาก 1107 เหลือ 656** (−41%) cost $0.000941 ·
> `certbot renew --dry-run --run-deploy-hooks` สำเร็จทั้งสามใบ (kiosk, ubet89.house,
> xn--l3cgts1b3bzcvf.com) · หลังทดสอบ certbot: thaitrack 200 · monthreport 302 ·
> kiosk healthz 200
>
> **สองอย่างที่รอบนั้นเจอและแก้แล้ว:**
> 1. `prompt-size` พังกับ API จริง — `400 messages.0: user messages must have
>    non-empty content` เพราะส่ง user message ว่างเพื่อวัด prompt ตัวเดียว
>    **stub ในเทสต์ยอมรับ แต่ของจริงไม่ยอม = เขียวหลอก** ตอนนี้ stub ปฏิเสธเหมือนของจริงแล้ว
> 2. คำตอบจริงยังปน "ค่ะ": `"สวัสดีครับ ผมพร้อมช่วยเหลือค่ะ มีอะไรให้ผมช่วยได้บ้างครับ"`
>    → เพิ่มการบังคับถ้อยคำด้วยโค้ดหลังได้คำตอบ ไม่พึ่ง prompt อย่างเดียว

ทุกขั้นในไฟล์นี้ **Poom ต้องรันเองบน VPS** เพราะต้องใช้ sudo

```bash
ssh poom@45.76.157.64
```

ผมไม่มี sudo จึงเตรียมสคริปต์กับไฟล์ config ไว้ให้ครบ แต่รันเองไม่ได้
สิ่งที่ผมทดสอบแล้วกับสิ่งที่ยังต้องรอขั้นตอนพวกนี้ อยู่ท้ายไฟล์

> **ทำไมไม่มีขั้น `loginctl enable-linger`** — ตอนแรกวางแผนไว้ว่าจะใช้ systemd
> user service แต่เปลี่ยนมาใช้ **system unit** (`User=kioskbroker`) แทน เพราะ
> (1) เริ่มเองตอนบูตโดยไม่ต้อง linger (2) ไฟล์ unit เป็นของ root → ตัว broker
> แก้ด่านความปลอดภัยของตัวเองไม่ได้ (3) ใช้ sandbox ของ systemd ได้
> เช่น `ProtectHome=tmpfs` ที่ทำให้ `/home/hermes` **ไม่มีอยู่** ในสายตาโปรเซสนี้
> ถ้าอยากได้ user service แบบเดิมบอกได้ ผมแก้ให้

---

## ขั้น 0 — ตรวจ firewall (อ่านอย่างเดียว ห้ามแก้)

ค้างมาจากรอบสำรวจ: thaitrack ฟังที่ `*:3000` ไม่ใช่ `127.0.0.1` ต่างจาก
monthreport ต้องรู้ว่า ufw กันพอร์ตนั้นจากภายนอกไหม

```bash
sudo ufw status verbose
```

**ห้ามแก้กฎ firewall ในงานนี้** แค่ส่งผลลัพธ์มาให้ดู ถ้า 3000 เปิดสู่ภายนอก
จะเป็นงานแยกอีกใบ

---

## ขั้น 1 — รันสคริปต์ติดตั้ง

```bash
cd ~/phone-ai-kiosk && git pull
sudo bash server/install/install.sh
```

(ถ้ายังไม่มี repo บนเครื่อง: `git clone https://github.com/mammonrn/phone-ai-kiosk.git ~/phone-ai-kiosk`)

สคริปต์จะ:
- สร้าง user `kioskbroker` (`--system`, ไม่มี shell, **ไม่อยู่กลุ่ม** shared / linuxuser /
  hermes / builder / sudo / adm — ถ้าอยู่จะหยุดทันที)
- ลง `python3.12-venv` ถ้ายังไม่มี แล้วสร้าง venv + ลง `anthropic`
- ก๊อปโค้ดไป `/home/kioskbroker/app` และ `pricing.json` ไป config dir
- เขียน `config.json` และ **ไฟล์ `env` เปล่า โหมด 0600**
- ติดตั้ง systemd unit (enable แต่ยังไม่ start)
- เขียน `/etc/nginx/sites-available/kiosk` **แต่ยังไม่ทำ symlink ยังไม่ reload**
- เช็ค thaitrack กับ monthreport **ก่อนและหลัง** ถ้าค่าเปลี่ยนจะหยุดและฟ้อง

สคริปต์ **ไม่แตะ** vhost ของ thaitrack และ monthreport และไม่ reload nginx

---

## ขั้น 2 — ใส่คีย์ Anthropic

ใช้คีย์ Haiku ตัวเดิมที่หัวหน้าอนุมัติ

```bash
sudo -u kioskbroker nano /home/kioskbroker/.config/kiosk-broker/env
```

ให้เหลือบรรทัดเดียว (ไม่ต้องมีเครื่องหมายคำพูด):

```
ANTHROPIC_API_KEY=<วางคีย์ที่นี่>
```

ตรวจว่าคนอื่นอ่านไม่ได้:

```bash
sudo ls -l /home/kioskbroker/.config/kiosk-broker/env     # ต้องเป็น -rw------- kioskbroker
sudo -u linuxuser cat /home/kioskbroker/.config/kiosk-broker/env   # ต้องขึ้น Permission denied
```

บรรทัดที่สองนั้นสำคัญ — มันคือการพิสูจน์ว่า**ผมเองก็อ่านคีย์ไม่ได้**

---

## ขั้น 3 — ทดสอบว่าคุยกับ Haiku ได้จริง (ยิงจริง 1 ครั้ง)

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker selftest
```

ต้องได้คำตอบภาษาไทยสั้นๆ พร้อม **INPUT TOKENS** และค่าใช้จ่ายที่คิดจาก `pricing.json`

`selftest` **ไม่บันทึกลงบัญชีงบ** ของมือถือ

### วัดขนาด prompt (ฟรี ไม่เสียเงินเลย)

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker prompt-size
```

ใช้ `count_tokens` ซึ่ง[เอกสารทางการระบุว่าฟรี](https://platform.claude.com/docs/en/build-with-claude/token-counting)
และมี rate limit แยกจากการสร้างข้อความ รันกี่ครั้งก็ได้

ตัวเลขที่วัดได้จริงบน production:

| | input tokens |
|---|---|
| prompt เดิม (1,132 ตัวอักษร) | **1,107** |
| prompt ปัจจุบัน (631 ตัวอักษร) | **656** |

**−41%** ต่อคำถาม

คำสั่งจะแยกให้เห็นสามตัวเลข: prompt + placeholder, placeholder ตัวเดียว,
และ prompt ล้วนที่ได้จากการลบกัน — เพราะ **API ไม่ยอมรับ user message ว่าง**
(`messages.0: user messages must have non-empty content`) จึงวัด prompt เดี่ยวๆ ตรงๆ ไม่ได้

### ทำไมไม่ใช้ prompt caching (ตรวจจากเอกสารทางการแล้ว)

**Haiku 4.5 ไม่ cache prefix ที่เล็กกว่า 4,096 โทเคน และไม่แจ้ง error เมื่อปฏิเสธ**
ต้องดูเองว่า `cache_creation_input_tokens` กับ `cache_read_input_tokens` เป็น 0 ทั้งคู่
([prompt caching docs](https://platform.claude.com/docs/en/build-with-claude/prompt-caching))

prompt ของเราอยู่ราว 600 โทเคน จึงต่ำกว่าเกณฑ์มาก **ใส่ `cache_control` ไปก็ไม่เกิดอะไรขึ้น**

ถ้าจะถ่วง prompt ให้ถึง 4,096 โทเคนเพื่อให้ cache ได้ คิดเป็นหน่วยโทเคนเทียบเท่า
(cache read = 0.1× · cache write 5 นาที = 1.25×):

- ไม่ cache: **600** ต่อคำถาม
- cache ได้: อ่าน 4,096 × 0.1 = **410** ต่อคำถาม แต่ต้องจ่ายค่าเขียน 4,096 × 1.25 = **5,120**
  ทุกครั้งที่ cache หมดอายุ (อายุ 5 นาที)

จุดคุ้มทุน: `5120 + 410n < 600(n+1)` → **n > 24** คือต้องถามเกิน 24 คำถาม
ภายในทุกช่วง 5 นาที ถึงจะเริ่มถูกกว่า เครื่องในบ้านถามไม่กี่ครั้งต่อชั่วโมง
**สรุป: ไม่ทำ** วิธีที่ได้ผลจริงคือ prompt ให้เล็ก ซึ่งทำไปแล้ว

---

## ขั้น 3ก — คีย์ของเฟส 3 (Groq และ Google TTS)

ทั้งสองใบอยู่ในไฟล์เดียวกับคีย์ Anthropic คือ
`/home/kioskbroker/.config/kiosk-broker/env` โหมด 600 **มือถือไม่ถือคีย์ทั้งสองใบ**

```bash
sudo -u kioskbroker nano /home/kioskbroker/.config/kiosk-broker/env
```

```
ANTHROPIC_API_KEY=<ของเดิม>
GROQ_API_KEY=<คีย์ Groq ใบใหม่>
GOOGLE_TTS_API_KEY=<API key ของ Google>
```

### สร้างคีย์ Groq ใบใหม่แยกสำหรับมือถือ

1. เข้า https://console.groq.com → API Keys → Create API Key
2. ตั้งชื่อให้รู้ว่าเป็นของงานนี้ เช่น `phone-ai-kiosk-broker`
3. **ใบใหม่แยกจากของเดิม** เพื่อเพิกถอนเฉพาะของมือถือได้โดยไม่กระทบงานอื่น
4. ❓ Groq ยังไม่มี IP restriction ต่อคีย์เท่าที่ตรวจพบ — ด่านที่มีคือ
   rate limit และงบของ broker เอง ถ้าคีย์หลุดต้องเพิกถอนที่ console

### เปิด Google Cloud TTS แบบสิทธิ์น้อยที่สุด

ใช้ **API key ไม่ใช่ service account** ตั้งใจเลือกแบบนี้เพราะ API key จำกัดได้
ทั้งตาม API และตาม IP ส่วน service account JSON จำกัดไม่ได้ทั้งสองอย่าง

1. https://console.cloud.google.com → สร้างโปรเจกต์ใหม่แยก เช่น `phone-ai-kiosk`
2. APIs & Services → Library → เปิดใช้ **Cloud Text-to-Speech API** ตัวเดียว
3. Credentials → Create credentials → **API key**
4. กด **Edit API key** แล้วตั้งสองอย่างนี้ (ข้อสำคัญที่สุด):
   - **API restrictions** → Restrict key → ติ๊กเฉพาะ **Cloud Text-to-Speech API**
   - **Application restrictions** → **IP addresses** → ใส่ `45.76.157.64`
5. ตั้งงบเตือนที่ Billing → Budgets & alerts เผื่อกรณีผิดพลาด

**ทำไมต้องจำกัดทั้งสองชั้น:** API key ของ Google คือ bearer token ระดับโปรเจกต์
ถ้าไม่จำกัด ใครได้ไปก็เรียก API ทุกตัวในโปรเจกต์จากที่ไหนก็ได้

ตรวจสิทธิ์ไฟล์:

```bash
sudo ls -l /home/kioskbroker/.config/kiosk-broker/env      # ต้อง -rw------- kioskbroker
sudo -u linuxuser cat /home/kioskbroker/.config/kiosk-broker/env   # ต้อง Permission denied
```

### ฟังเสียงแล้วเลือกเอง

Chirp 3 HD มีเสียงชาย 16 เสียง คำสั่งนี้สร้างไฟล์ทั้ง 16 เสียงพูดประโยคเดียวกัน

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker voice-samples --out /tmp/voices
```

ดึงมาฟังบนคอม:

```powershell
scp "poom@45.76.157.64:/tmp/voices/*.ogg" .
```

ค่าใช้จ่ายทั้งชุด ~$0.03 🔶 (16 เสียง × ~60 ตัวอักษร × $0.00003)
**ไม่ถูกบันทึกลงบัญชีงบของมือถือ** — การเลือกเสียงไม่ใช่งบของโทรศัพท์

ถูกใจเสียงไหนแล้วตั้งใน `config.json` (ปัจจุบัน production ใช้ **Schedar** ที่ Poom เลือกแล้ว):

```bash
sudo -u kioskbroker nano /home/kioskbroker/.config/kiosk-broker/config.json
```
```json
{ "tts_voice": "Schedar" }
```
แล้ว `sudo systemctl restart kiosk-broker`

### แก้คำที่ TTS อ่านผิด

เจอจริง: `อากาศดี` ถูกอ่านเป็น "อา-กา-สะ-ดี" เพราะเสียงตัดคำผิด มองตัว ศ
เป็นต้นพยางค์ใหม่กับ ดี แทนที่จะเป็นตัวสะกดของ อากาศ

แก้ด้วยพจนานุกรมคำอ่านที่ `/home/kioskbroker/.config/kiosk-broker/pronunciation.json`
**ใช้เฉพาะข้อความที่ส่งไป Google — บนจอและในประวัติแชทยังเป็นคำสะกดถูกต้อง**

ฟังเทียบก่อน/หลังด้วยคำสั่งนี้ (สร้างสองไฟล์ให้ฟัง):

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker say "วันนี้อากาศดีครับ" --out /tmp/say
```

```powershell
scp "poom@45.76.157.64:/tmp/say/*.ogg" .
```

จะได้ `as-written-Schedar.ogg` กับ `as-spoken-Schedar.ogg` ฟังเทียบได้เลย
ค่าใช้จ่ายราว $0.000001 และ**บันทึกในบัญชีเทรน ไม่ใช่งบมือถือ**

คำที่แก้แล้ว (มีหลักฐานว่าอ่านผิดจริงทุกคำ): `อากาศดี`, `อากาศ`, และ `แผนที่`
(2026-09-23 ได้ยินเป็น "เปิดแผน ที่ ไป" แก้ด้วย `เปิดแผนที่` → `เปิด แผนที่`
และ `แผนที่ไป` → `แผนที่ ไป`)

`install.sh` **ไม่เขียนทับ** ไฟล์นี้ถ้ามีอยู่แล้ว เมื่อ repo มีคำใหม่ต้องคัดลอกเอง
(ดูความต่างก่อน ถ้าเคยเพิ่มคำเองบน VPS คำนั้นจะหาย) broker อ่านไฟล์ใหม่เองเมื่อไฟล์เปลี่ยน
ไม่ต้อง restart:

```bash
sudo diff /home/kioskbroker/.config/kiosk-broker/pronunciation.json ~/phone-ai-kiosk/server/pronunciation.json
sudo install -o kioskbroker -g kioskbroker -m 0644 ~/phone-ai-kiosk/server/pronunciation.json \
  /home/kioskbroker/.config/kiosk-broker/pronunciation.json
```

### ตรวจความยาวคำตอบกับโมเดลจริง (`persona-eval`)

ถามโมเดลจริงด้วยสถานการณ์ที่ Poom กำหนด (ฟังไม่ออก ถามเวลา อากาศ เปิดแผนที่ ตั้งปลุก เรื่องยาว สิ่งที่ทำไม่ได้)
ผ่านเส้นทางเดียวกับ `/v1/chat` แล้วบอกความยาวและปัญหาของแต่ละคำตอบ รอบละราว $0.01 (บัญชีเทรน ไม่ใช่งบมือถือ)

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker   PYTHONPATH=/home/kioskbroker/app   /home/kioskbroker/venv/bin/python -m kiosk_broker persona-eval
```

### ตัวตัดคำไทยก่อนส่งไปสร้างเสียง (0.38)

broker หาขอบเขตคำก่อนส่งข้อความไปสร้างเสียง (`kiosk_broker/wordcut.py`, nlpo3 + รายการคำ CC0)
แล้วใช้คำอ่านใน `pronunciation.json` แบบ **ทั้งคำ** จึงไม่ต้องมีขั้นต่ำ 3 ตัวอักษรอีก
ถ้าตัวตัดคำไม่พร้อม (ติดตั้งไม่ได้หรือล้ม) broker จะใช้วิธีค้นแบบเดิมและเสียงยังออกตามปกติ

- ดูว่าข้อความถูกตัดตรงไหน และเสียงจะได้ข้อความอะไร (ไม่เสียเงิน):

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker   PYTHONPATH=/home/kioskbroker/app   /home/kioskbroker/venv/bin/python -m kiosk_broker segment-check "น้ำมันดีเซลลิตรละ 31.94 บาทที่บางจาก"
```

- คำที่ตัวตัดคำแยกผิด ให้เพิ่มลง `/home/kioskbroker/.config/kiosk-broker/tts_words.txt` บรรทัดละคำ
  broker อ่านใหม่เองเมื่อไฟล์เปลี่ยน ไม่ต้อง restart และ `install.sh` ไม่เขียนทับไฟล์นี้
- ทดสอบฟังเทียบ 4 แบบ (`tts-ab`): ดูรายการและราคาก่อนด้วย `--list-only` แล้วค่อยรันจริง
  ไฟล์ออกมาเป็น `01-A-original.ogg` ... พร้อม `index.txt` ที่บอกข้อความที่ส่งไปทุกไฟล์

เพิ่มคำใหม่: แก้ไฟล์ JSON ไม่ต้องแตะโค้ด

```json
{
  "entries": [
    { "spelling": "อากาศดี", "say": "อากาด ดี", "why": "อ่านเป็น อา-กา-สะ-ดี" },
    { "spelling": "<คำที่อ่านผิด>", "say": "<เขียนใหม่ให้อ่านถูก>", "why": "<อ่านผิดว่าอะไร>" }
  ]
}
```

แล้ว `sudo systemctl restart kiosk-broker`

**กฎที่ระบบบังคับ:** คำในพจนานุกรมต้องยาวอย่างน้อย 3 ตัวอักษร เพราะภาษาไทย
ไม่มีช่องว่างระหว่างคำ ถ้าใส่คำสั้นอย่าง "ดี" มันจะไปโดน "ดีใจ" และ "ดีเซล"
โดยไม่มีทางแยกแยะได้ — ระบบจะปฏิเสธไม่โหลดไฟล์ถ้าใส่คำสั้นกว่านั้น

### ~~ชุดเสียงสำหรับเทรนคำปลุก~~ — ยกเลิกแล้ว

คำปลุกเปลี่ยนเป็น **"Hey Jarvis"** ใช้โมเดล pretrained ของ openWakeWord
ไม่ต้องเทรนเอง ไม่ต้องสร้างเสียง **ไม่มีค่าใช้จ่ายส่วนนี้อีกแล้ว**

คำสั่ง `wake-samples` ถูกถอดออกจาก CLI แล้ว ของเดิมย้ายไป
`archive/wakeword-saifon/` ค่าใช้จ่ายที่เคยลงบัญชีไว้ยังอ่านได้ด้วย
`training-usage` รายละเอียดอยู่ใน [WAKEWORD.md](../WAKEWORD.md)

## ขั้น 4 — เริ่มบริการ

```bash
sudo systemctl start kiosk-broker
sudo systemctl status kiosk-broker --no-pager
curl -s http://127.0.0.1:8770/healthz     # ต้องได้ {"status": "ok"}
```

ยืนยันว่าฟังแค่ loopback:

```bash
sudo ss -tlnp | grep 8770                 # ต้องเห็น 127.0.0.1:8770 ไม่ใช่ 0.0.0.0
```

ถ้า service ไม่ขึ้น ให้ดู `sudo journalctl -u kiosk-broker -n 50 --no-pager`
สาเหตุที่น่าจะเป็นอันดับแรกคือบรรทัด sandbox ใน unit (`ProtectHome` / `BindPaths`)
ลอง comment ออกทีละบรรทัด — ผมยังไม่ได้ทดสอบส่วนนี้เพราะไม่มี sudo

---

## ขั้น 5 — DNS (ต้องทำที่ GoDaddy)

DNS ของ `พัสดุไทย.com` อยู่ที่ GoDaddy (`ns57/ns58.domaincontrol.com`)
ผมไม่มีสิทธิ์ที่นั่น **Poom ต้องเพิ่มเรคอร์ดนี้เอง**

| ชนิด | ชื่อ | ค่า | TTL |
|---|---|---|---|
| A | `kiosk` | `45.76.157.64` | 600 |

(ชื่อโดเมนเต็มคือ `kiosk.xn--l3cgts1b3bzcvf.com` ซึ่งเป็นรูป punycode ของ
`kiosk.พัสดุไทย.com` — ในหน้าจัดการของ GoDaddy ใส่แค่ `kiosk` ในช่อง Name)

รอแล้วเช็ค:

```bash
dig +short A kiosk.xn--l3cgts1b3bzcvf.com     # ต้องได้ 45.76.157.64
```

---

## ขั้น 6 — ใบรับรอง (วิธีที่ไม่แตะ vhost อื่นเลย)

ใช้ `certonly --webroot` **ไม่ใช่** `--nginx` เพราะ `--nginx` เข้าไปแก้ไฟล์ vhost
เอง และเราสัญญาไว้ว่าจะไม่แตะไฟล์ของ thaitrack `--webroot` เขียนแค่ไฟล์ challenge
ใน `/var/www/html` ซึ่งเป็น root ของ default_server อยู่แล้ว (ตรวจแล้ว)

```bash
sudo certbot certonly --webroot -w /var/www/html \
  -d kiosk.xn--l3cgts1b3bzcvf.com
```

---

## ขั้น 7 — เปิด vhost

```bash
# เก็บสำเนา config ทั้งชุดไว้ก่อน เผื่อต้องย้อน
sudo tar czf /root/nginx-before-kiosk-$(date +%F).tar.gz /etc/nginx

sudo ln -s /etc/nginx/sites-available/kiosk /etc/nginx/sites-enabled/kiosk
sudo nginx -t
```

**ถ้า `nginx -t` ไม่ผ่าน ให้ลบ symlink แล้วหยุด** อย่า reload:

```bash
sudo rm /etc/nginx/sites-enabled/kiosk
```

ถ้าผ่าน:

```bash
sudo systemctl reload nginx

# สองงานเดิมต้องยังตอบเหมือนเดิม: thaitrack 200, monthreport 302
curl -s -o /dev/null -w 'thaitrack %{http_code}\n'   -k -H 'Host: xn--l3cgts1b3bzcvf.com' https://127.0.0.1/
curl -s -o /dev/null -w 'monthreport %{http_code}\n' -k -H 'Host: ubet89.house'          https://127.0.0.1/
```

(ค่าตั้งต้นที่วัดไว้ 22 ก.ย. 2569 ก่อนแตะอะไร: thaitrack **200**, monthreport **302**)

---

## ขั้น 7ก — reload nginx อัตโนมัติหลังต่ออายุใบรับรอง

`install.sh` ติดตั้ง deploy hook ไว้แล้วที่
`/etc/letsencrypt/renewal-hooks/deploy/kiosk-reload-nginx` (รันซ้ำได้ ทับได้)

**ทำไมต้องมี:** ใบรับรองออกด้วย `certonly --webroot` จึงไม่มี installer plugin
ที่จะ reload nginx ให้ ต่างจาก thaitrack/monthreport ที่ออกด้วย `--nginx`
ถ้าไม่มี hook นี้ ไฟล์ใบใหม่จะอยู่บนดิสก์ แต่ nginx ยังเสิร์ฟใบเก่าที่ค้างใน
หน่วยความจำจนกว่าจะมีอะไรไป restart

**hook ทำแค่สองอย่าง:** `nginx -t` แล้ว reload ถ้าผ่าน **ไม่แก้ไฟล์ config ใดๆ เลย**

**ด่านสำคัญ:** โฟลเดอร์นี้ certbot รันให้ **ทุกใบที่ต่ออายุ** รวมของ thaitrack
และ monthreport ด้วย hook จึงเช็ค `RENEWED_LINEAGE` ก่อน ถ้าไม่ใช่ใบของ kiosk
จะ `exit 0` ทันทีโดยไม่ทำอะไร

### ทดสอบ hook

```bash
sudo certbot renew --dry-run --run-deploy-hooks
```

**ต้องมี `--run-deploy-hooks`** — `--dry-run` เฉยๆ **ไม่รัน** deploy hook
(certbot 2.9.0 บนเครื่องนี้ระบุไว้ใน `certbot renew --help all` ตรงๆ)

### อ่าน log ของ hook

hook เขียนทุกเหตุการณ์ลง journal ด้วย tag `kiosk-cert-hook`

```bash
sudo journalctl -t kiosk-cert-hook -n 30 --no-pager
```

สิ่งที่ควรเห็นหลัง `certbot renew --dry-run --run-deploy-hooks` (สามใบ):

```
kiosk-cert-hook: skipping 'ubet89.house' — not kiosk.xn--... and that certificate reloads itself
kiosk-cert-hook: skipping 'xn--l3cgts1b3bzcvf.com' — not kiosk.xn--... and that certificate reloads itself
kiosk-cert-hook: renewed kiosk.xn--l3cgts1b3bzcvf.com — testing nginx configuration before reloading
kiosk-cert-hook: nginx reloaded — kiosk.xn--l3cgts1b3bzcvf.com is serving the renewed certificate
```

บรรทัด `skipping` มีไว้ให้เห็นว่า hook **ทำงานแล้วและตั้งใจไม่ทำอะไร** กับใบของ
thaitrack/monthreport ไม่ใช่ว่า hook ไม่ถูกเรียก

กรองเฉพาะที่ผิดพลาด:

```bash
sudo journalctl -t kiosk-cert-hook -p err -n 20 --no-pager
```

hook **ไม่เคยอ่านไฟล์ใบรับรองหรือ private key เลย** มันดูแค่ *ชื่อ* lineage ที่
certbot ส่งมาให้ จึงไม่มีความลับอะไรให้หลุดลง log

ถ้าอยากทดสอบทีละกิ่งโดยไม่ยุ่งกับ certbot เลย เรียก hook ตรงๆ ได้:

```bash
# ใบของเรา -> ต้อง reload
sudo RENEWED_LINEAGE=/etc/letsencrypt/live/kiosk.xn--l3cgts1b3bzcvf.com \
  /etc/letsencrypt/renewal-hooks/deploy/kiosk-reload-nginx

# ใบของ thaitrack -> ต้องเงียบ ไม่ทำอะไร
sudo RENEWED_LINEAGE=/etc/letsencrypt/live/xn--l3cgts1b3bzcvf.com \
  /etc/letsencrypt/renewal-hooks/deploy/kiosk-reload-nginx
```

ตรวจว่า timer ต่ออายุทำงานอยู่:

```bash
systemctl list-timers 'certbot*' --no-pager
```

---

## ขั้น 8 — ออก token ให้มือถือ

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker issue-token kiosk-a07
```

พิมพ์ token ออกมา **ครั้งเดียวครั้งเดียว** ระบบเก็บแต่ค่า hash
เอาไปใส่ในมือถือแล้วอย่าวางไว้ที่อื่น (อย่าส่งมาให้ผม ผมไม่ต้องใช้)

เพิกถอนเมื่อไรก็ได้ เช่น มือถือหาย:

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=... PYTHONPATH=... \
  /home/kioskbroker/venv/bin/python -m kiosk_broker revoke-token kiosk-a07
```

---

## ขั้น 9 — ทดสอบจากภายนอกผ่าน HTTPS จริง

รันจาก **คอมของ Poom** ไม่ใช่บน VPS เพื่อให้ผ่านเน็ตจริง (PowerShell)

```powershell
$t = "<token>"
curl.exe -s -X POST https://kiosk.xn--l3cgts1b3bzcvf.com/v1/chat `
  -H "Authorization: Bearer $t" -H "Content-Type: application/json" `
  --data-raw '{"text":"สวัสดี ทดสอบระบบ"}'
```

ต้องได้ JSON ที่มี `reply` เป็นภาษาไทย และ **`"action": null`**

ทดสอบด้านลบด้วย (ทุกข้อต้องถูกปฏิเสธ):

```powershell
# ไม่มี token -> 401
curl.exe -s -o NUL -w "%{http_code}`n" -X POST https://kiosk.xn--l3cgts1b3bzcvf.com/v1/chat -H "Content-Type: application/json" --data-raw '{"text":"hi"}'

# token ผิด -> 401
curl.exe -s -o NUL -w "%{http_code}`n" -X POST https://kiosk.xn--l3cgts1b3bzcvf.com/v1/chat -H "Authorization: Bearer wrong" -H "Content-Type: application/json" --data-raw '{"text":"hi"}'

# เส้นทางอื่น -> 404
curl.exe -s -o NUL -w "%{http_code}`n" https://kiosk.xn--l3cgts1b3bzcvf.com/

# HTTP ต้องเด้งไป HTTPS -> 301
curl.exe -s -o NUL -w "%{http_code}`n" http://kiosk.xn--l3cgts1b3bzcvf.com/v1/chat
```

---

## ตรวจว่า prompt คุมถ้อยคำได้เองไหม

คำสั่ง `usage` รายงานด้วยว่ามีคำตอบกี่ครั้งที่โค้ดต้องเข้าไปแก้คำลงท้าย

```
replies      : 12
  needed the register corrected : 3 (4 particles in total)
  that is 25% of replies — the prompt is not holding on its own
```

ตัวเลขนี้เก็บเป็น**จำนวนครั้งเท่านั้น ไม่เก็บข้อความ** ถ้าเลขนี้สูงต่อเนื่อง
แปลว่าควรไปปรับถ้อยคำใน `persona.py` ถ้าเป็น 0 แปลว่า prompt เอาอยู่เองแล้ว

## ตรวจว่าคำตอบยาวเท่าไรจริง (เป้า 60-80 ตัวอักษร)

`chars_out=` ใน log คือความยาวคำตอบหลังแก้คำลงท้ายแล้ว ซึ่งเป็นตัวเลขเดียวกับที่
มือถือเห็น วัดจาก log จริงไม่ต้องเดา:

```bash
sudo journalctl -u kiosk-broker --since '7 days ago' | grep -o 'chars_out=[0-9]*' | cut -d= -f2 | sort -n | awk '{a[NR]=$1; s+=$1} END {printf "n=%d  min=%d  median=%d  p90=%d  max=%d  mean=%.1f\n", NR, a[1], a[int(NR/2)+1], a[int(NR*0.9)+1], a[NR], s/NR}'
```

```bash
sudo journalctl -u kiosk-broker --since '7 days ago' | grep -o 'chars_out=[0-9]*' | cut -d= -f2 | awk '{n++; if ($1>80) over++; if ($1>100) cut++} END {printf "เกิน 80: %d/%d (%.0f%%)   เกิน 100 จึงถูกตัด: %d\n", over, n, over*100/n, cut}'
```

**ต้องมี n อย่างน้อย 20 ก่อนสรุป** ถ้า p90 ยังเกิน 80 แปลว่าถ้อยคำใน `persona.py`
ยังไม่พอ ไม่ใช่ว่าเพดานผิด

ที่เคยวัดได้บน A07 คือ **94 ตัวอักษร** แต่คำตอบนั้นคือประโยคปฏิเสธเรื่องเวลา
("ไม่มีเครื่องมือดูเวลา") ซึ่งเป็นคำตอบที่ยาวโดยธรรมชาติ และตอนนี้ไม่เกิดขึ้นอีก
เพราะ broker บอกเวลาให้แล้ว — ดังนั้นต้องวัดใหม่ อย่าเทียบกับ 94

## ค่าใช้จ่ายจริงและโควตาฟรีของ Google

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker usage --days 31
```

คำสั่งนี้แสดง:
- ยอดรายเดือนและรายวัน แยก chat / stt / tts
- ค่าต่อคำตอบเสียง
- สถานะโควตาฟรีของ Google
- คำเตือนเมื่อใช้เกิน 80% ของงบ $5

**ระบบนับงบหักโควตาฟรีออกแล้ว (Poom ตัดสินเมื่อ 2026-09-23)** ตัวเลขทั้งสองอยู่ใน `pricing.json` พร้อมข้อความที่คัดมาจากหน้าราคาทางการ

| บริการ | ฟรีต่อเดือน | ราคาเมื่อเกิน | แหล่ง |
|---|---|---|---|
| Google TTS Chirp 3 HD | 1,000,000 ตัวอักษร | $30 ต่อ 1 ล้านตัว | https://cloud.google.com/text-to-speech/pricing |
| Google STT v1 | 60 นาที (ต่อบัญชี) | $0.024/นาที คิดรายวินาที | https://cloud.google.com/speech-to-text/pricing |

Groq และ Anthropic ไม่มีโควตาฟรีรายเดือนที่ใช้กับงานนี้ จึงคิดเต็มราคาเหมือนเดิม

**รอบเดือนของโควตา:** นับตามเวลาของ Google ไม่ใช่เวลาไทย
- รอบเริ่ม 00:00 น. วันที่ 1 ของเดือน ตามเวลาแปซิฟิก (UTC-8) ซึ่งตรงกับ **15:00 น. วันที่ 1 ตามเวลาไทย**
- ที่มา: หน้า Cloud Billing budgets เขียนว่า "All calendar and custom date times begin at 12 AM US and Canadian Pacific Time (UTC-8)" และรอบเดือน "starts on the first day of each month" (https://docs.cloud.google.com/billing/docs/how-to/budgets)
- ส่วนงบ $5 ของเรายังนับตามเดือนไทยเหมือนเดิม ทั้งสองรอบจึงเริ่มไม่ตรงกัน
- เอกสารเขียนว่า UTC-8 ตรง ๆ และไม่พูดถึงเวลาออมแสง โค้ดจึงใช้ UTC-8 ตายตัว ช่วงฤดูร้อนจึงอาจคลาดจากของ Google ได้ไม่เกิน 1 ชั่วโมง

**การเตือน:** เขียนลง log และแสดงใน `usage` สองระดับ
1. ใช้ถึง 80% ของโควตาตัวใดตัวหนึ่ง: `free tier 80% used`
2. ใช้หมดและเริ่มเสียเงินจริง: `free tier used up ... charges start now`

ดูย้อนหลังได้ด้วย:

```bash
sudo journalctl -u kiosk-broker --since '31 days ago' | grep 'free tier'
```

**ข้อจำกัดที่ต้องรู้:**
- **Google ไม่มีวิธีให้เช็คโควตาที่เหลือ** ตัวเลขใน `usage` เป็นการนับฝั่งเราเอง รวมจากที่มือถือใช้ และที่ใช้ผ่านคำสั่งทดสอบ (`stt-compare`, `say`, `voice-samples`)
- **ยังไม่ชัดว่าโควตานับต่อบัญชีหรือต่อโปรเจกต์:**
  - STT เขียนไว้ชัดว่านับต่อบัญชีเรียกเก็บเงิน (billing account)
  - TTS ไม่ได้ระบุ
  - ถ้ามีโปรเจกต์อื่นที่ใช้บัญชีเดียวกันเรียก TTS หรือ STT ด้วย โควตาจะหมดเร็วกว่าที่ `usage` แสดง และค่าจริงจะสูงกว่าตัวเลขของเรา
- **ตรวจยอดจริงได้ที่หน้า Billing ของ Google Cloud** ถ้าสองตัวเลขต่างกันมาก ให้เชื่อ Google
- **ตัวกันงบยังจองเผื่อด้วยราคาเต็ม** ก่อนส่งคำขอแต่ละครั้ง (ไม่หักโควตาฟรีตอนจอง) ช่วงใกล้หมดงบจึงอาจปฏิเสธเร็วกว่าที่จำเป็นเล็กน้อย ซึ่งเป็นฝั่งที่ปลอดภัย

## เวลาแต่ละชั้นใช้ไปเท่าไร

ไฟล์ log แยกของ kiosk เท่านั้น ไม่แตะ log ของ thaitrack หรือ monthreport:

```bash
sudo tail -20 /var/log/nginx/kiosk-timing.log
```

```
2026-09-22T04:41:57+00:00 200 POST /v1/tts in=327 out=21329 request=1.230 upstream_header=1.230 upstream_all=1.230 connect=0.001
```

| ค่า | ความหมาย |
|---|---|
| `request` | ทั้งหมดที่ nginx ใช้ ตั้งแต่ไบต์แรกเข้าถึงไบต์สุดท้ายออก |
| `upstream_header` | จนได้ response header จาก broker = **ส่วนของ broker** |
| `upstream_all` | จนได้ไบต์สุดท้ายจาก broker |
| `request - upstream_all` | ส่วนที่ nginx เติมเข้ามาเอง |

ฝั่ง broker:

```bash
sudo journalctl -u kiosk-broker -n 50 | grep 'tts ok'
```

```
tts ok device=a07 voice=Schedar chars=37 truncated=False respellings=0 bytes=21329 cost=0.001110 register_fixes=0 upstream_ms=1223 handler_ms=1227 audio_ms=7000
```

`upstream_ms` คือ Google `handler_ms - upstream_ms` คือ broker เอง และ
**`audio_ms` คือความยาวของเสียง ไม่ใช่ latency** อ่านจาก granule ของ Ogg Opus
ไม่ได้เดาจากขนาดไฟล์

`audio_ms=` ว่างได้ ถ้าอ่าน container ไม่ออก เสียงยังทำงานปกติ — ตัวอ่านความยาว
ไม่มีสิทธิ์ทำให้เสียงล่ม

ไม่มีไฟล์ไหนในนี้บันทึก path พร้อม query string, header, token หรือข้อความภาษาไทย
(`$uri` ไม่ใช่ `$request`) — ทดลองแล้วว่าไม่มี

## จาร์วิสรู้เวลาแล้ว

broker เติมวันเวลาของ Asia/Bangkok ลงท้าย system prompt ทุกคำขอ **ไม่ได้เพิ่ม tool
ให้โมเดล และโมเดลไม่ได้คิดเวลาเอง**

```
ปัจจุบัน: วันอังคาร 22 กันยายน 2569 11:41 (สิบเอ็ดโมงเช้าสี่สิบเอ็ดนาที)
```

เครื่องนี้ตั้งเป็น UTC โค้ดไม่อ่าน timezone ของเครื่องเลย แปลงผ่าน
`zoneinfo` ทุกครั้ง ถ้าเครื่องไม่มี tzdata จะถอยไปใช้ +07:00 ตรึงไว้ ซึ่งเป็น
offset เดียวที่ไทยเคยใช้ในยุคนี้ — เพื่อไม่ให้ "ไม่มี tzdata" กลายเป็น "ตอบเวลาไม่ได้"

รูปคำอ่านภาษาไทยคำนวณใน Python ไม่ปล่อยให้โมเดลแปลง เพราะ 13:00 คือ "บ่ายโมง"
22:00 คือ "สี่ทุ่ม" 00:30 คือ "เที่ยงคืนครึ่ง" — เป็นกฎที่โมเดลพลาดได้ และเวลา
ที่พูดผิดคือเวลาที่ผิด

ตั้งโซนแยกจากโซนของงบได้ใน `config.json`:

```json
{"clock_timezone": "Asia/Bangkok", "budget_timezone": "Asia/Bangkok"}
```

สองค่านี้เป็นคนละการตัดสินใจ อันหนึ่งบอกว่าเดือนตัดรอบเมื่อไร อีกอันบอกว่า
"ตอนนี้กี่โมง" ตอบอะไร

## ทดลอง Botnoi Voice (ไม่แตะ production)

**production ยังใช้ Google Chirp 3 HD เสียง Schedar และงานนี้ไม่เปลี่ยนมัน**
`tts_provider` ค่าเริ่มต้นคือ `google` และไม่ได้แก้ `config.json` บนเครื่องจริง

### ตรวจก่อนว่ามีคีย์อะไรอยู่แล้ว (ไม่แสดงค่า)

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker keys
```

บอกแค่ `present` / `missing` ต่อคีย์ ไม่พิมพ์ค่า ไม่พิมพ์แม้ความยาว — เพราะความยาว
ของ token ก็เป็นข้อมูลของ token เอง `BOTNOI_TOKEN missing` เป็นเรื่องปกติ:
production ใช้ Google

### ใส่ BOTNOI_TOKEN โดยไม่ให้ค้างใน history

**ด่านแรก: ถ้ามีอยู่แล้วต้องหยุด** ต้องได้ `0` ถ้าไม่ใช่ `0` อย่าทำขั้นต่อไป
(ไปอ่านหัวข้อถัดไปแทน) — การ append ทับตอนที่มีอยู่แล้วจะได้สองบรรทัด และค่าที่มี
ผลจริงคือบรรทัดล่าง ซึ่งเป็นกับดักที่เคยเจอมาแล้วในโปรเจกต์อื่น

```bash
sudo -u kioskbroker grep -c '^BOTNOI_TOKEN=' /home/kioskbroker/.config/kiosk-broker/env
```

ได้ `0` แล้วจึงใส่ พิมพ์ในเชลล์ของ poom เอง (`read -rsp` ไม่แสดงตัวอักษรและไม่ลง
history) แล้ว **append** เข้าไฟล์ผ่าน `tee -a`:

```bash
read -rsp 'BOTNOI_TOKEN: ' T; echo; printf 'BOTNOI_TOKEN=%s\n' "$T" | sudo -u kioskbroker tee -a /home/kioskbroker/.config/kiosk-broker/env >/dev/null; unset T
```

ทำไมต้องเป็นแบบนี้ สามข้อ และทุกข้อเคยเป็นบั๊กจริงในคำสั่งรุ่นก่อน:

1. **`tee -a` ต่อท้ายเท่านั้น ไม่เขียนทับไฟล์** รุ่นก่อนใช้ `grep -v ... > "$F.new"`
   แล้ว `mv` ทับ ถ้า `grep` ล้มเหลว (ไฟล์อ่านไม่ได้ พาธผิด ดิสก์เต็ม) `|| true`
   จะกลืน error แล้ว `$F.new` จะมีแค่บรรทัด Botnoi — **ANTHROPIC_API_KEY,
   GROQ_API_KEY และ GOOGLE_TTS_API_KEY หายทั้งหมด** ทดลองจริงแล้วเหลือ 1 บรรทัด
2. **ไม่สร้างไฟล์ชั่วคราว** `$F.new` ถูกสร้างใหม่ตาม umask จึงเป็น `664` แล้ว `mv`
   ก็พาสิทธิ์นั้นมาทับของเดิม เปิดช่องให้ทุกคนบนเครื่องอ่านคีย์ทั้งหมดได้
   ทดลองจริงแล้วไฟล์กลายเป็น `664` — `chmod 600` ตามหลังช้าไปแล้ว
   ส่วน `tee -a` ต่อท้ายไฟล์เดิม สิทธิ์ `600` ไม่เปลี่ยน (ทดลองแล้ว)
3. **`>/dev/null` ปิดปาก tee** ไม่ใส่จะพิมพ์ token กลับขึ้นจอ

ตรวจผลโดยไม่แสดงค่า — ต้องได้ `1` และ `-rw-------`:

```bash
sudo -u kioskbroker grep -c '^BOTNOI_TOKEN=' /home/kioskbroker/.config/kiosk-broker/env
sudo ls -l /home/kioskbroker/.config/kiosk-broker/env
```

แล้วดูภาพรวมอีกครั้งด้วย `keys` ข้างบน — คีย์อื่นต้องยัง `present` ทั้งสามตัว

### ถ้ามี BOTNOI_TOKEN อยู่แล้ว และต้องการเปลี่ยนค่า

อย่า append ทับ ให้ลบบรรทัดเดิมก่อนแล้วค่อยใส่ใหม่ `sed -i` ลบเฉพาะบรรทัดที่ตรง
pattern คีย์อื่นไม่ถูกแตะ และสิทธิ์ `600` ยังอยู่ (ทดลองแล้วทั้งสองข้อ):

```bash
sudo -u kioskbroker sed -i '/^BOTNOI_TOKEN=/d' /home/kioskbroker/.config/kiosk-broker/env
sudo -u kioskbroker grep -c '^BOTNOI_TOKEN=' /home/kioskbroker/.config/kiosk-broker/env
```

บรรทัดที่สองต้องได้ `0` แล้วจึงกลับไปใช้คำสั่ง `tee -a` ข้างบน

ถ้า `env` ยังไม่มีไฟล์เลย `tee -a` จะสร้างใหม่ตาม umask ซึ่งอาจกว้างกว่า `600`
กรณีนั้นต้อง `sudo -u kioskbroker chmod 600` ตามทันที แล้วตรวจด้วย `ls -l` —
แต่บน production ไฟล์นี้มีอยู่แล้วเพราะ broker กำลังทำงานด้วยคีย์ในไฟล์นี้

### ดูรายชื่อเสียงก่อน (ไม่สร้างเสียง ไม่ใช้ point)

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker botnoi-voices --list-only
```

### สร้างเสียงเทียบกับ Google Schedar

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker botnoi-voices --out /tmp/tts-compare --max 6
```

ประโยคที่ใช้ทั้งสองฝั่งเหมือนกัน เพื่อให้เทียบกันได้จริง:

> วันนี้อากาศดีครับ ผมพร้อมช่วยเหลือครับ ตอนนี้เวลา 10 โมงครึ่ง

ดึงลง Windows แล้วฟัง:

```powershell
scp "poom@45.76.157.64:/tmp/tts-compare/*" .
```

`save_file` ถูกตั้งเป็น `"False"` ทุกครั้ง — ไม่ทิ้งไฟล์ทดสอบไว้บนสตอเรจของเขา

### ความปลอดภัยตอนโหลดไฟล์เสียง

`audio_url` ที่ Botnoi ส่งกลับมาคือ URL จากบริการภายนอก การโหลด URL ที่คนอื่นให้มา
คือช่องทาง SSRF โดยธรรมชาติ จึงบังคับไว้ห้าชั้น:

| ด่าน | ค่า |
|---|---|
| ต้องเป็น HTTPS | ไม่ใช่ = ปฏิเสธ |
| โดเมนต้องอยู่ใน allowlist | `botnoi.ai` และ `amazonaws.com` (ตรวจแบบ anchor ที่จุด) |
| redirect | ตามได้ไม่เกิน 3 ครั้ง **และตรวจโดเมนใหม่ทุกครั้ง** |
| ขนาดไฟล์ | ไม่เกิน 8 MB อ่านเกินเพดาน 1 ไบต์เพื่อไม่เชื่อ Content-Length |
| timeout | มี |

**ไม่มีที่ไหนพิมพ์ URL เต็ม** เพราะ presigned S3 URL มี credential อยู่ใน query string
แสดงแค่โดเมน

🔶 **allowlist ตั้งไว้กว้างกว่าที่ควรจะเป็น** เพราะผมไม่มี token จึงยังไม่เคยเห็น
bucket จริง คำสั่งจะพิมพ์โดเมนที่ใช้ทุกครั้ง (`from=...`) เอาโดเมนนั้นมาใส่
`botnoi_audio_hosts` ใน `config.json` แล้วมันจะแคบลงเหลือของจริงอันเดียว

### ❓ ที่ยังไม่ทราบ

- **ราคาต่อ point** ยังไม่ทราบ ระบบจึงบันทึกเป็น **point ไม่ใช่ดอลลาร์**
  ในบัญชีเทรน และไม่แปลงเป็นเงิน เพราะจะเป็นการเดา
- **Botnoi เก็บไฟล์เสียงไว้นานแค่ไหน** แม้ตั้ง `save_file=False` แล้ว ❓
- **Botnoi รองรับจำกัด IP ต่อ token ไหม** ❓ เท่าที่หาไม่พบ ต่างจาก Google API key
  ที่จำกัดได้ทั้ง API และ IP — ถ้า token หลุดต้องเพิกถอนที่หน้า console
- **โดเมน S3 จริง** ❓ (ดูด้านบน)
- ราคาเทียบกับ Google ยังเทียบไม่ได้จนรู้ราคา point

### ถ้าจะเปลี่ยนไปใช้ Botnoi จริง (ยังไม่ทำ)

โค้ดพร้อมสลับแล้ว แต่**ต้องให้ Poom ตัดสินก่อน** เปลี่ยนสองบรรทัดใน `config.json`:

```json
{ "tts_provider": "botnoi", "botnoi_speaker": "4" }
```

ถ้าตั้งเป็น `botnoi` แต่ไม่มี `BOTNOI_TOKEN` ระบบจะ **ปฏิเสธชัดเจน** (502
`tts_not_configured`) และเขียน error ลง log ตอนสตาร์ท ไม่ใช่เงียบๆ

## หน้าหลักของจอ — ข้อมูลสามแหล่ง (เฟส 5)

`GET /v1/dashboard` ต้องมี token เหมือนทุก endpoint ตอบกลับเป็นสามก้อน
`weather` `gold` `crypto` (บวก `place` กับ `location_fallback`)
**แต่ละก้อนล้มแยกกัน** ก้อนที่ล้มส่ง `ok: false`
พร้อม `error` ที่เป็นชื่อชนิดข้อผิดพลาดเท่านั้น และถ้าเคยดึงสำเร็จมาก่อน
จะแนบค่าเดิมมาใน `stale` พร้อม `age_seconds` ให้จอบอกผู้ใช้ว่าเก่าแค่ไหน

**ไม่คิดเงิน** ไม่มีแหล่งไหนในสามแหล่งนี้เก็บเงิน และ endpoint นี้ไม่ถูกหัก
จากงบเดือน — จอที่รีเฟรชถี่จึงทำให้ถามจาร์วิสไม่ได้ไม่ได้ แต่ยัง rate limit
เหมือน endpoint อื่นเพื่อกันยิงรัว

### แหล่งข้อมูล ตรวจเงื่อนไขก่อนเลือกแล้ว

| แหล่ง | ต้องใช้ key | เงื่อนไข | cache |
|---|---|---|---|
| Open-Meteo | ไม่ต้อง | CC BY 4.0 · ฟรีไม่เกิน 10,000 ครั้ง/วัน (เราใช้ 144) | 10 นาที |
| Nominatim (OSM) | ไม่ต้อง | ODbL · นโยบายห้าม bulk geocoding — เราถามครั้งเดียวต่อ 1 ตร.กม. ที่เครื่องเคยอยู่ | 1 วัน |
| Binance public | ไม่ต้อง | market data สาธารณะ · quote เป็น USDT ไม่ใช่ดอลลาร์จริง เขียนบอกไว้ใน payload | 1 นาที |
| CoinGecko | ไม่ต้อง | ฟรี 10,000 ครั้ง/**เดือน** — ใช้แค่ถามอันดับ market cap วันละครั้ง (~30/เดือน) | 1 วัน |
| api.chnwt.dev | ไม่ต้อง | 🔶 **บุคคลที่สาม** ดึงจากสมาคมค้าทองคำอีกที ไม่ใช่ API ทางการ | 5 นาที |

🔶 เรื่องทองต้องรู้: สมาคมค้าทองคำ**ไม่มี API สาธารณะ** เว็บตัวเองเป็น
JavaScript ล้วน ตัวที่ใช้อยู่เป็นของบุคคลที่สามที่ scrape มาอีกที Poom
ตัดสินใจใช้ตัวนี้ไปก่อนโดยรู้ข้อนี้แล้ว — ถ้าวันหนึ่งมันหายไปเงียบๆ จอจะโชว์
ราคาเดิมพร้อมอายุ ไม่ใช่จอว่าง นั่นคือเหตุผลที่โค้ดเก็บค่าเดิมไว้ตั้งแต่แรก

### ตำแหน่งที่ดูอากาศ — มือถือเป็นคนบอก ไม่ใช่ config

**ไม่มีพิกัดใน config อีกแล้ว** มือถือส่งตำแหน่งตัวเองมาใน query string ของ
`GET /v1/dashboard?lat=20.05&lon=99.89` ทุกครั้ง และ broker ปัดซ้ำอีกรอบ

กฎที่ยึด เรียงตามลำดับที่มันบังคับกันเอง:

1. แอปขอแค่ **ACCESS_COARSE_LOCATION** ไม่ขอ FINE — fine คือระดับเมตร
   ซึ่งบอกได้ว่าอยู่ห้องไหนของบ้าน
2. มือถือ**ปัดเหลือ 2 ตำแหน่งทศนิยม** (~1.1 กม.) ก่อนส่ง — ดูที่
   `voice/KioskLocation.kt`
3. broker **ปัดซ้ำ** ตอนรับ ไม่ไว้ใจว่ามือถือทำแล้วจริง — `clean_coords`
4. **ไม่มีที่ไหน log พิกัด** ทั้ง broker log, nginx access log
   (`log_format` ใช้ `$uri` ซึ่งตัด query string ทิ้ง) และ `dumpsys` บนมือถือ
   ทุกที่บอกได้แค่ `fallback=True/False`
5. cache อากาศกับชื่อสถานที่ผูกกับ**พิกัดที่ปัดแล้ว** ไม่ใช่ค่าดิบ

ถ้าหาตำแหน่งไม่ได้ — ไม่มีสิทธิ์, Location ปิด, GPS เย็น, query string เพี้ยน —
ทุกกรณีตกไปที่ **พื้นที่มหาวิทยาลัยแม่ฟ้าหลวง (20.05, 99.89)** ค่านี้ตรวจจาก
สองแหล่งอิสระ ไม่ได้เดา: Wikidata Q958942 (20.045147, 99.894883) และ
OpenStreetMap (20.044948, 99.896844) ปัดสองทศนิยมแล้วตรงกันในระยะ ~1 กม.

ชื่อบนหัวหน้าต่างมาจาก reverse geocoding ของพิกัดที่ปัดแล้ว ตัดคำนำหน้าทิ้ง
(`จังหวัดเชียงราย` → `เชียงราย`) ถ้าอ่านชื่อไม่ได้ จอขึ้นว่า
`ตำแหน่งปัจจุบัน` ไม่เคยขึ้นพิกัด

### ทองไทย — เปอร์เซ็นต์เทียบกับอะไร

`api.chnwt.dev/thai-gold-api` มี endpoint เดียวคือ `/latest` และคืนแค่
ราคาซื้อ/ขายของรูปพรรณกับทองแท่ง + วันเวลาอัปเดต **ไม่มีราคาก่อนหน้า
ไม่มีราคาปิด ไม่มี change** (ตรวจแล้ว 23/09/2569 — `/history`, `/yesterday`,
`/all` ตอบ 404 หมด)

เปอร์เซ็นต์บนจอจึงเป็น **การเคลื่อนไหวเทียบกับประกาศครั้งก่อน** ที่
*broker เห็นเอง*: เก็บราคาล่าสุดที่ต่างจากราคาปัจจุบันไว้ในตาราง
`dashboard_state` ของ SQLite (อยู่รอด restart) แล้วคิดจากตรงนั้น จอเขียนกำกับ
ว่า `เทียบครั้งก่อน` ทุกครั้งที่มีตัวเลข

**ไม่ใช่**การเทียบกับราคาปิดเมื่อวาน และถ้า broker ยังไม่เคยเห็นราคาขยับเลย
(เพิ่งติดตั้ง) จะ**ไม่แสดงเปอร์เซ็นต์เลย** แทนที่จะโชว์ `0.00%` ซึ่งเป็นการ
อ้างสิ่งที่ยังไม่มีหลักฐาน

### ทองไทย — ความบริสุทธิ์ 96.5%

หัวหน้าต่างทองบนจอเขียนว่า `ความบริสุทธิ์ 96.5%` ค่านี้**เป็นค่าคงที่ในโค้ด**
(`GOLD_PURITY_PCT` ใน `kiosk_broker/dashboard.py`) เพราะไม่มี API ไหนส่งมา —
`api.chnwt.dev` ส่งแค่ตัวเลขราคา แต่หน้าที่มันไปดึงมา
`https://classic.goldtraders.or.th/default.aspx` เขียนหัวแถวสองแถวที่มันอ่านว่า
`ทองคำแท่ง 96.5%` และ `ทองรูปพรรณ 96.5%` (ตรวจแล้ว 23/09/2569 โดยจับคู่ป้ายกับ
id ของ element ที่ scraper อ่าน) นี่คือมาตรฐานทองไทย ไม่ใช่ทองสากล 99.99%

เปอร์เซ็นต์ที่มีเครื่องหมาย + หรือ − ต่อท้ายราคาคือ**การเปลี่ยนแปลงราคา** ไม่ใช่
ความบริสุทธิ์ จอเขียนกำกับไว้ใต้ราคาทุกครั้ง

### คริปโต — เลือกเหรียญยังไง

4 เหรียญที่ market cap สูงสุด **ไม่นับ stablecoin และเหรียญที่ผูกราคากับ
ดอลลาร์** กรองสองชั้น: รายชื่อจาก category `stablecoins` ของ CoinGecko
เป็นตัวกรองหลัก และราคาที่ห่างจาก $1 ไม่เกิน 5% เป็นตัวกันพลาด (เช่น
FIGR_HELOC ที่ $1.025 ติดอันดับ 10 ตอนเขียน — เหรียญจริงที่ใกล้เส้นที่สุดคือ
XRP ที่ $1.57 ซึ่งห่าง 57%)

จากนั้นไล่ลงไปตามอันดับจนได้ 4 ตัวที่**มีคู่ USDT บน Binance จริง** (ZEC กับ
HYPE อยู่ท็อป 15 ทั้งคู่ตอนเขียน แต่มีคู่ USDT แค่ตัวเดียว) ราคายังดึงจาก
Binance เหมือนเดิม — CoinGecko ใช้แค่ถามอันดับวันละครั้ง อันดับล่าสุดเก็บใน
SQLite ด้วย ถ้า CoinGecko ล่มก็ใช้อันดับของเมื่อวานต่อ ไม่ใช่จอว่าง

### อยากลดความถี่ที่ยิงออกนอก

```json
{"dashboard_weather_ttl": 600, "dashboard_gold_ttl": 300, "dashboard_crypto_ttl": 60}
```

หน่วยเป็นวินาที มือถือถามทุก 60 วินาทีอยู่แล้ว ค่าพวกนี้คุมว่า "ถามแล้ว
broker ยิงออกนอกจริงไหม" ไม่ใช่ความถี่ที่มือถือถาม

## ค่าใช้จ่ายจริงต่อคำถามหนึ่งครั้ง 🔶

จากราคาทางการที่ตรวจแล้ว:

| ส่วน | หน่วย | ต่อคำถาม |
|---|---|---|
| Groq STT | $0.04/ชม. **คิดขั้นต่ำ 10 วินาที** | $0.000111 |
| Claude Haiku | in $1 / out $5 ต่อ 1M | ~$0.0011 |
| Google TTS Chirp 3 HD | **$0.00003 ต่อตัวอักษร** | ~$0.0021 (คำตอบ 70 ตัวอักษร) |
| **รวม** | | **~$0.0032** |

**งบ $5 ≈ 1,557 คำถาม/เดือน ≈ 52 คำถาม/วัน**
กรณีแย่สุด (คำตอบชนเพดาน 100 ตัวอักษร, เสียง 30 วินาที) ≈ $0.0051 ≈ 32 คำถาม/วัน

### ลดลงมาจาก $0.0047 ได้อย่างไร

สองอย่าง ทำงานร่วมกัน:

1. **prompt สั่งให้ตอบ 60–80 ตัวอักษร** — เพิ่ม prompt ราว 70 ตัวอักษร
   (คิดเป็น $0.000067 ต่อคำขอ) แต่ลดคำตอบจากราว 120 เหลือราว 70 ตัวอักษร
   ประหยัด TTS $0.0015 → **คุ้ม 22 เท่า ทุกคำถาม**
2. **เพดานบังคับด้วยโค้ด 100 ตัวอักษร** — ตัดก่อนส่งไป Google ไม่ใช่ส่งไปแล้ว
   ค่อยนับเงิน ตัดที่ท้ายประโยค (คำว่า "ครับ" เป็นตัวบอกจบประโยคที่เชื่อถือได้
   ในภาษาที่ไม่มีจุด) ถ้าไม่มีก็ตัดแบบไม่ผ่ากลางตัวอักษร

**ผลรวม: ถูกลง 32% ต่อคำถาม และได้จำนวนคำถามต่อวันเพิ่มขึ้น 49%**

⚠️ TTS ยังกินงบมากที่สุดอยู่ คำตอบยิ่งยาวยิ่งแพงเป็นเส้นตรง

⚠️ **แก้ไขจากที่เคยรายงานไว้:** รอบสำรวจผมบอกว่า Chirp 3 HD มีโควตาฟรี 1 ล้าน
ตัวอักษร/เดือน **ตอนนี้อ่านหน้าราคาทางการแบบคำต่อคำแล้วพบว่าไม่ใช่** — ข้อความ
เรื่องโควตาฟรีระบุเฉพาะ WaveNet (1M) กับ Standard (4M) ไม่ได้ระบุ Chirp 3 HD และ
แถวของ Chirp 3 HD คิดเงินตั้งแต่ตัวอักษรแรก ระบบจึง**ไม่สมมติว่ามีโควตาฟรี**
เพราะถ้าสมมติผิดจะนับงบต่ำกว่าจริงเงียบๆ

## ดูงบที่ใช้ไป

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker usage
```

ยอดนี้เป็นของมือถือเครื่องเดียว แยกฐานข้อมูลจากสายฝนใน Telegram คนละไฟล์
คนละ user คิดจาก `usage` ที่ Anthropic ส่งกลับมาจริง ไม่ใช่การประมาณ

เมื่อชนเพดาน broker จะตอบ **402** พร้อมข้อความว่ากลับมาใช้ได้วันที่ 1 ของเดือนหน้า
และ **ไม่สลับไปโมเดลอื่น** ตามที่ตัดสินไว้

---

## ทดสอบว่างบหมดจริงไหม โดยไม่เผางบจริง

ลดเพดานชั่วคราวให้ต่ำกว่ายอดที่ใช้ไปแล้ว → ต้องได้ 402 แล้วคืนค่าเดิม

```bash
CONF=/home/kioskbroker/.config/kiosk-broker/config.json
sudo cp $CONF $CONF.bak
sudo -u kioskbroker python3 -c "
import json,sys
p='$CONF'; d=json.load(open(p)); d['monthly_budget_usd']=0.0001
json.dump(d,open(p,'w'),indent=2)"
sudo systemctl restart kiosk-broker

# ต้องได้ 402
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8770/v1/chat \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  --data-raw '{"text":"สวัสดี"}'

# คืนค่าเดิม
sudo mv $CONF.bak $CONF
sudo systemctl restart kiosk-broker
```

ข้อนี้ไม่ยิง API เลยเพราะด่านงบอยู่**ก่อน**การเรียกโมเดล จึงไม่เสียเงินแม้แต่บาทเดียว

---

## 🔴 deploy — ต้องรัน install.sh เสมอ (ผมเคยบอกผิด)

**broker ไม่ได้รันโค้ดจาก git checkout** `install.sh` บรรทัด 85-86 ทำ:

```bash
rm -rf "$APP_DIR/kiosk_broker"
cp -r "$REPO_SERVER_DIR/kiosk_broker" "$APP_DIR/"
```

และ systemd unit รันด้วย `WorkingDirectory=/home/kioskbroker/app` กับ
`PYTHONPATH=/home/kioskbroker/app`

**แปลว่า broker รันสำเนาที่อยู่ใน `/home/kioskbroker/app/kiosk_broker`**
`git pull` อัปเดตแค่ repo ส่วนสำเนาไม่ขยับ `systemctl restart` เฉยๆ จึง
**รีสตาร์ตโค้ดเก่า**

🔴 **ผมเคยเขียนในรายงานว่า "ไม่ต้องรัน install.sh" ทั้งรอบเปลี่ยนชื่อเป็นจาร์วิส
และรอบเฟส 4 — นั่นผิด** ถ้าตอนนั้นรันแค่ `git pull && restart` แปลว่าโค้ดใหม่
ยังไม่เคยขึ้นเครื่องเลย

### คำสั่ง deploy ที่ถูกต้อง ใช้ทุกครั้งที่แก้ `server/`

```bash
cd ~/phone-ai-kiosk && git pull && sudo bash server/install/install.sh && sudo systemctl restart kiosk-broker
```

`install.sh` เป็น idempotent — รันซ้ำได้ ไม่ทำลายคีย์ ไม่ทำลาย config
และจะ `nginx -t` ก่อน reload (ไม่ผ่านจะหยุดและไม่ reload)

### ตรวจว่าโค้ดที่รันอยู่ใหม่จริง

```bash
sudo -u kioskbroker diff -r /home/kioskbroker/app/kiosk_broker ~/phone-ai-kiosk/server/kiosk_broker && echo "ตรงกัน"
```

ไม่มี output + `ตรงกัน` = สำเนาตรงกับ repo แล้ว

ตรวจสิ่งที่รอบนี้เปลี่ยน:

```bash
systemctl is-active kiosk-broker && curl -s -o /dev/null -w '%{http_code}\n' https://kiosk.xn--l3cgts1b3bzcvf.com/healthz
```

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker PYTHONPATH=/home/kioskbroker/app /home/kioskbroker/venv/bin/python -c "
from kiosk_broker import actions
from kiosk_broker.persona import SYSTEM_PROMPT
print('actions :', sorted(actions.ENABLED_ACTION_TYPES))
print('address :', [l for l in SYSTEM_PROMPT.splitlines() if 'แทนตัวเองว่า' in l][0])
print('chars   :', len(SYSTEM_PROMPT))
"
```

ต้องได้:
```
actions : ['open_maps']
address : แทนตัวเองว่า "ผม" เรียกผู้ใช้ว่า "พี่" ลงท้าย "ครับ"
chars   : 1057
```

**ถ้า `address` ยังไม่มีคำว่า "พี่" แปลว่า install.sh ยังไม่ได้รัน**

ดู log ว่าโทนเอาอยู่ไหมหลังใช้งานไปสักพัก:

```bash
sudo journalctl -u kiosk-broker --since '1 hour ago' | grep -oE 'register_fixes=[0-9]+ formality=[0-9]+' | sort | uniq -c
```

`register_fixes` คือจำนวนครั้งที่โค้ดต้องแก้คำลงท้าย/คำเรียก
`formality` คือจำนวนคำราชการที่เจอ (นับอย่างเดียว ไม่แก้)
**ทั้งคู่เป็นตัวเลข ไม่มีข้อความ** — ถ้าเป็น 0 ต่อเนื่องแปลว่า prompt เอาอยู่เอง

## deploy รอบก่อนๆ

รอบเปลี่ยนชื่อเป็นจาร์วิส และรอบเฟส 4 เคยเขียนไว้ว่า "ไม่ต้องรัน install.sh"
**ซึ่งผิด** ดูหัวข้อข้างบน — ใช้คำสั่งเดียวกันทุกครั้ง

## อัปเดตโค้ดรอบต่อไป

```bash
cd ~/phone-ai-kiosk && git pull
sudo bash server/install/install.sh      # idempotent
sudo systemctl restart kiosk-broker
```

`install.sh` ไม่เขียน `config.json` หรือ `env` ทับถ้ามีอยู่แล้ว

### ⚠️ การอัปเดตเฟส 3 ต้อง reload nginx ด้วย

ไฟล์ vhost เปลี่ยน (เพิ่ม `location = /v1/stt` และ `/v1/tts`) **ไฟล์ที่เปลี่ยนแล้ว
แต่ไม่มีใคร reload คือไฟล์ที่ไม่มีผลอะไรเลย** — นั่นคือสาเหตุที่ `/v1/stt` โดน
413 ทั้งที่ config ถูกแก้ใน git ไปแล้ว

`install.sh` จัดการให้เองแล้วถ้าเว็บไซต์ถูก enable อยู่: มันจะรัน `nginx -t` ก่อน
**ถ้าไม่ผ่านจะหยุดและไม่ reload เด็ดขาด** (config ที่กำลังรันอยู่ไม่ถูกแตะ
ทั้งสามเว็บยังทำงาน) ถ้าผ่านถึงจะ reload

ตรวจเองอีกชั้นหลังรัน:

```bash
sudo nginx -t
curl -s -o /dev/null -w 'thaitrack %{http_code}\n'   -k -H 'Host: xn--l3cgts1b3bzcvf.com' https://127.0.0.1/
curl -s -o /dev/null -w 'monthreport %{http_code}\n' -k -H 'Host: ubet89.house'          https://127.0.0.1/
curl -s -o /dev/null -w 'kiosk %{http_code}\n'       https://kiosk.xn--l3cgts1b3bzcvf.com/healthz
```

ค่าที่ถูก: **200 / 302 / 200**

### เพดานขนาด body แต่ละเส้นทาง

| เส้นทาง | nginx | broker | ใครปฏิเสธ |
|---|---|---|---|
| `/v1/stt` | 1200k | 1 MiB (`max_audio_bytes`) | **broker** → ได้ข้อความไทยที่อ่านออกเสียงได้ |
| `/v1/chat` | 16k | 8 KiB | broker |
| `/v1/tts` | 16k | 8 KiB | broker |

nginx ตั้งไว้**หลวมกว่า** broker เสมอโดยตั้งใจ เพื่อให้ broker เป็นคนปฏิเสธและ
มือถือได้ JSON ภาษาไทย ไม่ใช่หน้า HTML ของ nginx ที่อ่านออกเสียงไม่ได้

rate limit ของ nginx คือ 90 คำขอ/นาที ส่วน broker คือ 10 คำขอ/นาทีต่อเส้นทาง
**เสียงหนึ่งรอบใช้ 3 คำขอ** (stt + chat + tts) จึงตั้ง nginx ให้หลวมพอที่
เพดานของ broker เป็นตัวที่มีผลจริง

---

## Tuya — ไฟบ้าน (เฟสอ่านอย่างเดียว)

Poom ตัดสินใจแล้ว: ไฟบ้านใช้ Tuya ผ่านแอป Smart Life, broker บน VPS เป็นตัวเดียว
ที่คุยกับ Tuya Cloud, **มือถือไม่ถือคีย์ Tuya**, ยกเลิก Google Home APIs สำหรับไฟ

**รอบนี้อ่านอย่างเดียว** — ในโค้ดไม่มีฟังก์ชันส่งคำสั่งเลย คำสั่งเปิดปิดไฟถูก
ออกแบบไว้แล้ว (`tuya.plan_switch`) แต่ส่งไม่ได้ และ action `set_light` ไม่อยู่ใน
`ENABLED_ACTION_TYPES` ถ้าโมเดลพยายามส่งมา โค้ดจะตัดทิ้งเอง ไม่พึ่ง prompt

### ขั้นที่ 1 — บน platform.tuya.com (Poom ทำเอง ✅ ตามเอกสาร Tuya)

1. สมัคร/ล็อกอิน https://platform.tuya.com
2. Cloud → Development → **Create Cloud Project**
   - Development Method: **Smart Home**
   - Data Center: **ต้องตรงกับบัญชี Smart Life** ดูในแอป Smart Life ที่
     ฉัน (Me) → ตั้งค่า → บัญชีและความปลอดภัย → ภูมิภาค
     🔶 บัญชีไทยที่สร้างก่อน 3 มิ.ย. 2025 มักอยู่ Western America (`us`)
     บัญชีใหม่กว่านั้นมักอยู่ Singapore (`sg`)
3. ตอนสร้างจะให้เลือกบริการ ให้มี **IoT Core** และ **Authorization Token
   Management** — ใช้ Trial Edition ที่ฟรี **ห้ามกดซื้อแพ็กเกจที่เสียเงิน**
4. แท็บ **Devices → Link Tuya App Account → Add App Account** แล้วเอาแอป
   Smart Life สแกน QR → Confirm login
   - ❓ ถ้ามีตัวเลือกสิทธิ์อุปกรณ์แบบ "อ่านอย่างเดียว" ให้เลือกอันนั้นรอบนี้
     ถ้ามีแต่ "Read, Write and Manage" ก็ได้ — โค้ดฝั่งเราอ่านอย่างเดียวอยู่แล้ว
5. แท็บ **Overview** → Authorization Key → จะเห็น **Access ID/Client ID** และ
   **Access Secret/Client Secret** — **ห้ามคัดลอกไปวางในแชทหรือที่อื่น** นอกจาก
   ขั้นที่ 2 ข้างล่าง

ข้อจำกัดของ Trial Edition (✅ หน้า membership-service ของ Tuya): ประมาณ
**26,000 API calls/เดือน**, อุปกรณ์สูงสุด 50 ตัว, ใช้ได้เฉพาะส่วนตัว/ทดสอบ
ครบโควตาแล้วบริการหยุดจนเดือนหน้า ไม่มีเก็บเงินเกิน ❓ อายุ trial ที่แน่นอน
ไม่ได้เขียนไว้ในหน้าราคา (คู่มือ Home Assistant ของ Tuya บอกว่าต่ออายุฟรีได้)

### ขั้นที่ 2 — ใส่คีย์บน VPS (append ทีละบรรทัด ไม่เขียนทับไฟล์ env)

คำสั่ง `set-key` ต่อท้ายไฟล์ env หนึ่งบรรทัด ไม่แตะบรรทัดเดิม ไม่แสดงค่าที่
พิมพ์ (ช่องจะว่างขณะวาง) และปฏิเสธถ้าชื่อนั้นมีอยู่แล้ว

```bash
cd ~/phone-ai-kiosk && git pull && sudo bash server/install/install.sh
B='sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker PYTHONPATH=/home/kioskbroker/app /home/kioskbroker/venv/bin/python -m kiosk_broker'
$B set-key TUYA_ACCESS_ID        # วาง Access ID แล้ว Enter (มองไม่เห็นตอนวาง)
$B set-key TUYA_ACCESS_SECRET    # วาง Access Secret แล้ว Enter
$B set-key TUYA_DATA_CENTER      # พิมพ์ us หรือ sg (หรือ cn us-e eu eu-w in)
$B keys                          # ต้องขึ้น present ทั้งสามบรรทัด TUYA_*
```

ถ้าวางผิดแล้วต้องแก้: `set-key` จะไม่ยอมเขียนซ้ำ ให้ลบบรรทัดนั้นเองด้วย
`sudo -u kioskbroker nano /home/kioskbroker/.config/kiosk-broker/env` แล้วรัน
`set-key` ใหม่ (อย่าถ่ายหน้าจอ nano)

### ขั้นที่ 3 — ทดสอบ (อ่านอย่างเดียว ไม่ต้อง restart broker)

```bash
$B tuya-check      # ต้องขึ้น token: ok (valid for ...s)
$B tuya-devices    # ชื่อ · ประเภท · online · เปิด/ปิด · …4 ตัวท้ายของ id
```

ผลลัพธ์ไม่มีคีย์ ไม่มี token ไม่มี id เต็ม และไม่มี `local_key` (กุญแจเข้ารหัส
ในบ้านที่ Tuya แนบมากับรายการอุปกรณ์ — โค้ดทิ้งตั้งแต่รับมา) อุปกรณ์ประเภท
กล้อง กุญแจ ประตู สัญญาณกันขโมย และเซ็นเซอร์ จะมีป้าย `(ห้ามควบคุม)`

| ถ้าขึ้น | แปลว่า |
|---|---|
| `tuya 1004` | Access Secret ผิด |
| `tuya 1013` | นาฬิกา VPS เพี้ยน — `timedatectl` |
| `tuya 2007` / token ได้แต่ไม่เห็นอุปกรณ์ | data center ไม่ตรงกับบัญชี Smart Life |
| `tuya 1106` / `28841101` | ยังไม่ได้ subscribe บริการ หรือยังไม่ได้ link บัญชี |
| `tuya 1199` | ยิงถี่เกิน รอสักครู่ |
| `tuya 28841004` | โควตาเดือนนี้หมด |

### เฟสถัดไป (ยังไม่เปิด)

- action `set_light` + **allowlist อุปกรณ์** (id เต็ม) ที่ Poom เลือกเอง
- ตรวจด้วยโค้ดทั้งหมด: หมวดต้องเป็นไฟหรือสวิตช์ไฟ (`dj xdd fwd dc dd kg tgkg`),
  **ปลั๊ก (`cz pc`) ไม่นับเป็นไฟ** เพราะอาจเสียบฮีตเตอร์อยู่, และหมวด กล้อง
  กุญแจ ประตู โรงรถ กันขโมย เซ็นเซอร์ **ถูกปฏิเสธเสมอแม้อยู่ใน allowlist**
- ค่าได้แค่ true/false, code ได้แค่ `switch_led` `switch` `switch_1`
- endpoint ที่จะใช้: `POST /v1.0/devices/{id}/commands`

---

## เทียบตัวถอดเสียง 3 ทาง และโหมดวิเคราะห์คำพูด

ที่มา: Poom พูด "กล้อง" แต่ถอดได้ "กล่อง" ต้องการเลือกตัวถอดเสียงจากผลจริง
**ค่าเริ่มต้นคือ `groq-hints`** (Poom ตัดสิน 23/09/2569 จากเสียงจริง: "ขอดูกล้อง
หน่อยครับ" — groq ได้ "กล่อง", groq-hints ได้ "กล้อง" ราคาและเวลาเท่ากัน, google
ถูกเหมือนกันแต่ช้ากว่า ~4 เท่า แพงกว่า ~15 เท่า จึงเก็บไว้เทียบภายหลัง)
เปลี่ยนได้ด้วย `"stt_provider": "groq"` หรือ `"google"` ใน config.json แล้ว restart
หรือสลับชั่วคราวจากมือถือผ่าน adb (TESTING.md)

| ชื่อ | คืออะไร | ราคา (ตรวจจากหน้าทางการ 23/09/2569) |
|---|---|---|
| `groq` | Groq whisper-large-v3-turbo แบบเดิม | $0.04/ชม. คิดขั้นต่ำ 10 วินาทีต่อครั้ง |
| `groq-hints` | ตัวเดิม + `prompt` คำใบ้จาก `stt_hints.json` (Groq รองรับจริง จำกัด 224 tokens) | เท่า groq |
| `google` | Google Cloud Speech-to-Text v1, `latest_short`, th-TH, `speechContexts` คำใบ้ | ฟรี 60 นาที/เดือน แล้ว $0.024/นาที คิดเป็นวินาที |

ตัวถอดเสียงในเครื่อง Android **ยังไม่เปิดใช้** — ดูเหตุผลใน TESTING.md หัวข้อ v0.25.0

### ⚠️ โหมดวิเคราะห์ — เก็บคำที่คนพูดจริง

**โหมดนี้เก็บข้อความที่พูดกับ kiosk คำต่อคำ** (Poom เปลี่ยนกติกาเดิมที่ห้ามเก็บ
ข้อความ เพื่อแก้ปัญหาถอดเสียงผิด) กติกาของโหมดนี้:

- **ปิดเป็นค่าเริ่มต้น** เปิดเมื่อจะเก็บข้อมูลเท่านั้น แล้วปิดทันทีเมื่อพอ
- เก็บต่อรอบ: เวลา, ตัวถอดเสียง, ข้อความ, ความยาวเสียง, ผลจับคำสั่ง
  (camera/maps/none), ค่าใช้จ่ายโดยประมาณ
- อยู่ในฐานข้อมูลของ broker ใน `/home/kioskbroker/.config/kiosk-broker/`
  (โฟลเดอร์ 0700 ไฟล์ 0600 อ่านได้เฉพาะ kioskbroker) — **ไม่ลง journal ไม่ลง nginx**
- **ไม่เก็บไฟล์เสียง** เว้นแต่เปิดด้วย `--audio` แยกอีกชั้น
- **ลบเองเมื่อเกิน 14 วัน**

```bash
B='sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker PYTHONPATH=/home/kioskbroker/app /home/kioskbroker/venv/bin/python -m kiosk_broker'
$B analysis status          # ดูว่าเปิดอยู่ไหม
$B analysis on              # เปิด (เก็บข้อความ ไม่เก็บเสียง)
$B analysis on --audio      # เปิด + เก็บไฟล์เสียงด้วย (สำหรับ stt-compare)
$B analysis summary         # จำนวนรอบ คำที่จับคำสั่งไม่ได้บ่อย ตัวอย่างล่าสุด
$B analysis off             # ปิด — ไม่เก็บอะไรใหม่ ไม่ต้อง restart
$B analysis purge           # ลบทุกอย่างทันที รวมไฟล์เสียง
```

### คำใบ้ — `stt_hints.json`

อยู่ที่ `/home/kioskbroker/.config/kiosk-broker/stt_hints.json` install.sh เขียน
ให้ครั้งแรกครั้งเดียว หลังจากนั้นแก้เองได้ **ไม่ต้อง deploy ไม่ต้อง restart**
(อ่านใหม่เมื่อไฟล์เปลี่ยน) ถ้าไฟล์ผิดรูปแบบ ระบบถอดเสียงต่อได้แบบไม่มีคำใบ้ ไม่ล่ม

```bash
sudo -u kioskbroker nano /home/kioskbroker/.config/kiosk-broker/stt_hints.json
$B stt-hints-check          # ต้องขึ้น ok ก่อนใช้
```

**วิธีเพิ่มคำ:** ใส่คำในลิสต์ `"phrases"` เป็นข้อความในเครื่องหมายคำพูด คั่นด้วยจุลภาค
**เรียงคำสำคัญไว้ก่อน** — ถ้ารวมกันยาวเกิน 150 ตัวอักษร คำท้ายๆ จะถูกตัดออกจาก
คำใบ้ของ Groq (`stt-hints-check` พิมพ์คำใบ้ที่ใช้จริงออกมาให้เห็น) เพดาน 150
ตัวอักษรมาจากการวัดด้วยตัวตัดคำของ Whisper เอง: ลิสต์ปัจจุบัน 17 คำ = 142 ตัวอักษร
= 127 tokens จากเพดาน 224 ของ Groq (คำสั้นอย่าง "ไฟ" ใช้ 4 tokens ต่อ 2 ตัวอักษร
จึงไม่ปล่อยให้เกิน 150)

**⚠️ ไฟล์บน VPS ไม่ถูกเขียนทับตอน deploy** — ถ้าจะใช้ลิสต์ใหม่ใน repo (17 คำ เพิ่ม
เซ็นทรัล ดูกล้อง) ต้องคัดลอกเอง ครั้งเดียว (ถ้าเคยแก้ไฟล์เองไว้ คำที่แก้จะหาย):
```bash
sudo install -o kioskbroker -g kioskbroker -m 0644 ~/phone-ai-kiosk/server/stt_hints.json \
  /home/kioskbroker/.config/kiosk-broker/stt_hints.json
$B stt-hints-check          # ต้องขึ้น ok, 17 phrases
```

### คีย์ Google สำหรับ Speech-to-Text (ถ้าจะลอง `google`)

**ใช้คีย์เดียวกับ TTS — `GOOGLE_TTS_API_KEY` ไม่มีชื่อ env ใหม่** (Poom ตัดสินใจแล้ว)
คีย์นั้นจำกัดให้เรียกได้เฉพาะ Cloud Text-to-Speech API และ Cloud Speech-to-Text
API และจำกัด IP เป็น IPv4/IPv6 ของ VPS

✅ วิธีส่งคีย์ วัดบน VPS แล้ว: ใส่ใน header `X-Goog-Api-Key` ได้ **403 "Method
doesn't allow unregistered callers"** ส่วน query string `?key=` ได้ 400
"RecognitionAudio not set" (แปลว่าคีย์ผ่าน) โค้ดจึงส่งแบบ `?key=` เหมือน TTS
URL จึงมีคีย์อยู่ ดังนั้น**ไม่มีจุดไหนบันทึก URL** — error ทุกตัวสร้างจากรหัสสถานะ
และคำสถานะของ Google เท่านั้น มีเทสต์ยืนยันว่าคีย์ไม่หลุดเข้า log และ error ทุกกรณี

### เทียบผล — `stt-compare` (เพดานรวมทุกครั้ง $0.20)

ใช้เสียงชุดเดียวกันส่งเข้า groq, groq-hints, google แล้วพิมพ์ตาราง: คำหลักถูกไหม
(กล้อง/เซ็นทรัล/อากาศ/ไฟ), CER (สัดส่วนตัวอักษรที่ผิด), เวลา, ค่าใช้จ่าย
ทุกบาทลงบัญชี `training-usage` job `stt-compare` และ**หยุดเองก่อนเกิน $0.20**

เสียงของ Poom เอง (ดีที่สุด):
1. `$B analysis on --audio`
2. ที่ kiosk พูดทีละประโยค **ตามลำดับนี้** (Hey Jarvis ก่อนทุกประโยค):
   ขอดูกล้องหน่อยครับ · พาไปเซ็นทรัลเชียงราย · วันนี้อากาศเป็นยังไง · เปิดไฟห้องนั่งเล่น
3. `$B analysis summary` ดูเลข `#id` ของแถวที่มีคำว่า `audio`
4. `$B stt-compare --ids 12,15` — **เทียบแบบไม่มีเฉลย**: แสดงแค่ข้อความที่ถอดได้
   เวลา ค่าใช้จ่าย **ไม่แสดงความถูกต้อง** เพราะระบบไม่รู้ว่าเสียงไหนคือประโยคไหน
5. ถ้าจะให้ตัดสินถูกผิด ต้องบอกเองว่า id ไหนคือประโยคที่เท่าไร (1–4 ตามลิสต์ข้างบน):
   `$B stt-compare --ids 12,15 --expect 12=1,15=3` — ฟังจากข้อความใน summary ว่าเป็น
   ประโยคไหน **ห้ามเดาจากลำดับ** (รอบ 23/09 เสียงอากาศถูกจับคู่กับประโยคเซ็นทรัลเพราะลำดับ)
6. `$B analysis off` แล้ว `$B analysis purge` เมื่อได้ผลแล้ว

หรือเสียงสังเคราะห์ (ตัดสินได้เสมอ เพราะรู้ข้อความ แต่เป็นเสียงสะอาด ไม่ใช่คำตัดสิน):
`$B stt-compare --synth`

ถ้า Google ยังใช้ไม่ได้: เพิ่ม `--providers groq,groq-hints`

`/healthz` ต้องแสดง `"build"` ของรุ่นนี้ก่อน ถ้าไม่มี แปลว่ายังไม่ได้ deploy
