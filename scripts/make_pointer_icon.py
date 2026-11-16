#!/usr/bin/env python3
"""画指点杖的物品图标（16×16 像素）：一根斜放的木杖，顶上一颗青色的小晶石。

图标是这个脚本一个像素一个像素画出来的，没有用别处的素材。改了配色重跑一遍：
    python3 scripts/make_pointer_icon.py
产物：src/main/resources/assets/kizuna_agent/textures/item/pointer.png
"""
from pathlib import Path

from PIL import Image

TARGET = Path(__file__).resolve().parent.parent / 'src/main/resources/assets/kizuna_agent/textures/item/pointer.png'
OUTLINE = (40, 30, 18, 255)
WOOD_DARK = (104, 78, 47, 255)
WOOD = (137, 103, 39, 255)
WOOD_LIGHT = (171, 133, 62, 255)
BAND = (212, 212, 220, 255)
GEM_DARK = (14, 106, 122, 255)
GEM = (38, 198, 218, 255)
GEM_LIGHT = (178, 245, 252, 255)
GLINT = (255, 255, 255, 255)

# 每行一串字符，左上角是 (0, 0)；杖从左下斜到右上，和原版手持工具一个朝向。
ROWS = [
    '............oo..',
    '...........oLGo.',
    '..........oLGGDo',
    '..........oGGDDo',
    '.........oBoDDo.',
    '........oWBBoo..',
    '.......oWMDo....',
    '......oWMDo.....',
    '.....oWMDo......',
    '....oWMDo.......',
    '...oWMDo........',
    '..oWMDo.........',
    '.oWMDo..........',
    '.oMDo...........',
    '..oo............',
    '................',
]
COLORS = {'o': OUTLINE, 'W': WOOD_LIGHT, 'M': WOOD, 'D': WOOD_DARK, 'B': BAND, 'L': GEM_LIGHT, 'G': GEM}
# 晶石里的暗部和杖身的暗部共用字母 D：按所在的行分开上色。
GEM_ROWS = range(0, 5)


def main():
    image = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(ROWS):
        for x, letter in enumerate(row):
            if letter == '.':
                continue
            color = GEM_DARK if letter == 'D' and y in GEM_ROWS else COLORS[letter]
            image.putpixel((x, y), color)
    image.putpixel((11, 1), GLINT)
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    image.save(TARGET)
    print(TARGET)


if __name__ == '__main__':
    main()
