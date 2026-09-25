# คู่มือทดสอบเฟส 1 บน Galaxy A07

> **กติกาถาวร: คำสั่งลบ (Poom 2026-09-24)**
> - ห้ามใช้คำสั่งลบแบบ wildcard ที่พึ่งพาโฟลเดอร์ปัจจุบัน เช่น `cd X && rm -f *` ถ้า `cd` ล้มเหลว จะลบใน repo
> - คำสั่งลบทุกครั้งต้องระบุเส้นทางเต็ม
> - ถ้าจำเป็นต้องลบในโฟลเดอร์ชั่วคราว ให้ตรวจก่อนว่าโฟลเดอร์นั้นมีอยู่จริงและถูกต้อง ดีกว่านั้นคือสร้างโฟลเดอร์ใหม่แล้วเขียนลงไปเลย ไม่ต้องลบ
> - บน Git Bash ใส่ `MSYS_NO_PATHCONV=1` ก่อนคำสั่ง adb ที่มี `/sdcard/...` ไม่อย่างนั้น path จะถูกแปลงเป็นของ Windows


ทุกคำสั่งในไฟล์นี้ **รันบน PowerShell ของคอมพิวเตอร์** (ไม่ใช่บนมือถือ)
ต่อสาย USB และเปิด USB debugging ไว้ตลอด — นี่คือทางกู้คืนทางเดียว

> **เฟส 1 ผ่านครบทุกข้อบนเครื่องจริงแล้ว** Galaxy A07 / Android 15 เมื่อ 21 ก.ย. 2569
> สิ่งที่เขียนในไฟล์นี้คือพฤติกรรมที่วัดได้จริง ไม่ใช่ที่คาดว่าจะเป็น
>
> | ข้อ | ผล | หลักฐาน |
> |---|---|---|
> | Device Owner | ผ่าน | `dpm list-owners` แสดง DeviceOwner |
> | Lock task | ผ่าน | `mLockTaskModeState=LOCKED` |
> | HOME / BACK / APP_SWITCH หนีไม่ออก | ผ่าน | ยิง keyevent ครบสามปุ่มแล้วยัง LOCKED |
> | รีบูตกลับเข้า kiosk | ผ่าน | บูตแล้ว LOCKED + top activity เป็น MainActivity |
> | แตะ 10 ครั้งออกได้ | ผ่าน | `input tap 650 1440` ×10 → NONE + launcher Samsung |
> | จอค้างเฉพาะตอนชาร์จ | ผ่าน | `dumpsys battery unplug` → `awake=off`, `reset` → `awake=on` |
> | อัปเดตทับไม่ต้องถอด owner | ผ่าน | `adb install -r -t` สำเร็จขณะเป็น Device Owner |
> | กุญแจเซ็นคงที่ | ผ่าน | APK versionCode 3 ลงทับได้ |

**ทางลัด:** มีสคริปต์รวมคำสั่งตรวจไว้ให้แล้วที่ [`tools/Phase1-Check.ps1`](tools/Phase1-Check.ps1)
รันเปล่าๆ จะ **อ่านอย่างเดียว** ไม่ลง ไม่ถอน ไม่รีบูต ไม่แตะการตั้งค่าใดๆ
(สิ่งเดียวที่เขียนคือไฟล์ชั่วคราว `/sdcard/ui.xml` ที่ `uiautomator` ใช้ส่งผลกลับ
แล้วลบทิ้งทั้งก่อนและหลังอ่าน)

```powershell
.\tools\Phase1-Check.ps1
```

โหมดที่เปลี่ยนเครื่อง **ต้องพิมพ์ YES ก่อนเสมอ**:

| สวิตช์ | ทำอะไร |
|---|---|
| `-RebootTest` | รีบูตแล้วตรวจว่ากลับเข้า kiosk |
| `-ExitTest` | แตะมุม 10 ครั้งเพื่อออกจาก lock task |
| `-AwakeTest` | แกล้งถอดที่ชาร์จ ตรวจ `awake=off` แล้ว **reset คืนเสมอ** |
| `-UpdateTest -Apk .\app-debug.apk` | ลง APK ทับขณะเป็น Device Owner |

สคริปต์อ่านบรรทัดสถานะจากหน้าจอจริงผ่าน `uiautomator` (ไม่ใช่เดาจาก dumpsys)
เพราะ `awake=` ไม่มีใน dumpsys ที่ไหนเลย

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

## ถ้า adb ขึ้น unauthorized

เจอจริงตอนเปลี่ยนกุญแจ adb ฝั่งคอม เครื่องจะค้างที่ `unauthorized` และ**ไม่มี
หน้าต่างขออนุญาตเด้งขึ้นมาอีก** เพราะเครื่องยังจำกุญแจเก่าว่า "อนุญาตแล้ว"

วิธีแก้บนตัวมือถือ:
1. Settings → Developer options → **Revoke USB debugging authorizations** → ยืนยัน
2. ถอดสาย เสียบใหม่
3. หน้าต่าง "Allow USB debugging?" จะเด้ง → ติ๊ก Always allow → Allow

แล้วเช็คว่ากลับมาปกติ:
```powershell
adb kill-server
adb devices
```

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

### บัญชีซ่อนของ Google Meet — เจอจริงบน A07

เครื่องที่รีเซ็ตโรงงานแล้วและ**ยังไม่ได้ล็อกอิน Google เลย** ก็ยังขึ้น
`Accounts: 1` ได้ ตัวการคือ Google Meet ที่ติดมากับเครื่อง มันสร้างบัญชี
ชนิด `com.google.android.apps.tachyon` ซึ่ง **ไม่โผล่ในหน้า Settings → Accounts**
จึงลบทางหน้าจอไม่ได้

ถอนออกจาก user 0 (ตัวแอปยังอยู่ในเครื่อง ไม่ได้ลบทิ้งถาวร):
```powershell
adb shell pm uninstall --user 0 com.google.android.apps.tachyon
```

ตรวจซ้ำว่าเหลือ 0 บัญชีแล้ว:
```powershell
adb shell dumpsys account | Select-String "Account \{"
```

อยากได้ Google Meet คืนเมื่อไร (ทำได้ทุกเมื่อ แม้หลังตั้ง Device Owner แล้ว):
```powershell
adb shell cmd package install-existing com.google.android.apps.tachyon
```

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
owner=no  lock=off  awake=on  taps=0/10
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
owner=yes  lock=on  awake=on  taps=0/10
```

`awake=on` แปลว่าแอปกำลังถือจอไว้ (เสียบชาร์จและมีคนใช้ภายใน 5 นาที) ถ้าถอด
สายชาร์จจะเปลี่ยนเป็น `awake=off` ภายในไม่กี่วินาที แล้วจอจะดับตามการตั้งค่าปกติ
ของเครื่อง

**ตั้งแต่ v0.20.0 จอดับเองเมื่อไม่มีคนใช้ 5 นาที ไม่ว่าเสียบชาร์จหรือไม่** (Poom
เปลี่ยนการตัดสินใจเดิมที่ให้จอติดตลอดตอนชาร์จ) ไมค์ยังฟังคำปลุกอยู่ และพูด
"Hey Jarvis" แล้วจอติดขึ้นมาเอง ดูขั้นทดสอบที่ท้ายไฟล์

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

ตรวจซ้ำจากฝั่งคอมก็ได้ — ยิงปุ่มผ่าน adb แล้วดูว่า state ไม่เปลี่ยน:
```powershell
adb shell input keyevent HOME
adb shell input keyevent BACK
adb shell input keyevent APP_SWITCH
adb shell dumpsys activity activities | Select-String "LockTask|ResumedActivity"
```
ทดสอบจริงแล้ว: ยิงครบสามปุ่มแล้ว `mLockTaskModeState` ยังเป็น `LOCKED`

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

### พฤติกรรมจริงที่วัดได้บน A07 (ไม่ใช่ที่คาดไว้)

ลำดับที่เกิดขึ้นจริง ยืนยันด้วย `dumpsys` ทุกขั้น:

| ทำอะไร | `mLockTaskModeState` | `topResumedActivity` |
|---|---|---|
| ก่อนแตะ | `LOCKED` | `…phoneaikiosk.debug/….MainActivity` |
| แตะครบ 10 ครั้ง | `NONE` | `com.sec.android.app.launcher/.activities.LauncherActivity` |
| จากนั้นกดปุ่ม HOME | `NONE` | `…phoneaikiosk.debug/….MainActivity` |
| รีบูต | `LOCKED` | `…phoneaikiosk.debug/….MainActivity` |

อ่านตารางนี้ว่า: **แตะ 10 ครั้งแล้วไปโผล่ที่ launcher ของ Samsung จริง**
แต่แอปเรายังเป็น Home อยู่ (`resolve-activity` ยังชี้มาที่เรา และ `isDefault=true`)
ดังนั้น**กดปุ่ม Home จะกลับมาหน้า kiosk — แต่กลับมาแบบไม่ล็อก** กด Home ออกไป
ใหม่ได้เรื่อยๆ ไม่ต้องแตะ 10 ครั้งซ้ำ

จะกลับไปล็อกอีกทีเมื่อ **รีบูต** หรือเมื่อระบบฆ่าโปรเซสแอปทิ้ง

ที่ออกแบบแบบนี้เพราะข้อกำหนดบอกว่ารีบูตแล้วต้องกลับเข้า kiosk ซึ่งทำได้ก็ต่อเมื่อ
แอปยังเป็น Home ถาวรอยู่ ถ้าไปถอด Home ตอนออก รีบูตแล้วจะไปโผล่ launcher ของ
Samsung แทน

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

## ย้ายมาใช้กุญแจเซ็นตัวใหม่ — ทำครั้งเดียว

**ทำไมต้องทำ:** ก่อนหน้านี้ CI สร้างกุญแจเซ็นใหม่ทุกรอบ พิสูจน์แล้วด้วยการเทียบ
ใบรับรองของ APK สองรอบ (`d5e883…` กับ `b7bd30…` คนละใบ) Android ไม่ยอมให้
อัปเดตแอปที่ลายเซ็นเปลี่ยน และแอปนี้ถอนทิ้งเฉยๆ ไม่ได้เพราะเป็น Device Owner
→ ทุกครั้งที่อัปเดตต้องรื้อ Device Owner ใหม่ทั้งหมด

ตอนนี้ CI ใช้กุญแจคงที่จาก GitHub Secret แล้ว แต่เครื่องที่ยังมี APK กุญแจเก่า
ติดตั้งอยู่ **ต้องล้างครั้งสุดท้ายหนึ่งรอบ** ตามลำดับนี้ (สลับลำดับไม่ได้):

```powershell
adb shell dpm remove-active-admin com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
adb uninstall com.mammonrn.phoneaikiosk.debug
adb install -t app-debug.apk
adb shell dpm set-device-owner com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
```

แล้วเปิดแอปหนึ่งครั้งให้มันลงนโยบาย:
```powershell
adb shell am start -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.MainActivity
```

**หลังจากรอบนี้แล้ว การอัปเดตครั้งต่อๆ ไปใช้คำสั่งเดียว ไม่ต้องถอด Device Owner:**

```powershell
adb install -r -t app-debug.apk
```

ถ้าเจอ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` แปลว่าลายเซ็นไม่ตรง — เช็คที่หน้า
summary ของ run นั้นว่าขึ้น "Signed with the **pinned** keystore" หรือเปล่า
ถ้าขึ้นเตือนว่าใช้กุญแจชั่วคราว แปลว่า secret หาย ต้องบอกผม

---

## เฟส 3 — ใส่ token ให้มือถือ (ไม่มี token ใน APK เด็ดขาด)

token **ไม่อยู่ใน APK ไม่อยู่ในซอร์ส ไม่อยู่ใน repo ไม่อยู่ใน log และไม่อยู่ใน artifact**
ใส่เข้าเครื่อง debug ผ่าน adb และเก็บไว้ในพื้นที่ส่วนตัวของแอปเท่านั้น

ออก token จาก VPS ก่อน (ดู `server/INSTALL.md` ขั้น 8) แล้วเซฟลงไฟล์ `token.txt`
บนคอม **อย่าพิมพ์ token ลงบรรทัดคำสั่งตรงๆ** เพราะจะค้างใน history ของ PowerShell

```powershell
# 1) ส่งไฟล์ไปที่พื้นที่ของ shell (ไม่ใช่ /sdcard ซึ่งแอปอื่นอ่านได้)
adb push token.txt /data/local/tmp/kiosk-token

# 2) ย้ายเข้าพื้นที่ส่วนตัวของแอป — cat รันในฐานะ shell ที่อ่านได้
#    แล้วส่งต่อเข้า run-as ที่เขียนในฐานะแอป
adb shell "mkdir -p /data/local/tmp && cat /data/local/tmp/kiosk-token | run-as com.mammonrn.phoneaikiosk.debug sh -c 'mkdir -p files && cat > files/device_token'"

# 3) ลบร่องรอย
adb shell rm /data/local/tmp/kiosk-token
del token.txt
```

ตรวจว่าเข้าไปแล้ว (ไม่แสดงค่า แค่บอกว่ามีกี่ตัวอักษร):

```powershell
adb shell run-as com.mammonrn.phoneaikiosk.debug sh -c 'wc -c files/device_token'
```

หรือดูที่จอ: บรรทัดสถานะจะขึ้น `token=yes`

**เปลี่ยน token:** ทำขั้น 1–3 ซ้ำด้วยไฟล์ใหม่
**ลบ token:**

```powershell
adb shell run-as com.mammonrn.phoneaikiosk.debug rm files/device_token
```

จากนั้นเพิกถอนฝั่งเซิร์ฟเวอร์ด้วย `revoke-token` (ดู server/INSTALL.md)

❓ คำสั่งชุดนี้ยังไม่ได้ทดสอบบนเครื่องจริง โดยเฉพาะการอ้างอิง `run-as` กับ quoting
ของ PowerShell — ถ้าติด ให้ส่ง error มา

---

## เฟส 3 — ทดสอบเส้นทางเสียงก่อนมีโมเดลคำปลุก

โมเดลคำปลุกยังไม่มี (ดู [WAKEWORD.md](WAKEWORD.md)) ระหว่างนี้ใช้ตัวกระตุ้นลับ
ผ่าน adb เพื่อทดสอบ STT → chat → TTS ได้ทั้งเส้น **ตัวกระตุ้นนี้มีเฉพาะใน debug
build ไม่มีปุ่มบนจอ และไม่มีอยู่ใน release build เลย**

```powershell
# ต้องมี -p ระบุ package ไม่งั้นแอปไม่ได้รับ broadcast เลย (ยืนยันบน A07 แล้ว)
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_LISTEN -p com.mammonrn.phoneaikiosk.debug
```

**`-p` จำเป็น** Android จำกัด implicit broadcast ไปยังแอปที่ไม่ได้ทำงาน
ถ้าไม่ระบุ package คำสั่งจะขึ้นว่าส่งสำเร็จแต่แอปไม่เคยได้รับ

ตรวจการ์ดจาร์วิสกับคำตอบยาว โดยไม่อัดเสียงและไม่เสียเงิน: คำสั่งนี้วางคำตอบตัวอย่างของเราเองไว้บนจอ แล้วทำเหมือนกำลังพูดอยู่ 15 วินาที เพื่อดูว่าจอเลื่อนตามเสียงไหม

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_SHOW_REPLY -p com.mammonrn.phoneaikiosk.debug
# ยาวจนต้องเลื่อน: ทวนคำตอบ 1-4 รอบ
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_SHOW_REPLY --ei times 3 -p com.mammonrn.phoneaikiosk.debug
# ส่งคำถามเป็นข้อความผ่านเส้นทางจริง (/v1/chat → เสียง → action) ไม่ต้องพูด
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "ตั้งปลุก 11 โมงเช้าได้ไหมครับ" -p com.mammonrn.phoneaikiosk.debug
# นาฬิกาปลุกทดสอบชื่อ "ทดสอบ" ในอีก N นาที (ไม่ผ่าน broker) แล้วลบทิ้ง
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ALARM_IN --ei in_minutes 2 -p com.mammonrn.phoneaikiosk.debug
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ALARM_CLEAR -p com.mammonrn.phoneaikiosk.debug
```

### อ่านสถานะด้วย dumpsys (แนะนำ ไม่ต้องพึ่ง uiautomator)

```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService
```

⚠️ **ต้องใช้ชื่อคลาสเต็ม** ย่อเป็น `.voice.VoiceService` ไม่ได้ เพราะ Android
เติมชื่อย่อด้วย **package id** ซึ่งมี `.debug` ต่อท้าย ได้เป็น
`com.mammonrn.phoneaikiosk.debug.voice.VoiceService` แต่คลาสจริงคือ
`com.mammonrn.phoneaikiosk.voice.VoiceService` (ไม่มี `.debug`)

**กับดักเดียวกับ component ของ Device Owner** — `applicationIdSuffix` เปลี่ยน
package id ไม่เปลี่ยนชื่อคลาส Java

ได้แบบนี้:

```
kiosk voice state
  mic        : open
  detector   : no-model
  wake       : listening
  stt        : ok
  chat       : ok
  tts        : ok
  level      : 1573
  token      : yes
  turns      : 1
  last-error : none
  capture-mode : LISTENING
  armed        : false
```

⚠️ **`adb shell uiautomator dump` ใช้กับหน้าจอนี้ไม่ได้** เพราะนาฬิกาเดินทุกวินาที
ทำให้ลำดับชั้นของหน้าจอไม่เคยนิ่ง uiautomator จึงรอจนหมดเวลาแล้วล้ม
(เจอจริงบน A07) — ให้ใช้ `dumpsys` ข้างบนแทน

### ดู log สดตอนทดสอบ

```powershell
adb logcat -s KioskVoice:I
```

ทุกขั้นมี log tag เดียวคือ `KioskVoice` เห็นการเปลี่ยนสถานะ จำนวนไบต์ที่อัด
HTTP status และเวลาที่ใช้ **ไม่มี token ไม่มีคีย์ ไม่มีเสียงดิบ และไม่มีข้อความเต็ม**
มีแต่จำนวนตัวอักษร

ดูที่จอมือถือ จะเห็นทีละขั้น:

```
mic=open wake=triggered stt=recording chat=idle tts=idle
mic=open wake=triggered stt=ok chat=asking tts=idle
ได้ยิน: วันนี้อากาศเป็นยังไง
ตอบ: ผมยังดูอากาศให้ไม่ได้ครับ
mic=open wake=listening stt=ok chat=ok tts=ok
```

ชี้ไปเซิร์ฟเวอร์อื่นชั่วคราว (เช่นทดสอบกับ staging):

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_SET_BROKER --es url "https://kiosk.xn--l3cgts1b3bzcvf.com"
```

### ความหมายของแต่ละสถานะ

