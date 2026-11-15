"""把玩家周围的一块区域读下来存成文件，之后可以原样搬到别处。

用法：python dump_region.py 存档名 [半径] [高度]
读回来再放：
    from kizuna_mc import mc, Region
    region = Region.load("存档名.json")
    mc.set_blocks(mc.player().block, region.relative())
"""
import sys

from kizuna_mc import mc

name = sys.argv[1] if len(sys.argv) > 1 else "region"
radius = int(sys.argv[2]) if len(sys.argv) > 2 else 8
height = int(sys.argv[3]) if len(sys.argv) > 3 else 16

here = mc.player().block
region = mc.get_blocks(here + (-radius, 0, -radius), here + (radius, height - 1, radius), nbt=True)
region.save(f"{name}.json")
print(f"存到 {name}.json：{region.size.x}×{region.size.y}×{region.size.z}，最多的几种方块：")
for block, count in list(region.counts().items())[:8]:
    print(f"  {count:6d}  {block}")
