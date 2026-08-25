package com.cuzz.rookieCrates.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelAssetsContractTest {

    private static final Path BLUEPRINTS = Path.of("model-assets", "ModelEngine", "blueprints");

    @Test
    void modelMetadataMatchesDeploymentIds() throws IOException {
        for (String modelId : List.of(
                "default_crate",
                "loot_white",
                "loot_pink",
                "loot_blue",
                "loot_yellow"
        )) {
            JsonObject model = read(modelId);
            assertEquals(modelId, model.get("name").getAsString());
            assertEquals(modelId, model.get("model_identifier").getAsString());
        }
    }

    @Test
    void defaultCrateProvidesConfiguredAnimations() throws IOException {
        JsonObject model = read("default_crate");
        assertTrue(hasNamedEntry(model.getAsJsonArray("animations"), "idle"));
        assertTrue(hasNamedEntry(model.getAsJsonArray("animations"), "open2"));
    }

    @Test
    void lootModelsProvideIdleAnimationAndItemBone() throws IOException {
        for (String modelId : List.of("loot_white", "loot_pink", "loot_blue", "loot_yellow")) {
            JsonObject model = read(modelId);
            assertTrue(hasNamedEntry(model.getAsJsonArray("animations"), "idle"), modelId + " idle");
            assertTrue(hasNamedEntry(model.getAsJsonArray("outliner"), "item"), modelId + " item bone");
        }
    }

    @Test
    void embeddedTexturesAreExportedIntoTheModelEngineResourcePack() throws IOException {
        for (String modelId : List.of(
                "default_crate",
                "loot_white",
                "loot_pink",
                "loot_blue",
                "loot_yellow"
        )) {
            JsonArray textures = read(modelId).getAsJsonArray("textures");
            assertTrue(textures != null && !textures.isEmpty(), modelId + " textures");
            for (var element : textures) {
                JsonObject texture = element.getAsJsonObject();
                assertTrue(texture.get("internal").getAsBoolean(), modelId + " internal texture");
                assertTrue(texture.get("source").getAsString().startsWith("data:image/png;base64,"),
                        modelId + " embedded texture source");
                assertTrue(!texture.has("namespace") || texture.get("namespace").getAsString().isBlank(),
                        modelId + " texture must be managed by ModelEngine");
            }
        }
    }

    private static JsonObject read(String modelId) throws IOException {
        Path file = BLUEPRINTS.resolve(modelId + ".bbmodel");
        assertTrue(Files.isRegularFile(file), () -> "Missing model asset: " + file);
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static boolean hasNamedEntry(JsonArray entries, String expectedName) {
        if (entries == null) {
            return false;
        }
        for (var element : entries) {
            if (element.isJsonObject()
                    && element.getAsJsonObject().has("name")
                    && expectedName.equals(element.getAsJsonObject().get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }
}
