# โมเดลคำปลุก "สายฝน" — สิ่งที่พร้อมแล้ว และสิ่งที่ต้องให้ Poom ตัดสิน

## ✅ อนุมัติแล้ว: Google Colab ชั้นฟรี

Poom เลือก **Colab ฟรี** และอนุมัติค่า Google TTS สำหรับสร้างชุดเสียง
**ไม่เกิน $0.50** เทรนบนมือถือหรือ VPS ห้ามทำ

สถานะตอนนี้:

| ขั้น | สถานะ |
|---|---|
| เครื่องมือสร้างชุดเสียงบน VPS | ✅ พร้อม (`wake-samples`) |
| ชุดเสียง 1,050 คลิป | ⏳ รอ Poom รันบน VPS — $0.2952 จากเพดาน $0.50 |
| Colab notebook | ✅ พร้อม [`wakeword/train_saifon_colab.ipynb`](wakeword/train_saifon_colab.ipynb) — **แก้แล้ว ต้องเปิดใหม่** ดูหัวข้อถัดไป |
| เทรนจริง | ⏳ รอ Poom |
| ต่อเข้าแอป Android | ⏳ รอไฟล์โมเดล |
| วัดผลบน A07 | ⏳ เครื่องมือพร้อม ยังไม่มีตัวเลข |

## 🔴 notebook รุ่นแรกติดตั้งไม่ผ่าน — แก้แล้ว ต้องเปิดใหม่

### อาการ

```
ERROR: Could not find a version that satisfies the requirement
       tflite-runtime<3,>=2.8.0; platform_system == "Linux" (from openwakeword)
       (from versions: none)
```

### สาเหตุ ✅ ตรวจจาก PyPI และจาก source ของ v0.6.0 แล้ว

openWakeWord v0.6.0 ประกาศ `tflite-runtime` เป็น **dependency หลัก ไม่ใช่ extra**:

```
Requires-Dist: tflite-runtime <3,>=2.8.0 ; platform_system == "Linux"
```

`pip install` บน Linux จึงพยายามลงมันเสมอ แต่ **tflite-runtime ออกรุ่นสุดท้ายคือ
2.14.0 เมื่อ 3 ต.ค. 2023 และ wheel สูงสุดคือ cp311** ไล่ดูทุกรุ่นบน PyPI แล้ว
ไม่เคยมี wheel สำหรับ Python 3.12 ขึ้นไปเลยสักรุ่น Colab ตอนนี้ใช้ Python 3.12
จึงได้ `(from versions: none)` แปลว่า "ไม่มีให้ลงเลย" ไม่ใช่เน็ตมีปัญหา
ไม่ใช่ pip พัง และรอไปก็ไม่หายเอง

### ทำไมข้ามมันได้ ✅ ตรวจจาก source

| จุด | ต้องใช้ tflite ไหม |
|---|---|
| `import openwakeword` | ไม่ — `import tflite_runtime` อยู่ใต้ `if` ไม่ใช่ระดับไฟล์ |
| ดึง feature ตอนเทรน | ไม่ — `AudioFeatures` default เป็น `inference_framework="onnx"` และ `train.py` เรียกโดยไม่ส่งค่านี้ |
| ใช้งานบนมือถือ | ไม่ — เราใช้ onnxruntime มาตั้งแต่ต้น |
| `convert_onnx_to_tflite()` | ใช้ — แต่เราไม่ต้องการ `.tflite` เลย จึงปิดทิ้ง |

ทดลองจริงแล้ว: ลง `--no-deps` แล้ว `import openwakeword` ผ่าน โดยไม่มี
tflite_runtime ในเครื่อง และ `AudioFeatures` รายงาน framework เป็น `onnx`

### แก้อะไรไปบ้าง

1. **เซลล์ติดตั้ง** เปลี่ยนเป็น `pip install --no-deps` แล้วระบุ dependency
   ที่ training ใช้จริงเอง ไล่มาจาก import ของ v0.6.0 ทีละไฟล์
