package dev.helios.core.vk;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Compiles the GLSL sources in {@code assets/helios/shaders} to SPIR-V at runtime with shaderc.
 * {@code #include "file"} is expanded textually (each file at most once).
 */
public final class ShaderCompiler implements AutoCloseable {
    public static final String SHADER_ROOT = "/assets/helios/shaders/";
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$", Pattern.MULTILINE);

    private final long compiler;
    private final long options;

    public ShaderCompiler() {
        compiler = shaderc_compiler_initialize();
        if (compiler == 0L) throw new IllegalStateException("shaderc_compiler_initialize failed");
        options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_4);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
    }

    /** @return SPIR-V in a newly allocated direct buffer; free with {@link MemoryUtil#memFree}. */
    public ByteBuffer compile(String name) {
        // Encode on the heap: the CharSequence overload would copy the whole source onto LWJGL's
        // small thread stack.
        ByteBuffer source = MemoryUtil.memUTF8(load(name, new HashSet<>()), false);
        ByteBuffer fileName = MemoryUtil.memUTF8(name);
        ByteBuffer entryPoint = MemoryUtil.memUTF8("main");
        long result;
        try {
            result = shaderc_compile_into_spv(compiler, source, kindFor(name), fileName, entryPoint, options);
        } finally {
            MemoryUtil.memFree(source);
            MemoryUtil.memFree(fileName);
            MemoryUtil.memFree(entryPoint);
        }
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalStateException("Failed to compile " + name + ":\n" + shaderc_result_get_error_message(result));
            }
            ByteBuffer spirv = shaderc_result_get_bytes(result);
            ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
            copy.put(spirv).flip();
            return copy;
        } finally {
            shaderc_result_release(result);
        }
    }

    static String load(String name, Set<String> included) {
        String text;
        try (InputStream in = ShaderCompiler.class.getResourceAsStream(SHADER_ROOT + name)) {
            if (in == null) throw new IllegalArgumentException("Missing shader " + name);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read shader " + name, e);
        }
        included.add(name);
        Matcher m = INCLUDE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String inc = m.group(1);
            String replacement = included.contains(inc) ? "" : load(inc, included);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    static int kindFor(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return switch (ext) {
            case "rgen" -> shaderc_raygen_shader;
            case "rmiss" -> shaderc_miss_shader;
            case "rchit" -> shaderc_closesthit_shader;
            case "rahit" -> shaderc_anyhit_shader;
            case "comp" -> shaderc_compute_shader;
            default -> throw new IllegalArgumentException("Unknown shader stage for " + name);
        };
    }

    @Override
    public void close() {
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);
    }
}
