import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Manages the optional, independently installed Ollama enhancement for MVSS. */
public final class OllamaManager {
    private static final URI API_ROOT = URI.create("http://127.0.0.1:11434/api/");
    private static final URI LATEST_RELEASE = URI.create(
            "https://api.github.com/repos/ollama/ollama/releases/latest");
    private static final Pattern TAG_NAME = Pattern.compile(
            "\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern VERSION = Pattern.compile(
            "\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern MODEL_NAME = Pattern.compile(
            "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern PULL_STATUS = Pattern.compile(
            "\\\"status\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern PULL_COMPLETED = Pattern.compile(
            "\\\"completed\\\"\\s*:\\s*(\\d+)");
    private static final Pattern PULL_TOTAL = Pattern.compile(
            "\\\"total\\\"\\s*:\\s*(\\d+)");
    private static final Pattern CHECKSUM = Pattern.compile(
            "(?im)^([0-9a-f]{64})\\s+\\*?(?:\\./)?OllamaSetup\\.exe\\s*$");

    private static final List<ModelOption> COMPATIBLE_MODELS = List.of(
            new ModelOption("qwen3:0.6b", "523 MB", 523_000_000L, true,
                    "Recommended // multilingual text classifier with the best size/quality balance."),
            new ModelOption("gemma3:270m", "292 MB", 292_000_000L, false,
                    "Minimum footprint // quickest on weak hardware, with reduced classification quality."),
            new ModelOption("qwen:0.5b", "395 MB", 395_000_000L, false,
                    "Legacy lightweight multilingual option."),
            new ModelOption("gemma3:1b", "806 MB", 806_000_000L, false,
                    "Stronger multilingual classification at a moderate memory cost."),
            new ModelOption("qwen3:1.7b", "1.4 GB", 1_400_000_000L, false,
                    "Higher-quality option for systems with more RAM."));

    private final Path dataDirectory;
    private final HttpClient httpClient;

