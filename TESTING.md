# คู่มือทดสอบเฟส 1 บน Galaxy A07

ทุกคำสั่งในไฟล์นี้ **รันบน PowerShell ของคอมพิวเตอร์** (ไม่ใช่บนมือถือ)
ต่อสาย USB และเปิด USB debugging ไว้ตลอด — นี่คือทางกู้คืนทางเดียว

ชื่อ component ที่ใช้ตลอดคู่มือนี้ ลอกไปวางได้เลย:

```
com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
```

ระวัง: ฝั่งซ้ายของ `/` มี `.debug` ต่อท้าย (application id ของ debug build)
ฝั่งขวา **ไม่มี** `.debug` (ชื่อคลาส Java) — สลับกันเมื่อไรจะได้ error ว่าหา
admin ไม่เจอ

---

## ทางกู้คืน — อ่านก่อนเริ่ม

ถ้าอะไรพัง เช่น แอปค้าง เปิดไม่ขึ้น หรือออกจาก kiosk ไม่ได้ ให้รันสองคำสั่งนี้
ตามลำดับ เครื่องจะกลับเป็นปกติโดยไม่ต้อง factory reset:

```powershell
adb shell dpm remove-active-admin com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
adb uninstall com.mammonrn.phoneaikiosk.debug
```

ที่ทำได้เพราะ debug build ตั้ง `android:testOnly="true"` ไว้ ถ้าไม่มีธงนี้
Android จะไม่ยอมให้ถอด Device Owner เลย และทางออกเดียวคือ factory reset

---

## ขั้น A — ตรวจสภาพเครื่องก่อนตั้ง Device Owner

`dpm set-device-owner` จะ **ล้มทันทีถ้ามีบัญชีใดๆ ผูกอยู่ในเครื่อง**
(บัญชี Google, Samsung account, อะไรก็ตาม) ตรวจก่อน

```powershell
adb devices
```
ต้องเห็นเครื่องขึ้นสถานะ `device` ถ้าขึ้น `unauthorized` ให้ดูจอมือถือแล้วกดอนุญาต

```powershell
adb shell dumpsys account | Select-String "Account \{"
```
**ต้องไม่มีผลลัพธ์ออกมาเลย** ถ้ามีบรรทัดไหนโผล่ แปลว่ายังมีบัญชีผูกอยู่
ต้องเข้าไปลบใน Settings ก่อน

```powershell
adb shell pm list users
```
ควรมี user เดียวคือ `UserInfo{0:...}`

```powershell
adb shell dpm list-owners
```
ตอนนี้ต้องยังไม่มี owner

---

## ขั้น B — ติดตั้ง APK

ดาวน์โหลด artifact `phone-ai-kiosk-debug-apk` จากหน้า Actions แตก zip จะได้
`app-debug.apk` แล้ว cd ไปที่โฟลเดอร์นั้น

```powershell
adb install -t -r app-debug.apk
```

**ต้องมี `-t`** เพราะ APK ตั้ง testOnly ไว้ ถ้าไม่ใส่จะได้
`INSTALL_FAILED_TEST_ONLY`

เปิดแอปด้วยมือก่อน เพื่อดูว่ามันไม่ crash **ก่อน** จะไปตั้ง Device Owner:

```powershell
adb shell am start -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.MainActivity
```

ดูที่จอมือถือ ต้องเห็นนาฬิกาเดิน คำว่า `Phase 1 Kiosk` และบรรทัดสถานะ

```
owner=no  lock=off  taps=0/10
```

`owner=no` ตอนนี้ถูกต้องแล้ว เพราะยังไม่ได้ตั้ง Device Owner
ตอนนี้กด Home ต้องออกไปหน้า Samsung ได้ตามปกติ (ยังไม่ได้ล็อกอะไร)

**ถ้าแอป crash หรือจอดำ ให้หยุดตรงนี้ อย่าไปขั้น C** แล้วบอกผม

---

## ขั้น C — ตั้ง Device Owner

```powershell
adb shell dpm set-device-owner com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
```

ผลที่ต้องได้:
```
Success: Device owner set to package com.mammonrn.phoneaikiosk.debug
```

ยืนยัน:
```powershell
adb shell dpm list-owners
```

จากนั้น **เปิดแอปหนึ่งครั้ง** เพื่อให้มันลงนโยบาย (lock task allowlist + ตั้ง
ตัวเองเป็น Home ถาวร) — นโยบายพวกนี้ลงตอนแอปเปิด ไม่ใช่ตอนตั้ง owner

```powershell
adb shell am start -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.MainActivity
```

ที่จอต้องเปลี่ยนเป็น:
```
owner=yes  lock=on  taps=0/10
```

---

## ขั้น D — ทดสอบว่าออกจาก kiosk ไม่ได้

