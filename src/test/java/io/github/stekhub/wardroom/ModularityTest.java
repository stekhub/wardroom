package io.github.stekhub.wardroom;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build when a module uses the internals of another module or modules depend on each other in a cycle. */
class ModularityTest {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(WardroomApplication.class).verify();
    }
}
