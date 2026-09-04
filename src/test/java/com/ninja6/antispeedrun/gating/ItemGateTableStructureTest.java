package com.ninja6.antispeedrun.gating;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Asserts the criterion that replaced "querying {@code isGated(Material)} takes &lt; 10
 * nanoseconds".
 *
 * <p>Audit finding R-11 rejected the original wording because JUnit cannot check it without JMH,
 * and a test that tries will either be deleted or reduced to a stopwatch assertion loose enough to
 * pass on any hardware — which measures nothing. The replacement criterion is structural: <em>the
 * lookup performs no allocation and no string operation</em>. Both halves are checked here for
 * real.
 *
 * <ul>
 *   <li><strong>No string operation</strong> is checked against the compiled class file. The
 *       constant pool of {@code ItemGateTable} is parsed and asserted to contain no reference to
 *       {@code java.lang.String}, {@code StringBuilder}, {@code java.util.regex} or the
 *       string-concatenation bootstrap. A method reference to any of them — including one hidden in
 *       a descriptor, such as the two-argument {@code Objects.requireNonNull} — would put the name
 *       in the pool and fail this test. That is why every string in the gating feature lives in
 *       {@code ItemGateCompiler} instead.</li>
 *   <li><strong>No allocation</strong> is measured, not inferred, with the JVM's own per-thread
 *       allocation counter across a million lookups.</li>
 * </ul>
 */
class ItemGateTableStructureTest {

    /** Types whose mere appearance in the constant pool would mean the lookup does string work. */
    private static final List<String> FORBIDDEN = List.of(
            "java/lang/String",
            "java/lang/CharSequence",
            "java/lang/StringBuilder",
            "java/lang/StringBuffer",
            "java/util/regex",
            "StringConcatFactory",
            "makeConcat");

    private static ItemGateTable<TestMaterial> table() throws GateCollisionException {
        PluginConfig.ItemTier tier = new PluginConfig.ItemTier("iron-tier",
                List.of("IRON_*", "*_IRON_ORE"), List.of("IRON_DOOR"), List.of("SHIELD"),
                List.of("minecraft:story/mine_stone"), 0.0D, 0, "");
        return ItemGateCompiler.compile(TestMaterial.class, TestMaterial.UNIVERSE, List.of(tier),
                new ArrayList<>());
    }

    @Test
    @DisplayName("the lookup class refers to no string type at all")
    void noStringOperationInTheLookup() throws Exception {
        List<String> pool = constantPoolText(ItemGateTable.class);
        assertTrue(pool.size() > 5, "the constant pool was not parsed: " + pool);

        for (String entry : pool) {
            for (String forbidden : FORBIDDEN) {
                assertTrue(!entry.contains(forbidden),
                        "ItemGateTable's constant pool refers to " + forbidden + " via \"" + entry
                                + "\". The lookup sits on the item pickup path and must do no "
                                + "string work; move whatever needs it into ItemGateCompiler.");
            }
        }

        // Guards the guard: the same scan over the compiler, which legitimately does string work,
        // must find plenty. Otherwise a parser that silently returned nothing would pass above.
        List<String> compilerPool = constantPoolText(ItemGateCompiler.class);
        assertTrue(compilerPool.stream().anyMatch(e -> e.contains("java/lang/String")),
                "the constant-pool scan is not actually reading anything");
    }

