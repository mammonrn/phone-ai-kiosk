# Google Home — ขั้นตอนเตรียมและความปลอดภัย

การตัดสินใจของ Poom (2026-09-23): ใช้ Google Home คุมไฟบ้าน (Tuya และ eWeLink พักไว้), เก็บ SDK ไว้ใน repo นี้หลังเปลี่ยนเป็นส่วนตัว, minSdk เป็น 29, บัญชี kiosk เป็น Admin ของบ้านได้

รอบแรกเป็นแบบ **อ่านอย่างเดียว** แสดงรายชื่ออุปกรณ์ ประเภท และสถานะเปิด/ปิด ยังไม่มีการสั่งเปิดปิด
การสั่งในรอบถัดไปต้องผ่าน `home/HomeGate.kt` ซึ่งไม่ยอมให้คุมกล้อง ประตู กุญแจ โรงรถ รั้ว ม่าน และ alarm เด็ดขาด

## 0. ก่อนเปลี่ยน repo เป็นส่วนตัว — ต้องแก้เรื่องโควตาก่อน

repo สาธารณะใช้ GitHub Actions ฟรีไม่จำกัด แต่ repo ส่วนตัวมีโควตา (docs.github.com, Actions billing):

| | GitHub Free | GitHub Pro |
|---|---|---|
| นาที Actions ต่อเดือน | 2,000 | 3,000 |
| พื้นที่เก็บ artifact | 500 MB | 1 GB |
| เกินโควตา (ถ้าไม่มีบัตร) | ถูกบล็อก CI หยุดจนต้นเดือนถัดไป | เหมือนกัน |
| ราคาส่วนเกิน | Linux $0.006/นาที, พื้นที่ $0.25/GB-เดือน | เหมือนกัน |

ที่ใช้อยู่จริง (วัดจากประวัติ workflow 21–23 ก.ย. 2569): ราว 256 นาทีใน 3 วัน (~85 นาที/วัน ช่วงพัฒนาหนัก) และ artifact APK 91 ชิ้นรวม **1,056 MB** (APK ละ ~45 MB เก็บ 30 วัน)
ถ้าเปลี่ยนเป็นส่วนตัวตอนนี้ พื้นที่ artifact เกินโควตาทันที และนาทีจะเกินภายในราว 3 สัปดาห์ในจังหวะพัฒนาแบบนี้ ต้องตกลงวิธีลดก่อน (ดูรายงาน)

## 1. เปลี่ยน repo เป็นส่วนตัว (หลังตกลงเรื่องโควตาแล้ว)

1. เปิด https://github.com/mammonrn/phone-ai-kiosk → **Settings**
2. เลื่อนลงล่างสุด **Danger Zone** → **Change repository visibility** → **Change visibility** → **Make private**
3. พิมพ์ชื่อ repo ยืนยัน
4. secret `ANDROID_DEBUG_KEYSTORE_BASE64` ยังอยู่ workflow ทั้งสองยังทำงาน การดาวน์โหลด artifact ของ Claude ใช้ `gh` ที่ล็อกอินเป็นเจ้าของ repo จึงยังใช้ได้

## 2. ดาวน์โหลด Home APIs SDK 1.10.1

1. เปิด https://developers.home.google.com/apis/android/sdk แล้วล็อกอินด้วยบัญชี Google ของ Poom (ไม่ใช่บัญชี kiosk)
2. **อ่านเงื่อนไขในหน้าดาวน์โหลดก่อนกดรับ** ถ้าเงื่อนไขห้ามเก็บ SDK ไว้ใน repo แม้แบบส่วนตัว ให้หยุดและบอก Claude
3. ดาวน์โหลด `home.android.sdk_GHP_1_10_1` แตกไฟล์ จะได้ `play-services-home-17.0.0.aar` และ `play-services-home-types-17.0.0.aar`

## 3. สร้าง OAuth client แบบ Android

ใช้โปรเจกต์ `kiosk-personal` ที่สร้างไว้แล้ว (แยกจากโปรเจกต์ TTS)

1. console.cloud.google.com → เลือกโปรเจกต์ → **Google Auth Platform → Clients → Create client**
2. Application type: **Android**
3. Package name: `com.mammonrn.phoneaikiosk.debug`
4. SHA-1 certificate fingerprint: `68:CC:5C:12:8F:58:93:3D:45:F9:65:99:8B:57:24:EA:0A:F1:E5:5F`
   - คือใบรับรองของ APK ที่ CI เซ็นด้วย keystore ที่ปักไว้ (ไม่ใช่ความลับ) ถ้าวันหนึ่งเปลี่ยน keystore ต้องแก้ค่านี้ด้วย
5. **Create** — client แบบ Android ไม่มี secret และไม่ต้องดาวน์โหลดไฟล์ใส่แอป

## 4. เพิ่มบัญชี kiosk เป็นผู้ใช้ทดสอบ

