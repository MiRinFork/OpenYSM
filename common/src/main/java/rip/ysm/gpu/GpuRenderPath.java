package rip.ysm.gpu;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.mixin.client.RenderSystemAccessor;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class GpuRenderPath {
    private static final float[] rootPoseScratch = new float[16];
    private static final float[] rootNormalScratch = new float[9];
    private static final float[] projScratch = new float[16];
    private static final Matrix4f projMVScratch = new Matrix4f();
    private static final Vector3f[] currentLights = new Vector3f[2];
    private static final ConcurrentHashMap<Long, GpuMesh> meshMap = new ConcurrentHashMap<>();
    private static final AtomicLong ref = new AtomicLong(1);

    public static boolean tryRender(GeoModel model, PoseStack.Pose pose, float[] boneParams, int renderPartMask, int packedLight, int packedOverlay, float r, float g, float b, float a, ResourceLocation textureLocation) {
        if (!GpuCapability.isAvailable()) return false;
        if (!BoneSkinShader.ensureCompiled()) return false;
        if (model.bakedBones == null || model.bakedBones.isEmpty()) return false;

        if (model.gpuMeshHandle == 0) {
            GpuMesh mesh = GpuMeshBuilder.build(model);
            if (mesh == null) return false;
            model.gpuMeshHandle = encodeMeshRef(mesh);
        }
        GpuMesh mesh = decodeMeshRef(model.gpuMeshHandle);
        if (mesh == null) return false;

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
        GeoModel.nComputeBoneMatrices(mesh.pointer, rootPoseScratch, rootNormalScratch, boneParams, packedLight, boneBuf);
        boneBuf.position(0);
        boneBuf.limit(mesh.boneCount * 144);

        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        Minecraft mc = Minecraft.getInstance();
        AbstractTexture modelTex = mc.getTextureManager().getTexture(textureLocation);
        int modelTexId = modelTex.getId();

        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 2);
        mc.gameRenderer.lightTexture().turnOnLightLayer();

        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 1);
        mc.gameRenderer.overlayTexture().setupOverlayColor();

        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(modelTexId);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, mesh.boneSsbo);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, boneBuf);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BoneSkinShader.ssbo, mesh.boneSsbo);

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
        int drawCount = mesh.indexDrawCount(renderPartMask);
        if (drawCount > 0) {
            GL11.glDrawElements(GL11.GL_TRIANGLES, drawCount, GL11.GL_UNSIGNED_INT, offsetBytes);
        }

        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BoneSkinShader.ssbo, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GlStateManager._glUseProgram(0);

        com.mojang.blaze3d.vertex.BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(0);

        mc.gameRenderer.lightTexture().turnOffLightLayer();

        return true;
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

    private static long encodeMeshRef(GpuMesh mesh) {
        long ref = GpuRenderPath.ref.getAndIncrement();
        meshMap.put(ref, mesh);
        return ref;
    }

    private static GpuMesh decodeMeshRef(long ref) {
        return meshMap.get(ref);
    }
}
