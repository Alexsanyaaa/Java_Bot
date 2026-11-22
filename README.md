# Java Mentor Bot — «Олег»

Личный Telegram-бот для подготовки к собеседованию на стажировку Java-бэкенд. Он:

- каждый день собирает урок из 4 шагов: теория, алгоритм LeetCode, вопрос с собеса, практика;
- сам понимает, что в сообщении ответ, и сразу отправляет его на проверку моделью;
- пинает напоминаниями весь день, держит стрик и долги, после пропуска включает «строгие часы» для Mentor Focus;
- раз в день задаёт вопрос по пройденному, проводит мини-собес, выдаёт разминочные задачи и сам их отмечает;
- отдельным режимом ведёт проект Школы 21 T04B к дедлайну.

Отвечает персонаж **Олег**: жёсткий ментор-кент с матом и мемами. Засчитывает ответы не он, а отдельная
строгая проверка. Олег только говорит.

> **Сервер общий.** На нём живут ВКР (диплом) и VPN владельца. Приоритет: **ВКР > VPN > бот**.
> Трогать можно только то, что относится к боту (список ниже). Ничего не собирать на сервере, не выкладывать
> утром, перед каждой выкладкой — бэкап базы. Если бот мешает ВКР или VPN — сначала
> `systemctl stop java-mentor-bot`, разбираться потом.

## Где что лежит

| Что | Где |
|---|---|
| Исходники (главная копия) | GitHub `Alexsanyaaa/Java_Bot`, ветка `main`; у владельца — клон `C:\Users\467\Desktop\bot\Java_Bot` |
| Адрес сервера | в командах — `<IP-сервера>`; настоящий адрес — в `server.local.md` рядом с клоном (в git не попадает) |
| Копия исходников на сервере (только для чтения) | `/opt/java-mentor-bot/source/` |
| Работающий JAR | `/opt/java-mentor-bot/java-mentor-bot.jar` (+ `*.previous.jar` — прошлая версия) |
| Настройки и ключи | `/etc/java-mentor-bot/mentor.env` (права 600, в логи и чаты не выводить) |
| База (H2, одно JSON-состояние) | `/var/lib/java-mentor-bot/data/mentor.mv.db`, бэкапы `mentor.before-*.bak` рядом |
| Служба systemd | `java-mentor-bot.service` + `java-mentor-bot.service.d/50-memory-guard.conf` |
| Сторож памяти | `/usr/local/sbin/mentor-memory-guard`, `mentor-memory-guard.timer/.service` |
| Документация | `/opt/java-mentor-bot/README.md` и `/opt/java-mentor-bot/docs/` (эта папка) |

## Документация

| Файл | О чём |
|---|---|
| [docs/01-user-guide.md](docs/01-user-guide.md) | Руководство пользователя: меню, команды, как сдавать, стрик, долг, строгие часы, Школа 21 |
| [docs/02-architecture.md](docs/02-architecture.md) | Как устроен бот: классы, потоки, состояние, расписания |
| [docs/03-models-and-prompts.md](docs/03-models-and-prompts.md) | Модели, ключи, лимиты, цепочки запасных, как собираются промпты и что в них нельзя трогать |
| [docs/04-server-and-deploy.md](docs/04-server-and-deploy.md) | Сервер, systemd, память, выкладка, откат, бэкапы, логи |
| [docs/05-development.md](docs/05-development.md) | Сборка, тесты, живые прогоны, инварианты, как безопасно менять код |
| [docs/06-troubleshooting.md](docs/06-troubleshooting.md) | Поломки: симптом → причина → где смотреть → как чинить |
| [docs/07-history.md](docs/07-history.md) | История решений и инцидентов: почему сделано именно так |
| [docs/08-prompts-verbatim.md](docs/08-prompts-verbatim.md) | Все промпты дословно (собирается скриптом из кода) |

## Шпаргалка на сервере

```sh
systemctl status java-mentor-bot --no-pager          # жив ли
journalctl -u java-mentor-bot --since "-1h" --no-pager | grep -E "\[chat\]|\[review\]|WARN|ERROR"
systemctl stop java-mentor-bot                       # аварийно остановить (ВКР/VPN важнее)
free -m                                              # память хоста (1 ГБ на всех)
```

Выкладка и откат — только по [docs/04-server-and-deploy.md](docs/04-server-and-deploy.md).