2. **เซลล์ตรวจความพร้อมใหม่ (ขั้น 1ก)** import ทั้ง 21 ตัวรวม
   `openwakeword.train` แล้วหยุดทันทีถ้าไม่ครบ — จะได้ไม่เสียเวลาโหลด dataset
   หลายกิกะไบต์แล้วมาพังทีหลัง และตรวจ GPU **หลัง** ติดตั้ง เพราะ pip
   อาจเผลอสลับ torch เป็น build แบบ CPU แล้ว T4 หายเงียบๆ
3. **🔴 บั๊กที่สองที่เจอระหว่างทาง** `train.py` ไม่เคยเรียก `download_models()`
   และทั้ง wheel และ repo **ไม่ได้แถม** `melspectrogram.onnx` กับ
   `embedding_model.onnx` มาด้วย (ตรวจทั้งสองที่แล้ว) ถ้าแก้แค่เรื่องติดตั้ง
   มันจะไปพังตอนสร้าง feature แทน — notebook จึงโหลดเองในขั้น 6
4. **🔴 บั๊กที่สาม** ท้าย `train.py` เรียก `convert_onnx_to_tflite()` แบบ
   **ไม่มีเงื่อนไข** ทันทีหลัง export `.onnx` เสร็จ และฟังก์ชันนั้นต้องการ
   `tensorflow-cpu==2.8.1` กับ `onnx-tf==1.10.0` (ปักรุ่นไว้ตั้งแต่ปี 2022
   ลง Python 3.12 ไม่ได้) ถ้าปล่อยไว้จะได้ traceback ยาวๆ **หลังเทรนเสร็จแล้ว**
   notebook จึงปิดฟังก์ชันนั้นทิ้งพร้อม assert ว่าแก้ติดจริง
5. **🔴 บั๊กที่สี่ — CI จับได้เอง ผมไม่ได้เจอจากการอ่านโค้ด**
   `openwakeword.data` ใช้ `acoustics.generator.noise()` สร้างเสียงรบกวนตอน
   augment แต่ `import acoustics` ทำ `from scipy.special import sph_harm`
   ซึ่ง **scipy เอาออกใน 1.17** (ทดลองแล้ว: มีใน 1.16.2 หายใน 1.17.0)
   และ `acoustics` ออกรุ่นสุดท้ายเมื่อ ก.ค. 2022 จึงไม่มีรุ่นใหม่มาแก้
   ผลคือ `import openwakeword.train` พังทั้งก้อน ไม่ใช่แค่ acoustics เจ้าเดียว
   แก้ด้วยการตรึง `scipy<1.17` ทดลองแล้วได้ 1.16.3 และ `noise()` ทำงานครบทั้ง
   5 สีที่ openWakeWord สุ่มใช้ (white, pink, blue, brown, violet)
   🔶 ยังไม่ทราบว่า Colab ติด scipy รุ่นไหนมาให้ ถ้าต่ำกว่า 1.17 อยู่แล้ว
   บรรทัดนี้ก็ไม่ทำอะไร ถ้าสูงกว่า มันจะ downgrade ให้
6. **CI ใหม่** [`wakeword-deps.yml`](.github/workflows/wakeword-deps.yml)
   ลงและ import จริงบน Ubuntu + Python 3.12 ทุกครั้งที่แตะโฟลเดอร์ `wakeword/`
   **อ่านคำสั่ง pip และรายชื่อโมดูลออกมาจากตัว notebook เอง** ไม่ได้ก๊อปรายการมาไว้
   ซ้ำ — รายการที่ก๊อปมาคือรายการที่เขียวทั้งที่ notebook พังแล้ว
   ไม่โหลด dataset ไม่เทรนจริง และไม่รันบน VPS เพราะ RAM ไม่พอ

   **CI คุ้มค่าตั้งแต่รอบแรก**: รอบแรกที่รันมันแดง และสิ่งที่มันจับได้คือข้อ 5
   ข้างบน ซึ่งผมอ่าน source แล้วมองไม่เห็น เพราะมันไม่ได้อยู่ในโค้ดของ
   openWakeWord แต่อยู่ใน dependency ของ dependency อีกทีหนึ่ง

