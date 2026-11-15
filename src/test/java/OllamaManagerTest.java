import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OllamaManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void currentChecksumFormatWithRelativePathIsAccepted() throws Exception {
        String hash = "84db4f756ddc9e091142a85cdc0f7ae5db68bc9b3e075f114388b326f8ed912d";
        assertEquals(hash, OllamaManager.parseInstallerChecksum(
                hash + "  ./OllamaSetup.exe\n"));
    }

    @Test
    void checksumEntryIsMandatory() {
        assertThrows(IOException.class, () ->
                OllamaManager.parseInstallerChecksum("abc  ./other.zip\n"));
    }

    @Test
    void releaseTagIsReadFromGithubMetadata() throws Exception {
        assertEquals("v0.40.16", OllamaManager.parseReleaseTag(
                "{\"tag_name\":\"v0.40.16\",\"draft\":false}"));
    }

    @Test
    void lightweightQwenIsTheSingleRecommendation() {
        OllamaManager manager = new OllamaManager(temporaryDirectory);
        var recommended = manager.compatibleModels().stream()
                .filter(OllamaManager.ModelOption::recommended).toList();
        assertEquals(1, recommended.size());
        assertEquals("qwen3:0.6b", recommended.get(0).name());
        assertTrue(recommended.get(0).approximateBytes() < 600_000_000L);
    }
}