| ช่อง | ค่าที่เป็นไปได้ |
|---|---|
| `mic` | `off` `open` `closed` `error` **`no-permission`** |
| `wake` | `idle` `listening` **`heard`** `detected` `triggered` **`no-model`** **`model-load-failed`** |
| `stt` | `idle` `recording` `sending` `ok` `error` **`empty`** **`too-short`** |
| `chat` | `idle` `asking` `ok` `error` |
| `tts` | `idle` `synthesising` **`speaking`** `ok` **`device-fallback`** `failed` |
| `capture-mode` | `LISTENING` `CAPTURING` **`BUSY`** (จาก dumpsys) |
| `last-action` | `none` `open_maps:opened` `open_maps:not_installed` `open_maps:refused` `open_maps:failed` |
| `maps` | `unknown` `ready` `not-installed` `location-denied` `granted-unconfirmed` **`installed-no-dpm`** **`installed-not-owner`** `opened` |

`stt=empty` แปลว่าอัดแล้วไม่ได้เสียงเลย — ไม่ถูกส่งขึ้นเซิร์ฟเวอร์และไม่เสียเงิน

`mic=no-permission` แปลว่า Device Owner ให้สิทธิ์ไมค์ตัวเองไม่สำเร็จ
ทางแก้ชั่วคราว: `adb shell pm grant com.mammonrn.phoneaikiosk.debug android.permission.RECORD_AUDIO`
**แล้วบอกผม** เพราะแปลว่าข้อสันนิษฐานเรื่อง `setPermissionGrantState` ผิด

`tts=device-fallback` แปลว่า Cloud TTS ใช้ไม่ได้และใช้เสียงในเครื่องแทน —
ตั้งใจให้เป็นแบบนี้ ไม่ใช่ความผิดพลาด

`tts=synthesising` คือกำลังรอเสียงจากเซิร์ฟเวอร์ `tts=speaking` คือได้เสียงแล้ว
และกำลังพูดอยู่ สองอันนี้แยกกันเพราะมันเป็นคนละปัญหา — อันแรกช้าคือปัญหา
อันที่สองยาวคือคำตอบยาว

### อ่านเวลาว่าช้าที่ชั้นไหน

บรรทัด `tts` ใน log มีทุกชั้นในบรรทัดเดียว:

```
tts ok 28813 bytes synth=1310ms play=9340ms ttfb=1290ms dl=20ms broker=1240ms net=50ms vendor=1235ms audio=9600ms
```

| ค่า | ใครวัด | ความหมาย |
|---|---|---|
| `synth=` | มือถือ | ขอเสียงจนได้เสียงครบ **นี่คือ latency จริง** |
| `play=` | มือถือ | เวลาที่ใช้พูดออกลำโพง **ไม่ใช่ latency** |
| `ttfb=` | มือถือ | ส่งคำขอจนได้ response header |
| `dl=` | มือถือ | โหลด body ต่อจาก header |
| `broker=` | broker | เวลาใน handler ทั้งหมด (ส่งกลับมาใน header) |
| `net=` | คำนวณ | `ttfb - broker` = เครือข่าย + TLS + nginx |
| `vendor=` | broker | เวลาที่ Google ใช้จริง |
| `audio=` | broker | **ความยาวของเสียง** อ่านจาก granule ของ Ogg Opus |

`net` ติดลบเล็กน้อยได้ เพราะเป็นนาฬิกาสองเครื่องและ broker หยุดนับก่อนไบต์
สุดท้ายออก — พิมพ์ตามที่วัดได้ ไม่ปัดขึ้นศูนย์ เพราะการปัดจะกลบนาฬิกาที่เพี้ยน

ฝั่งเซิร์ฟเวอร์มีสองไฟล์ให้เทียบ ถ้าต้องยืนยัน:

```bash
sudo journalctl -u kiosk-broker -n 50 | grep 'tts ok'
sudo tail -5 /var/log/nginx/kiosk-timing.log
```

`upstream_header` ของ nginx ควรใกล้ `broker` ของมือถือ และ
`request - upstream_all` คือเวลาที่ nginx เติมเข้ามาเอง

### 🔴 การวัดผิดที่เคยหลงทางมาแล้ว

log ของ versionCode 5 เขียนว่า `tts ok 28813 bytes 9343 ms` เลขเดียว และถูกอ่าน
ว่า "Google ช้า 9 วินาที" **ซึ่งไม่จริง** ตัวจับเวลาเดียวนั้นครอบทั้งการสร้างเสียง
และการเล่นเสียง และ `Speaker.play` รอจนเสียงพูดจบ (`done.await()`) ก่อนคืนค่า

คำตอบภาษาไทยหนึ่งประโยคใช้เวลาพูดหลายวินาที ดังนั้นเลขนั้นคือ "เวลาพูด" เกือบทั้งก้อน
เลขเดียวที่รวมสองอย่างซึ่งแก้ด้วยวิธีต่างกัน คือเลขที่พาไปแก้ผิดจุด
ตั้งแต่ versionCode 6 แยกเป็น `synth=` กับ `play=` และมีเทสต์
`synthesis and playback are timed separately` กันถอยหลัง

### 🔴 บั๊กที่แก้แล้วใน versionCode 5 — อาการที่เคยเจอ

ถ้าเห็น `wake=triggered` แล้ว `stt` ค้างที่ `idle` ไม่ขยับเลย นั่นคือบั๊กของ
versionCode 4 **ต้องอัปเป็น versionCode 5 ขึ้นไป**

สาเหตุ: service ใช้ executor เธรดเดียว ลูปฟังไมค์จองเธรดนั้นไว้ตลอด งานที่
trigger สั่งจึงถูกต่อคิวหลังงานที่ไม่จบ `wake` เปลี่ยนได้เพราะตั้งบนเธรด binder
แต่ `stt` ไม่มีทางขยับ และไม่มี exception ให้เห็นเลย

แก้เป็นสองเธรดแยก: เธรดจับเสียงถือไมค์เท่านั้น เธรดเครือข่ายทำงาน STT/chat/TTS
พร้อมกับแก้อีกจุดที่ยังไม่เคยแสดงอาการ — โค้ดเดิมเปิด AudioRecord ตัวที่สอง
ขณะลูปแรกยังถืออยู่ ซึ่งจะพังทันทีที่มีโมเดลคำปลุก

---

## คำปลุก "Hey Jarvis" — ทดสอบทีละขั้นบน A07

ผู้ช่วยชื่อ **จาร์วิส** คำปลุกคือ **"Hey Jarvis"** (ภาษาอังกฤษ เพราะโมเดลเป็น
pretrained ของ openWakeWord) ทุกอย่างรันบนเครื่อง ไม่มีเสียงออกจากห้องก่อนเจอคำปลุก

### ขั้น 0 — ติดตั้ง versionCode 12

```powershell
adb install -r -t app-debug.apk
```

`-t` จำเป็นเพราะ debug build เป็น `testOnly` และ `-r` เพื่อไม่ให้เสีย Device Owner

```powershell
adb shell dumpsys package com.mammonrn.phoneaikiosk.debug | Select-String versionCode
```
ต้องเห็น `versionCode=12`

### ขั้น 1 — detector โหลดโมเดลได้จริงไหม

```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService
```

ต้องเห็น:

```
  detector   : hey_jarvis
  score      : 0.0000  (threshold 0.40)
  detections : 0
  deaf-for-ms  : 0
```

🔴 ถ้าเห็น `detector : model-load-failed` แปลว่าไฟล์โมเดลไม่ได้เข้า APK
หรือ onnxruntime โหลดไม่ขึ้น **หยุดแล้วบอกผม** อย่าทดสอบต่อ

### ขั้น 2 — เปิด log แล้วพูด

```powershell
adb logcat -c
adb logcat -s KioskVoice:I
```

แล้วพูด **"Hey Jarvis"** ห่างจากเครื่องประมาณ 1 เมตรก่อน ควรเห็น:

```
wake word detected score=0.812 threshold=0.50
capture started
capture finished reason=end-of-speech bytes=...
stt ok ... chat ok ... tts ok ...
turn finished outcome=COMPLETED
```

log มีแต่ **คะแนนกับจำนวนไบต์ ไม่มีเสียงดิบ ไม่มีข้อความเต็ม ไม่มี token**

### ขั้น 3 — ดูคะแนนสดตอนพูด

```powershell
while ($true) {
  adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
    Select-String "score|detections"
  Start-Sleep -Milliseconds 700
}
```

พูด "Hey Jarvis" แล้วดูว่า `score` กระโดดขึ้นแค่ไหน ถ้าขึ้นไปแค่ 0.3 แต่ threshold
เป็น 0.5 แปลว่าต้องลด threshold ไม่ใช่ว่าโมเดลพัง

### ขั้น 4 — ปรับ threshold โดยไม่ต้อง build ใหม่

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_SET_THRESHOLD `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es value 0.4
adb logcat -s KioskStats:I -d | Select-Object -Last 3
```

ต้องเห็น `wake threshold now 0.4` · ค่าถูกบีบอยู่ในช่วง 0.01–0.999
และ **กลับเป็น 0.40 (ค่าเริ่มต้นใหม่) เมื่อรีสตาร์ต service** (ตั้งใจ: ค่าที่ปรับระหว่างวัดต้องไม่
กลายเป็นค่าถาวรโดยไม่มีใครตัดสินใจ)

### ขั้น 5 — โหมดทดสอบคำปลุกอย่างเดียว แล้ววัด 20 ครั้งที่ 3 เมตร

**เปิดโหมดนี้ก่อนวัดเสมอ** ในโหมดนี้เมื่อได้ยิน "Hey Jarvis" เครื่องจะ
**นับ + โชว์คะแนนบนจอ + ส่งเสียงบี๊บ** แล้วจบ — **ไม่อัดคำถาม ไม่เรียก STT
ไม่เรียก chat ไม่เรียก TTS** วัด 20 ครั้งจึงใช้เวลาไม่กี่นาทีและ**ไม่เสียเงินเลย**

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_WAKE_ONLY `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es value on
adb logcat -s KioskStats:I -d | Select-Object -Last 2
```
ต้องเห็น `wake-only mode ON` และบนจอเครื่องจะขึ้น `โหมดทดสอบคำปลุก`

ยืนยันจาก dumpsys:
```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "wake-only|detections"
```
ต้องเห็น `wake-only  : ON — no STT, chat or TTS`

**ขั้นตอนวัด 20 ครั้ง:**

1. รีเซ็ตตัวนับ
   ```powershell
   adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_RESET_STATS `
     -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver
   ```
2. ยืนห่างจากเครื่อง **3 เมตร** (วัดจริง อย่ากะ) ในสภาพห้องที่จะใช้งานจริง
   ถ้าปกติเปิดทีวี **ก็เปิดทีวี** — การวัดในห้องเงียบให้ตัวเลขที่ใช้ไม่ได้
3. พูด **"Hey Jarvis"** ด้วยเสียงปกติ **20 ครั้ง** เว้นแต่ละครั้ง **≥3 วินาที**
   (cooldown 2 วินาที ถ้าพูดถี่กว่านั้นจะนับไม่ครบเพราะกันนับซ้ำ)
   **นับเองด้วยว่าพูดไปกี่ครั้ง** — ตัวเลขบนจอคือครั้งที่เครื่องได้ยิน
   ได้ยินเมื่อไรจะมี **เสียงบี๊บ** ทันที ไม่ต้องรอดูจอ
4. อ่านผล
   ```powershell
   adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
     Select-String "detections|score|threshold"
   ```

**เกณฑ์: `detections` ≥ 18 จาก 20 ครั้ง (90%)**

| ผลที่ได้ | แปลว่า | ทำอะไรต่อ |
|---|---|---|
| ≥18 | ผ่านเกณฑ์ที่ threshold นี้ | ไปขั้น 6 วัดปลุกผิด |
| 12–17 | ใกล้แล้ว | ลด threshold ทีละ 0.05 วัดใหม่ |
| <12 | ไกล | ดูคะแนนในขั้น 3 ก่อน ถ้าคะแนนสูงสุดยังต่ำกว่า 0.3 แปลว่าไมค์หรือระยะมีปัญหา ไม่ใช่ threshold |

**ปิดโหมดเมื่อวัดเสร็จ** ไม่งั้นจาร์วิสจะไม่ตอบอะไรเลย:
```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_WAKE_ONLY `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es value off
```

🔴 **ห้ามเปลี่ยนค่า threshold เริ่มต้น (0.50) ในโค้ดจนกว่าจะมีผลวัดแล้วอนุมัติ**
ค่าที่ตั้งผ่าน adb เป็นค่าชั่วคราว หายเมื่อรีสตาร์ต service ซึ่งตั้งใจให้เป็นแบบนั้น

### ขั้น 5ก — วัดความแม่นแบบเต็มรอบ (ถ้าต้องการ)

ถ้าอยากวัดทั้งวงรวม STT/chat/TTS ให้ปิดโหมดทดสอบก่อน แล้วพูด "Hey Jarvis"
**20 ครั้ง** ห่าง 3 เมตร เว้นแต่ละครั้ง ~5 วินาที

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_RESET_STATS `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver
# ... พูด 20 ครั้ง ...
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_STATS `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver
adb logcat -s KioskStats:I -d
```

**เกณฑ์: ปลุกติด ≥18 จาก 20 ครั้ง (90%)** · ถ้าไม่ถึง ลด threshold ทีละ 0.05
แล้ววัดใหม่ **แล้วต้องกลับไปวัดปลุกผิดใหม่ด้วย** — ลด threshold คือแลกกันตรงๆ

### ขั้น 6 — วัดปลุกผิด 8 ชั่วโมง

รีเซ็ตตัวนับ แล้วปล่อยไว้ 8 ชั่วโมงในห้องที่ใช้งานปกติ (มีทีวี มีคนคุย)
**ห้ามพูด "Hey Jarvis"** ระหว่างนั้น

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_RESET_STATS `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver
```

ครบ 8 ชั่วโมงแล้วอ่านด้วยคำสั่ง `TEST_STATS` ข้างบน
**เกณฑ์: ปลุกผิด ≤1 ครั้ง** · ถ้าเกิน เพิ่ม threshold ทีละ 0.05 แล้ววัดขั้น 5 ใหม่

ตัวนับอยู่รอดการรีบูต ถ้าเครื่องรีสตาร์ตกลางทางก็อ่านต่อได้

### ขั้น 7 — เครื่องต้องไม่ปลุกตัวเอง

อันนี้สำคัญ เพราะคำตอบของจาร์วิสออกลำโพงเข้าไมค์ตัวเอง

```powershell
adb logcat -c
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_LISTEN `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver
```

ถามอะไรก็ได้ที่ทำให้ตอบยาวๆ เช่น "เล่าเรื่องแมวให้ฟังหน่อย" แล้วดู log:

```powershell
adb logcat -s KioskVoice:I -d | Select-String "wake word detected|listening again"
```

**ต้องเห็น `listening again after speaking` และต้องไม่เห็น `wake word detected`
ระหว่างที่กำลังพูดตอบ** ถ้าเห็น แปลว่าการปิดหูไม่ทำงาน — บอกผม

ดูค่าถอยหลังได้ระหว่างเล่นเสียง:
```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "deaf-for-ms"
```

### ขั้น 8 — พูดซ้ำๆ ต้องปลุกครั้งเดียว

พูด "Hey Jarvis Hey Jarvis Hey Jarvis" รัวๆ ติดกัน — `detections` ควรเพิ่ม
**1 ครั้ง ไม่ใช่ 3** เพราะมี cooldown 25 เฟรม (2 วินาที)

## 🔴 บั๊กที่แก้ใน versionCode 8 — ตรวจว่าหายจริง

### อาการเดิม

พูด "Hey Jarvis" ครั้งเดียวไม่ได้ถามอะไร แล้วจาร์วิสตอบหลายรอบ

สาเหตุจาก log ของ A07: `capture finished` แล้ว machine กลับไป LISTENING ทันที
**ทั้งที่ยังทำ STT/chat/TTS อยู่** คำปลุกจึงยิงซ้ำระหว่างนั้นได้ แล้ว capture
รอบใหม่**อัดเสียงคำตอบของจาร์วิสเอง** ส่งไปถอดเสียงแล้วเอาไปถามโมเดลต่อ

และ "เสียงพูด" เดิมเป็นค่าคงที่ 2000 ทีวีจึงดันให้ทุก capture มีเสียงตลอด
บางครั้งอัดยาวจนชน timeout 12 วินาที แล้วเอาเสียงห้องไปถามโมเดล

### ตรวจข้อ 1 — ห้ามมี turn ซ้อน

```powershell
adb logcat -c
```
พูด **"Hey Jarvis"** แล้วถามอะไรสักอย่าง จากนั้น **พูด "Hey Jarvis" ซ้ำระหว่างที่
จาร์วิสกำลังตอบ**

```powershell
adb logcat -s KioskVoice:I -d | Select-String "wake word detected|capture started|turn finished"
```

**ต้องเห็น `capture started` เพียงครั้งเดียว** และต้องไม่เห็น `wake word detected`
ระหว่างช่วงที่ยังไม่ถึง `turn finished`

ดูสถานะสดระหว่างนั้น — ต้องเป็น `BUSY`:
```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "capture-mode"
```

### ตรวจข้อ 2 — เรียกแล้วเงียบ ต้องไม่เสียเงิน

พูด **"Hey Jarvis"** แล้ว **เงียบไปเลย** ไม่ต้องถามอะไร

```powershell
adb logcat -s KioskVoice:I -d | Select-String "capture cancelled|stt ok|chat ok"
```

**ต้องเห็น** `capture cancelled reason=no-speech-after-wake threshold=... ambient=...`
**และต้องไม่เห็น `stt ok` หรือ `chat ok` เลย** บนจอจะขึ้น `ไม่ได้ยินคำถามครับ`

ถ้ายังเห็น `stt ok` แปลว่าเสียงในห้องดังพอจะผ่านเกณฑ์ — ดู `threshold` กับ
`ambient` ในบรรทัด cancelled ว่าห่างกันพอไหม แล้วบอกผม

### ตรวจข้อ 3 — ทีวีต้องไม่ทำให้อัดยาว

เปิดทีวีเสียงปกติ พูด "Hey Jarvis" แล้วเงียบ

```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "speech-floor|stop-reason"
```

`speech-floor` ต้อง**สูงกว่า**ระดับเสียงทีวี (ดู `ambient` ในวงเล็บ) และ
`stop-reason` ต้องเป็น `no-speech-after-wake` **ไม่ใช่ `silence-timeout` หรือ
`max-length`**

## เฟส 4 — พูดให้เปิดแผนที่

### 🔴 ขั้น M-1 — บั๊กที่แก้ใน versionCode 10 (อ่านก่อนถ้ามาจาก vc9)

**อาการ:** `maps : not-installed` ทั้งที่ `pm list packages` เห็น Maps และ Maps
รันอยู่จริง

