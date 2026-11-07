import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers and stages verified iNTAKE beta releases from GitHub.
 *
 * <p>The running application only downloads and verifies artifacts. A detached
 * PowerShell process performs the uninstall/install transaction after every
 * iNTAKE JVM has exited, so no in-use runtime files are replaced.</p>
 */
public final class ApplicationUpdater {
    private static final String REPOSITORY = "Testingmofo/iNTAKE";
    private static final URI RELEASES_API = URI.create(
            "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=20");
    private static final Pattern BETA_TAG = Pattern.compile(
            "\"tag_name\"\\s*:\\s*\"v?(\\d+\\.\\d+\\.\\d+-beta(?:\\.\\d+)?)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECKSUM_LINE = Pattern.compile(
            "(?im)^([0-9a-f]{64})\\s+\\*?(.+?)\\s*$");
    private static final Pattern MANIFEST_VERSION = Pattern.compile(
            "\"version\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+){2,3})\"");

    private final HttpClient httpClient;
    private final Path applicationDataDirectory;

    public ApplicationUpdater(Path applicationDataDirectory) {
        this.applicationDataDirectory = Objects.requireNonNull(applicationDataDirectory)
                .toAbsolutePath().normalize();
        httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public static String currentVersion() {
        String configured = System.getProperty("intake.version");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String packaged = System.getProperty("jpackage.app-version");
        if (packaged != null && packaged.matches("\\d+\\.\\d+\\.\\d+")) {
            return packaged + "-beta";
        }
        return "0.2.2-beta";
    }

    public Optional<ReleaseInfo> findNewerBetaRelease() throws IOException, InterruptedException {
        HttpRequest request = request(RELEASES_API).GET().build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(response.statusCode(), "GitHub release check");

        Version current = Version.parse(currentVersion());
        List<Version> releases = new ArrayList<>();
        Matcher matcher = BETA_TAG.matcher(response.body());
        while (matcher.find()) {
            Version candidate = Version.parse(matcher.group(1));
            if (!releases.contains(candidate)) {
                releases.add(candidate);
            }
        }
        return releases.stream()
                .filter(candidate -> candidate.compareTo(current) > 0)
                .max(Comparator.naturalOrder())
                .map(ApplicationUpdater::releaseInfo);
    }

    public StagedUpdate downloadAndVerify(ReleaseInfo release, Consumer<String> status)
            throws IOException, InterruptedException {
        Objects.requireNonNull(release);
        Consumer<String> reporter = status == null ? ignored -> { } : status;
        Path releaseDirectory = applicationDataDirectory.resolve("updates")
                .resolve(release.tag()).toAbsolutePath().normalize();
        Files.createDirectories(releaseDirectory);

        reporter.accept("Downloading release checksums...");
        String checksums = downloadText(release.checksumsUri());
        String expectedHash = expectedHash(checksums, release.installerFileName());

        Path installer = releaseDirectory.resolve(release.installerFileName()).normalize();
        if (!installer.startsWith(releaseDirectory)) {
            throw new IOException("Unsafe installer filename returned by release metadata");
        }
        if (!Files.isRegularFile(installer)
                || !sha256(installer).equalsIgnoreCase(expectedHash)) {
            Path partial = installer.resolveSibling(installer.getFileName() + ".partial");
            Files.deleteIfExists(partial);
            reporter.accept("Downloading " + release.installerFileName() + "...");
            downloadFile(release.installerUri(), partial);
            String actualHash = sha256(partial);
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                Files.deleteIfExists(partial);
                throw new IOException("Installer SHA-256 mismatch. Expected " + expectedHash
                        + " but received " + actualHash);
            }
            moveReplacing(partial, installer);
        }
        reporter.accept("Installer SHA-256 verified.");
        return new StagedUpdate(release, installer, expectedHash);
    }

