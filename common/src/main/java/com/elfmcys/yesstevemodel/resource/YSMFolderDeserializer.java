package com.elfmcys.yesstevemodel.resource;

import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import rip.ysm.imagestream.avif.AvifDecoder;
import rip.ysm.imagestream.webp.WebpDecoder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

import static com.elfmcys.yesstevemodel.util.DigestUtil.md5Digest;
import static com.elfmcys.yesstevemodel.util.DigestUtil.md5Hex;
import static com.elfmcys.yesstevemodel.util.DigestUtil.sha256Hex;

public class YSMFolderDeserializer implements AutoCloseable {
    private static final Vector3f NORMAL_NORTH = new Vector3f(0, 0, -1);
    private static final Vector3f NORMAL_SOUTH = new Vector3f(0, 0, 1);
    private static final Vector3f NORMAL_EAST = new Vector3f(1, 0, 0);
    private static final Vector3f NORMAL_WEST = new Vector3f(-1, 0, 0);
    private static final Vector3f NORMAL_UP = new Vector3f(0, 1, 0);
    private static final Vector3f NORMAL_DOWN = new Vector3f(0, -1, 0);

    private static final int[] FACE_WEST_CORNERS = {3, 2, 0, 1};   // p4, p3, p1, p2
    private static final int[] FACE_EAST_CORNERS = {6, 7, 5, 4};   // p7, p8, p6, p5
    private static final int[] FACE_NORTH_CORNERS = {2, 6, 4, 0};  // p3, p7, p5, p1
    private static final int[] FACE_SOUTH_CORNERS = {7, 3, 1, 5};  // p8, p4, p2, p6
    private static final int[] FACE_UP_CORNERS = {3, 7, 6, 2};     // p4, p8, p7, p3
    private static final int[] FACE_DOWN_CORNERS = {0, 4, 5, 1};   // p1, p5, p6, p2

    private static final Float FLOAT_ZERO = 0f;
    private static final Comparator<Map.Entry<String, JsonElement>> ENTRY_BY_NUMERIC_KEY =
            Comparator.comparingDouble(e -> Double.parseDouble(e.getKey()));