### คำเตือน protobuf — ✅ ตรวจแล้วว่าไม่กระทบ

คำเตือนที่เห็นจาก `ydf`, `grpcio-status`, `google-ai-generativelanguage` มาจาก
package ที่ **Colab ติดมาให้เอง** ซึ่งอยากได้ protobuf คนละช่วงกัน
source ของ openWakeWord ไม่อ้างถึง protobuf เลยสักบรรทัด (grep แล้ว) และเส้นทาง
การเทรนไม่ import `google.protobuf` ที่ไหน จึงเป็นคำเตือนที่ไม่กระทบการเทรน

แต่ทำให้มันไม่ต้องโผล่ตั้งแต่แรกดีกว่า จึง **ไม่ลง `onnx`** ด้วย — `onnx` รุ่นใหม่
บังคับ `protobuf>=6.31.1` ซึ่งเป็นตัวที่ไปดัน protobuf ของ Colab
และ training ไม่ต้องใช้มันอยู่แล้ว เพราะ `train.py` export ด้วย
`torch.onnx.export` ซึ่งต้องการแค่ torch

🔶 **ตรงนี้ผมทำต่างจากที่สั่งไว้** Poom บอกให้เซลล์ตรวจความพร้อม import `onnx`
ด้วย แต่การลง `onnx` คือสาเหตุของคำเตือน protobuf ที่สั่งให้จัดการพอดี
ผมจึงตรวจโมเดลด้วย `onnxruntime` แทน ซึ่งตรงกว่าเพราะเป็น runtime ตัวเดียวกับที่
มือถือใช้จริง (เซลล์ขั้น 7 ใช้ `onnxruntime` โหลดโมเดลอยู่แล้ว) **ถ้าอยากได้
`onnx` จริงๆ บอกได้ เพิ่มกลับให้ในบรรทัดเดียว**

### ‼️ Poom ต้องทำอะไร

**ปิดแท็บ Colab เดิมทิ้ง** แล้วเปิดใหม่จาก main:

https://colab.research.google.com/github/mammonrn/phone-ai-kiosk/blob/main/wakeword/train_saifon_colab.ipynb

Colab **cache notebook ที่เปิดค้างไว้** ถ้ากด Runtime > Restart เฉยๆ จะยังได้
เซลล์เก่าที่พัง ต้องเปิด URL ใหม่ แล้ว:

1. Runtime > Change runtime type > **T4 GPU**
2. Runtime > **Disconnect and delete runtime** (ล้างของที่ลงค้างไว้รอบก่อน)
3. รันจากเซลล์แรกใหม่ทั้งหมด
4. **ขั้น 1ก ต้องขึ้น "พร้อมเทรน ไปขั้น 2 ได้"** ถ้าไม่ขึ้น หยุดแล้วส่ง output มาให้ผม

❓ **ยังไม่ทราบ**: ผมรัน Colab เองไม่ได้ จึงยังไม่มีหลักฐานว่า notebook ที่แก้แล้ว
รันผ่านบน Colab จริง CI พิสูจน์ได้แค่ว่า Ubuntu + Python 3.12 ลงและ import ผ่าน
ซึ่งเป็นจุดที่พังพอดี แต่ไม่ใช่ Colab เอง

## 🔴 รอบที่ 2 — ขั้น 4 ล้ม เพราะ datasets เปลี่ยนรูปแบบข้อมูลเสียง

ขั้น 1ก ถึงขั้น 3 ผ่านหมด (torch 2.11.0+cu128, T4, positive_train 390 /
positive_test 60 / negative_train 512 / negative_test 88) แล้วขั้น 4 ตาย:

