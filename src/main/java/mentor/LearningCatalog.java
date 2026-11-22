package mentor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import mentor.MentorState.Project;
import mentor.MentorState.Task;

final class LearningCatalog {
    private LearningCatalog() {}

    private static final int THEORY_MINUTES = 45;
    static final int ALGORITHM_MINUTES = 25;
    private static final int INTERVIEW_MINUTES = 30;
    static final int INTERVIEW_TRACK_MINUTES = THEORY_MINUTES + ALGORITHM_MINUTES + INTERVIEW_MINUTES;
    static final int PRACTICE_MINUTES = 20;
    static final String METANIT_JAVA = "https://metanit.com/java/tutorial/";
    static final String YOUTUBE_PLAYLIST_1 = "https://www.youtube.com/playlist?list=PL5BhKu-LkR_3zYHvQZ-AjkmcYciv65QVB";
    static final String YOUTUBE_PLAYLIST_2 = "https://www.youtube.com/playlist?list=PL5BhKu-LkR_0mDGftRWeaakqGuYE1ToGG";
    static final String T04B = "t04b";
    static final String T03_CONTEXT = "T03 — завершённый Spring Boot проект «Крестики-нолики», основа для T04 и T05. "
            + "Используй его реализацию как отправную точку; запуск T03 нужен для сверки перед переносом в T04B.";
    static final String T04B_DESCRIPTION = "Продолжение серверных крестиков-ноликов T03. PostgreSQL, JPA и CrudRepository "
            + "вместо GameStorage. Пользователи с UUID, регистрация, Basic Auth, AuthFilter и SecurityFilterChain. "
            + "Состояния партии, значки, игра с человеком или компьютером, список доступных игр, вступление, ход, "
            + "чтение игры и публичных данных пользователя. Решение в src ветки develop.";

    static final String T03_TRACE = "По исходникам T03 (снимок develop a079b99): Swagger UI /swagger-ui/index.html. "
            + "Ход — POST /game/{gameId}, gameId — UUID. В Swagger есть пример «Первый ход». "
            + "Цепочка: GameController.play → GameServiceImpl.play → GameRepositoryImpl.update → GameStorage.update "
            + "→ games.compute(id, ...). Поле games имеет тип ConcurrentHashMap<UUID, StoredGame>, поэтому игра хранится в памяти.";
    static final String T04B_REQUIREMENTS = """
            Условие T04B, переданное учеником:
            1. PostgreSQL через application.properties, убрать класс-хранилище, добавить аннотации сущностей, репозитории на CrudRepository.
            2. Пользователь с UUID, логином и паролем на всех слоях. SignUpRequest, сервис и публичные маршруты регистрации и авторизации.
               Авторизация принимает Base64(login:password) в заголовке и возвращает UUID. AuthFilter наследует GenericFilterBean,
               пропускает запрос при успешной проверке и возвращает 401 иначе. SecurityFilterChain требует авторизацию для остальных маршрутов.
            3. Игра с человеком или компьютером. Состояния: ожидание, ход игрока с UUID, ничья, победа игрока с UUID.
               Значки игроков и проверка конца партии. Маршруты: создание игры, список доступных игр, присоединение, ход,
               получение игры и получение информации о пользователе по UUID.
            PDF /pdf project — пособие по T04B. Ежедневные шаги составлены для этого бота и могут дробить официальные пункты.
            """;

    /**
     * exercise — what the student must send after reading; criterion — how the review grades it.
     * Both live together, so the criterion always matches the visible task.
     */
    private record Topic(String id, String title, String exercise, String criterion, String question, String answerKey) {}