1. Google Auth Platform → **Audience** → **Test users** → **Add users**
2. ใส่อีเมลของบัญชี Google ที่ล็อกอินอยู่บน A07 → Save
   - Home APIs จำกัดผู้ใช้ทดสอบ 100 คนจนกว่า Google จะเปิดให้ลงทะเบียน Developer Console

## 5. ตั้งบัญชี kiosk เป็น Admin ในบ้าน Google Home

1. บนมือถือของ Poom เปิดแอป **Google Home** → เลือกบ้าน → **Settings (ตั้งค่า) → Household (สมาชิกในบ้าน) → เพิ่ม (+) → Invite a person**
2. ใส่อีเมลบัญชี kiosk → เลือกสิทธิ์ **Admin** → ส่ง
3. บน A07 (ออกจาก kiosk ด้วยการแตะ 10 ครั้งก่อน) เปิดแอป Google Home ด้วยบัญชี kiosk → รับคำเชิญ
4. เสร็จแล้วติดตั้ง APK ใหม่หรือรีสตาร์ท เพื่อกลับเข้าโหมด kiosk

## 6. อัปโหลดไฟล์ SDK เข้า repo (หลังข้อ 1 เท่านั้น)

**ห้ามทำก่อน repo เป็นส่วนตัว** — ไฟล์ที่ push ขึ้น repo สาธารณะแล้วถือว่าเผยแพร่ไปแล้ว ลบทีหลังก็ยังอยู่ในประวัติ

```powershell
cd C:\Users\Asus\phone-ai-kiosk
git pull
Copy-Item "$HOME\Downloads\home.android.sdk_GHP_1_10_1\play-services-home-17.0.0.aar" app\libs\
Copy-Item "$HOME\Downloads\home.android.sdk_GHP_1_10_1\play-services-home-types-17.0.0.aar" app\libs\
gh repo view --json visibility   # ต้องขึ้น "PRIVATE" ก่อนขั้นถัดไป
git add app/libs/*.aar
git commit -m "Google Home APIs SDK 1.10.1 (private repo)"
git push
```

หรือส่งไฟล์ให้ Claude วางและ push ให้ก็ได้ Claude จะตรวจว่า repo เป็น PRIVATE ก่อน push ทุกครั้ง

## 7. อนุญาตสิทธิ์ครั้งแรก (ครั้งเดียว)

หน้าขอสิทธิ์เป็นหน้าจอของ Google ซึ่งโหมด kiosk อาจบล็อก จึงทำตอนออกจาก kiosk **โดยไม่แก้ค่า lock task**:

1. แตะมุมขวาล่าง 10 ครั้ง ออกจากโหมด kiosk
2. เปิดแผงควบคุม → Google Home → **เชื่อมต่อ** → เลือกบัญชี kiosk → เลือกบ้าน → อนุญาต
3. ติดตั้ง APK ใหม่หรือรีสตาร์ท เพื่อกลับเข้าโหมด kiosk
4. ทุกครั้งที่แอปเริ่ม แอปจะตรวจว่าสิทธิ์ยังอยู่ (ตามคำแนะนำของ Google)

## 8. ความปลอดภัย

**บัญชี kiosk เป็น Admin เข้าถึงอะไรได้บ้าง:**
- เห็นและคุมอุปกรณ์ทุกตัวในบ้านนั้นผ่านแอป Google Home ได้เท่าที่ Admin ทำได้: เพิ่ม/ลบอุปกรณ์ เชิญหรือลบสมาชิก แชร์อุปกรณ์ให้บริการภายนอก ดูกล้อง (ถ้ามี) และประวัติ
- **แอป kiosk เอง** ได้สิทธิ์เฉพาะบ้านที่อนุญาต และด้วยโค้ดของเราจะอ่านอย่างเดียวในรอบนี้ รอบถัดไปสั่งได้เฉพาะไฟและปลั๊กที่อยู่ใน allowlist ผ่าน HomeGate
- สิทธิ์ Admin ของบัญชีกว้างกว่าที่แอปใช้ ความเสี่ยงคือถ้ามีคนได้เครื่องไปแล้วออกจากโหมด kiosk ได้ เขาจะเปิดแอป Google Home ด้วยบัญชีนี้ได้
- บัญชี kiosk ควรเป็นบัญชีแยก ไม่มีอีเมลหรือข้อมูลส่วนตัวของ Poom (ตรงกับที่ตั้งไว้แล้ว)

**ถ้าเครื่องหาย ทำทันทีตามลำดับ:**
1. แอป Google Home บนมือถือ Poom → Settings → Household → เลือกบัญชี kiosk → **Remove** (ตัดการเข้าถึงบ้านทั้งหมด)
2. myaccount.google.com ของ**บัญชี kiosk** → Security → Your devices → ออกจากระบบ A07 และเปลี่ยนรหัสผ่านบัญชี kiosk
3. บน VPS: `$KB revoke-token kiosk-a07` และ `$KB google-disconnect` (ตาม server/INSTALL.md)
4. ถ้าต้องการถอนเฉพาะแอป: myaccount.google.com → Data & privacy → Third-party apps & services หรือแอป Google Home → Settings → Linked apps
