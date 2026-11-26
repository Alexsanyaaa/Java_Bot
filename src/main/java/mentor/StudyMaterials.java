package mentor;

import java.util.List;
import java.util.stream.Collectors;

/** Hand-selected sections checked on 2026-11-14. No model or web request is needed to prepare a lesson. */
final class StudyMaterials {
    private StudyMaterials() {}

    record Reading(String title, String url, int minutes, String focus, String boundary) {
        String text() {
            return title + " · ~" + minutes + " мин\n" + url
                    + "\nИзучить: " + focus + "\nГраница чтения: " + boundary;
        }
    }

    static String forTopic(String topic) {
        return readings(topic).stream().map(Reading::text).collect(Collectors.joining("\n\n"));
    }

    static List<Reading> readings(String topic) {
        return switch (topic) {
            case "collections" -> List.of(
                    new Reading("Metanit: Object", "https://metanit.com/java/tutorial/3.9.php", 12,
                            "equals, hashCode и согласованность их результатов.",
                            "Только подразделы про hashCode и equals; клонирование пропустить."),
                    new Reading("Metanit: HashMap", "https://metanit.com/java/tutorial/5.8.php", 13,
                            "put, get, containsKey и замену значения по существующему ключу.",
                            "Разделы Map и HashMap; методы удаления по условию и обход всех вариантов API не нужны."));
            case "interfaces" -> List.of(
                    new Reading("Metanit: интерфейсы", "https://metanit.com/java/tutorial/3.7.php", 9,
                            "Объявление интерфейса, implements, методы default, static и private.",
                            "Разделы про методы; вложенные интерфейсы пропустить."),
                    new Reading("Metanit: конфликт реализаций", "https://metanit.com/java/tutorial/3.29.php", 8,
                            "Конфликт одинаковых default-методов и выбор реализации через Interface.super.",
                            "Пример Printable/Console и два способа устранения конфликта."),
                    new Reading("Metanit: абстрактные классы", "https://metanit.com/java/tutorial/3.6.php", 8,
                            "Состояние, конструктор и абстрактный метод; отличие от интерфейса.",
                            "Определение и первый пример наследования; остальные примеры по желанию."));
            case "di" -> List.of(new Reading("Spring: Dependency Injection (англ.)",
                    "https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html", 25,
                    "Кто создаёт зависимости и как передать сервису репозиторий через конструктор.",
                    "Введение и Constructor-based Dependency Injection до Constructor Argument Resolution. "
                            + "Оставшееся время — разобрать пример SimpleMovieLister; XML-конфигурации пропустить."));
            case "mvc" -> List.of(
                    new Reading("Spring: REST-контроллер (англ.)", "https://spring.io/guides/gs/rest-service/", 15,
                            "Связь URL, @RestController, @GetMapping, параметра и JSON-ответа.",
                            "Create a Resource Representation Class и Create a Resource Controller. Установку и сборку пропустить."),
                    requestBody(10, "Как JSON тела POST попадает в аргумент метода через HttpMessageConverter.",
                            "Введение и первый пример @RequestBody; валидацию подробно разберём отдельно."));
            case "dto" -> List.of(requestBody(25,
                    "@RequestBody, @Valid, ошибки входных данных и ответ 400. Отделить поля входного DTO от бизнес-правил.",
                    "Вся короткая страница, включая пример с Errors; по ссылкам на следующие главы не переходить."));
            case "jpa" -> List.of(new Reading("Spring: JPA и репозиторий (англ.)",
                    "https://spring.io/guides/gs/accessing-data-jpa/", 25,
                    "@Entity, @Id, конструктор сущности, CrudRepository и создание реализации интерфейса Spring Data.",
                    "Define a Simple Entity и Create Simple Queries. Сборку приложения и полный проект не повторять."));
            case "atomicity" -> List.of(
                    new Reading("Metanit: потоки и класс Thread", "https://metanit.com/java/tutorial/8.1.php", 7,
                            "Что такое поток, главный поток программы и зачем их несколько.",
                            "Вводная часть и методы Thread; join и sleep — только понять смысл."),
                    new Reading("Metanit: создание потоков", "https://metanit.com/java/tutorial/8.2.php", 6,
                            "Как запустить свой поток через Thread и Runnable и почему start(), а не run().",
                            "Первые два примера; лямбды для Runnable можно бегло."),
                    new Reading("Metanit: volatile", "https://metanit.com/java/tutorial/8.16.php", 5,
                            "Видимость изменения общей переменной между потоками.",
                            "Определение volatile и первый пример; выписать, какие операции выполняет каждый поток."),
                    new Reading("Metanit: атомарность", "https://metanit.com/java/tutorial/8.17.php", 7,
                            "Составные операции, потерю обновления и атомарный incrementAndGet.",
                            "Введение, пример счётчика и операции увеличения; массивы и атомарные ссылки пока пропустить."));
            case "sql" -> List.of(
                    new Reading("Metanit: LEFT JOIN", "https://metanit.com/sql/postgresql/6.3.php", 8,
                            "Какая строка остаётся у пользователя без заказов и где появляются NULL.",
                            "Только LEFT JOIN; RIGHT, FULL и CROSS JOIN пока пропустить."),
                    new Reading("Metanit: группировка соединения", "https://metanit.com/sql/postgresql/6.4.php", 10,
                            "Группировку по покупателю и подсчёт его заказов, включая покупателей без заказов.",
                            "Первые два запроса с Customers и Orders; пример с суммой товаров пропустить."),
                    new Reading("PostgreSQL: COUNT (англ.)", "https://www.postgresql.org/docs/current/functions-aggregate.html", 7,
                            "Различие count(*) и count(expression) при NULL.",
                            "Только две строки count в таблице General-Purpose Aggregate Functions."));
            case "transactions" -> List.of(
                    new Reading("PostgreSQL: транзакции (англ.)", "https://www.postgresql.org/docs/current/tutorial-transactions.html", 12,
                            "Перевод денег как единое действие, BEGIN, COMMIT и ROLLBACK.",
                            "От начала до SAVEPOINT; вложенные точки сохранения пока пропустить."),
                    new Reading("Spring: @Transactional (англ.)",
                            "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html", 13,
                            "Границу транзакции на сервисе, вызов через proxy и ограничение self-invocation.",
                            "Первый пример и примечание про self-invocation. Полную таблицу настроек не учить."));
            case "streams" -> List.of(
                    new Reading("Metanit: filter и map", "https://metanit.com/java/tutorial/10.3.php", 15,
                            "Фильтрацию и преобразование элементов; связать их в один конвейер.",
                            "Примеры filter и map; flatMap пока пропустить."),
                    new Reading("Java: Stream (англ.)", "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/Stream.html", 10,
                            "Ленивое выполнение, терминальную операцию и запрет повторного использования одного Stream.",
                            "Вводный текст до списка методов; полный справочник не читать."));
            case "exceptions" -> List.of(
                    new Reading("Metanit: try/catch/finally", "https://metanit.com/java/tutorial/2.10.php", 10,
                            "Порядок выполнения при успешном вызове и при исключении.",
                            "Первые примеры до раздела про сокращённую конструкцию."),
                    new Reading("Metanit: классы исключений", "https://metanit.com/java/tutorial/4.2.php", 8,
                            "Различие проверяемых и непроверяемых исключений, роль RuntimeException.",
                            "Иерархию и подразделы про два вида исключений; собственные типы пока не создавать."),
                    new Reading("Metanit: try-with-resources", "https://metanit.com/java/tutorial/6.2.php", 7,
                            "Закрытие ресурса при нормальном выходе и ошибке, роль AutoCloseable.",
                            "Первый пример автоматического закрытия; детали файлового API не учить."));
            case "http-auth" -> List.of(
                    new Reading("RFC 7617: HTTP Basic (англ.)", "https://www.rfc-editor.org/rfc/rfc7617.html", 8,
                            "Формат login:password, кодирование Base64 и необходимость защищённого канала.",
                            "Разделы 2 и 4; обсуждение кодировок в приложении пропустить."),
                    new Reading("Spring: Basic Authentication (англ.)",
                            "https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html", 8,
                            "Запрос без учётных данных, ответ 401 и повторный запрос с Authorization.",
                            "Первые две схемы прохождения запроса; конфигурацию пока не копировать."),
                    new Reading("Spring: права на запросы (англ.)",
                            "https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html", 9,
                            "Разницу между authenticated и проверкой полномочий для маршрута, отказ в доступе.",
                            "Введение и первый пример правил; полный перечень matchers пропустить."));
            case "tests" -> List.of(
                    new Reading("Spring: модульные тесты (англ.)", "https://docs.spring.io/spring-framework/reference/testing/unit.html", 12,
                            "Тестирование обычного объекта без контейнера, передачу зависимостей и замену внешних вызовов.",
                            "Введение перед Mock Objects; полный каталог заглушек не нужен."),
                    new Reading("Spring: интеграционные тесты (англ.)", "https://docs.spring.io/spring-framework/reference/testing/integration.html", 13,
                            "Проверку связей компонентов, контекста и транзакций с инфраструктурой.",
                            "Введение и Goals of Integration Testing; отдельные главы TestContext пока не открывать."));
            case "generics" -> List.of(new Reading("Dev.java: Generics", "https://dev.java/learn/generics/", 25,
                    "Инвариантность, upper and lower bounded wildcards.",
                    "Подразделы про wildcards, включая рекомендации, когда использовать extends и super."));
            case "optional" -> List.of(new Reading("Java 21: Optional",
                    "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html", 25,
                    "ofNullable, map, flatMap, orElse и orElseGet.",
                    "Прочитай описания этих методов и сравни поведение для пустого Optional."));
            case "executors" -> List.of(new Reading("Java 21: ExecutorService",
                    "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html",
                    25, "submit, Future, жизненный цикл пула.",
                    "Введение, примеры использования и определения submit и close."));
            case "sql-aggregates" -> List.of(new Reading("PostgreSQL: Aggregate Functions",
                    "https://www.postgresql.org/docs/current/tutorial-agg.html", 25,
                    "Порядок фильтрации WHERE, группировки GROUP BY и отбора групп HAVING.",
                    "Разбери два последних примера с GROUP BY и HAVING и придумай свой набор из 5 строк."));
            case "indexes" -> List.of(
                    new Reading("PostgreSQL: Indexes", "https://www.postgresql.org/docs/current/indexes-intro.html",
                            10, "Зачем нужен индекс и как он влияет на вставки.", "Только введение."),
                    new Reading("PostgreSQL: EXPLAIN", "https://www.postgresql.org/docs/current/using-explain.html",
                            15, "Seq Scan, Index Scan и оценка числа строк.",
                            "EXPLAIN Basics, первые примеры. EXPLAIN ANALYZE оставь на потом."));
            case "spring-errors" -> List.of(new Reading("Spring: Exceptions",
                    "https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html",
                    25, "@ExceptionHandler, @ControllerAdvice, ResponseEntity.",
                    "Введение, пример обработчика и возвращаемые значения. Форматы представлений пропусти."));
            case "collectors" -> List.of(new Reading("Java 21: Collectors",
                    "https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/Collectors.html",
                    25, "groupingBy, counting и toMap с функцией слияния.",
                    "Вводные примеры и только перегрузки для перечисленных операций."));
            case "mockmvc" -> List.of(new Reading("Spring: MockMvc",
                    "https://docs.spring.io/spring-framework/reference/testing/mockmvc.html", 25,
                    "Цель MockMvc, настройка, запрос и проверка результата.",
                    "Введение и первые примеры Setup Choices и Performing Requests. Достаточно одного теста."));
            default -> List.of();
        };
    }

    private static Reading requestBody(int minutes, String focus, String boundary) {
        return new Reading("Spring: @RequestBody (англ.)",
                "https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestbody.html",
                minutes, focus, boundary);
    }
}