    private static JsonObject parseJsonObject(byte[] data) {
        return JsonParser.parseReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)
        ).getAsJsonObject();
    }

    private final Map<String, String> readFilesMd5Map = new TreeMap<>();
    private String finalFolderHash;
    private final Path rootPath;
    private final FileSystem zipFileSystem;
    private final RawYsmModel model;

    private final Map<String, byte[]> inMemoryFiles;

    private final Vector3f scratchNormal = new Vector3f();
    private final Vector4f scratchPos = new Vector4f();
    private final Matrix4f scratchBakeMat = new Matrix4f();
    private final Matrix3f scratchNormalMat = new Matrix3f();

    public YSMFolderDeserializer(Path sourcePath) throws IOException {
        if (!Files.exists(sourcePath)) {
            throw new FileNotFoundException("Model source not found: " + sourcePath);
        }

        this.inMemoryFiles = null;

        if (Files.isDirectory(sourcePath)) {
            this.rootPath = sourcePath;
            this.zipFileSystem = null;
        } else if (sourcePath.toString().endsWith(".zip") || sourcePath.toString().endsWith(".ysm")) {
            URI uri = URI.create("jar:" + sourcePath.toUri());
            this.zipFileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
            this.rootPath = this.zipFileSystem.getPath("/");
        } else {
            throw new IllegalArgumentException("Unsupported file type. Expected directory or .zip");
        }

        this.model = new RawYsmModel();
        this.model.formatVersion = 65535;
    }

    public YSMFolderDeserializer(Map<String, byte[]> memoryFiles) {
        this.inMemoryFiles = memoryFiles;
        this.rootPath = null;
        this.zipFileSystem = null;
        this.model = new RawYsmModel();
        this.model.formatVersion = 65535;
    }

    private byte[] readResource(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        try {
            String path = relativePath;
            if (!path.isEmpty() && path.charAt(0) == '/') {
                path = path.substring(1);
            }
            String normalizedPath = path.indexOf('\\') >= 0 ? path.replace('\\', '/') : path;
            byte[] data = null;

            if (inMemoryFiles == null) {
                Path target = rootPath.resolve(path);
                if (Files.exists(target) && Files.isRegularFile(target)) {
                    data = Files.readAllBytes(target);
                }
            } else {
                data = inMemoryFiles.get(normalizedPath);
            }

            if (data != null && !readFilesMd5Map.containsKey(normalizedPath)) {
                readFilesMd5Map.put(normalizedPath, md5Hex(data));
            }
            return data;

        } catch (Exception e) {
            System.err.println("[YSM] Warning: Failed to read resource: " + relativePath);
        }
        return null;
    }

    public RawYsmModel deserialize() {
        byte[] ysmJsonBytes = readResource("ysm.json");
        if (ysmJsonBytes != null) {  // https://ysm.cfpa.team/wiki/struct/#%E6%96%87%E4%BB%B6%E7%9B%AE%E5%BD%95%E7%BB%93%E6%9E%84
            parseYsmJson(parseJsonObject(ysmJsonBytes));
        } else parseLegacyFormat();

        parseGlobalResources();

        this.finalFolderHash = calculateFinalFolderHash();
        model.properties.sha256 = finalFolderHash;

        model.footer.version = 65535;
        return model;
    }

    @Override
    public void close() throws IOException {
        if (this.zipFileSystem != null) {
            this.zipFileSystem.close();
        }
        if (inMemoryFiles != null) inMemoryFiles.clear();
    }

    private void parseYsmJson(JsonObject ysmJson) {
        if (ysmJson.has("metadata")) parseMetadata(ysmJson.getAsJsonObject("metadata"));
        if (ysmJson.has("properties")) parseProperties(ysmJson.getAsJsonObject("properties"));
        if (ysmJson.has("files")) {
            JsonObject files = ysmJson.getAsJsonObject("files");
            if (files.has("player")) parseMainEntity(files.getAsJsonObject("player"));
            if (files.has("vehicles")) parseSubEntities(files.get("vehicles"), model.vehicles, "vehicle");
            if (files.has("projectiles")) parseSubEntities(files.get("projectiles"), model.projectiles, "projectile");
        }
    }

    private void parseMetadata(JsonObject metaObj) {
        model.metadata.name = getStr(metaObj, "name", "");
        model.metadata.tips = getStr(metaObj, "tips", "");
        if (metaObj.has("license") && metaObj.get("license").isJsonObject()) {
            JsonObject licObj = metaObj.getAsJsonObject("license");
            model.metadata.licenseType = getStr(licObj, "type", "");
            model.metadata.licenseDescription = getStr(licObj, "desc", "");
        }

        if (metaObj.has("authors") && metaObj.get("authors").isJsonArray()) {
            for (JsonElement elem : metaObj.getAsJsonArray("authors")) {
                if (!elem.isJsonObject()) continue;
                JsonObject authorObj = elem.getAsJsonObject();
                RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
                author.name = getStr(authorObj, "name", "");
                author.role = getStr(authorObj, "role", "");
                author.comment = getStr(authorObj, "comment", "");

                if (authorObj.has("contact") && authorObj.get("contact").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> cEntry : authorObj.getAsJsonObject("contact").entrySet()) {
                        author.contacts.put(cEntry.getKey(), cEntry.getValue().getAsString());
                    }
                }

                if (authorObj.has("avatar")) {
                    String avatarPath = getStr(authorObj, "avatar", "");
                    if (!avatarPath.isEmpty()) {
                        byte[] avatarData = readResource(avatarPath);
                        if (avatarData != null) {
                            ImageMeta meta = parseImageMeta(avatarData, avatarPath);
                            RawYsmModel.RawImage img = new RawYsmModel.RawImage();
                            img.width = meta.width();
                            img.height = meta.height();
                            img.format = meta.format();
                            img.name = author.name;
                            img.data = avatarData;
                            img.unknownFlag = 1;

                            author.avatar = avatarPath;
                            author.avatarImage = img;
                        }
                    }
                }
                model.metadata.authors.add(author);
            }
        }

        if (metaObj.has("link") && metaObj.get("link").isJsonObject()) {
            for (Map.Entry<String, JsonElement> linkEntry : metaObj.getAsJsonObject("link").entrySet()) {
                model.metadata.links.put(linkEntry.getKey(), linkEntry.getValue().getAsString());
            }
        }
    }

    private void parseProperties(JsonObject propsObj) {
        model.properties.widthScale = (float) getDouble(propsObj, "width_scale", 0.7);
        model.properties.heightScale = (float) getDouble(propsObj, "height_scale", 0.7);
        model.properties.defaultTexture = getStr(propsObj, "default_texture", "default");
        model.properties.previewAnimation = getStr(propsObj, "preview_animation", "");
        model.properties.isFree = getBool(propsObj, "free", false);
        model.properties.renderLayersFirst = getBool(propsObj, "render_layers_first", false);
        model.properties.allCutout = getBool(propsObj, "all_cutout", false);
        model.properties.disablePreviewRotation = getBool(propsObj, "disable_preview_rotation", false);
        model.properties.guiNoLighting = getBool(propsObj, "gui_no_lighting", false);
        model.properties.mergeMultilineExpr = getBool(propsObj, "merge_multiline_expr", false);
        model.properties.guiForeground = getStr(propsObj, "gui_foreground", "");
        model.properties.guiBackground = getStr(propsObj, "gui_background", "");
        if (propsObj.has("extra_animation") && propsObj.get("extra_animation").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : propsObj.getAsJsonObject("extra_animation").entrySet()) {
                model.properties.extraAnimations.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        if (propsObj.has("extra_animation_classify") && propsObj.get("extra_animation_classify").isJsonArray()) {
            for (JsonElement elem : propsObj.getAsJsonArray("extra_animation_classify")) {
                if (!elem.isJsonObject()) continue;
                JsonObject clsObj = elem.getAsJsonObject();
                RawYsmModel.ExtraAnimationClassify classify = new RawYsmModel.ExtraAnimationClassify();
                classify.id = getStr(clsObj, "id", "");
                if (clsObj.has("extra_animation") && clsObj.get("extra_animation").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : clsObj.getAsJsonObject("extra_animation").entrySet()) {
                        classify.extras.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                model.properties.extraAnimationClassifies.add(classify);
            }
        }

        if (propsObj.has("extra_animation_buttons") && propsObj.get("extra_animation_buttons").isJsonArray()) {
            for (JsonElement elem : propsObj.getAsJsonArray("extra_animation_buttons")) {
                if (!elem.isJsonObject()) continue;
                JsonObject btnObj = elem.getAsJsonObject();
                RawYsmModel.ExtraAnimationButton btn = new RawYsmModel.ExtraAnimationButton();
                btn.id = getStr(btnObj, "id", "");
                btn.name = getStr(btnObj, "name", "");
                btn.description = getStr(btnObj, "description", "");

                if (btnObj.has("config_forms") && btnObj.get("config_forms").isJsonArray()) {
                    for (JsonElement formElem : btnObj.getAsJsonArray("config_forms")) {
                        if (!formElem.isJsonObject()) continue;
                        JsonObject formObj = formElem.getAsJsonObject();
                        RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
                        form.type = getStr(formObj, "type", "");
                        form.title = getStr(formObj, "title", "");
                        form.description = getStr(formObj, "description", "");
                        form.defaultValue = getStr(formObj, "value", "");
                        form.step = (float) getDouble(formObj, "step", 0);
                        form.min = (float) getDouble(formObj, "min", 0);
                        form.max = (float) getDouble(formObj, "max", 0);
                        if (formObj.has("labels") && formObj.get("labels").isJsonObject()) {
                            for (Map.Entry<String, JsonElement> lEntry : formObj.getAsJsonObject("labels").entrySet()) {
                                form.labels.put(lEntry.getKey(), lEntry.getValue().getAsString());
                            }
                        }
                        btn.forms.add(form);
                    }
                }
                model.properties.extraAnimationButtons.add(btn);
            }
        }

        loadGuiImage(model.properties.guiBackground, "gui_background");
        loadGuiImage(model.properties.guiForeground, "gui_foreground");
    }

    private void loadGuiImage(String path, String id) {
        if (path == null || path.isEmpty()) return;
        byte[] data = readResource(path);
        if (data == null) data = readResource("background/" + id + ".png");

        if (data != null) {
            ImageMeta meta = parseImageMeta(data, path);
            RawYsmModel.RawImage img = new RawYsmModel.RawImage();
            img.width = meta.width();
            img.height = meta.height();
            img.format = meta.format();
            img.name = id;
            img.data = data;
            img.unknownFlag = 1;
            model.properties.backgroundImages.add(img);
        }
    }

    private void parseMainEntity(JsonObject playerObj) {
        if (playerObj.has("model") && playerObj.get("model").isJsonObject()) {
            JsonObject modelObj = playerObj.getAsJsonObject("model");
            if (modelObj.has("main")) {
                byte[] geoData = readResource(modelObj.get("main").getAsString());
                if (geoData != null) model.mainEntity.mainModel = parseGeometry(geoData, 1);
            }
            if (modelObj.has("arm")) {
                byte[] geoData = readResource(modelObj.get("arm").getAsString());
                if (geoData != null) model.mainEntity.armModel = parseGeometry(geoData, 2);
            }
        }

        if (playerObj.has("texture")) {
            JsonElement texElem = playerObj.get("texture");
            if (texElem.isJsonArray()) {
                for (JsonElement elem : texElem.getAsJsonArray()) processMainTextureEntry(elem);
            } else {
                processMainTextureEntry(texElem);
            }
        }

        if (playerObj.has("animation") && playerObj.get("animation").isJsonObject()) {
            JsonObject animObj = playerObj.getAsJsonObject("animation");
            for (Map.Entry<String, JsonElement> entry : animObj.entrySet()) {
                byte[] animData = readResource(entry.getValue().getAsString());
                if (animData != null) {
                    RawYsmModel.RawAnimationFile raf = parseAnimations(animData);
                    raf.fileHash = sha256Hex(animData);
                    raf.animType = getAnimTypeFromKey(entry.getKey());
                    model.mainEntity.animationFiles.put(entry.getKey(), raf);
                }
            }
        }
        if (playerObj.has("animation_controllers") && playerObj.get("animation_controllers").isJsonArray()) {
            for (JsonElement acElem : playerObj.getAsJsonArray("animation_controllers")) {
                String acPath = acElem.getAsString();
                byte[] acData = readResource(acPath);
                if (acData != null) {
                    String acHash = sha256Hex(acData);
                    RawYsmModel.RawAnimationControllerFile acFile = new RawYsmModel.RawAnimationControllerFile();
                    acFile.name = extractFileName(acPath);
                    acFile.hash = acHash;
                    parseAnimationControllers(acData, acFile.controllers);
                    model.mainEntity.animationControllerFiles.add(acFile);
                }
            }
        }
    }

    private void processMainTextureEntry(JsonElement elem) {
        String texPath = null;
        JsonObject elemObj = null;
        if (elem.isJsonPrimitive()) {
            texPath = elem.getAsString();
        } else if (elem.isJsonObject()) {
            elemObj = elem.getAsJsonObject();
            JsonElement uv = elemObj.get("uv");
            if (uv != null) texPath = uv.getAsString();
        }
        if (texPath == null) return;

        byte[] texData = readResource(texPath);
        if (texData == null) return;

        ImageMeta meta = parseImageMeta(texData, texPath);
        RawYsmModel.RawTexture rt = new RawYsmModel.RawTexture();
        rt.hash = sha256Hex(texData); // 计算原始数据的 hash
        rt.width = meta.width();
        rt.height = meta.height();
        rt.imageFormat = meta.format();
        rt.name = extractFileName(texPath);
        rt.data = texData;
        rt.unknownFlag = 1;

        if (elemObj != null) {
            JsonElement specular = elemObj.get("specular");
            if (specular != null) {
                byte[] spData = readResource(specular.getAsString());
                if (spData != null) {
                    ImageMeta spMeta = parseImageMeta(spData, "specular");
                    RawYsmModel.RawTexture.SubTexture sub = new RawYsmModel.RawTexture.SubTexture();
                    sub.specularType = 2;
                    sub.data = spData;
                    sub.unknownFlag = 1;
                    sub.hash = sha256Hex(spData);
                    sub.width = spMeta.width();
                    sub.height = spMeta.height();
                    sub.imageFormat = spMeta.format();
                    rt.subTextures.add(sub);
                }
            }
            JsonElement normal = elemObj.get("normal");
            if (normal != null) {
                byte[] nrData = readResource(normal.getAsString());
                if (nrData != null) {
                    ImageMeta nrMeta = parseImageMeta(nrData, "normal");
                    RawYsmModel.RawTexture.SubTexture sub = new RawYsmModel.RawTexture.SubTexture();
                    sub.specularType = 1;
                    sub.data = nrData;
                    sub.unknownFlag = 1;
                    sub.hash = sha256Hex(nrData);
                    sub.width = nrMeta.width();
                    sub.height = nrMeta.height();
                    sub.imageFormat = nrMeta.format();
                    rt.subTextures.add(sub);
                }
            }
        }
        model.mainEntity.textures.put(rt.name, rt);
    }

    private void parseSubEntities(JsonElement sectionElem, Map<String, RawYsmModel.RawSubEntity> targetMap, String defaultIdentifier) {
        if (sectionElem.isJsonArray()) {
            int index = 0;
            for (JsonElement e : sectionElem.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                processSubEntity(e.getAsJsonObject(), defaultIdentifier + "_" + index, index, targetMap);
                index++;
            }
        } else if (sectionElem.isJsonObject()) {
            int index = 0;
            for (Map.Entry<String, JsonElement> entry : sectionElem.getAsJsonObject().entrySet()) {
                JsonElement v = entry.getValue();
                if (!v.isJsonObject()) continue;
                JsonObject item = v.getAsJsonObject();
                String id = item.has("match") ? (defaultIdentifier + "_" + index) : entry.getKey();
                processSubEntity(item, id, index, targetMap);
                index++;
            }
        }
    }

    private void processSubEntity(JsonObject item, String identifier, int index, Map<String, RawYsmModel.RawSubEntity> targetMap) {
        RawYsmModel.RawSubEntity sub = new RawYsmModel.RawSubEntity();
        sub.identifier = identifier;

        JsonElement match = item.get("match");
        if (match != null) {
            if (match.isJsonArray()) {
                JsonArray mArr = match.getAsJsonArray();
                sub.matchIds = new String[mArr.size()];
                for (int i = 0; i < mArr.size(); i++) sub.matchIds[i] = mArr.get(i).getAsString();
            } else if (match.isJsonPrimitive()) {
                sub.matchIds = new String[]{match.getAsString()};
            }
        }

        JsonElement modelElem = item.get("model");
        if (modelElem != null) {
            byte[] geoData = readResource(modelElem.getAsString());
            if (geoData != null) sub.model = parseGeometry(geoData, 3);
        }

        JsonElement textureElem = item.get("texture");
        if (textureElem != null) {
            String texPath = textureElem.isJsonObject() ? textureElem.getAsJsonObject().get("uv").getAsString() : textureElem.getAsString();
            byte[] texData = readResource(texPath);
            if (texData != null) {
                ImageMeta meta = parseImageMeta(texData, texPath);
                RawYsmModel.RawTexture rt = new RawYsmModel.RawTexture();

                rt.hash = sha256Hex(texData);
                rt.width = meta.width();
                rt.height = meta.height();
                rt.imageFormat = meta.format();

                rt.name = "base_texture_" + index;
                rt.data = texData;
                rt.unknownFlag = 1;
                sub.textures.put(rt.name, rt);
            }
        }

        JsonElement animElem = item.get("animation");
        if (animElem != null) {
            byte[] animData = readResource(animElem.getAsString());
            if (animData != null) {
                RawYsmModel.RawAnimationFile raf = parseAnimations(animData);
                raf.fileHash = sha256Hex(animData);
                raf.animType = getAnimTypeFromKey("extra");
                sub.animationFiles.put("sub_anim", raf);
            }
        }

        JsonElement controllerElem = item.get("controller");
        if (controllerElem != null) {
            String acPath = controllerElem.getAsString();
            byte[] acData = readResource(acPath);
            if (acData != null) {
                String acHash = sha256Hex(acData);
                RawYsmModel.RawAnimationControllerFile acFile = new RawYsmModel.RawAnimationControllerFile();
                acFile.name = extractFileName(acPath);
                acFile.hash = acHash;
                parseAnimationControllers(acData, acFile.controllers);
                sub.animationControllerFiles.add(acFile);
            }
        }

        targetMap.put(sub.identifier, sub);
    }

    private RawYsmModel.RawGeometry parseGeometry(byte[] data, int modelType) {
        JsonObject root = parseJsonObject(data);
        JsonArray geometries = root.has("minecraft:geometry") ? root.getAsJsonArray("minecraft:geometry") : null;
        if (geometries == null || geometries.isEmpty()) return new RawYsmModel.RawGeometry();

        JsonObject geoObj = geometries.get(0).getAsJsonObject();
        RawYsmModel.RawGeometry geo = new RawYsmModel.RawGeometry();
        geo.sha256 = sha256Hex(data);

        geo.modelType = modelType;
        geo.unkFloat1 = 0.7f;
        geo.unkFloat2 = 0.7f;

        if (geoObj.has("description")) {
            JsonObject desc = geoObj.getAsJsonObject("description");
            geo.identifier = getStr(desc, "identifier", "");
            geo.textureWidth = (float) getDouble(desc, "texture_width", 64.0);
            geo.textureHeight = (float) getDouble(desc, "texture_height", 64.0);
            geo.visibleBoundsWidth = (float) getDouble(desc, "visible_bounds_width", 0);
            geo.visibleBoundsHeight = (float) getDouble(desc, "visible_bounds_height", 0);
            if (desc.has("visible_bounds_offset") && desc.get("visible_bounds_offset").isJsonArray()) {
                JsonArray offsetArr = desc.getAsJsonArray("visible_bounds_offset");
                geo.visibleBoundsOffset = new float[offsetArr.size()];
                for (int i = 0; i < offsetArr.size(); i++) geo.visibleBoundsOffset[i] = offsetArr.get(i).getAsFloat();
            } else {
                geo.visibleBoundsOffset = new float[0];
            }

            if (modelType == 1 && desc.has("ysm_extra_info")) { // legacy
                parseLegacyMetadata(desc.getAsJsonObject("ysm_extra_info"), false);
            }
        }

        if (geoObj.has("bones") && geoObj.get("bones").isJsonArray()) {
            for (JsonElement boneElem : geoObj.getAsJsonArray("bones")) {
                if (!boneElem.isJsonObject()) continue;
                JsonObject bObj = boneElem.getAsJsonObject();
                RawYsmModel.RawBone bone = new RawYsmModel.RawBone();
                bone.name = getStr(bObj, "name", "");
                bone.parentName = getStr(bObj, "parent", "");

                if (bObj.has("pivot")) {
                    JsonArray pivot = bObj.getAsJsonArray("pivot");
                    bone.pivot[0] = -pivot.get(0).getAsFloat();
                    bone.pivot[1] = pivot.get(1).getAsFloat();
                    bone.pivot[2] = pivot.get(2).getAsFloat();
                }
                if (bObj.has("rotation")) {
                    JsonArray rot = bObj.getAsJsonArray("rotation");
                    bone.rotation[0] = (float) -Math.toRadians(rot.get(0).getAsFloat());
                    bone.rotation[1] = (float) -Math.toRadians(rot.get(1).getAsFloat());
                    bone.rotation[2] = (float) Math.toRadians(rot.get(2).getAsFloat());
                }

                float boneInflate = (float) getDouble(bObj, "inflate", 0.0);
                boolean boneMirror = getBool(bObj, "mirror", false);

                if (bObj.has("cubes") && bObj.get("cubes").isJsonArray()) {
                    for (JsonElement cElem : bObj.getAsJsonArray("cubes")) {
                        if (!cElem.isJsonObject()) continue;
                        JsonObject cObj = cElem.getAsJsonObject();
                        RawYsmModel.RawCube cube = new RawYsmModel.RawCube();

                        float inflate = cObj.has("inflate") ? cObj.get("inflate").getAsFloat() : boneInflate;
                        boolean mirror = cObj.has("mirror") ? cObj.get("mirror").getAsBoolean() : boneMirror;

                        JsonArray originArr = cObj.has("origin") ? cObj.getAsJsonArray("origin") : null;
                        float originX = readFloat(originArr, 0, 0f);
                        float originY = readFloat(originArr, 1, 0f);
                        float originZ = readFloat(originArr, 2, 0f);

                        JsonArray sizeArr = cObj.has("size") ? cObj.getAsJsonArray("size") : null;
                        float sizeX = readFloat(sizeArr, 0, 0f);
                        float sizeY = readFloat(sizeArr, 1, 0f);
                        float sizeZ = readFloat(sizeArr, 2, 0f);

                        float cx = -originX - sizeX - inflate;
                        float cy = originY - inflate;
                        float cz = originZ - inflate;
                        float cw = sizeX + inflate * 2;
                        float ch = sizeY + inflate * 2;
                        float cd = sizeZ + inflate * 2;

                        scratchBakeMat.identity();
                        if (cObj.has("rotation") || cObj.has("pivot")) {
                            JsonArray cpvtArr = cObj.has("pivot") ? cObj.getAsJsonArray("pivot") : null;
                            JsonArray crotArr = cObj.has("rotation") ? cObj.getAsJsonArray("rotation") : null;
                            float cpvtX = readFloat(cpvtArr, 0, 0f);
                            float cpvtY = readFloat(cpvtArr, 1, 0f);
                            float cpvtZ = readFloat(cpvtArr, 2, 0f);
                            float crotX = readFloat(crotArr, 0, 0f);
                            float crotY = readFloat(crotArr, 1, 0f);
                            float crotZ = readFloat(crotArr, 2, 0f);
                            scratchBakeMat.translate(-cpvtX / 16f, cpvtY / 16f, cpvtZ / 16f);
                            scratchBakeMat.rotateZ((float) Math.toRadians(crotZ));
                            scratchBakeMat.rotateY((float) -Math.toRadians(crotY));
                            scratchBakeMat.rotateX((float) -Math.toRadians(crotX));
                            scratchBakeMat.translate(cpvtX / 16f, -cpvtY / 16f, -cpvtZ / 16f);
                        }
                        scratchBakeMat.normal(scratchNormalMat);

                        if (cObj.has("uv")) {
                            JsonElement uvElem = cObj.get("uv");
                            if (uvElem.isJsonObject()) {
                                JsonObject uvObj = uvElem.getAsJsonObject();
                                bakeFaceToRaw(cube, uvObj, "north", "north", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_NORTH);
                                bakeFaceToRaw(cube, uvObj, "south", "south", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_SOUTH);
                                bakeFaceToRaw(cube, uvObj, "east", mirror ? "west" : "east", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_EAST);
                                bakeFaceToRaw(cube, uvObj, "west", mirror ? "east" : "west", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_WEST);
                                bakeFaceToRaw(cube, uvObj, "up", "up", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_UP);
                                bakeFaceToRaw(cube, uvObj, "down", "down", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_DOWN);
                            } else if (uvElem.isJsonArray()) {
                                JsonArray uvArr = uvElem.getAsJsonArray();
                                float uvX = uvArr.get(0).getAsFloat();
                                float uvY = uvArr.get(1).getAsFloat();
                                float dx = (float) Math.floor(sizeX);
                                float dy = (float) Math.floor(sizeY);
                                float dz = (float) Math.floor(sizeZ);

                                bakeFaceToRaw(cube, uvX + dz,           uvY + dz, dx,  dy, "north", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_NORTH);
                                bakeFaceToRaw(cube, uvX + dz + dx + dz, uvY + dz, dx,  dy, "south", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_SOUTH);
                                bakeFaceToRaw(cube, uvX,                uvY + dz, dz,  dy, mirror ? "west" : "east", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_EAST);
                                bakeFaceToRaw(cube, uvX + dz + dx,      uvY + dz, dz,  dy, mirror ? "east" : "west", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_WEST);
                                bakeFaceToRaw(cube, uvX + dz,           uvY,      dx,  dz, "up", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_UP);
                                bakeFaceToRaw(cube, uvX + dz + dx,      uvY + dz, dx, -dz, "down", mirror, cx, cy, cz, cw, ch, cd, geo.textureWidth, geo.textureHeight, NORMAL_DOWN);
                            }
                        }
                        bone.cubes.add(cube);
                    }
                }
                geo.bones.add(bone);
            }
        }
        return geo;
    }

    private void bakeFaceToRaw(RawYsmModel.RawCube cube, JsonObject uvObj, String faceType, String uvFaceName, boolean mirror, float x, float y, float z, float w, float h, float d, float tw, float th, Vector3fc rawNormal) {
        JsonObject faceData = uvObj.getAsJsonObject(uvFaceName);
        if (faceData == null) return;
        JsonArray uvArr = faceData.getAsJsonArray("uv");
        JsonArray uvSizeArr = faceData.getAsJsonArray("uv_size");
        float uvU = uvArr != null && uvArr.size() > 0 ? uvArr.get(0).getAsFloat() : 0f;
        float uvV = uvArr != null && uvArr.size() > 1 ? uvArr.get(1).getAsFloat() : 0f;
        float uvSizeU = uvSizeArr != null && uvSizeArr.size() > 0 ? uvSizeArr.get(0).getAsFloat() : 0f;
        float uvSizeV = uvSizeArr != null && uvSizeArr.size() > 1 ? uvSizeArr.get(1).getAsFloat() : 0f;
        bakeFaceToRaw(cube, uvU, uvV, uvSizeU, uvSizeV, faceType, mirror, x, y, z, w, h, d, tw, th, rawNormal);
    }

    private void bakeFaceToRaw(RawYsmModel.RawCube cube, float uvU, float uvV, float uvSizeU, float uvSizeV, String faceType, boolean mirror, float x, float y, float z, float w, float h, float d, float tw, float th, Vector3fc rawNormal) {
        int[] cornerIndices = switch (faceType) {
            case "west" -> FACE_WEST_CORNERS;
            case "east" -> FACE_EAST_CORNERS;
            case "north" -> FACE_NORTH_CORNERS;
            case "south" -> FACE_SOUTH_CORNERS;
            case "up" -> FACE_UP_CORNERS;
            case "down" -> FACE_DOWN_CORNERS;
            default -> null;
        };
        if (cornerIndices == null) return;

        float u0 = uvU / tw;
        float v0 = uvV / th;
        float u1 = (uvU + uvSizeU) / tw;
        float v1 = (uvV + uvSizeV) / th;

        if (!mirror) {
            float temp = u0; u0 = u1; u1 = temp;
        }

        RawYsmModel.RawFace face = new RawYsmModel.RawFace();

        scratchNormal.set(rawNormal).mul(scratchNormalMat).normalize();
        face.normal[0] = scratchNormal.x;
        face.normal[1] = scratchNormal.y;
        face.normal[2] = scratchNormal.z;

        float x1 = x / 16f, x2 = (x + w) / 16f;
        float y1 = y / 16f, y2 = (y + h) / 16f;
        float z1 = z / 16f, z2 = (z + d) / 16f;

        for (int i = 0; i < 4; i++) {
            int idx = cornerIndices[i];
            float px = (idx & 4) != 0 ? x2 : x1;
            float py = (idx & 2) != 0 ? y2 : y1;
            float pz = (idx & 1) != 0 ? z2 : z1;
            scratchPos.set(px, py, pz, 1.0f).mul(scratchBakeMat);
            face.positions[i][0] = scratchPos.x();
            face.positions[i][1] = scratchPos.y();
            face.positions[i][2] = scratchPos.z();
        }

        face.u[0] = u0; face.u[1] = u1; face.u[2] = u1; face.u[3] = u0;
        face.v[0] = v0; face.v[1] = v0; face.v[2] = v1; face.v[3] = v1;
        cube.faces.add(face);
    }

    private RawYsmModel.RawAnimationFile parseAnimations(byte[] data) {
        JsonObject root = parseJsonObject(data);
        RawYsmModel.RawAnimationFile raf = new RawYsmModel.RawAnimationFile();

        if (root.has("animations")) {
            JsonObject anims = root.getAsJsonObject("animations");
            for (Map.Entry<String, JsonElement> entry : anims.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject aObj = entry.getValue().getAsJsonObject();
                RawYsmModel.RawAnimation anim = new RawYsmModel.RawAnimation();
                anim.name = entry.getKey();
                anim.length = (float) getDouble(aObj, "animation_length", Float.POSITIVE_INFINITY);

                if (aObj.has("loop")) {
                    String loopStr = aObj.get("loop").getAsString();
                    if ("true".equals(loopStr)) anim.loopMode = 1;
                    else if ("hold_on_last_frame".equals(loopStr)) anim.loopMode = 3;
                    else anim.loopMode = 0;
                } else {
                    anim.loopMode = 2;
                }

                if (aObj.has("blend_weight")) {
                    JsonElement bw = aObj.get("blend_weight");
                    if (bw.isJsonPrimitive() && bw.getAsJsonPrimitive().isNumber()) {
                        anim.blendWeight = bw.getAsFloat();
                    } else {
                        anim.blendWeight = bw.getAsString();
                    }
                }

                if (aObj.has("bones") && aObj.get("bones").isJsonObject()) {
                    JsonObject bonesObj = aObj.getAsJsonObject("bones");
                    for (Map.Entry<String, JsonElement> bEntry : bonesObj.entrySet()) {
                        if (!bEntry.getValue().isJsonObject()) continue;
                        JsonObject bObj = bEntry.getValue().getAsJsonObject();
                        RawYsmModel.RawBoneAnimation ba = new RawYsmModel.RawBoneAnimation();
                        ba.boneName = bEntry.getKey();

                        parseChannelToKeyframes(bObj, "rotation", ba.rotation);
                        parseChannelToKeyframes(bObj, "position", ba.position);
                        parseChannelToKeyframes(bObj, "scale", ba.scale);

                        anim.boneAnimations.add(ba);
                    }
                }

                if (aObj.has("timeline") && aObj.get("timeline").isJsonObject()) {
                    JsonObject tlObj = aObj.getAsJsonObject("timeline");
                    for (Map.Entry<String, JsonElement> tlEntry : tlObj.entrySet()) {
                        RawYsmModel.RawTimelineEvent tle = new RawYsmModel.RawTimelineEvent();
                        tle.timestamp = Float.parseFloat(tlEntry.getKey());
                        JsonElement val = tlEntry.getValue();
                        if (val.isJsonArray()) {
                            for (JsonElement e : val.getAsJsonArray()) tle.events.add(e.getAsString());
                        } else {
                            tle.events.add(val.getAsString());
                        }
                        anim.timelineEvents.add(tle);
                    }
                }

                if (aObj.has("sound_effects") && aObj.get("sound_effects").isJsonObject()) {
                    JsonObject sfxObj = aObj.getAsJsonObject("sound_effects");
                    for (Map.Entry<String, JsonElement> sfxEntry : sfxObj.entrySet()) {
                        RawYsmModel.RawSoundEffect sfx = new RawYsmModel.RawSoundEffect();
                        sfx.timestamp = Float.parseFloat(sfxEntry.getKey());
                        sfx.effectName = getStr(sfxEntry.getValue().getAsJsonObject(), "effect", "");
                        anim.soundEffects.add(sfx);
                    }
                }

                raf.animations.put(anim.name, anim);
            }
        }
        return raf;
    }

    private void parseChannelToKeyframes(JsonObject bObj, String channel, List<RawYsmModel.RawKeyframe> targetList) {
        JsonElement cElem = bObj.get(channel);
        if (cElem == null) return;

        if (!cElem.isJsonObject()) {
            RawYsmModel.RawKeyframe kf = new RawYsmModel.RawKeyframe();
            kf.timestamp = 0.0f;
            kf.interpolationMode = 0; // linear
            kf.hasPreData = false;
            jsonElementToMolangArray(cElem, kf.postData);
            targetList.add(kf);
            return;
        }

        JsonObject kfsObj = cElem.getAsJsonObject();
        List<Map.Entry<String, JsonElement>> sorted = new ArrayList<>(kfsObj.entrySet());
        sorted.sort(ENTRY_BY_NUMERIC_KEY);

        for (Map.Entry<String, JsonElement> entry : sorted) {
            RawYsmModel.RawKeyframe kf = new RawYsmModel.RawKeyframe();
            kf.timestamp = Float.parseFloat(entry.getKey());
            kf.interpolationMode = 0;

            JsonElement valElem = entry.getValue();
            if (valElem.isJsonObject()) {
                JsonObject obj = valElem.getAsJsonObject();
                JsonElement lerpMode = obj.get("lerp_mode");
                if (lerpMode != null) {
                    String lm = lerpMode.getAsString();
                    if ("catmullrom".equals(lm)) kf.interpolationMode = 2;
                    else if ("step".equals(lm)) kf.interpolationMode = 1;
                } else {
                    kf.interpolationMode = 1;
                }

                JsonElement pre = obj.get("pre");
                JsonElement post = obj.get("post");
                if (pre != null && post != null) {
                    kf.hasPreData = true;
                    jsonElementToMolangArray(pre, kf.preData);
                    jsonElementToMolangArray(post, kf.postData);
                } else {
                    kf.hasPreData = false;
                    JsonElement src = post != null ? post : pre != null ? pre : obj;
                    jsonElementToMolangArray(src, kf.postData);
                }
            } else {
                kf.hasPreData = false;
                jsonElementToMolangArray(valElem, kf.postData);
            }
            targetList.add(kf);
        }
    }

    private static void jsonElementToMolangArray(JsonElement elem, Object[] arr) {
        arr[0] = FLOAT_ZERO;
        arr[1] = FLOAT_ZERO;
        arr[2] = FLOAT_ZERO;
        if (elem == null || elem.isJsonNull()) return;

        if (elem.isJsonArray()) {
            JsonArray jArr = elem.getAsJsonArray();
            int n = Math.min(3, jArr.size());
            for (int i = 0; i < n; i++) {
                JsonElement e = jArr.get(i);
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) arr[i] = e.getAsFloat();
                else arr[i] = e.getAsString();
            }
        } else {
            Object val;
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) val = elem.getAsFloat();
            else val = elem.getAsString();
            arr[0] = val; arr[1] = val; arr[2] = val;
        }
    }

    private void parseAnimationControllers(byte[] data, Map<String, RawYsmModel.RawAnimationController> targetMap) {
        JsonObject root = parseJsonObject(data);

        if (!root.has("animation_controllers")) return;
        JsonObject acs = root.getAsJsonObject("animation_controllers");

        for (Map.Entry<String, JsonElement> acEntry : acs.entrySet()) {
            if (!acEntry.getValue().isJsonObject()) continue;
            JsonObject acObj = acEntry.getValue().getAsJsonObject();

            RawYsmModel.RawAnimationController ac = new RawYsmModel.RawAnimationController();
            ac.animationName = acEntry.getKey();
            ac.initialState = getStr(acObj, "initial_state", "default");

            if (acObj.has("states") && acObj.get("states").isJsonObject()) {
                JsonObject statesObj = acObj.getAsJsonObject("states");
                for (Map.Entry<String, JsonElement> sEntry : statesObj.entrySet()) {
                    if (!sEntry.getValue().isJsonObject()) continue;
                    JsonObject sObj = sEntry.getValue().getAsJsonObject();

                    RawYsmModel.RawControllerState state = new RawYsmModel.RawControllerState();
                    state.name = sEntry.getKey();

                    if (sObj.has("animations") && sObj.get("animations").isJsonArray()) {
                        for (JsonElement ae : sObj.getAsJsonArray("animations")) {
                            if (ae.isJsonPrimitive()) {
                                state.animations.put(ae.getAsString(), "");
                            } else if (ae.isJsonObject()) {
                                for (Map.Entry<String, JsonElement> objEntry : ae.getAsJsonObject().entrySet()) {
                                    state.animations.put(objEntry.getKey(), objEntry.getValue().getAsString());
                                }
                            }
                        }
                    }

                    if (sObj.has("transitions") && sObj.get("transitions").isJsonArray()) {
                        for (JsonElement te : sObj.getAsJsonArray("transitions")) {
                            if (te.isJsonObject()) {
                                for (Map.Entry<String, JsonElement> objEntry : te.getAsJsonObject().entrySet()) {
                                    state.transitions.put(objEntry.getKey(), objEntry.getValue().getAsString());
                                }
                            }
                        }
                    }

                    if (sObj.has("on_entry") && sObj.get("on_entry").isJsonArray()) {
                        for (JsonElement oe : sObj.getAsJsonArray("on_entry")) state.onEntry.add(oe.getAsString());
                    }

                    if (sObj.has("on_exit") && sObj.get("on_exit").isJsonArray()) {
                        for (JsonElement oe : sObj.getAsJsonArray("on_exit")) state.onExit.add(oe.getAsString());
                    }

                    if (sObj.has("sound_effects") && sObj.get("sound_effects").isJsonArray()) {
                        for (JsonElement se : sObj.getAsJsonArray("sound_effects")) {
                            if (se.isJsonObject()) state.soundEffects.add(getStr(se.getAsJsonObject(), "effect", ""));
                            else if (se.isJsonPrimitive()) state.soundEffects.add(se.getAsString());
                        }
                    }

                    if (sObj.has("blend_transition")) {
                        JsonElement btElem = sObj.get("blend_transition");
                        if (btElem.isJsonPrimitive() && btElem.getAsJsonPrimitive().isNumber()) {
                            state.blendTransitionValue = btElem.getAsFloat();
                        } else if (btElem.isJsonObject()) {
                            for (Map.Entry<String, JsonElement> btEntry : btElem.getAsJsonObject().entrySet()) {
                                state.blendTransitions.put(Float.parseFloat(btEntry.getKey()), btEntry.getValue().getAsFloat());
                            }
                        }
                    }

                    ac.states.add(state);
                }
            }
            targetMap.put(ac.animationName, ac);
        }
    }

    private void parseGlobalResources() {
        if (inMemoryFiles != null) {
            for (Map.Entry<String, byte[]> entry : inMemoryFiles.entrySet()) {
                String path = entry.getKey();
                if (!readFilesMd5Map.containsKey(path)) {
                    readFilesMd5Map.put(path, md5Hex(entry.getValue()));
                }
                if (isGlobalResourcePath(path)) {
                    processGlobalResourceFile(path, entry.getValue());
                }
            }
        } else {
            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    String relativePath = rootPath.relativize(path).toString().replace('\\', '/');
                    boolean isGlobal = isGlobalResourcePath(relativePath);
                    boolean alreadyHashed = readFilesMd5Map.containsKey(relativePath);

                    if (isGlobal) {
                        byte[] data = readResource(relativePath);
                        if (data != null) {
                            processGlobalResourceFile(relativePath, data);
                        }
                    } else if (!alreadyHashed) {
                        try {
                            readFilesMd5Map.put(relativePath, streamingMd5Hex(path));
                        } catch (IOException e) {
                            System.err.println("[YSM] Warning: Failed to hash resource: " + relativePath);
                        }
                    }
                });
            } catch (IOException e) {
                System.err.println("[YSM] Warning: Failed to scan global resources. " + e.getMessage());
            }
        }
    }

    private static boolean isGlobalResourcePath(String relativePath) {
        return relativePath.startsWith("sounds/")
                || relativePath.endsWith(".ogg")
                || (relativePath.startsWith("lang/") && relativePath.endsWith(".json"))
                || (relativePath.startsWith("functions/") && relativePath.endsWith(".molang"));
    }

    private static String streamingMd5Hex(Path file) throws IOException {
        MessageDigest digest = md5Digest();
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream dis = new DigestInputStream(in, digest)) {
            byte[] scratch = new byte[16 * 1024];
            while (dis.read(scratch) != -1) {
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void processGlobalResourceFile(String relativePath, byte[] data) {
        if (relativePath.startsWith("sounds/") || relativePath.endsWith(".ogg")) {
            String soundName = extractFileName(relativePath);
            String hash = sha256Hex(data);
            model.soundFiles.put(soundName, new RawYsmModel.RawDataFile(hash, data));
        }
        else if (relativePath.startsWith("lang/") && relativePath.endsWith(".json")) {
            String locale = relativePath.substring("lang/".length(), relativePath.length() - 5);
            try {
                String hash = sha256Hex(data);
                JsonObject langJson = parseJsonObject(data);
                Map<String, String> langMap = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> langEntry : langJson.entrySet()) {
                    if (langEntry.getValue().isJsonPrimitive()) {
                        langMap.put(langEntry.getKey(), langEntry.getValue().getAsString());
                    }
                }
                model.languageFiles.put(locale, new RawYsmModel.RawLanguageFile(hash, langMap));
            } catch (Exception ignored) {}
        }
        else if (relativePath.startsWith("functions/") && relativePath.endsWith(".molang")) {
            String fnName = extractFileName(relativePath);
            String hash = sha256Hex(data);
            model.functionFiles.put(fnName, new RawYsmModel.RawDataFile(hash, data));
        }
    }


    private record ImageMeta(int width, int height, int format) {}


    private static ImageMeta parseImageMeta(byte[] data, String path) {
        if (data == null || data.length < 8) {
            throw new RuntimeException("Invalid image data. File too small: " + path);
        }

        int format = detectFormat(data);
        if (format == 0) {
            throw new RuntimeException("Unsupported image format for: " + path);
        }

        if (format == 2 && data.length >= 24) {
            int w = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16) | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
            int h = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16) | ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
            return new ImageMeta(w, h, format);
        }

        if (format == 1) {
            int[] dims = readBmpDimensions(data);
            if (dims != null) return new ImageMeta(dims[0], dims[1], format);
        }

        if (format == 3) {
            int[] dims = readJpegDimensions(data);
            if (dims != null) return new ImageMeta(dims[0], dims[1], format);
        }

        if (format == 1 || format == 3) {
            try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis);
                        return new ImageMeta(reader.getWidth(0), reader.getHeight(0), format);
                    } finally {
                        reader.dispose();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        try {
            BufferedImage img = null;
            switch (format) {
                case 1, 3 -> img = ImageIO.read(new ByteArrayInputStream(data));
                case 4 -> img = new WebpDecoder().read(data);
                case 5 -> img = new AvifDecoder().read(data);
            }
            if (img != null) {
                return new ImageMeta(img.getWidth(), img.getHeight(), format);
            }
            throw new RuntimeException("Failed to decode image dimensions for: " + path);
        } catch (Exception e) {
            throw new RuntimeException("Error processing image: " + path, e);
        }
    }

    private static int[] readBmpDimensions(byte[] data) {
        if (data.length < 26) return null;
        int w = (data[18] & 0xFF)
                | ((data[19] & 0xFF) << 8)
                | ((data[20] & 0xFF) << 16)
                | ((data[21] & 0xFF) << 24);
        int h = (data[22] & 0xFF)
                | ((data[23] & 0xFF) << 8)
                | ((data[24] & 0xFF) << 16)
                | ((data[25] & 0xFF) << 24);
        if (w <= 0 || h == 0) return null;
        return new int[]{w, Math.abs(h)};
    }

    private static int[] readJpegDimensions(byte[] data) {
        if (data.length < 4 || data[0] != (byte) 0xFF || data[1] != (byte) 0xD8) return null;
        int pos = 2;
        while (pos + 8 < data.length) {
            if (data[pos] != (byte) 0xFF) return null;
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xFF) { pos++; continue; }
            if (marker >= 0xD0 && marker <= 0xD9) { pos += 2; continue; }
            boolean isSof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (isSof) {
                int height = ((data[pos + 5] & 0xFF) << 8) | (data[pos + 6] & 0xFF);
                int width = ((data[pos + 7] & 0xFF) << 8) | (data[pos + 8] & 0xFF);
                if (width > 0 && height > 0) return new int[]{width, height};
                return null;
            }
            int segLen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (segLen < 2) return null;
            pos += 2 + segLen;
        }
        return null;
    }

    public static int detectFormat(byte[] data) {
        if (data.length >= 2 && data[0] == 0x42 && data[1] == 0x4D) return 1; // 'BM'
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) return 2; // PNG
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return 3; // JPEG
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return 4; // WEBP RIFF...WEBP
        if (data.length >= 12 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') return 5; // AVIF ftyp
        return 0;
    }

    public static int getAnimTypeFromKey(String key) {
        return switch (key) {
            case "main" -> 1;
            case "arm" -> 2;
            case "extra" -> 3;
            case "tac" -> 4;
            case "arrow" -> 5;
            case "carryon" -> 6;
            case "parcool" -> 7;
            case "swem" -> 8;
            case "slashblade" -> 9;
            case "tlm" -> 10;
            case "fp.arm", "fp_arm" -> 11;
            case "immersive_melodies" -> 12;
            case "irons_spell_books" -> 13;
            default -> 0;
        };
    }

    public static String getAnimKeyFromType(int type) {
        return switch (type) {
            case 1 -> "main";
            case 2 -> "arm";
            case 3 -> "extra";
            case 4 -> "tac";
            case 5 -> "arrow";
            case 6 -> "carryon";
            case 7 -> "parcool";
            case 8 -> "swem";
            case 9 -> "slashblade";
            case 10 -> "tlm";
            case 11 -> "fp_arm";
            case 12 -> "immersive_melodies";
            case 13 -> "irons_spell_books";
            default -> "unknown";
        };
    }

    private static String getStr(JsonObject obj, String key, String def) {
        JsonElement e = obj.get(key);
        return e != null ? e.getAsString() : def;
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        JsonElement e = obj.get(key);
        return e != null ? e.getAsBoolean() : def;
    }

    private static double getDouble(JsonObject obj, String key, double def) {
        JsonElement e = obj.get(key);
        return e != null ? e.getAsDouble() : def;
    }

    private static float readFloat(JsonArray arr, int idx, float def) {
        if (arr == null || idx >= arr.size()) return def;
        return arr.get(idx).getAsFloat();
    }

    private static String extractFileName(String fullPath) {
        String name = fullPath;
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) name = name.substring(lastSlash + 1);
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx >= 0) name = name.substring(0, dotIdx);
        return name;
    }

    private String calculateFinalFolderHash() {
        try {
            MessageDigest digest = md5Digest();
            byte[] hexScratch = new byte[64];
            for (Map.Entry<String, String> entry : readFilesMd5Map.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                String val = entry.getValue();
                int len = val.length();
                if (len > hexScratch.length) hexScratch = new byte[len];
                for (int i = 0; i < len; i++) hexScratch[i] = (byte) val.charAt(i);
                digest.update(hexScratch, 0, len);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    public String getFolderHash() {
        return finalFolderHash;
    }

    private void parseLegacyFormat() {
        byte[] mainData = readResource("main.json");
        byte[] armData = readResource("arm.json");

        if (mainData == null) {
            throw new RuntimeException("Legacy model missing main.json");
        } else if (armData == null) {
            throw new RuntimeException("Legacy model missing arm.json");
        }

        List<String> pngFiles = new ArrayList<>();
        if (inMemoryFiles != null) {
            for (String pathKey : inMemoryFiles.keySet()) {
                if (pathKey.endsWith(".png") && !pathKey.contains("/")) {
                    pngFiles.add(pathKey);
                }
            }
        } else {
            try (Stream<Path> stream = Files.list(rootPath)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    String fileName = path.getFileName().toString();
                    if (fileName.endsWith(".png")) {
                        pngFiles.add(fileName);
                    }
                });
            } catch (IOException e) { e.printStackTrace(); }
        }

        boolean hasMainTexture = false;
        for (String texName : pngFiles) {
            if (!texName.equals("arrow.png")) {
                hasMainTexture = true;
                break;
            }
        }

        if (!hasMainTexture) {
            throw new RuntimeException("Legacy model requires at least one texture.");
        }

        byte[] arrowData = readResource("arrow.json");
        if (arrowData != null && !pngFiles.contains("arrow.png")) {
            throw new RuntimeException("arrow.json is present but arrow.png is missing.");
        }

        // 这个可能不存在
        byte[] infoData = readResource("info.json");
        if (infoData != null) {
            try {
                parseLegacyMetadata(parseJsonObject(infoData), true);
            } catch (Exception e) {
                System.err.println("Failed to parse info.json");
                e.printStackTrace();
            }
        }

        model.mainEntity.mainModel = parseGeometry(mainData, 1);
        model.mainEntity.armModel = parseGeometry(armData, 2);

        for (String texName : pngFiles) {
            if (texName.equals("arrow.png")) continue;
            byte[] texData = readResource(texName);
            if (texData != null) {
                ImageMeta meta = parseImageMeta(texData, texName);
                RawYsmModel.RawTexture rt = new RawYsmModel.RawTexture();
                rt.hash = sha256Hex(texData);
                rt.width = meta.width();
                rt.height = meta.height();
                rt.imageFormat = meta.format();
                rt.name = extractFileName(texName);
                rt.data = texData;
                rt.unknownFlag = 1;
                model.mainEntity.textures.put(rt.name, rt);
            }
        }

        if (!model.mainEntity.textures.isEmpty()) {
            model.properties.defaultTexture = model.mainEntity.textures.keySet().iterator().next();
        }

        String[] animFiles = {"main.animation.json", "arm.animation.json", "extra.animation.json", "tac.animation.json", "carryon.animation.json", "slashblade.animation.json", "tlm.animation.json"};
        for (String fileName : animFiles) {
            byte[] animData = readResource(fileName);
            if (animData != null) {
                RawYsmModel.RawAnimationFile raf = parseAnimations(animData);
                raf.fileHash = sha256Hex(animData);

                String animKey = fileName.substring(0, fileName.length() - ".animation.json".length());
                raf.animType = getAnimTypeFromKey(animKey);
                model.mainEntity.animationFiles.put(animKey, raf);
            }
        }

        // 箭矢
        if (arrowData != null) {
            RawYsmModel.RawSubEntity arrowSub = new RawYsmModel.RawSubEntity();
            arrowSub.identifier = "arrow";
            arrowSub.model = parseGeometry(arrowData, 3);

            byte[] arrowTexData = readResource("arrow.png");
            if (arrowTexData != null) {
                ImageMeta meta = parseImageMeta(arrowTexData, "arrow.png");
                RawYsmModel.RawTexture rt = new RawYsmModel.RawTexture();
                rt.hash = sha256Hex(arrowTexData);
                rt.width = meta.width();
                rt.height = meta.height();
                rt.imageFormat = meta.format();
                rt.name = "arrow";
                rt.data = arrowTexData;
                rt.unknownFlag = 1;
                arrowSub.textures.put(rt.name, rt);
            }

            byte[] arrowAnimData = readResource("arrow.animation.json");
            if (arrowAnimData != null) {
                RawYsmModel.RawAnimationFile raf = parseAnimations(arrowAnimData);
                raf.fileHash = sha256Hex(arrowAnimData);
                raf.animType = getAnimTypeFromKey("arrow");
                arrowSub.animationFiles.put("sub_anim", raf);
            }

            model.projectiles.put("arrow", arrowSub);
        }
    }

    public static boolean isModelFolder(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        if (Files.isRegularFile(dir.resolve("ysm.json"))) {
            return true;
        }
        return Files.isRegularFile(dir.resolve("main.json")) && Files.isRegularFile(dir.resolve("arm.json"));
    }

    private void parseLegacyMetadata(JsonObject infoObj, boolean overwrite) {
        if (infoObj == null) return;
        if (infoObj.has("name") && (overwrite || model.metadata.name.isEmpty())) {
            model.metadata.name = getStr(infoObj, "name", "");
        }
        if (infoObj.has("tips") && (overwrite || model.metadata.tips.isEmpty())) {
            model.metadata.tips = getStr(infoObj, "tips", "");
        }
        if (infoObj.has("license") && (overwrite || model.metadata.licenseDescription.isEmpty())) {
            model.metadata.licenseDescription = getStr(infoObj, "license", "");
        }
        if (infoObj.has("free")) {
            if (overwrite || !model.properties.isFree) {
                model.properties.isFree = getBool(infoObj, "free", false);
            }
        }

        if (infoObj.has("authors") && infoObj.get("authors").isJsonArray()) {
            if (overwrite || model.metadata.authors.isEmpty()) {
                model.metadata.authors.clear();
                for (JsonElement e : infoObj.getAsJsonArray("authors")) {
                    RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
                    author.name = e.getAsString();
                    model.metadata.authors.add(author);
                }
            }
        }

        if (infoObj.has("extra_animation_names") && infoObj.get("extra_animation_names").isJsonArray()) {
            if (overwrite || model.properties.extraAnimations.isEmpty()) {
                model.properties.extraAnimations.clear();
                JsonArray extras = infoObj.getAsJsonArray("extra_animation_names");
                for (int i = 0; i < extras.size(); i++) {
                    String extraName = extras.get(i).getAsString();
                    model.properties.extraAnimations.put("extra" + i, extraName);
                }
            }
        }
    }
}