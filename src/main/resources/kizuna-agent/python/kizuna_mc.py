"""Kizuna Agent 的 Python 客户端：从脚本里操作正开着的 Minecraft 世界。

    from kizuna_mc import mc

    here = mc.player().block                      # 玩家脚下的方块坐标
    mc.fill(here + (-3, -1, -3), here + (3, -1, 3), "minecraft:stone_bricks")
    mc.set_blocks(here, [((0, y, 0), "minecraft:oak_log[axis=y]") for y in range(8)])
    region = mc.get_blocks(here + (-3, -1, -3), here + (3, 8, 3))
    print(region.counts())
    mc.cmd("time set day")
    mc.undo()                                     # 撤回最近一次放的方块
    for shot in mc.screenshot(orbit={"target": here, "distance": 16}):
        print(shot.label, shot.file)              # 绕着拍四面，图存成文件
    print(mc.marks())                             # 玩家用指点杖标的点

只用标准库。连接信息先读环境变量 KIZUNA_MC_URL、KIZUNA_MC_TOKEN（助手跑脚本时已经设好），
没有就读本文件上一级目录的 bridge.json（游戏开着单人世界时才有），所以自己在命令行里跑脚本也行。

坐标都是方块坐标的整数三元组 (x, y, z)。方块写法和 /setblock 一样：
"minecraft:oak_stairs[facing=north,half=top]"，带内容的可以跟 NBT："minecraft:chest{Items:[...]}"。
"""
import json
import os
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

__all__ = ["mc", "Minecraft", "Vec", "Region", "BridgeError"]

# 一次请求里最多放多少个方块；更多的自动分几次发（每次是一步撤回）。
BATCH = 200_000


class BridgeError(Exception):
    """游戏那头拒绝了请求。code 是短码，str(error) 是一句说明。"""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class Vec(tuple):
    """整数坐标 (x, y, z)，可以和三元组相加减。"""

    def __new__(cls, x, y=None, z=None):
        values = x if y is None else (x, y, z)
        if len(values) != 3:
            raise ValueError(f"坐标要是三个数，收到 {values!r}")
        return super().__new__(cls, (int(values[0]), int(values[1]), int(values[2])))

    x = property(lambda self: self[0])
    y = property(lambda self: self[1])
    z = property(lambda self: self[2])

    def __add__(self, other):
        other = Vec(other)
        return Vec(self[0] + other[0], self[1] + other[1], self[2] + other[2])

    __radd__ = __add__

    def __sub__(self, other):
        other = Vec(other)
        return Vec(self[0] - other[0], self[1] - other[1], self[2] - other[2])

    def __repr__(self):
        return f"Vec({self[0]}, {self[1]}, {self[2]})"


class Region:
    """读回来的一块区域。region[x, y, z] 用世界坐标取方块写法；blocks() 逐个给出 (坐标, 方块)。"""

    def __init__(self, origin, size, palette, indices, block_entities=None):
        self.origin = Vec(origin)
        self.size = Vec(size)
        self.palette = list(palette)
        self.indices = list(indices)
        # {坐标: NBT 文本}，只有 get_blocks(nbt=True) 时才有内容。
        self.block_entities = dict(block_entities or {})

    def _index(self, position):
        local = Vec(position) - self.origin
        if not all(0 <= local[axis] < self.size[axis] for axis in range(3)):
            raise KeyError(f"{tuple(position)} 不在这块区域里（{self.origin} 起，大小 {self.size}）")
        return (local.y * self.size.z + local.z) * self.size.x + local.x

    def __getitem__(self, position):
        return self.palette[self.indices[self._index(position)]]

    def blocks(self, skip_air=False):
        """按 y、z、x 的顺序逐个给出 (世界坐标, 方块写法)。"""
        index = 0
        for y in range(self.size.y):
            for z in range(self.size.z):
                for x in range(self.size.x):
                    state = self.palette[self.indices[index]]
                    index += 1
                    if skip_air and state.split("[")[0] in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
                        continue
                    yield self.origin + (x, y, z), state

    def counts(self):
        """每种方块有多少个，多的在前。"""
        tally = Counter(self.palette[index] for index in self.indices)
        return dict(tally.most_common())

    def relative(self, skip_air=True):
        """[((dx, dy, dz), 方块)…]：相对这块区域最小角的偏移，可以直接交给 set_blocks 搬到别处。"""
        return [(position - self.origin, state) for position, state in self.blocks(skip_air)]

    def save(self, path):
        data = {"origin": list(self.origin), "size": list(self.size), "palette": self.palette, "blocks": self.indices,
                "block_entities": [{"position": list(position), "nbt": nbt} for position, nbt in self.block_entities.items()]}
        Path(path).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    @classmethod
    def load(cls, path):
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        entities = {Vec(entry["position"]): entry["nbt"] for entry in data.get("block_entities", [])}
        return cls(data["origin"], data["size"], data["palette"], data["blocks"], entities)


