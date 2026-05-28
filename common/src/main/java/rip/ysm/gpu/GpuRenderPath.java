package rip.ysm.gpu;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.mixin.client.RenderSystemAccessor;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class GpuRenderPath {
    private static final float[] rootPoseScratch = new float[16];
    private static final float[] rootNormalScratch = new float[9];
    private static final float[] projScratch = new float[16];
    private static final float[] identityScratch = new Matrix4f().get(new float[16]);
    /** Byte stride of one vertex in the compute shader (bone_xform.csh) readback output. */
    private static final int READBACK_STRIDE = 36;
    private static final int[] quadIndexScratch = new int[6];
    private static final Matrix4f projMVScratch = new Matrix4f();
    private static final Vector3f[] currentLights = new Vector3f[2];
    private static final ConcurrentHashMap<Long, GpuMesh> meshMap = new ConcurrentHashMap<>();
    private static final AtomicLong ref = new AtomicLong(1);
    private static final Matrix4f pivotAbsScratchMat = new Matrix4f();
    private static final boolean DEBUG = Boolean.getBoolean("ysm.gpu.debug");
    private static final int DEBUG_SAMPLE_INTERVAL = 240;
    private static int debugSuccessCount;
    private static int debugFallbackCount;
    private static int[] pivotAbsPathScratch = new int[64];

    public static boolean tryRender(
            GeoModel model,
            PoseStack.Pose pose,
            float[] boneParams,
            float[] stateBuffer,
            int renderPartMask,
            int packedLight,
            int packedOverlay,
            float r, float g, float b, float a,
            ResourceLocation textureLocation
    ) {
        return tryRender(model, pose, boneParams, stateBuffer, renderPartMask, packedLight, packedOverlay, r, g, b, a, textureLocation, "unknown");
    }

    public static boolean tryRender(
            GeoModel model,
            PoseStack.Pose pose,
            float[] boneParams,
            float[] stateBuffer,
            int renderPartMask,
            int packedLight,
            int packedOverlay,
            float r, float g, float b, float a,
            ResourceLocation textureLocation,
            String renderContext
    ) {
        if (!GpuCapability.isAvailable()) {
            debugFallback(renderContext, GpuCapability.getReason(), renderPartMask, packedLight, textureLocation);
            return false;
        }
        if (!BoneSkinShader.ensureCompiled()) {
            debugFallback(renderContext, "shader compile failed", renderPartMask, packedLight, textureLocation);
            return false;
        }
        if (model.bakedBones == null || model.bakedBones.isEmpty()) {
            debugFallback(renderContext, "empty model", renderPartMask, packedLight, textureLocation);
            return false;
        }

        GlStateSnapshot snapshot = GlStateSnapshot.capture();
        int drawCount = 0;
        try {
            if (model.gpuMeshHandle == 0) {
                GpuMesh builtMesh = GpuMeshBuilder.build(model);
                if (builtMesh == null) {
                    debugFallback(renderContext, "mesh build failed", renderPartMask, packedLight, textureLocation);
                    return false;
                }
                model.gpuMeshHandle = encodeMeshRef(builtMesh);
            }
            GpuMesh mesh = decodeMeshRef(model.gpuMeshHandle);
            if (mesh == null) {
                debugFallback(renderContext, "missing mesh handle", renderPartMask, packedLight, textureLocation);
                return false;
            }

            Matrix4f rootPose = pose.pose();
            Matrix3f rootNormal = pose.normal();
            Matrix4f projMat = RenderSystem.getProjectionMatrix();
            Matrix4f mvMat = RenderSystem.getModelViewMatrix();

            rootPose.get(rootPoseScratch);
            rootNormal.get(rootNormalScratch);
            projMat.mul(mvMat, projMVScratch);
            projMVScratch.get(projScratch);

            ByteBuffer boneBuf = mesh.perFrameBoneBuffer;
            boneBuf.clear();

            updatePivotAbsStateBuffer(model, boneParams, stateBuffer);

            GeoModel.nComputeBoneMatrices(mesh.pointer, rootPoseScratch, rootNormalScratch, boneParams, packedLight, boneBuf);
            boneBuf.position(0);
            boneBuf.limit(mesh.boneCount * 144);

            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager._blendEquation(GL14.GL_FUNC_ADD);
            GL11.glFrontFace(GL11.GL_CCW);

            Minecraft mc = Minecraft.getInstance();
            AbstractTexture modelTex = mc.getTextureManager().getTexture(textureLocation);
            int modelTexId = modelTex.getId();

            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 2);
            mc.gameRenderer.lightTexture().turnOnLightLayer();

            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 1);
            mc.gameRenderer.overlayTexture().setupOverlayColor();
            GlStateManager._bindTexture(RenderSystem.getShaderTexture(1)); // overlayTexture里的texture没getter，固定bind 1

            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(modelTexId);

            int boneSsbo = mesh.nextBoneSsbo();
            GL15.glBindBuffer(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, boneSsbo);
            GL15.glBufferSubData(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, 0L, boneBuf);
            GL30.glBindBufferBase(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, BoneSkinShader.ssbo, boneSsbo);

            float fogStart = RenderSystem.getShaderFogStart();
            float fogEnd = RenderSystem.getShaderFogEnd();
            float[] fogColor = RenderSystem.getShaderFogColor();
            int fogShape = RenderSystem.getShaderFogShape().getIndex();

            GlStateManager._glUseProgram(BoneSkinShader.program());
            if (BoneSkinShader.locProj() >= 0) GL20.glUniformMatrix4fv(BoneSkinShader.locProj(), false, projScratch);
            if (BoneSkinShader.locColor() >= 0) GL20.glUniform4f(BoneSkinShader.locColor(), r, g, b, a);
            if (BoneSkinShader.locOverlay() >= 0) GL20.glUniform1i(BoneSkinShader.locOverlay(), packedOverlay);
            if (BoneSkinShader.locFogStart() >= 0) GL20.glUniform1f(BoneSkinShader.locFogStart(), fogStart);
            if (BoneSkinShader.locFogEnd() >= 0) GL20.glUniform1f(BoneSkinShader.locFogEnd(), fogEnd);

            if (BoneSkinShader.locFogColor() >= 0)
                GL20.glUniform4f(BoneSkinShader.locFogColor(), fogColor[0], fogColor[1], fogColor[2], fogColor[3]);

            if (BoneSkinShader.locFogShape() >= 0) GL20.glUniform1i(BoneSkinShader.locFogShape(), fogShape);

            refreshLights();

            if (BoneSkinShader.locLight0() >= 0)
                GL20.glUniform3f(BoneSkinShader.locLight0(), currentLights[0].x, currentLights[0].y, currentLights[0].z);
            if (BoneSkinShader.locLight1() >= 0)
                GL20.glUniform3f(BoneSkinShader.locLight1(), currentLights[1].x, currentLights[1].y, currentLights[1].z);

            GlStateManager._glBindVertexArray(mesh.vao);

            int offsetBytes = mesh.indexOffsetBytes(renderPartMask);
            drawCount = mesh.indexDrawCount(renderPartMask);
            if (drawCount > 0) {
                if (BoneSkinShader.locAlphaMode() >= 0) GL20.glUniform1i(BoneSkinShader.locAlphaMode(), 1);
                GL11.glDrawElements(GL11.GL_TRIANGLES, drawCount, GL11.GL_UNSIGNED_INT, offsetBytes);

                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                if (BoneSkinShader.locAlphaMode() >= 0) GL20.glUniform1i(BoneSkinShader.locAlphaMode(), 2);
                GL11.glDrawElements(GL11.GL_TRIANGLES, drawCount, GL11.GL_UNSIGNED_INT, offsetBytes);
                RenderSystem.disableBlend();
            }

            debugSuccess(renderContext, drawCount, renderPartMask, packedLight, textureLocation, snapshot);
            return true;
        } catch (Throwable t) {
            debugFallback(renderContext, "exception: " + t.getClass().getSimpleName() + ": " + t.getMessage(), drawCount, renderPartMask, packedLight, textureLocation, snapshot);
            YesSteveModel.LOGGER.error("[YSM GPU] GPU render path failed; falling back for this draw", t);
            return false;
        } finally {
            try {
                Minecraft mc = Minecraft.getInstance();
                mc.gameRenderer.overlayTexture().teardownOverlayColor();
                mc.gameRenderer.lightTexture().turnOffLightLayer();
            } catch (Throwable ignored) {
            }
            GL30.glBindBufferBase(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, BoneSkinShader.ssbo, 0);
            GL15.glBindBuffer(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, 0);
            GlStateManager._glUseProgram(0);
            com.mojang.blaze3d.vertex.BufferUploader.invalidate();
            snapshot.restore();
        }
    }

    public static boolean tryRenderToConsumer(
            VertexConsumer vertexConsumer,
            GeoModel model,
            PoseStack.Pose pose,
            float[] boneParams,
            float[] stateBuffer,
            int renderPartMask,
            int packedLight,
            int packedOverlay,
            float r, float g, float b, float a,
            ResourceLocation textureLocation,
            String renderContext
    ) {
        if (vertexConsumer == null) {
            debugFallback(renderContext, "missing vertex consumer", renderPartMask, packedLight, textureLocation);
            return false;
        }
        if (!GpuCapability.isAvailable()) {
            debugFallback(renderContext, GpuCapability.getReason(), renderPartMask, packedLight, textureLocation);
            return false;
        }
        if (!GL.getCapabilities().OpenGL43) {
            debugFallback(renderContext, "OpenGL 4.3 compute shader unavailable", renderPartMask, packedLight, textureLocation);
            return false;
        }
        if (!BoneXformCompute.ensureCompiled()) {
            debugFallback(renderContext, "compute shader compile failed", renderPartMask, packedLight, textureLocation);
            return false;
        }
        if (model.bakedBones == null || model.bakedBones.isEmpty()) {
            debugFallback(renderContext, "empty model", renderPartMask, packedLight, textureLocation);
            return false;
        }

        GlStateSnapshot snapshot = GlStateSnapshot.capture();
        int drawCount = 0;
        try {
            GpuMesh mesh = getOrBuildMesh(model);
            if (mesh == null) {
                debugFallback(renderContext, "mesh build failed", renderPartMask, packedLight, textureLocation);
                return false;
            }
            mesh.ensureXformBuffers();

            Matrix4f rootPose = pose.pose();
            Matrix3f rootNormal = pose.normal();
            rootPose.get(rootPoseScratch);
            rootNormal.get(rootNormalScratch);

            ByteBuffer boneBuf = mesh.perFrameBoneBuffer;
            boneBuf.clear();

            updatePivotAbsStateBuffer(model, boneParams, stateBuffer);

            GeoModel.nComputeBoneMatrices(mesh.pointer, rootPoseScratch, rootNormalScratch, boneParams, packedLight, boneBuf);
            boneBuf.position(0);
            boneBuf.limit(mesh.boneCount * 144);

            int boneSsbo = mesh.nextBoneSsbo();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, boneSsbo);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, boneBuf);

            GlStateManager._glUseProgram(BoneXformCompute.program());
            if (BoneXformCompute.locColor() >= 0) GL20.glUniform4f(BoneXformCompute.locColor(), r, g, b, a);
            if (BoneXformCompute.locOverlay() >= 0) GL20.glUniform1i(BoneXformCompute.locOverlay(), packedOverlay);
            if (BoneXformCompute.locModelView() >= 0) {
                GL20.glUniformMatrix4fv(BoneXformCompute.locModelView(), false, identityScratch);
            }

            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, mesh.vbo);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, mesh.xformVbo());
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, boneSsbo);

            GL43.glDispatchCompute(BoneXformCompute.dispatchGroupCount(mesh.vertexCount), 1, 1);
            GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);

            ByteBuffer vertices = mesh.xformReadbackBuffer();
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, mesh.xformVbo());
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, vertices);

            drawCount = mesh.indexDrawCount(renderPartMask);
            if (drawCount > 0) {
                ByteBuffer indices = mesh.indexReadbackBuffer(drawCount * Integer.BYTES);
                GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo);
                GL15.glGetBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.indexOffsetBytes(renderPartMask), indices);
                submitReadbackVertices(vertexConsumer, vertices, indices, drawCount);
            }

            debugSuccess(renderContext + ":consumer", drawCount, renderPartMask, packedLight, textureLocation, snapshot);
            return true;
        } catch (Throwable t) {
            debugFallback(renderContext, "consumer exception: " + t.getClass().getSimpleName() + ": " + t.getMessage(), drawCount, renderPartMask, packedLight, textureLocation, snapshot);
            YesSteveModel.LOGGER.error("[YSM GPU] GPU consumer render path failed; falling back for this draw", t);
            return false;
        } finally {
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            GlStateManager._glUseProgram(0);
            com.mojang.blaze3d.vertex.BufferUploader.invalidate();
            snapshot.restore();
        }
    }

    private static void submitReadbackVertices(VertexConsumer vertexConsumer, ByteBuffer vertices, ByteBuffer indices, int drawCount) {
        int[] quad = quadIndexScratch;
        int i = 0;
        for (; i + 5 < drawCount; i += 6) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int k = 0; k < 6; k++) {
                int idx = indices.getInt((i + k) * Integer.BYTES);
                quad[k] = idx;
                min = Math.min(min, idx);
                max = Math.max(max, idx);
            }
            // The target RenderType uses QUADS draw mode (4 verts/primitive) while the index
            // buffer is triangulated (6 indices/quad). A contiguous run of exactly 4 distinct
            // vertices reconstructs into a single quad; otherwise fall back to two triangles.
            if (max - min == 3 && coversContiguousQuad(quad, min)) {
                if (!isHiddenQuad(vertices, min)) {
                    submitReadbackVertex(vertexConsumer, vertices, min);
                    submitReadbackVertex(vertexConsumer, vertices, min + 1);
                    submitReadbackVertex(vertexConsumer, vertices, min + 2);
                    submitReadbackVertex(vertexConsumer, vertices, min + 3);
                }
                continue;
            }

            submitReadbackTriangle(vertexConsumer, vertices, quad[0], quad[1], quad[2]);
            submitReadbackTriangle(vertexConsumer, vertices, quad[3], quad[4], quad[5]);
        }
        for (; i + 2 < drawCount; i += 3) {
            int idx0 = indices.getInt(i * Integer.BYTES);
            int idx1 = indices.getInt((i + 1) * Integer.BYTES);
            int idx2 = indices.getInt((i + 2) * Integer.BYTES);
            submitReadbackTriangle(vertexConsumer, vertices, idx0, idx1, idx2);
        }
    }

    private static boolean coversContiguousQuad(int[] indices, int min) {
        for (int offset = 0; offset < 4; offset++) {
            if (!hasIndex(indices, min + offset)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasIndex(int[] indices, int value) {
        for (int idx : indices) {
            if (idx == value) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHiddenQuad(ByteBuffer vertices, int min) {
        return isHiddenReadbackVertex(vertices, min)
                && isHiddenReadbackVertex(vertices, min + 1)
                && isHiddenReadbackVertex(vertices, min + 2)
                && isHiddenReadbackVertex(vertices, min + 3);
    }

    private static void submitReadbackTriangle(VertexConsumer vertexConsumer, ByteBuffer vertices, int idx0, int idx1, int idx2) {
        if (isHiddenReadbackVertex(vertices, idx0) && isHiddenReadbackVertex(vertices, idx1) && isHiddenReadbackVertex(vertices, idx2)) {
            return;
        }
        submitReadbackVertex(vertexConsumer, vertices, idx0);
        submitReadbackVertex(vertexConsumer, vertices, idx1);
        submitReadbackVertex(vertexConsumer, vertices, idx2);
    }

    private static boolean isHiddenReadbackVertex(ByteBuffer vertices, int index) {
        int base = index * READBACK_STRIDE;
        return vertices.getFloat(base) == 2.0f
                && vertices.getFloat(base + 4) == 2.0f
                && vertices.getFloat(base + 8) == 2.0f;
    }

    private static void submitReadbackVertex(VertexConsumer vertexConsumer, ByteBuffer vertices, int index) {
        int base = index * READBACK_STRIDE;
        float x = vertices.getFloat(base);
        float y = vertices.getFloat(base + 4);
        float z = vertices.getFloat(base + 8);
        float red = (vertices.get(base + 12) & 0xFF) / 255.0f;
        float green = (vertices.get(base + 13) & 0xFF) / 255.0f;
        float blue = (vertices.get(base + 14) & 0xFF) / 255.0f;
        float alpha = (vertices.get(base + 15) & 0xFF) / 255.0f;
        float u = vertices.getFloat(base + 16);
        float v = vertices.getFloat(base + 20);
        int overlay = vertices.getInt(base + 24);
        int light = vertices.getInt(base + 28);
        float normalX = vertices.get(base + 32) / 127.0f;
        float normalY = vertices.get(base + 33) / 127.0f;
        float normalZ = vertices.get(base + 34) / 127.0f;
        vertexConsumer.vertex(x, y, z, red, green, blue, alpha, u, v, overlay, light, normalX, normalY, normalZ);
    }

    public static void debugFallback(String renderContext, String reason, int renderPartMask, int packedLight, ResourceLocation textureLocation) {
        debugFallback(renderContext, reason, -1, renderPartMask, packedLight, textureLocation, null);
    }

    private static void debugSuccess(String renderContext, int drawCount, int renderPartMask, int packedLight, ResourceLocation textureLocation, GlStateSnapshot snapshot) {
        if (!DEBUG) return;
        int count = ++debugSuccessCount;
        if (count > 5 && count % DEBUG_SAMPLE_INTERVAL != 0) return;
        YesSteveModel.LOGGER.info("[YSM GPU] render ok context={} drawCount={} partMask={} packedLight={} texture={} stateBefore={}",
                renderContext, drawCount, renderPartMask, packedLight, textureLocation, snapshot);
    }

    private static void debugFallback(String renderContext, String reason, int drawCount, int renderPartMask, int packedLight, ResourceLocation textureLocation, GlStateSnapshot snapshot) {
        if (!DEBUG) return;
        int count = ++debugFallbackCount;
        if (count > 12 && count % DEBUG_SAMPLE_INTERVAL != 0) return;
        YesSteveModel.LOGGER.info("[YSM GPU] fallback context={} reason={} drawCount={} partMask={} packedLight={} texture={} stateBefore={}",
                renderContext, reason, drawCount, renderPartMask, packedLight, textureLocation, snapshot);
    }

    private static void refreshLights() {
        Vector3f[] arr = RenderSystemAccessor.ysm$getShaderLightDirections();
        currentLights[0] = (arr != null && arr.length > 0 && arr[0] != null) ? arr[0] : new Vector3f(0.2f, 1.0f, -0.7f).normalize();
        currentLights[1] = (arr != null && arr.length > 1 && arr[1] != null) ? arr[1] : new Vector3f(-0.2f, 1.0f, 0.7f).normalize();
    }

    public static void disposeMesh(GeoModel model) {
        if (model.gpuMeshHandle == 0) return;
        GpuMesh mesh = meshMap.remove(model.gpuMeshHandle);
        if (mesh != null) mesh.dispose();
        model.gpuMeshHandle = 0;
    }

    public static GpuMesh getOrBuildMesh(GeoModel model) {
        if (model.gpuMeshHandle == 0) {
            GpuMesh mesh = GpuMeshBuilder.build(model);
            if (mesh == null) return null;
            model.gpuMeshHandle = encodeMeshRef(mesh);
        }
        return decodeMeshRef(model.gpuMeshHandle);
    }

    private static long encodeMeshRef(GpuMesh mesh) {
        long ref = GpuRenderPath.ref.getAndIncrement();
        meshMap.put(ref, mesh);
        return ref;
    }

    private static GpuMesh decodeMeshRef(long ref) {
        return meshMap.get(ref);
    }

    private static void updatePivotAbsStateBuffer(GeoModel model, float[] boneParams, float[] stateBuffer) {
        if (stateBuffer == null || boneParams == null) return;
        if (model.bakedBones == null || model.bakedBones.isEmpty()) return;

        int boneCount = model.bakedBones.size();

        for (int i = 0; i < boneCount; i++) {
            int pOffset = i * 12;
            if (pOffset + 11 >= boneParams.length) break;

            float unk3 = boneParams[pOffset + 11];
            if (unk3 != 1.0f) continue;

            int sOffset = i * 4;
            if (sOffset + 2 >= stateBuffer.length) continue;

            computeOnePivotAbs(i, model.bakedBones, boneParams, stateBuffer, sOffset);
        }
    }

    private static void computeOnePivotAbs(int targetIdx, List<GeoModel.BakedBone> bones, float[] boneParams, float[] stateBuffer, int stateOffset) {
        int depth = 0;
        int idx = targetIdx;

        while (idx != -1) {
            if (depth >= pivotAbsPathScratch.length) {
                int[] newPath = new int[pivotAbsPathScratch.length * 2];
                System.arraycopy(pivotAbsPathScratch, 0, newPath, 0, pivotAbsPathScratch.length);
                pivotAbsPathScratch = newPath;
            }

            pivotAbsPathScratch[depth++] = idx;
            idx = bones.get(idx).parentIdx;
        }

        Matrix4f localMat = pivotAbsScratchMat.identity();
        boolean isVisible = true;

        for (int p = depth - 1; p >= 0; p--) {
            int boneIdx = pivotAbsPathScratch[p];
            GeoModel.BakedBone bone = bones.get(boneIdx);

            int pOffset = boneIdx * 12;
            if (pOffset + 11 >= boneParams.length) return;

            float animRx = boneParams[pOffset];
            float animRy = boneParams[pOffset + 1];
            float animRz = boneParams[pOffset + 2];
            float animTx = boneParams[pOffset + 3];
            float animTy = boneParams[pOffset + 4];
            float animTz = boneParams[pOffset + 5];
            float animSx = boneParams[pOffset + 6];
            float animSy = boneParams[pOffset + 7];
            float animSz = boneParams[pOffset + 8];

            if (animSx == 0.0f && animSy == 0.0f && animSz == 0.0f) {
                isVisible = false;
            }

            if (!isVisible) {
                return;
            }

            localMat.translate((bone.pivotX - animTx) * 0.0625f, (bone.pivotY + animTy) * 0.0625f, (bone.pivotZ + animTz) * 0.0625f);

            localMat.rotateZ(animRz);
            localMat.rotateY(animRy);
            localMat.rotateX(animRx);

            if (animSx != 1.0f || animSy != 1.0f || animSz != 1.0f) {
                localMat.scale(animSx, animSy, animSz);
            }

            if (boneIdx == targetIdx) {
                stateBuffer[stateOffset] = -localMat.m30() * 16.0f;
                stateBuffer[stateOffset + 1] = localMat.m31() * 16.0f;
                stateBuffer[stateOffset + 2] = localMat.m32() * 16.0f;
                return;
            }

            localMat.translate(-bone.pivotX / 16.0f, -bone.pivotY / 16.0f, -bone.pivotZ / 16.0f);
        }
    }

    private static final class GlStateSnapshot {
        private final int program;
        private final int vao;
        private final int arrayBuffer;
        private final int elementArrayBuffer;
        private final int shaderStorageBuffer;
        private final int shaderStorageBase0;
        private final int activeTexture;
        private final int texture0;
        private final int texture1;
        private final int texture2;
        private final boolean blend;
        private final boolean depthTest;
        private final boolean cull;
        private final boolean depthMask;
        private final int frontFace;
        private final int blendSrcRgb;
        private final int blendDstRgb;
        private final int blendSrcAlpha;
        private final int blendDstAlpha;
        private final int blendEquationRgb;
        private final int blendEquationAlpha;

        private GlStateSnapshot(
                int program,
                int vao,
                int arrayBuffer,
                int elementArrayBuffer,
                int shaderStorageBuffer,
                int shaderStorageBase0,
                int activeTexture,
                int texture0,
                int texture1,
                int texture2,
                boolean blend,
                boolean depthTest,
                boolean cull,
                boolean depthMask,
                int frontFace,
                int blendSrcRgb,
                int blendDstRgb,
                int blendSrcAlpha,
                int blendDstAlpha,
                int blendEquationRgb,
                int blendEquationAlpha
        ) {
            this.program = program;
            this.vao = vao;
            this.arrayBuffer = arrayBuffer;
            this.elementArrayBuffer = elementArrayBuffer;
            this.shaderStorageBuffer = shaderStorageBuffer;
            this.shaderStorageBase0 = shaderStorageBase0;
            this.activeTexture = activeTexture;
            this.texture0 = texture0;
            this.texture1 = texture1;
            this.texture2 = texture2;
            this.blend = blend;
            this.depthTest = depthTest;
            this.cull = cull;
            this.depthMask = depthMask;
            this.frontFace = frontFace;
            this.blendSrcRgb = blendSrcRgb;
            this.blendDstRgb = blendDstRgb;
            this.blendSrcAlpha = blendSrcAlpha;
            this.blendDstAlpha = blendDstAlpha;
            this.blendEquationRgb = blendEquationRgb;
            this.blendEquationAlpha = blendEquationAlpha;
        }

        static GlStateSnapshot capture() {
            int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int texture0 = textureBinding(GL13.GL_TEXTURE0);
            int texture1 = textureBinding(GL13.GL_TEXTURE0 + 1);
            int texture2 = textureBinding(GL13.GL_TEXTURE0 + 2);
            GlStateManager._activeTexture(activeTexture);

            return new GlStateSnapshot(
                    GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                    GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                    GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                    GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING),
                    GL11.glGetInteger(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER_BINDING),
                    GL30.glGetIntegeri(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER_BINDING, BoneSkinShader.ssbo),
                    activeTexture,
                    texture0,
                    texture1,
                    texture2,
                    GL11.glIsEnabled(GL11.GL_BLEND),
                    GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                    GL11.glIsEnabled(GL11.GL_CULL_FACE),
                    GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                    GL11.glGetInteger(GL11.GL_FRONT_FACE),
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                    GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                    GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),
                    GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)
            );
        }

        private static int textureBinding(int textureUnit) {
            GlStateManager._activeTexture(textureUnit);
            return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }

        void restore() {
            restoreFlag(blend, RenderSystem::enableBlend, RenderSystem::disableBlend);
            restoreFlag(depthTest, RenderSystem::enableDepthTest, RenderSystem::disableDepthTest);
            restoreFlag(cull, RenderSystem::enableCull, RenderSystem::disableCull);
            GlStateManager._depthMask(depthMask);
            GL11.glFrontFace(frontFace);
            GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            GlStateManager._blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);

            GlStateManager._glUseProgram(program);
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer);
            GL30.glBindBufferBase(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, BoneSkinShader.ssbo, shaderStorageBase0);
            GlStateManager._glBindBuffer(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, shaderStorageBuffer);

            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(texture0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 1);
            GlStateManager._bindTexture(texture1);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 2);
            GlStateManager._bindTexture(texture2);
            GlStateManager._activeTexture(activeTexture);
        }

        private static void restoreFlag(boolean enabled, Runnable enable, Runnable disable) {
            if (enabled) {
                enable.run();
            } else {
                disable.run();
            }
        }

        @Override
        public String toString() {
            return "program=" + program
                    + ",vao=" + vao
                    + ",arrayBuffer=" + arrayBuffer
                    + ",elementArrayBuffer=" + elementArrayBuffer
                    + ",ssbo=" + shaderStorageBuffer
                    + ",ssbo0=" + shaderStorageBase0
                    + ",activeTexture=0x" + Integer.toHexString(activeTexture)
                    + ",tex0=" + texture0
                    + ",tex1=" + texture1
                    + ",tex2=" + texture2
                    + ",blend=" + blend
                    + ",depthTest=" + depthTest
                    + ",cull=" + cull
                    + ",depthMask=" + depthMask
                    + ",frontFace=0x" + Integer.toHexString(frontFace);
        }
    }
}
