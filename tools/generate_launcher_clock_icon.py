#!/usr/bin/env python3
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math

SIZE = 512
CENTER = SIZE // 2

ROOT = Path(__file__).resolve().parents[1]
TARGETS = [
    ROOT / 'app/src/main/res/drawable-nodpi/ic_talkingtimer_launcher.png',
    ROOT / 'wear/src/main/res/drawable-nodpi/ic_talkingtimer_launcher.png',
]


def load_font(size: int):
    candidates = [
        '/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf',
        '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
    ]
    for p in candidates:
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            pass
    return ImageFont.load_default()


def draw_watch_icon() -> Image.Image:
    img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # soft drop shadow
    d.ellipse((56, 62, SIZE - 44, SIZE - 38), fill=(0, 0, 0, 55))

    # case + bezel + dial
    d.ellipse((44, 44, SIZE - 44, SIZE - 44), fill=(18, 56, 76, 255))
    d.ellipse((62, 62, SIZE - 62, SIZE - 62), fill=(28, 82, 108, 255))
    d.ellipse((86, 86, SIZE - 86, SIZE - 86), fill=(245, 242, 232, 255))

    # cardinal markers and ticks
    for i in range(60):
        angle = math.radians(i * 6 - 90)
        is_cardinal = i % 15 == 0
        is_hour = i % 5 == 0
        r_outer = 164
        r_inner = 132 if is_cardinal else (142 if is_hour else 150)
        width = 10 if is_cardinal else (6 if is_hour else 3)
        x1 = CENTER + math.cos(angle) * r_inner
        y1 = CENTER + math.sin(angle) * r_inner
        x2 = CENTER + math.cos(angle) * r_outer
        y2 = CENTER + math.sin(angle) * r_outer
        color = (27, 73, 94, 255) if is_hour else (87, 122, 136, 220)
        d.line((x1, y1, x2, y2), fill=color, width=width)

    # numerals 12/3/6/9
    font_big = load_font(56)
    font_mid = load_font(64)
    numeral_specs = [
        ('12', 0, 106, font_big),
        ('3', 106, 0, font_mid),
        ('6', 0, 110, font_mid),
        ('9', -110, 0, font_mid),
    ]
    for text, dx, dy, font in numeral_specs:
        bbox = d.textbbox((0, 0), text, font=font)
        w = bbox[2] - bbox[0]
        h = bbox[3] - bbox[1]
        x = CENTER + dx - w / 2
        y = CENTER + dy - h / 2
        # subtle outline for contrast
        for ox, oy in [(-1,0),(1,0),(0,-1),(0,1)]:
            d.text((x+ox, y+oy), text, font=font, fill=(255,255,255,130))
        d.text((x, y), text, font=font, fill=(20, 64, 84, 255))

    # hands (10:10 + seconds accent)
    def hand(angle_deg, length, width, color, tail=18):
        angle = math.radians(angle_deg - 90)
        x2 = CENTER + math.cos(angle) * length
        y2 = CENTER + math.sin(angle) * length
        x1 = CENTER - math.cos(angle) * tail
        y1 = CENTER - math.sin(angle) * tail
        d.line((x1, y1, x2, y2), fill=color, width=width)

    hand(300, 86, 14, (230, 86, 58, 255), tail=22)   # hour hand (10)
    hand(60, 126, 10, (12, 45, 62, 255), tail=18)    # minute hand (10)
    hand(165, 138, 4, (31, 149, 158, 255), tail=28)  # second hand accent

    # center cap
    d.ellipse((CENTER - 15, CENTER - 15, CENTER + 15, CENTER + 15), fill=(12, 45, 62, 255))
    d.ellipse((CENTER - 7, CENTER - 7, CENTER + 7, CENTER + 7), fill=(245, 242, 232, 255))

    return img


def main():
    img = draw_watch_icon()
    for target in TARGETS:
        target.parent.mkdir(parents=True, exist_ok=True)
        img.save(target, format='PNG')
        print(target)


if __name__ == '__main__':
    main()
