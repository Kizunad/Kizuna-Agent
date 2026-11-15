// Kizuna Agent 的 pi 扩展：把游戏内桥接接口做成 pi 的工具。
// 连接信息由模组经环境变量给：KIZUNA_MC_URL、KIZUNA_MC_TOKEN。
// KIZUNA_AGENT_LOCAL_CODE=1 时再注册 run_python（用 KIZUNA_MC_PYTHON 里的命令跑脚本）。
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import * as http from "node:http";
import * as path from "node:path";

const URL_BASE = process.env.KIZUNA_MC_URL ?? "";
const TOKEN = process.env.KIZUNA_MC_TOKEN ?? "";
const LOCAL_CODE = process.env.KIZUNA_AGENT_LOCAL_CODE === "1";
// 交给模型的文字上限：再长就截断，提示改用 Python 处理。
const MAX_TEXT = 24_000;
// 这么小的区域才把每一层画给模型看。
const MAX_DRAWN = 4096;
const PYTHON_TIMEOUT_SECONDS = 300;

const Position = Type.Array(Type.Integer(), { minItems: 3, maxItems: 3, description: "[x, y, z] 方块坐标" });
const Mode = Type.Optional(Type.String({
  description: "shapes（默认，相邻方块形状联动）、physics（再加方块更新：沙子会掉、红石会亮）、strict（完全照给的状态放）",
}));

function call(name: string, body: Record<string, unknown>, signal?: AbortSignal): Promise<any> {
  // 用 node:http 直连本机，不经任何代理设置。
  return new Promise((resolve, reject) => {
    const payload = Buffer.from(JSON.stringify(body), "utf-8");
    const request = http.request(`${URL_BASE}/v1/${name}`, {
      method: "POST",
      headers: { Authorization: `Bearer ${TOKEN}`, "Content-Type": "application/json", "Content-Length": payload.length },
      signal,
    }, (response) => {
      const chunks: Buffer[] = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => {
        let answer: any;
        try {
          answer = JSON.parse(Buffer.concat(chunks).toString("utf-8"));
        } catch {
          reject(new Error(`游戏接口 ${name} 回的不是 JSON（HTTP ${response.statusCode}）`));
          return;
        }
        if (answer.ok) {
          delete answer.ok;
          resolve(answer);
        } else {
          reject(new Error(answer.error?.message ?? `游戏接口 ${name} 失败（HTTP ${response.statusCode}）`));
        }
      });
    });
    request.on("error", (error) => reject(new Error(`连不上游戏（${error.message}）。世界还开着吗？`)));
    request.end(payload);
  });
}

function text(value: string) {
  const clipped = value.length > MAX_TEXT
    ? `${value.slice(0, MAX_TEXT)}\n…（太长，截掉了 ${value.length - MAX_TEXT} 个字符；要完整内容请分段读，或写 Python 处理）`
    : value;
  return { content: [{ type: "text" as const, text: clipped }], details: {} };
}

function json(value: unknown) {
  return text(JSON.stringify(value));
}

function describeRegion(answer: any): string {
  // 给模型看的区域：大小、每种方块几个；够小就按层画出来，一格一个调色板下标。
  const [sizeX, sizeY, sizeZ] = answer.size as number[];
  const counts = new Array(answer.palette.length).fill(0);
  for (const index of answer.blocks as number[]) counts[index]++;
  const lines = [
    `origin（最小角）${JSON.stringify(answer.origin)}，size ${JSON.stringify(answer.size)}`,
    "palette（下标: 方块 ×个数）:",
    ...answer.palette.map((name: string, index: number) => `  ${index}: ${name} ×${counts[index]}`),
  ];
  if (answer.blocks.length <= MAX_DRAWN) {
    lines.push("各层（y 从低到高；每层里一行是一个 z，从小到大；行内 x 从小到大；数字是 palette 下标）:");
    for (let y = 0; y < sizeY; y++) {
      lines.push(`y=${answer.origin[1] + y}`);
      for (let z = 0; z < sizeZ; z++) {
        const start = (y * sizeZ + z) * sizeX;
        lines.push(`  ${answer.blocks.slice(start, start + sizeX).join(" ")}`);
      }
    }
  } else {
    lines.push(`区域有 ${answer.blocks.length} 个方块，这里只给统计。要逐格的内容，写 Python 用 mc.get_blocks() 处理。`);
  }
  if (answer.block_entities?.length) {
    lines.push("带数据的方块:", ...answer.block_entities.map((entry: any) => `  ${JSON.stringify(entry.position)} ${entry.nbt}`));
  }
  return lines.join("\n");
}

