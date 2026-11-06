import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVSS (Music Vibes Storage Sorter) classifies an audio file locally and moves
 * it into an MVSS/vibe directory below the configured audio library.
 *
 * <p>The primary classifier uses acoustic features extracted from decoded PCM,
 * so it works without a network connection or model download. When a small
 * Ollama language model is already available on localhost, its constrained
 * classification can refine the deterministic result.</p>
 */
public final class MusicVibeSorter {
    private static final int SAMPLE_RATE = 8_000;
    private static final int MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 90;
    private static final URI OLLAMA_TAGS = URI.create("http://127.0.0.1:11434/api/tags");
    private static final URI OLLAMA_GENERATE = URI.create("http://127.0.0.1:11434/api/generate");
    private static final Pattern MODEL_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern OLLAMA_RESPONSE = Pattern.compile(
            "\\\"response\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final List<Topic> DEFAULT_TOPICS = List.of(
            new Topic("Energetic", List.of("energy", "workout", "dance", "hardstyle", "rave", "fast")),
            new Topic("Chill", List.of("chill", "lofi", "sleep", "calm", "relax", "acoustic")),
            new Topic("Dark", List.of("dark", "night", "doom", "death", "shadow", "villain", "goth")),
            new Topic("Bright", List.of("happy", "sun", "summer", "smile", "party", "love", "bright")),
            new Topic("Melancholic", List.of("sad", "cry", "lonely", "goodbye", "broken", "rain", "melancholy")),
            new Topic("Atmospheric", List.of("ambient", "dream", "space", "ethereal", "cinematic", "atmospheric")));

    private final String ffmpegExecutable;
    private final HttpClient httpClient;