```
TypeError: 'torchcodec.decoders.AudioDecoder' object is not subscriptable
  ที่บรรทัด  name = row['audio']['path'].split('/')[-1]
```

### สาเหตุ ✅ อ่านจาก source ของ datasets แล้ว ไม่ได้เดา

`datasets` 5.x คืน `torchcodec.decoders.AudioDecoder` แทน dict แต่จุดที่สำคัญคือ
**มันไม่ได้พังทั้งหมด** ใน `datasets/features/_torchcodec.py` มี wrapper:

```python
class AudioDecoder(_AudioDecoder):
    def __getitem__(self, key):
        if key == "array":          ...   # ยังใช้ได้
        elif key == "sampling_rate": ...  # ยังใช้ได้
        else:
            raise TypeError("'torchcodec.decoders.AudioDecoder' object is not subscriptable")
```

แปลว่า `row['audio']['array']` **ยังทำงานอยู่** ส่วน `row['audio']['path']`
คือตัวเดียวที่โยน TypeError ออกมา — ตรงกับข้อความ error ที่ได้เป๊ะๆ
เซลล์เดิมใช้ `['path']` แค่เพื่อ**ตั้งชื่อไฟล์** ซึ่งเป็นสิ่งที่ไม่จำเป็นเลย

### แก้อะไรไปบ้าง

1. **เซลล์ใหม่ "ขั้น 3ก"** มีแต่ฟังก์ชัน `audio_16k_mono()` กับ `to_int16()`
   ไม่มี side effect รองรับทุกรูปแบบที่ datasets เคยคืนมา:
   AudioDecoder (เรียก `get_all_samples()` ตัวจริง ไม่ใช่ shim), dict แบบเก่า
   `{'array','sampling_rate'}`, dict ที่ยังไม่ decode `{'bytes','path'}`,
   และ path/bytes ดิบ **ไม่พึ่งรูปแบบใดรูปแบบเดียว**
2. **resample จริง** เซลล์เดิมเขียน header เป็น `16000` แบบฮาร์ดโค้ดโดยไม่เคยดู
   ว่าเสียงต้นทางเป็น rate เท่าไร ถ้า dataset ไม่ใช่ 16 kHz พอดี impulse response
   ทุกไฟล์จะเพี้ยนแบบเงียบๆ ตอนนี้ resample ด้วย `scipy.signal.resample_poly`
   (ไม่เพิ่ม dependency) และรวมเป็น mono จริง
3. **`to_int16()` clip ก่อนแปลง** `resample_poly` แกว่งเกิน 1.0 ได้ที่ขอบสัญญาณ
   และ `(x * 32767).astype(np.int16)` จะ **wrap รอบ** กลายเป็นเสียงแตกดังลั่น
   ความเสียหายแบบนี้นับจำนวนไฟล์ไม่เจอ ต้องฟังเท่านั้นจึงจะรู้
4. **ชื่อไฟล์จากตัวนับ** `rir_00000.wav` / `bg_00000.wav` ไม่ซ้ำโดยโครงสร้าง
   และไม่ต้องมี path จาก dataset เลย
5. **`mkdir` ย้ายไปไว้บนสุด** ✅ ยืนยันว่า Poom วิเคราะห์ถูก: `mkdir -p
   /content/background_clips` เดิมอยู่ **ท้าย** เซลล์ขั้น 4 หลังลูป RIR พอลูปล้ม
   บรรทัดนั้นไม่ได้รัน เซลล์ถัดไปจึงตายด้วย `FileNotFoundError` ตามมา
   ทำให้ดูเหมือนพังสองที่ทั้งที่พังที่เดียว ตอนนี้สร้างทั้งสองโฟลเดอร์ก่อน
   ทำอะไรที่ล้มได้ และเซลล์เสียงพื้นหลังก็ `mkdir` เองด้วย ไม่พึ่งเซลล์ก่อนหน้า