    private static final List<Topic> TOPICS = List.of(
            new Topic("collections", "Равенство и HashMap",
                    "Напиши класс User(id, name), где equals и hashCode считаются только по id. Положи в HashMap<User, String> "
                            + "два разных объекта User с одинаковым id.\nПришли: код и сколько записей окажется в мапе и почему.",
                    "equals и hashCode согласованы и используют id. В мапе одна запись: HashMap ищет корзину по hashCode, "
                            + "затем сравнивает equals. Без переопределения hashCode было бы две записи.",
                    "Почему одинаковый hashCode не означает, что два объекта равны?",
                    "Равные объекты обязаны иметь одинаковый hashCode, у разных возможна коллизия."),
            new Topic("mvc", "Spring MVC",
                    "Есть запрос POST /games с телом {\"size\": 3}. Напиши метод контроллера, который получает это тело "
                            + "в объект CreateGameRequest.\nПришли: метод с аннотациями и одну фразу — кто превращает JSON в объект.",
                    "@PostMapping(\"/games\") и параметр @RequestBody CreateGameRequest. JSON в объект превращает "
                            + "HttpMessageConverter на базе Jackson до вызова метода.",
                    "Какие два случая могут остановить запрос до вызова твоего контроллера?",
                    "Различает маршрут, преобразование параметров, десериализацию JSON и вызов метода."),
            new Topic("di", "IoC и внедрение зависимостей",
                    "Есть цепочка GameController → GameService → GameRepository. Напиши GameService с внедрением "
                            + "репозитория через конструктор.\nПришли: 5–10 строк кода и 1–2 фразы — кто создаёт эти объекты в Spring.",
                    "Репозиторий передаётся через конструктор (лучше в final-поле), класс помечен @Service или объявлен бином. "
                            + "Объекты создаёт и связывает IoC-контейнер Spring, а не new в коде.",
                    "Зачем внедрять репозиторий через конструктор и как это помогает тестированию?",
                    "Контейнер создаёт бины и подставляет зависимости. В тесте можно явно передать замену."),
            new Topic("interfaces", "Интерфейсы и абстрактные классы",
                    "Приведи один пример, где нужен интерфейс, и один, где нужен абстрактный класс.\n"
                            + "Пришли: по 2–3 строки кода на каждый и одну фразу — почему выбрал именно так.",
                    "Интерфейс — контракт без состояния, который могут реализовать несвязанные классы, и их может быть несколько. "
                            + "Абстрактный класс — общие поля, конструктор или общая реализация; наследование только одно. Примеры этому соответствуют.",
                    "Два несвязанных интерфейса определяют одинаковый default-метод. Что должен сделать класс?",
                    "Класс обязан переопределить метод и может вызвать нужную реализацию через Interface.super.method()."),
            new Topic("jpa", "JPA и модели",
                    "На примере крестиков-ноликов: чем отличаются GameEntity (JPA), Game (доменная модель) и GameDto?\n"
                            + "Пришли: по фразе о роли каждого и что сломается, если отдавать Entity прямо в API.",
                    "Entity — отображение на таблицу (@Entity, @Id), доменная модель — правила игры без зависимостей от БД, "
                            + "DTO — формат API. Отдача Entity наружу связывает API со схемой БД и может отдать лишние поля.",
                    "Кто реализует интерфейс CrudRepository и за что отвечает адаптер репозитория?",
                    "Реализацию создаёт Spring Data при запуске. Адаптер переводит доменную модель в сущности и обратно."),
            new Topic("atomicity", "Атомарность и видимость",
                    "Два потока одновременно делают count++ над общим полем int count = 0.\n"
                            + "Пришли: по шагам (чтение / +1 / запись), как после двух инкрементов получается 1, и один способ это починить.",
                    "Оба потока читают 0 до записи и оба пишут 1: count++ — это три операции, а не одна. "
                            + "Починка — synchronized, Lock или AtomicInteger.incrementAndGet(); volatile не помогает.",
                    "Почему volatile не делает count++ атомарным? Назови один рабочий способ защиты.",
                    "Два потока могут прочитать старое значение. Подойдёт общий монитор или AtomicInteger."),
            new Topic("sql", "LEFT JOIN и COUNT",
                    "Таблицы users(id, name) и orders(id, user_id). Напиши запрос: имя каждого пользователя и число его заказов, "
                            + "включая тех, у кого заказов нет.\nПришли: SQL.",
                    "LEFT JOIN orders ON orders.user_id = users.id, GROUP BY пользователю и COUNT(orders.id). "
                            + "COUNT(*) не подходит: у пользователя без заказов он даст 1.",
                    "Что вернут COUNT(*) и COUNT(o.id) для пользователя без заказов после LEFT JOIN и GROUP BY?",
                    "LEFT JOIN сохраняет одну строку. COUNT(*) даёт 1, COUNT(o.id) даёт 0."),
            new Topic("transactions", "Транзакции",
                    "Перевод 100 ₽ со счёта A на счёт B — это два UPDATE. Приложение упало сразу после первого.\n"
                            + "Пришли: что будет с деньгами без транзакции и что будет с @Transactional.",
                    "Без транзакции списание сохранится, а зачисление нет — деньги пропадут. С транзакцией изменения "
                            + "фиксируются вместе или откатываются вместе (commit/rollback).",
                    "Почему два отдельных вызова чтения и записи могут потерять конкурентное изменение?",
                    "Объясняет окно гонки и называет защиту, например проверку версии или блокировку."),
            new Topic("streams", "Stream API",
                    "Из List.of(\"java\", \"\", \"spring\", \"sql\") получи через Stream API список непустых строк "
                            + "в верхнем регистре.\nПришли: код и результат.",
                    "Есть filter по непустым строкам, map(String::toUpperCase) и toList() или collect. "
                            + "Результат [JAVA, SPRING, SQL].",
                    "Когда начинают выполняться промежуточные операции Stream? Можно ли запустить тот же Stream второй раз?",
                    "Называет ленивость промежуточных операций, терминальную операцию и одноразовое потребление потока."),
            new Topic("exceptions", "Исключения",
                    "Что напечатает вызов f() и что он вернёт?\n```java\nstatic int f() {\n    try {\n        System.out.println(\"A\");\n"
                            + "        throw new IllegalStateException();\n    } catch (IllegalStateException e) {\n        System.out.println(\"B\");\n"
                            + "        return 1;\n    } finally {\n        System.out.println(\"C\");\n    }\n}\n```\n"
                            + "Пришли: вывод, возвращаемое значение и одну фразу — почему C печатается, хотя в catch уже был return.",
                    "Вывод A, B, C; метод вернёт 1. finally выполняется перед фактическим выходом из метода, даже после return в catch.",
                    "Чем checked-исключения отличаются от unchecked и как закрывается ресурс при ошибке?",
                    "Checked компилятор требует поймать или объявить в throws, unchecked — наследники RuntimeException. "
                            + "Ресурс закрывает try-with-resources через AutoCloseable.close(), в том числе при ошибке."),
            new Topic("dto", "DTO и валидация",
                    "Пришёл заказ {\"productId\": \"abc\", \"quantity\": -5}, а на складе 3 штуки.\n"
                            + "Пришли: список ошибок, для каждой — где её ловить (DTO и @Valid или сервис) и какой HTTP-код вернуть.",
                    "Неверный формат productId и отрицательное quantity ловит валидация DTO (@Valid) — 400. "
                            + "Нехватка остатка — бизнес-правило в сервисе — 409 или 422.",
                    "Почему формат UUID и проверка доступного остатка товара живут в разных слоях?",
                    "Различает транспортную проверку входа и предметное правило."),
            new Topic("http-auth", "Аутентификация HTTP",
                    "Пользователь залогинен и открывает /orders/42, но заказ чужой. Какой код вернуть? А если запрос вообще без логина?\n"
                            + "Пришли: два кода и 1–2 фразы о разнице.",
                    "Без аутентификации — 401. Аутентифицирован, но нет прав на чужой заказ — 403 (или 404, чтобы скрыть существование). "
                            + "Различает аутентификацию и авторизацию.",
                    "Что отличает HTTP 401 от 403? Почему Base64 не шифрует пароль?",
                    "Различает отсутствующую или неверную аутентификацию и недостаточные права. Base64 — обратимое кодирование."),
            new Topic("tests", "Уровни тестов",
                    "Метод discount(sum) даёт скидку 10% начиная с 1000 ₽. Напиши на него JUnit-тест.\n"
                            + "Пришли: тест и одну фразу — какую ошибку найдёт только интеграционный тест с базой.",
                    "Юнит-тест проверяет граничные значения (например 999 и 1000) через assertEquals без БД и Spring. "
                            + "Интеграционный ловит ошибки маппинга, схемы, SQL или транзакций.",
                    "Какие ошибки найдёт интеграционный тест с БД, которые пропустит заглушка?",
                    "Приводит пример ошибки схемы, отображения или поведения транзакции.")
    );

