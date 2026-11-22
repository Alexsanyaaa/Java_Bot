# Сервер и выкладка

## Правила сервера (читать до любых действий)

- Сервер общий, 1 ГБ RAM. На нём **ВКР (диплом) — самое важное, особенно утром**, потом **VPN владельца**
  (без него у владельца нет интернета), и только потом бот.
- Трогать можно **только** бота: службу `java-mentor-bot`, `mentor-memory-guard`, папки `/opt/java-mentor-bot`,
  `/etc/java-mentor-bot`, `/var/lib/java-mentor-bot`. Другие службы, контейнеры и конфиги не смотреть даже на чтение.
- **Ничего не собирать на сервере** (Gradle съест память и уронит ВКР). JAR собирается на компьютере владельца.
- **Не выкладывать утром.** Каждая выкладка — после явного «да» владельца, перед ней — бэкап базы.
- Бот мешает ВКР или VPN → `systemctl stop java-mentor-bot`, потом разбираться.
- Ключи из `/etc/java-mentor-bot/mentor.env` не печатать, не отправлять в чаты и не вписывать в команды открытым текстом.

## Что где

```
/opt/java-mentor-bot/
  java-mentor-bot.jar            ← работающая версия
  java-mentor-bot.previous.jar   ← прошлая версия (для отката)
  java-mentor-bot.*-<дата>.jar   ← старые снимки (before-0400, codex-0400)
  README.md, docs/               ← эта документация
  source/                        ← копия исходников, только для чтения (собирать здесь нельзя)
  day-deadline/                  ← устаревшее: патч Codex от 21.11 (перенесён в исходники), хранится для истории
  project.pdf, spring.pdf        ← материалы для /pdf
  t04b/                          ← локальная копия репозитория T04B для «Проверить Git»
/etc/java-mentor-bot/
  mentor.env                     ← настройки и ключи (root, 600)
  mentor.env.before-*            ← копии перед правками
/var/lib/java-mentor-bot/data/
  mentor.mv.db                   ← база (одно JSON-состояние)
  mentor.before-<что>-<UTC>.bak  ← бэкапы перед каждой выкладкой
/etc/systemd/system/java-mentor-bot.service
/etc/systemd/system/java-mentor-bot.service.d/50-memory-guard.conf
/usr/local/sbin/mentor-memory-guard (+ mentor-memory-guard.timer/.service)
```

## Служба

`java-mentor-bot.service`: пользователь `java-mentor`, рабочая папка `/var/lib/java-mentor-bot`,
`EnvironmentFile=/etc/java-mentor-bot/mentor.env`, `ExecStart=/usr/bin/java -jar /opt/java-mentor-bot/java-mentor-bot.jar`.
Java — OpenJDK 21.

Защита памяти (`50-memory-guard.conf`):

- JVM: `-Xms16m -Xmx80m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=24m`.
- cgroup: `MemoryHigh=176M`, `MemoryMax=192M`, `MemorySwapMax=0`, `OOMScoreAdjust=800` — при нехватке памяти
  ядро первым убьёт бота, а не ВКР или VPN.
- `Restart=on-failure`, `RestartSec=300s`.
- `ExecCondition=mentor-memory-guard allow-start` — старт только если `MemAvailable ≥ 320 МиБ`.
- Таймер каждые 5 с запускает `mentor-memory-guard check`: если `MemAvailable < 192 МиБ`, бот
  останавливается, после паузы 300 с и при ≥ 320 МиБ запускается снова.

Обычно бот занимает ~100–110 МБ (`anon` в `memory.stat`).

Поэтому при выкладке всегда `stop` → замена → `start`, а не `restart`: новому процессу нужны 320 МиБ свободной
памяти, а старый должен их сначала отдать.

## Настройки (`mentor.env`)

| Переменная | Что это |
|---|---|
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_OWNER_ID` | Токен бота и Telegram ID владельца (бот отвечает только ему) |
| `LLM_BASE_URL`, `LLM_API_KEY` | OpenRouter |
| `LLM_MODEL`, `LLM_FALLBACK_MODELS` | Основная модель разговора и запасные по порядку |
| `LLM_ROUTER_MODELS`, `LLM_REVIEW_MODELS` | Классификатор и проверка (идут первыми для своих задач) |
| `GROQ_API_KEY` | Groq: модели `groq:` и распознавание голоса |
| `GEMINI_API_KEY` | Google AI Studio: модели `gemini:` |
| `MENTOR_PROJECT_PDF`, `MENTOR_SPRING_PDF` | Пути к PDF для `/pdf` |
| `MENTOR_T04B_SSH`, `MENTOR_T04B_LOCAL_REPO` | Адрес и локальная копия репозитория T04B |
| `MENTOR_STUDY_RESET_FROM` | ⚠ Дата сброса учёбы (сейчас `2026-11-16`). **Не трогать.** Если поставить дату позже уже применённой, при старте сотрутся уроки, навыки, переписка, пропуски, прогресс проектов |

Правка настроек: сначала копия, потом изменение, потом `stop`/`start`:

```sh
cp -p /etc/java-mentor-bot/mentor.env /etc/java-mentor-bot/mentor.env.before-<что>-$(date -u +%Y%m%dT%H%M%SZ)
sed -i "s|^LLM_MODEL=.*|LLM_MODEL='gemini:gemini-3.6-flash'|" /etc/java-mentor-bot/mentor.env
grep -E "^(LLM_MODEL|LLM_FALLBACK_MODELS)=" /etc/java-mentor-bot/mentor.env      # только не-секретные строки
```

Нужно значение ключа в скрипте — загружай файл в подоболочке: `( set -a; . /etc/java-mentor-bot/mentor.env; … )`.
Не вытаскивай его через `cut | tr -d "\x27"`: `tr` не понимает `\x27` и выкусывает из ключа символы `x`, `2`, `7`.

Ключ вставляет **владелец сам** — с подсказкой, которая не светит ключ в чат:

```powershell
$k = Read-Host "Gemini key"; ssh root@<IP-сервера> "sed -i s/^GEMINI_API_KEY=.*/GEMINI_API_KEY=$k/ /etc/java-mentor-bot/mentor.env"
```

## Выкладка

С компьютера владельца, Git Bash, из клона `Java_Bot`. `<IP-сервера>` в командах — адрес из `server.local.md` (файл лежит рядом с клоном, в git его нет).

```sh
cd "/c/Users/467/Desktop/bot/Java_Bot"
export JAVA_HOME="/c/Users/467/AppData/Local/Programs/Java/jdk-21.0.12.1+1"