6. **ข้ามรายการที่อ่านไม่ได้ ไม่ให้ทั้งเซลล์ล้ม** นับ `เขียน / ข้าม / สั้นเกินไป`
   แยกกัน และพิมพ์สาเหตุ 3 รายการแรกให้ดู
7. **เซลล์ใหม่ "ขั้น 4ก"** หยุดถ้า RIR = 0 ไฟล์ หรือเสียงพื้นหลัง = 0 ไฟล์
   หรือ `validation_set_features.npy` เล็กกว่า 100 KB (ไฟล์เล็กแบบนั้นมักเป็น
   หน้า error ที่ถูกบันทึกเป็นไฟล์) — **ก่อน**โหลดไฟล์ feature 2 GB
8. **CI เป็น matrix 3.12 + 3.13** และเทสต์ตัว parser จริง

### 🔴 CI เคยทดสอบ Python ผิดเวอร์ชัน

Colab ใช้ **Python 3.13** ไม่ใช่ 3.12 (เห็นจาก path ใน traceback:
`/usr/local/lib/python3.13/dist-packages`) รอบก่อนผมเขียน CI ไว้ที่ 3.12
และมันเขียว — เพราะวิธีแก้ `--no-deps` ไม่ขึ้นกับเวอร์ชัน Python จึงใช้ได้ทั้งคู่
แต่ CI ที่ทดสอบเวอร์ชันผิดคือ CI ที่โชคดี ไม่ใช่ CI ที่ถูก ตอนนี้รันทั้ง
3.13 (ตรงกับ Colab) และ 3.12 (ตรงกับ VPS)

CI เทสต์ parser กับทุกรูปแบบ รวม **AudioDecoder ตัวจริงจาก torchcodec**
ไม่ใช่ของปลอม และยืนยันว่า `wrapper['path']` **ยังโยน TypeError อยู่** —
ถ้าวันหนึ่ง datasets เลิกโยน แปลว่าสมมติฐานของเทสต์เปลี่ยน ต้องกลับมาอ่านใหม่
ถ้า torchcodec ลงไม่ได้ CI จะ **แดง** ไม่ใช่ข้ามเงียบๆ เพราะเทสต์ที่ข้ามเคสที่
ทำให้ production ล่มคือเทสต์ที่เขียวหลอก

### torchcodec ไม่อยู่ในบรรทัด pip ของ notebook โดยตั้งใจ

มันเป็น extension ที่คอมไพล์คู่กับ torch เวอร์ชันหนึ่งๆ ถ้าให้ pip ลงเอง
pip อาจลาก torch เวอร์ชันอื่นมาแล้ว **CUDA/T4 หาย** — กับดักเดียวกับ torch
Colab ติดคู่ที่เข้ากันมาให้แล้ว (หลักฐาน: datasets คืน AudioDecoder ได้)
จึงอยู่ในรายการ "ตรวจ" ของขั้น 1ก ไม่ใช่รายการ "ติดตั้ง"

### คำเตือน Hugging Face unauthenticated — ไม่ต้องทำอะไร

✅ ทั้งสอง dataset (`davidscripka/MIT_environmental_impulse_responses` และ
`agkphysics/AudioSet`) เป็น public การไม่ล็อกอินมีผลแค่ rate limit ที่หย่อนกว่า
**ไม่ต้องใส่ HF_TOKEN** และไม่ควรใส่ เพราะ token ใน notebook คือ token ที่หลุด
ไปอยู่ใน output cell หรือใน repo ได้ ถ้าวันหนึ่งโดน rate limit จริงค่อยคุยกัน

### ‼️ Poom ต้องทำอะไร — รอบนี้ต้องอัปโหลด zip ใหม่ด้วย

