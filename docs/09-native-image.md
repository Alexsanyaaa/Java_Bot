# Native image: сборка на Linux Mint

Native image — бот, заранее скомпилированный GraalVM в обычную программу для Linux, без JVM. Ему не нужны
загрузка классов и JIT, поэтому памяти уходит меньше. Ожидание: около 35–50 МБ вместо ~70 МБ у JAR без Spring
и ~110 МБ у старого JAR со Spring. На сервере это ещё не проверено.

Собирать можно **только на Linux**: Windows соберёт `.exe`, а сервер его не запустит. И **не на сервере**:
сборке нужно 3–6 ГБ памяти, а там ВКР и VPN. Поэтому сборка идёт на втором компьютере с Linux Mint
(8 ГБ, 200 ГБ свободно).

Всё ниже вводится в терминале Mint. Команды можно копировать блоками.

> ⚠️ **Никогда не запускай бота на Mint с настоящим `TELEGRAM_BOT_TOKEN`.** Два бота с одним токеном отнимают
> друг у друга сообщения, и боевой бот начнёт терять твои ответы. Во всех проверках ниже токена нет, поэтому
> бот в Telegram не ходит.

---

## 1. Пакеты системы (один раз)

```sh
sudo apt update
sudo apt install -y git curl zip unzip build-essential zlib1g-dev
```

`build-essential` (gcc, ld) и `zlib1g-dev` нужны `native-image` для сборки программы.

Проверь версию glibc: она должна быть **не новее серверной**. На сервере Ubuntu 26.04 с glibc 2.43,
у Mint 21/22 версия 2.35/2.39, так что всё в порядке.

```sh
ldd --version | head -1
```

## 2. GraalVM 21 через SDKMAN (один раз)

```sh
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk list java | grep -E " 21\.[0-9.]+-graal "
```

Из списка возьми самую свежую строку вида `21.0.N-graal` (Oracle GraalVM для Java 21) и установи её:

```sh
sdk install java 21.0.N-graal      # подставь номер из списка
sdk default java 21.0.N-graal
```

Проверка: обе команды должны ответить без ошибок, в первой должно быть слово `GraalVM`.

```sh
java -version
native-image --version
```

Если SDKMAN не подходит, скачай архив «GraalVM for JDK 21, Linux x64» с graalvm.org/downloads, распакуй
в `~/graalvm-21` и добавь в `~/.bashrc`:

```sh
export JAVA_HOME="$HOME/graalvm-21"
export PATH="$JAVA_HOME/bin:$PATH"
```

## 3. Исходники

Первый раз:

```sh
mkdir -p ~/javka/botik
git clone https://github.com/Alexsanyaaa/Java_Bot.git ~/javka/botik/Java_Bot
cd ~/javka/botik/Java_Bot
```

Если GitHub спросит логин, значит репозиторий закрытый. Тогда войди через `gh auth login` (пакет `gh`) или
добавь SSH-ключ Mint в GitHub и клонируй по адресу `git@github.com:Alexsanyaaa/Java_Bot.git`.

Если клон уже есть, обнови его:

```sh
cd ~/javka/botik/Java_Bot
git pull --ff-only
```

Если `./gradlew` отвечает «Permission denied» (файл пришёл с Windows):

```sh
chmod +x gradlew
```

## 4. Обычная сборка и тесты на JVM

GraalVM — это ещё и обычный JDK, так что сначала проверь, что проект в порядке:

```sh
./gradlew test bootJar
```

Должно быть `BUILD SUCCESSFUL`, а файл `build/libs/java-mentor-bot.jar` около 5–6 МБ. Если тесты
падают, дальше не иди и пришли мне вывод.

## 5. Запись метаданных (что бот достаёт рефлексией)

Native image заранее должен знать, к каким классам код обращается «по имени»: так делают Jackson при чтении
состояния, H2 и HTTPS. Списки классов моего бота сборка генерирует сама. Для библиотек их записывает агент
GraalVM, пока работают тесты и самопроверка.

```sh
./gradlew -Pagent test
./gradlew metadataCopy
```

Потом самопроверка JAR под агентом на пустой учебной базе (не на боевой):

```sh
rm -rf /tmp/mentor-check && mkdir -p /tmp/mentor-check
MENTOR_DB_URL=jdbc:h2:file:/tmp/mentor-check/mentor \
  java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/mentor/java-mentor-bot \
  -jar build/libs/java-mentor-bot.jar --check --net
```

Должны появиться три строки `OK:` (состояние, `/today`, `HTTPS https://api.telegram.org → 302`).
В папке `src/main/resources/META-INF/native-image/mentor/java-mentor-bot/` появятся файлы `*-config.json`
или `reachability-metadata.json`:

```sh
ls src/main/resources/META-INF/native-image/mentor/java-mentor-bot/
```

## 6. Сборка native image

Закрой браузер и тяжёлые программы: сборка занимает 3–10 минут и до 6 ГБ памяти.

```sh
./gradlew nativeCompile
```

Результат — `build/native/nativeCompile/java-mentor-bot`, один файл на 60–100 МБ (это размер на диске, не
память). Если сборка оборвалась со словом `Killed`, памяти не хватило: закрой всё лишнее и повтори.

## 7. Проверка готовой программы

Самопроверка на той же пустой базе, уже без JVM:

```sh
rm -rf /tmp/mentor-check && mkdir -p /tmp/mentor-check
MENTOR_DB_URL=jdbc:h2:file:/tmp/mentor-check/mentor ./build/native/nativeCompile/java-mentor-bot --check --net
echo "код выхода: $?"
```

