#!/usr/bin/env python3
"""ABP Call: ребрендинг строк поверх upstream CallVault (правило коммита 285c46e4).

Во всех app/src/main/res/values*/strings*.xml заменяет «CallVault» на «ABP Call» внутри
<string>, <plurals>/<item> и <string-array>/<item>, КРОМЕ строк атрибуции (GPL §7 п.4 —
ссылка на оригинал обязана остаться) и просьбы о поддержке авторов.
Повторный запуск ничего не меняет. Комментарии и копирайты в шапке файла не трогаются.

Зачем: синхронизация с madkongo/CallVault берёт строки upstream как есть и прогоняет
этот скрипт, вместо ручного разбора десятков конфликтов в 16 локалях.

Режим слияния одного файла (для конфликтов, в том числе базовой локали values/):
  rebrand_strings.py --merge OURS THEIRS OUT [BASE]
берёт файл upstream (THEIRS), применяет ребрендинг и возвращает из OURS: строки, которые
ведём руками (PRESERVE: шапка FORK, атрибуция), и строки, которых у upstream нет (наши добавления; с BASE — только те, которых не было и в общей
основе, чтобы не воскрешать строки, удалённые upstream).
"""
import pathlib, re, sys

KEEP = {
    "support_appeal_title", "support_appeal_body",
    "settings_fork_attribution", "settings_fork_attribution_callvault", "settings_fork_attribution_callvault_supporting",
    "disclaimer_body",  # шапка FORK и копирайты ABP Call ведутся руками в values/strings.xml
}
ELEM = re.compile(r'(<(string|plurals|string-array)\b[^>]*\bname="([^"]+)"[^>]*>)(.*?)(</\2>)', re.S)

# Строки, которые ведём руками в values/strings.xml (GPL §7 п.3–4): при слиянии берём нашу версию.
PRESERVE = {
    "disclaimer_body", "settings_fork_attribution",
    "settings_fork_attribution_callvault", "settings_fork_attribution_callvault_supporting",
}

def elements(text: str) -> dict:
    return {m.group(3): m.group(0) for m in ELEM.finditer(text)}

def merge(ours: str, theirs: str, base: str = "") -> str:
    out = rebrand(theirs)
    mine, up, old = elements(ours), elements(out), elements(base)
    def keep_ours(m):
        name = m.group(3)
        return mine[name] if name in PRESERVE and name in mine else m.group(0)
    out = ELEM.sub(keep_ours, out)
    added = [mine[n] for n in mine if n not in up and n not in old]
    if added:
        idx = out.rfind("</resources>")
        if idx < 0:
            raise SystemExit("no </resources> in THEIRS")
        out = out[:idx] + "    <!-- ABP Call: строки форка, которых нет у upstream -->\n" + "".join("    " + a + "\n" for a in added) + out[idx:]
    return out

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
    if len(sys.argv) in (5, 6) and sys.argv[1] == "--merge":
        o, t, out = (pathlib.Path(a) for a in sys.argv[2:5])
        base = pathlib.Path(sys.argv[5]).read_text(encoding="utf-8") if len(sys.argv) == 6 else ""
        out.write_text(merge(o.read_text(encoding="utf-8"), t.read_text(encoding="utf-8"), base), encoding="utf-8")
        print(f"rebrand_strings: merged {out}")
        sys.exit(0)
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