1. **ปิดแท็บ Colab เดิม** แล้วเปิดใหม่จาก main (Colab cache notebook ที่เปิดค้างไว้
   กด Restart เฉยๆ จะยังได้เซลล์เก่า):

   https://colab.research.google.com/github/mammonrn/phone-ai-kiosk/blob/main/wakeword/train_saifon_colab.ipynb

2. Runtime > Change runtime type > **T4 GPU**
3. Runtime > **Disconnect and delete runtime**
4. **‼️ ต้องอัปโหลด `wake-samples.zip` ใหม่** — runtime ใหม่คือเครื่องใหม่
   `/content` ว่างเปล่า ไฟล์ที่อัปไว้รอบก่อนหายไปพร้อม runtime เดิม
   **ไม่ต้องสร้าง zip ใหม่บน VPS และไม่เสียเงินเพิ่ม** ใช้ไฟล์เดิมที่ดาวน์โหลด
   ไว้แล้วได้เลย (ถ้ายังอยู่บนเครื่อง Windows)
5. รันจากเซลล์แรก แล้วต้องผ่านสองด่านนี้:
   - **ขั้น 1ก** → "พร้อมเทรน ไปขั้น 2 ได้"
   - **ขั้น 4ก** → "ข้อมูลเสียงครบ ไปขั้น 5 ได้"

   ถ้าด่านไหนไม่ผ่าน หยุดแล้วส่ง output มาให้ผม อย่ารันต่อ

❓ **ยังไม่ทราบ**: ผมรัน Colab เองไม่ได้ CI พิสูจน์ว่า parser รับ AudioDecoder
จริงได้บนทั้ง 3.12 และ 3.13 แต่ไม่ได้พิสูจน์ว่า `agkphysics/AudioSet` กับ
`MIT_environmental_impulse_responses` จะ stream ได้ราบรื่นบน Colab
(CI ห้ามโหลด dataset ใหญ่) — ขั้น 4ก มีไว้เพื่อจับกรณีนั้นก่อนเสียเวลา

## ทำไมถึงเลือก openWakeWord (ทวนจากรอบสำรวจ)

✅ **Porcupine ไม่รองรับภาษาไทย** — มีแค่ en/fr/de/it/ja/ko/zh/pt/es ต้องคุยฝ่ายขายเป็นเคสๆ
✅ **sherpa-onnx KWS มีแค่โมเดล zh / en / zh-en** ไม่มีไทย
✅ **openWakeWord เทรนจากเสียงสังเคราะห์ จึงไม่ผูกกับภาษา** — ป้อน TTS พูด "สายฝน"
ก็ได้โมเดลของคำนั้น Apache-2.0 ไม่มีคีย์ ไม่มีค่าบริการ ออกเป็น ONNX รันบน Android ได้

**ความต่างที่ทำให้ตัดสินใจได้:** Porcupine สร้างโมเดลจาก *ข้อความ* จึงต้องมีโมเดล
ภาษานั้นก่อน · openWakeWord เทรนจาก *เสียง* ภาษาจึงไม่เกี่ยว

## ชุดเสียง — สร้างบน VPS ไม่ใช่ใน Colab

คำสั่ง `wake-samples` สร้างชุดฝึกจาก **Google Chirp 3 HD ภาษาไทยทั้ง 30 เสียง**
(ชาย 16 หญิง 14 — คำปลุกต้องได้ยินจากใครก็ได้ในบ้าน ไม่ใช่แค่เสียงผู้ชาย)

| กลุ่ม | จำนวน | ที่มา |
|---|---|---|
| คำปลุก | 450 คลิป | 5 ประโยคพา × 30 เสียง × 3 ความเร็ว (0.9 / 1.0 / 1.2) |
| คำใกล้เคียงที่ต้องไม่ปลุก | 480 คลิป | 8 คำ × 30 เสียง × 2 ความเร็ว |
| ประโยคทั่วไปไม่มีคำปลุก | 120 คลิป | 12 ประโยค × 10 เสียง |
| **รวม** | **1,050 คลิป** | 9,840 ตัวอักษร = **$0.2952** |