    public MusicVibeSorter(String ffmpegExecutable) {
        this.ffmpegExecutable = Objects.requireNonNull(ffmpegExecutable);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(650))
                .build();
    }

    public static List<Topic> defaultTopics() {
        return DEFAULT_TOPICS;
    }

    public SortResult sort(Path audioFile, Path audioLibrary) throws IOException, InterruptedException {
        return sort(audioFile, audioLibrary, DEFAULT_TOPICS);
    }

    public SortResult sort(Path audioFile, Path audioLibrary, List<Topic> configuredTopics)
            throws IOException, InterruptedException {
        Path source = requireSortableFile(audioFile, audioLibrary);
        List<Topic> topics = normalizeTopics(configuredTopics);
        AudioFeatures features = analyze(source);
        Archetype archetype = classifyArchetype(source.getFileName().toString(), features);
        Topic heuristic = classifyConfiguredTopic(
                source.getFileName().toString(), archetype, topics);
        Optional<Topic> modelTopic = classifyWithLocalModel(
                source.getFileName().toString(), features, topics);
        Topic selected = modelTopic.orElse(heuristic);

        Path root = audioLibrary.toAbsolutePath().normalize();
        Path destinationDirectory = root.resolve("MVSS").resolve(selected.name());
        Files.createDirectories(destinationDirectory);
        Path destination = uniqueDestination(destinationDirectory.resolve(source.getFileName()));
        move(source, destination);
        return new SortResult(source, destination, selected.name(), features,
                modelTopic.isPresent() ? "Ollama local model" : "MVSS acoustic engine");
    }

    private AudioFeatures analyze(Path source) throws IOException, InterruptedException {
        List<String> command = List.of(
                ffmpegExecutable, "-v", "error", "-ss", "10", "-i", source.toString(),
                "-t", "90", "-vn", "-ac", "1", "-ar", Integer.toString(SAMPLE_RATE),
                "-f", "s16le", "pipe:1");
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        byte[] pcm;
        try (var input = process.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(MAX_AUDIO_BYTES)) {
            byte[] buffer = new byte[16_384];
            int total = 0;
            int read;
            while (total < MAX_AUDIO_BYTES
                    && (read = input.read(buffer, 0, Math.min(buffer.length, MAX_AUDIO_BYTES - total))) >= 0) {
                output.write(buffer, 0, read);
                total += read;
            }
            pcm = output.toByteArray();
        }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("FFmpeg audio analysis timed out");
        }
        if (process.exitValue() != 0 || pcm.length < SAMPLE_RATE * 2) {
            throw new IOException("FFmpeg could not decode enough audio for MVSS analysis");
        }
        return calculateFeatures(pcm);
    }

    private static AudioFeatures calculateFeatures(byte[] pcm) {
        int sampleCount = pcm.length / 2;
        double[] samples = new double[sampleCount];
        double squareSum = 0.0;
        double differenceSquareSum = 0.0;
        int zeroCrossings = 0;
        double previous = 0.0;
        for (int index = 0; index < sampleCount; index++) {
            int low = pcm[index * 2] & 0xff;
            int high = pcm[index * 2 + 1];
            double sample = (short) ((high << 8) | low) / 32768.0;
            samples[index] = sample;
            squareSum += sample * sample;
            if (index > 0) {
                double difference = sample - previous;
                differenceSquareSum += difference * difference;
                if ((sample >= 0.0) != (previous >= 0.0)) {
                    zeroCrossings++;
                }
            }
            previous = sample;
        }

        double rms = Math.sqrt(squareSum / sampleCount);
        double zeroCrossingRate = zeroCrossings / (double) sampleCount;
        double brightness = clamp(Math.sqrt(differenceSquareSum / Math.max(1, sampleCount - 1))
                / Math.max(0.0001, rms * 2.0));

        int windowSize = SAMPLE_RATE / 20;
        int windows = sampleCount / windowSize;
        double[] envelope = new double[windows];
        double envelopeMean = 0.0;
        for (int window = 0; window < windows; window++) {
            double sum = 0.0;
            int offset = window * windowSize;
            for (int index = 0; index < windowSize; index++) {
                double value = samples[offset + index];
                sum += value * value;
            }
            envelope[window] = Math.sqrt(sum / windowSize);
            envelopeMean += envelope[window];
        }
        envelopeMean /= Math.max(1, windows);
        double envelopeVariance = 0.0;
        for (double value : envelope) {
            double delta = value - envelopeMean;
            envelopeVariance += delta * delta;
        }
        double dynamics = clamp(Math.sqrt(envelopeVariance / Math.max(1, windows))
                / Math.max(0.0001, envelopeMean));
        double bpm = estimateTempo(envelope);
        return new AudioFeatures(bpm, clamp(rms * 4.0), brightness,
                clamp(zeroCrossingRate * 12.0), dynamics);
    }

    private static double estimateTempo(double[] envelope) {
        if (envelope.length < 80) {
            return 100.0;
        }
        double[] onset = new double[envelope.length];
        for (int index = 1; index < envelope.length; index++) {
            onset[index] = Math.max(0.0, envelope[index] - envelope[index - 1]);
        }
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestLag = 12;
        for (int lag = 7; lag <= 20; lag++) {
            double score = 0.0;
            for (int index = lag; index < onset.length; index++) {
                score += onset[index] * onset[index - lag];
            }
            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }
        return 1_200.0 / bestLag;
    }

    private static Archetype classifyArchetype(String fileName, AudioFeatures features) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (containsAny(name, "sad", "cry", "lonely", "goodbye", "broken", "rain", "melanchol")) {
            return Archetype.MELANCHOLIC;
        }
        if (containsAny(name, "dark", "night", "doom", "death", "shadow", "villain", "goth")) {
            return Archetype.DARK;
        }
        if (containsAny(name, "happy", "sun", "summer", "smile", "party", "love", "bright")) {
            return Archetype.BRIGHT;
        }
        if (containsAny(name, "ambient", "dream", "space", "ethereal", "cinematic", "atmos")) {
            return Archetype.ATMOSPHERIC;
        }
        if (containsAny(name, "chill", "lofi", "sleep", "calm", "relax", "acoustic")) {
            return Archetype.CHILL;
        }
        if (containsAny(name, "energetic", "energy", "workout", "dance", "hardstyle", "rave")) {
            return Archetype.ENERGETIC;
        }
        if (features.bpm() >= 128.0 || (features.energy() > 0.62 && features.brightness() > 0.42)) {
            return Archetype.ENERGETIC;
        }
        if (features.energy() < 0.28 && features.dynamics() > 0.50) {
            return Archetype.ATMOSPHERIC;
        }
        if (features.energy() < 0.42 && features.bpm() < 105.0) {
            return Archetype.CHILL;
        }
        if (features.brightness() < 0.27 && features.energy() > 0.42) {
            return Archetype.DARK;
        }
        if (features.brightness() > 0.58 && features.zeroCrossing() > 0.34) {
            return Archetype.BRIGHT;
        }
        return features.dynamics() > 0.62 ? Archetype.MELANCHOLIC : Archetype.CHILL;
    }

    private static Topic classifyConfiguredTopic(String fileName, Archetype archetype,
                                                 List<Topic> topics) {
        String normalizedFileName = fileName.toLowerCase(Locale.ROOT);
        Topic best = topics.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (Topic topic : topics) {
            int score = 0;
            for (String hint : topic.hints()) {
                if (normalizedFileName.contains(hint)) {
                    score += 100 + Math.min(20, hint.length());
                }
            }
            if (topicMatchesArchetype(topic, archetype)) {
                score += 30;
            }
            if (score > bestScore) {
                best = topic;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean topicMatchesArchetype(Topic topic, Archetype archetype) {
        String searchable = (topic.name() + " " + String.join(" ", topic.hints()))
                .toLowerCase(Locale.ROOT);
        return switch (archetype) {
            case ENERGETIC -> containsAny(searchable, "energetic", "energy", "workout", "dance", "rave", "fast");
            case CHILL -> containsAny(searchable, "chill", "lofi", "sleep", "calm", "relax", "acoustic");
            case DARK -> containsAny(searchable, "dark", "doom", "shadow", "villain", "goth", "night");
            case BRIGHT -> containsAny(searchable, "bright", "happy", "sun", "summer", "smile", "party");
            case MELANCHOLIC -> containsAny(searchable, "melanchol", "sad", "cry", "lonely", "broken", "rain");
            case ATMOSPHERIC -> containsAny(searchable, "atmos", "ambient", "dream", "space", "ethereal", "cinematic");
        };
    }

    private Optional<Topic> classifyWithLocalModel(String fileName, AudioFeatures features,
                                                   List<Topic> topics) {
        try {
            HttpRequest tagsRequest = HttpRequest.newBuilder(OLLAMA_TAGS)
                    .timeout(Duration.ofMillis(900)).GET().build();
            HttpResponse<String> tags = httpClient.send(tagsRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (tags.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            Optional<String> model = selectSmallModel(tags.body());
            if (model.isEmpty()) {
                return Optional.empty();
            }
            String topicChoices = topics.stream()
                    .map(topic -> topic.name() + (topic.hints().isEmpty() ? ""
                            : " (" + String.join(", ", topic.hints()) + ")"))
                    .reduce((left, right) -> left + "; " + right).orElseThrow();
            String prompt = "Classify this music into exactly one of these user-defined topics: "
                    + topicChoices + ". Reply with the topic name only. Filename: " + fileName
                    + ". Features: BPM=" + rounded(features.bpm())
                    + ", energy=" + rounded(features.energy())
                    + ", brightness=" + rounded(features.brightness())
                    + ", zero-crossing=" + rounded(features.zeroCrossing())
                    + ", dynamics=" + rounded(features.dynamics()) + ".";
            String body = "{\"model\":\"" + jsonEscape(model.get()) + "\",\"prompt\":\""
                    + jsonEscape(prompt) + "\",\"stream\":false,\"options\":{\"temperature\":0}}";
            HttpRequest request = HttpRequest.newBuilder(OLLAMA_GENERATE)
                    .timeout(Duration.ofSeconds(35))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            Matcher responseMatcher = OLLAMA_RESPONSE.matcher(response.body());
            if (!responseMatcher.find()) {
                return Optional.empty();
            }
            String answer = responseMatcher.group(1).replace("\\\"", "\"")
                    .replace("\\n", " ").trim();
            return topics.stream()
                    .filter(topic -> answer.equalsIgnoreCase(topic.name())
                            || answer.toLowerCase(Locale.ROOT).contains(topic.name().toLowerCase(Locale.ROOT)))
                    .max(Comparator.comparingInt(topic -> topic.name().length()));
        } catch (IOException | InterruptedException | RuntimeException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private static Optional<String> selectSmallModel(String tagsJson) {
        List<String> models = new ArrayList<>();
        Matcher matcher = MODEL_NAME.matcher(tagsJson);
        while (matcher.find()) {
            String name = matcher.group(1);
            String lower = name.toLowerCase(Locale.ROOT);
            if (containsAny(lower, "0.5b", "1b", "1.5b", "2b", "3b", "mini")) {
                models.add(name);
            }
        }
        return models.stream().min(Comparator.comparingInt(String::length));
    }

    private static List<Topic> normalizeTopics(List<Topic> topics) {
        if (topics == null || topics.isEmpty()) {
            throw new IllegalArgumentException("MVSS requires at least one topic");
        }
        if (topics.size() > 32) {
            throw new IllegalArgumentException("MVSS supports at most 32 topics");
        }
        List<Topic> normalized = new ArrayList<>(topics.size());
        Set<String> names = new HashSet<>();
        for (Topic topic : topics) {
            Topic checked = new Topic(topic.name(), topic.hints());
            if (!names.add(checked.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate MVSS topic: " + checked.name());
            }
            normalized.add(checked);
        }
        return List.copyOf(normalized);
    }

    private static Path requireSortableFile(Path audioFile, Path audioLibrary) throws IOException {
        Path source = audioFile.toAbsolutePath().normalize();
        Path root = audioLibrary.toAbsolutePath().normalize();
        if (!source.startsWith(root)) {
            throw new IOException("MVSS refused to move a file outside the configured audio library");
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("Downloaded audio file was not found: " + source);
        }
        return source;
    }

    private static Path uniqueDestination(Path desired) {
        if (!Files.exists(desired)) {
            return desired;
        }
        String fileName = desired.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 2; ; index++) {
            Path candidate = desired.resolveSibling(base + " (" + index + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unavailable) {
            Files.move(source, destination);
        }
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String rounded(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    public record AudioFeatures(double bpm, double energy, double brightness,
                                double zeroCrossing, double dynamics) {
        public String summary() {
            return "BPM " + rounded(bpm) + " // energy " + rounded(energy)
                    + " // brightness " + rounded(brightness) + " // dynamics " + rounded(dynamics);
        }
    }

    public record SortResult(Path originalPath, Path sortedPath, String vibe,
                             AudioFeatures features, String classifier) {
    }

    public record Topic(String name, List<String> hints) {
        public Topic {
            name = name == null ? "" : name.trim();
            if (name.isEmpty() || name.length() > 48 || name.equals(".") || name.equals("..")
                    || name.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*")) {
                throw new IllegalArgumentException("Invalid MVSS topic name: " + name);
            }
            List<String> normalizedHints = new ArrayList<>();
            if (hints != null) {
                for (String hint : hints) {
                    String normalized = hint == null ? "" : hint.trim().toLowerCase(Locale.ROOT);
                    if (!normalized.isEmpty() && normalized.length() <= 40
                            && !normalizedHints.contains(normalized)) {
                        normalizedHints.add(normalized);
                    }
                }
            }
            if (normalizedHints.size() > 24) {
                throw new IllegalArgumentException("MVSS topic " + name + " has more than 24 hints");
            }
            hints = List.copyOf(normalizedHints);
        }
    }

    private enum Archetype { ENERGETIC, CHILL, DARK, BRIGHT, MELANCHOLIC, ATMOSPHERIC }
}
