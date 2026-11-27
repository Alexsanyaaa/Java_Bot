package mentor;

/** Sets a private field in a test (replaces Spring's ReflectionTestUtils.setField). */
final class TestFields {
    private TestFields() {}

    static void set(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException(name, failure);
        }
    }
}
