package com.elfmcys.yesstevemodel.client.texture;

import rip.ysm.compat.oculus.ShadersTextureType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

public class OuterFileTexture extends AbstractTexture implements ITextureMap {
    private final byte[] data;
    private final int rawRgbaWidth;
    private final int rawRgbaHeight;

    private Map<ShadersTextureType, OuterFileTexture> suffixTextures = Reference2ReferenceMaps.emptyMap();

    public OuterFileTexture(byte[] data) {
        this.data = data;
        this.rawRgbaWidth = 0;
        this.rawRgbaHeight = 0;
    }

    public OuterFileTexture(byte[] rawRgbaData, int width, int height) {
        this.data = rawRgbaData;
        this.rawRgbaWidth = width;
        this.rawRgbaHeight = height;
    }

    @Override
    public void load(@NotNull ResourceManager resourceManager) {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(this::doLoad);
        } else {
            doLoad();
        }
    }

    public void doLoad() {
        try {
            NativeImage imageIn = isRawRgba() ? readRawRgba() : NativeImage.read(new ByteArrayInputStream(data));
            int width = imageIn.getWidth();
            int height = imageIn.getHeight();
            TextureUtil.prepareImage(this.getId(), 0, width, height);
            imageIn.upload(0, 0, 0, 0, 0, width, height, false, false, false, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isRawRgba() {
        return rawRgbaWidth > 0 && rawRgbaHeight > 0;
    }

    private NativeImage readRawRgba() throws IOException {
        long expectedLength = (long) rawRgbaWidth * (long) rawRgbaHeight * 4L;
        if (data == null || data.length < expectedLength) {
            throw new IOException("Invalid raw RGBA texture data");
        }

        NativeImage image = new NativeImage(rawRgbaWidth, rawRgbaHeight, false);
        for (int y = 0; y < rawRgbaHeight; y++) {
            int row = y * rawRgbaWidth * 4;
            for (int x = 0; x < rawRgbaWidth; x++) {
                int offset = row + x * 4;
                int r = data[offset] & 0xFF;
                int g = data[offset + 1] & 0xFF;
                int b = data[offset + 2] & 0xFF;
                int a = data[offset + 3] & 0xFF;
                image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return image;
    }

    public void setSuffixTextures(Map<ShadersTextureType, OuterFileTexture> map) {
        this.suffixTextures = Reference2ReferenceMaps.unmodifiable(new Reference2ReferenceOpenHashMap<>(map));
    }

    public Map<ShadersTextureType, ? extends AbstractTexture> getSuffixTextures() {
        return this.suffixTextures;
    }
}
