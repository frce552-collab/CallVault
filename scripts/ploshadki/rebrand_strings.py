#!/usr/bin/env python3
"""ABP Call: ребрендинг строк поверх upstream CallVault (правило коммита 285c46e4).

Во всех app/src/main/res/values*/strings*.xml заменяет «CallVault» на «ABP Call» внутри
<string>, <plurals>/<item> и <string-array>/<item>, КРОМЕ строк атрибуции (GPL §7 п.4 —
ссылка на оригинал обязана остаться) и просьбы о поддержке авторов.
Повторный запуск ничего не меняет. Комментарии и копирайты в шапке файла не трогаются.

Зачем: синхронизация с madkongo/CallVault берёт строки upstream как есть и прогоняет
этот скрипт, вместо ручного разбора десятков конфликтов в 16 локалях.
"""
import pathlib, re, sys

KEEP = {
    "support_appeal_title", "support_appeal_body",
    "settings_fork_attribution", "settings_fork_attribution_callvault", "settings_fork_attribution_callvault_supporting",
    "disclaimer_body",  # шапка FORK и копирайты ABP Call ведутся руками в values/strings.xml
}
ELEM = re.compile(r'(<(string|plurals|string-array)\b[^>]*\bname="([^"]+)"[^>]*>)(.*?)(</\2>)', re.S)

def rebrand(text: str) -> str:
    def sub(m):
        if m.group(3) in KEEP:
            return m.group(0)
        return m.group(1) + m.group(4).replace("CallVault", "ABP Call") + m.group(5)
    return ELEM.sub(sub, text)

def main(root: str) -> int:
    changed = 0
    for p in sorted(pathlib.Path(root, "app/src/main/res").glob("values*/strings*.xml")):
        old = p.read_text(encoding="utf-8")
        new = rebrand(old)
        if new != old:
            p.write_text(new, encoding="utf-8")
            changed += 1
    print(f"rebrand_strings: files changed = {changed}")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