**สาเหตุ ✅ ยืนยันจาก manifest:** ตั้งแต่ Android 11 แอปที่ target API 30+
**มองไม่เห็นแพ็กเกจอื่น** เว้นแต่ประกาศไว้ใน `<queries>` — และ manifest ของเรา
**ไม่มี `<queries>` เลย** `getPackageInfo` จึงโยน NameNotFound เหมือนกับว่าไม่ได้
ติดตั้ง และที่ร้ายกว่าคือ **`startActivity` ที่ `setPackage()` ก็ resolve ไม่ได้
ด้วยเหตุผลเดียวกัน** — ต่อให้ข้ามการเช็คไป Maps ก็เปิดไม่ขึ้นอยู่ดี

**แก้แล้วใน vc10** ประกาศแพ็กเกจเดียว ไม่ใช้ `QUERY_ALL_PACKAGES`

ตรวจว่า APK ที่ลงมีจริง (ทำบนเครื่อง Windows กับไฟล์ APK):
```powershell
$aapt = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\aapt2.exe" | Select-Object -Last 1).FullName
& $aapt dump xmltree app-debug.apk --file AndroidManifest.xml | Select-String -Context 0,2 "E: queries"
```
ต้องเห็น `E: queries` แล้วตามด้วย `com.google.android.apps.maps`

ถ้าไม่มี aapt2 ให้ดูจากในเครื่องแทน — vc10 เช็คใหม่ทุกครั้งก่อนเปิดแผนที่อยู่แล้ว
ดังนั้นแค่ดู `maps` ใน dumpsys ก็พอ

### ขั้น M0 — ตรวจว่ามี Google Maps และได้สิทธิ์ตำแหน่ง

```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "maps"
```

ต้องเห็นสองบรรทัด:
```
  maps       : ready
  maps-package : com.google.android.apps.maps installed=true
```

🔴 ถ้า `installed=false` ทั้งที่ `pm list packages` เห็น Maps — นั่นคือบั๊ก
package visibility ของ vc9 **ให้ตรวจว่าลง vc10 แล้วจริงหรือยัง** (ขั้น 0)

**ไม่ต้องรีสตาร์ตแอปหลังติดตั้ง Maps แล้ว** vc10 ถามใหม่ทุกครั้งก่อนเปิดแผนที่
ถ้าติดตั้ง Maps ระหว่างที่ kiosk ทำงานอยู่ ครั้งถัดไปที่สั่งก็ใช้ได้เลย
(ในเครื่องคุณ Maps ถูก `cmd package install-existing` ให้ user 0 **หลัง** service
เริ่มไปแล้ว ซึ่งเป็นเหตุผลที่ข้อนี้ถูกแก้ไปด้วย)

ดู log ตอนสั่งจริง จะเห็นผลการตรวจสดๆ:
```powershell
adb logcat -s KioskVoice:I -d | Select-String "maps readiness"
```

| ค่า `maps` | แปลว่า | ทำอะไรต่อ |
|---|---|---|
| `ready` | Device Owner ให้สิทธิ์ตำแหน่งสำเร็จ ไม่มี dialog | ไปต่อได้ |
| `not-installed` | เครื่องไม่มี Maps | ติดตั้ง Maps ก่อน แล้วเปิดแอป kiosk ใหม่ |
| `location-denied` | ให้สิทธิ์ไม่ผ่าน | **หยุด ส่ง output มาให้ผม** |
| `granted-unconfirmed` | สั่งให้แล้วแต่อ่านกลับมายืนยันไม่ได้ | **หยุด ส่ง output มาให้ผม** |
| `installed-not-owner` | มี Maps แต่แอปไม่ได้เป็น Device Owner | Maps จะถามสิทธิ์เอง ซึ่งใน lock task กดไม่ได้ — บอกผม |
| `installed-no-dpm` | อ่าน DevicePolicyManager ไม่ได้ | **หยุด ส่ง output มาให้ผม** |

🔴 **สิทธิ์ ≠ ระบบระบุตำแหน่งเปิดอยู่** Device Owner ให้ "สิทธิ์" ได้ แต่**เปิด
Location Services ของเครื่องให้ไม่ได้ และล็อกอิน Google ให้ไม่ได้** ถ้า Maps
เปิดแล้วบอกว่าไม่รู้ตำแหน่ง ให้ไปเปิด Location ในตั้งค่าเครื่องเอง แล้วบอกผมว่า
ต้องทำ — จะได้บันทึกไว้

ตรวจว่า lock task อนุญาต Maps แล้ว:
```powershell
adb shell dumpsys device_policy | Select-String -Context 0,3 "Lock task packages"
```
ต้องเห็นทั้ง `com.mammonrn.phoneaikiosk.debug` และ `com.google.android.apps.maps`

### ขั้น M1 — สั่งด้วยเสียง

```powershell
adb logcat -c
adb logcat -s KioskVoice:I
```

พูด **"Hey Jarvis"** รอเสียงบี๊บ แล้วพูด **"พาไปเซ็นทรัลเชียงราย"**

ควรเห็น:
```
wake word detected score=...
capture started
capture finished reason=end-of-speech
stt ok ... chat ok ... action=open_maps
tts ok ...
action open_maps result=OPENED destination_chars=16
turn finished outcome=COMPLETED
```

**จาร์วิสต้องพูดยืนยันก่อน แล้ว Maps ค่อยเปิด** ไม่ใช่สลับกัน

🔴 `destination_chars=16` คือ**จำนวนตัวอักษร ไม่ใช่ปลายทาง** — ปลายทางที่คุณจะไป
ไม่ถูกบันทึกลง log และไม่ขึ้นบนจอ ตั้งใจให้เป็นแบบนั้น

### ขั้น M2 — สั่งสิ่งที่ห้าม ต้องถูกปฏิเสธ

ลองพูดทีละอัน:

| พูดว่า | ต้องได้ |
|---|---|
| "โทรหาแม่หน่อย" | ปฏิเสธด้วยเสียง **ไม่มี** `action=` ใน log |
| "ส่งข้อความหาพ่อ" | ปฏิเสธ |
| "เปิดเว็บ google" | ปฏิเสธ |
| "เปิดยูทูป" | ปฏิเสธ |

```powershell
adb logcat -s KioskVoice:I -d | Select-String "action="
```
ทุกบรรทัดต้องเป็น `action=none` **ถ้าเห็น `action=open_maps` จากคำสั่งพวกนี้ ให้หยุดแล้วบอกผม**

ฝั่ง broker ก็มี log ของตัวเอง ถ้าโมเดลพยายามส่ง action แปลกๆ:
```bash
sudo journalctl -u kiosk-broker -n 100 | grep "dropped action"
```

### ขั้น M3 — กลับหน้า kiosk

หลัง Maps เปิดแล้ว ลองสามทางตามลำดับ:

1. **กดปุ่ม Back** — ควรกลับหน้า kiosk
2. **พูด "Hey Jarvis"** — kiosk ควรกลับมาหน้าจอเอง
   ```powershell
   adb logcat -s KioskVoice:I -d | Select-String "returning to the kiosk|could not return"
   ```
   ❓ ข้อนี้ผมไม่แน่ใจว่าจะทำงาน Android จำกัดการเปิดหน้าจอจากเบื้องหลัง
   ถ้าเห็น `could not return to the kiosk screen: ...` **นั่นคือคำตอบ ส่งมาให้ผม**
3. **adb** — ทางที่ไม่พึ่งข้อ 1 หรือ 2
   ```powershell
   adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_HOME `
     -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver
   ```

**บอกผมว่าข้อไหนใช้ได้บ้าง** ถ้า Back ใช้ได้ก็พอแล้ว ที่เหลือเป็นของแถม

### ขั้น M4 — ไมค์ยังทำงานตอน Maps อยู่หน้าจอไหม ❓

**นี่คือข้อที่ผมตอบล่วงหน้าไม่ได้** และเป็นข้อที่สำคัญที่สุดในเฟสนี้

🔶 เหตุผลที่**น่าจะ**ทำงาน: ตั้งแต่ Android 11 แอปจะอัดเสียงตอนไม่ได้อยู่หน้าจอได้
ก็ต่อเมื่อมี foreground service ชนิด `microphone` ทำงานอยู่ ซึ่งเรามี และมันไม่ได้
ถูกหยุดตอน Maps ขึ้นมา ❓ แต่ Samsung มีการจัดการพลังงานของตัวเอง และ Android 14+
เข้มเรื่อง FGS มากขึ้น — **ต้องวัดบนเครื่องจริงเท่านั้น**

วิธีวัด: เปิด Maps ทิ้งไว้ แล้วดูว่า `level` ขยับตามเสียงไหม

```powershell
# เปิด Maps ก่อน (ผ่านขั้น M1) แล้วรันอันนี้ พร้อมตบมือดังๆ ใกล้เครื่อง
while ($true) {
  adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
    Select-String "score|level|mic "
  Start-Sleep -Milliseconds 500
}
```

| ผล | แปลว่า |
|---|---|
| `level` ขยับตามเสียง | ✅ ไมค์ทำงาน — ลองพูด "Hey Jarvis" ได้เลย |
| `level` ค้างที่ 0 หรือใกล้ 0 ตลอด | 🔴 Android ปิดไมค์ให้ **ส่ง output มา ผมจะหาทางแก้** |
| `mic` เป็น `error` | service โดนหยุด — ส่ง `adb logcat -s KioskVoice:I -d` มาด้วย |

## 🔴 บั๊กที่แก้ใน versionCode 11 — เรียกแล้วถามทันทีแต่ถูกยกเลิก

**สาเหตุ ✅ ยืนยันจากโค้ดและเลขใน log ของคุณ:** `9742 × 2.5 = 24355` ตรงเป๊ะ
แปลว่าเลขคณิตถูก แต่ **ambient = 9742 เองคือความผิด** — นั่นคือ ~30% ของสเกลเต็ม
ซึ่งเป็นระดับ**คนพูดติดไมค์ ไม่ใช่เสียงห้อง**

โค้ดเดิมอัปเดต ambient ทุกเฟรมที่ฟังอยู่ **รวมเฟรมที่มีเสียง "Hey Jarvis" เอง**
และคำปลุกถูกตรวจพบ**ตอนจบประโยค** ค่า ambient จึงถูกดันขึ้นไปเท่าความดังของคำปลุก
ก่อนจะถูกใช้ตั้งเกณฑ์ → **คำปลุกยิ่งดังชัด เกณฑ์ยิ่งสูง จนคำถามปกติผ่านไม่ได้**
(0.958 → ambient 9742, 0.502 → ambient 8501 สอดคล้องกัน)

**แก้แล้ว:** วัดห้องจากเฟรมที่อยู่**ก่อน**คำปลุก (ข้ามย้อนหลัง ~2 วินาที) ใช้
**median** ไม่ใช่ค่าเฉลี่ย (ประตูกระแทกทีเดียวไม่ขยับ median) · กันเสียงบี๊บ
300 ms แรก · ขยายเวลารอเป็น 3.5 วินาที นับจาก**หลังบี๊บจบ**

### ตรวจว่าหายจริง

```powershell
adb logcat -c
```
พูด **"Hey Jarvis"** รอบี๊บ แล้วถาม **"พาไปเซ็นทรัลเชียงราย"**

```powershell
adb logcat -s KioskVoice:I -d | Select-String "capture started|capture cancelled|stt ok"
```

ต้องเห็น `capture started ambient=... threshold=...` โดย **ambient ควรเป็นหลักร้อย
ไม่ใช่หลักพันปลายๆ** และต้อง**ไม่เห็น** `capture cancelled`

ถ้ายังถูกยกเลิก บรรทัด cancelled มีทุกอย่างที่ต้องใช้วินิจฉัย:
```
capture cancelled reason=no-speech-after-wake ambient=250 threshold=2000 peak_while_waiting=1800 margin=2.50 wait_ms=3500
```

| อ่านยังไง | แปลว่า | แก้ยังไง |
|---|---|---|
| `peak_while_waiting` ใกล้ `threshold` | **มีคนพูด แต่เบาไปนิดเดียว** | ลด margin (ขั้นถัดไป) |
| `peak_while_waiting` ใกล้ `ambient` | ไม่มีใครพูดจริง | ปกติ ไม่ต้องแก้ |
| `ambient` สูงผิดปกติ (>3000) | ห้องดังจริง หรือยังมีเสียงปนเข้ามา | ส่ง log มาให้ผม |

### ปรับ margin และเวลารอชั่วคราว

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_SET_MARGIN `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver --es value 1.8
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_SET_WAIT `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver --es value 5000
adb logcat -s KioskStats:I -d | Select-Object -Last 2
```
ค่าถูกบีบอยู่ในช่วง margin 1.2–10 และ wait 500–10000 ms
**ทั้งคู่กลับเป็นค่าเริ่มต้นเมื่อ service restart** ตั้งใจให้เป็นแบบนั้น

ยืนยันจาก dumpsys:
```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "speech-floor|wake-tuning|peak-waiting"
```

### ตรวจว่าเรียกแล้วเงียบยังถูกยกเลิกอยู่ (ต้องไม่เสียเงิน)

พูด "Hey Jarvis" แล้วเงียบเลย → ต้องเห็น `capture cancelled` และ**ต้องไม่เห็น `stt ok`**

## ตัวช่วยเสียงของไมค์ — ทดลองบน A07

🔴 **ทั้งสามตัวปิดเป็นค่าเริ่มต้น และจะไม่เปลี่ยนจนกว่าคุณจะวัดแล้วอนุมัติ**
สวิตช์ทั้งหมดหายเมื่อ service restart

### ขั้น A0 — ดูว่าเครื่องรองรับตัวไหนบ้าง

```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String -Context 0,6 "microphone"
```

ควรเห็น:
```
microphone
  source       : voice_recognition  (requested voice_recognition)
  asked for    : echo=off noise=off gain=off
  echo-canceler: not available on this device
  noise-suppressor: off
  auto-gain: off
  (all adb switches reset when the service restarts)
```

**`not available on this device` คือคำตอบที่มีความหมาย** — ถ้า A07 ไม่มีตัวไหน
ก็ไม่ต้องเสียเวลาทดสอบตัวนั้น ส่ง output นี้มาให้ผมด้วย

### ขั้น A1 — Echo Canceler ก่อน (ตัวที่มีเหตุผลชัดที่สุด)

kiosk เล่นเสียงตอบของตัวเองเข้าไมค์ตัวเอง echo canceler จึงเป็นตัวที่**มีเหตุผล
ทางทฤษฎีชัดที่สุด**ว่าจะช่วย

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_AUDIO_EFFECT `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es name echo --es value on
adb logcat -s KioskStats:I -d | Select-Object -Last 1
```
ต้องเห็น `audio effects now echo=on noise=off gain=off`

ยืนยันว่า**ติดจริง** ไม่ใช่แค่ขอไว้:
```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "echo-canceler"
```
ต้องเป็น `echo-canceler: ON` — ถ้าเป็น `off` ทั้งที่ available แปลว่าสร้างไม่สำเร็จ **บอกผม**

แล้ววัดสามอย่าง **ทีละอย่าง อย่าเปลี่ยนสองตัวพร้อมกัน**:

| วัดอะไร | ทำยังไง | ดูที่ไหน |
|---|---|---|
| คำปลุกยังติดไหม | เปิดโหมด wake-only พูด 10 ครั้ง | `detections` |
| ปลุกตัวเองไหม | ถามอะไรยาวๆ ฟังคำตอบจนจบ | ไม่ควรมี `wake word detected` ระหว่างพูด |
| คำถามยังผ่านไหม | ถามปกติ 5 ครั้ง | ไม่ควรมี `capture cancelled` |

**จดตัวเลขไว้เทียบกับตอนปิด** ถ้าไม่ดีขึ้นให้ปิดกลับ:
```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_AUDIO_EFFECT `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es name echo --es value off
```

### ขั้น A2 — Noise Suppressor (ทำหลัง A1 เสร็จแล้วเท่านั้น)

🔶 **ตัวนี้อาจทำให้แย่ลง** openWakeWord เทรนด้วยเสียงที่**ไม่ได้ผ่าน** noise
suppression การลดเสียงรบกวนที่ฟังดีขึ้นสำหรับหู อาจทำให้คะแนนคำปลุกตกก็ได้
ต้องวัด ไม่ใช่เดา

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_AUDIO_EFFECT `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es name noise --es value on
```
แล้ววัดสามอย่างเดิม **เทียบกับตัวเลขตอนปิด** โดยเฉพาะ **คะแนนคำปลุก** —
ถ้า score ตกลงชัดเจน ปิดทิ้งเลย

### ขั้น A3 — Auto Gain (ทางเลือก คาดว่าจะแย่ลง)

🔶 auto-gain ดันเสียงห้องเงียบให้ดังเท่าเสียงคน ซึ่งเป็น**สิ่งตรงข้าม**กับที่
ขั้นตอนแยกเสียงพูดต้องการ ลองได้แต่ผมคาดว่าจะทำให้ `capture cancelled` บ่อยขึ้น

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_AUDIO_EFFECT `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es name gain --es value on
```

### ขั้น A4 — สลับ audio source

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_AUDIO_SOURCE `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es name voice_communication
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "source"
```
ต้องเห็น `source : voice_communication (requested voice_communication)`

🔶 `voice_communication` คือเส้นทางโทรศัพท์ มักมาพร้อม echo cancel + ลดเสียงรบกวน
แบบหนักไม่ว่าจะขอหรือไม่ — อาจดีมากสำหรับ kiosk ที่คุยกับตัวเอง หรืออาจประมวลผล
หนักจนคำปลุกจำไม่ได้ **วัดคะแนนคำปลุกด้วยโหมด wake-only เป็นอย่างแรก**

กลับค่าเดิม:
```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_AUDIO_SOURCE `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es name voice_recognition
```

✅ **การสลับทุกแบบไม่เปิด AudioRecord ซ้อน** — คำสั่ง adb แค่ตั้งธง เธรดที่ถือไมค์
เป็นคนปิดตัวเก่าแล้วเปิดตัวใหม่ทีละอัน มีเทสต์นับจำนวนที่เปิดพร้อมกันยืนยัน
(`switching the source never leaves two microphones open`)

## 🔴 versionCode 12 — ทำไมต้องพูดสองครั้ง และทำไมช้า

### สาเหตุของ "ต้องพูดสองครั้ง" ✅ คำนวณจากโค้ดได้ตรงๆ

`detector.reset()` ถูกเรียกทุกครั้งที่กลับมาฟัง และ reset **ล้าง feature buffer ทิ้ง**