    /** Criteria for lesson cards created before the exercises were rewritten. */
    private static final java.util.Map<String, String> LEGACY_THEORY_CRITERIA = java.util.Map.ofEntries(
            java.util.Map.entry("collections", "Ключи с одинаковым ID равны и имеют одинаковый hashCode. Пример показывает одну запись в HashMap."),
            java.util.Map.entry("interfaces", "Два примера: интерфейс как общий контракт и абстрактный класс для общего состояния или поведения."),
            java.util.Map.entry("mvc", "На примере POST показывает привязку JSON из тела запроса к аргументу метода контроллера."),
            java.util.Map.entry("di", "Верно связывает контроллер, сервис и репозиторий, указывает, что зависимости создаёт и передаёт контейнер."),
            java.util.Map.entry("jpa", "На примере игры различает хранение в JPA, правила доменной модели и данные, передаваемые через DTO."),
            java.util.Map.entry("atomicity", "Показывает порядок чтения и записи двух потоков, при котором один инкремент теряется."),
            java.util.Map.entry("sql", "Запрос сохраняет пользователей без заказов и считает заказы по непустому полю правой таблицы."),
            java.util.Map.entry("transactions", "Часть одной транзакции включает оба изменения счёта. При откате ни одно из них не сохраняется."),
            java.util.Map.entry("streams", "Пример использует filter и map перед collect или toList."),
            java.util.Map.entry("exceptions", "Называет порядок: код try до исключения, затем подходящий catch, затем finally. "
                    + "Достаточно короткого ответа по сути, в том числе «в таком порядке», если порядок перечислен в вопросе."),
            java.util.Map.entry("dto", "На примере заказа различает проверку формата входных данных и проверку бизнес-правила."),
            java.util.Map.entry("http-auth", "Различает проверку личности и проверку права читать чужой заказ."),
            java.util.Map.entry("tests", "Один тест проверяет правило скидки изолированно, другой проверяет сохранение заказа через базу."));

    private record Problem(int id, String title, String slug, String criterion) {}
    private static final String TOP_K_CRITERION = "Возвращает k различных наиболее частых значений в любом порядке. "
            + "Min-heap по частоте, из которого удаляют минимум при размере больше k, решает задачу. "
            + "Убывающий порядок при извлечении из готовой кучи не требуется. Проверь оценку времени и памяти.";