function runPython(code: string, seconds: number, cwd: string, signal?: AbortSignal): Promise<string> {
  // 脚本存进工作目录的 scripts/ 再跑，方便事后再看、再改。
  const command: string[] = JSON.parse(process.env.KIZUNA_MC_PYTHON ?? "[]");
  if (command.length === 0) {
    return Promise.reject(new Error("这台电脑上没找到 Python。请在 config/kizuna-agent.toml 的 [runtime] python 里填上路径"));
  }
  const directory = path.join(cwd, "scripts");
  mkdirSync(directory, { recursive: true });
  const file = path.join(directory, `run-${new Date().toISOString().replace(/[:.]/g, "-")}.py`);
  writeFileSync(file, code, "utf-8");
  return new Promise((resolve, reject) => {
    const child = spawn(command[0], [...command.slice(1), file], {
      cwd,
      env: { ...process.env, PYTHONIOENCODING: "utf-8", PYTHONUTF8: "1" },
      windowsHide: true,
    });
    const chunks: Buffer[] = [];
    child.stdout.on("data", (chunk) => chunks.push(chunk));
    child.stderr.on("data", (chunk) => chunks.push(chunk));
    const timer = setTimeout(() => child.kill(), seconds * 1000);
    signal?.addEventListener("abort", () => child.kill());
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(new Error(`Python 没跑起来（${error.message}）`));
    });
    child.on("close", (status, killedBy) => {
      clearTimeout(timer);
      const output = Buffer.concat(chunks).toString("utf-8");
      const ending = killedBy ? `被中止（超过 ${seconds} 秒或被打断）` : `退出码 ${status}`;
      resolve(`${output}\n[${ending}；脚本存在 ${file}]`);
    });
  });
}

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "mc_command",
    label: "执行指令",
    description: "以玩家身份执行一条 Minecraft 指令（带不带开头的 / 都行），返回是否成功、返回值和输出的每一行。",
    parameters: Type.Object({ command: Type.String({ description: "例如 time set day、give @s minecraft:torch 16" }) }),
    async execute(_id, params, signal) {
      return json(await call("command", params, signal));
    },
  });

  pi.registerTool({
    name: "mc_player",
    label: "玩家位置",
    description: "玩家现在在哪儿：block 脚下方块坐标、position 精确位置、yaw/pitch、facing 面朝的方向、dimension、"
      + "looking_at 准星指着的方块（block、face 指着的面、state、adjacent 贴着那个面的空位）。盖东西前先调它定位置。",
    parameters: Type.Object({}),
    async execute(_id, _params, signal) {
      return json(await call("player", {}, signal));
    },
  });

  pi.registerTool({
    name: "mc_get_blocks",
    label: "读方块",
    description: "读两个角之间的区域（含两角，最多 64×64×64）。返回大小、每种方块的个数；区域不超过 4096 格时再按层画出来。"
      + "nbt 为 true 时附上箱子、告示牌等方块的数据。",
    parameters: Type.Object({
      from: Position,
      to: Position,
      nbt: Type.Optional(Type.Boolean()),
      dimension: Type.Optional(Type.String({ description: "缺省是玩家所在维度" })),
    }),
    async execute(_id, params, signal) {
      return text(describeRegion(await call("blocks/get", params, signal)));
    },
  });

  pi.registerTool({
    name: "mc_set_blocks",
    label: "放方块",
    description: "批量放方块：palette 是方块写法的列表，blocks 里每项是 [dx, dy, dz, palette 下标]，偏移相对 origin。"
      + "方块写法和 /setblock 一样，如 minecraft:oak_stairs[facing=north,half=top]，带内容的可以跟 NBT。"
      + "几百个以内直接用它；更大、有规律的建筑写 Python 用 mc.set_blocks() 算出来。返回真改了几个。",
    parameters: Type.Object({
      origin: Position,
      palette: Type.Array(Type.String()),
      blocks: Type.Array(Type.Array(Type.Integer(), { minItems: 4, maxItems: 4 })),
      mode: Mode,
      dimension: Type.Optional(Type.String()),
    }),
    async execute(_id, params, signal) {
      return json(await call("blocks/set", params, signal));
    },
  });

  pi.registerTool({
    name: "mc_fill",
    label: "填充",
    description: "把两个角之间的长方体填满一种方块（最多一百万个）。replace 只替换这种方块（按种类，不看属性）；hollow 只填六个面。",
    parameters: Type.Object({
      from: Position,
      to: Position,
      block: Type.String(),
      replace: Type.Optional(Type.String()),
      hollow: Type.Optional(Type.Boolean()),
      mode: Mode,
      dimension: Type.Optional(Type.String()),
    }),
    async execute(_id, params, signal) {
      return json(await call("blocks/fill", params, signal));
    },
  });

  pi.registerTool({
    name: "mc_clone",
    label: "复制区域",
    description: "把两个角之间的区域（最多 64×64×64，含箱子内容）复制一份，最小角落在 destination。",
    parameters: Type.Object({ from: Position, to: Position, destination: Position, mode: Mode, dimension: Type.Optional(Type.String()) }),
    async execute(_id, params, signal) {
      return json(await call("blocks/clone", params, signal));
    },
  });

  pi.registerTool({
    name: "mc_undo",
    label: "撤回",
    description: "撤回最近一次用 mc_set_blocks、mc_fill、mc_clone（或 Python 里对应方法）放的方块，连箱子内容一起还原。"
      + "指令改的东西撤不回。返回还原了几个、还能再撤几次。",
    parameters: Type.Object({}),
    async execute(_id, _params, signal) {
      return json(await call("undo", {}, signal));
    },
  });

  pi.registerTool({
    name: "mc_mods",
    label: "模组列表",
    description: "已加载的模组：id、版本、名字、JAR 路径。filter 只留 id 或名字里含这个词的。",
    parameters: Type.Object({ filter: Type.Optional(Type.String()) }),
    async execute(_id, params, signal) {
      const answer = await call("mods", {}, signal);
      const needle = params.filter?.toLowerCase();
      const mods = needle
        ? answer.mods.filter((mod: any) => `${mod.id} ${mod.name}`.toLowerCase().includes(needle))
        : answer.mods;
      return text(mods.map((mod: any) => `${mod.id} ${mod.version}  ${mod.name}${mod.jar ? `  ${mod.jar}` : ""}`).join("\n"));
    },
  });

  pi.registerTool({
    name: "mc_jar_list",
    label: "列 JAR 文件",
    description: "列一个模组 JAR 里的文件和大小。prefix 例如 assets/；glob 例如 **/lang/*.json。",
    parameters: Type.Object({
      mod: Type.String({ description: "模组 id" }),
      prefix: Type.Optional(Type.String()),
      glob: Type.Optional(Type.String()),
      limit: Type.Optional(Type.Integer()),
    }),
    async execute(_id, params, signal) {
      const answer = await call("jar/list", params, signal);
      const lines = answer.entries.map((entry: any) => `${entry.size}\t${entry.path}`);
      if (answer.truncated) lines.push(`…共 ${answer.total} 个，只列了前 ${answer.entries.length} 个；用 prefix 或 glob 缩小范围`);
      return text(lines.join("\n"));
    },
  });

  pi.registerTool({
    name: "mc_jar_read",
    label: "读 JAR 文件",
    description: "读模组 JAR 里的一个文本文件（json、toml、语言文件、txtpb 等）。大文件用 offset、max_bytes 分段读。",
    parameters: Type.Object({
      mod: Type.String(),
      path: Type.String({ description: "JAR 里的路径，例如 assets/<命名空间>/lang/zh_cn.json" }),
      offset: Type.Optional(Type.Integer()),
      max_bytes: Type.Optional(Type.Integer()),
    }),
    async execute(_id, params, signal) {
      const answer = await call("jar/read", params, signal);
      const note = answer.truncated ? `\n…（全文 ${answer.size} 字节，这里从 ${answer.offset} 起；接着读请给 offset）` : "";
      return text(answer.text + note);
    },
  });

  pi.registerTool({
    name: "mc_jar_search",
    label: "搜 JAR",
    description: "在模组的文本资源里找一个词，返回 模组、文件、行号、那一行。不给 mod 就搜全部模组；glob 缩小到某类文件，例如 **/lang/zh_cn.json。",
    parameters: Type.Object({
      text: Type.String(),
      mod: Type.Optional(Type.String()),
      glob: Type.Optional(Type.String()),
      limit: Type.Optional(Type.Integer()),
      case_sensitive: Type.Optional(Type.Boolean()),
    }),
    async execute(_id, params, signal) {
      const answer = await call("jar/search", params, signal);
      const lines = answer.matches.map((match: any) => `${match.mod}  ${match.path}:${match.line}  ${match.text}`);
      lines.push(answer.truncated ? `…共 ${answer.total} 处，只列了前 ${answer.matches.length} 处` : `共 ${answer.total} 处`);
      return text(lines.join("\n"));
    },
  });

  pi.registerTool({
    name: "mc_jar_classes",
    label: "列类",
    description: "列一个模组里的类名。prefix 例如 dev.kizuna.meridian。",
    parameters: Type.Object({ mod: Type.String(), prefix: Type.Optional(Type.String()), limit: Type.Optional(Type.Integer()) }),
    async execute(_id, params, signal) {
      const answer = await call("jar/classes", params, signal);
      const lines = [...answer.classes];
      if (answer.truncated) lines.push(`…共 ${answer.total} 个，只列了前 ${answer.classes.length} 个；用 prefix 缩小范围`);
      return text(lines.join("\n"));
    },
  });

  pi.registerTool({
    name: "mc_jar_class",
    label: "看类签名",
    description: "一个类的签名：种类、父类、接口、字段、方法（不是源码）。private 为 true 时连私有成员一起列。",
    parameters: Type.Object({
      mod: Type.String(),
      class: Type.String({ description: "完整类名，例如 dev.kizuna.agent.AgentMod" }),
      private: Type.Optional(Type.Boolean()),
    }),
    async execute(_id, params, signal) {
      const answer = await call("jar/class", params, signal);
      const head = `${answer.modifiers} ${answer.kind} ${answer.class}`.trim()
        + (answer.extends ? ` extends ${answer.extends}` : "")
        + (answer.implements.length ? ` implements ${answer.implements.join(", ")}` : "");
      return text([head, "字段:", ...answer.fields.map((line: string) => `  ${line}`), "方法:",
        ...answer.methods.map((line: string) => `  ${line}`)].join("\n"));
    },
  });

  if (LOCAL_CODE) {
    pi.registerTool({
      name: "run_python",
      label: "跑 Python",
      description: "在这台电脑上跑一段 Python 脚本，返回输出。脚本里 `from kizuna_mc import mc` 就能操作游戏："
        + "mc.player()、mc.get_blocks(a, b)、mc.set_blocks(origin, [((dx,dy,dz), 方块)…])、mc.fill(a, b, 方块)、mc.clone(...)、"
        + "mc.undo()、mc.cmd(指令)、mc.mods()、mc.jar_list/jar_read/jar_json/jar_search/jar_classes/jar_class。"
        + "大的、有规律的建筑（圆、拱、螺旋、重复的构件）用它算坐标，比手写方块列表可靠。库的完整说明在 ../python/kizuna_mc.py 开头，示例在 ../python/examples/。",
      parameters: Type.Object({
        code: Type.String({ description: "完整的 Python 脚本" }),
        timeout_seconds: Type.Optional(Type.Integer({ description: `缺省 ${PYTHON_TIMEOUT_SECONDS}` })),
      }),
      async execute(_id, params, signal, _onUpdate, ctx) {
        return text(await runPython(params.code, params.timeout_seconds ?? PYTHON_TIMEOUT_SECONDS, ctx.cwd, signal));
      },
    });
  }
}
