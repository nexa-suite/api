package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Objects;

/** Wraps a central-backed use-case contract with a binding check at its public boundary. */
public final class CentralFallbackUseCaseGuard {
    private CentralFallbackUseCaseGuard() { }

    public static <T> T protect(Class<T> contract, T delegate, CentralTenantBusinessDataAccessGuard guard) {
        Objects.requireNonNull(contract, "Use-case contract is required");
        Objects.requireNonNull(delegate, "Central use-case delegate is required");
        Objects.requireNonNull(guard, "Tenant business access guard is required");
        if (!contract.isInterface() || !contract.isInstance(delegate)) {
            throw new IllegalArgumentException("Central use-case protection requires a matching interface");
        }
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(proxy, method, arguments, delegate);
            }
            CurrentAccessContext context = arguments == null ? null : Arrays.stream(arguments)
                    .filter(CurrentAccessContext.class::isInstance)
                    .map(CurrentAccessContext.class::cast)
                    .findFirst()
                    .orElse(null);
            if (context == null) {
                throw new TenantBusinessDatabaseUnavailableException(
                        "Central business access requires verified Tenant context");
            }
            guard.requireTenantBoundRoute(context);
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        };
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, handler);
        return contract.cast(proxy);
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] arguments, Object delegate) {
        return switch (method.getName()) {
            case "toString" -> "Tenant binding guarded " + delegate.getClass().getSimpleName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
            default -> throw new IllegalStateException("Unexpected Object method: " + method.getName());
        };
    }
}
