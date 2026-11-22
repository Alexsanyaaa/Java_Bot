# Разработка

## Окружение

- Главная копия исходников — GitHub `Alexsanyaaa/Java_Bot`, ветка `main`. У владельца — клон
  `C:\Users\467\Desktop\bot\Java_Bot`, правки делаются в нём. Старая папка `C:\Users\467\Desktop\botik\New project` —
  архив, там не править.
  На сервере в `/opt/java-mentor-bot/source/` лежит копия только для чтения.
- JDK 21: `C:\Users\467\AppData\Local\Programs\Java\jdk-21.0.12.1+1`. Сборка — Gradle Wrapper (`./gradlew`).
- Коммиты и push владелец делает сам, тремя командами: `git add -A`, `git commit -m "…"`, `git push origin main`.
  В сообщениях коммитов — обычный текст, без строк `Co-Authored-By` и упоминаний ИИ-помощников.
  Почему сделано так, а не иначе, — в [07-history.md](07-history.md).
- `server.local.md` (адрес сервера) и `.env` (если нужен для локального запуска) — в `.gitignore`: в git, архивы и
  на сервер их не класть. В командах документации вместо адреса сервера стоит `<IP-сервера>`.

```sh
cd "/c/Users/467/Desktop/bot/Java_Bot"
export JAVA_HOME="/c/Users/467/AppData/Local/Programs/Java/jdk-21.0.12.1+1"
./gradlew test             # все тесты (живые пропускаются без переменных)
./gradlew test bootJar     # тесты + JAR в build/libs/java-mentor-bot.jar
```

## Структура

```
src/main/java/mentor/     код бота (описание классов — 02-architecture.md)
src/main/resources/       application.properties: имена переменных окружения
src/test/java/mentor/     тесты
docs/                     документация; docs/tools/extract_prompts.py — выгрузка промптов
android/                  Mentor Focus (Android, сборка: android/build-apk.sh)
deploy/systemd/           служба, сторож памяти, таймер — эталон для сервера
```

## Тесты

| Тест | Что проверяет |
|---|---|
| `MentorFlowTest` | Полный путь через фейковый Telegram и фейковую модель: команды, напоминания, сдача, проверка, переживание перезапуска, «не более одного раза» |
| `PreparedLessonsAndCheckinsTest` | Карточки уроков, свободный режим, сброс учёбы, правила пропуска, проектные отчёты |
| `RealChatRegressionTest` | Случаи из реальной переписки: короткие ответы, бот не засчитывает сам, долг и строгие часы |
| `NovemberNineteenRegressionTest` | Поддельные зачёты в чате, «дай задачу» с нерешёнными, код не проглатывается, апелляция вопроса дня, автоотметка разминки |
| `SchoolMentorTest` | Школьный режим, план T04B, отчёт о шаге |
| `DayBoundaryTest` | День 04:00–04:00, перенос старых уроков, стрик с пропуском, дедлайн в заметках и карточке, напоминания без «полуночи» |
| `GeminiAuthTest` | Ключ Gemini уходит в обоих заголовках |
| `LiveConversationTest` | **Живой** прогон диалога на настоящих моделях (пропускается без переменных) |
| `LiveReviewTest` | **Живая** проверка ответов (`MENTOR_LIVE_REVIEW`) |
| `ProductionCopyDryRunTest` | Прогон логики на копии прод-базы (`MENTOR_DRYRUN_DB=путь`) |

Фейковые модели в тестах узнают тип запроса по кускам промпта: `классификатор`, `"steps"`,
`вопрос с собеседования`, `Сейчас особая задача`. Поменял эти слова в промпте — поправь тесты.

Тесты называют по инцидентам: каждый реальный баг из переписки получает тест с датой и цитатой.

## Живые прогоны

Логика бота — настоящая, Telegram — фейковый, модели и ключи — с сервера (ключи никуда не печатаются):

```sh
cd "/c/Users/467/Desktop/bot/Java_Bot"
export JAVA_HOME="/c/Users/467/AppData/Local/Programs/Java/jdk-21.0.12.1+1"
eval "$(ssh -o BatchMode=yes root@<IP-сервера> 'grep -E "^(LLM_API_KEY|GROQ_API_KEY|GEMINI_API_KEY|LLM_BASE_URL|LLM_REVIEW_MODELS|LLM_ROUTER_MODELS)=" /etc/java-mentor-bot/mentor.env | sed "s/^/export /"')"
unset HTTPS_PROXY HTTP_PROXY
printf '%s\n' "Ты душнила" "кинь задание" "решил, accepted" > /tmp/script.txt
LLM_MODEL="gemini:gemini-3.6-flash" LLM_FALLBACK_MODELS="" \
MENTOR_LIVE_SCRIPT=/tmp/script.txt MENTOR_LIVE_PAUSE=6 MENTOR_LIVE_TRANSCRIPT=/tmp/live.txt \
  ./gradlew test --tests mentor.LiveConversationTest --rerun -q
cat /tmp/live.txt
grep -oE "\[(chat|router|review|voice)\][^<]{0,120}" build/test-results/test/TEST-mentor.LiveConversationTest.xml
```