    public OllamaManager(Path applicationDataDirectory) {
        dataDirectory = Objects.requireNonNull(applicationDataDirectory)
                .toAbsolutePath().normalize();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public List<ModelOption> compatibleModels() {
        return COMPATIBLE_MODELS;
    }

    public Status detect() {
        Optional<Path> executable = locateExecutable();
        boolean running = false;
        String version = "";
        List<String> models = List.of();
        try {
            HttpResponse<String> versionResponse = httpClient.send(
                    localRequest("version").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (versionResponse.statusCode() / 100 == 2) {
                running = true;
                version = firstGroup(VERSION, versionResponse.body()).orElse("unknown");
                HttpResponse<String> tagsResponse = httpClient.send(
                        localRequest("tags").GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (tagsResponse.statusCode() / 100 == 2) {
                    ArrayList<String> discovered = new ArrayList<>();
                    Matcher matcher = MODEL_NAME.matcher(tagsResponse.body());
                    while (matcher.find()) {
                        if (!discovered.contains(matcher.group(1))) {
                            discovered.add(matcher.group(1));
                        }
                    }
                    models = List.copyOf(discovered);
                }
            }
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return new Status(executable.isPresent() || running, running, version,
                executable, modelDirectory(), models, hardwareSummary());
    }

    public Path downloadVerifiedInstaller(Consumer<String> progress)
            throws IOException, InterruptedException {
        Consumer<String> reporter = progress == null ? ignored -> { } : progress;
        reporter.accept("Reading the latest official Ollama release...");
        HttpResponse<String> release = httpClient.send(
                remoteRequest(LATEST_RELEASE).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(release, "Ollama release lookup");
        String tag = parseReleaseTag(release.body());
        String base = "https://github.com/ollama/ollama/releases/download/" + tag + "/";
        HttpResponse<String> checksums = httpClient.send(
                remoteRequest(URI.create(base + "sha256sum.txt")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(checksums, "Ollama checksum download");
        String expected = parseInstallerChecksum(checksums.body());

        Path directory = dataDirectory.resolve("addons").resolve("ollama").resolve(tag);
        Files.createDirectories(directory);
        Path installer = directory.resolve("OllamaSetup.exe");
        if (Files.isRegularFile(installer) && sha256(installer).equalsIgnoreCase(expected)) {
            reporter.accept("Cached Ollama installer verified.");
            return installer;
        }

        Path partial = installer.resolveSibling("OllamaSetup.exe.partial");
        Files.deleteIfExists(partial);
        reporter.accept("Downloading Ollama " + tag + "...");
        HttpResponse<InputStream> response = httpClient.send(
                remoteRequest(URI.create(base + "OllamaSetup.exe"))
                        .timeout(Duration.ofHours(2)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Ollama installer download returned HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        try (InputStream input = response.body(); var output = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[128 * 1024];
            long received = 0;
            int lastPercent = -1;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                received += read;
                if (total > 0) {
                    int percent = (int) Math.min(100, received * 100 / total);
                    if (percent >= lastPercent + 5) {
                        lastPercent = percent;
                        reporter.accept("Downloading Ollama // " + percent + "%");
                    }
                }
            }
        }
        String actual = sha256(partial);
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(partial);
            throw new IOException("Ollama installer SHA-256 mismatch");
        }
        Files.move(partial, installer, StandardCopyOption.REPLACE_EXISTING);
        reporter.accept("Official Ollama installer SHA-256 verified.");
        return installer;
    }

    public Process launchInstaller(Path installer) throws IOException {
        return new ProcessBuilder(Objects.requireNonNull(installer).toString()).start();
    }

    public void pullModel(ModelOption model, Consumer<String> progress)
            throws IOException, InterruptedException {
        Objects.requireNonNull(model);
        Consumer<String> reporter = progress == null ? ignored -> { } : progress;
        ensureRunning(reporter);
        String body = "{\"model\":\"" + jsonEscape(model.name()) + "\"}";
        HttpResponse<InputStream> response = httpClient.send(
                localRequest("pull").timeout(Duration.ofHours(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Ollama model pull returned HTTP " + response.statusCode());
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            int lastPercent = -1;
            while ((line = reader.readLine()) != null) {
                String status = firstGroup(PULL_STATUS, line).orElse("Downloading model");
                long completed = longGroup(PULL_COMPLETED, line).orElse(0L);
                long total = longGroup(PULL_TOTAL, line).orElse(0L);
                if (total > 0) {
                    int percent = (int) Math.min(100, completed * 100 / total);
                    if (percent >= lastPercent + 2) {
                        lastPercent = percent;
                        reporter.accept(status + " // " + percent + "%");
                    }
                } else if (!status.isBlank()) {
                    reporter.accept(status);
                }
            }
        }
        setPreferredModel(model.name());
        reporter.accept(model.name() + " installed and ready for MVSS.");
    }

    private void ensureRunning(Consumer<String> reporter) throws IOException, InterruptedException {
        if (detect().running()) {
            return;
        }
        Path executable = locateExecutable().orElseThrow(() ->
                new IOException("Ollama is not installed"));
        reporter.accept("Starting the local Ollama service...");
        new ProcessBuilder(executable.toString(), "serve")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        for (int attempt = 0; attempt < 30; attempt++) {
            Thread.sleep(500);
            if (detect().running()) {
                return;
            }
        }
        throw new IOException("Ollama did not start within 15 seconds");
    }

    private Optional<Path> locateExecutable() {
        ArrayList<Path> candidates = new ArrayList<>();
        ProcessHandle.allProcesses()
                .map(ProcessHandle::info)
                .map(ProcessHandle.Info::command)
                .flatMap(Optional::stream)
                .map(Path::of)
                .filter(path -> path.getFileName() != null
                        && path.getFileName().toString().equalsIgnoreCase("ollama.exe"))
                .forEach(candidates::add);
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "Programs", "Ollama", "ollama.exe"));
        }
        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String element : pathValue.split(Pattern.quote(java.io.File.pathSeparator))) {
                if (!element.isBlank()) {
                    candidates.add(Path.of(element).resolve("ollama.exe"));
                }
            }
        }
        return candidates.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile).findFirst();
    }

    public Optional<String> preferredModel() {
        Path file = dataDirectory.resolve("ollama-preferred-model.txt");
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.matches("[A-Za-z0-9._/-]+(?::[A-Za-z0-9._-]+)?")
                    ? Optional.of(value) : Optional.empty();
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private void setPreferredModel(String model) throws IOException {
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("ollama-preferred-model.txt"), model,
                StandardCharsets.UTF_8);
    }

    public Path modelDirectory() {
        String configured = System.getenv("OLLAMA_MODELS");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".ollama", "models")
                        .toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private String hardwareSummary() {
        String architecture = Objects.requireNonNullElse(
                System.getenv("PROCESSOR_ARCHITECTURE"), System.getProperty("os.arch"));
        int processors = Runtime.getRuntime().availableProcessors();
        try {
            FileStore store = Files.getFileStore(Path.of(System.getProperty("user.home")));
            return architecture + " // " + processors + " logical CPU(s) // "
                    + humanBytes(store.getUsableSpace()) + " free on model drive";
        } catch (IOException unavailable) {
            return architecture + " // " + processors + " logical CPU(s)";
        }
    }

    private static HttpRequest.Builder localRequest(String endpoint) {
        return HttpRequest.newBuilder(API_ROOT.resolve(endpoint))
                .timeout(Duration.ofSeconds(3));
    }

    private static HttpRequest.Builder remoteRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "iNTAKE-Ollama-Addon");
    }

    private static void requireSuccess(HttpResponse<?> response, String operation)
            throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException(operation + " returned HTTP " + response.statusCode());
        }
    }

    private static Optional<String> firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    static String parseReleaseTag(String releaseJson) throws IOException {
        return firstGroup(TAG_NAME, Objects.requireNonNull(releaseJson))
                .orElseThrow(() -> new IOException("Latest Ollama release has no tag"));
    }

    static String parseInstallerChecksum(String checksumFile) throws IOException {
        return firstGroup(CHECKSUM, Objects.requireNonNull(checksumFile))
                .orElseThrow(() -> new IOException(
                        "Official Ollama checksums do not contain OllamaSetup.exe"));
    }

    private static Optional<Long> longGroup(Pattern pattern, String value) {
        return firstGroup(pattern, value).map(Long::parseLong);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String humanBytes(long bytes) {
        double gib = bytes / 1_073_741_824.0;
        return String.format(Locale.ROOT, "%.1f GiB", gib);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ModelOption(String name, String downloadSize, long approximateBytes,
                              boolean recommended, String description) {
        public ModelOption {
            Objects.requireNonNull(name);
            Objects.requireNonNull(downloadSize);
            Objects.requireNonNull(description);
        }

        @Override
        public String toString() {
            return (recommended ? "★ " : "") + name + " // " + downloadSize;
        }
    }

    public record Status(boolean installed, boolean running, String version,
                         Optional<Path> executable, Path modelDirectory,
                         List<String> models, String hardwareSummary) {
        public Status {
            Objects.requireNonNull(version);
            executable = Objects.requireNonNull(executable);
            modelDirectory = Objects.requireNonNull(modelDirectory).toAbsolutePath().normalize();
            models = List.copyOf(models);
            Objects.requireNonNull(hardwareSummary);
        }
    }
}
