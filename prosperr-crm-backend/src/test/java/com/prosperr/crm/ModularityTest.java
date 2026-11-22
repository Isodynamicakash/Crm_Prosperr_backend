package com.prosperr.crm;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Fails the build when a module uses another module's internals or modules depend on each other in a cycle. */
class ModularityTest {
    static final ApplicationModules modules = ApplicationModules.of(CrmApplication.class);

    @Test
    void modulesRespectBoundaries() { modules.verify(); }

    @Test
    void writeModuleDocs() { new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml(); }
}