class _Record(dict):
    """应答里的字段既能用 ["名字"] 取，也能用 .名字 取。"""

    def __getattr__(self, name):
        try:
            return self[name]
        except KeyError:
            raise AttributeError(name) from None


def _plain(value):
    """请求里的机位参数：坐标（Vec、元组）都换成列表。"""
    if value is None:
        return None
    return {key: list(item) if isinstance(item, tuple) else item for key, item in value.items()}


class Minecraft:
    def __init__(self, url=None, token=None):
        self._url = url
        self._token = token

    def _connection(self):
        if self._url and self._token:
            return self._url, self._token
        url, token = os.environ.get("KIZUNA_MC_URL"), os.environ.get("KIZUNA_MC_TOKEN")
        if not (url and token):
            file = Path(os.environ.get("KIZUNA_MC_BRIDGE_FILE", Path(__file__).resolve().parent.parent / "bridge.json"))
            if not file.is_file():
                raise BridgeError("unavailable", f"连不上游戏：没有 {file}。先在游戏里进一个单人世界")
            connection = json.loads(file.read_text(encoding="utf-8"))
            url, token = connection["url"], connection["token"]
        self._url, self._token = url, token
        return url, token

    def call(self, name, **body):
        """直接调一个接口，返回应答的字典。一般用下面包好的方法。"""
        url, token = self._connection()
        payload = json.dumps({key: value for key, value in body.items() if value is not None}).encode("utf-8")
        request = urllib.request.Request(f"{url}/v1/{name}", data=payload, method="POST", headers={
            "Authorization": f"Bearer {token}", "Content-Type": "application/json"})
        # 本机回环地址不走系统代理。
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(request, timeout=660) as response:
                answer = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as rejected:
            try:
                error = json.loads(rejected.read().decode("utf-8"))["error"]
            except (ValueError, KeyError):
                raise BridgeError("http", f"接口 {name} 回了 HTTP {rejected.code}") from None
            raise BridgeError(error["code"], error["message"]) from None
        except urllib.error.URLError as unreachable:
            raise BridgeError("unavailable", f"连不上游戏（{unreachable.reason}）。世界还开着吗？") from None
        answer.pop("ok", None)
        return answer

    # ---- 指令和玩家 ----

    def cmd(self, command):
        """以玩家身份执行一条指令（带不带开头的 / 都行）。返回 .success、.result、.output（输出的每一行）。"""
        return _Record(self.call("command", command=command))

    def player(self):
        """玩家信息：.block 脚下方块坐标、.position 精确位置、.yaw、.pitch、.facing、.dimension、
        .looking_at（准星指着的方块：.block、.face、.state、.adjacent；没指着方块时没有这一项）。"""
        info = _Record(self.call("player"))
        info["block"] = Vec(info["block"])
        if "looking_at" in info:
            looking = _Record(info["looking_at"])
            looking["block"], looking["adjacent"] = Vec(looking["block"]), Vec(looking["adjacent"])
            info["looking_at"] = looking
        return info

    # ---- 方块 ----

    def get_blocks(self, corner, opposite, nbt=False, dimension=None):
        """读两个角之间的区域（含两角，最多 64×64×64），返回 Region。"""
        answer = self.call("blocks/get", **{"from": list(Vec(corner)), "to": list(Vec(opposite))}, nbt=nbt or None,
                           dimension=dimension)
        entities = {Vec(entry["position"]): entry["nbt"] for entry in answer.get("block_entities", [])}
        return Region(answer["origin"], answer["size"], answer["palette"], answer["blocks"], entities)

    def get_block(self, position, dimension=None):
        return self.get_blocks(position, position, dimension=dimension)[position]

    def set_block(self, position, block, mode=None, dimension=None):
        return self.set_blocks(position, [((0, 0, 0), block)], mode=mode, dimension=dimension)

    def set_blocks(self, origin, blocks, mode=None, dimension=None):
        """批量放方块。blocks 是 [((dx, dy, dz), 方块)…] 或 {(dx, dy, dz): 方块}，偏移相对 origin。

        mode：None/"shapes" 相邻方块的形状会联动（栅栏接上）；"physics" 再加方块更新（沙子会掉、红石会亮）；
        "strict" 完全照给的状态放。返回 .changed 真改了几个、.skipped 超出世界高度的几个、.batches 分了几次发。
        """
        items = blocks.items() if isinstance(blocks, dict) else blocks
        palette, indices, placed = [], {}, []
        for offset, block in items:
            index = indices.get(block)
            if index is None:
                index = indices[block] = len(palette)
                palette.append(block)
            dx, dy, dz = Vec(offset)
            placed.append([dx, dy, dz, index])
        result = _Record(changed=0, skipped=0, batches=0)
        for start in range(0, len(placed), BATCH):
            answer = self.call("blocks/set", origin=list(Vec(origin)), palette=palette,
                               blocks=placed[start:start + BATCH], mode=mode, dimension=dimension)
            result["changed"] += answer["changed"]
            result["skipped"] += answer["skipped"]
            result["batches"] += 1
        return result

    def fill(self, corner, opposite, block, replace=None, hollow=False, mode=None, dimension=None):
        """把两个角之间填满 block（最多一百万个）。replace 只替换这种方块；hollow 只填六个面。"""
        return _Record(self.call("blocks/fill", **{"from": list(Vec(corner)), "to": list(Vec(opposite))}, block=block,
                                 replace=replace, hollow=hollow or None, mode=mode, dimension=dimension))

    def clone(self, corner, opposite, destination, mode=None, dimension=None):
        """把两个角之间的区域（最多 64×64×64，含箱子内容）复制一份，最小角落在 destination。"""
        return _Record(self.call("blocks/clone", **{"from": list(Vec(corner)), "to": list(Vec(opposite))},
                                 destination=list(Vec(destination)), mode=mode, dimension=dimension))

    def undo(self, times=1):
        """撤回最近 times 次放的方块（set_blocks 分了几次发就算几次）。返回一共还原了几个。"""
        restored = 0
        for _ in range(times):
            restored += self.call("undo")["restored"]
        return restored

    # ---- 截图、标记 ----

    def screenshot(self, camera=None, orbit=None, hud=False, width=None):
        """截游戏画面（不是桌面），存成图片文件：[.label（从哪儿往哪儿看）、.file（路径）、.width、.height、.notes（要留意的事）]。

        不带参数是玩家现在的视角，不含 HUD；hud=True 连 HUD 和打开的界面一起截。
        camera={"position": (x, y, z), "look_at": (x, y, z)}（或给 "yaw"、"pitch"，可加 "fov"）从指定机位看，玩家不动。
        orbit={"target": (x, y, z), "distance": 16}（可加 "elevation" 角度、"sides": ["north", …]、"top": True）绕着一个点拍几面。
        机位超出渲染距离会抛 BridgeError。"""
        answer = self.call("screenshot", camera=_plain(camera), orbit=_plain(orbit), hud=hud or None, width=width)
        return [_Record({key: value for key, value in shot.items() if key != "data"}) for shot in answer["shots"]]

    def marks(self):
        """玩家用指点杖标的点，按编号排：[.number、.block（Vec）、.dimension、.state、.face、.kind（block、distant、feet）]。"""
        marks = [_Record(mark) for mark in self.call("marks")["marks"]]
        for mark in marks:
            mark["block"] = Vec(mark["block"])
        return marks

    # ---- 模组 JAR ----

    def mods(self):
        """已加载的模组：[.id、.version、.name、.jar（路径，可能没有）]。"""
        return [_Record(mod) for mod in self.call("mods")["mods"]]

    def jar_list(self, mod, prefix="", glob=None, limit=None):
        """列一个模组 JAR 里的文件：[.path、.size]。prefix 例如 "assets/"；glob 例如 "**/lang/*.json"。"""
        return [_Record(entry) for entry in self.call("jar/list", mod=mod, prefix=prefix or None, glob=glob, limit=limit)["entries"]]

    def jar_read(self, mod, path, offset=None, max_bytes=None):
        """读 JAR 里的一个文本文件，返回文字。大文件用 offset、max_bytes 分段读。"""
        return self.call("jar/read", mod=mod, path=path, offset=offset, max_bytes=max_bytes)["text"]

    def jar_json(self, mod, path):
        """读 JAR 里的一个 JSON 文件（例如语言文件），返回解析好的对象。"""
        return json.loads(self.call("jar/read", mod=mod, path=path, max_bytes=1024 * 1024)["text"])

    def jar_search(self, text, mod=None, glob=None, limit=None, case_sensitive=False):
        """在模组的文本资源里找一个词（不给 mod 就找全部模组）：[.mod、.path、.line、.text]。"""
        answer = self.call("jar/search", text=text, mod=mod, glob=glob, limit=limit, case_sensitive=case_sensitive or None)
        return [_Record(match) for match in answer["matches"]]

    def jar_classes(self, mod, prefix="", limit=None):
        """列一个模组里的类名；prefix 例如 "dev.kizuna.meridian"。"""
        return self.call("jar/classes", mod=mod, prefix=prefix or None, limit=limit)["classes"]

    def jar_class(self, mod, name, private=False):
        """一个类的签名：.kind、.extends、.implements、.fields、.methods。不是源码。"""
        return _Record(self.call("jar/class", **{"mod": mod, "class": name, "private": private or None}))


mc = Minecraft()
