package rip.ysm.gpu;

import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public final class GpuMesh {
    public final long pointer;
    public final int vao;
    public final int vbo;
    public final int ibo;
    public final int boneSsbo;
    public final int vertexCount;
    public final int indexCount;
    public final int boneCount;
    public final int partMask1Start, partMask1Count;
    public final int partMask2Start, partMask2Count;
    public final int partMask3Start, partMask3Count;
    public final ByteBuffer perFrameBoneBuffer;
    private boolean disposed = false;

    GpuMesh(long pointer, int vao, int vbo, int ibo, int boneSsbo, int vertexCount, int indexCount, int boneCount, int pm1s, int pm1c, int pm2s, int pm2c, int pm3s, int pm3c) {
        this.pointer = pointer;
        this.vao = vao;
        this.vbo = vbo;
        this.ibo = ibo;
        this.boneSsbo = boneSsbo;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.boneCount = boneCount;
        this.partMask1Start = pm1s;
        this.partMask1Count = pm1c;
        this.partMask2Start = pm2s;
        this.partMask2Count = pm2c;
        this.partMask3Start = pm3s;
        this.partMask3Count = pm3c;
        this.perFrameBoneBuffer = MemoryUtil.memAlloc(boneCount * 144);
    }

    public int indexOffsetBytes(int renderPartMask) {
        if (renderPartMask == 0 || renderPartMask == 3) return 0;
        if (renderPartMask == 1) return partMask1Start * Integer.BYTES;
        if (renderPartMask == 2) return partMask2Start * Integer.BYTES;
        return 0;
    }

    public int indexDrawCount(int renderPartMask) {
        if (renderPartMask == 0) return indexCount;
        if (renderPartMask == 3) return indexCount;
        int self = (renderPartMask == 1) ? partMask1Count : (renderPartMask == 2) ? partMask2Count : 0;
        return self + partMask3Count;
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        GlStateManager._glDeleteBuffers(vbo);
        GlStateManager._glDeleteBuffers(ibo);
        GlStateManager._glDeleteBuffers(boneSsbo);
        GL45.glDeleteVertexArrays(vao);
        if (pointer != 0) {
            GeoModel.nFreeGpuMesh(pointer);
        }
        MemoryUtil.memFree(perFrameBoneBuffer);
    }
}
