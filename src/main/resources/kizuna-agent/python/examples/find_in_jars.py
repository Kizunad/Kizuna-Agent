"""在所有模组的语言文件里找一个词，看它是哪个模组的哪一条。用法：python find_in_jars.py 词"""
import sys

from kizuna_mc import mc

word = sys.argv[1] if len(sys.argv) > 1 else "经脉"
matches = mc.jar_search(word, glob="**/lang/*.json", limit=200)
for match in matches:
    print(f"{match.mod}  {match.path}:{match.line}  {match.text}")
print(f"共 {len(matches)} 处")
