"""在玩家面前盖一座圆塔。用法：python tower.py [半径] [高度]"""
import sys

from kizuna_mc import mc

radius = int(sys.argv[1]) if len(sys.argv) > 1 else 4
height = int(sys.argv[2]) if len(sys.argv) > 2 else 12

player = mc.player()
step = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}.get(player.facing, (0, 0, 1))
# 塔心放在玩家面前，离开一个半径再多两格。
center = player.block + tuple(axis * (radius + 2) for axis in step)

blocks = {}
for y in range(height):
    for dx in range(-radius, radius + 1):
        for dz in range(-radius, radius + 1):
            distance = (dx * dx + dz * dz) ** 0.5
            if radius - 0.5 <= distance < radius + 0.5:
                # 墙：每四层开一圈窗。
                window = y % 4 == 2 and (dx == 0 or dz == 0)
                blocks[(dx, y, dz)] = "minecraft:glass_pane" if window else "minecraft:stone_bricks"
            elif distance < radius - 0.5 and y % 4 == 0:
                blocks[(dx, y, dz)] = "minecraft:spruce_planks"
# 塔顶一圈垛口。
for dx in range(-radius, radius + 1):
    for dz in range(-radius, radius + 1):
        if radius - 0.5 <= (dx * dx + dz * dz) ** 0.5 < radius + 0.5 and (dx + dz) % 2 == 0:
            blocks[(dx, height, dz)] = "minecraft:stone_brick_wall"

result = mc.set_blocks(center, blocks)
print(f"塔心 {tuple(center)}，半径 {radius}，高 {height}：放了 {result.changed} 个方块。不满意就 mc.undo()。")