    private static final List<Problem> PROBLEMS = List.of(
            new Problem(1, "Two Sum", "two-sum",
                    "Нужно вернуть индексы двух разных элементов с заданной суммой. Учитывает одинаковые значения на разных позициях."),
            new Problem(20, "Valid Parentheses", "valid-parentheses",
                    "Учитывает тип и порядок вложенности скобок, лишнюю закрывающую скобку и незакрытые пары."),
            new Problem(121, "Best Time to Buy and Sell Stock", "best-time-to-buy-and-sell-stock",
                    "Одна покупка до одной продажи. На невозрастающих ценах возвращает 0."),
            new Problem(49, "Group Anagrams", "group-anagrams",
                    "Группирует все строки по совпадающим частотам букв. Учитывает пустые строки и дубликаты."),
            new Problem(704, "Binary Search", "binary-search",
                    "Возвращает индекс цели или -1 в отсортированном массиве. Границы корректны, время O(log n)."),
            new Problem(347, "Top K Frequent Elements", "top-k-frequent-elements",
                    TOP_K_CRITERION),
            new Problem(217, "Contains Duplicate", "contains-duplicate",
                    "Определяет, повторяется ли хотя бы одно значение. Проверяет случай без дубликатов и сложность."),
            new Problem(11, "Container With Most Water", "container-with-most-water",
                    "Возвращает максимальную площадь и объясняет, почему можно сдвинуть меньшую сторону."),
            new Problem(33, "Search in Rotated Sorted Array", "search-in-rotated-sorted-array",
                    "Ищет за O(log n), корректно определяет отсортированную половину и возвращает -1 при отсутствии цели."),
            new Problem(53, "Maximum Subarray", "maximum-subarray",
                    "Ищет максимум по непустым непрерывным подмассивам, в том числе когда все числа отрицательные."),
            new Problem(56, "Merge Intervals", "merge-intervals",
                    "Объединяет пересекающиеся и соприкасающиеся интервалы, учитывает произвольный входной порядок."),
            new Problem(98, "Validate Binary Search Tree", "validate-binary-search-tree",
                    "Соблюдает ограничения всех предков и строгий порядок, включая случай повторяющегося значения."),
            new Problem(102, "Binary Tree Level Order Traversal", "binary-tree-level-order-traversal",
                    "Возвращает отдельный список для каждого уровня слева направо и пустой список для пустого дерева."),
            new Problem(198, "House Robber", "house-robber",
                    "Находит максимальную сумму несмежных элементов и объясняет переход и базовые случаи."),
            new Problem(238, "Product of Array Except Self", "product-of-array-except-self",
                    "Возвращает произведения без деления за O(n) и учитывает случаи с одним и двумя нулями."),
            // Classic interview set (Blind 75 and neighbours), roughly from easier to harder.
            p(242, "Valid Anagram", "valid-anagram"), p(125, "Valid Palindrome", "valid-palindrome"),
            p(206, "Reverse Linked List", "reverse-linked-list"), p(21, "Merge Two Sorted Lists", "merge-two-sorted-lists"),
            p(141, "Linked List Cycle", "linked-list-cycle"), p(104, "Maximum Depth of Binary Tree", "maximum-depth-of-binary-tree"),
            p(100, "Same Tree", "same-tree"), p(226, "Invert Binary Tree", "invert-binary-tree"),
            p(70, "Climbing Stairs", "climbing-stairs"), p(268, "Missing Number", "missing-number"),
            p(191, "Number of 1 Bits", "number-of-1-bits"), p(338, "Counting Bits", "counting-bits"),
            p(190, "Reverse Bits", "reverse-bits"), p(155, "Min Stack", "min-stack"),
            p(167, "Two Sum II - Input Array Is Sorted", "two-sum-ii-input-array-is-sorted"),
            p(543, "Diameter of Binary Tree", "diameter-of-binary-tree"), p(110, "Balanced Binary Tree", "balanced-binary-tree"),
            p(572, "Subtree of Another Tree", "subtree-of-another-tree"), p(1046, "Last Stone Weight", "last-stone-weight"),
            p(235, "Lowest Common Ancestor of a Binary Search Tree", "lowest-common-ancestor-of-a-binary-search-tree"),
            p(128, "Longest Consecutive Sequence", "longest-consecutive-sequence"), p(15, "3Sum", "3sum"),
            p(3, "Longest Substring Without Repeating Characters", "longest-substring-without-repeating-characters"),
            p(424, "Longest Repeating Character Replacement", "longest-repeating-character-replacement"),
            p(150, "Evaluate Reverse Polish Notation", "evaluate-reverse-polish-notation"),
            p(739, "Daily Temperatures", "daily-temperatures"), p(74, "Search a 2D Matrix", "search-a-2d-matrix"),
            p(875, "Koko Eating Bananas", "koko-eating-bananas"),
            p(153, "Find Minimum in Rotated Sorted Array", "find-minimum-in-rotated-sorted-array"),
            p(143, "Reorder List", "reorder-list"), p(19, "Remove Nth Node From End of List", "remove-nth-node-from-end-of-list"),
            p(146, "LRU Cache", "lru-cache"), p(199, "Binary Tree Right Side View", "binary-tree-right-side-view"),
            p(230, "Kth Smallest Element in a BST", "kth-smallest-element-in-a-bst"),
            p(105, "Construct Binary Tree from Preorder and Inorder Traversal", "construct-binary-tree-from-preorder-and-inorder-traversal"),
            p(208, "Implement Trie (Prefix Tree)", "implement-trie-prefix-tree"),
            p(211, "Design Add and Search Words Data Structure", "design-add-and-search-words-data-structure"),
            p(215, "Kth Largest Element in an Array", "kth-largest-element-in-an-array"),
            p(973, "K Closest Points to Origin", "k-closest-points-to-origin"), p(78, "Subsets", "subsets"),
            p(39, "Combination Sum", "combination-sum"), p(46, "Permutations", "permutations"), p(79, "Word Search", "word-search"),
            p(200, "Number of Islands", "number-of-islands"), p(133, "Clone Graph", "clone-graph"),
            p(994, "Rotting Oranges", "rotting-oranges"), p(417, "Pacific Atlantic Water Flow", "pacific-atlantic-water-flow"),
            p(207, "Course Schedule", "course-schedule"), p(57, "Insert Interval", "insert-interval"),
            p(435, "Non-overlapping Intervals", "non-overlapping-intervals"), p(213, "House Robber II", "house-robber-ii"),
            p(5, "Longest Palindromic Substring", "longest-palindromic-substring"),
            p(647, "Palindromic Substrings", "palindromic-substrings"), p(91, "Decode Ways", "decode-ways"),
            p(322, "Coin Change", "coin-change"), p(152, "Maximum Product Subarray", "maximum-product-subarray"),
            p(139, "Word Break", "word-break"), p(300, "Longest Increasing Subsequence", "longest-increasing-subsequence"),
            p(62, "Unique Paths", "unique-paths"), p(1143, "Longest Common Subsequence", "longest-common-subsequence"),
            p(55, "Jump Game", "jump-game"), p(73, "Set Matrix Zeroes", "set-matrix-zeroes"),
            p(54, "Spiral Matrix", "spiral-matrix"), p(48, "Rotate Image", "rotate-image"),
            p(371, "Sum of Two Integers", "sum-of-two-integers"), p(377, "Combination Sum IV", "combination-sum-iv"),
            p(42, "Trapping Rain Water", "trapping-rain-water"), p(76, "Minimum Window Substring", "minimum-window-substring"),
            p(23, "Merge k Sorted Lists", "merge-k-sorted-lists"), p(124, "Binary Tree Maximum Path Sum", "binary-tree-maximum-path-sum"),
            p(297, "Serialize and Deserialize Binary Tree", "serialize-and-deserialize-binary-tree"),
            p(295, "Find Median from Data Stream", "find-median-from-data-stream"), p(212, "Word Search II", "word-search-ii")
    );

