package com.ninja6.antispeedrun.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.spi.ToolProvider;

import org.junit.jupiter.api.Test;

/** Guards the production bytecode against the BookMeta return-type change in Paper 26.2. */
class JourneyBookLinkageTest {

    @Test
    void bookUsesTheVoidComponentAppendDescriptor() throws Exception {
        // Inspect the compiled production class, rather than recompiling a test against the same
        // old API: pages(List) compiles successfully there but fails to link on newer servers.
        var resource = Objects.requireNonNull(getClass().getResource("JourneyBookCommand.class"));
        var javap = ToolProvider.findFirst("javap").orElseThrow();
        StringWriter output = new StringWriter();
        PrintWriter writer = new PrintWriter(output);
        assertEquals(0, javap.run(writer, writer, "-c", "-p", Path.of(resource.toURI()).toString()));
        String bytecode = output.toString();

        assertTrue(bytecode.contains("InterfaceMethod org/bukkit/inventory/meta/BookMeta.addPages:"
                + "([Lnet/kyori/adventure/text/Component;)V"), bytecode);
        assertFalse(bytecode.contains("InterfaceMethod org/bukkit/inventory/meta/BookMeta.pages:"),
                "BookMeta.pages has incompatible covariant return descriptors across supported APIs");
    }
}