Нужны три строки `OK:` и `код выхода: 0`. Если вместо этого есть `MissingReflectionRegistrationError`,
`ClassNotFoundException` или `ОШИБКА:`, пришли мне вывод целиком. Значит, агент на шаге 5 чего-то не записал.

Память в простое (бот без токена: стартует, пишет «Telegram не настроен» и ждёт):

```sh
MENTOR_DB_URL=jdbc:h2:file:/tmp/mentor-check/mentor ./build/native/nativeCompile/java-mentor-bot -Xmx64m &
sleep 10; ps -o rss=,cmd= -p $!; kill $!
```

`rss` здесь в килобайтах: 40000 — это ~40 МБ. Пришли мне это число.

Необязательно, но полезно: все тесты, собранные в native, около 10–15 минут.

```sh
./gradlew nativeTest
```

## 8. Метаданные в git

Файлы из шага 5 нужно сохранить, чтобы следующие сборки (и у меня) их видели:

```sh
git add src/main/resources/META-INF/native-image
```

```sh
git commit -m "Native image metadata"
```

```sh
git push origin main
```

## 9. Программа — на сервер

Если с Mint есть SSH-доступ к серверу (ключ добавлен через `ssh-copy-id root@<IP-сервера>`):

```sh
scp build/native/nativeCompile/java-mentor-bot root@<IP-сервера>:/tmp/java-mentor-bot.native.new
```

Если доступа нет, перекинь файл на Windows-компьютер (флешка, общая папка, Telegram «Избранное») в
`C:\Users\467\Desktop\bot\java-mentor-bot` и скажи мне, откуда залить.

Дальше напиши мне «native залит». Выкладку я сделаю сам, по правилам: не утром, с бэкапами, после
самопроверки на копии боевой базы прямо на сервере. Ниже — что именно будет сделано, если вдруг
придётся без меня.

## 10. Выкладка native на сервер (что делаю я)

```sh
# a) самопроверка на копии последнего бэкапа, с потолком памяти 96 МБ, чтобы не задеть ВКР и VPN
ssh root@<IP-сервера> 'set -e
  install -o root -g root -m 755 /tmp/java-mentor-bot.native.new /opt/java-mentor-bot/java-mentor-bot-native.new
  d=$(mktemp -d); cp "$(ls -t /var/lib/java-mentor-bot/data/*.bak | head -1)" $d/mentor.mv.db
  systemd-run --scope -q -p MemoryMax=96M env MENTOR_DB_URL=jdbc:h2:file:$d/mentor \
      /opt/java-mentor-bot/java-mentor-bot-native.new --check --net
  rm -rf $d'

# b) замена: stop → бэкап базы → программа + профиль → start
scp deploy/systemd/80-native.conf root@<IP-сервера>:/tmp/80-native.conf
ssh root@<IP-сервера> 'set -e; ts=$(date -u +%Y%m%dT%H%M%SZ)
  systemctl stop java-mentor-bot
  install -o java-mentor -g java-mentor -m 600 /var/lib/java-mentor-bot/data/mentor.mv.db \
          /var/lib/java-mentor-bot/data/mentor.before-native-$ts.bak
  mv /opt/java-mentor-bot/java-mentor-bot-native.new /opt/java-mentor-bot/java-mentor-bot-native
  install -m 644 /tmp/80-native.conf /etc/systemd/system/java-mentor-bot.service.d/80-native.conf
  systemctl daemon-reload; systemctl reset-failed java-mentor-bot || true
  systemctl start java-mentor-bot'
```

Профиль [`80-native.conf`](../deploy/systemd/80-native.conf) только подменяет `ExecStart`. Сторож памяти,
лимиты 176/192 МБ и `EnvironmentFile` остаются прежними. JAR тоже остаётся на месте, поэтому откат —
одна команда:

```sh
ssh root@<IP-сервера> 'systemctl stop java-mentor-bot; rm /etc/systemd/system/java-mentor-bot.service.d/80-native.conf
  systemctl daemon-reload; systemctl reset-failed java-mentor-bot || true; systemctl start java-mentor-bot'
```

## 11. Следующие версии

Каждый раз после изменений в коде:

```sh
cd ~/javka/botik/Java_Bot && git pull --ff-only
./gradlew test bootJar
./gradlew -Pagent test && ./gradlew metadataCopy     # если добавились классы или библиотеки
./gradlew nativeCompile
```

Дальше шаг 7 (проверка) → шаг 9 (на сервер). Если в коде появился новый класс, сохраняемый в состоянии,
отдельно ничего делать не нужно: задача `nativeReflectionConfig` сама вносит все классы пакета `mentor`.

## Если что-то пошло не так

| Симптом | Причина | Что делать |
|---|---|---|
| `native-image: command not found` | `JAVA_HOME`/`PATH` смотрят не на GraalVM | `sdk default java 21.0.N-graal`, новый терминал |
| `Killed` / `Exit code 137` при сборке | Не хватило памяти | Закрыть браузер, повторить |
| `MissingReflectionRegistrationError`, `ClassNotFoundException` в `--check` | Агент не записал класс | Шаг 5 заново, вывод — мне |
| `GLIBC_2.xx not found` на сервере | Mint новее сервера | Собрать на более старой системе (или в Docker на Ubuntu 24.04) |
| `Illegal instruction` на сервере | Сборка под процессор Mint | В `build.gradle.kts` должен быть `-march=compatibility` (есть) |
| Gradle: `Could not resolve org.graalvm.buildtools.native` | Нет интернета или прокси | Проверить сеть, повторить |