ทำที่ตัวเครื่อง ไม่ต้องใช้คำสั่ง:

1. กดปุ่ม **Home** → ต้องอยู่หน้าเดิม ไม่ออกไปหน้า Samsung
2. กดปุ่ม **Back** → ต้องอยู่หน้าเดิม
3. กดปุ่ม **Recents / ปุ่มสามขีด** → ต้องไม่มีอะไรเกิดขึ้น
4. **ปัดลงจากขอบบน** เพื่อเรียกแถบแจ้งเตือน → ต้องไม่ลงมา
5. กดปุ่มเพาเวอร์ค้าง → เมนูปิดเครื่อง/รีสตาร์ท **ต้องขึ้น** (อันนี้ตั้งใจให้ขึ้น
   เป็นค่าเริ่มต้นของ lock task และเป็นวิธีที่จะใช้รีบูตในขั้นถัดไป)

ถ้าข้อ 1–4 ข้อไหนหลุดออกไปได้ ให้จดว่าข้อไหนแล้วบอกผม

---

## ขั้น E — ทดสอบรีบูต

กดปุ่มเพาเวอร์ค้าง → เลือก Restart (หรือ)

```powershell
adb reboot
```

รอเครื่องบูตเสร็จ **ต้องเข้าหน้า kiosk เองโดยไม่ต้องกดอะไร** และบรรทัดสถานะ
ต้องเป็น `owner=yes  lock=on` อีกครั้ง

ไม่ควรมีหน้าจอให้เลือก launcher โผล่มา ถ้ามี แปลว่าการตั้ง Home ถาวรไม่ติด
ให้บอกผม

---

## ขั้น F — ทดสอบทางออกลับ 10 ครั้ง

1. แตะ **มุมขวาล่างสุดของจอ** เร็วๆ ติดกัน 10 ครั้ง
   - พื้นที่แตะกว้างประมาณ 96dp (ราวปลายนิ้วโป้ง 2 นิ้ววางต่อกัน) อยู่มุมล่างขวา ไม่มีอะไรแสดงให้เห็น
   - ดูตัวเลข `taps=` บนจอไต่ขึ้น 1,2,3... ใช้ยืนยันว่าแตะโดนจุด
   - **ต้องแตะห่างกันไม่เกิน 1.5 วินาที** ถ้าช้ากว่านั้นตัวนับจะเริ่มใหม่ที่ 1
2. พอครบ 10 → เครื่องต้องออกจาก lock task และเด้งไปหน้า Samsung (One UI Home)
3. ตอนนี้ใช้เครื่องได้ตามปกติ เปิด Settings เปิดแอปอื่นได้

**สิ่งที่ต้องรู้:** แอปยังเป็น Home อยู่ ดังนั้น**กดปุ่ม Home จะกลับมาหน้า kiosk**
แต่จะกลับมาแบบ **ไม่ล็อก** (`lock=off`) กด Home ออกไปใหม่ได้เรื่อยๆ
จะล็อกอีกทีเมื่อรีบูตเครื่อง — ตั้งใจให้เป็นแบบนี้ เพราะข้อกำหนดบอกว่ารีบูต
แล้วต้องกลับเข้า kiosk ซึ่งทำได้ก็ต่อเมื่อแอปยังเป็น Home อยู่

4. ทดสอบต่อ: รีบูตอีกครั้ง → ต้องกลับมา `lock=on` เหมือนเดิม

---

## ขั้น G — ถอด Device Owner ออก

```powershell
adb shell dpm remove-active-admin com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
```

ยืนยันว่าไม่มี owner แล้ว:
```powershell
adb shell dpm list-owners
```
ต้องไม่มีผลลัพธ์

ถอนแอป:
```powershell
adb uninstall com.mammonrn.phoneaikiosk.debug
```

**ต้องถอด admin ก่อนถอนแอปเสมอ** ถ้าถอนแอปทั้งที่ยังเป็น Device Owner อยู่
Android จะปฏิเสธ

จากนั้นกด Home ครั้งแรกเครื่องอาจถามว่าจะใช้ launcher ตัวไหน ให้เลือก One UI Home

---

## สรุปคำสั่งตรวจสถานะ

| อยากรู้ | คำสั่ง (PowerShell) |
|---|---|
| เครื่องต่ออยู่ไหม | `adb devices` |
| มีบัญชีผูกอยู่ไหม | `adb shell dumpsys account \| Select-String "Account \{"` |
| ใครเป็น Device Owner | `adb shell dpm list-owners` |
| แอปติดตั้งอยู่ไหม | `adb shell pm list packages \| Select-String phoneaikiosk` |
| ดู log แอป | `adb logcat -s AndroidRuntime:E ActivityManager:I` |
