package dev.helios.core.vk;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles every shader to SPIR-V (no GPU needed), catching GLSL errors at build time. */
class ShaderCompilerTest {
    private static ShaderCompiler compiler;

    @BeforeAll
    static void init() {
        compiler = new ShaderCompiler();
    }

    @AfterAll
    static void release() {
        compiler.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pathtrace.rgen", "primary.rmiss", "shadow.rmiss", "surface.rchit", "alphatest.rahit",
            "temporal.comp", "atrous.comp", "modulate.comp", "taau.comp", "tonemap.comp"})
    void compiles(String shader) {
        ByteBuffer spirv = compiler.compile(shader);
        try {
            assertTrue(spirv.remaining() > 20);
            assertEquals(0x07230203, spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(0), "SPIR-V magic");
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    @org.junit.jupiter.api.Test
    void includesExpandOnce() {
        String src = ShaderCompiler.load("surface.rchit", new java.util.HashSet<>());
        assertFalse(src.contains("#include"));
        assertEquals(1, src.split("uint pcgHash\\(uint v\\)", -1).length - 1);
    }
}
