package mentor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** Manual live check of lesson reviews on real submissions. Runs only with MENTOR_LIVE_REVIEW set to an output file. */
@EnabledIfEnvironmentVariable(named = "MENTOR_LIVE_REVIEW", matches = ".+")
class LiveReviewTest {
    static final String CODE_33 = """
            ```java
                public static int myBinarySearch(int[] arr, int target, int left, int right) {
                    while (left <= right) {
                        int mid = left + (right - left) / 2;
                        if (arr[mid] == target) {
                            return mid;
                        }
                        if (arr[mid] < target) {
                            left = mid + 1;
                        } else {
                            right = mid - 1;
                        }
                    }
                    return -1;
                }
                public static int mySearch(int[] nums, int target, int leftInd, int rightInd) {
                    if (leftInd < rightInd){
                        int mid = leftInd + (rightInd - leftInd) / 2;
                        if(mid == leftInd) {
                            if(target == nums[leftInd]) return leftInd;
                            else if (target == nums[rightInd]) return rightInd;
                        }
                        if(nums[leftInd] < nums[mid]) {
                            //слева отсортировано
                            if(target >= nums[leftInd] && target <= nums[mid]) {
                                return myBinarySearch(nums, target, leftInd, mid);
                            } else {
                                return mySearch(nums, target, mid, rightInd);
                            }
                        } else {
                            //Справа отсортировано
                            if(target >= nums[mid] && target <= nums[rightInd]) {
                                return myBinarySearch(nums, target, mid, rightInd);
                            } else {
                                return mySearch(nums, target, leftInd, mid);
                            }
                        }
                    } else if(leftInd == rightInd && nums[leftInd] == target){
                        return leftInd;
                    }
                    return -1;
                }
                public int search(int[] nums, int target) {
                    return mySearch(nums, target, 0, nums.length - 1);
                }
            ```

            Я тут, конечно, изобрел три велосипеда, к тому же с 5 колесами каждый. Но Accepted
            198 / 198 testcases passed
            Runtime 0ms Beats 100.00%

            По времени и памяти все хорошо. Время лог эН, память эН""";

    @Test
    void reviewsRealSubmissions() throws Exception {
        var json = JsonMapper.builder().build();
        var model = new MentorModel(json, env("LLM_BASE_URL", "https://openrouter.ai/api/v1"), env("LLM_MODEL", ""),
                env("LLM_API_KEY", ""));
        TestFields.set(model, "groqKey", env("GROQ_API_KEY", ""));
        TestFields.set(model, "fallbackModels", env("LLM_FALLBACK_MODELS", ""));
        TestFields.set(model, "reviewModels", env("LLM_REVIEW_MODELS", ""));
        var state = new MentorState();
        var chosen = new java.util.HashSet<Integer>(List.of(1, 20, 121, 49, 704, 347, 217, 11));
        var algorithm = LearningCatalog.nextAlgorithm(state, chosen);
        var tasks = List.of(algorithm, LearningCatalog.interviewQuestion("atomicity"), LearningCatalog.dailyPractice("atomicity"));
        var out = new StringBuilder("Задача: ").append(algorithm.prompt()).append('\n');
        for (int run = 1; run <= 3; run++) {
            var results = model.reviewLesson(2, tasks, "", List.of(), "", CODE_33);
            out.append("run ").append(run).append(": ").append(results).append('\n');
        }
        Files.writeString(Path.of(System.getenv("MENTOR_LIVE_REVIEW")), out);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.replaceAll("^'|'$", "");
    }
}