    @Test
    @DisplayName("a million lookups allocate nothing")
    void lookupAllocatesNothing() throws Exception {
        ItemGateTable<TestMaterial> gates = table();

        Class<?> sunBean = Class.forName("com.sun.management.ThreadMXBean");
        Object bean = ManagementFactory.getThreadMXBean();
        assumeTrue(sunBean.isInstance(bean), "no per-thread allocation counter on this JVM");
        Method supported = sunBean.getMethod("isThreadAllocatedMemorySupported");
        assumeTrue(Boolean.TRUE.equals(supported.invoke(bean)),
                "per-thread allocation measurement is not supported here");
        Method allocated = sunBean.getMethod("getThreadAllocatedBytes", long.class);
        long self = Thread.currentThread().threadId();

        // Warm up so the measured window contains no first-time class loading or JIT bookkeeping.
        assertTrue(hammer(gates, 200_000) >= 0);

        // The counter is read reflectively, and reflection boxes its result and allocates a varargs
        // array, so even an empty window is not free. Measure that overhead once and treat it as
        // the floor.
        long emptyBefore = (Long) allocated.invoke(bean, self);
        hammer(gates, 0);
        long emptyAfter = (Long) allocated.invoke(bean, self);
        long harness = emptyAfter - emptyBefore;

        // Take the cheapest of several windows. The JVM occasionally allocates on this thread for
        // reasons of its own — a recompile, a counter overflowing — and a single window picks that
        // up as noise. It is not a threshold in disguise: a lookup that allocated even one 16-byte
        // object per call would put every window a megabyte over the floor, so the minimum cannot
        // come back clean unless the lookup really is allocation-free.
        long cheapest = Long.MAX_VALUE;
        long observed = 0;
        for (int attempt = 0; attempt < 8 && cheapest > harness; attempt++) {
            long before = (Long) allocated.invoke(bean, self);
            observed += hammer(gates, 1_000_000);
            long after = (Long) allocated.invoke(bean, self);
            cheapest = Math.min(cheapest, after - before);
        }

        assertTrue(observed > 0, "the loop must not be optimised away entirely");
        assertEquals(harness, cheapest,
                "isGated/tierFor allocated " + (cheapest - harness)
                        + " bytes over a million calls, above the " + harness
                        + " bytes the measurement harness itself costs");
    }

    /** Runs both lookups over the whole universe and returns a value derived from the results. */
    private static long hammer(ItemGateTable<TestMaterial> gates, int iterations) {
        long sink = 0;
        TestMaterial[] universe = TestMaterial.UNIVERSE;
        for (int i = 0; i < iterations; i++) {
            TestMaterial material = universe[i % universe.length];
            if (gates.isGated(material)) {
                sink++;
            }
            if (gates.tierFor(material) != null) {
                sink += 2;
            }
        }
        return sink;
    }

    @Test
    @DisplayName("the table is array-backed, not hash-backed")
    void usesEnumCollections() throws Exception {
        // R-11 asks for EnumMap over HashMap specifically: it is indexed by ordinal() rather than
        // hashed, and it cannot be corrupted by a concurrent read the way a HashMap can.
        ItemGateTable<TestMaterial> gates = table();
        assertTrue(gates.gatedMaterials().size() > 0);

        var gatedField = ItemGateTable.class.getDeclaredField("gated");
        var mapField = ItemGateTable.class.getDeclaredField("byMaterial");
        assertEquals(EnumSet.class, gatedField.getType());
        assertEquals(EnumMap.class, mapField.getType());
    }

    // -------------------------------------------------------------------------------------------

    /** Every UTF-8 entry in a compiled class's constant pool: class names, descriptors, literals. */
    private static List<String> constantPoolText(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "could not read the compiled " + resource);
            DataInputStream data = new DataInputStream(in);
            assertEquals(0xCAFEBABE, data.readInt(), "not a class file");
            data.readUnsignedShort(); // minor version
            data.readUnsignedShort(); // major version

            int count = data.readUnsignedShort();
            List<String> text = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = data.readUnsignedByte();
                switch (tag) {
                    case 1 -> text.add(data.readUTF());
                    case 7, 8, 16, 19, 20 -> data.skipBytes(2);
                    case 15 -> data.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> data.skipBytes(4);
                    case 5, 6 -> {
                        data.skipBytes(8);
                        i++; // longs and doubles occupy two constant-pool slots
                    }
                    default -> throw new IOException("unknown constant pool tag " + tag
                            + " at index " + i + " in " + resource);
                }
            }
            return text;
        }
    }
}