classifier อ่าน **16 embedding ย้อนหลัง** และได้ 1 embedding ต่อ 80 ms
→ หลัง reset ต้องรอ **16 × 80 = 1,280 ms ถึงจะมีคะแนนออกมาเลยสักค่า**
ไม่ใช่คะแนนต่ำ แต่**ไม่มีคะแนนเลย** บวก settle 700 ms = **เงียบสนิทราว 2 วินาที
หลังทุกครั้งที่ใช้งาน** พูดตอนนั้นคือพูดใส่เครื่องที่ยังไม่ตื่น

**แก้:** ป้อน**ความเงียบ**ให้ detector แทนการไม่ป้อนอะไรเลย บัฟเฟอร์จึงเต็มและ
เดินหน้าตลอด โดย**เสียงของจาร์วิสเองไม่เคยเข้าไป** ซึ่งเป็นเหตุผลเดียวที่เคยไม่ป้อน
— ไม่ต้อง reset อีกต่อไป

### threshold เป็น 0.40 ถาวรแล้ว

```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "threshold|detector-warm"
```
ต้องเห็น `(threshold 0.40)` **หลังรีสตาร์ต service ด้วย** (ไม่ใช่ค่าที่ตั้งผ่าน adb ค้างอยู่):
```powershell
adb shell am force-stop com.mammonrn.phoneaikiosk.debug
adb shell am start -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.MainActivity
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "threshold"
```

และต้องเห็น `detector-warm: true (features 16/16, ...)` ตอนอยู่เฉยๆ
**ถ้าเป็น false ตอนไม่ได้ทำอะไร แปลว่ามีอะไรยัง reset อยู่ — บอกผม**

### ตรวจว่าพูดครั้งเดียวติด

```powershell
adb logcat -c
```
ถาม-ตอบให้จบหนึ่งรอบ **แล้วพูด "Hey Jarvis" อีกครั้งทันทีหลังจาร์วิสพูดจบ**

```powershell
adb logcat -s KioskVoice:I -d | Select-String "listening again|wake word detected|near miss"
```
`listening again (mode=LISTENING features=16)` — **features ต้องเป็น 16 ไม่ใช่ 0**

### log near-miss — ตัวที่บอกว่าควรลด threshold อีกไหม

```powershell
adb logcat -s KioskVoice:I -d | Select-String "near miss"
```
```
wake near miss score=0.312 threshold=0.40 warm=true features=16 chunks=812
```

| อ่านยังไง | แปลว่า |
|---|---|
| near miss เยอะ score 0.30–0.39 | ยังลด threshold ได้อีก **แต่ดูตัวนับ false-wake ก่อน** |
| ไม่มี near miss เลยตอนที่พูดแล้วไม่ติด | detector ไม่ได้ยินเลย ไม่ใช่เรื่อง threshold — ดู `warm` |
| `warm=false` | บัฟเฟอร์ยังไม่เต็ม **นั่นคือบั๊กที่เพิ่งแก้ ถ้ายังเจอให้บอกผม** |

บรรทัดตอนปลุกติดมีเวลาด้วย:
```
wake word detected score=0.726 threshold=0.40 best_before=0.726 climb_ms=180 warm=true
beep 12 ms after the best score
```

### ตัวนับ false-wake candidate

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_STATS `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver
adb logcat -s KioskStats:I -d | Select-String "woke, nobody spoke|near misses|detections"
```
```
  detections         : 14
  woke, nobody spoke : 2   <- false-wake candidates
  near misses        : 31  <- heard, scored under the threshold
```

**`woke, nobody spoke` คือหลักฐานว่า 0.40 ต่ำไปหรือยัง** — ปลุกติดแล้วไม่มีใครถาม
แปลว่าทีวีหรือเสียงห้องผ่านเกณฑ์ **ถ้าเลขนี้ไต่ขึ้นตอนไม่มีคนอยู่ในห้อง 0.40 ต่ำไป**
นับเฉพาะที่ปลุกด้วยเสียงจริง ไม่นับที่สั่งผ่าน adb

### ลด delay หลังพูดจบ

เวลารอความเงียบ **ลดจาก 1,200 → 900 ms** = เร็วขึ้น 300 ms ทุกรอบ และเป็นส่วนเดียว
ในความหน่วงที่**ไม่ได้ทำอะไรเลย** (ที่เหลือเป็น STT/chat/TTS ซึ่งอยู่ที่ผู้ให้บริการ)

ดูเวลารวมจากตอนพูดจบ:
```powershell
adb logcat -s KioskVoice:I -d | Select-String "capture finished|turn finished"
```
```
capture finished reason=end-of-speech bytes=... silence_ms=900
turn finished outcome=COMPLETED since_capture_end_ms=3980
```
`since_capture_end_ms` คือเวลาที่คุณรอจริงตั้งแต่หยุดพูด

ลองสั้นกว่านี้ได้ (บีบอยู่ในช่วง 300–3000 ms):
```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_SET_SILENCE `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver --es value 700
```
🔶 **ต่ำกว่า 700 ms เริ่มเสี่ยงตัดคนที่เว้นจังหวะกลางประโยค** ถ้าลดแล้วเจอคำถามขาดกลาง
ให้เพิ่มกลับ ค่านี้หายเมื่อ restart

## เฟส 5 — หน้าหลักธีม Windows 95

หน้าจอใหม่มี 5 หน้าต่าง: นาฬิกา · อากาศ · ราคาทอง · คริปโต · จาร์วิส
(ตั้งแต่ v0.20.0 หน้าต่างนาฬิกาบนสุดกลายเป็นเมนู Google Home ที่ยังไม่เปิดใช้
และเวลาย้ายไปอยู่มุมขวาล่างแบบ AM/PM อย่างเดียว)
และมี taskbar ข้างล่าง มุมขวาล่างยังเป็นทางออก (แตะ 10 ครั้ง) เหมือนเดิม

**ข้อมูลทุกตัววิ่งผ่าน broker** มือถือไม่ได้ต่อตรงไปที่แหล่งไหนเลย และไม่มี
key ของแหล่งข้อมูลอยู่ในเครื่อง — ทั้งสามแหล่งไม่ต้องใช้ key อยู่แล้ว

### ขั้น D0 — ทดสอบฝั่ง broker ก่อน (ทำจากเครื่องไหนก็ได้ที่มี token)

```powershell
curl.exe -s -H "Authorization: Bearer $env:KIOSK_TOKEN" "https://<โดเมน>/v1/dashboard?lat=20.05&lon=99.89"
```

✅ ต้องได้ JSON ที่มีครบสามก้อน `weather` `gold` `crypto` แต่ละก้อนมี `ok` กับ
`age_seconds` ดูว่า `ok` เป็น `true` ทั้งสามไหม ถ้าตัวไหนเป็น `false` ให้ดู
`error` — เป็น**ชื่อชนิดข้อผิดพลาด**เท่านั้น เช่น `OSError` ไม่ใช่ URL

✅ `place` ต้องเป็นชื่อสั้นๆ เช่น `"เชียงราย"` และ `location_fallback` ต้องเป็น
`false` เพราะส่งพิกัดไปให้แล้ว

ยิงซ้ำรอบที่สองภายในหนึ่งนาที `age_seconds` ของ `crypto` ต้องเพิ่มขึ้น ไม่ใช่
เป็น 0 อีกครั้ง — นั่นคือหลักฐานว่า cache ทำงานและเราไม่ได้ยิงออกนอกทุกครั้ง

ยิงแบบ**ไม่ใส่พิกัด**ดูด้วย:

```powershell
curl.exe -s -H "Authorization: Bearer $env:KIOSK_TOKEN" https://<โดเมน>/v1/dashboard
```

✅ ต้องยังได้อากาศครบ และ `location_fallback` เป็น `true` — นี่คือทางที่
โค้ดเดินเวลามือถือไม่มีสิทธิ์ตำแหน่ง, Location ปิด หรือหาพิกัดไม่ได้

### ขั้น D0b — พิกัดต้องไม่หลุดไปอยู่ใน log ที่ไหนเลย

ยิงด้วยพิกัดที่จำง่ายและไม่ใช่ของจริง แล้วไล่หาทุก log:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "https://<โดเมน>/v1/dashboard?lat=13.75&lon=100.50" > /dev/null

sudo journalctl -u kiosk-broker -n 200 | grep -E "13\.75|100\.50"
sudo grep -E "13\.75|100\.50" /var/log/nginx/kiosk-timing.log
```

✅ ทั้งสองคำสั่งต้อง**ไม่เจออะไรเลย** ถ้าเจอแปลว่ามีที่ไหนสักแห่งเริ่ม log
query string — ฝั่ง nginx ให้ดูว่า `log_format kiosk_timing` ยังใช้ `$uri`
อยู่ไหม (ไม่ใช่ `$request` หรือ `$request_uri` ซึ่งพ่วง query string มาด้วย)

✅ ใน journalctl ต้องเห็นบรรทัด `dashboard device=... fallback=False ...`
คือบอกว่า*ใช้พิกัดจากมือถือ* โดยไม่บอกว่าพิกัดอะไร

### ขั้น D1 — ดูหน้าจอจริง

ติดตั้ง APK แล้วเปิดแอป ปล่อยไว้สักหนึ่งนาที

✅ ที่ต้องเห็น
- นาฬิกาเดินทุกวินาที วันที่เป็นภาษาไทย
- อากาศขึ้นอุณหภูมิ + คำอย่าง "แดดรำไร" และความชื้น
- ราคาทองขึ้นทั้งรูปพรรณและทองแท่ง
- คริปโตขึ้น **4 เหรียญ แบ่งสองคอลัมน์** (ซ้าย 2 ขวา 2) พร้อมเปอร์เซ็นต์
  ที่มีเครื่องหมาย + หรือ −
- หัวหน้าต่างอากาศขึ้นชื่อจังหวัดสั้นๆ เช่น `อากาศ · เชียงราย` ถ้าอ่านชื่อ
  ไม่ได้ต้องขึ้น `อากาศ · ตำแหน่งปัจจุบัน` — **ห้ามขึ้นพิกัดเด็ดขาด**
- **กลางคืน** ไอคอนต้องเป็นพระจันทร์ ไม่ใช่ดวงอาทิตย์ และคำต้องไม่มีคำว่า
  "แดด" (ฟ้าโปร่ง / เมฆบางส่วน)
- หัวหน้าต่างทองขึ้น `ราคาทอง · ความบริสุทธิ์ 96.5%` (ต้อง deploy broker
  v0.20 ก่อน broker เก่าไม่ส่งค่านี้ หัวหน้าต่างจะเป็นแค่ `ราคาทอง`)
- ถ้าราคาทองขยับตั้งแต่ broker เริ่มเฝ้า จะมีเปอร์เซ็นต์ที่มีเครื่องหมาย + หรือ −
  ต่อท้ายราคา และมีบรรทัดเล็กสีเทาใต้ราคาว่า `(+/− เทียบครั้งก่อน)` ถ้ายังไม่เคย
  ขยับจะไม่มีเปอร์เซ็นต์เลย — **ไม่ใช่ 0.00%**
- แถบชื่อหน้าต่างจาร์วิสเปลี่ยนตามสถานะจริงตอนพูดคุย
  (พร้อมฟัง → กำลังฟัง → กำลังคิด → กำลังพูด)

หน้าจอรีเฟรชข้อมูลทุก 60 วินาที นาฬิกาเดินเองไม่เกี่ยวกับเน็ต

### ขั้น D2 — 🔴 ข้อสำคัญที่สุด: ตัดเน็ตแล้วจอต้องไม่พัง

```powershell
# ตัดทางออกเน็ตของเครื่อง
adb shell svc wifi disable
adb shell svc data disable
```

รอสองนาที แล้วดูจอ

✅ ที่ถูกต้อง
- **นาฬิกายังเดิน** — ไม่พึ่งเน็ตเลย
- กล่องข้อมูลยังมีตัวเลขเดิมอยู่ พร้อมวงเล็บบอกอายุ เช่น `(7 นาทีก่อน)`
- หรือถ้าไม่เคยโหลดสำเร็จเลย ขึ้น "ข้อมูลไม่พร้อม" เฉพาะกล่องนั้น
- **แอปต้องไม่ดับ ไม่ค้าง ไม่ขึ้นจอขาว**

❌ ถ้าทั้งจอว่างหรือแอปปิดตัวเอง = บั๊ก ให้ส่ง log มา

เปิดเน็ตกลับ:
```powershell
adb shell svc wifi enable
adb shell svc data enable
```

ภายใน 60 วินาที วงเล็บบอกอายุต้องหายไปเอง

### ขั้น D3 — แหล่งเดียวล่ม กล่องอื่นต้องอยู่

อันนี้ทดสอบยากบนเครื่องจริงเพราะบังคับให้ API ล่มไม่ได้ — ฝั่ง broker มีเทสต์
ครอบไว้แล้ว 521 ตัว รวมกรณี "ทองล่ม แต่อากาศกับคริปโตยังมา" และฝั่งแอปมีอีก
16 ตัวที่อ่าน payload แบบล่มบางส่วนโดยตรง

ถ้าอยากเห็นของจริงบนจอ: หยุด broker ชั่วคราวแล้วดูว่าจอยังอยู่ไหม
```powershell
# บน VPS ไม่ใช่บนมือถือ
sudo systemctl stop kiosk-broker
# ...ดูจอ 2 นาที แล้วเปิดกลับ...
sudo systemctl start kiosk-broker
```
✅ นาฬิกาต้องเดินต่อ และกล่องข้อมูลต้องโชว์ค่าเดิมพร้อมอายุ

### ขั้น D4 — ดู log การดึงข้อมูล

```powershell
adb logcat -s KioskDashboard:*
```

✅ ควรเห็นบรรทัดสั้นๆ นาทีละครั้ง: `refresh ok` หรือถ้าล้ม
`refresh failed: SocketTimeoutException` — มีแค่ชื่อชนิดข้อผิดพลาด
**ไม่มีเนื้อข้อมูล ไม่มี URL ไม่มี token**


บน VPS:
```bash
sudo journalctl -u kiosk-broker -n 50 | grep dashboard
```

✅ บรรทัดที่ล้มต้องมีแค่ชื่อชนิดข้อผิดพลาด ห้ามมี URL, key หรือ token โผล่

### ขั้น D5 — เรื่องลิขสิทธิ์ที่ต้องรู้

- ไอคอนสี่ตัวบนจอ (ดวงอาทิตย์ ทอง เหรียญ จาร์วิส) **วาดเองในโปรเจกต์นี้**
  เป็น vector จากสี่เหลี่ยมล้วน ไม่มีไฟล์ภาพและไม่ได้โหลดจากที่ไหน
- ฟอนต์ฝังมาสองตัว ทั้งคู่เป็น SIL Open Font License 1.1 และไฟล์ license
  อยู่ใน `licenses/fonts/`
  - **Press Start 2P** (CodeMan38, Google Fonts) — ตัวเลขและอักษรละติน
  - **IBM Plex Sans Thai Looped** (IBM / Bold Monday, Google Fonts) — ภาษาไทย
  Press Start 2P ไม่มีภาษาไทย การสลับฟอนต์ในบรรทัดเดียวทำใน
  `ui/RetroType.kt` ไม่ได้ปล่อยให้ Android fallback เอง
- **ไม่ได้ใช้ฟอนต์พิกเซลไทย** เพราะตัวที่ใกล้เคียงที่สุด (TA Chai Lai_Pixel)
  แจกฟรีเฉพาะใช้ส่วนตัวไม่เชิงพาณิชย์ และ repo นี้เป็น public — ดู NOTICE
- กรอบหน้าต่างทำจาก layer-list ในโปรเจกต์ ไม่ได้ใช้ asset ของ Microsoft
- อากาศมาจาก Open-Meteo (CC BY 4.0) — เครดิตติดมากับข้อมูลใน payload
- ชื่อสถานที่มาจาก Nominatim / OpenStreetMap (ODbL) — เครดิตติดมากับ payload
  เหมือนกัน และไม่มีข้อมูล OSM ก้อนไหนถูกเก็บลง repo หรือใส่ใน APK
- อันดับ market cap ของคริปโตมาจาก CoinGecko (ถามวันละครั้ง) ราคายังเป็นของ
  Binance เหมือนเดิม

## สรุปคำสั่งตรวจสถานะ

| อยากรู้ | คำสั่ง (PowerShell) |
|---|---|
| เครื่องต่ออยู่ไหม | `adb devices` |
| มีบัญชีผูกอยู่ไหม | `adb shell dumpsys account \| Select-String "Account \{"` |
| ใครเป็น Device Owner | `adb shell dpm list-owners` |
| แอปติดตั้งอยู่ไหม | `adb shell pm list packages \| Select-String phoneaikiosk` |
| ดู log แอป | `adb logcat -s AndroidRuntime:E ActivityManager:I` |
| บรรทัดสถานะบนจอ | `adb shell uiautomator dump /sdcard/ui.xml` แล้ว `adb shell cat /sdcard/ui.xml` |
| แบตถูกแกล้งค้างไว้ไหม | `adb shell dumpsys battery \| Select-String "UPDATES STOPPED"` |
| คืนค่าแบตให้เป็นจริง | `adb shell dumpsys battery reset` |
| สถานะ kiosk ทั้งหมด | `adb shell dumpsys activity activities \| Select-String "LockTask\|ResumedActivity"` |
| รายชื่อ allowlist จริง | `adb shell dumpsys activity activities \| Select-String "mLockTaskPackages" -Context 0,3` |

### เรื่อง `mLockTaskPackages` ที่ดูเหมือนว่าง

บรรทัด `mLockTaskPackages (userId:packages)=` **ว่างเปล่าเสมอ** ไม่ว่า allowlist
จะมีของหรือไม่มี เพราะซอร์ส AOSP พิมพ์หัวข้อจบด้วย `=` แล้วขึ้นบรรทัดใหม่ทีละ
user:

```java
pw.println(prefix + "mLockTaskPackages (userId:packages)=");
for (int i = 0; i < mLockTaskPackages.size(); ++i) {
    pw.println(prefix + "  u" + mLockTaskPackages.keyAt(i)
            + ":" + Arrays.toString(mLockTaskPackages.valueAt(i)));
}
```

`Select-String` คัดเฉพาะบรรทัดที่ตรงคำค้น บรรทัด `u0:[...]` ข้างล่างไม่มีคำว่า
`LockTask` อยู่เลย มันจึงถูกทิ้ง — ไม่ใช่ PowerShell ตัดบรรทัด แต่เป็นการกรอง
ทีละบรรทัดที่ทำให้ของหาย ใส่ `-Context 0,3` แล้วจะเห็น

---
## v0.20.0 — จอดับเองเมื่อไม่ใช้ 5 นาที และ Hey Jarvis ปลุกจอ

Poom เปลี่ยนการตัดสินใจเดิม (จอติดตลอดตอนชาร์จ) เป็น: ไม่มีคนแตะ ไม่มีคนคุย
ครบ 5 นาที จอดับ — ไมค์ไม่ดับ lock task ไม่หลุด

