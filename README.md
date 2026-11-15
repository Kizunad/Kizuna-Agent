# Kizuna Agent

Minecraft 26.2（Fabric）里的 AI 助手。在聊天栏里说一句话，它就在你正开着的单人世界里动手：盖建筑、执行指令、查各个模组 JAR 里的资源和类、跑 Python 建筑脚本。

助手本体是 [pi coding agent](https://github.com/badlogic/pi-mono)，整个打在 JAR 里。模组只做三件事：在游戏里开一个只听本机的接口，把这个接口做成 pi 的工具，把 pi 的回答显示到聊天栏。

## 装

1. 把 `kizuna-agent-<版本>.jar` 放进实例的 `mods/`。只依赖 Fabric API。
2. 这台电脑上要有 **Node.js 22.19 以上**（pi 的要求）。要让助手跑 Python 脚本，再装一个 Python 3。
3. 进一次单人世界，生成 `config/kizuna-agent.toml`。在 `[model]` 里填接口地址、密钥、模型名，再进一次世界。

```toml
[model]
api = "openai-completions"          # 兼容 OpenAI 的接口都用这个
base_url = "http://127.0.0.1:8317/v1"
api_key = "……"
model = "gemini-3.8-flash-high"
fallback_model = "gpt-6.1-terra"    # 主模型报 429 或额度用尽时自动换它；留空不换
```

其余各项（Node 和 Python 的路径、关掉「动这台电脑」的开关、接口端口、放方块的速度）在生成的文件里都有注释。

## 用

| 指令 | 做什么 |
|---|---|
| `/agent <要它做什么>` | 把话交给助手。它正在干活时，这句话插到它下一步之前 |
| `/agent stop` | 让它停下 |
| `/agent new` | 开一段新对话 |
| `/agent undo` | 撤回它上一次放的方块（连箱子里的东西一起还原），可以连着撤 |
| `/agent status` | 模型、Node、Python、接口各自的状态；哪项没配好这里会说 |
| `/agent model <名字>` | 换模型，下一句开始生效 |

- 只在单人世界里可用，按世界主人的权限执行，不受「允许作弊」开关限制。
- 助手说的话、每一步在做什么（灰色斜体）、出错（红色）都显示在聊天栏。
- 退出世界时助手进程结束；再进世界会接着上次的对话。

## 助手能用的工具

| 工具 | 做什么 |
|---|---|
| `mc_command` | 以玩家身份执行指令，拿回输出 |
| `mc_player` | 玩家位置、朝向、准星指着的方块 |
| `mc_get_blocks` | 读一块区域（最多 64×64×64） |
| `mc_set_blocks`、`mc_fill`、`mc_clone` | 批量放方块、填充、复制；分 tick 落地，不卡服 |
| `mc_undo` | 撤回最近一次放的方块 |
| `mc_mods` | 已加载的模组和 JAR 路径 |
| `mc_jar_list`、`mc_jar_read`、`mc_jar_search` | 列 JAR 里的文件、读文本资源、在各模组的文本里找词 |
| `mc_jar_classes`、`mc_jar_class` | 列类、看类的字段和方法签名（不是源码） |
| `run_python` | 跑一段 Python 脚本，脚本里 `from kizuna_mc import mc` 操作游戏 |
| 读写文件、PowerShell（Linux 上是 bash） | pi 自带的，工作目录在 `config/kizuna-agent/workspace/` |

后两行在配置里 `[tools] local_code = false` 时关掉，助手只剩游戏内工具。

## 自己写 Python

第一次用助手时，库和示例解到 `config/kizuna-agent/python/`。世界开着的时候，在那个目录下直接跑：

```
python examples/tower.py 4 12
```

```python
from kizuna_mc import mc

here = mc.player().block                       # 玩家脚下
mc.fill(here + (2, 0, -3), here + (8, 0, 3), "minecraft:stone_bricks")
mc.set_blocks(here + (5, 1, 0), [((0, y, 0), "minecraft:oak_log[axis=y]") for y in range(6)])
region = mc.get_blocks(here + (2, 0, -3), here + (8, 6, 3))
print(region.counts())
mc.jar_search("经脉", glob="**/lang/zh_cn.json")
mc.undo()
```

库只用标准库，说明在 `kizuna_mc.py` 开头。助手跑脚本时连接信息已经在环境变量里；自己跑时它读 `config/kizuna-agent/bridge.json`。

## 游戏内接口

世界开着时，模组在 `127.0.0.1` 上开一个 HTTP 接口，端口和令牌写在 `config/kizuna-agent/bridge.json`（关世界时删掉，令牌每次重新生成）。全部是 `POST /v1/<名字>`，请求头带 `Authorization: Bearer <令牌>`，请求体和应答都是 JSON；带 `Origin` 头的请求（网页发来的）一律拒。

| 接口 | 请求 | 应答 |
|---|---|---|
| `command` | `command` | `success`、`result`、`output`（每行一项） |
| `player` | 无 | `block`、`position`、`yaw`、`pitch`、`facing`、`dimension`、`looking_at`、`can_edit` |
| `blocks/get` | `from`、`to`，可选 `nbt`、`dimension` | `origin`、`size`、`palette`、`blocks`（下标，顺序 y→z→x） |
| `blocks/set` | `origin`、`palette`、`blocks`（每项 `[dx, dy, dz, 下标]`），可选 `mode`、`dimension` | `changed`、`skipped`、`undoable`、`ticks`（分了几个 tick 放完） |
| `blocks/fill` | `from`、`to`、`block`，可选 `replace`、`hollow`、`mode`、`dimension` | 同上 |
| `blocks/clone` | `from`、`to`、`destination`，可选 `mode`、`dimension` | 同上 |
| `undo` | 无 | `restored`、`remaining` |
| `mods` | 无 | `mods`（`id`、`version`、`name`、`jar`） |
| `jar/list` | `mod`，可选 `prefix`、`glob`、`limit` | `entries`（`path`、`size`）、`total`、`truncated` |
| `jar/read` | `mod`、`path`，可选 `offset`、`max_bytes` | `text`、`size`、`truncated` |
| `jar/search` | `text`，可选 `mod`、`glob`、`limit`、`case_sensitive` | `matches`（`mod`、`path`、`line`、`text`） |
| `jar/classes` | `mod`，可选 `prefix`、`limit` | `classes` |
| `jar/class` | `mod`、`class`，可选 `private` | `kind`、`extends`、`implements`、`fields`、`methods` |

- 方块写法和 `/setblock` 一样，带内容的方块可以跟 NBT。
- `mode`：`shapes`（默认，相邻方块形状联动）、`physics`（再加方块更新）、`strict`（完全照给的状态放）。
- 上限：读和复制一次 64×64×64，放和填一次一百万个。被替换的方块不掉落、里面的东西不洒出来。
- 出错时应答是 `{"ok": false, "error": {"code": …, "message": …}}`，`message` 是一句说得清错在哪儿的话。

## 开发

```
kz test   ~/Code/Kizuna-Agent           # 单测、构建、服务端 GameTest
kz client ~/Code/Kizuna-Agent agent     # 真客户端验收：真的 pi 和桥接，模型是照剧本答话的假接口
```

接真模型录一段（要环境变量 `CLIPROXY_BASE_URL`、`CLIPROXY_API_KEY`）：

```
kz client ~/Code/Kizuna-Agent agent -- -PagentScene=live -PagentExtraMods=<别的模组的 JAR>
```

- `src/main/java/dev/kizuna/agent/bridge/`：游戏内接口，不碰客户端类。
- `src/main/java/dev/kizuna/agent/pi/`：找 Node 和 Python、解出 pi、拉起 pi 进程、把事件变成聊天栏的一行行话，不碰游戏类。
- `src/main/java/dev/kizuna/agent/client/`：`/agent` 指令和聊天栏显示。
- `src/main/resources/kizuna-agent/`：pi 扩展、Python 库和示例、系统提示词、默认配置。
- `runtime/pi-<版本>.zip`：pi 的 RPC 运行文件，由 `scripts/pack_pi.py` 从本机装好的 pi 打出来。升级 pi 的步骤写在那个脚本开头。

## 许可证

本仓 MIT。JAR 里带的 pi 及其依赖的许可证在 `META-INF/licenses/`（仓里是 `runtime/licenses/`）。