    private static Problem p(int id, String title, String slug) {
        return new Problem(id, title, slug, "Решение корректно на примерах и граничных случаях из условия "
                + "(пустой ввод, один элемент, повторы — что применимо); правильный рабочий код или верная идея.");
    }

    private static final java.util.regex.Pattern PROBLEM_LINK =
            java.util.regex.Pattern.compile("leetcode\\.com/problems/([a-z0-9-]+)");

    /** The catalog problem a text links to (the last link wins), or null. */
    static Integer linkedProblem(String text) {
        var matcher = PROBLEM_LINK.matcher(text);
        Integer found = null;
        while (matcher.find()) {
            String slug = matcher.group(1);
            found = PROBLEMS.stream().filter(problem -> problem.slug().equals(slug)).map(Problem::id).findFirst().orElse(found);
        }
        return found;
    }

    /** A catalog problem named in a message by title or LeetCode slug, or null. */
    static Integer namedProblem(String message) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return PROBLEMS.stream().filter(problem -> lower.contains(problem.title().toLowerCase(java.util.Locale.ROOT))
                        || lower.contains(problem.slug())).map(Problem::id).findFirst().orElse(null);
    }

    static String problemTitle(int id) {
        return PROBLEMS.stream().filter(problem -> problem.id() == id).map(Problem::title).findFirst().orElse("задача " + id);
    }

    /** The next unsolved catalog problems, for «дай задачу» in chat. */
    static List<String> unsolvedProblems(MentorState state, int limit) {
        return PROBLEMS.stream().filter(problem -> !state.solved.contains(problem.id())).limit(limit)
                .map(problem -> problem.id() + ". " + problem.title() + " — https://leetcode.com/problems/" + problem.slug() + "/")
                .toList();
    }

    private static final List<String> PROJECT_STEPS = List.of(
            "Запусти T03, в Swagger отправь POST /game/{gameId} с примером «Первый ход» и проследи вызовы до GameStorage.update и games.compute. Пришли цепочку и тип хранилища.",
            "Перенеси рабочую основу T03 в src T04B и запусти существующие тесты.",
            "Настрой PostgreSQL через application.properties и подтверди подключение приложения.",
            "Выбери представление доски в БД. Сохрани одну игру через CrudRepository и прочитай по UUID.",
            "Переключи игровой сценарий на PostgreSQL, убери GameStorage и проверь ход против компьютера.",
            "Перезапусти приложение и продолжи партию по тому же UUID. Проверь, что предыдущие ходы сохранились.",
            "Определи границу транзакции при ходе, чтобы чтение и запись защищали игру от потери обновлений.",
            "Сохрани пользователя с UUID и уникальным логином. Проверь, что в БД сохраняется хеш пароля.",
            "Добавь SignUpRequest, UserService и публичный endpoint регистрации. Проверь успех и повторный логин.",
            "Проверь Basic Auth: верные логин и пароль дают UUID пользователя.",
            "Подключи AuthFilter к SecurityFilterChain. Проверь публичные маршруты и 401 до вызова контроллера.",
            "Опиши состояния игры, UUID игроков и значки. Проверь переход от ожидания к первому ходу.",
            "Добавь создание игры в обоих режимах и получение доступных партий.",
            "Добавь присоединение и проверь, что второе место нельзя занять дважды.",
            "Проверь очерёдность и завершение игры, ход компьютера и отказ при ходе постороннего.",
            "Проверь чтение партии и получение публичных данных пользователя по UUID.",
            "Прогони сценарии T04B целиком, включая перезапуск, 401, игру вдвоём и игру с компьютером."
    );

    static List<Task> day(MentorState state, LocalDate date, int index, Set<Integer> chosen, Set<String> topics) {
        Task theory = nextTheory(state, date, index, topics);
        topics.add(theory.topic());
        return List.of(theory, nextAlgorithm(state, chosen), interview(state, date, theory.topic()));
    }

    static Task nextAlgorithm(MentorState state, Set<Integer> chosen) {
        return PROBLEMS.stream()
                .filter(problem -> !state.solved.contains(problem.id()) && chosen.add(problem.id()))
                .findFirst()
                .map(problem -> new Task("Алгоритмы", "leetcode-" + problem.id(), ALGORITHM_MINUTES,
                        problem.title() + ". Пришли решение или подход с оценкой времени и памяти.",
                        problem.criterion() + " Нужна оценка времени и памяти.",
                        "https://leetcode.com/problems/" + problem.slug() + "/"
                                + "\nИзучить: условие, ограничения и примеры входа/выхода. Раздел Solutions — после своей попытки.",
                        null, problem.id()))
                .orElseGet(() -> new Task("Алгоритмы", "algorithm-practice", ALGORITHM_MINUTES,
                        "Самостоятельный разбор. Выбери новую для себя задачу, сравни простой и улучшенный подход.",
                        "Есть условие, сравнение подходов, оценка затрат времени и памяти и один граничный случай.",
                        "", null, null));
    }

    private static final List<String> STUDY_ORDER = List.of(
            "collections", "interfaces", "exceptions", "atomicity", "sql", "di", "mvc",
            "dto", "jpa", "transactions", "streams", "http-auth", "tests");

    static Task nextTheory(MentorState state, LocalDate date, int index, Set<String> topics) {
        for (String topic : STUDY_ORDER) {
            var skill = state.skills.get(topic);
            if (!topics.contains(topic) && skill != null && !skill.demonstrated() && !skill.reviewOn().isAfter(date)
                    && state.lessons.stream().noneMatch(lesson -> !lesson.paused
                            && !Planner.date(state, lesson.start).isBefore(date)
                            && lesson.tasks.getFirst().topic().equals(topic))) {
                return theoryForTopic(topic);
            }
        }
        for (int step = 0; step < STUDY_ORDER.size(); step++) {
            Task task = theory(index + step);
            var skill = state.skills.get(task.topic());
            if (!topics.contains(task.topic()) && !state.knownTopics.contains(task.topic()) && (skill == null || !skill.demonstrated())
                    && state.lessons.stream().noneMatch(lesson -> !lesson.paused
                            && !Planner.date(state, lesson.start).isBefore(date)
                            && lesson.tasks.getFirst().topic().equals(task.topic()))) return task;
        }
        return theory(index);
    }

    static String topicId(String name) {
        return TOPICS.stream().filter(topic -> topic.id().equalsIgnoreCase(name) || topic.title().equalsIgnoreCase(name))
                .map(Topic::id).findFirst().orElse("");
    }

    static String topics() {
        return TOPICS.stream().map(topic -> topic.title() + " · " + topic.id())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    static Task theory(int index) {
        return theoryForTopic(STUDY_ORDER.get(Math.floorMod(index, STUDY_ORDER.size())));
    }

    static Task theoryForTopic(String id) {
        Topic topic = topic(id);
        return new Task("Теория", topic.id(), THEORY_MINUTES,
                topic.title() + ".\nПорядок: 25 минут на указанные разделы, затем 20 минут на задание."
                        + EXERCISE_MARK + topic.exercise(),
                topic.criterion(), StudyMaterials.forTopic(topic.id()), null, null);
    }

    private static final String EXERCISE_MARK = "\nПосле чтения: ";

    private static Topic topic(String id) {
        return TOPICS.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    static String gradingCriterion(Task task) {
        if (Integer.valueOf(347).equals(task.algorithmId())) return TOP_K_CRITERION;
        if (task.type().equals("Теория") && task.prompt().contains(EXERCISE_MARK) && STUDY_ORDER.contains(task.topic())) {
            Topic topic = topic(task.topic());
            // A card saved before the exercises were rewritten keeps its matching criterion.
            return task.prompt().endsWith(topic.exercise()) ? topic.criterion() : LEGACY_THEORY_CRITERIA.get(topic.id());
        }
        return task.criterion();
    }

    /** The part of a task the student must actually do, without reading lists. */
    static String exercise(Task task) {
        int mark = task.prompt().indexOf(EXERCISE_MARK);
        return mark < 0 ? task.prompt() : task.prompt().substring(mark + EXERCISE_MARK.length());
    }

    /** Lesson card text: what to read first, then the exercise and what to send — last and clearly marked. */
    static String card(Task task) {
        String prompt = task.prompt();
        String resource = task.resource().isBlank() ? "" : "\n\n" + task.resource();
        int mark = prompt.indexOf(EXERCISE_MARK);
        if (mark >= 0) {
            return prompt.substring(0, mark) + resource + "\n\n✍️ Задание: " + prompt.substring(mark + EXERCISE_MARK.length());
        }
        return switch (task.type()) {
            case "Алгоритмы" -> {
                int split = prompt.indexOf(". ");
                String title = split < 0 ? prompt : prompt.substring(0, split);
                yield title + resource
                        + "\n\n✍️ Задание: реши задачу. Пришли код (или идею словами) и сложность по времени и памяти."
                        + "\nБонус для собеса: одной фразой — почему решение работает.";
            }
            case "Собеседование" -> prompt.startsWith("1) ")
                    ? "Мини-собес, три вопроса:\n" + prompt + "\n\n✍️ Ответь на все три своими словами, можно одним сообщением."
                    : "Вопрос с собеса: " + prompt + "\n\n✍️ Ответь своими словами, как интервьюеру. Можно голосовым.";
            default -> prompt + resource + "\n\n✍️ Пришли: что сделал и как проверил (вывод тестов, ошибку или кусок кода).";
        };
    }

    static boolean isPlaylist(Task task) {
        return task.resource().equals(YOUTUBE_PLAYLIST_1) || task.resource().equals(YOUTUBE_PLAYLIST_2);
    }

    static Task interview(MentorState state, LocalDate date, String fallbackTopic) {
        Topic topic = TOPICS.stream()
                .filter(candidate -> state.skills.containsKey(candidate.id()))
                .filter(candidate -> !state.skills.get(candidate.id()).reviewOn().isAfter(date))
                .min(java.util.Comparator.<Topic, Boolean>comparing(
                        candidate -> state.skills.get(candidate.id()).demonstrated())
                        .thenComparing(candidate -> state.skills.get(candidate.id()).reviewOn()))
                .orElseGet(() -> TOPICS.stream().filter(candidate -> candidate.id().equals(fallbackTopic))
                        .findFirst().orElse(TOPICS.getFirst()));
        // Thirty minutes for one question was silly: a mini interview of three, two of them repeat studied topics.
        var pool = new java.util.ArrayList<Topic>(TOPICS.stream().filter(candidate -> state.skills.containsKey(candidate.id()))
                .sorted(java.util.Comparator.comparing((Topic candidate) -> state.skills.get(candidate.id()).reviewOn()))
                .toList());
        TOPICS.stream().filter(candidate -> candidate.id().equals(fallbackTopic)).findFirst().ifPresent(today -> pool.add(0, today));
        var review = pool.stream().filter(candidate -> !candidate.id().equals(topic.id())).distinct().limit(2).toList();
        if (review.isEmpty()) return interviewQuestion(topic.id());
        var prompt = new StringBuilder("1) ").append(topic.question()).append(" Приведи небольшой пример.");
        var criterion = new StringBuilder("1) ").append(topic.answerKey());
        for (int i = 0; i < review.size(); i++) {
            prompt.append("\n").append(i + 2).append(") ").append(review.get(i).question());
            criterion.append(" ").append(i + 2).append(") ").append(review.get(i).answerKey());
        }
        prompt.append("\nОтвечай как на собесе: 2–4 предложения на вопрос, можно голосовым.");
        criterion.append(" Засчитывай, если по сути верны все ответы; мелкие неточности — нюанс в feedback.");
        return new Task("Собеседование", topic.id(), INTERVIEW_MINUTES, prompt.toString(), criterion.toString(), "", null, null);
    }

    static String nextInterviewTopic(String previous) {
        return STUDY_ORDER.get((STUDY_ORDER.indexOf(previous) + 1) % STUDY_ORDER.size());
    }

    static Task interviewQuestion(String id) {
        Topic topic = topic(id);
        return new Task("Собеседование", topic.id(), INTERVIEW_MINUTES, topic.question()
                + " Приведи небольшой пример, который подтверждает ответ.",
                topic.answerKey(), "", null, null);
    }

    static final int T04B_STEPS = PROJECT_STEPS.size();

    private static final java.util.Map<String, String> DAILY_PRACTICE = java.util.Map.ofEntries(
            java.util.Map.entry("collections", "Напиши метод, который считает частоту слов в строке через HashMap<String, Integer>."
                    + "\nПришли: код и вывод для строки «a b a c b a»."),
            java.util.Map.entry("interfaces", "Сделай интерфейс Shape с методом area() и два класса: Circle и Rectangle. "
                    + "Посчитай сумму площадей списка фигур.\nПришли: код и вывод."),
            java.util.Map.entry("exceptions", "Напиши parseAge(String), который бросает IllegalArgumentException для не-числа "
                    + "и отрицательного возраста. Вызови его в try/catch для «25», «abc» и «-3».\nПришли: код и вывод."),
            java.util.Map.entry("atomicity", "Запусти два потока, каждый делает count++ 100 000 раз над общим int. Выведи итог. "
                    + "Потом почини через AtomicInteger.\nПришли: оба результата и код."),
            java.util.Map.entry("sql", "В любой онлайн-песочнице (например db-fiddle.com) создай users и orders, вставь 3 пользователей "
                    + "и 4 заказа, выполни запрос с LEFT JOIN и COUNT.\nПришли: SQL и результат."),
            java.util.Map.entry("di", "Без Spring собери вручную GameController(GameService(GameRepository)) через конструкторы "
                    + "и вызови один метод.\nПришли: код и одну фразу — что из этого сделал бы за тебя Spring."),
            java.util.Map.entry("mvc", "Сделай в Spring Boot эндпоинт GET /hello?name=…, который возвращает JSON "
                    + "{\"greeting\": \"Привет, name\"}.\nПришли: код контроллера и ответ curl."),
            java.util.Map.entry("dto", "Сделай record CreateOrderRequest с @NotNull и @Positive и эндпоинт с @Valid."
                    + "\nПришли: код и что вернёт запрос с quantity = -1."),
            java.util.Map.entry("jpa", "Опиши сущность GameEntity (@Entity, @Id типа UUID, поле board) и интерфейс "
                    + "GameRepository extends CrudRepository.\nПришли: код."),
            java.util.Map.entry("transactions", "Напиши в сервисе метод transfer(from, to, amount) с @Transactional, который бросает "
                    + "исключение при нехватке денег.\nПришли: код и что будет с балансами после исключения."),
            java.util.Map.entry("streams", "Через Stream API посчитай сумму квадратов чётных чисел от 1 до 20."
                    + "\nПришли: код и результат."),
            java.util.Map.entry("http-auth", "Отправь curl-запрос с Basic Auth на httpbin.org/basic-auth/user/pass — с верным "
                    + "и неверным паролем.\nПришли: команды и коды ответов."),
            java.util.Map.entry("tests", "Напиши isPalindrome(String) и JUnit 5 тест на три случая, включая пустую строку."
                    + "\nПришли: код метода и теста."));

    /** The fourth step of a daily lesson: a small hands-on task on the day's topic. School 21 lives in its own mode. */
    static Task dailyPractice(String topic) {
        String prompt = DAILY_PRACTICE.getOrDefault(topic,
                "Возьми сегодняшнюю тему, напиши минимальный Java-пример и проверь один граничный случай.\nПришли: код и вывод.");
        return new Task("Практика", "practice-" + topic, PRACTICE_MINUTES, prompt,
                "Код решает задачу и есть результат запуска или вывод. Мелкие недочёты — нюанс в разборе, не повод для доработки.",
                "", null, null);
    }

    static Task practice(Project project, int step) {
        if (project == null) {
            return new Task("Практика", "java-practice", PRACTICE_MINUTES,
                    "Возьми сегодняшнюю тему. Напиши минимальный Java- или SQL-пример и проверь один граничный случай.",
                    "Есть воспроизводимый пример, ожидаемый результат и объяснение проверки.", "", null, null);
        }
        if (T04B.equals(project.id())) {
            int index = Math.min(step, PROJECT_STEPS.size() - 1);
            String reading = index == 0 ? "стр. 3–4" : index < 2 ? "стр. 6–7" : index < 7 ? "стр. 8–13"
                    : index < 11 ? "стр. 14–17" : "стр. 18–24";
            return new Task("Школа 21", "t04b-" + index, PRACTICE_MINUTES, PROJECT_STEPS.get(index),
                    "Для зачёта нужен результат указанного шага и объяснение. Если результат частичный, помоги определить следующий короткий шаг.",
                    "T04B, " + reading + " (/pdf project)" + (index == 0 ? "\n" + T03_TRACE : ""), project.id(), null);
        }
        String prompt = switch (step) {
            case 0 -> "Прочитай условие. Назови первый шаг, критерий готовности и сделай минимальную проверку.";
            case 1 -> "Реализуй минимальный сценарий из условия. Покажи его запуск и объясни результат.";
            default -> "Выбери следующий незакрытый критерий из условия, реализуй его и покажи проверку.";
        };
        return new Task("Школа 21", "project-" + project.id(), PRACTICE_MINUTES, prompt,
                "Есть конкретный результат по условию, проверка и собственное объяснение.", "", project.id(), null);
    }
}
