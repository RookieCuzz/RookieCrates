package com.cuzz.rookieCrates.runtime;

import com.cuzz.rookieCrates.gui.api.CratesGuiFacade;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DrawSceneContractTest {

    @Test
    void publicDrawApiExposesExactlySingleAndSeven() {
        assertEquals(
                List.of(CratesGuiFacade.DrawType.SINGLE, CratesGuiFacade.DrawType.SEVEN),
                List.of(CratesGuiFacade.DrawType.values())
        );
        assertEquals(1, CratesGuiFacade.DrawType.SINGLE.draws());
        assertEquals(7, CratesGuiFacade.DrawType.SEVEN.draws());
    }

    @Test
    void placementRequiresExactlySevenLootDisplayPoints() {
        assertThrows(IllegalArgumentException.class, () -> placementWithLootCount(6));
        assertEquals(7, placementWithLootCount(7).lootLocations().size());
        assertThrows(IllegalArgumentException.class, () -> placementWithLootCount(8));
    }

    private static CratePlacement placementWithLootCount(int count) {
        World world = world();
        Location origin = new Location(world, 0, 64, 0);
        List<Location> loot = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new Location(world, index, 65, 0))
                .toList();
        return new CratePlacement(
                "basic", "spawn", origin, origin, origin, loot,
                1.5F, 2.0F, "default_crate", "idle"
        );
    }

    private static World world() {
        UUID id = UUID.randomUUID();
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "getName" -> "world";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "TestWorld";
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