| อยากรู้ | คำสั่ง |
|---|---|
| จอติดอยู่ไหม, แบต, อุณหภูมิ | `adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService` ดู `screen-on`, `battery` |
| ไมค์ยังฟังไหม | ในผลเดียวกัน `mic : open`, `wake : listening` และ `score` |
| จอดับกี่ครั้ง ปลุกกี่ครั้ง | `screen-idle: ...  sleeps=N  wakes=N  note=...` |
| log เฉพาะเรื่องจอ | `adb logcat -s KioskScreen:*` |

`note=` บอกผลล่าสุด: `slept` จอดับสำเร็จ · `lock-refused` ระบบไม่ยอมให้ดับ
(จอจะดับตามเวลาปกติของเครื่องแทน) · `woke` คำปลุกเปิดจอได้ · `wake-denied`
ระบบไม่ยอมให้คำปลุกเปิดจอ

ขั้นทดสอบ (Poom ทำเอง):
1. วางมือถือไว้เฉยๆ ห้ามแตะ ห้ามพูด 5 นาที → จอต้องดับ
2. รออีกสักครู่ แล้วพูด "Hey Jarvis" จากระยะปกติ → จอต้องติดขึ้นมาที่หน้า
   kiosk (ไม่ใช่หน้าล็อก) มีเสียงติ๊ด แล้วถามต่อได้ตามปกติ
3. ระหว่างจาร์วิสตอบ จอต้องไม่ดับ แม้จะเลย 5 นาทีจากการแตะครั้งสุดท้าย
4. แตะมุมขวาล่าง 10 ครั้ง → ต้องออกจาก kiosk ได้เหมือนเดิม

---
## v0.21.0 — "ขอดูกล้อง" เปิดแอป Xiaomi Home

พูด "Hey Jarvis … ขอดูกล้อง" (หรือ "เปิดกล้อง", "ดูกล้องหน้าบ้าน") → จาร์วิสพูด
"กำลังเปิดกล้องให้ครับ" → แอป **Xiaomi Home** (`com.xiaomi.smarthome` ชื่อเดิม
Mi Home) ขึ้นมา ถ้าจอดับอยู่ คำปลุกจะเปิดจอก่อน

broker ตัดสินจาก**ข้อความที่ถอดเสียงได้ ด้วยโค้ด** ไม่ถามโมเดล (ไม่เสียเงิน) และ
โมเดลเขียนคำสั่งเปิดกล้องเองไม่ได้ ถ้าเขียนมาจะถูกทิ้ง มือถือเปิดได้แค่แพ็กเกจ
นี้แพ็กเกจเดียว

### ⚠️ ความปลอดภัย — เครื่องนี้ล็อกอินบัญชี Xiaomi

- ใช้**บัญชี Xiaomi ที่สร้างไว้สำหรับ kiosk เท่านั้น** ห้ามใช้บัญชีหลักของ Poom
  แชร์กล้องจากบัญชีหลักมาให้บัญชี kiosk ในแอป Xiaomi Home (ให้แค่ "ดู")
- ใครถือเครื่องนี้ได้ = ดูกล้องได้ นั่นคือความตั้งใจของฟีเจอร์นี้ ให้วาง kiosk
  ไว้ในที่ที่ยอมรับได้
- แอปเราและ broker **ไม่เก็บรหัสผ่าน ไม่รู้ชื่อบัญชี ไม่ log อะไรเกี่ยวกับกล้อง**
  log มีแค่ `type=open_camera_app` กับผลลัพธ์
- **ถ้าเครื่องหาย** (ทำทันที ไม่ต้องรอเครื่อง):
  1. บนมือถือ Poom: แอป Xiaomi Home → กล้องแต่ละตัว → แชร์อุปกรณ์ → เอาบัญชี
     kiosk ออก (ตัดสิทธิ์ดูกล้องทันที)
  2. เข้า https://account.xiaomi.com ด้วยบัญชี kiosk → เปลี่ยนรหัสผ่าน และ
     ออกจากระบบอุปกรณ์ที่ล็อกอินอยู่ 🔶 (ชื่อเมนูอาจต่างตามเวอร์ชันเว็บ)
  3. ถ้าจะใช้บัญชีนี้ต่อ ให้ตั้งรหัสผ่านใหม่ที่ไม่ซ้ำกับที่อื่น

### ล็อกอินบัญชี Xiaomi บน A07 — ทำตอนไหน

**ก่อนทดสอบคำสั่งเสียง ทำครั้งเดียว ตอนที่ kiosk ถูกปลดล็อกอยู่** เพราะหน้า
ล็อกอินหรือหน้าขอสิทธิ์ครั้งแรกอาจเปิดแอปอื่น (เบราว์เซอร์, บริการของ Google)
ซึ่งโหมด lock task จะไม่ยอมให้ขึ้น 🔶

1. ติดตั้ง Xiaomi Home บน A07 (Play Store/Galaxy Store — ❓ เครื่องนี้ไม่มีบัญชี
   Google ผูกไว้ ต้องเลือกช่องทางเอง)
2. ติดตั้ง APK v0.21.0 จาก CI (ขั้นข้างล่าง) — ต้องมีเวอร์ชันนี้ก่อน เพราะเป็น
   เวอร์ชันที่เพิ่ม Xiaomi Home ใน allowlist
3. แตะมุมขวาล่าง 10 ครั้ง เพื่อออกจาก kiosk
4. เปิด Xiaomi Home จากหน้าจอหลักปกติ → ล็อกอิน**บัญชี kiosk** → กดอนุญาต
   สิทธิ์ที่แอปขอ → ลองเปิดดูกล้องทั้ง 3 ตัวให้ขึ้นภาพ
5. กดปุ่ม Home → กลับมาที่ kiosk (ยังไม่ล็อก) แล้วรีสตาร์ตแอปหรือรีบูตหนึ่งครั้ง
   เพื่อให้ kiosk กลับเข้าโหมดล็อก

### ขั้นทดสอบบน A07 (PowerShell ทีละขั้น)

```powershell
# 0) เครื่องต่ออยู่
adb devices

# 1) ติดตั้ง APK จาก CI (โหลด artifact ของ run ล่าสุดที่เขียว)
$run = gh run list -R mammonrn/phone-ai-kiosk --workflow build-apk.yml --limit 1 --json databaseId -q '.[0].databaseId'
gh run download $run -R mammonrn/phone-ai-kiosk -D apk-ci
adb install -r -t apk-ci\phone-ai-kiosk-debug-apk\app-debug.apk

# 2) แอปเห็น Xiaomi Home ไหม (อ่านจากแอปเราเอง)
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService | Select-String "camera-package|mic  |wake  "
#    ต้องขึ้น camera-package: com.xiaomi.smarthome installed=true version=...
#    ถ้า installed=false = ยังไม่ได้ติดตั้ง หรือชื่อแพ็กเกจบนเครื่องไม่ตรง → หยุดแล้วแจ้ง

# 3) เปิด log เฉพาะแอปเรา ทิ้งไว้อีกหน้าต่าง
adb logcat -s KioskVoice:* KioskScreen:*
```

4. พูด "Hey Jarvis" รอเสียงติ๊ด แล้วพูด "ขอดูกล้อง"
   - ✅ ต้องได้ยิน "กำลังเปิดกล้องให้ครับ" **ก่อน** แอปขึ้น
   - ✅ log: `action open_camera_app result=OPENED`
5. กดปุ่ม Back ใน Xiaomi Home จนออก → ✅ ต้องกลับมาหน้า kiosk
6. เปิดกล้องอีกครั้ง (ข้อ 4) แล้ว**พูด "Hey Jarvis" ขณะภาพกล้องขึ้นอยู่**
   - ✅ ต้องได้ยินเสียงติ๊ด และจอกลับมาหน้า kiosk (log: `returning to the kiosk screen reason=wake`)
   - ❓ ถ้าไม่ติ๊ด อาจเป็นเพราะแอปกล้องใช้ไมค์หรือเสียงดังทับ — ส่ง log มา
7. ปล่อยเครื่องไว้ 5 นาทีให้จอดับ แล้วพูด "Hey Jarvis … เปิดกล้อง"
   - ✅ จอต้องติดเอง และแอปกล้องขึ้น
8. ลองคำที่**ต้องไม่เปิดแอป**: "กล้องวงจรปิดยี่ห้อไหนดี" → จาร์วิสตอบเป็นคำพูด
   ธรรมดา ไม่เปิดแอป
9. แตะมุมขวาล่าง 10 ครั้ง → ✅ ยังออกจาก kiosk ได้

```powershell
# 10) ตรวจว่าไม่มีข้อมูลบัญชีหรือกล้องใน log ของแอปเรา
adb logcat -d -s KioskVoice:* | Select-String -Pattern "xiaomi|@|camera" 
#    ต้องเห็นแค่ชื่อแพ็กเกจกับ open_camera_app ไม่มีอีเมล/ชื่อบัญชี/ชื่อกล้อง
```

---
## v0.22.0 — ข้อมูลเชิงเทคนิคออกจากจอ

จอผู้ใช้เหลือแค่ข้อมูลที่คนในบ้านใช้ (อากาศ ทอง คริปโต) และสถานะจาร์วิสเป็นคำ
บนแถบชื่อหน้าต่าง (พร้อมฟัง · กำลังฟัง · กำลังคิด · กำลังพูด) บรรทัดแบบ `mic=`
`wake=` `idle=` `owner=` `taps=` **ไม่ขึ้นบนจอแล้ว** แต่ยังดูได้ครบจาก dumpsys:

```powershell
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "kiosk  |status-line|third-line|on-screen|screen-idle"
```

ถ้าจำเป็นต้องเห็นบนจอ (เช่นกำลังจูนไมค์) เปิดโหมด debug ผ่าน adb — มีเฉพาะ
build debug ปิดเป็นค่าเริ่มต้น และกลับเป็นปิดเองเมื่อแอปรีสตาร์ต:

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_DIAGNOSTICS `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es value on      # ปิดด้วย --es value off
```

นาฬิกามุมขวาล่างเหลือบรรทัดเดียว `พ. 23 ก.ย.  8:16 AM` (วันที่ภาษาไทยตั้งแต่ v0.23.0) สูงเท่าปุ่ม "จาร์วิส"

---
## v0.24.0 — "ขอดูกล้อง" ไม่เปิดแอป: วิธีหาสาเหตุ และเวลารอพูดจบ 1.5 วินาที

**เวลารอพูดจบ (silence) เริ่มต้น 1,500 ms** (เดิม 900) เพราะ 900 ตัด "ขอดูกล้อง
หน่อยครับ" เหลือแค่ "ขอดู" ถ้า 1,500 ยังตัดอยู่ ตั้ง 1,800 ได้ทันที (หายเมื่อแอป
รีสตาร์ต):

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_SET_SILENCE `
  -n com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver `
  --es value 1800
```

**ถ้าสั่งกล้องแล้วไม่เปิด ดูสามที่ตามลำดับ:**

1. VPS รันโค้ดรุ่นไหน — ต้องไม่ใช่รุ่นก่อน `f6ed1e3`:
   ```powershell
   curl.exe -s https://kiosk.xn--l3cgts1b3bzcvf.com/healthz
   # {"status": "ok", "build": "<commit>"}   ไม่มี "build" = ยังไม่ได้ deploy รุ่นนี้
   ```
2. บนจอ: กล่องจาร์วิสแสดง `ได้ยิน: …` ค้างไว้ 1 นาทีหลังจบแต่ละรอบ — ดูว่าระบบ
   ถอดเสียงเป็นคำว่า "กล้อง" จริงไหม
3. บน VPS: `journalctl -u kiosk-broker -n 50 | grep intent` ทุกคำถามมีหนึ่งบรรทัด
   ```
   intent device=kiosk-a07 camera=yes reason=phrase:ขอดูกล้อง chars=18
   intent device=kiosk-a07 camera=no reason=near-miss:กล่อง chars=18
   ```
   `near-miss:กล่อง` = ถอดเสียงผิดเป็น "กล่อง" · `question-word` = เป็นคำถามเรื่อง
   กล้อง (ยี่ห้อ ราคา เสีย ไม่ได้) · `no-phrase` = มีคำว่ากล้องแต่ไม่ใช่คำสั่ง
   log ไม่มีข้อความที่พูด มีแค่เหตุผลกับความยาว

---
## v0.25.0 — สลับตัวถอดเสียงชั่วคราวบนมือถือ และตัวถอดเสียงในเครื่อง

**สลับชั่วคราวผ่าน adb** (หายเมื่อแอปรีสตาร์ต กลับไปใช้ค่าเริ่มต้นของ broker = Groq):

```powershell
$R = "com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.TestTriggerReceiver"
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_STT_PROVIDER -n $R --es value groq-hints
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_STT_PROVIDER -n $R --es value google
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_STT_PROVIDER -n $R --es value default
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService |
  Select-String "stt-engine|device-stt"
