# คู่มือทดสอบเฟส 1 บน Galaxy A07

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

`awake=on` แปลว่าจอจะไม่ดับเพราะกำลังเสียบชาร์จอยู่ ถ้าถอดสายชาร์จจะเปลี่ยนเป็น
`awake=off` ภายในไม่กี่วินาที แล้วจอจะดับตามการตั้งค่าปกติของเครื่อง

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
