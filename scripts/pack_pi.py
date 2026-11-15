#!/usr/bin/env python3
"""把本机装好的 pi（@earendil-works/pi-coding-agent）打成 JAR 里带的运行包。

只取 RPC 模式跑得起来所需的文件：打好的 bundle、内置主题，和 bundle 没打进去的几个纯 JS / wasm 依赖。
不带终端界面的原生模块（剪贴板）、各家云厂商 SDK 的未打包副本和类型声明。

用法：pack_pi.py [pi 包目录]
     缺省取 `npm root -g` 下的 @earendil-works/pi-coding-agent。
产物：runtime/pi-<版本>.zip（进 JAR）、runtime/licenses/（进 JAR 的 META-INF/licenses）。
升级 pi：npm i -g @earendil-works/pi-coding-agent@<新版本>，重跑本脚本，把 build.gradle 里的 piVersion 改成新版本，
删掉旧的 zip。
"""
import json
import subprocess
import sys
import zipfile
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent
# bundle 运行时按包名去找的依赖；都没有原生模块。
DEPENDENCIES = ['typebox', 'undici', 'jiti', '@earendil-works/chord', '@silvia-odwyer/photon-node']
# pi 自己的几个包代码已经打进 bundle，这里只为附许可证。
BUNDLED = ['@earendil-works/pi-ai', '@earendil-works/pi-agent-core', '@earendil-works/pi-tui']
SKIPPED_SUFFIXES = ('.d.ts', '.d.mts', '.d.cts', '.map', '.md', '.ts.map')
LICENSE_NAMES = ('license', 'license.md', 'license.txt', 'notice', 'notice.md')
# 压缩包里的时间戳固定，同样的输入打出同样的包。
EPOCH = (2026, 1, 1, 0, 0, 0)


def package_directory():
    if len(sys.argv) > 1:
        return Path(sys.argv[1]).resolve()
    root = subprocess.run(['npm', 'root', '-g'], check=True, capture_output=True, text=True).stdout.strip()
    return Path(root) / '@earendil-works' / 'pi-coding-agent'


def wanted(path):
    name = path.name.lower()
    return path.is_file() and (name in LICENSE_NAMES or not name.endswith(SKIPPED_SUFFIXES))


def collect(package):
    files = {}
    files['package.json'] = package / 'package.json'
    for path in sorted((package / 'dist' / 'bundle').rglob('*')):
        if wanted(path):
            files[path.relative_to(package).as_posix()] = path
    for path in sorted((package / 'dist' / 'modes' / 'interactive' / 'theme').glob('*.json')):
        files[path.relative_to(package).as_posix()] = path
    for dependency in DEPENDENCIES:
        root = package / 'node_modules' / dependency
        if not root.is_dir():
            raise SystemExit(f'找不到依赖 {dependency}：{root}')
        for path in sorted(root.rglob('*')):
            if 'node_modules' in path.relative_to(root).parts:
                continue
            if wanted(path):
                files[path.relative_to(package).as_posix()] = path
    return files


def license_text(root):
    for path in sorted(root.iterdir()):
        if path.name.lower() in LICENSE_NAMES:
            return path.read_text(encoding='utf-8')
    return None


def write_licenses(package, version):
    target = REPOSITORY / 'runtime' / 'licenses'
    target.mkdir(parents=True, exist_ok=True)
    for old in target.glob('*.txt'):
        old.unlink()
    lines = [f'本 JAR 内的 kizuna-agent/pi-{version}.zip 是 pi coding agent {version} 的 RPC 运行文件，原样再分发。',
             '来源：https://github.com/badlogic/pi-mono （npm 包 @earendil-works/pi-coding-agent）', '',
             '包内各部分的许可证：']
    for name in ['@earendil-works/pi-coding-agent', *BUNDLED, *DEPENDENCIES]:
        root = package if name.endswith('pi-coding-agent') else package / 'node_modules' / name
        manifest = json.loads((root / 'package.json').read_text(encoding='utf-8'))
        text = license_text(root)
        file_name = name.replace('@', '').replace('/', '-') + '.txt'
        lines.append(f"- {name} {manifest['version']}：{manifest.get('license', '未标明')}"
                     + (f'，原文见 {file_name}' if text else '，包内没有附许可证原文，按 package.json 的声明'))
        if text:
            (target / file_name).write_text(text, encoding='utf-8')
    (target / 'PI-RUNTIME-NOTICE.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def main():
    package = package_directory()
    version = json.loads((package / 'package.json').read_text(encoding='utf-8'))['version']
    files = collect(package)
    output = REPOSITORY / 'runtime' / f'pi-{version}.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, path in sorted(files.items()):
            entry = zipfile.ZipInfo(name, EPOCH)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, path.read_bytes())
    write_licenses(package, version)
    size = sum(path.stat().st_size for path in files.values())
    print(f'{output}：{len(files)} 个文件，解开 {size / 1e6:.1f} MB，压缩后 {output.stat().st_size / 1e6:.1f} MB')


if __name__ == '__main__':
    main()
