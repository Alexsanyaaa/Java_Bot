"""Builds docs/08-prompts-verbatim.md from the Java sources, so the prompt texts in the docs never drift from the code.

Run from the project root:  python docs/tools/extract_prompts.py
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCES = ["MentorModel.java", "MentorTone.java"]
OUT = ROOT / "docs" / "08-prompts-verbatim.md"

# What each prompt is for, by constant or method name.
PURPOSE = {
    "PLAN_RULES": "Методист на неделю: выбирает темы из каталога по датам и учебному прогрессу.",
    "CHAT_RULES": "Характер Олега — системный промпт разговора в обоих режимах. Сюда же он добавляется в пересказ вердикта (voice).",
    "DAILY_REFERENCE": "Справка дневного режима (подготовка к собесу), идёт после CHAT_RULES.",
    "SCHOOL_REFERENCE": "Справка школьного режима (Школа 21, T04B), идёт после CHAT_RULES.",
    "ROUTER": "Классификатор сообщения: ответ на шаг урока, вопрос, болтовня или ответ на вопрос дня.",
    "route": "Добавка к классификатору, когда открыт вопрос дня (появляется вариант recall).",
    "review": "Проверка одного ответа: вопрос дня, мини-собес. Перед текстом стоит MODEL_STYLE.",
    "reviewLesson": "Проверка сдачи по шагам урока (JSON steps). Перед текстом стоит MODEL_STYLE.",
    "reviewProjectStep": "Проверка отчёта по шагу проекта Школы 21. Перед текстом стоит MODEL_STYLE.",
    "hint": "Одна подсказка по /hint. Перед текстом стоит MODEL_STYLE.",
    "reviewGit": "Сводка по git-метаданным проекта (кнопка «Проверить Git»).",
    "career": "Карьерный шаг (/career, /vacancy).",
    "talk": "Добавки к разговору: необязательный выбор Telegram-реакции и правила отчёта о ходе Java-проекта.",
    "voice": "Пересказ вердикта проверки голосом Олега. Перед текстом стоят CHAT_RULES; вердикт решает проверка, не Олег.",
    "MODEL_STYLE": "Общий стиль для проверок, подсказок, git-сводки и карьерных ответов (не для разговора).",
}

MEMBER = re.compile(r"^    (?![\s}/@*])(?:[\w<>\[\],.? ]+\s)?(\w+)\s*\(")
CONSTANT = re.compile(r"String\s+(\w+)\s*=\s*\"\"\"")


def blocks(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    method = ""
    i = 0
    while i < len(lines):
        line = lines[i]
        member = MEMBER.match(line)
        if member and not line.rstrip().endswith(";"):
            method = member.group(1)
        if line.count('"""') == 1:
            start = i
            body = []
            i += 1
            while i < len(lines) and '"""' not in lines[i]:
                body.append(lines[i])
                i += 1
            indent = min((len(b) - len(b.lstrip()) for b in body if b.strip()), default=0)
            text = "\n".join(b[indent:] for b in body).rstrip()
            constant = CONSTANT.search(line)
            yield start + 1, (constant.group(1) if constant else method), text
        i += 1


def main():
    out = [
        "# Промпты дословно",
        "",
        "Собрано скриптом `docs/tools/extract_prompts.py` из исходников — руками не править.",
        "Поменял промпт в коде — перезапусти скрипт. Источник истины — код, а не этот файл.",
        "Как промпты складываются в запросы, описано в `03-models-and-prompts.md`.",
        "",
    ]
    seen = {}
    for name in SOURCES:
        path = ROOT / "src" / "main" / "java" / "mentor" / name
        for number, title, text in blocks(path):
            seen[title] = seen.get(title, 0) + 1
            suffix = "" if seen[title] == 1 else f" (часть {seen[title]})"
            out += [f"## {title}{suffix}  ·  `{name}:{number}`", "",
                    PURPOSE.get(title, "Текст из кода."), "", "```text", text, "```", ""]
    OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"written {OUT}")


if __name__ == "__main__":
    main()