คำใกล้เคียงที่เลือกมาจงใจให้ครอบวิธีที่ภาษาไทยชนกับชื่อนี้: พยางค์แรกเหมือน
(สายลม สายไฟ สายด่วน สายพาน) พยางค์หลังเหมือน (ฝนตก ฝนหยุด) สลับพยางค์ (ชายฝน)
และ**ต่างกันแค่วรรณยุกต์เดียว (สายฝัน)**

### ซื้อแค่ความหลากหลายที่ซื้อแล้วคุ้ม

จ่ายเงินซื้อ **ความเร็วการพูด** จาก Google เพราะ pace control ให้การออกเสียงที่
ต่างกันจริง ไม่ใช่การยืดไฟล์

**ไม่จ่าย**ค่าเสียงรบกวน เสียงก้องห้อง ระดับเสียง และระดับเสียงสูงต่ำ เพราะ
openWakeWord มี augmentation ที่ทำสิ่งเหล่านี้ฟรีอยู่แล้ว (ตรวจซอร์ส v0.6.0 แล้ว:
`PitchShift`, `SevenBandParametricEQ`, `TanhDistortion`, `BandStopFilter`,
`AddColoredNoise`, `AddBackgroundNoise`, `Gain`) การจ่ายเงินซื้อสำเนาที่ต่างกัน
แค่ระดับเสียงคือการเผาเงินเปล่า

### เพดานถูกบังคับก่อนยิงคำขอแรก

ระบบคิดราคาทั้งแผนล่วงหน้า บวกกับที่รอบก่อนหน้าใช้ไปแล้ว แล้วเทียบกับเพดาน
**ถ้าเกินจะหยุดโดยไม่ยิงอะไรเลย** — เพดานที่ตรวจทีหลังคือใบเสร็จ ไม่ใช่เพดาน

ทดสอบแล้ว: `--dry-run` ไม่ยิงเลย · เพดาน $0.10 ถูกปฏิเสธและไม่มีไฟล์ออกมา ·
รันซ้ำรอบที่สองถูกปฏิเสธเพราะรวมกันเป็น $0.5904

🔴 **งบ $0.50 ซื้อได้หนึ่งรอบเต็ม บวกเงินทอนราว $0.20 ไม่ใช่สองรอบ**
ถ้าโมเดลรอบแรกไม่ถึง 90% ทางเลือกคือรันเพิ่มบางส่วน (เฉพาะคำปลุก) หรือขอเพดานใหม่

## นำโมเดลเข้าแอปอย่างไร

เมื่อได้ `wakeword.onnx` จาก Colab แล้ว:

```powershell
# ส่งไฟล์เข้าพื้นที่ส่วนตัวของแอป วิธีเดียวกับ token
adb push wakeword.onnx /data/local/tmp/wakeword.onnx
adb shell "cat /data/local/tmp/wakeword.onnx | run-as com.mammonrn.phoneaikiosk.debug sh -c 'mkdir -p files && cat > files/wakeword.onnx'"
adb shell rm /data/local/tmp/wakeword.onnx
```

ชื่อไฟล์คือค่าคงที่ `NoModelDetector.MODEL_FILE` ในโค้ด จึงเปลี่ยนที่เดียว

ฝั่งโค้ดเหลืองานสองอย่าง:
1. เขียนคลาส implement `Detector` ที่โหลด ONNX แล้วรัน inference
2. เปลี่ยนบรรทัดเดียวใน `VoiceService.onCreate` จาก `NoModelDetector` เป็นตัวใหม่

### 🔴 ขนาด APK ที่เพิ่มขึ้น — วัดจริงแล้ว ต้องอนุมัติก่อน

ผมลองใส่ `onnxruntime-android` แล้ว build จริงเพื่อวัด แล้วถอนออก **ตัวเลขนี้
ไม่ใช่การประมาณ**