    public Process launchDetachedUpdate(StagedUpdate staged, Path installDirectory, long parentPid)
            throws IOException {
        Objects.requireNonNull(staged);
        Path installation = Objects.requireNonNull(installDirectory).toAbsolutePath().normalize();
        Path script = staged.installer().resolveSibling("install-update.ps1");
        Files.writeString(script, updaterScript(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return new ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.toString(),
                "-Installer", staged.installer().toString(),
                "-ExpectedHash", staged.expectedSha256(),
                "-InstallDir", installation.toString(),
                "-ParentPid", Long.toString(parentPid))
                .directory(script.getParent().toFile())
                .start();
    }

    public ExtensionSyncResult syncManagedExtension(Path bundledExtensionDirectory)
            throws IOException {
        Path source = Objects.requireNonNull(bundledExtensionDirectory)
                .toAbsolutePath().normalize();
        Path sourceManifest = source.resolve("manifest.json");
        if (!Files.isRegularFile(sourceManifest)) {
            return new ExtensionSyncResult(false, "", managedExtensionDirectory());
        }
        String sourceVersion = manifestVersion(sourceManifest);
        Path destination = managedExtensionDirectory();
        Path destinationManifest = destination.resolve("manifest.json");
        String existingVersion = Files.isRegularFile(destinationManifest)
                ? manifestVersion(destinationManifest) : "";
        if (sourceVersion.equals(existingVersion)) {
            return new ExtensionSyncResult(false, sourceVersion, destination);
        }

        Path temporary = destination.resolveSibling("browser-extension.update");
        deleteTree(temporary);
        Files.createDirectories(temporary);
        try (var paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                Path relative = source.relativize(item);
                Path target = temporary.resolve(relative).normalize();
                if (!target.startsWith(temporary)) {
                    throw new IOException("Unsafe browser-extension path: " + relative);
                }
                if (Files.isDirectory(item)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(item)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(item, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        Path backup = destination.resolveSibling("browser-extension.previous");
        deleteTree(backup);
        if (Files.exists(destination)) {
            Files.move(destination, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            moveReplacing(temporary, destination);
            deleteTree(backup);
        } catch (IOException failure) {
            if (!Files.exists(destination) && Files.exists(backup)) {
                Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            throw failure;
        }
        return new ExtensionSyncResult(true, sourceVersion, destination);
    }

    public Path managedExtensionDirectory() {
        return applicationDataDirectory.resolve("browser-extension")
                .toAbsolutePath().normalize();
    }

    public String managedExtensionVersion() {
        Path manifest = managedExtensionDirectory().resolve("manifest.json");
        try {
            return Files.isRegularFile(manifest) ? manifestVersion(manifest) : "";
        } catch (IOException invalid) {
            return "";
        }
    }

    private String downloadText(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                request(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(response.statusCode(), "Release metadata download");
        return response.body();
    }

    private void downloadFile(URI uri, Path destination) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = httpClient.send(
                request(uri).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        requireSuccess(response.statusCode(), "Installer download");
        try (InputStream input = response.body()) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "iNTAKE-Updater");
    }

    private static void requireSuccess(int statusCode, String operation) throws IOException {
        if (statusCode / 100 != 2) {
            throw new IOException(operation + " returned HTTP " + statusCode);
        }
    }

    private static ReleaseInfo releaseInfo(Version version) {
        String value = version.toString();
        String tag = "v" + value;
        String fileName = "iNTAKE-Setup-" + value + ".exe";
        String base = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/";
        return new ReleaseInfo(value, tag, fileName, URI.create(base + fileName),
                URI.create(base + "SHA256SUMS.txt"),
                URI.create("https://github.com/" + REPOSITORY + "/releases/tag/" + tag));
    }

    private static String expectedHash(String checksums, String fileName) throws IOException {
        Matcher matcher = CHECKSUM_LINE.matcher(checksums);
        while (matcher.find()) {
            if (matcher.group(2).trim().equals(fileName)) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        throw new IOException("SHA256SUMS.txt does not contain " + fileName);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
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

    private static String manifestVersion(Path manifest) throws IOException {
        Matcher matcher = MANIFEST_VERSION.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
        if (!matcher.find()) {
            throw new IOException("Extension manifest has no valid version: " + manifest);
        }
        return matcher.group(1);
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unavailable) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String updaterScript() {
        return """
                param(
                    [Parameter(Mandatory=$true)][string]$Installer,
                    [Parameter(Mandatory=$true)][string]$ExpectedHash,
                    [Parameter(Mandatory=$true)][string]$InstallDir,
                    [Parameter(Mandatory=$true)][long]$ParentPid
                )
                $ErrorActionPreference = 'Stop'
                $log = Join-Path (Split-Path -Parent $Installer) 'update.log'
                try {
                    "[$(Get-Date -Format o)] iNTAKE update started." | Set-Content -LiteralPath $log
                    $actualHash = (Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash.ToLowerInvariant()
                    if ($actualHash -ne $ExpectedHash.ToLowerInvariant()) {
                        throw "Installer hash changed after verification."
                    }
                    $deadline = (Get-Date).AddSeconds(90)
                    while (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) {
                        if ((Get-Date) -gt $deadline) {
                            Stop-Process -Id $ParentPid -Force -ErrorAction SilentlyContinue
                            break
                        }
                        Start-Sleep -Milliseconds 250
                    }
                    Get-Process -Name 'iNTAKE' -ErrorAction SilentlyContinue |
                        Stop-Process -Force -ErrorAction SilentlyContinue
                    Start-Sleep -Milliseconds 750

                    $registryPaths = @(
                        'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
                        'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
                        'HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'
                    )
                    $entry = Get-ItemProperty -Path $registryPaths -ErrorAction SilentlyContinue |
                        Where-Object { $_.DisplayName -eq 'iNTAKE' -and $_.Publisher -eq 'iNSRT' } |
                        Select-Object -First 1
                    if ($entry) {
                        $uninstall = [string]$entry.UninstallString
                        if ($uninstall -notmatch '(?i)({[0-9a-f-]+})') {
                            throw "The registered iNTAKE uninstaller has no MSI product code."
                        }
                        $remove = Start-Process -FilePath 'msiexec.exe' -ArgumentList @(
                            '/x', $Matches[1], '/qn', '/norestart'
                        ) -Wait -PassThru
                        if ($remove.ExitCode -notin @(0, 1605, 1614, 3010)) {
                            throw "Uninstall failed with exit code $($remove.ExitCode)."
                        }
                    }

                    $installArgs = @('/quiet', ('INSTALLDIR="' + $InstallDir + '"'))
                    $install = Start-Process -FilePath $Installer -ArgumentList $installArgs -Wait -PassThru
                    if ($install.ExitCode -notin @(0, 1641, 3010)) {
                        throw "Setup failed with exit code $($install.ExitCode)."
                    }
                    $installedEntry = Get-ItemProperty -Path $registryPaths -ErrorAction SilentlyContinue |
                        Where-Object { $_.DisplayName -eq 'iNTAKE' -and $_.Publisher -eq 'iNSRT' } |
                        Select-Object -First 1
                    $actualInstallDir = if ($installedEntry -and $installedEntry.InstallLocation) {
                        [string]$installedEntry.InstallLocation
                    } else {
                        $InstallDir
                    }
                    $application = Join-Path $actualInstallDir 'iNTAKE.exe'
                    if (Test-Path -LiteralPath $application) {
                        Start-Process -FilePath $application
                    }
                    "[$(Get-Date -Format o)] Update completed." | Add-Content -LiteralPath $log
                } catch {
                    "[$(Get-Date -Format o)] $($_ | Out-String)" | Add-Content -LiteralPath $log
                    Start-Process -FilePath $Installer
                    exit 1
                }
                """;
    }

    public record ReleaseInfo(String version, String tag, String installerFileName,
                              URI installerUri, URI checksumsUri, URI releasePage) {
        public ReleaseInfo {
            Objects.requireNonNull(version);
            Objects.requireNonNull(tag);
            Objects.requireNonNull(installerFileName);
            Objects.requireNonNull(installerUri);
            Objects.requireNonNull(checksumsUri);
            Objects.requireNonNull(releasePage);
        }
    }

    public record StagedUpdate(ReleaseInfo release, Path installer, String expectedSha256) {
        public StagedUpdate {
            Objects.requireNonNull(release);
            installer = Objects.requireNonNull(installer).toAbsolutePath().normalize();
            Objects.requireNonNull(expectedSha256);
        }
    }

    public record ExtensionSyncResult(boolean updated, String version, Path directory) {
        public ExtensionSyncResult {
            Objects.requireNonNull(version);
            directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        }
    }

    private record Version(int major, int minor, int patch, String qualifier)
            implements Comparable<Version> {
        private static final Pattern FORMAT = Pattern.compile(
                "v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?");

        static Version parse(String value) {
            Matcher matcher = FORMAT.matcher(Objects.requireNonNull(value).trim());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid release version: " + value);
            }
            return new Version(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Objects.requireNonNullElse(matcher.group(4), ""));
        }

        @Override
        public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result == 0) result = Integer.compare(minor, other.minor);
            if (result == 0) result = Integer.compare(patch, other.patch);
            if (result == 0) {
                if (qualifier.isEmpty() != other.qualifier.isEmpty()) {
                    result = qualifier.isEmpty() ? 1 : -1;
                } else {
                    result = qualifier.compareToIgnoreCase(other.qualifier);
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch
                    + (qualifier.isEmpty() ? "" : "-" + qualifier);
        }
    }
}