# stt-engine : asked=groq-hints last-used=groq-hints   ← last-used คือที่ broker ใช้จริง
```

### ตัวถอดเสียงในเครื่อง Android — ยังไม่เปิดใช้ (เหตุผลและหลักฐาน)

แอปตรวจแค่ว่า **มีหรือไม่** ตอนเริ่ม (บรรทัด `device-stt` ใน dumpsys) — ไม่เคยสั่ง
ให้ถอดเสียง ไม่เปิดไมค์

ทางเดียวที่จะใช้ได้โดยไม่เปิดไมค์ซ้อนคือส่งเสียงที่อัดไว้แล้วให้มันผ่าน
`RecognizerIntent.EXTRA_AUDIO_SOURCE` (API 33) แต่เอกสาร Android เขียนว่า
*"If this extra is not set or the recognizer does not support this feature, the
recognizer will open the mic"* และไม่บอกว่าตัวในเครื่องรองรับหรือไม่ ถ้าไม่รองรับ
มันจะ**เปิดไมค์ซ้อนกับ AudioRecord ที่ฟังคำปลุกอยู่** — ซึ่ง Poom ห้าม

ทางที่ปลอดภัยแน่นอน (ต้องให้ Poom อนุมัติก่อน เพราะแตะวงจรไมค์หลัก): ปิด
AudioRecord ของเราก่อนส่งเสียงให้ตัวถอดเสียง แล้วเปิดใหม่หลังได้ผล — ระหว่างนั้น
ไม่มีทางมีสองตัวพร้อมกัน ข้อเสีย: ช่วงถอดเสียง (ราว 1 วินาที) จะไม่ได้ยินคำปลุก
ซึ่งตอนนั้นก็ไม่ได้ฟังคำปลุกอยู่แล้ว

---
## v0.26.0 — ปุ่มจาร์วิส, แบตเตอรี่, และจาร์วิสรู้อากาศ

- **ปุ่ม "จาร์วิส" มุมซ้ายล่าง** กดแล้วเหมือนพูด Hey Jarvis: เสียงติ๊ด ขึ้น "ฟังอยู่ครับ"
  แล้วถามได้เลย ถ้ากดระหว่างจาร์วิสกำลังทำงาน ระบบไม่รับ (ไม่มี turn ซ้อน)
  log: `armed by the Jarvis button` หรือ `Jarvis button ignored: mode=...`
- **แบตเตอรี่** ในถาดเวลา: ไอคอนแบต + เปอร์เซ็นต์ มีสายฟ้าเมื่อชาร์จ แถบแดงเมื่อ
  ≤15% และไม่ได้ชาร์จ อัปเดตทุก 10 วินาที และทันทีเมื่อเสียบ/ถอดสาย
- **อากาศ** (ฝั่ง broker ต้อง deploy): จาร์วิสตอบอากาศจากข้อมูลเดียวกับบนจอ ไม่ยิง
  API เพิ่ม ถ้าข้อมูลเก่ากว่า 3 ชั่วโมงหรือไม่มี จะบอกว่ายังไม่มีข้อมูล

---
## v0.44.0 — File manager และจาร์วิสพักระหว่างเล่นสื่อ

### ให้สิทธิ์ไฟล์ — ทำครั้งเดียวหลังติดตั้ง (หรือหลัง provision ใหม่)

"เข้าถึงไฟล์ทั้งหมด" (`MANAGE_EXTERNAL_STORAGE`) เป็น special permission
Device Owner ให้เองไม่ได้ และไม่มีหน้าจอให้กดใน lock task จึงให้ผ่าน adb:

```
adb shell appops set --uid com.mammonrn.phoneaikiosk.debug MANAGE_EXTERNAL_STORAGE allow
adb shell appops get --uid com.mammonrn.phoneaikiosk.debug MANAGE_EXTERNAL_STORAGE
```

บรรทัดที่สองต้องตอบ `MANAGE_EXTERNAL_STORAGE: allow` สิทธิ์นี้อยู่ต่อข้าม
`adb install -r` ไม่ต้องให้ใหม่ทุกครั้งที่อัปเดต ถ้ายังไม่ได้ให้ หน้า "จัดการไฟล์"
จะขึ้นแม่กุญแจพร้อมคำสั่งนี้บนจอ

สิทธิ์เครือข่ายในบ้าน (สำหรับ NAS) แอปให้ตัวเองตอนเปิด ไม่ต้องทำอะไร:
Android 16 ใช้ `NEARBY_WIFI_DEVICES` ช่วงที่ระบบยังเป็นแบบเลือกเปิด ส่วน API 37
ขึ้นไปใช้ `ACCESS_LOCAL_NETWORK` ตรวจได้ด้วย

```
adb shell dumpsys package com.mammonrn.phoneaikiosk.debug | grep -E "NEARBY_WIFI|LOCAL_NETWORK"
```

### log ของ File manager

```
adb logcat -s KioskFiles:I
```

เขียนเฉพาะชนิดงานและผล เช่น `copy ok`, `unzip failed UNSAFE_ZIP`,
`nas list failed problem=LOGON_FAILURE` ไม่มีชื่อไฟล์ path ที่อยู่ NAS ชื่อผู้ใช้
หรือรหัสผ่าน (FileManagerTest ตรวจ)

### ทดสอบ NAS โดยไม่มี NAS จริง

ต่อสายเครื่อง แล้วเปิด SMB server ทดสอบบนคอมที่พอร์ต 4445 (เช่น impacket
`smbserver.py -smb2support -port 4445 -username u -password p TEST <โฟลเดอร์>`)
แล้ว `adb reverse tcp:4445 tcp:4445` ในแอปกรอกที่อยู่ `127.0.0.1:4445` แชร์ `TEST`

### จาร์วิสพัก — จำลองเพลงกำลังเล่น (debug build เท่านั้น)

ยังไม่มีเครื่องเล่นเพลงจริงในรอบนี้ ใช้สวิตช์นี้แทนผู้เล่น:

```
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_MEDIA_HOLD --ez on true  -p com.mammonrn.phoneaikiosk.debug
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_MEDIA_HOLD --ez on false -p com.mammonrn.phoneaikiosk.debug
adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService | grep wake-pause
```

- เปิดแล้ว: การ์ดขึ้น "จาร์วิส · พักระหว่างเล่นเพลง" พูด Hey Jarvis ต้องไม่ปลุก
- กดปุ่มจาร์วิส: ต้องถามได้ และ log มี `test media quiet` ตอนเริ่มฟัง `test media resume` หลังตอบ
- ปิดแล้ว (หรือรอ 90 วินาทีเพราะสวิตช์นี้ไม่ต่ออายุ): การ์ดกลับเป็น "พร้อมฟัง" และ Hey Jarvis ใช้ได้

---
## v0.45.0 — การ์ด "อุปกรณ์ในบ้าน" (eWeLink อ่านอย่างเดียว)

การ์ดซ่อนอยู่จนกว่า broker จะมีอุปกรณ์ให้แสดง ถ้าจะดูหน้าตาการ์ดก่อนเชื่อมบัญชีจริง
(debug build เท่านั้น ชื่ออุปกรณ์เป็นตัวอย่าง ไม่มีอะไรส่งไป broker):

```
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_HOME_CARD --ez on true  -p com.mammonrn.phoneaikiosk.debug
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_HOME_CARD --ez on false -p com.mammonrn.phoneaikiosk.debug
```

การ์ดขึ้นหรือหายในรอบดึง dashboard ถัดไป (ไม่เกิน 1 นาที) log: `adb logcat -s KioskHome:I`

ฝั่ง VPS ดู INSTALL.md หัวข้อ eWeLink

## v0.46.0 — สั่งเปิดปิดไฟ (eWeLink)

**การ์ดตัวอย่างพร้อมปุ่ม** (debug build เท่านั้น ข้อมูลตัวอย่างแบบบ้านของ Poom กดแล้ว broker จริง
จะตอบ "ไม่พบอุปกรณ์นี้แล้วครับ" เพราะ key เป็นของปลอม ใช้ดูหน้าตาและสถานะ "กำลังสั่ง…"):

```
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_HOME_CARD --es value on -p com.mammonrn.phoneaikiosk.debug
```

log ของการกด: `adb logcat -s KioskDashboard:I` → `home switch on=true ok=false online=null`
(ไม่มีชื่อ ไม่มี key)

**สิ่งที่ Poom ต้องทดสอบบนเครื่องจริง หลัง deploy และ `ewelink-allow` ครบ 3 ตัว:**

1. 🔶 สวิตช์ 4 ช่อง: `$B ewelink-switch …zzzz 1 on` แล้วดูที่สวิตช์จริงว่า **ช่อง 2 ถึง 4 ไม่เปลี่ยน**
   ถ้าช่องอื่นดับหรือติดตาม ให้หยุด (`$B ewelink-control off`) แล้วแจ้ง
2. พูด "เฮย์ จาร์วิส เปิดไฟหน้าบ้าน" (ชื่อช่องจริง) → ไฟติด จาร์วิสตอบ "เปิดไฟหน้าบ้านแล้วครับ" การ์ดเปลี่ยนเป็น "เปิด" ภายในไม่กี่วินาที
3. พูด "ปิดไฟห้องนั่งเล่น" (มีไฟหลายดวง) → จาร์วิส **ถามกลับ** ว่าดวงไหน ตอบชื่อ → ดวงนั้นดับดวงเดียว
   ตอบ "ทั้งหมด" → ดับทุกดวงในห้อง ตอบ "ยกเลิก" → ไม่มีอะไรเปลี่ยน
4. ถอดปลั๊ก Light2 (หรือตอนที่ออฟไลน์อยู่) แล้วพูด "เปิดไฟห้องนอน" → จาร์วิสบอกว่า **ออฟไลน์** ไม่พูดว่าเปิดแล้ว
   บนการ์ด Light2 ขึ้น "ออฟไลน์" เป็นไทล์แบนกดไม่ได้
5. พูด "เปิดไฟทั้งหมด" แล้ว "ปิดไฟทั้งหมด" → ทุกดวงที่ออนไลน์เปลี่ยน ดวงที่ออฟไลน์ถูกบอกชื่อ
6. พูด "ไฟหน้าบ้านเปิดอยู่ไหม" → ตอบสถานะ **ไม่สั่งอะไร**
7. กดไทล์ไฟบนการ์ด ("ปิดอยู่ · กดเพื่อเปิด") → ไทล์เป็น "กำลังสั่ง…" แล้วบรรทัดใต้ตารางบอกผลจริง ไทล์เปลี่ยนตามผลเท่านั้น
8. `$B ewelink-control off` → พูดสั่งไฟ จาร์วิสตอบ "ตอนนี้ปิดการสั่งไฟไว้ครับ" ไทล์บนการ์ดกลับเป็นแถวอ่านอย่างเดียวในรอบถัดไป → `$B ewelink-control on`
9. ดู log: `sudo journalctl -u kiosk-broker --since "10 min ago" | grep "home switch"` ต้องเห็นแค่ `target=…xxxx:N` ไม่มี id เต็มหรือชื่อ

**คำที่ broker เข้าใจ (มี unit test):** "เปิดไฟ…", "ปิดไฟ…", "ดับไฟ…", "ช่วยเปิดไฟ…หน่อยได้ไหมครับ",
คำที่ตัวถอดเสียงเขียนผิดบ่อย ("เปิดไป", "เปิ้ด", "ไฝ", "ไฟฟ้า"), ชื่ออังกฤษที่ถอดเป็นไทย ("ไลท์วัน" = Light1)
ไม่ใช่คำสั่ง: คำถาม ("…อยู่ไหม", "…หรือยัง"), คำปฏิเสธ ("อย่าเปิดไฟ"), แผนที่ ปลุก กล้อง เพลง ทีวี แอร์, "ระดับ"
ประโยคที่มีทั้งเปิดและปิด → ขอให้สั่งทีละอย่าง

## v0.47.0 — หน้า "ไฟในบ้าน" การ์ดหลอดไฟ และวันที่บนการ์ดจาร์วิส

**การ์ดตัวอย่าง** (debug build เท่านั้น เปิด ปิด และออฟไลน์ แบบบ้านของ Poom ไม่ส่งอะไรไป broker):

```
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_HOME_CARD --ez on true  -p com.mammonrn.phoneaikiosk.debug
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_HOME_CARD --ez on false -p com.mammonrn.phoneaikiosk.debug
```

**หน้า "ไฟในบ้าน" ตัวอย่าง** (debug build เท่านั้น ใช้ตอน VPS ยังไม่ได้ deploy เปิดหน้านั้นใหม่หลังส่ง):

```
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_LIGHTS_PAGE --ez on true  -p com.mammonrn.phoneaikiosk.debug
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_LIGHTS_PAGE --ez on false -p com.mammonrn.phoneaikiosk.debug
```

log ของหน้า "ไฟในบ้าน": `adb logcat -s KioskHome:I` → `lights page devices=3 error=none` และ `lights change ok=true`
(ไม่มีชื่อ ไม่มี key)

**สิ่งที่ Poom ต้องทดสอบบนเครื่องจริงหลัง deploy:**
1. แผงควบคุม → ไฟในบ้าน: Switch1 ต้องขึ้น **3 ช่อง** ไม่ใช่ 4
2. ตั้งชื่อช่อง 1 เป็นชื่อจริง (เช่น "ไฟหน้าบ้าน") → บันทึก → พูด "เปิดไฟหน้าบ้าน" ต้องติดดวงนั้นทันที
3. ลองตั้งชื่อช่อง 2 เป็น "หน้าบ้าน" → ต้องขึ้นสีแดงว่าซ้ำ และไม่บันทึก
4. เอาติ๊ก "อนุญาตให้สั่ง" ของช่องหนึ่งออก → พูดสั่งช่องนั้น → จาร์วิสต้องบอกว่ายังไม่ได้รับอนุญาต → ติ๊กคืน
5. การ์ดหน้าหลัก: ดวงที่เปิดเป็นหลอดเหลืองมีรัศมี ดวงที่ปิดเป็นหลอดเทา Light2 (ออฟไลน์) เป็นหลอดกลวงมีกากบาท
6. วันที่อยู่มุมขวาของแถบหัวข้อจาร์วิส taskbar เหลือ ชนิดการเชื่อมต่อ แบต และเวลา (ดูตอน 10–12 นาฬิกาด้วย)

## v0.47.1 (broker) — เปิด/ปิด ได้ยินสลับกัน

**ดูว่าตัวถอดเสียงได้ยินอะไร:** หลังพูด จอแสดง "ได้ยิน: …" อยู่แล้ว ไม่ต้องรันอะไรเพิ่ม
ถ้าต้องการเก็บข้อความและเสียงไว้เทียบ (14 วัน อ่านได้เฉพาะ broker):
`$B analysis on --audio` → พูด → `$B analysis summary` → เสร็จแล้ว `$B analysis off` และ `$B analysis purge`

**ทดสอบบนเครื่องจริงหลัง deploy:**
1. ไฟหน้าบ้านปิดอยู่ พูด "ปิดไฟหน้าบ้าน" → ต้องไม่สั่ง และตอบ "ไฟหน้าบ้านปิดอยู่แล้วครับ ต้องการเปิดไฟหน้าบ้านใช่ไหมครับ" → ตอบ "ใช่" → ไฟติด
2. ไฟหน้าบ้านเปิดอยู่ พูด "ปิดไฟหน้าบ้าน" → ไฟดับ "ปิดไฟหน้าบ้านแล้วครับ" → ภายใน 30 วินาที พูด "ไม่ใช่" → ไฟติดกลับ
3. "เปิดไฟทั้งหมด" ตอนบางดวงเปิดอยู่แล้ว → สั่งเฉพาะดวงที่ปิด และบอกว่าดวงไหนเปิดอยู่แล้ว
4. log: `journalctl -u kiosk-broker | grep "intent=lights:"` → `lights:already`, `lights:done`, `lights:undone`

## v0.48.0 — แตะไอคอนเพื่อสั่ง และเลือกไอคอน

1. แตะไอคอนที่นูนบนการ์ด → ชื่อเป็น "กำลังสั่ง…" หน้าต่างจาร์วิสขึ้น "กำลังสั่ง ชื่อ…" ไอคอนเปลี่ยนเมื่อ broker ตอบเท่านั้น แล้วหน้าต่างจาร์วิสบอกผล
2. แตะไอคอนแบน (ออฟไลน์ / ไม่อนุญาต) → ไม่สั่ง และหน้าต่างจาร์วิสบอกเหตุผล
3. แผงควบคุม › ไฟในบ้าน → เลือกพัดลมให้ดวงหนึ่ง → การ์ดหน้าหลักเปลี่ยนภายในรอบถัดไป (ไม่เกิน 1 นาที)
4. log: `adb logcat -s KioskDashboard:I` → `home tap on=true ok=true online=true` (ไม่มีชื่อ ไม่มี key)

## v0.49.0 — พยางค์แรกของคำสั่ง (เก็บเสียงก่อนคำปลุกจบ)

**วัดก่อนและหลังด้วยเสียงจริงของ Poom** (APK เดียวกัน สลับด้วย adb ส่วนเสียงเก็บไว้เทียบ 14 วัน):

```
# บน VPS: เก็บข้อความและเสียงไว้เทียบ
$B analysis on --audio
# บนคอม: ปิดการเก็บเสียงล่วงหน้า (= แบบเดิม)
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_PREROLL --es value off -p com.mammonrn.phoneaikiosk.debug
```
Poom พูดชุดนี้ **ต่อจาก "Hey Jarvis" ทันที ไม่เว้น** ประโยคละ 2 ครั้ง (ไม่สั่งไฟจริง เพราะเป็นคำถาม):
1. "Hey Jarvis เปิดไฟหน้าบ้านอยู่ไหม"
2. "Hey Jarvis ปิดไฟหน้าบ้านอยู่ไหม"
3. "Hey Jarvis กล้องหน้าบ้านเปิดอยู่ไหม"
4. "Hey Jarvis ปลุกพรุ่งนี้กี่โมง"
5. "Hey Jarvis นัดพรุ่งนี้มีอะไรบ้าง"
```
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_PREROLL --es value on -p com.mammonrn.phoneaikiosk.debug
```
แล้วพูดชุดเดิมอีกรอบ จากนั้น `$B analysis summary` ดูคำแรกของแต่ละแถว (เปิด/ปิด, กล้อง, ปลุก, นัด)
เสร็จแล้ว `$B analysis off` และ `$B analysis purge`

**ทดสอบเร็ว:** `adb shell dumpsys activity service com.mammonrn.phoneaikiosk.debug/com.mammonrn.phoneaikiosk.voice.VoiceService | grep pre-roll`
→ `pre-roll : on last=250 ms` หลังพูดต่อกันทันที

**การปลุกที่ไม่มีอยู่:** พิมพ์ด้วย `TEST_ASK --es text "'เปิดปลุก ทดสอบไม่มี'"` ตอนไม่มีการปลุกชื่อนี้ → จอต้องขึ้น "ไม่พบการปลุกนั้นครับ" ไม่ใช่ "เปิดปลุก…แล้วครับ"

## v0.51.0 (broker) — คำสั่งแผนที่ถอดซ้ำด้วย Qwen

ไม่มี APK ใหม่ เปลี่ยนแค่ broker (รายละเอียดและคำสั่งทั้งหมดใน `server/INSTALL.md` หัวข้อ "ถอดซ้ำคำสั่งแผนที่ด้วย Qwen")

**1. เสียงจริงที่เก็บไว้ (บน VPS, ≤ $0.05):**
```
$B analysis summary
$B stt-compare --ids <id ทั้งหมดที่มี audio> --providers groq-hints,rescue --maps-check
```
ต้องเห็น: ประโยคแผนที่ขึ้น `<used ...>` หรือ `<same ...>` และ `-> Maps: <ชื่อที่ถูก>` ·
ประโยคอื่นขึ้น `<->` และข้อความเหมือนแถว groq-hints ทุกตัวอักษร · บรรทัด `rescue: ... extra wait mean X ms` คือเวลาที่เพิ่ม

**2. บน A07 (พูดจริง):** "Hey Jarvis พาไปดอยตุง" · "พาไปสิงห์ปาร์ค" · "นำทางไปวัดร่องขุ่น" · "พาไปภูชี้ฟ้า"
→ Maps เปิดถูกที่ทุกครั้ง แล้วดู log:
```
sudo journalctl -u kiosk-broker --since "10 min ago" | grep "stt ok" | grep -o "text_from=[a-z-]* rescue=[a-z-]*.*rescue_ms=[0-9]*"
```
แผนที่ต้องเป็น `text_from=qwen rescue=used|same` · คำสั่งอื่น ("เปิดไฟหน้าบ้าน", "ขอดูกล้อง", "ตั้งปลุก 6 โมง") ต้องเป็น
`text_from=groq-hints rescue=- ... rescue_ms=0` และทำงานเหมือนเดิม

**3. ถอยกลับไป Groq:** `"maps_rescue_timeout_s": 0.01` ใน config.json แล้ว restart → พูดคำสั่งแผนที่ →
Maps ยังเปิด (ชื่อจาก Groq) log ขึ้น `rescue=fallback-timeout` → เอาค่านั้นออกแล้ว restart

## v0.51.2 (broker) — คำตอบระหว่างรอคำถามของไฟ

ทุกประโยคที่เข้ามาระหว่างรอคำตอบมี log หนึ่งบรรทัด (ไม่มีคำพูด):
`lights answer device=… waiting=<confirm|which|undo> answer=<no|yes|verb-same|verb-other|name|all|other|-> result=<confirmed|cancelled|undone|already|chosen|asked-again|new-command|not-an-answer|expired>`
```
sudo journalctl -u kiosk-broker --since "10 min ago" | grep "lights answer\|lights device\|home switch"
```
**ทดสอบบน A07 ด้วยข้อความพิมพ์ (ผ่าน broker จริง ไฟหน้าบ้านต้องปิดอยู่ก่อน):**
```
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "ปิดไฟหน้าบ้าน" -p com.mammonrn.phoneaikiosk.debug   # → จะเปิดไหมครับ
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "ปิด" -p com.mammonrn.phoneaikiosk.debug              # → ยกเลิกแล้วครับ ไม่ได้สั่งไฟ (ไฟไม่เปลี่ยน)
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "ปิดไฟหน้าบ้าน" -p com.mammonrn.phoneaikiosk.debug
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "เปิด" -p com.mammonrn.phoneaikiosk.debug             # → เปิดไฟหน้าบ้านแล้วครับ
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "ปิด" -p com.mammonrn.phoneaikiosk.debug              # → ขอโทษครับ ปิดไฟหน้าบ้านแล้วครับ (ย้อนกลับ)
```

## v0.52.0 — เครื่องคิดเลขวิศวกรรม (แผงควบคุม)

ไม่แตะ broker ไม่ต้อง deploy VPS ติดตั้งแค่ APK จาก CI (`adb install -r -t app-debug.apk`)

**โจทย์ที่ตรวจคำตอบได้ (ผ่านบน A07 เมื่อ 2026-09-24):**
| หน้า | ใส่ | ต้องได้ |
|---|---|---|
| คำนวณ | 5 × 12 = | 60 |
| คำนวณ | √ 256 = | 16 (โจทย์แสดง "√(256)") |
| คำนวณ | sin 30 = (องศา) | 0.5 |
| คำนวณ | 1 ÷ 0 = | "หารด้วยศูนย์ไม่ได้" สีแดง |
| คำนวณ | 1.5 EXP (-) 7 × 2 = | 3E-7 |
| คำนวณ | 2 x^y 10 = | 1024 |
| คำนวณ | 100 = M+ · 25 M+ · MR + 1 = | หน่วยความจำ 125, ผล 126 |
| กฎของโอห์ม | V 12 V, R 4 Ω | I = 3 A, P = 36 W (ตัวหนา) |
| กฎของโอห์ม | V -5 | "ผิดพลาด: ค่า V ต้องไม่ติดลบ" หายเมื่อเริ่มแก้ |
| อนุกรม/ขนาน | 100 Ω, 0.1 kΩ | 200 Ω / 50 Ω |
| รหัสสี อ่าน | เหลือง ม่วง ส้ม ทอง | 47 kΩ ±5% (44.65–49.35 kΩ) |
| รหัสสี หา | 3300 Ω ±1% | ส้ม ส้ม แดง น้ำตาล |
| กระแสสลับ | 230 V 10 A PF 0.8 | P 1.84 kW, S 2.3 kVA, Q 1.38 kvar |
| รีแอกแตนซ์ | 50 Hz, C 100 µF | X_C = 31.83 Ω |
| ขนาดสาย | 230 V 16 A 20 m 3% | 2.5 ตร.มม. ตก 5.67 V (2.47%) |

**ทางออก 10 ครั้ง (layout ปัจจุบัน):** พื้นที่แตะอยู่เหนือนาฬิกา taskbar มุมขวาล่าง
`for i in $(seq 1 10); do adb shell input tap 650 1560; sleep 0.2; done` → `mLockTaskModeState=NONE`
(ค่า `650 1440` ด้านบนของไฟล์นี้เป็นของ layout เก่า ตอนนี้ตรงนั้นคือการ์ดจาร์วิส)
**กลับไปล็อก:** `adb reboot` เท่านั้น — `am force-stop` ไม่ฆ่าโปรเซสของแอปที่เป็น Device Owner
(pid เดิมยังอยู่) แอปจึงยังจำว่าปลดล็อกไว้

## v0.53.3 (broker) — สถานะไฟตามทันเมื่อเปลี่ยนจากทางอื่น และข้อมูลดิบของ eWeLink

**1. ดูว่า eWeLink ส่งอะไรมาบ้าง (อ่านอย่างเดียว ใช้ API 1-2 ครั้ง):**
```
$B ewelink-raw
```
พิมพ์ชื่อช่องทั้งหมดของแต่ละอุปกรณ์ (ชื่อเท่านั้น) และค่าของช่องสถานะ สัญญาณ พลังงาน และตารางเวลา (`timers`)
id ถูกตัดเหลือ 4 ตัวท้าย ไม่พิมพ์ค่า apikey, devicekey, MAC, IP หรือรายชื่อที่แชร์ ส่งผลให้ผมดูได้ (ไม่มีข้อมูลลับ)

**2. ทดสอบบนเส้นทางจริง:** เปิดจอ kiosk ไว้ที่หน้าหลัก แล้วในแอป eWeLink สลับไฟหน้าบ้าน จดเวลา
แล้วดูว่าการ์ด "อุปกรณ์ในบ้าน" เปลี่ยนตามภายในกี่วินาที (ควรไม่เกินราว 3 นาที: cache 2 นาที + รอบของมือถือ 1 นาที)
ระหว่างรอ ถามจาร์วิส "ไฟหน้าบ้านเปิดอยู่ไหม" ต้องตอบตามจริงทันที (อ่านสด)


## v0.54.0 — ขนาดมาตรฐานของแผงควบคุม (`ui/UiScale.kt`)

- **เทสต์:** `UiScaleTest` ตรวจสามอย่าง
  - ไม่มีขนาดที่เป็นตัวเลขในหน้าจอของแผงควบคุม
  - ค่าใน `UiScale` ทำตามกติกา
  - ปุ่มนูนเห็นเงาด้านล่างและขวา
- **บนเครื่อง:** เปิดทุกหน้าในแผงควบคุม แล้วใช้ `uiautomator dump` วัดขนาด (A07 = 1.875 px ต่อ dp) ต้องได้ผลดังนี้
  - ปุ่มทุกปุ่มสูง 48dp ขึ้นไป
  - ปุ่ม X ขนาด 48×48
  - ช่องกรอกสูง 48
  - ปุ่มหลักและ "กลับหน้าหลัก" สูง 56
  - แถวเพลงและแถวไฟล์สูง 56
- **หน้าหลักต้องไม่เปลี่ยน:** ขอบเขตของ `card_jarvis` และ `exit_corner` ใน dump ต้องเท่าเดิม (0.54.0: `[13,1103][707,1502]` และ `[585,1465][720,1600]`)
- **ระวังระหว่างทดสอบ:** ถ้าได้ยิน Hey Jarvis (รวมถึงเสียงในห้องที่ถูกจับเป็นคำปลุกผิด) จาร์วิสจะปิดทุกหน้าจอตามข้อ 11 ถ้าหน้าจอหายกลางการทดสอบ ให้ดู `KioskScreens: closed` ใน logcat ก่อนสงสัยว่าแอปล่ม

## v0.55.0–0.56.0 — เครื่องเล่นเพลงแบบ Winamp และเครื่องเล่นวิดีโอแบบ PowerDVD

- **หน้าเพลงวาดใหม่ตลอดเวลา (กราฟแท่ง):** `uiautomator dump` ตอบ "could not get idle state" บนหน้า "กำลังเล่น" จึงตรวจด้วย `adb exec-out screencap` และพิกัดแทน หน้าคลังเพลง หน้ารายการวิดีโอ และแผงควบคุมยัง dump ได้ตามปกติ
- **อีควอไลเซอร์เป็นตัวเลข (debug):**
  ```
  adb shell "am broadcast -a com.mammonrn.phoneaikiosk.TEST_FX -p com.mammonrn.phoneaikiosk.debug --es preset 'เบสหนัก'"
  adb logcat -d | grep "fx bars"
  ```
  - บรรทัด log ให้ค่าเฉลี่ย 19 แท่งตลอด 3 วินาที พร้อม kbps, kHz และจำนวนช่องเสียงของไฟล์
  - เปลี่ยน preset แล้วค่าแท่งต้องเปลี่ยนตามทิศของ preset เช่น เปิดโทน 440 Hz แล้วสลับ Flat กับ "เบสหนัก" แท่งที่ 7 ต้องขึ้นจาก 0.70 เป็น 0.75 (ประมาณ +3 dB)
  - broadcast นี้ยอมรอได้แค่ 5 วินาที เคยรอเพลงที่ถอดรหัสไม่ได้นานเกินจนเกิด ANR (เฉพาะ debug build)
- **ALAC ถอดรหัสบน A07 ไม่ได้** เพราะเครื่องไม่มี decoder ต้องเห็นข้อความ "…ไม่มีตัวถอดรหัส ALAC จึงข้ามไปเพลงถัดไป" และ log `no decoder for audio/alac` ถ้าเวลาเดินแต่ไม่มีเสียงแปลว่าถอยกลับไปเป็นบั๊กเดิม
- **ไฟล์วิดีโอทดสอบ** สร้างด้วย ffmpeg แล้ววางที่ `/sdcard/Movies/KioskTest/` จากนั้นสั่ง `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/KioskTest/<ไฟล์>`
  - ไฟล์ที่ต้องเล่นได้: MP4 H.264 720p, MKV ที่มีเสียง 2 แทร็ก (ไทย/อังกฤษ) และคำบรรยาย SRT, WebM VP9, 3GP H.263, MOV, MP4 HEVC 720p
  - ไฟล์ที่ต้องถูกปฏิเสธพร้อมเหตุผล: MP4 1080p และ MP4 AV1
- **บันไดความร้อน** (คืนค่าทุกครั้งหลังทดสอบ):
  ```
  adb shell cmd thermalservice override-status 2   # จอหรี่ 70% และมีข้อความบนภาพ
  adb shell cmd thermalservice override-status 4   # หยุดพัก และกดเล่นไม่ได้
  adb shell cmd thermalservice reset
  adb logcat -s KioskVideo:I                        # "heat status=… step=…"
  ```
  หลัง reset บันไดลงทีละขั้น ขั้นละ 60 วินาที
- **จาร์วิสพักระหว่างเล่นวิดีโอ:** `dumpsys … VoiceService | grep wake-pause` ต้องได้ `ON — video lease …` และการ์ดจาร์วิสต้องบอก "พักระหว่างเล่นวิดีโอ"
- **เล่นทีละเสียง:** เล่นเพลงแล้วเปิดวิดีโอ เพลงต้องหยุดพัก ทำกลับกันก็ต้องได้ผลเดียวกัน

## v0.57.0 — วิดีโอแนวนอน, คำสั่งเสียงวิดีโอ, อีควอไลเซอร์ที่วัดด้วยโทนเสียง

- **หมุนจอ (debug):** เปิดเต็มจอในเครื่องเล่นก่อน แล้วสั่ง
  ```
  adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_VIDEO_ROTATE -p com.mammonrn.phoneaikiosk.debug --es to landscape
  adb shell dumpsys window | grep -o "mCurrentRotation=[^ ]*"
  ```
  - คำสั่งนี้จำลองสิ่งที่เซนเซอร์บอก ใช้ได้เมื่อเต็มจอและยังไม่ล็อก ถ้าล็อกอยู่ log จะบอก "test turn ignored"
  - การหมุนเครื่องด้วยมือจริงยังต้องทดสอบบนเครื่อง
- **แผงลอยซ่อนเองและการแตะภาพคือสลับแผง:** แตะโดยไม่รู้ว่าแผงแสดงอยู่หรือไม่จะพลาดง่าย ให้ตรวจพิกเซลของแผงจากภาพหน้าจอก่อนกด
- **คำสั่งเสียงผ่านโค้ดจริงของเครื่อง (debug) ไม่ต้องใช้ broker:**
  ```
  adb shell "am broadcast -a com.mammonrn.phoneaikiosk.TEST_PERFORM -p com.mammonrn.phoneaikiosk.debug --es type video --es command play --es query 'v5 h264'"
  adb shell "am broadcast -a com.mammonrn.phoneaikiosk.TEST_PERFORM -p com.mammonrn.phoneaikiosk.debug --es type video --es command pause --ez as_turn true"
  ```
  - `as_turn true` จำลองคำถามที่เริ่มจากปุ่มจาร์วิส: สื่อถูกทำให้เงียบ คำสั่งทำงาน แล้วคำถามจบ
  - หลัง pause ต้องได้ `wake pause off` และต้องไม่มีบรรทัด "resumed"
  - คำถามอื่น เช่น `quieter` ต้องได้ "resumed 1 player(s)"
- **Hey Jarvis จริงทางอากาศ:** ลำโพงคอมพิวเตอร์พูดผ่าน System.Speech (เสียง David) แล้วเครื่องจับได้ที่คะแนน 0.9 ขึ้นไป
  ```
  powershell -Command "Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('Hey Jarvis')"
  adb logcat -d | grep "wake word detected"
  ```
- **tray ตอน 10–12 นาฬิกา (debug):** `adb shell "am broadcast -a com.mammonrn.phoneaikiosk.TEST_CLOCK -p com.mammonrn.phoneaikiosk.debug --es text '12:59 PM'"` เวลานี้แสดงค้าง 60 วินาที
  - ต้องใส่ `'…'` ไว้ในเครื่องหมายคำพูดของ adb shell ไม่งั้นช่องว่างทำให้ "PM" หายไป เคยพลาดมาแล้วครั้งหนึ่ง
- **อีควอไลเซอร์วัดด้วยโทน (debug):** เล่นไฟล์ `eq_tones.flac` (ไซน์ที่ 60, 170, 310, 600, 1000, 3000, 6000 Hz ความดังอย่างละ 0.04 สร้างด้วย ffmpeg `aevalsrc`) แล้วสั่ง
  ```
  adb shell "am broadcast -a com.mammonrn.phoneaikiosk.TEST_FX -p com.mammonrn.phoneaikiosk.debug --es preset 'เบสหนัก' --es tones '60,170,310,600,1000,3000,6000'"
  ```
  - ใช้ Goertzel กับ 8192 ตัวอย่าง (หน้าต่าง Hann) ผลตรงกับทฤษฎีภายใน 0.1 dB
  - pink noise กับแท่ง FFT ใช้วัดย่านต่ำไม่ได้ เพราะแท่ง 50–92 Hz มีแค่ช่องความถี่เดียวกว้าง 43 Hz
- **ความร้อนขั้น 3 และขั้น 4:**
  - `override-status 4` → หยุดพัก ภาพค้าง กดเล่นไม่ได้ และคำปลุกกลับมา
  - `override-status 6` → หยุดเล่น ภาพดำ เวลาเป็น 0:00:00 และ service หยุด
  - ต้อง `cmd thermalservice reset` ทุกครั้งหลังทดสอบ

## v0.58.0 — หมุนจอเมื่อเต็มจอ, วิดีโอไม่เล่นเบื้องหลัง

- **หมุนจอด้วยมือจริง (ต้องใช้คนหมุนเครื่อง):**
  1. เปิดวิดีโอ แตะภาพให้แผงขึ้น แล้วกด "เต็มจอ" จนปุ่มขึ้นว่า "เปิด"
  2. หมุนเครื่องเป็นแนวนอน ภาพต้องหมุนตามภายในประมาณ 1 วินาที
  3. กลับไปที่รายการ แล้วเลือกวิดีโอเรื่องอื่น (ทางที่เคยพังใน 0.57.0) แล้วหมุนเครื่องอีกครั้ง
  4. ดู log ได้ด้วย `adb logcat -s KioskVideo` ต้องเห็น `orientation asked=sensor` ทุกครั้งที่หน้าเล่นแสดงขณะเต็มจอ
  - ถ้าเซนเซอร์บอกแนวนอนแล้วแต่ภาพไม่หมุน ให้ดู `adb shell dumpsys window | grep -E "mCurrentAppOrientation|mRotation"` ค่าแรกต้องเป็น SENSOR
- **ออกจากเครื่องเล่นแล้ววิดีโอต้องหยุด (log `KioskVideo`):**
  - X หรือ Back → `stopped: left the player (closed)`
  - "◄ รายการ" → `stopped: left the player (list)` และรายการแสดง "ดูถึง …"
  - จอดับ (`adb shell input keyevent KEYCODE_SLEEP`) → `paused: screen off`
  - ทุกกรณีต้องได้ `wake pause off` แล้วพูด Hey Jarvis ทางลำโพงคอมพิวเตอร์ เครื่องต้องจับได้
- **ลบไฟล์ทดสอบ:** ระบุ path เต็มทีละไฟล์ ห้ามใช้ wildcard แล้วสั่งสแกนใหม่ทีละไฟล์
  ```
  adb shell rm /sdcard/Music/KioskTest/eq_tones.flac
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/KioskTest/eq_tones.flac
  adb shell content query --uri content://media/external/video/media --projection _data
  ```
  - ไฟล์ที่ลบไปแล้วแต่ยังอยู่ในรายการเพลงที่บันทึกไว้ จะไม่หายเอง กดเล่นแล้วแอปหยุดเองและขึ้นข้อความ ต้องเอาออกจากรายการด้วยปุ่ม "ลบ"

## v0.59.0 — โฟลเดอร์มาตรฐาน, playlist, ไฟล์เดียว, ตัวดูรูป

- **อัปเดตทับแล้ว "รายการเดิม" ต้องครบ:** ก่อนอัปเดต ให้ใส่เพลงลงรายการของเครื่องเล่นเพลงรุ่นเก่าก่อน แล้วเทียบรายการก่อนและหลัง โดยดูแค่จำนวนและ hash ของ id ไม่พิมพ์ชื่อเพลง
  ```
  adb shell run-as com.mammonrn.phoneaikiosk.debug cat files/music_session.txt      # ก่อน: บรรทัดแรกคือ index ตำแหน่ง สุ่ม เล่นซ้ำ
  adb shell run-as com.mammonrn.phoneaikiosk.debug cat files/playlists.txt          # หลัง: "L" คือ playlist, "T" คือเพลง
  ```
  - playlist ถูกสร้างตอนเปิดเครื่องเล่นเพลงครั้งแรกหลังอัปเดต ใน log ต้องมี `KioskMusic: playlists made: lists=1 songs=N`
  - build ของ CI เป็น test-only ต้องติดตั้งด้วย `adb install -r -t`
- **ไฟล์เดียวจากตัวจัดการไฟล์:** ใน log ต้องเห็น `one file playing on its own` แล้วตามด้วย `list back after one file` เมื่อกด Back หรือไฟล์เล่นจบ
  - ระหว่างนั้น `music_session.txt` ต้องยังเป็นรายการเดิม
- **ตัวดูรูป:** `mCurrentFocus` ต้องเป็น `ImageViewerActivity` และ `mLockTaskModeState=LOCKED`
  - adb ทำนิ้วเดียวได้อย่างเดียว จึงทดสอบได้แค่แตะสองครั้งเพื่อขยาย 2 เท่าและลากรูป ส่วนการขยายด้วยสองนิ้วต้องใช้มือจริง
- **ไฟล์ที่ยังไม่รองรับ (.DAT .mpg .wmv .wma):** กดแล้วใน log ต้องเห็น `not played: kind not yet` ทันที บนจอขึ้น "ยังไม่รองรับไฟล์ชนิด …" และไม่ค้าง
- **คำสั่งเสียงค้นเฉพาะ playlist:** ทดสอบผ่านโค้ดของเครื่องได้โดยไม่ต้องใช้ broker ใน log เห็นแค่ `done=true/false`
  ```
  adb shell "am broadcast -a com.mammonrn.phoneaikiosk.TEST_PERFORM -p com.mammonrn.phoneaikiosk.debug --es type music --es command play --es query 'ชื่อเพลง'"
  ```
- **แตะด้วยข้อความ (tapt):** "ลบ playlist" ตรงกับทั้งประโยคคำถามและปุ่ม ต้องเลือกรายการที่สอง (`tapt.sh "ลบ playlist" 1`)
- **ลบไฟล์ทดสอบ:** ใช้ path เต็มทีละไฟล์ แล้ว `rmdir` ทีละโฟลเดอร์จากชั้นในสุด

## v0.60.0 — LibVLC สำหรับไฟล์ที่ Media3 เล่นไม่ได้, สุ่มที่ไม่หยุดกลางทาง, การลบต้องยืนยันตัวตน

- **เครื่องเล่นที่ถูกเลือก:** ดูจาก log เท่านั้น ไม่พิมพ์ชื่อไฟล์
  - ไฟล์ที่ไปทาง VLC ต้องมี `KioskVideo: engine vlc` หรือ `KioskMusic: engine vlc` ตามด้วย `state playing`
  - ไฟล์ของ Media3 (MP3, AAC m4a, MP4) ต้องไม่มีบรรทัด `engine vlc`
  - m4a ถูกเลือกด้วย codec จริงข้างในไฟล์ (`media/Mp4Sniff.kt`) ไม่ใช่นามสกุล ALAC ไป VLC ส่วน AAC อยู่กับ Media3
- **มีเสียงจริง:** หา AudioTrack ของแอปที่อยู่ในสถานะ started
  ```
  pid=$(adb shell pidof com.mammonrn.phoneaikiosk.debug)
  adb shell dumpsys audio | grep -E "u/pid:[0-9]+/$pid state:started"
  ```
- **มีภาพจริง (VCD/MPG):** ถ่ายภาพจอหลังเริ่มเล่นอย่างน้อย 20 วินาที เพราะฉากแรกของบางแผ่นมืดจนดูเหมือนจอดำ
  - ถ้าภาพดำแต่มีเสียง ให้ตรวจว่า VLC ได้รับขนาดของ SurfaceView ก่อนเล่น (`VlcDeck.attach`)
- **ไฟล์ที่เล่นผ่าน VLC ไม่มีกราฟแท่ง:** บนจอต้องขึ้น "ไม่มีกราฟสำหรับไฟล์นี้" และห้ามมีแท่งปลอม
  - ไฟล์ของ Media3 ต้องมีแท่งที่ขยับ ให้ถ่ายภาพสองภาพห่างกัน 1 วินาทีแล้วเทียบกัน
- **ความร้อน:** ใช้ `dumpsys thermalservice` ส่วน "Current temperatures from HAL" ทุก 5 นาทีเป็นเวลา 20 นาที
  - เทียบกับไฟล์เดียวกันที่แปลงเป็น MP4 (H.264 ขนาดเท่าเดิม) แล้วเล่นผ่าน Media3
  - ให้เครื่องเย็นลงก่อนเริ่มรอบที่สอง และเสียบสาย USB เหมือนกันทั้งสองรอบ
- **การลบต้องยืนยันตัวตน:** ใช้ไฟล์และโฟลเดอร์ทดสอบที่สร้างขึ้นเองเท่านั้น
  - แตะค้างแล้วกด "ลบ" ตามด้วย "ลบถาวร" ต้องเปิด `auth.VerifyActivity`
  - กด "ยกเลิก" แล้วต้องขึ้น "ยังไม่ได้ลบ เพราะยกเลิกการยืนยันตัวตน" และไฟล์ยังอยู่ (`adb shell ls`)
  - ใน log ต้องเห็น `KioskFiles: delete: identity not passed`
  - ทดสอบทั้งไฟล์และโฟลเดอร์ รวมถึงการลบ playlist และปุ่ม "ล้าง" ของเครื่องเล่น
- **สุ่มและเล่นซ้ำ:** ใช้เพลงสั้นหรือเลื่อนไปใกล้ท้ายเพลง แล้วทดสอบครบ 6 แบบ (สุ่มเปิดหรือปิด × เล่นซ้ำปิด/ทั้งหมด/เพลงเดียว)
  - ทดสอบเพิ่มเพลงเข้า playlist ระหว่างเล่น แล้วปล่อยให้เพลงจบ ต้องเล่นเพลงถัดไปต่อ
  - ใส่ไฟล์ที่เล่นผ่าน VLC (เช่น ALAC) ไว้ใน playlist ด้วย
- **LibVLC ของเรา:** CI ดาวน์โหลด AAR จาก release ที่ตรึงไว้ และปฏิเสธถ้า SHA-256 ไม่ตรง
  - สร้างใหม่ได้ด้วย workflow `libvlc-lgpl.yml` ในแท็บ Actions รายละเอียดอยู่ใน `licenses/LIBVLC.md`
  - ขนาด APK ต้องไม่เกิน 120 MiB (CI ตรวจให้)
- **ไฟล์ VCD ที่มีจุดเสีย:** เล่นยาวต่อเนื่องอย่างน้อย 20 นาที ห้ามดูแค่ 10 วินาทีแรก (คู่โจร1 หยุดเองที่ 4:07)
  - ใน log ต้องเห็น `KioskVlc: vlc: early end, going on past it (N)` ตามด้วย `vlc: started where asked` และไม่มี `video service down` ก่อนเวลา
  - ตำแหน่งบนหน้าจอต้องเดินต่อเนื่อง ถ้าย้อนกลับไปต้นเรื่อง แปลว่า seek ไม่ติด
  - หาตำแหน่งจุดเสียด้วย `ffmpeg -v error -i FILE -f null -` ("Missing picture start code") หรือดูการกระโดดของ SCR ใน pack header
- **สัดส่วนภาพ VCD:** แผง LCD ต้องขึ้น "288p" และภาพต้องเต็มความกว้างจอในแนวตั้ง ถ้าภาพเล็กและมีขอบดำซ้ายขวา แปลว่ากรอบภาพค้างที่ 16:9
  - ใน log ต้องเห็น `vlc: window 720x540` หลัง `window 720x405 at attach`
- **ตั้งสุ่มและเล่นซ้ำจาก adb:** อ่านค่าจาก `files/music_session.txt` บรรทัดแรก (ช่อง 4 = สุ่ม, ช่อง 5 = เล่นซ้ำ) แอปบันทึกทันทีที่กด ห้ามอ่านจาก uiautomator เพราะหน้าจอที่มีกราฟขยับทำให้ได้ค่าเก่า

## ปฏิทิน: เพิ่มนัดด้วยเสียง (broker)

ฝั่ง broker อย่างเดียว ไม่ต้องลง APK ใหม่ ดูหัวข้อ deploy — ต้องรัน install.sh เสมอ
(Poom รันเองบน VPS ตาม INSTALL.md "ขั้น 1 — รันสคริปต์ติดตั้ง" แล้ว restart บริการ)
ต้องเชื่อมบัญชี Google ไว้แล้ว (รอบ 2A) และสิทธิ์ `calendar.events` มีอยู่แล้ว ไม่ต้องขอใหม่

**พูดทีละรอบ (Hey Jarvis ทุกครั้ง เพราะเครื่องไม่ฟังต่อหลังตอบ):**

1. "ลงนัดพรุ่งนี้บ่ายสองไปทดสอบจาร์วิส"
   - ยังไม่ได้ยืนยันตัวตน: ได้ยิน "ขอยืนยันตัวตนก่อนนะครับพี่" แล้วหน้ายืนยันตัวตนเปิด ผ่านแล้วเครื่องถามซ้ำเอง
   - ยืนยันแล้ว: ได้ยิน "จะลงนัด พรุ่งนี้ (วัน…ที่ …) บ่าย 2 โมง ไปทดสอบจาร์วิส ถ้าถูกต้องพูดว่า ยืนยัน ครับ"
2. ภายใน 2 นาที พูด "ยืนยัน" → ได้ยิน "ลงนัดแล้วครับ พรุ่งนี้ …" (พูดเฉพาะเมื่อ Google ตอบว่าเพิ่มสำเร็จแล้วเท่านั้น)
3. พูด "ยืนยัน" ซ้ำอีกครั้ง → ต้องไม่เกิดนัดที่สอง (คำนี้จะไปเป็นคำถามธรรมดา)

**กรณีอื่นที่ควรลอง:**

- พูด "ไม่" หรือ "ยกเลิก" แทนยืนยัน → "ยกเลิกแล้ว ไม่ได้ลงนัดครับ" และไม่มีนัดเกิดขึ้น
- พูดเรื่องอื่นแทนยืนยัน → นัดที่รอถูกทิ้ง ไม่มีอะไรถูกเพิ่ม
- รอเกิน 2 นาทีแล้วค่อยพูด "ยืนยัน" (ภายใน 10 นาที) → "หมดเวลายืนยันแล้วครับ ยังไม่ได้ลงนัด ถ้าต้องการให้สั่งเพิ่มนัดใหม่อีกครั้ง"
  ถ้าพูด "ยกเลิก" แทน → "ไม่มีนัดที่รอยืนยันครับ" หลังจาก 10 นาที คำว่า "ใช่ครับ" จะเป็นคำถามธรรมดาตามเดิม
- "ลงนัดพรุ่งนี้สองโมงประชุม" → ถามกลับว่า "2 โมงเช้าหรือบ่าย 2 โมง" ต้องพูดใหม่ทั้งประโยค
- "ลงนัดพรุ่งนี้ไปหาหมอ" (ไม่บอกเวลา) → "นัดกี่โมงครับ …" ไม่มีการเดาเป็นนัดทั้งวัน
- "ลงนัดวันนี้" ตามด้วยเวลาที่ผ่านไปแล้ว → "… ผ่านไปแล้วครับ ไม่ได้ลงนัด"
- ยืนยันตัวตนหมดอายุ (เกิน 2 นาทีหลังสแกนหน้า) ตอนพูด "ยืนยัน" → ขอยืนยันตัวตนอีกครั้ง แล้วเครื่องส่ง "ยืนยัน" ซ้ำเอง

**ตรวจใน log (มีแค่ขั้นตอนและเหตุผล ห้ามมีชื่อนัดหรือเวลา):**

```bash
sudo journalctl -u kiosk-broker -n 100 | grep -E "calendar-add|calendar_add="
# calendar-add device=… step=asked reason=draft:relative
# calendar-add device=… step=confirmed ms=…
# ถ้าไม่สำเร็จ: step=failed http=403 sure=yes  (sure=no = ไม่รู้ผล ให้เปิดดูในปฏิทินเอง)
```

ถ้าพูดแล้วไม่เข้าทางเพิ่มนัด ดูช่อง `calendar_add=` ท้ายบรรทัด `intent` ว่าเป็น
`no-add-word` (ถอดเสียงไม่ได้คำว่า ลงนัด/เพิ่มนัด) `question:…` หรือ `not-an-add:…`

**Poom ตรวจและลบนัดทดสอบเอง:** เปิด Google Calendar (แอปหรือเว็บ) ไปที่วันพรุ่งนี้
ต้องเห็นนัด "ไปทดสอบจาร์วิส" เวลา 14:00–15:00 และในรายละเอียดมีคำว่า "เพิ่มโดยจาร์วิส"
แตะนัดนั้นแล้วกดลบเอง (จาร์วิสลบนัดไม่ได้ และไม่มีคำสั่งลบ)

## v0.61.0 — สุ่มใหม่ทุกรอบ, audio focus ของ VLC, แอปพื้นฐาน (กล้อง บันทึกเสียง Drive วิทยุ), ปุ่มปิดกลับหน้าที่มา, แผงควบคุมเป็นหมวด

- **สุ่ม × โหมดซ้ำ:** playlist ทดสอบ 10 รายการ (เพลงละ 25 วินาที มี ALAC ผ่าน VLC และไฟล์ที่หายไป 1 รายการ) ติดตามลำดับจาก `music_session.txt` ทุก 2 วินาที ไม่ log ชื่อเพลง ทั้ง 6 คู่ต้องได้: ปิด/ปิด ครบแล้วหยุด · ปิด/ทั้งรายการ วนกลับต้น · ปิด/เพลงเดียว ซ้ำ · เปิด/ปิด ครบรวมเพลงที่เพิ่มระหว่างเล่นแล้วหยุด · **เปิด/ทั้งรายการ รอบที่สองลำดับต่างจากรอบแรก** · เปิด/เพลงเดียว ซ้ำ
- **audio focus ของ VLC (debug):** เล่นไฟล์ VLC แล้ว
  ```
  adb shell "am broadcast -a com.mammonrn.phoneaikiosk.TEST_FOCUS -p com.mammonrn.phoneaikiosk.debug --es kind transient|duck|loss --ei ms 3000"
  ```
  ต้องได้ `KioskVlc: vlc: focus lost for a moment` → `focus back` (เล่นต่อเอง) / `ducked` → `focus back` / `focus lost` + `KioskMusic: state paused` (ไม่เล่นต่อเอง) และ `dumpsys audio` ส่วน focus stack มี client `VlcDeck`
- **จาร์วิสพูดทับไฟล์ VLC:** `TEST_PERFORM --ez as_turn true` ระหว่างเพลง VLC → `dumpsys audio` player ของแอปเป็น `paused` ระหว่างคำถาม แล้ว `started` หลังจบ (อย่าใช้คำสั่ง `quieter` ซ้ำ ๆ มันลดเสียงจริง)
- **แถบสมดุล:** ไฟล์ VLC → แถบไม่มีปุ่มเลื่อน จาง แตะแล้วบรรทัดชื่อเพลงขึ้น "ปรับเสียงซ้ายขวาไม่ได้…" และค่า `balance` ใน `shared_prefs/music.xml` ไม่เปลี่ยน / ไฟล์ Media3 → แถบกลับปกติ
- **หน้าเพิ่มไฟล์:** ติ๊ก 2 → ยกเลิก → เปิดใหม่ ทุกตัวเลขเป็น 0 / เพิ่มจริงแล้วเปิดใหม่ ทุกตัวเลขเป็น 0
- **กล้อง:** ตรวจด้วย log เท่านั้น **ห้ามแคปตอนกล้องเปิด** `KioskCamera: camera bound lens=back|front`, `photo saved ... exists=true`, ออกจากหน้า `camera closed` รูปอยู่ `DCIM/Kiosk` ตัวจัดการไฟล์เห็น และ "ดูรูปล่าสุด" เปิด `ImageViewerActivity`
- **บันทึกเสียง:** `KioskRec: mic handed to recorder`, `wake pause on source=recorder` ระหว่างอัด Hey Jarvis ต้องไม่มี `wake word detected` หลังหยุด `wake pause off` แล้ว Hey Jarvis จับได้ทันที ไฟล์ `.ogg` ใน `Recordings/Kiosk` เปิดจากตัวจัดการไฟล์เล่นในเครื่องเล่นเพลงแบบไฟล์เดียว
- **Drive (ยังไม่มีค่าจาก Poom):** แตะ "Google Drive" ในตัวจัดการไฟล์ → "ยังไม่ได้เชื่อมต่อ Google Drive" พร้อมเหตุผล ไม่ค้าง แตะ "เชื่อมต่อ" ไม่เปิดอะไร lock task ยัง LOCKED log `KioskDrive: consent needed by=com.google.android.gms`
- **ปุ่มปิด:** เปิดแต่ละแอปจากแผงควบคุมแล้วกด X → กลับ `SettingsActivity` / Drive → X → `FilesActivity` / การ์ดเพลงหน้าแรก → X → `MainActivity` / LOCKED ตลอด
- **สวิตช์วัดเสียงบี๊บ (debug, สำหรับ Poom พูดเอง):** `TEST_BEEP --es value off|on` ปิด/เปิดเสียงติ๊ดตอนได้ยินคำปลุก · `TEST_KEEP_CAPTURE --es value on|off` เก็บเสียงคำถามล่าสุดไว้ที่ `files/last_capture.wav` (ทับทุกครั้ง, off = ลบไฟล์) ดึงด้วย `adb exec-out run-as com.mammonrn.phoneaikiosk.debug cat files/last_capture.wav > last_capture.wav` ทั้งสองสวิตช์หายเมื่อแอปเริ่มใหม่
- **การวัดด้วยเสียงสังเคราะห์ใช้ไม่ได้ (2026-09-25):** เสียงไทย Pattara จากลำโพงคอมถูกถอดเพี้ยนทั้งประโยค (เช่น "แม่ฟ้าหลวง…", "ดูกล้อง") ไม่ว่าจะเปิดหรือปิดบี๊บ และการพูดต่อจาก "Jarvis" ทันที (เว้น 30 ms) ทำให้ทั้งคำสั่งต่ำกว่าเกณฑ์เสียงพูด → `no-speech-after-wake` จึงต้องวัดด้วยเสียงคนจริงเท่านั้น

## v0.62.0 — โน้ต / รายการซื้อของ (แอป + broker)

**ลำดับติดตั้ง: APK ก่อน แล้วค่อย deploy broker** มือถือรุ่นเก่าไม่รู้จัก `note_add`
ถ้า broker ขึ้นก่อน คำว่า "เพิ่ม … แล้วครับ" จะถูกพูดทั้งที่ไม่ได้เพิ่ม
deploy broker ตามหัวข้อ "deploy — ต้องรัน install.sh เสมอ" (Poom รันเองบน VPS แล้ว restart บริการ)

**หน้าจอ (แผงควบคุม → ในบ้านและใช้บ่อย → "โน้ต"):** เปิดมาที่ "รายการซื้อของ"
- พิมพ์ "นม" กด "เพิ่ม" → ขึ้น `เพิ่ม “นม” แล้ว` ช่องยังเปิดรอบรรทัดถัดไป / พิมพ์ "นม" ซ้ำ → "มีอยู่ในรายการนี้แล้ว" ใต้ช่อง ไม่ล้างที่พิมพ์
- แตะช่องสี่เหลี่ยม → ☑ และมีเส้นขีดทับ ย้ายไปอยู่ใต้ "ติ๊กแล้ว (1)" / แตะอีกครั้ง → กลับขึ้นไปที่เดิม
- แตะข้อความ → หน้าแก้ไข: แก้แล้ว "บันทึก" / "เอาออกจากรายการ" ออกทันที ไม่ถามและไม่ยืนยันตัวตน
- "ล้างที่ติ๊กแล้ว" → เหลือแต่ที่ยังไม่ติ๊ก ลำดับเดิม
- "◄ รายการทั้งหมด" → "สร้างรายการใหม่" ตั้งชื่อ → เปิดรายการนั้น ล่างสุดมี [เปลี่ยนชื่อรายการ][ลบรายการนี้] → ถาม "ลบรายการ … ใช่หรือไม่" ในที่เดิม → [ลบรายการ] ลบจริง / [ยกเลิก] ไม่ลบ รายการซื้อของและโน้ตไม่มีปุ่มลบ
- X → กลับแผงควบคุม / Back ถอยทีละหน้า / LOCKED ตลอด / ปิดแอปแล้วเปิดใหม่ ทุกบรรทัดและติ๊กยังอยู่

**คำสั่งเสียง (debug พิมพ์แทนพูดได้):**
```
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_ASK --es text "'เพิ่ม นม ในรายการซื้อของ'" -p com.mammonrn.phoneaikiosk.debug
```
| พูด | ต้องได้ |
|---|---|
| "เพิ่ม นม ในรายการซื้อของ" / "จดว่าซื้อไข่" / "ซื้อ น้ำปลา ด้วย" | "เพิ่ม … ในรายการซื้อของแล้วครับ" และบรรทัดอยู่ในหน้าจอทันที (ถ้าเปิดค้างไว้) |
| พูดของเดิมซ้ำ (ยังไม่ติ๊ก) | "มี … ในรายการซื้อของอยู่แล้วครับ" ไม่มีบรรทัดซ้ำ |
| "จดว่า พรุ่งนี้จ่ายค่าไฟ" | "จดไว้ในโน้ตแล้วครับ" อยู่ในรายการ "โน้ต" |
| "อ่านรายการซื้อของ" / "ต้องซื้ออะไรบ้าง" | อ่านเฉพาะที่ยังไม่ติ๊ก ตามลำดับ ยาวเกินพูด "และอีก N อย่าง ดูได้ที่หน้าโน้ตครับ" / ว่าง "รายการซื้อของยังว่างอยู่ครับ" |
| "ลบนมออกจากรายการซื้อของ" | "ลบหรือขีดรายการด้วยเสียงยังทำไม่ได้ครับ …" ไม่มีอะไรเปลี่ยน |
| "ซื้ออะไรดี" / "เพิ่มนัดหาหมอพรุ่งนี้บ่ายสอง" / "ตั้งปลุกหกโมง" | ไม่เข้าโน้ต (ไปทางเดิม: โมเดล / เพิ่มนัด / ปลุก) |

**log (ห้ามมีข้อความของบรรทัดหรือชื่อรายการ):**
```
adb logcat -s KioskVoice:I KioskNotes:I     # action note_add list=shopping added=true / action note_read list=shopping pending=3
sudo journalctl -u kiosk-broker -n 50 | grep "intent device"   # บน VPS: ท้ายบรรทัด notes=add:shopping:named | read:shopping | question | no-note-word …
```
