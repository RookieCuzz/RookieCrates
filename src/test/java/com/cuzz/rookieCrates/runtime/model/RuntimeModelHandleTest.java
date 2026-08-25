package com.cuzz.rookieCrates.runtime.model;

import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModelHandleTest {

    @Test
    void emptyAddResultIsAcceptedWhenModelWasAttached() {
        ActiveModel active = activeModel(new AtomicBoolean());
        AtomicReference<ActiveModel> stored = new AtomicReference<>();
        ModeledEntity modeled = modeledEntity(stored, Optional.empty(), true);

        assertDoesNotThrow(() -> RuntimeModelHandle.attachModel(modeled, active, "default_crate"));
    }

    @Test
    void emptyAddResultIsRejectedOnlyWhenModelIsAbsent() {
        ActiveModel active = activeModel(new AtomicBoolean());
        ModeledEntity modeled = modeledEntity(new AtomicReference<>(), Optional.empty(), false);

        assertThrows(IllegalStateException.class,
                () -> RuntimeModelHandle.attachModel(modeled, active, "default_crate"));
    }

    @Test
    void replacedModelIsDestroyedAfterSuccessfulAttach() {
        AtomicBoolean previousDestroyed = new AtomicBoolean();
        ActiveModel previous = activeModel(previousDestroyed);
        ActiveModel active = activeModel(new AtomicBoolean());
        ModeledEntity modeled = modeledEntity(new AtomicReference<>(), Optional.of(previous), true);

        RuntimeModelHandle.attachModel(modeled, active, "default_crate");

        assertTrue(previousDestroyed.get());
    }

    private static ModeledEntity modeledEntity(
            AtomicReference<ActiveModel> stored,
            Optional<ActiveModel> addResult,
            boolean attach
    ) {
        return (ModeledEntity) Proxy.newProxyInstance(
                ModeledEntity.class.getClassLoader(),
                new Class<?>[]{ModeledEntity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "addModel" -> {
                        if (attach) stored.set((ActiveModel) args[0]);
                        yield addResult;
                    }
                    case "getModel" -> Optional.ofNullable(stored.get());
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ModeledEntityTestDouble";
                    default -> primitiveDefault(method.getReturnType());
                }
        );
    }

    private static ActiveModel activeModel(AtomicBoolean destroyed) {
        return (ActiveModel) Proxy.newProxyInstance(
                ActiveModel.class.getClassLoader(),
                new Class<?>[]{ActiveModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "destroy" -> {
                        destroyed.set(true);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ActiveModelTestDouble";
                    default -> primitiveDefault(method.getReturnType());
                }
        );
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        return null;
    }
}
