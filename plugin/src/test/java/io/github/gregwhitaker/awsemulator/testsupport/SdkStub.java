package io.github.gregwhitaker.awsemulator.testsupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class SdkStub {
    private SdkStub() {}
    public static <T> T client(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
