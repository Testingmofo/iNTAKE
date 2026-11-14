import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MusicVibeSorterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void identicalHashIsQuarantinedBeforeAudioAnalysis() throws Exception {
        Path library = Files.createDirectories(temporaryDirectory.resolve("Audio"));
        Path existingDirectory = Files.createDirectories(library.resolve("MVSS").resolve("Chill"));
        byte[] audio = "identical fake audio bytes".getBytes(StandardCharsets.UTF_8);
        Path existing = Files.write(existingDirectory.resolve("First name.mp3"), audio);
        Path incoming = Files.write(library.resolve("Completely different name.mp3"), audio);

        MusicVibeSorter.SortResult result = new MusicVibeSorter("missing-ffmpeg")
                .sort(incoming, library);

        assertTrue(result.isDuplicate());
        assertEquals(existing.toAbsolutePath(),
                result.duplicate().orElseThrow().existingTrack());
        assertEquals("identical SHA-256 audio file",
                result.duplicate().orElseThrow().evidence());
        assertTrue(result.sortedPath().startsWith(library.resolve("MVSS").resolve("Duplicates")));
        assertTrue(Files.isRegularFile(result.sortedPath()));
        assertFalse(Files.exists(incoming));
        assertTrue(result.features().isEmpty());
    }

    @Test
    void normalizedTitleMatchQuarantinesDifferentEncoding() throws Exception {
        Path library = Files.createDirectories(temporaryDirectory.resolve("Audio"));
        Path existingDirectory = Files.createDirectories(library.resolve("MVSS").resolve("Energetic"));
        Path existing = Files.writeString(existingDirectory.resolve(
                "Example Artist - Signal (Official Audio).mp3"), "older encoding");
        Path incoming = Files.writeString(library.resolve(
                "Example Artist - Signal [PqpCRSOUuIE].opus"), "new encoding with other bytes");

        MusicVibeSorter.SortResult result = new MusicVibeSorter("missing-ffmpeg")
                .sort(incoming, library);

        assertTrue(result.isDuplicate());
        assertEquals(existing.toAbsolutePath(),
                result.duplicate().orElseThrow().existingTrack());
        assertEquals("matching normalized track title",
                result.duplicate().orElseThrow().evidence());
    }

    @Test
    void meaningfulVersionMarkersPreventFalseDuplicate() {
        assertFalse(MusicVibeSorter.titlesIndicateDuplicate(
                "Example Artist - Signal.mp3", "Example Artist - Signal (Live).mp3"));
        assertFalse(MusicVibeSorter.titlesIndicateDuplicate(
                "Example Artist - Signal (Remix).mp3",
                "Example Artist - Signal (Acoustic).mp3"));
    }

    @Test
    void minorTitleTypoIsRecognizedAsSimilar() {
        assertTrue(MusicVibeSorter.titlesIndicateDuplicate(
                "Example Artist - Electricity (Official Audio).mp3",
                "Example Artist - Electricty.mp3"));
    }
}
