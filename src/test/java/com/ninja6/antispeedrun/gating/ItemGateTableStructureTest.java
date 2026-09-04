package com.ninja6.antispeedrun.gating;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.config.PluginConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the criterion that replaced "querying {@code isGated(Material)} takes &lt; 10
 * nanoseconds".
 *
 * <p>Audit finding R-11 rejected the original wording because JUnit cannot check it without JMH,
 * and a test that tries will either be deleted or reduced to a stopwatch assertion loose enough to
 * pass on any hardware. The replacement criterion is structural: <em>the lookup performs no
 * allocation and no string operation</em>. Both halves are checked against the compiled class file,
 * statically, with the same answer on every JVM.
 *
 * <ul>
 *   <li><strong>No string operation.</strong> {@code ItemGateTable}'s constant pool is parsed and
 *       asserted to contain no reference to {@code java.lang.String}, {@code StringBuilder},
 *       {@code java.util.regex} or the string-concatenation bootstrap. A reference hidden in a
 *       descriptor counts — the two-argument {@code Objects.requireNonNull} would fail this on its
 *       signature alone — which is why every string in the gating feature lives in
 *       {@code ItemGateCompiler} and warnings are collected there rather than on the table.</li>
 *   <li><strong>No allocation.</strong> The bytecode of {@code isGated} and {@code tierFor} is
 *       extracted and asserted to contain none of the five instructions that can create an
 *       object.</li>
 * </ul>
 *
 * <p>An earlier revision measured allocation with the JVM's per-thread counter instead. That was
 * dropped on review, for two reasons worth recording so it is not reintroduced. It proved less than
 * it claimed: in a microbenchmark the call site is monomorphic and C2 is free to scalar-replace an
 * allocation that would survive in a real listener, so a lookup that <em>did</em> allocate could
 * still measure zero. And it asserted exact byte equality against a floor measured with un-warmed
 * reflection, which is a flake waiting to happen in a required check. A static check of the
 * instructions themselves has neither problem.
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

    /**
     * Every JVM instruction that can produce an object. {@code invokedynamic} is here because its
     * bootstrap is where a string concatenation or a captured lambda would hide.
     */
    private static final Map<Integer, String> ALLOCATING = Map.of(
            0xBB, "new",
            0xBC, "newarray",
            0xBD, "anewarray",
            0xC5, "multianewarray",
            0xBA, "invokedynamic");

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
    @DisplayName("neither lookup method contains an allocating instruction")
    void noAllocationInTheLookup() throws Exception {
        // The scan walks the code array byte by byte rather than decoding instruction boundaries.
        // That is deliberate and safe in the direction that matters: it can only ever over-report,
        // by mistaking an operand byte for an opcode, and can never let a real `new` through. An
        // over-report would fail the build here, deterministically, where it is trivial to see.
        for (String method : List.of("isGated", "tierFor")) {
            byte[] code = bytecodeOf(ItemGateTable.class, method);
            assertTrue(code.length > 0 && code.length < 32,
                    method + " should be a handful of instructions, found " + code.length + " bytes");

            for (byte instruction : code) {
                int opcode = instruction & 0xFF;
                assertTrue(!ALLOCATING.containsKey(opcode),
                        method + " contains " + ALLOCATING.get(opcode)
                                + ". The lookup sits on the item pickup path and must allocate "
                                + "nothing; whatever needs a new object belongs in "
                                + "ItemGateCompiler, which runs once per reload.");
            }
        }

        // Guards the guard again: the compiler's own compile method must trip the same scan, or the
        // bytecode extraction is finding nothing and both assertions above are vacuous.
        byte[] compile = bytecodeOf(ItemGateCompiler.class, "compile");
        boolean allocatesSomewhere = false;
        for (byte instruction : compile) {
            allocatesSomewhere |= ALLOCATING.containsKey(instruction & 0xFF);
        }
        assertTrue(allocatesSomewhere, "the bytecode scan is not actually reading anything");
    }

    @Test
    @DisplayName("the table is array-backed, not hash-backed")
    void usesEnumCollections() throws Exception {
        // R-11 asks for EnumMap over HashMap specifically: it is indexed by ordinal() rather than
        // hashed, and it cannot be corrupted by a concurrent read the way a HashMap can.
        ItemGateTable<TestMaterial> gates = table();
        assertTrue(gates.gatedMaterials().size() > 0);

        assertEquals(EnumSet.class, ItemGateTable.class.getDeclaredField("gated").getType());
        assertEquals(EnumMap.class, ItemGateTable.class.getDeclaredField("byMaterial").getType());
    }

    // -------------------------------------------------------------------------------------------
    // A very small class-file reader. Only what these two assertions need.
    // -------------------------------------------------------------------------------------------

    /** Every UTF-8 entry in a compiled class's constant pool: class names, descriptors, literals. */
    private static List<String> constantPoolText(Class<?> type) throws IOException {
        return new ArrayList<>(readConstantPool(open(type), type.getSimpleName()).values());
    }

    /** The code array of the named method, which must appear exactly once in {@code type}. */
    private static byte[] bytecodeOf(Class<?> type, String methodName) throws IOException {
        String name = type.getSimpleName();
        DataInputStream data = open(type);
        Map<Integer, String> pool = readConstantPool(data, name);

        data.skipBytes(2 + 2 + 2);                          // access flags, this class, super class
        data.skipBytes(2 * data.readUnsignedShort());       // interfaces
        skipMembers(data, pool);                            // fields

        int methods = data.readUnsignedShort();
        byte[] found = null;
        for (int i = 0; i < methods; i++) {
            data.skipBytes(2);                              // access flags
            String memberName = pool.get(data.readUnsignedShort());
            data.skipBytes(2);                              // descriptor
            byte[] code = readCodeAttribute(data, pool);
            if (methodName.equals(memberName) && code != null) {
                assertNotNull(memberName);
                found = code;
            }
        }
        assertNotNull(found, "no Code attribute for " + name + "#" + methodName);
        return found;
    }

    private static DataInputStream open(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "could not read the compiled " + resource);
            return new DataInputStream(new ByteArrayInputStream(in.readAllBytes()));
        }
    }

    /** Reads the header and constant pool, leaving the stream positioned on the access flags. */
    private static Map<Integer, String> readConstantPool(DataInputStream data, String name)
            throws IOException {
        assertEquals(0xCAFEBABE, data.readInt(), name + " is not a class file");
        data.readUnsignedShort();  // minor version
        data.readUnsignedShort();  // major version

        int count = data.readUnsignedShort();
        Map<Integer, String> utf8 = new HashMap<>();
        for (int i = 1; i < count; i++) {
            int tag = data.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8.put(i, data.readUTF());
                case 7, 8, 16, 19, 20 -> data.skipBytes(2);
                case 15 -> data.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> data.skipBytes(4);
                case 5, 6 -> {
                    data.skipBytes(8);
                    i++;  // longs and doubles occupy two constant-pool slots
                }
                default -> throw new IOException("unknown constant pool tag " + tag + " at index "
                        + i + " in " + name);
            }
        }
        return utf8;
    }

    /** Skips a whole {@code field_info} table. */
    private static void skipMembers(DataInputStream data, Map<Integer, String> pool)
            throws IOException {
        int members = data.readUnsignedShort();
        for (int i = 0; i < members; i++) {
            data.skipBytes(2 + 2 + 2);  // access flags, name, descriptor
            readCodeAttribute(data, pool);
        }
    }

    /**
     * Walks one attribute table, returning the code array of its {@code Code} attribute if it has
     * one. Every other attribute is skipped by its declared length, so unknown attributes are
     * harmless.
     */
    private static byte[] readCodeAttribute(DataInputStream data, Map<Integer, String> pool)
            throws IOException {
        int attributes = data.readUnsignedShort();
        byte[] code = null;
        for (int i = 0; i < attributes; i++) {
            String attribute = pool.get(data.readUnsignedShort());
            int length = data.readInt();
            if (!"Code".equals(attribute)) {
                data.skipBytes(length);
                continue;
            }
            data.skipBytes(2 + 2);  // max stack, max locals
            int codeLength = data.readInt();
            code = new byte[codeLength];
            data.readFully(code);
            data.skipBytes(length - 8 - codeLength);  // exception table and nested attributes
        }
        return code;
    }
}