- `--rerun` обязателен: Gradle не считает переменные окружения входами теста и иначе его пропустит.
- `LLM_FALLBACK_MODELS=""` — чтобы видеть именно эту модель, а не запасную.
- **Лимиты:** прогон тратит те же квоты, что бот. OpenRouter — 50 запросов в день на всё; вечерний прогон
  может оставить владельца ночью на слабой qwen. Прогоняй на Gemini или Groq. Для Groq ставь паузу 20–30 с:
  8000 токенов в минуту.
- Без `MENTOR_LIVE_SCRIPT` прогоняется встроенный сценарий из 15 реплик.

## Инварианты — не ломать

1. **Не более одного раза.** `updateOffset` сохраняется до обработки сообщения; напоминание помечается
   отправленным до отправки; вердикт снимается с очереди до отправки. Сбой Telegram не должен вызывать повторы
   (было: десятки одинаковых сообщений).
2. **Зачёт ставит только приложение** по JSON проверки. Ответ Олега проходит `withoutChatCredit`, строку
   вердикта пишет код. Никакой «модель сказала засчитано — засчитаем».
3. **Одно JSON-состояние.** Новые поля — только со значением по умолчанию. Не переименовывать и не удалять
   поля. Миграции идемпотентны (`curriculumVersion`, `migrateFlexibleDays`). Проверка — на копии прод-базы.
4. **Учебный день — через `Planner.date()`** (граница 04:00 в свободном режиме). `toLocalDate()` напрямую
   не вызывать.
5. **Никаких сетевых вызовов внутри `store.write(...)`.** Лямбда держит блокировку состояния: пока она висит
   на модели, стоят `poll()` и напоминания. Сначала модель, потом запись. Пример: `voiced()` вызывается до
   `store.write` в `reviewPending`.
6. **Секреты не в логах.** В журнал идут только имена моделей, время и класс исключения
   (`failure.getClass().getSimpleName()`), не тела ответов и не ключи.
7. **Память.** Heap 80 МБ. Никаких кешей и больших коллекций в памяти; размеры промптов ограничены
   (`clip`, `fit`, бюджеты `DayMemory`).
8. **Только бесплатные модели** — решение владельца.
9. Всё, что видит владелец, — по-русски.

## Как вносить изменения

1. Найди место по [02-architecture.md](02-architecture.md). Новая команда — `respondCommand` в `MentorBot`
   + `HELP` + таблица команд в [01-user-guide.md](01-user-guide.md). Новое напоминание — в `reminders()`,
   с отметкой «отправлено» до отправки. Новое поле состояния — в `MentorState` со значением по умолчанию.
2. Сначала тест, который воспроизводит проблему (лучше прямо с репликой из чата), потом исправление.
3. `./gradlew test` — всё зелёное.
4. Промпты или поведение модели — живой прогон (см. выше), сравни ответы до и после.
5. Обнови документацию: этот каталог и `python docs/tools/extract_prompts.py`, если трогал промпты.
6. Выкладка — только после «да» владельца, не утром, по [04-server-and-deploy.md](04-server-and-deploy.md).
   После выкладки залей обновлённую документацию и копию исходников на сервер (раздел ниже).

## Документация и копия исходников на сервере

```sh
cd "/c/Users/467/Desktop/bot/Java_Bot"
tar --exclude=.env --exclude=server.local.md -czf /tmp/mentor-src.tgz \
    README.md docs src android deploy prompts build.gradle.kts settings.gradle.kts gradle gradlew gradlew.bat \
    .env.example .gitignore .gitattributes
scp /tmp/mentor-src.tgz root@<IP-сервера>:/tmp/
ssh root@<IP-сервера> 'set -e; rm -rf /opt/java-mentor-bot/source.new; mkdir /opt/java-mentor-bot/source.new
  tar -xzf /tmp/mentor-src.tgz -C /opt/java-mentor-bot/source.new; rm /tmp/mentor-src.tgz
  rm -rf /opt/java-mentor-bot/source /opt/java-mentor-bot/docs
  mv /opt/java-mentor-bot/source.new /opt/java-mentor-bot/source
  cp -r /opt/java-mentor-bot/source/docs /opt/java-mentor-bot/docs
  cp /opt/java-mentor-bot/source/README.md /opt/java-mentor-bot/README.md
  chown -R root:root /opt/java-mentor-bot/source /opt/java-mentor-bot/docs /opt/java-mentor-bot/README.md'
```
