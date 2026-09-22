"""Building the training set for the "สายฝน" wake word, on a fixed budget.

openWakeWord is trained on audio, not on text, which is the property that makes
a Thai wake word possible at all — Porcupine builds its models from text and has
no Thai, and sherpa-onnx's keyword spotters have no Thai model either. What it
needs is a few hundred clips of somebody saying the phrase, and a few hundred of
things that sound nearly like it but must not trigger.

The voices come from the Chirp 3 HD Thai set that phase 3 already pays for: 30
distinct speakers, which is where the variety comes from. Speaking rate is
varied here because Google's pace control produces a genuinely different
delivery; noise, reverb, gain and pitch are NOT bought here, because the
training notebook adds those for free and paying per character for a resampled
copy of the same sentence would be waste.

THE CEILING IS ENFORCED BEFORE THE FIRST REQUEST, not after the last one. The
whole plan is priced up front, added to whatever previous runs already spent, and
compared against the cap — because a cap checked afterwards is a receipt, not a
limit.
"""

from __future__ import annotations

from dataclasses import dataclass, field

#: The phrase, and short carriers around it. A wake word is almost never said in
#: isolation, so a model trained only on the bare word learns the silence around
#: it as much as the word.
POSITIVE_TEXTS = (
    "สายฝน",
    "สายฝนครับ",
    "นี่สายฝน",
    "สายฝน ช่วยหน่อย",
    "โอเค สายฝน",
)

#: Things that must NOT wake it. Chosen for the specific ways Thai can collide
#: with this name: the same first syllable (สาย…), the same second syllable
#: (…ฝน), and the two syllables in the other order.
NEAR_MISS_TEXTS = (
    "สายลม",
    "สายไฟ",
    "สายด่วน",
    "สายพาน",
    "ฝนตก",
    "ฝนหยุด",
    "สายฝัน",
    "ชายฝน",
)

#: Ordinary Thai with the phrase nowhere in it, which is what the phone actually
#: hears for twenty-three and a half hours a day.
BACKGROUND_TEXTS = (
    "วันนี้อากาศดีมากเลยนะ",
    "ไปกินข้าวเย็นที่ไหนกันดี",
    "พรุ่งนี้ต้องตื่นเช้าไปทำงาน",
    "ช่วยปิดไฟในห้องนอนด้วย",
    "เมื่อวานรถติดมากบนถนนสุขุมวิท",
    "ราคาน้ำมันขึ้นอีกแล้วหรือ",
    "เดี๋ยวผมโทรกลับนะครับ",
    "ลูกกลับบ้านกี่โมงวันนี้",
    "อย่าลืมเอาร่มไปด้วยนะ",
    "ขอบคุณมากครับสำหรับความช่วยเหลือ",
    "ทีวีช่องนี้สนุกกว่าช่องเมื่อคืน",
    "น้ำในตู้เย็นหมดแล้วนะ",
)

#: Google's pace control, 0.25–2.0. Three values around normal: a wake word said
#: in a hurry and said carefully are different sounds.
POSITIVE_RATES = (0.9, 1.0, 1.2)
NEGATIVE_RATES = (0.95, 1.15)

#: How many of the 30 voices each group uses. Positives and near-misses get all
#: of them because they are what the model has to separate; background sentences
#: are long, so fewer voices carry the same variety for a third of the cost.
BACKGROUND_VOICE_COUNT = 10


@dataclass(frozen=True)
class Utterance:
    """One clip to synthesise."""

    label: str          # positive | nearmiss | background
    text: str
    voice: str
    speaking_rate: float

    @property
    def characters(self) -> int:
        """What Google bills: the characters sent, spaces included."""
        return len(self.text)

    def filename(self, index: int) -> str:
        return f"{self.label}_{index:05d}_{self.voice}_{self.speaking_rate:g}.wav"


@dataclass
class Plan:
    utterances: list[Utterance] = field(default_factory=list)

    @property
    def characters(self) -> int:
        return sum(u.characters for u in self.utterances)

    def cost(self, pricing, voice_family: str) -> float:
        return pricing.tts_cost(voice_family, self.characters)

    def by_label(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for u in self.utterances:
            counts[u.label] = counts.get(u.label, 0) + 1
        return counts


def build_plan(voices: tuple[str, ...]) -> Plan:
    """Every clip, decided before a single request is made.

    Deterministic and pure so the cost can be known — and refused — in advance.
    """
    plan = Plan()

    for text in POSITIVE_TEXTS:
        for voice in voices:
            for rate in POSITIVE_RATES:
                plan.utterances.append(Utterance("positive", text, voice, rate))

    for text in NEAR_MISS_TEXTS:
        for voice in voices:
            for rate in NEGATIVE_RATES:
                plan.utterances.append(Utterance("nearmiss", text, voice, rate))

    for text in BACKGROUND_TEXTS:
        for voice in voices[:BACKGROUND_VOICE_COUNT]:
            plan.utterances.append(Utterance("background", text, voice, 1.0))

    return plan


class BudgetExceeded(RuntimeError):
    """The plan would cost more than the approved ceiling. Nothing was sent."""


def check_ceiling(*, planned_usd: float, already_spent_usd: float, ceiling_usd: float) -> None:
    """Refuses before the first request rather than reporting after the last.

    Counts what previous runs already spent: the ceiling Poom approved is for the
    job, not for one invocation, so a second attempt has to see the first one's
    bill.
    """
    total = already_spent_usd + planned_usd
    if total > ceiling_usd:
        raise BudgetExceeded(
            f"this run would cost ${planned_usd:.4f} and ${already_spent_usd:.4f} has already "
            f"been spent, which is ${total:.4f} against a ${ceiling_usd:.2f} ceiling. "
            f"Nothing was sent. Use --budget to raise it only if Poom has approved a higher one."
        )
