package rip.ysm.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public final class BoneSkinShader {
    public static final int ssbo = 0;
    private static int program = 0;
    private static int locProj = -1;
    private static int locColor = -1;
    private static int locOverlay = -1;
    private static int locFogStart = -1;
    private static int locFogEnd = -1;
    private static int locFogColor = -1;
    private static int locFogShape = -1;
    private static int locLight0 = -1;
    private static int locLight1 = -1;
    private static boolean failed = false;

    public static synchronized boolean ensureCompiled() {
        if (program != 0) return true;
        if (failed) return false;
        RenderSystem.assertOnRenderThreadOrInit();

        try {
            int vs = compileShader(GL20.GL_VERTEX_SHADER, getShader("/bone_skin.vsh"), "bone_skin.vsh");
            int fs = compileShader(GL20.GL_FRAGMENT_SHADER, getShader("/bone_skin.fsh"), "bone_skin.fsh");
            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glBindAttribLocation(prog, 0, "a_position");
            GL20.glBindAttribLocation(prog, 1, "a_uv");
            GL20.glBindAttribLocation(prog, 2, "a_normal");
            GL20.glBindAttribLocation(prog, 3, "a_boneId");
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
                String log = GL20.glGetProgramInfoLog(prog);
                GL20.glDeleteProgram(prog);
                GL20.glDeleteShader(vs);
                GL20.glDeleteShader(fs);
                throw new RuntimeException("Link failed: " + log);
            }
            GL20.glDetachShader(prog, vs);
            GL20.glDetachShader(prog, fs);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            int blockIdx = GL31.glGetUniformBlockIndex(prog, "BoneBlock");
            if (blockIdx != GL31.GL_INVALID_INDEX) {
                int ssboBlock = GL43.glGetProgramResourceIndex(prog, GL43.GL_SHADER_STORAGE_BLOCK, "BoneBlock");
                if (ssboBlock != GL43.GL_INVALID_INDEX) {
                    GL43.glShaderStorageBlockBinding(prog, ssboBlock, ssbo);
                }
            } else {
                int ssboBlock = GL43.glGetProgramResourceIndex(prog, GL43.GL_SHADER_STORAGE_BLOCK, "BoneBlock");
                if (ssboBlock != GL43.GL_INVALID_INDEX) {
                    GL43.glShaderStorageBlockBinding(prog, ssboBlock, ssbo);
                }
            }

            locProj = GL20.glGetUniformLocation(prog, "u_proj");
            locColor = GL20.glGetUniformLocation(prog, "u_color");
            locOverlay = GL20.glGetUniformLocation(prog, "u_packedOverlay");
            locFogStart = GL20.glGetUniformLocation(prog, "u_fogStart");
            locFogEnd = GL20.glGetUniformLocation(prog, "u_fogEnd");
            locFogColor = GL20.glGetUniformLocation(prog, "u_fogColor");
            locFogShape = GL20.glGetUniformLocation(prog, "u_fogShape");
            locLight0 = GL20.glGetUniformLocation(prog, "u_light0");
            locLight1 = GL20.glGetUniformLocation(prog, "u_light1");

            int locSampler0 = GL20.glGetUniformLocation(prog, "Sampler0");
            int locSampler1 = GL20.glGetUniformLocation(prog, "Sampler1");
            int locSampler2 = GL20.glGetUniformLocation(prog, "Sampler2");
            GL20.glUseProgram(prog);
            if (locSampler0 >= 0) GL20.glUniform1i(locSampler0, 0);
            if (locSampler1 >= 0) GL20.glUniform1i(locSampler1, 1);
            if (locSampler2 >= 0) GL20.glUniform1i(locSampler2, 2);
            GL20.glUseProgram(0);

            program = prog;
            return true;
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }

    public static int program() {
        return program;
    }

    public static int locProj() {
        return locProj;
    }

    public static int locColor() {
        return locColor;
    }

    public static int locOverlay() {
        return locOverlay;
    }

    public static int locFogStart() {
        return locFogStart;
    }

    public static int locFogEnd() {
        return locFogEnd;
    }

    public static int locFogColor() {
        return locFogColor;
    }

    public static int locFogShape() {
        return locFogShape;
    }

    public static int locLight0() {
        return locLight0;
    }

    public static int locLight1() {
        return locLight1;
    }

    private static int compileShader(int type, String src, String name) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(sh);
            GL20.glDeleteShader(sh);
            throw new RuntimeException("Compile failed (" + name + "): " + log);
        }
        return sh;
    }

    private static String getShader(String path) throws IOException {
        try (InputStream in = BoneSkinShader.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("resource not found: " + path);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}