# 1. Сборка и тесты (все зелёные, иначе не выкладывать)
./gradlew test bootJar
sha256sum build/libs/java-mentor-bot.jar

# 2. Проверка перед выкладкой: время (не утро!), что сейчас стоит, память
ssh root@<IP-сервера> 'TZ=Europe/Moscow date; sha256sum /opt/java-mentor-bot/java-mentor-bot.jar; free -m | sed -n 2p'
```

Если `sha256` работающего JAR не совпадает с последней твоей сборкой, его кто-то поменял в обход исходников
(так было 21.11 с Codex). Сначала разберись и перенеси чужую правку в исходники — иначе выкладка её откатит.

```sh
# 3. Выкладка: бэкап базы → копия JAR → замена → старт
scp build/libs/java-mentor-bot.jar root@<IP-сервера>:/tmp/java-mentor-bot.new.jar
ssh root@<IP-сервера> 'set -e; ts=$(date -u +%Y%m%dT%H%M%SZ)
  systemctl stop java-mentor-bot
  install -o java-mentor -g java-mentor -m 600 /var/lib/java-mentor-bot/data/mentor.mv.db \
          /var/lib/java-mentor-bot/data/mentor.before-<что>-$ts.bak
  cp -p /opt/java-mentor-bot/java-mentor-bot.jar /opt/java-mentor-bot/java-mentor-bot.previous.jar
  install -o root -g root -m 644 /tmp/java-mentor-bot.new.jar /opt/java-mentor-bot/java-mentor-bot.jar
  rm /tmp/java-mentor-bot.new.jar
  systemctl reset-failed java-mentor-bot || true
  systemctl start java-mentor-bot'

# 4. Проверка после
ssh root@<IP-сервера> 'sleep 20; journalctl -u java-mentor-bot --since "-2min" --no-pager | grep -E "Started MentorApplication|ERROR|Exception";
  systemctl show java-mentor-bot -p ActiveState -p NRestarts;
  grep -E "^anon " /sys/fs/cgroup/system.slice/java-mentor-bot.service/memory.stat; free -m | sed -n 2p'
```

Должно быть: `Started MentorApplication`, `ActiveState=active`, `NRestarts=0`, `anon` около 100–110 МБ.

Если старт не происходит — скорее всего, сторож памяти не пускает (меньше 320 МиБ свободно):
`journalctl -u mentor-memory-guard -u java-mentor-bot --since "-10min"`. Не освобождай память, останавливая
чужие службы. Подожди или оставь бота выключенным.

## Откат

```sh
ssh root@<IP-сервера> 'set -e
  systemctl stop java-mentor-bot
  cp -p /opt/java-mentor-bot/java-mentor-bot.previous.jar /opt/java-mentor-bot/java-mentor-bot.jar
  systemctl reset-failed java-mentor-bot || true
  systemctl start java-mentor-bot'
```

База откатывается, только если её испортила новая версия, — восстановление теряет всё, что было после бэкапа:

```sh
systemctl stop java-mentor-bot
install -o java-mentor -g java-mentor -m 600 /var/lib/java-mentor-bot/data/mentor.before-<что>-<ts>.bak \
        /var/lib/java-mentor-bot/data/mentor.mv.db
systemctl start java-mentor-bot
```

## Логи и проверки

```sh
journalctl -u java-mentor-bot --since "-1h" --no-pager | grep -E "\[(chat|router|review|voice)\]"   # кто отвечал
journalctl -u java-mentor-bot --since "-1h" --no-pager | grep -E "WARN|ERROR"                        # сбои
journalctl -u mentor-memory-guard -u java-mentor-bot --since "-1d" | grep -iE "memory|deferred|resuming"
systemctl show java-mentor-bot -p NRestarts -p ActiveEnterTimestamp
```

Тексты переписки в журнал не пишутся. Чтобы посмотреть историю, скопируй базу к себе:
`ssh root@… 'cat /var/lib/java-mentor-bot/data/mentor.mv.db' > mentor.mv.db` и читай её локально через
H2 (`org.h2.tools.Shell`, `CALL CSVWRITE('state.csv', 'SELECT payload FROM mentor_state', 'charset=UTF-8')`).
На сервере к базе ничего не подключай, пока бот работает.