| | ขนาด APK | เพิ่มขึ้น |
|---|---|---|
| ปัจจุบัน (ไม่มี onnxruntime) | 2.4 MB | — |
| ใส่ onnxruntime ทุก ABI | **74.6 MB** | **+70.5 MB** |
| ใส่ onnxruntime เฉพาะ arm64-v8a | **20.1 MB** | **+17.7 MB** |

A07 เป็น arm64 ตัวเลขที่ควรใช้จึงเป็น **+17.7 MB** โดยใส่
`ndk { abiFilters += "arm64-v8a" }` ซึ่งตัดไลบรารีของสถาปัตยกรรมอื่นออกหมด

ทางเลือกที่เล็กกว่า: openWakeWord ส่งออก **TFLite** ได้ด้วย และ
`org.tensorflow:tensorflow-lite` เล็กกว่า onnxruntime ❓ ยังไม่ได้วัด
ถ้า +17.7 MB มากเกินไป บอกได้ ผมจะวัด TFLite ให้เทียบ

**ยังไม่ใส่ dependency ใดๆ ในรอบนี้** — ใส่ตอนที่มีโมเดลจริงให้รัน

## เครื่องมือวัดผลบน A07 — พร้อมแล้ว ✅

เกณฑ์ที่ Poom ตั้งไว้: **แม่น ≥90% ที่ 3 เมตร** และ **ปลุกผิด ≤1 ครั้งต่อ 8 ชั่วโมง**

`VoiceStats` นับและเก็บลงเครื่อง (รอดการรีบูตกลางการทดสอบ) อ่านผ่าน adb ได้

### เริ่มการทดสอบ 8 ชั่วโมง

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_RESET_STATS
```

### อ่านผลระหว่างทางหรือตอนจบ

```powershell
adb logcat -c
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_STATS
adb logcat -s KioskStats:I -d
```

ได้ประมาณนี้:

```
wake word test
  since              : 2026-09-22 08:00
  elapsed hours      : 8.02
  detections         : 3
  confirmed by human : 2
  unconfirmed        : 1
  unconfirmed per 8h : 1.00   (target: at most 1)
  completed turns    : 2
  errors             : 0
```

**นิยามที่ใช้:** "ปลุกผิด" = การตรวจพบที่ไม่มีคนยืนยัน ระหว่างทดสอบ ถ้าคุณเป็นคน
พูดเอง ให้ยืนยันทันทีด้วย:

```powershell
adb shell am broadcast -a com.mammonrn.phoneaikiosk.TEST_LISTEN
```

(คำสั่งนี้ทั้งเริ่มรอบสนทนาและนับเป็น "ยืนยันแล้ว" ในตัว)
อะไรที่เหลือไม่ถูกยืนยัน จะถูกนับเป็นปลุกผิด — **นับแบบเข้มไว้ก่อน**

### วัดความแม่น (ต้องทำด้วยมือ)

พูด "สายฝน" **20 ครั้ง ที่ระยะ 3 เมตร** แล้วดูว่าตัวเลข `detections` ขึ้นกี่ครั้ง
**ต้องได้อย่างน้อย 18 จาก 20** จึงจะผ่านเกณฑ์ 90%

ทำซ้ำในสองสภาพ: เงียบ และเปิดทีวี/พัดลม ตัวเลขทั้งสองชุดคือผลจริง

## 🔴 ยังไม่ผ่านเกณฑ์ และจะไม่อ้างว่าผ่าน

ณ ตอนนี้ **ยังไม่มีโมเดล จึงยังไม่มีตัวเลขใดๆ** ทั้งความแม่นและอัตราปลุกผิด
เกณฑ์ 90% / 1 ครั้งต่อ 8 ชม. **ยังไม่ถูกวัด** และจะไม่มีการอ้างว่าผ่าน
จนกว่าจะมีผลจากเครื่อง A07 จริง
