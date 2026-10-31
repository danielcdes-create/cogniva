package cogniva;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Replaces Paparazzi 1.3.5's Gradle 8 HTML reporter on Gradle 9.3. */
public final class NoopTestReporter implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString":
                return "noop-test-reporter";
            case "hashCode":
                return 0;
            case "equals":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    public static Object create(Class<?> reporterType) {
        return Proxy.newProxyInstance(
                reporterType.getClassLoader(),
                new Class<?>[] {reporterType},
                new NoopTestReporter());
    }
}
