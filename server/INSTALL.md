# ติดตั้ง kiosk broker (เฟส 2)

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

ต้องได้คำตอบภาษาไทยสั้นๆ พร้อมจำนวนโทเคนและค่าใช้จ่ายที่คิดจาก `pricing.json`
ประมาณ $0.0002 ต่อครั้ง

`selftest` **ไม่บันทึกลงบัญชีงบ** ของมือถือ

---

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

## ดูงบที่ใช้ไป

```bash
sudo -u kioskbroker env KIOSK_BROKER_HOME=/home/kioskbroker/.config/kiosk-broker \
  PYTHONPATH=/home/kioskbroker/app \
  /home/kioskbroker/venv/bin/python -m kiosk_broker usage
```

ยอดนี้เป็นของมือถือเครื่องเดียว แยกฐานข้อมูลจากของสายฝนใน Telegram คนละไฟล์
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

## อัปเดตโค้ดรอบต่อไป

```bash
cd ~/phone-ai-kiosk && git pull
sudo bash server/install/install.sh      # idempotent
sudo systemctl restart kiosk-broker
```

`install.sh` ไม่เขียน `config.json` หรือ `env` ทับถ้ามีอยู่แล้ว
