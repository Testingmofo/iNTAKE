import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * iNTAKE - a tray-resident JavaFX front end and command coordinator for yt-dlp.
 *
 * <p>Invocation examples:</p>
 * <pre>
 *   mvn javafx:run
 *   mvn javafx:run -Djavafx.args="-silent"
 *   mvn javafx:run -Djavafx.args="-extension https://example/video"
 * </pre>
 *
 * <p>All arguments other than {@code -silent} and {@code -extension} are passed
 * to one yt-dlp process. Additional requests can be submitted programmatically
 * through {@link #submitDownload(List)} while the application is running.</p>
 */
public final class Main extends Application {
    private static final String BACKGROUND = "#0b0e14";
    private static final String PANEL = "#111722";
    private static final String YELLOW = "#fcee0a";
    private static final String CYAN = "#00f0ff";
    private static final String MAGENTA = "#ff0055";
    private static final String TEXT = "#d7f9ff";
    private static final int MAX_LOG_NODES = 5_000;
    private static final int MAX_HISTORY_ENTRIES = 2_000;
    private static final int EXTENSION_BRIDGE_PORT = 17_845;
    private static final int MAX_EXTENSION_REQUEST_BYTES = 16_384;
    private static final String EXTENSION_ID = "omgodbpfilkfdihjffinlknijmdpogbn";
    private static final String NATIVE_HOST_NAME = "com.insrt.intake";
    private static final Path APPLICATION_DATA_DIRECTORY = resolveApplicationDataDirectory();
    private static final List<String> SUPPORTED_AUDIO_FORMATS = List.of(
            "best", "aac", "alac", "flac", "m4a", "mp3", "opus", "vorbis", "wav");
    private static final List<String> SUPPORTED_VIDEO_FORMATS = List.of(
            "avi", "flv", "mkv", "mov", "mp4", "webm");
    private static final List<String> DEFAULT_AUDIO_FORMATS = List.of("opus", "mp3");
    private static final List<String> DEFAULT_VIDEO_FORMATS = List.of("mp4", "mov");

    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "^\\[download]\\s+(?<percent>\\d+(?:\\.\\d+)?)%\\s+of\\s+(?:~\\s*)?(?<size>\\S+).*$");
    private static final Pattern DESTINATION_PATTERN = Pattern.compile(
            "^\\[download]\\s+Destination:\\s+(?<path>.+)$");
    private static final Pattern ALREADY_DOWNLOADED_PATTERN = Pattern.compile(
            "^\\[download]\\s+(?<path>.+?)\\s+has already been downloaded$");
    private static final Pattern POST_PROCESS_DESTINATION_PATTERN = Pattern.compile(
            "^\\[(?:ExtractAudio|VideoConvertor|Metadata)]\\s+Destination:\\s+(?<path>.+)$");
    private static final Pattern MERGER_PATTERN = Pattern.compile(
            "^\\[Merger]\\s+Merging formats into \\\"(?<path>.+)\\\"$");

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withLocale(Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final AtomicReference<Main> INSTANCE = new AtomicReference<>();

    private final BlockingQueue<DownloadCommand> commandQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, DownloadItem> extensionJobs = new ConcurrentHashMap<>();
    private final ObservableList<DownloadItem> downloads = FXCollections.observableArrayList();
    private final AtomicInteger outstandingDownloads = new AtomicInteger();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean updateComplete = new AtomicBoolean(false);
    private final AtomicBoolean initialCommandsSubmitted = new AtomicBoolean(false);
    private final CountDownLatch updateFinished = new CountDownLatch(1);
    private final ReentrantLock pauseLock = new ReentrantLock();
    private final Condition unpaused = pauseLock.newCondition();
    private final Object failureFileLock = new Object();
    private final Object historyFileLock = new Object();

    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            daemonThreadFactory("intake-coordinator"));
    private final ExecutorService background = Executors.newCachedThreadPool(
            daemonThreadFactory("intake-background"));

    private volatile Process activeProcess;
    private volatile DownloadItem activeItem;
    private volatile boolean silentLaunch;
    private volatile boolean extensionLaunch;
    private volatile TrayIcon trayIcon;
    private volatile HttpServer extensionBridge;

    private Stage primaryStage;
    private Stage settingsStage;
    private TranslateTransition marqueeAnimation;
    private TableView<DownloadItem> downloadTable;
    private TextFlow logFlow;
    private ScrollPane logScrollPane;
    private Button pauseButton;
    private Label stateLabel;
    private ComboBox<String> audioFormatSelector;
    private ComboBox<String> videoFormatSelector;
    private final Path settingsFile = APPLICATION_DATA_DIRECTORY.resolve("intake.properties");
    private final Path historyFile = APPLICATION_DATA_DIRECTORY.resolve("download-history.tsv");
    private AppSettings appSettings = AppSettings.defaults();
    private double windowDragOffsetX;
    private double windowDragOffsetY;
    private boolean windowDragArmed;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        INSTANCE.set(this);
        appSettings = loadSettings();
        List<String> args = getParameters().getRaw();
        silentLaunch = containsIgnoreCase(args, "-silent") || containsIgnoreCase(args, "-extension");
        extensionLaunch = containsIgnoreCase(args, "-extension");
    }

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false);
        primaryStage = stage;
        // A transparent stage is undecorated and allows the polygonal control
        // module to define the visible outer silhouette of the window.
        stage.initStyle(StageStyle.TRANSPARENT);
        loadDownloadHistory();
        initializeUI(stage);
        registerPackagedNativeMessagingHost();
        boolean trayReady = installSystemTray();
        startExtensionBridge();

        coordinator.execute(this::coordinateCommands);
        background.execute(this::runUpdateCheck);

        List<String> ytDlpArguments = getParameters().getRaw().stream()
                .filter(argument -> !argument.equalsIgnoreCase("-silent"))
                .filter(argument -> !argument.equalsIgnoreCase("-extension"))
                .toList();
        if (!ytDlpArguments.isEmpty()) {
            submitDownload(ytDlpArguments);
        }
        initialCommandsSubmitted.set(true);

        if (!silentLaunch || !trayReady) {
            showMainWindow();
        } else {
            appendLog("Started hidden in the system tray.", LogKind.SYSTEM);
        }
        evaluateExtensionExit();
    }

    @Override
    public void stop() {
        shuttingDown.set(true);
        if (marqueeAnimation != null) {
            marqueeAnimation.stop();
            marqueeAnimation = null;
        }
        resumeWorkers();
        Process process = activeProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        HttpServer bridge = extensionBridge;
        extensionBridge = null;
        if (bridge != null) {
            bridge.stop(0);
        }
        coordinator.shutdownNow();
        background.shutdownNow();
        removeTrayIcon();
        INSTANCE.compareAndSet(this, null);
    }

    /**
     * Adds one yt-dlp invocation to the coordinator. The method is thread-safe.
     * Each list element must be one already-tokenized process argument.
     */
    public static void submitDownload(List<String> ytDlpArguments) {
        Main application = INSTANCE.get();
        if (application == null) {
            throw new IllegalStateException("iNTAKE is not running");
        }
        application.enqueueDownload(ytDlpArguments);
    }

    private void initializeUI(Stage stage) {
        downloadTable = createDownloadTable();
        logFlow = new TextFlow();
        logFlow.setLineSpacing(3);
        logFlow.setPadding(new Insets(10));
        logFlow.setStyle("-fx-background-color: " + BACKGROUND + ";");

        logScrollPane = new ScrollPane(logFlow);
        logScrollPane.setFitToWidth(true);
        logScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        logScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        logScrollPane.setStyle("-fx-background: " + BACKGROUND + "; -fx-background-color: " + BACKGROUND
                + "; -fx-border-color: " + CYAN + "; -fx-border-width: 2;");
        VBox.setVgrow(logScrollPane, Priority.ALWAYS);

        Label logTitle = titleLabel("LOG CONSOLE // LIVE TELEMETRY", CYAN);
        VBox logPanel = new VBox(8, logTitle, logScrollPane);
        logPanel.setPadding(new Insets(12));

        VBox centerPanel = new VBox(10, createDownloadInputBar(), downloadTable, createControlBar());
        centerPanel.setPadding(new Insets(12));
        centerPanel.setStyle("-fx-background-color: " + BACKGROUND + ";");
        centerPanel.setMinWidth(0);
        VBox.setVgrow(downloadTable, Priority.ALWAYS);

        logPanel.setMinWidth(350);
        logPanel.setPrefWidth(350);
        logPanel.setMaxWidth(350);
        logPanel.setStyle("-fx-background-color: " + PANEL + "; -fx-border-color: " + CYAN
                + "; -fx-border-width: 1 0 1 1;");

        HBox workspace = new HBox(10, centerPanel, logPanel);
        HBox.setHgrow(centerPanel, Priority.ALWAYS);
        HBox.setMargin(logPanel, new Insets(12, 10, 12, 0));
        workspace.setStyle("-fx-background-color: " + BACKGROUND + ";");

        BorderPane applicationBody = new BorderPane(workspace);
        applicationBody.setTop(createApplicationHeader(stage));
        applicationBody.setStyle("-fx-background-color: " + BACKGROUND + "; -fx-border-color: "
                + YELLOW + "; -fx-border-width: 1;");

        StackPane windowFrame = new StackPane(applicationBody, createWindowControlPanel(stage));
        StackPane.setMargin(applicationBody, new Insets(43, 0, 0, 0));
        StackPane.setAlignment(applicationBody, Pos.BOTTOM_CENTER);
        windowFrame.setStyle("-fx-background-color: transparent; -fx-font-family: 'Monospaced';");

        Scene scene = new Scene(windowFrame, 1280, 784, Color.TRANSPARENT);
        stage.setScene(scene);
        stage.setTitle("iNTAKE // Media NetCrawler");
        stage.setMinWidth(920);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(event -> {
            event.consume();
            hideApplicationWindow("Window close requested");
        });
        stage.setOnShown(event -> Platform.runLater(this::styleInterfaceSkins));
    }

    private StackPane createApplicationHeader(Stage stage) {
        Label brand = new Label("iNTAKE //");
        brand.setTextFill(Color.web(YELLOW));
        brand.setFont(Font.font("Monospaced", FontWeight.EXTRA_BOLD, 22));
        brand.setMinWidth(Region.USE_PREF_SIZE);
        brand.setMaxWidth(Region.USE_PREF_SIZE);

        String marqueeMessage =
                "CRAWLING THE NET // UNKNOWN VARIABLES // SIGNAL ACQUIRED // MEDIA PIPELINE ACTIVE // ";
        Font marqueeFont = Font.font("Monospaced", FontWeight.BOLD, 13);
        HBox marqueeTrack = new HBox();
        marqueeTrack.setAlignment(Pos.CENTER_LEFT);
        marqueeTrack.setLayoutY(5);

        Pane marqueeViewport = new Pane(marqueeTrack);
        marqueeViewport.setMinWidth(0);
        marqueeViewport.setMinHeight(28);
        marqueeViewport.setPrefHeight(28);
        Rectangle marqueeClip = new Rectangle();
        marqueeClip.widthProperty().bind(marqueeViewport.widthProperty());
        marqueeClip.heightProperty().bind(marqueeViewport.heightProperty());
        marqueeViewport.setClip(marqueeClip);
        HBox.setHgrow(marqueeViewport, Priority.ALWAYS);

        HBox headerContent = new HBox(14, brand, marqueeViewport);
        headerContent.setAlignment(Pos.CENTER_LEFT);
        headerContent.setPadding(new Insets(8, 275, 8, 16));
        headerContent.setMaxWidth(Double.MAX_VALUE);

        StackPane header = new StackPane(headerContent);
        header.setStyle("-fx-background-color: #080a0f; -fx-border-color: transparent transparent "
                + YELLOW + " transparent; -fx-border-width: 0 0 2 0;");
        Runnable restartMarquee = () -> restartMarquee(
                marqueeViewport, marqueeTrack, marqueeMessage, marqueeFont);
        marqueeViewport.widthProperty().addListener((property, oldValue, newValue) -> restartMarquee.run());
        Platform.runLater(restartMarquee);
        header.setOnMousePressed(event -> {
            windowDragArmed = true;
            windowDragOffsetX = event.getScreenX() - stage.getX();
            windowDragOffsetY = event.getScreenY() - stage.getY();
        });
        header.setOnMouseDragged(event -> {
            if (!windowDragArmed) {
                return;
            }
            if (stage.isMaximized()) {
                double horizontalRatio = event.getSceneX() / Math.max(1.0, stage.getWidth());
                stage.setMaximized(false);
                windowDragOffsetX = stage.getWidth() * horizontalRatio;
                windowDragOffsetY = Math.min(44.0, event.getSceneY());
            }
            stage.setX(event.getScreenX() - windowDragOffsetX);
            stage.setY(event.getScreenY() - windowDragOffsetY);
        });
        header.setOnMouseReleased(event -> windowDragArmed = false);
        return header;
    }

    private void restartMarquee(Pane viewport, HBox track, String message, Font font) {
        if (viewport.getWidth() <= 0) {
            return;
        }
        if (marqueeAnimation != null) {
            marqueeAnimation.stop();
        }

        Text firstSegment = createMarqueeSegment(message, font);
        double segmentWidth = firstSegment.getLayoutBounds().getWidth();
        if (segmentWidth <= 0) {
            return;
        }
        int segmentCount = Math.max(2,
                (int) Math.ceil(viewport.getWidth() / segmentWidth) + 2);
        track.getChildren().setAll(firstSegment);
        for (int index = 1; index < segmentCount; index++) {
            track.getChildren().add(createMarqueeSegment(message, font));
        }
        track.setTranslateX(0);

        marqueeAnimation = new TranslateTransition(
                Duration.seconds(Math.max(4.0, segmentWidth / 55.0)), track);
        marqueeAnimation.setFromX(0);
        marqueeAnimation.setToX(-segmentWidth);
        marqueeAnimation.setInterpolator(Interpolator.LINEAR);
        marqueeAnimation.setCycleCount(TranslateTransition.INDEFINITE);
        marqueeAnimation.playFromStart();
    }

    private static Text createMarqueeSegment(String message, Font font) {
        Text segment = new Text(message);
        segment.setFill(Color.web(CYAN));
        segment.setFont(font);
        return segment;
    }

    private StackPane createWindowControlPanel(Stage stage) {
        Button settingsButton = createWindowControlButton("");
        settingsButton.setGraphic(createSettingsIcon());
        settingsButton.setAccessibleText("Open iNTAKE settings");
        settingsButton.setOnAction(event -> showSettingsEditor());

        Button minimizeButton = createWindowControlButton("_");
        minimizeButton.setAccessibleText("Minimize iNTAKE");
        minimizeButton.setOnAction(event -> stage.setIconified(true));

        Button maximizeButton = createWindowControlButton("");
        maximizeButton.setGraphic(createMaximizeIcon(false));
        maximizeButton.setAccessibleText("Maximize or restore iNTAKE");
        maximizeButton.setOnAction(event -> stage.setMaximized(!stage.isMaximized()));
        stage.maximizedProperty().addListener((property, oldValue, maximized) ->
                maximizeButton.setGraphic(createMaximizeIcon(maximized)));

        Button closeButton = createWindowControlButton("X");
        closeButton.setAccessibleText("Close iNTAKE");
        closeButton.setOnAction(event -> hideApplicationWindow("Window Close selected"));

        HBox controls = new HBox(0, settingsButton, minimizeButton, maximizeButton, closeButton);
        controls.setAlignment(Pos.CENTER);

        Polygon enclosure = createControlPanelPolygon();
        enclosure.setFill(Color.web(BACKGROUND));
        enclosure.setMouseTransparent(true);

        Polyline exteriorEdges = new Polyline(
                0.0, 43.0,
                60.0, 2.0,
                204.0, 2.0,
                264.0, 43.0);
        exteriorEdges.setFill(Color.TRANSPARENT);
        exteriorEdges.setStroke(Color.web(YELLOW));
        exteriorEdges.setStrokeWidth(1.0);
        exteriorEdges.setStrokeLineJoin(StrokeLineJoin.MITER);
        exteriorEdges.setSmooth(false);
        exteriorEdges.setMouseTransparent(true);

        Pane panelGeometry = new Pane(enclosure, exteriorEdges);
        panelGeometry.setMinSize(264, 43);
        panelGeometry.setPrefSize(264, 43);
        panelGeometry.setMaxSize(264, 43);
        panelGeometry.setMouseTransparent(true);

        StackPane panel = new StackPane(panelGeometry, controls);
        panel.setMinSize(264, 43);
        panel.setPrefSize(264, 43);
        panel.setMaxSize(264, 43);
        panel.setPickOnBounds(false);
        StackPane.setAlignment(panel, Pos.TOP_RIGHT);
        return panel;
    }

    private StackPane createMaximizeIcon(boolean restoreMode) {
        Rectangle front = new Rectangle(10, 8, Color.TRANSPARENT);
        front.setStroke(Color.web(CYAN));
        front.setStrokeWidth(1.4);

        StackPane icon = new StackPane();
        icon.setMinSize(14, 12);
        icon.setPrefSize(14, 12);
        icon.setMaxSize(14, 12);
        if (restoreMode) {
            Rectangle rear = new Rectangle(9, 7, Color.TRANSPARENT);
            rear.setStroke(Color.web(CYAN));
            rear.setStrokeWidth(1.2);
            rear.setTranslateX(2);
            rear.setTranslateY(-2);
            front.setTranslateX(-1);
            front.setTranslateY(1);
            icon.getChildren().addAll(rear, front);
        } else {
            icon.getChildren().add(front);
        }
        icon.setMouseTransparent(true);
        return icon;
    }

    private Pane createSettingsIcon() {
        Pane icon = new Pane();
        icon.setMinSize(14, 12);
        icon.setPrefSize(14, 12);
        icon.setMaxSize(14, 12);
        for (double y : new double[]{1.5, 5.5, 9.5}) {
            Rectangle rail = new Rectangle(14, 1, Color.web(CYAN));
            rail.setY(y);
            icon.getChildren().add(rail);
        }
        double[] knobPositions = {3.0, 9.0, 5.5};
        for (int index = 0; index < knobPositions.length; index++) {
            Rectangle knob = new Rectangle(3, 3, Color.web(BACKGROUND));
            knob.setStroke(Color.web(CYAN));
            knob.setStrokeWidth(1.0);
            knob.setX(knobPositions[index]);
            knob.setY(index * 4.0 + 0.5);
            icon.getChildren().add(knob);
        }
        icon.setMouseTransparent(true);
        return icon;
    }

    private void showSettingsEditor() {
        if (settingsStage != null) {
            if (!settingsStage.isShowing()) {
                settingsStage.show();
            }
            settingsStage.toFront();
            settingsStage.requestFocus();
            return;
        }

        List<CheckBox> audioChecks = createFormatChecks(SUPPORTED_AUDIO_FORMATS, appSettings.audioFormats());
        List<CheckBox> videoChecks = createFormatChecks(SUPPORTED_VIDEO_FORMATS, appSettings.videoFormats());

        VBox formatMenuContent = new VBox(7);
        formatMenuContent.setPadding(new Insets(12));
        formatMenuContent.setMinWidth(330);
        formatMenuContent.setStyle("-fx-background-color: #080a0f; -fx-border-color: " + CYAN
                + "; -fx-border-width: 1; -fx-font-family: 'Monospaced';");
        formatMenuContent.getChildren().add(settingsSectionLabel("AUDIO FILETYPES", CYAN));
        formatMenuContent.getChildren().addAll(audioChecks);
        Separator formatSeparator = new Separator();
        formatSeparator.setStyle("-fx-background-color: " + YELLOW + ";");
        formatMenuContent.getChildren().add(formatSeparator);
        formatMenuContent.getChildren().add(settingsSectionLabel("VIDEO FILETYPES", YELLOW));
        formatMenuContent.getChildren().addAll(videoChecks);

        MenuButton filetypeMenu = new MenuButton("FILETYPES // EXPAND MATRIX");
        filetypeMenu.setMaxWidth(Double.MAX_VALUE);
        filetypeMenu.setStyle("-fx-background-color: " + CYAN + "; -fx-text-fill: #080a0f;"
                + " -fx-font-family: 'Monospaced'; -fx-font-weight: bold; -fx-background-radius: 0;"
                + " -fx-border-color: " + YELLOW + "; -fx-border-width: 0 3 3 0;");
        filetypeMenu.getItems().add(new CustomMenuItem(formatMenuContent, false));

        ComboBox<String> favoriteAudio = createFormatSelector();
        ComboBox<String> favoriteVideo = createFormatSelector();
        favoriteAudio.setPrefWidth(155);
        favoriteVideo.setPrefWidth(155);
        refreshFavoriteSelector(favoriteAudio, audioChecks, appSettings.favoriteAudioFormat());
        refreshFavoriteSelector(favoriteVideo, videoChecks, appSettings.favoriteVideoFormat());
        audioChecks.forEach(check -> check.setOnAction(event ->
                refreshFavoriteSelector(favoriteAudio, audioChecks, appSettings.favoriteAudioFormat())));
        videoChecks.forEach(check -> check.setOnAction(event ->
                refreshFavoriteSelector(favoriteVideo, videoChecks, appSettings.favoriteVideoFormat())));

        VBox favoriteAudioBox = new VBox(5,
                settingsSectionLabel("FAVORITE AUDIO FILETYPE", CYAN), favoriteAudio);
        VBox favoriteVideoBox = new VBox(5,
                settingsSectionLabel("FAVORITE VIDEO FILETYPE", YELLOW), favoriteVideo);
        HBox favoritesRow = new HBox(18, favoriteAudioBox, favoriteVideoBox);
        favoritesRow.setAlignment(Pos.CENTER_LEFT);

        TextField audioPath = createSettingsPathField(appSettings.audioOutputDirectory());
        TextField videoPath = createSettingsPathField(appSettings.videoOutputDirectory());

        VBox editor = new VBox(12,
                settingsSectionLabel("FORMAT ROUTING", CYAN),
                filetypeMenu,
                favoritesRow,
                settingsSectionLabel("AUDIO DOWNLOAD LOCATION", YELLOW),
                createDirectoryEditor(audioPath, "Choose audio download directory"),
                settingsSectionLabel("VIDEO DOWNLOAD LOCATION", YELLOW),
                createDirectoryEditor(videoPath, "Choose video download directory"));
        editor.setPadding(new Insets(18));
        editor.setStyle("-fx-background-color: " + PANEL + ";");

        Label configLocation = new Label("CONFIG // " + settingsFile);
        configLocation.setTextFill(Color.web("#718096"));
        configLocation.setFont(Font.font("Monospaced", 10));

        Button cancel = new Button("CANCEL");
        cancel.setStyle(warningButtonStyle());
        cancel.setOnAction(event -> settingsStage.hide());
        Button save = new Button("SAVE CONFIG");
        save.setStyle(primaryButtonStyle());
        save.setOnAction(event -> saveSettingsFromEditor(
                audioChecks, videoChecks, favoriteAudio, favoriteVideo, audioPath, videoPath));
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, configLocation, footerSpacer, cancel, save);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(10, 14, 14, 14));
        footer.setStyle("-fx-background-color: #080a0f; -fx-border-color: " + CYAN
                + " transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        BorderPane settingsRoot = new BorderPane(editor);
        settingsRoot.setTop(createSettingsTitleBar());
        settingsRoot.setBottom(footer);
        settingsRoot.setStyle("-fx-background-color: " + BACKGROUND + "; -fx-border-color: "
                + YELLOW + "; -fx-border-width: 1; -fx-font-family: 'Monospaced';");

        settingsStage = new Stage(StageStyle.TRANSPARENT);
        settingsStage.initOwner(primaryStage);
        settingsStage.initModality(Modality.WINDOW_MODAL);
        settingsStage.setTitle("iNTAKE // Configuration");
        settingsStage.setScene(new Scene(settingsRoot, 720, 520, Color.TRANSPARENT));
        settingsStage.setMinWidth(620);
        settingsStage.setMinHeight(470);
        settingsStage.setOnCloseRequest(event -> {
            event.consume();
            settingsStage.hide();
        });
        wireSettingsWindowDragging(settingsRoot, settingsStage);
        settingsStage.show();
    }

    private HBox createSettingsTitleBar() {
        Label title = settingsSectionLabel("// iNTAKE CONFIGURATION MATRIX", CYAN);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("X");
        close.setStyle(windowControlButtonStyle("transparent"));
        close.setOnAction(event -> settingsStage.hide());
        HBox bar = new HBox(8, title, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 14));
        bar.setStyle("-fx-background-color: #080a0f; -fx-border-color: transparent transparent "
                + CYAN + " transparent; -fx-border-width: 0 0 1 0;");
        return bar;
    }

    private void wireSettingsWindowDragging(Pane dragSurface, Stage stage) {
        double[] offset = new double[2];
        dragSurface.setOnMousePressed(event -> {
            if (event.getY() <= 44) {
                offset[0] = event.getScreenX() - stage.getX();
                offset[1] = event.getScreenY() - stage.getY();
            }
        });
        dragSurface.setOnMouseDragged(event -> {
            if (event.getY() <= 60) {
                stage.setX(event.getScreenX() - offset[0]);
                stage.setY(event.getScreenY() - offset[1]);
            }
        });
    }

    private List<CheckBox> createFormatChecks(List<String> supported, List<String> selected) {
        List<CheckBox> checks = new ArrayList<>();
        for (String format : supported) {
            CheckBox check = new CheckBox(formatDisplayName(format));
            check.setUserData(format);
            check.setSelected(selected.contains(format));
            check.setTextFill(Color.web(TEXT));
            check.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
            check.setStyle("-fx-mark-color: " + CYAN + "; -fx-focused-color: " + CYAN + ";");
            checks.add(check);
        }
        return checks;
    }

    private static void refreshFavoriteSelector(ComboBox<String> selector, List<CheckBox> checks,
                                                String preferredFormat) {
        String current = selector.getValue();
        List<String> enabled = selectedFormats(checks);
        List<String> displayValues = enabled.stream().map(Main::formatDisplayName).toList();
        selector.setItems(FXCollections.observableArrayList(displayValues));
        if (displayValues.isEmpty()) {
            selector.setValue(null);
            return;
        }
        String preferredDisplay = formatDisplayName(preferredFormat);
        if (current != null && displayValues.contains(current)) {
            selector.setValue(current);
        } else {
            selector.setValue(displayValues.contains(preferredDisplay)
                    ? preferredDisplay : displayValues.get(0));
        }
    }

    private HBox createDirectoryEditor(TextField pathField, String dialogTitle) {
        Button browse = new Button("BROWSE");
        browse.setStyle(primaryButtonStyle());
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(dialogTitle);
            try {
                Path current = Path.of(pathField.getText()).toAbsolutePath().normalize();
                if (Files.isDirectory(current)) {
                    chooser.setInitialDirectory(current.toFile());
                }
            } catch (RuntimeException ignored) {
                // The user may be midway through editing an incomplete path.
            }
            java.io.File chosen = chooser.showDialog(settingsStage);
            if (chosen != null) {
                pathField.setText(chosen.toPath().toAbsolutePath().normalize().toString());
            }
        });
        HBox row = new HBox(8, pathField, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        return row;
    }

    private TextField createSettingsPathField(Path value) {
        TextField field = new TextField(value.toString());
        field.setStyle("-fx-background-color: #080a0f; -fx-text-fill: " + TEXT
                + "; -fx-border-color: " + CYAN + "; -fx-border-width: 1;"
                + " -fx-background-radius: 0; -fx-border-radius: 0; -fx-font-family: 'Monospaced';"
                + " -fx-padding: 8;");
        return field;
    }

    private void saveSettingsFromEditor(List<CheckBox> audioChecks, List<CheckBox> videoChecks,
                                        ComboBox<String> favoriteAudio, ComboBox<String> favoriteVideo,
                                        TextField audioPath, TextField videoPath) {
        List<String> selectedAudio = selectedFormats(audioChecks);
        List<String> selectedVideo = selectedFormats(videoChecks);
        if (selectedAudio.isEmpty() || selectedVideo.isEmpty()) {
            appendLog("Settings require at least one audio and one video filetype.", LogKind.WARNING);
            return;
        }
        try {
            String selectedFavoriteAudio = normalizeFavoriteSelection(
                    favoriteAudio.getValue(), selectedAudio, "audio");
            String selectedFavoriteVideo = normalizeFavoriteSelection(
                    favoriteVideo.getValue(), selectedVideo, "video");
            Path audioDirectory = normalizeConfiguredDirectory(audioPath.getText(), "audio");
            Path videoDirectory = normalizeConfiguredDirectory(videoPath.getText(), "video");
            Files.createDirectories(audioDirectory);
            Files.createDirectories(videoDirectory);
            AppSettings updated = new AppSettings(
                    selectedAudio, selectedVideo, selectedFavoriteAudio, selectedFavoriteVideo,
                    audioDirectory, videoDirectory);
            persistSettings(updated);
            appSettings = updated;
            applyFormatSettings();
            appendLog("Configuration saved to " + settingsFile, LogKind.SUCCESS);
            settingsStage.hide();
        } catch (IOException | RuntimeException failure) {
            appendLog("Unable to save configuration: " + failure.getMessage(), LogKind.ERROR);
        }
    }

    private static String normalizeFavoriteSelection(String displayValue, List<String> enabledFormats,
                                                     String kind) {
        if (displayValue == null || displayValue.isBlank()) {
            throw new IllegalArgumentException("Select a favorite " + kind + " filetype");
        }
        String normalized = displayValue.toLowerCase(Locale.ROOT);
        if (!enabledFormats.contains(normalized)) {
            throw new IllegalArgumentException("Favorite " + kind + " filetype must be enabled");
        }
        return normalized;
    }

    private static List<String> selectedFormats(List<CheckBox> checks) {
        return checks.stream()
                .filter(CheckBox::isSelected)
                .map(check -> check.getUserData().toString())
                .toList();
    }

    private static Path normalizeConfiguredDirectory(String value, String kind) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The " + kind + " download location cannot be empty");
        }
        return Path.of(value.trim()).toAbsolutePath().normalize();
    }

    private void applyFormatSettings() {
        updateFormatSelector(audioFormatSelector, appSettings.audioFormats(), appSettings.favoriteAudioFormat());
        updateFormatSelector(videoFormatSelector, appSettings.videoFormats(), appSettings.favoriteVideoFormat());
    }

    private static void updateFormatSelector(ComboBox<String> selector, List<String> formats,
                                             String favoriteFormat) {
        if (selector == null) {
            return;
        }
        String previous = selector.getValue();
        List<String> displayValues = formats.stream().map(Main::formatDisplayName).toList();
        selector.setItems(FXCollections.observableArrayList(displayValues));
        selector.setValue(displayValues.contains(previous)
                ? previous : formatDisplayName(favoriteFormat));
    }

    private static Label settingsSectionLabel(String text, String color) {
        Label label = new Label(text);
        label.setTextFill(Color.web(color));
        label.setFont(Font.font("Monospaced", FontWeight.EXTRA_BOLD, 13));
        return label;
    }

    private static Path resolveApplicationDataDirectory() {
        Path directory;
        if (isPackagedApplication()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            directory = localAppData == null || localAppData.isBlank()
                    ? Path.of(System.getProperty("user.home"), "AppData", "Local", "iNTAKE")
                    : Path.of(localAppData, "iNTAKE");
        } else {
            directory = Path.of("").toAbsolutePath().normalize();
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            System.err.println("Unable to create iNTAKE data directory " + directory + ": " + failure);
        }
        return directory.toAbsolutePath().normalize();
    }

    private static boolean isPackagedApplication() {
        String applicationPath = System.getProperty("jpackage.app-path");
        return applicationPath != null && !applicationPath.isBlank();
    }

    private static Optional<Path> packagedToolsDirectory() {
        String applicationPath = System.getProperty("jpackage.app-path");
        if (applicationPath == null || applicationPath.isBlank()) {
            return Optional.empty();
        }
        Path launcher = Path.of(applicationPath).toAbsolutePath().normalize();
        Path imageRoot = launcher.getParent();
        if (imageRoot == null) {
            return Optional.empty();
        }
        Path tools = imageRoot.resolve("app").resolve("tools");
        return Files.isDirectory(tools) ? Optional.of(tools) : Optional.empty();
    }

    private static String resolveYtDlpExecutable() {
        return packagedToolsDirectory()
                .map(directory -> directory.resolve("yt-dlp.exe"))
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .orElse("yt-dlp");
    }

    private static ProcessBuilder createToolProcessBuilder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(APPLICATION_DATA_DIRECTORY.toFile());
        packagedToolsDirectory().ifPresent(directory -> {
            String existingPath = Objects.requireNonNullElse(builder.environment().get("PATH"), "");
            builder.environment().put("PATH", directory + java.io.File.pathSeparator + existingPath);
        });
        return builder;
    }

    private void registerPackagedNativeMessagingHost() {
        Optional<Path> toolsDirectory = packagedToolsDirectory();
        if (toolsDirectory.isEmpty()) {
            return;
        }
        Path nativeHost = toolsDirectory.get().resolve("iNTAKE.NativeHost.exe");
        if (!Files.isRegularFile(nativeHost)) {
            appendLog("Packaged browser wake host is missing: " + nativeHost, LogKind.WARNING);
            return;
        }

        try {
            Path manifest = APPLICATION_DATA_DIRECTORY.resolve(NATIVE_HOST_NAME + ".json");
            String manifestJson = "{\n"
                    + "  \"name\": \"" + NATIVE_HOST_NAME + "\",\n"
                    + "  \"description\": \"Starts iNTAKE and forwards browser downloads.\",\n"
                    + "  \"path\": \"" + jsonEscape(nativeHost.toString()) + "\",\n"
                    + "  \"type\": \"stdio\",\n"
                    + "  \"allowed_origins\": [\"chrome-extension://" + EXTENSION_ID + "/\"]\n"
                    + "}\n";
            Files.writeString(manifest, manifestJson, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            for (String browser : List.of("Google\\Chrome", "Microsoft\\Edge")) {
                String registryKey = "HKCU\\Software\\" + browser
                        + "\\NativeMessagingHosts\\" + NATIVE_HOST_NAME;
                Process registration = new ProcessBuilder("reg.exe", "add", registryKey,
                        "/ve", "/t", "REG_SZ", "/d", manifest.toString(), "/f")
                        .redirectErrorStream(true)
                        .start();
                if (registration.waitFor() != 0) {
                    appendLog("Could not register the browser wake host for " + browser + ".",
                            LogKind.WARNING);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            appendLog("Browser wake host registration was interrupted.", LogKind.WARNING);
        } catch (IOException | RuntimeException failure) {
            appendLog("Browser wake host registration failed: " + failure.getMessage(), LogKind.WARNING);
        }
    }

    private void startExtensionBridge() {
        try {
            HttpServer bridge = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), EXTENSION_BRIDGE_PORT), 0);
            bridge.createContext("/health", this::handleExtensionHealthRequest);
            bridge.createContext("/enqueue", this::handleExtensionEnqueueRequest);
            bridge.createContext("/status", this::handleExtensionStatusRequest);
            bridge.setExecutor(background);
            bridge.start();
            extensionBridge = bridge;
            appendLog("Browser bridge online at 127.0.0.1:" + EXTENSION_BRIDGE_PORT, LogKind.SYSTEM);
        } catch (IOException failure) {
            appendLog("Browser bridge unavailable: " + failure.getMessage(), LogKind.ERROR);
        }
    }

    private void handleExtensionHealthRequest(HttpExchange exchange) throws IOException {
        addExtensionCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendBridgeResponse(exchange, 204, "");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendBridgeResponse(exchange, 405, "{\"ok\":false,\"error\":\"Method not allowed\"}");
            return;
        }
        sendBridgeResponse(exchange, 200, "{\"ok\":true,\"service\":\"iNTAKE\"}");
    }

    private void handleExtensionEnqueueRequest(HttpExchange exchange) throws IOException {
        addExtensionCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendBridgeResponse(exchange, 204, "");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendBridgeResponse(exchange, 405, "{\"ok\":false,\"error\":\"Method not allowed\"}");
            return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String extensionMarker = exchange.getRequestHeaders().getFirst("X-iNTAKE-Extension");
        if (!"1".equals(extensionMarker)
                || (origin != null && !origin.startsWith("chrome-extension://"))) {
            sendBridgeResponse(exchange, 403, "{\"ok\":false,\"error\":\"Untrusted request origin\"}");
            return;
        }

        byte[] body = exchange.getRequestBody().readNBytes(MAX_EXTENSION_REQUEST_BYTES + 1);
        if (body.length > MAX_EXTENSION_REQUEST_BYTES) {
            sendBridgeResponse(exchange, 413, "{\"ok\":false,\"error\":\"Request too large\"}");
            return;
        }
        try {
            HashMap<String, String> form = parseFormBody(new String(body, StandardCharsets.UTF_8));
            URI mediaUri = URI.create(Objects.requireNonNullElse(form.get("url"), ""));
            if (!("http".equalsIgnoreCase(mediaUri.getScheme())
                    || "https".equalsIgnoreCase(mediaUri.getScheme()))) {
                throw new IllegalArgumentException("Only HTTP and HTTPS media URLs are accepted");
            }
            DownloadMode mode = switch (Objects.requireNonNullElse(form.get("mode"), "video")
                    .toLowerCase(Locale.ROOT)) {
                case "audio" -> DownloadMode.AUDIO;
                case "video" -> DownloadMode.VIDEO;
                default -> throw new IllegalArgumentException("Mode must be audio or video");
            };
            String requestedName = validateExtensionFileName(form.get("name"));
            DownloadItem item = enqueueDownload(
                    createExtensionDownloadArguments(mode, mediaUri.toString(), requestedName));
            String jobId = UUID.randomUUID().toString();
            extensionJobs.put(jobId, item);
            appendLog("Browser extension queued " + mode.label().toLowerCase(Locale.ROOT)
                    + ": " + mediaUri, LogKind.SYSTEM);
            sendBridgeResponse(exchange, 202, "{\"ok\":true,\"queued\":true,\"jobId\":\""
                    + jobId + "\"}");
        } catch (RuntimeException failure) {
            sendBridgeResponse(exchange, 400, "{\"ok\":false,\"error\":\""
                    + jsonEscape(Objects.requireNonNullElse(failure.getMessage(), "Invalid request")) + "\"}");
        }
    }

    private void handleExtensionStatusRequest(HttpExchange exchange) throws IOException {
        addExtensionCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendBridgeResponse(exchange, 204, "");
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendBridgeResponse(exchange, 405, "{\"ok\":false,\"error\":\"Method not allowed\"}");
            return;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String extensionMarker = exchange.getRequestHeaders().getFirst("X-iNTAKE-Extension");
        if (!"1".equals(extensionMarker)
                || (origin != null && !origin.startsWith("chrome-extension://"))) {
            sendBridgeResponse(exchange, 403, "{\"ok\":false,\"error\":\"Untrusted request origin\"}");
            return;
        }

        HashMap<String, String> query = parseFormBody(
                Objects.requireNonNullElse(exchange.getRequestURI().getRawQuery(), ""));
        String jobId = Objects.requireNonNullElse(query.get("id"), "");
        DownloadItem item = extensionJobs.get(jobId);
        if (item == null) {
            sendBridgeResponse(exchange, 404, "{\"ok\":false,\"error\":\"Unknown download job\"}");
            return;
        }

        DownloadStatus status = item.getStatusSnapshot();
        String response = "{\"ok\":true,\"jobId\":\"" + jsonEscape(jobId)
                + "\",\"status\":\"" + status.name().toLowerCase(Locale.ROOT)
                + "\",\"progress\":\"" + jsonEscape(item.getProgressSnapshot())
                + "\",\"title\":\"" + jsonEscape(item.getNameSnapshot())
                + "\",\"error\":\"" + jsonEscape(item.getErrorSnapshot()) + "\"}";
        sendBridgeResponse(exchange, 200, response);
    }

    private List<String> createExtensionDownloadArguments(DownloadMode mode, String mediaUrl,
                                                          String requestedName) {
        AppSettings settings = appSettings;
        List<String> arguments = new ArrayList<>();
        if (mode == DownloadMode.AUDIO) {
            String format = settings.favoriteAudioFormat();
            arguments.addAll(List.of(
                    "--extract-audio", "--audio-format", format,
                    "--embed-thumbnail", "--add-metadata"));
            arguments.add("--paths");
            arguments.add(settings.audioOutputDirectory().toString());
        } else {
            String format = settings.favoriteVideoFormat();
            arguments.addAll(List.of(
                    "--format", "bestvideo*+bestaudio/best",
                    "--merge-output-format", format,
                    "--remux-video", format));
            arguments.add("--paths");
            arguments.add(settings.videoOutputDirectory().toString());
        }
        if (requestedName != null) {
            arguments.add("--output");
            arguments.add(requestedName + ".%(ext)s");
        }
        arguments.add("--no-playlist");
        arguments.add(mediaUrl);
        return arguments;
    }

    private static String validateExtensionFileName(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return null;
        }
        String trimmed = requestedName.trim();
        if (trimmed.length() > 120 || !trimmed.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Requested filename contains unsupported characters");
        }
        return trimmed;
    }

    private static HashMap<String, String> parseFormBody(String body) {
        HashMap<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void addExtensionCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && origin.startsWith("chrome-extension://")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-iNTAKE-Extension");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static void sendBridgeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : payload.length);
        if (status != 204) {
            exchange.getResponseBody().write(payload);
        }
        exchange.close();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private Button createWindowControlButton(String text) {
        Button button = new Button(text);
        button.setMinSize(36, 28);
        button.setPrefSize(36, 28);
        button.setMaxSize(36, 28);
        button.setFont(Font.font("Monospaced", FontWeight.EXTRA_BOLD, 12));
        button.setStyle(windowControlButtonStyle("transparent"));
        button.setOnMouseEntered(event -> button.setStyle(windowControlButtonStyle(MAGENTA)));
        button.setOnMouseExited(event -> button.setStyle(windowControlButtonStyle("transparent")));
        return button;
    }

    private Polygon createControlPanelPolygon() {
        return new Polygon(
                0.0, 43.0,
                60.0, 2.0,
                204.0, 2.0,
                264.0, 43.0);
    }

    private VBox createDownloadInputBar() {
        TextField linkInput = new TextField();
        linkInput.setPromptText("PASTE MEDIA LINK // https://...");
        linkInput.setMaxWidth(Double.MAX_VALUE);
        linkInput.setStyle("-fx-background-color: #080a0f; -fx-text-fill: " + TEXT
                + "; -fx-prompt-text-fill: #657786; -fx-border-color: " + CYAN
                + "; -fx-border-width: 1 1 2 1; -fx-background-radius: 0; -fx-border-radius: 0;"
                + " -fx-font-family: 'Monospaced'; -fx-padding: 9;");
        HBox.setHgrow(linkInput, Priority.ALWAYS);

        ToggleButton playlistSelectionToggle = new ToggleButton("PLAYLIST: IGNORE");
        playlistSelectionToggle.setSelected(false);
        playlistSelectionToggle.setMinWidth(175);
        playlistSelectionToggle.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
        playlistSelectionToggle.setStyle(playlistToggleStyle(false));

        TextField playlistItems = new TextField();
        playlistItems.setPromptText("VIDEO ITEMS // 1,3,5-8");
        playlistItems.setMaxWidth(Double.MAX_VALUE);
        playlistItems.setStyle("-fx-background-color: #080a0f; -fx-text-fill: " + TEXT
                + "; -fx-prompt-text-fill: #657786; -fx-border-color: " + MAGENTA
                + "; -fx-border-width: 1 1 2 1; -fx-background-radius: 0; -fx-border-radius: 0;"
                + " -fx-font-family: 'Monospaced'; -fx-padding: 7;");
        HBox.setHgrow(playlistItems, Priority.ALWAYS);

        HBox playlistItemsRow = new HBox(8, playlistItems);
        playlistItemsRow.setAlignment(Pos.CENTER_LEFT);
        playlistItemsRow.setVisible(false);
        playlistItemsRow.setManaged(false);

        playlistSelectionToggle.setOnAction(event -> {
            boolean selectingItems = playlistSelectionToggle.isSelected();
            playlistSelectionToggle.setText(selectingItems
                    ? "PLAYLIST: SELECT VIDEOS" : "PLAYLIST: IGNORE");
            playlistSelectionToggle.setStyle(playlistToggleStyle(selectingItems));
            playlistItemsRow.setManaged(selectingItems);
            playlistItemsRow.setVisible(selectingItems);
            if (selectingItems) {
                playlistItems.requestFocus();
            }
        });

        audioFormatSelector = createFormatSelector(displayFormats(appSettings.audioFormats()));
        audioFormatSelector.setValue(formatDisplayName(appSettings.favoriteAudioFormat()));
        Button audioButton = new Button("DOWNLOAD AUDIO");
        configureDownloadButton(audioButton);
        audioButton.setOnAction(event -> submitManualDownload(linkInput, DownloadMode.AUDIO,
                audioFormatSelector.getValue(), playlistSelectionToggle, playlistItems));

        videoFormatSelector = createFormatSelector(displayFormats(appSettings.videoFormats()));
        videoFormatSelector.setValue(formatDisplayName(appSettings.favoriteVideoFormat()));
        Button videoButton = new Button("DOWNLOAD VIDEO");
        configureDownloadButton(videoButton);
        videoButton.setOnAction(event -> submitManualDownload(linkInput, DownloadMode.VIDEO,
                videoFormatSelector.getValue(), playlistSelectionToggle, playlistItems));
        linkInput.setOnAction(event -> submitManualDownload(linkInput, DownloadMode.VIDEO,
                videoFormatSelector.getValue(), playlistSelectionToggle, playlistItems));

        HBox linkRow = new HBox(8, linkInput);
        linkRow.setAlignment(Pos.CENTER_LEFT);
        HBox actionRow = new HBox(8, playlistSelectionToggle, audioFormatSelector, audioButton,
                videoFormatSelector, videoButton);
        actionRow.setAlignment(Pos.CENTER_RIGHT);

        VBox inputPanel = new VBox(8, linkRow, playlistItemsRow, actionRow);
        inputPanel.setPadding(new Insets(10));
        inputPanel.setStyle("-fx-background-color: #101620; -fx-border-color: " + CYAN
                + " transparent " + YELLOW + " transparent; -fx-border-width: 1 0 1 4;");
        return inputPanel;
    }

    private ComboBox<String> createFormatSelector(String... formats) {
        ComboBox<String> selector = new ComboBox<>(FXCollections.observableArrayList(formats));
        selector.setEditable(false);
        selector.setPrefWidth(100);
        selector.setStyle("-fx-background-color: #080a0f; -fx-border-color: " + YELLOW
                + "; -fx-border-width: 1; -fx-background-radius: 0; -fx-border-radius: 0;"
                + " -fx-font-family: 'Monospaced';");
        return selector;
    }

    private void configureDownloadButton(Button button) {
        button.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
        button.setMinWidth(150);
        button.setStyle(primaryButtonStyle());
    }

    private void submitManualDownload(TextField linkInput, DownloadMode mode, String selectedFormat,
                                      ToggleButton playlistSelectionToggle, TextField playlistItems) {
        String link = linkInput.getText() == null ? "" : linkInput.getText().trim();
        if (link.isEmpty()) {
            appendLog("A media link is required before a download can be queued.", LogKind.WARNING);
            linkInput.requestFocus();
            return;
        }

        String normalizedFormat = Objects.requireNonNullElse(selectedFormat, mode.defaultFormat())
                .toLowerCase(Locale.ROOT);
        String selectedItems = playlistItems.getText() == null ? "" : playlistItems.getText().trim();
        if (playlistSelectionToggle.isSelected()
                && (selectedItems.isEmpty() || !selectedItems.matches("(?=.*\\d)[0-9,:+\\-\\s]+"))) {
            appendLog("Enter playlist item numbers or ranges, for example: 1,3,5-8.", LogKind.WARNING);
            playlistItems.requestFocus();
            return;
        }

        List<String> arguments = new ArrayList<>();
        if (mode == DownloadMode.AUDIO) {
            arguments.addAll(List.of(
                    "--extract-audio",
                    "--audio-format", normalizedFormat,
                    "--embed-thumbnail",
                    "--add-metadata"));
        } else {
            arguments.addAll(List.of(
                    "--format", "bestvideo*+bestaudio/best",
                    "--merge-output-format", normalizedFormat,
                    "--remux-video", normalizedFormat));
        }

        if (playlistSelectionToggle.isSelected()) {
            arguments.add("--yes-playlist");
            arguments.add("--playlist-items");
            arguments.add(selectedItems.replaceAll("\\s+", ""));
        } else {
            arguments.add("--no-playlist");
        }
        Path outputDirectory = mode == DownloadMode.AUDIO
                ? appSettings.audioOutputDirectory() : appSettings.videoOutputDirectory();
        arguments.add("--paths");
        arguments.add(outputDirectory.toString());
        arguments.add(link);

        enqueueDownload(arguments);
        linkInput.clear();
        if (playlistSelectionToggle.isSelected()) {
            playlistItems.clear();
        }
        String playlistSummary = playlistSelectionToggle.isSelected()
                ? " Playlist items: " + selectedItems + "." : " Playlist ignored.";
        appendLog(mode.label() + " request queued as " + normalizedFormat.toUpperCase(Locale.ROOT) + "."
                + playlistSummary, LogKind.SYSTEM);
    }

    private TableView<DownloadItem> createDownloadTable() {
        TableView<DownloadItem> table = new TableView<>(downloads);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(neonLabel("NO DOWNLOAD SIGNALS DETECTED", CYAN));
        table.setStyle("-fx-background-color: " + PANEL + "; -fx-control-inner-background: " + PANEL
                + "; -fx-control-inner-background-alt: #0d121b; -fx-table-cell-border-color: #253443;"
                + " -fx-selection-bar: #1d5862; -fx-selection-bar-non-focused: #17343a;"
                + " -fx-border-color: " + YELLOW + "; -fx-border-width: 2;");

        TableColumn<DownloadItem, String> name = new TableColumn<>("Download Name");
        name.setCellValueFactory(data -> data.getValue().nameProperty());
        name.setPrefWidth(230);

        TableColumn<DownloadItem, String> status = new TableColumn<>("Download Status");
        status.setCellValueFactory(data -> Bindings.createStringBinding(
                data.getValue()::getDisplayStatus,
                data.getValue().statusProperty(),
                data.getValue().progressProperty()));
        status.setPrefWidth(135);
        status.setCellFactory(column -> new StatusTextCell());

        TableColumn<DownloadItem, String> size = new TableColumn<>("File Size");
        size.setCellValueFactory(data -> data.getValue().fileSizeProperty());
        size.setPrefWidth(110);

        TableColumn<DownloadItem, String> location = new TableColumn<>("File Location");
        location.setCellValueFactory(data -> data.getValue().fileLocationProperty());
        location.setMinWidth(220);
        location.prefWidthProperty().bind(Bindings.max(
                220.0,
                table.widthProperty().subtract(479.0)));

        for (TableColumn<DownloadItem, ?> column : List.of(name, status, size, location)) {
            column.setReorderable(false);
            column.setResizable(false);
        }
        name.setCellFactory(unused -> new NeonStringCell());
        size.setCellFactory(unused -> new NeonStringCell());
        location.setCellFactory(unused -> new FileLocationCell());
        table.getColumns().add(name);
        table.getColumns().add(status);
        table.getColumns().add(size);
        table.getColumns().add(location);
        return table;
    }

    private HBox createControlBar() {
        pauseButton = new Button("PAUSE WORKERS");
        pauseButton.setFont(Font.font("Monospaced", FontWeight.BOLD, 13));
        pauseButton.setStyle(primaryButtonStyle());
        pauseButton.setOnAction(event -> togglePause());

        stateLabel = neonLabel("SYSTEMS: ONLINE", CYAN);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(12, pauseButton, spacer, stateLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(9));
        bar.setStyle("-fx-background-color: #101620; -fx-border-color: " + CYAN
                + " transparent transparent transparent; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private void togglePause() {
        boolean shouldPause = !paused.get();
        paused.set(shouldPause);
        if (shouldPause) {
            pauseButton.setText("START WORKERS");
            pauseButton.setStyle(warningButtonStyle());
            stateLabel.setText("SYSTEMS: SUSPENDED");
            stateLabel.setTextFill(Color.web(MAGENTA));
            DownloadItem item = activeItem;
            if (item != null && item.getStatus() == DownloadStatus.DOWNLOADING) {
                item.setStatus(DownloadStatus.PAUSED);
            }
            appendLog("Worker gate paused. Process output consumption is suspended.", LogKind.WARNING);
        } else {
            pauseButton.setText("PAUSE WORKERS");
            pauseButton.setStyle(primaryButtonStyle());
            stateLabel.setText("SYSTEMS: ONLINE");
            stateLabel.setTextFill(Color.web(CYAN));
            DownloadItem item = activeItem;
            if (item != null && item.getStatus() == DownloadStatus.PAUSED) {
                item.setStatus(DownloadStatus.DOWNLOADING);
            }
            resumeWorkers();
            appendLog("Worker gate resumed.", LogKind.SYSTEM);
        }
    }

    private DownloadItem enqueueDownload(List<String> rawArguments) {
        Objects.requireNonNull(rawArguments, "ytDlpArguments");
        List<String> arguments = rawArguments.stream()
                .map(argument -> Objects.requireNonNull(argument, "yt-dlp argument"))
                .toList();
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("At least one yt-dlp argument is required");
        }
        if (shuttingDown.get()) {
            throw new IllegalStateException("iNTAKE is shutting down");
        }

        DownloadItem item = createDownloadItem(deriveDisplayName(arguments));
        outstandingDownloads.incrementAndGet();
        commandQueue.add(new DownloadCommand(arguments, item));
        appendLog("Queued download: " + item.getName(), LogKind.SYSTEM);
        return item;
    }

    private DownloadItem createDownloadItem(String displayName) {
        if (Platform.isFxApplicationThread()) {
            DownloadItem item = new DownloadItem(displayName, DownloadStatus.QUEUED, "—",
                    Path.of("").toAbsolutePath().normalize().toString());
            downloads.add(item);
            return item;
        }
        CompletableFuture<DownloadItem> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DownloadItem item = new DownloadItem(displayName, DownloadStatus.QUEUED, "—",
                        Path.of("").toAbsolutePath().normalize().toString());
                downloads.add(item);
                result.complete(item);
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating a download item", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Could not create a download item", failure.getCause());
        }
    }

    private void loadDownloadHistory() {
        if (!Files.isRegularFile(historyFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - MAX_HISTORY_ENTRIES);
            for (int index = start; index < lines.size(); index++) {
                try {
                    HistoryEntry entry = HistoryEntry.decode(lines.get(index));
                    downloads.add(new DownloadItem(entry.name(), DownloadStatus.COMPLETED,
                            entry.fileSize(), entry.fileLocation()));
                } catch (RuntimeException malformedEntry) {
                    System.err.println("Skipping malformed history entry " + (index + 1) + ": "
                            + malformedEntry.getMessage());
                }
            }
        } catch (IOException failure) {
            System.err.println("Unable to load download history " + historyFile + ": " + failure);
        }
    }

    private void markDownloadCompletedAndPersist(DownloadItem item) {
        CompletableFuture<HistoryEntry> snapshot = new CompletableFuture<>();
        runOnFx(() -> {
            item.setProgress("100%");
            item.setStatus(DownloadStatus.COMPLETED);
            snapshot.complete(HistoryEntry.from(item));
        });
        try {
            HistoryEntry entry = snapshot.get();
            synchronized (historyFileLock) {
                Files.writeString(historyFile, entry.encode() + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            appendLog("Download completed, but history persistence was interrupted.", LogKind.WARNING);
        } catch (ExecutionException | IOException failure) {
            appendLog("Download completed, but history could not be saved: " + failure.getMessage(),
                    LogKind.WARNING);
        }
    }

    private void coordinateCommands() {
        try {
            // Updating and executing the same yt-dlp binary concurrently is unsafe on
            // platforms that lock executables. The coordinator sleeps here until the
            // asynchronous launch check completes; no polling or CPU spin is involved.
            updateFinished.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        while (!shuttingDown.get()) {
            try {
                DownloadCommand command = commandQueue.take();
                awaitWorkerGate();
                executeDownload(command);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable failure) {
                reportFailure("Coordinator failure", failure, activeItem);
            }
        }
    }

    private void executeDownload(DownloadCommand command) {
        DownloadItem item = command.item();
        activeItem = item;
        runOnFx(() -> item.setStatus(DownloadStatus.DOWNLOADING));

        List<String> processArguments = new ArrayList<>();
        processArguments.add(resolveYtDlpExecutable());
        processArguments.add("--newline");
        processArguments.add("--no-color");
        processArguments.addAll(command.arguments());

        try {
            ProcessAttempt attempt = runDownloadAttempt(processArguments, item, "YT-DLP");
            StringBuilder completeOutput = new StringBuilder(attempt.output());

            if (attempt.exitCode() != 0 && requiresGenericImpersonation(attempt.output())
                    && !containsGenericImpersonation(processArguments)) {
                appendLog("Cloudflare challenge detected. Retrying once with generic browser impersonation.",
                        LogKind.WARNING);
                List<String> retryArguments = new ArrayList<>(processArguments);
                retryArguments.add(3, "--extractor-args");
                retryArguments.add(4, "generic:impersonate");

                ProcessAttempt retry = runDownloadAttempt(retryArguments, item, "YT-DLP RETRY");
                completeOutput.append(System.lineSeparator())
                        .append("=== GENERIC IMPERSONATION RETRY ===")
                        .append(System.lineSeparator())
                        .append(retry.output());
                attempt = retry;
            }

            if (attempt.exitCode() != 0) {
                throw new DownloadProcessException(attempt.exitCode(), completeOutput.toString());
            }
            markDownloadCompletedAndPersist(item);
            appendLog("Completed: " + item.getName(), LogKind.SUCCESS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reportFailure("Download interrupted", interrupted, item);
        } catch (Throwable failure) {
            reportFailure("Download failed", unwrapCompletionFailure(failure), item);
        } finally {
            activeProcess = null;
            activeItem = null;
            outstandingDownloads.decrementAndGet();
            evaluateExtensionExit();
        }
    }

    private ProcessAttempt runDownloadAttempt(List<String> processArguments, DownloadItem item,
                                              String logSource) throws IOException, InterruptedException {
        appendLog("Executing: " + printableCommand(processArguments), LogKind.SYSTEM);
        ProcessBuilder builder = createToolProcessBuilder(processArguments)
                .redirectErrorStream(true);
        Process process = builder.start();
        activeProcess = process;

        StringBuilder output = new StringBuilder(8_192);
        CompletableFuture<Void> outputReader = CompletableFuture.runAsync(
                () -> readProcessOutput(process, item, output, true, logSource), background);
        int exitCode = process.waitFor();
        outputReader.join();
        return new ProcessAttempt(exitCode, output.toString());
    }

    private static boolean requiresGenericImpersonation(String processOutput) {
        String normalized = processOutput.toLowerCase(Locale.ROOT);
        return normalized.contains("generic:impersonate")
                && (normalized.contains("cloudflare") || normalized.contains("anti-bot challenge"));
    }

    private static boolean containsGenericImpersonation(List<String> arguments) {
        return arguments.stream()
                .map(argument -> argument.toLowerCase(Locale.ROOT))
                .anyMatch(argument -> argument.contains("generic:impersonate"));
    }

    private void readProcessOutput(Process process, DownloadItem item, StringBuilder capture,
                                   boolean respectPause, String source) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (respectPause) {
                    awaitWorkerGate();
                }
                synchronized (capture) {
                    capture.append(line).append(System.lineSeparator());
                }
                appendLog("[" + source + "] " + line, line.contains("ERROR:") ? LogKind.ERROR : LogKind.NORMAL);
                if (item != null) {
                    parseDownloadOutput(line, item);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ioFailure) {
            if (!shuttingDown.get()) {
                throw new ProcessStreamException("Unable to read " + source + " output", ioFailure);
            }
        }
    }

    private void parseDownloadOutput(String line, DownloadItem item) {
        Matcher progress = PROGRESS_PATTERN.matcher(line);
        if (progress.matches()) {
            String size = progress.group("size");
            String percent = progress.group("percent") + "%";
            runOnFx(() -> {
                item.setFileSize(size);
                item.setProgress(percent);
                if (!paused.get()) {
                    item.setStatus(DownloadStatus.DOWNLOADING);
                }
            });
            return;
        }

        Optional<String> detectedPath = matchedGroup(DESTINATION_PATTERN, line, "path")
                .or(() -> matchedGroup(ALREADY_DOWNLOADED_PATTERN, line, "path"))
                .or(() -> matchedGroup(POST_PROCESS_DESTINATION_PATTERN, line, "path"))
                .or(() -> matchedGroup(MERGER_PATTERN, line, "path"));
        detectedPath.ifPresent(pathText -> {
            Path path = Path.of(stripSurroundingQuotes(pathText)).toAbsolutePath().normalize();
            runOnFx(() -> {
                item.setName(path.getFileName() == null ? path.toString() : path.getFileName().toString());
                item.setFileLocation(path.toString());
            });
        });
    }

    private void runUpdateCheck() {
        List<String> command = List.of(resolveYtDlpExecutable(), "--update-to", "nightly");
        StringBuilder output = new StringBuilder(2_048);
        try {
            appendLog("Checking yt-dlp nightly channel...", LogKind.SYSTEM);
            Process process = createToolProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            readProcessOutput(process, null, output, false, "UPDATE");
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new UpdateProcessException(exitCode, output.toString());
            }
            appendLog("yt-dlp nightly update check completed.", LogKind.SUCCESS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!shuttingDown.get()) {
                reportFailure("yt-dlp update interrupted", interrupted, null);
            }
        } catch (Throwable failure) {
            reportFailure("yt-dlp nightly update failed", unwrapCompletionFailure(failure), null);
        } finally {
            updateComplete.set(true);
            updateFinished.countDown();
            evaluateExtensionExit();
        }
    }

    private void reportFailure(String context, Throwable failure, DownloadItem item) {
        if (item != null) {
            item.setErrorSnapshot(Objects.requireNonNullElse(failure.getMessage(), context));
            runOnFx(() -> item.setStatus(DownloadStatus.ERROR));
        }
        StringWriter stackBuffer = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackBuffer));
        String exactStackTrace = stackBuffer.toString();
        appendLog(context + System.lineSeparator() + exactStackTrace, LogKind.ERROR);

        Path report = APPLICATION_DATA_DIRECTORY.resolve("failedError.txt");
        String entry = "=== " + Instant.now() + " | " + context + " ===" + System.lineSeparator()
                + exactStackTrace + System.lineSeparator();
        synchronized (failureFileLock) {
            try {
                Files.writeString(report, entry, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException logFailure) {
                appendLog("Unable to write failure report " + report + ": " + logFailure, LogKind.ERROR);
            }
        }
    }

    private void appendLog(String message, LogKind kind) {
        runOnFx(() -> {
            if (logFlow == null) {
                return;
            }
            Text timestamp = new Text("[" + LOG_TIME.format(Instant.now()) + "] ");
            timestamp.setFill(Color.web("#718096"));
            timestamp.setFont(Font.font("Monospaced", 12));

            Text content = new Text(message + System.lineSeparator());
            content.setFill(kind.color());
            content.setFont(Font.font("Monospaced", kind.bold() ? FontWeight.BOLD : FontWeight.NORMAL, 12));
            logFlow.getChildren().addAll(timestamp, content);
            int overflow = logFlow.getChildren().size() - MAX_LOG_NODES;
            if (overflow > 0) {
                logFlow.getChildren().remove(0, Math.min(overflow, logFlow.getChildren().size()));
            }
            logScrollPane.layout();
            logScrollPane.setVvalue(1.0);
        });
    }

    private boolean installSystemTray() {
        if (!SystemTray.isSupported()) {
            appendLog("System tray is unavailable. The main window will remain accessible.", LogKind.ERROR);
            return false;
        }
        try {
            EventQueue.invokeAndWait(() -> {
                java.awt.PopupMenu menu = new java.awt.PopupMenu();
                java.awt.MenuItem open = new java.awt.MenuItem("Open iNTAKE");
                java.awt.MenuItem exit = new java.awt.MenuItem("Exit");
                open.addActionListener(event -> Platform.runLater(this::showMainWindow));
                exit.addActionListener(event -> Platform.runLater(
                        () -> shutdownApplication("System tray Exit selected")));
                menu.add(open);
                menu.addSeparator();
                menu.add(exit);

                TrayIcon icon = new TrayIcon(createTrayImage(), "iNTAKE", menu);
                icon.setImageAutoSize(true);
                icon.addActionListener(event -> Platform.runLater(this::showMainWindow));
                try {
                    SystemTray.getSystemTray().add(icon);
                    trayIcon = icon;
                } catch (AWTException failure) {
                    throw new TrayInstallationException(failure);
                }
            });
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reportFailure("System tray installation interrupted", interrupted, null);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            reportFailure("System tray installation failed", unwrapCompletionFailure(failure.getCause()), null);
        } catch (TrayInstallationException failure) {
            reportFailure("System tray installation failed", failure.getCause(), null);
        }
        return false;
    }

    private BufferedImage createTrayImage() {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(java.awt.Color.decode(BACKGROUND));
            graphics.fillRect(0, 0, size, size);
            graphics.setColor(java.awt.Color.decode(YELLOW));
            graphics.fillPolygon(new int[]{8, 56, 48, 16}, new int[]{8, 8, 56, 56}, 4);
            graphics.setColor(java.awt.Color.decode(BACKGROUND));
            graphics.fillPolygon(new int[]{16, 48, 42, 22}, new int[]{16, 16, 48, 48}, 4);
            graphics.setColor(java.awt.Color.decode(CYAN));
            graphics.fillRect(28, 19, 8, 26);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void removeTrayIcon() {
        TrayIcon icon = trayIcon;
        trayIcon = null;
        if (icon != null && SystemTray.isSupported()) {
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(icon));
        }
    }

    private void showMainWindow() {
        if (shuttingDown.get()) {
            return;
        }
        if (!primaryStage.isShowing()) {
            primaryStage.show();
        }
        primaryStage.toFront();
        primaryStage.requestFocus();
        Platform.runLater(this::styleInterfaceSkins);
    }

    private void hideApplicationWindow(String reason) {
        if (shuttingDown.get()) {
            return;
        }
        if (trayIcon == null) {
            // Do not make the process unreachable on systems without a tray.
            primaryStage.setIconified(true);
            appendLog(reason + ". System tray unavailable; window minimized.", LogKind.WARNING);
            return;
        }
        primaryStage.hide();
        appendLog(reason + ". iNTAKE remains active in the system tray.", LogKind.SYSTEM);
    }

    private void styleInterfaceSkins() {
        styleTableSkin();
        styleLogConsoleScrollBar();
    }

    private void styleTableSkin() {
        if (downloadTable == null) {
            return;
        }
        downloadTable.applyCss();
        downloadTable.lookupAll(".column-header").forEach(node -> node.setStyle(
                "-fx-background-color: #161e29; -fx-border-color: transparent #314252 " + CYAN
                        + " transparent; -fx-border-width: 0 1 1 0;"));
        downloadTable.lookupAll(".column-header .label").forEach(node -> node.setStyle(
                "-fx-text-fill: " + YELLOW + "; -fx-font-family: 'Monospaced'; -fx-font-weight: bold;"));
        downloadTable.lookupAll(".filler").forEach(node -> node.setStyle(
                "-fx-background-color: #161e29; -fx-border-color: transparent transparent " + CYAN
                        + " transparent; -fx-border-width: 0 0 1 0;"));
    }

    private void styleLogConsoleScrollBar() {
        if (logScrollPane == null) {
            return;
        }
        logScrollPane.applyCss();
        logScrollPane.lookupAll(".scroll-bar .track").forEach(node -> node.setStyle(
                "-fx-background-color: #080a0f; -fx-background-radius: 0;"
                        + " -fx-border-color: " + CYAN + "; -fx-border-width: 0 0 0 1;"));
        logScrollPane.lookupAll(".scroll-bar .thumb").forEach(node -> node.setStyle(
                "-fx-background-color: " + CYAN + "; -fx-background-insets: 2;"
                        + " -fx-background-radius: 0;"));
        logScrollPane.lookupAll(".scroll-bar .increment-button, .scroll-bar .decrement-button")
                .forEach(node -> node.setStyle(
                        "-fx-background-color: #101620; -fx-background-radius: 0;"
                                + " -fx-border-color: " + CYAN + "; -fx-border-width: 1 0 0 0;"));
        logScrollPane.lookupAll(".scroll-bar .increment-arrow, .scroll-bar .decrement-arrow")
                .forEach(node -> node.setStyle("-fx-background-color: " + CYAN + ";"));
        logScrollPane.lookupAll(".corner").forEach(node -> node.setStyle(
                "-fx-background-color: #080a0f;"));
    }

    private void openDownloadLocation(String location) {
        if (location == null || location.isBlank()) {
            return;
        }
        background.execute(() -> {
            try {
                Path target = Path.of(location).toAbsolutePath().normalize();
                Path directory = Files.isDirectory(target) ? target : target.getParent();
                while (directory != null && !Files.isDirectory(directory)) {
                    directory = directory.getParent();
                }
                if (directory == null) {
                    throw new IOException("No accessible parent directory exists for " + target);
                }
                if (!Desktop.isDesktopSupported()
                        || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    throw new IOException("Opening folders is not supported on this system");
                }
                Desktop.getDesktop().open(directory.toFile());
                appendLog("Opened download directory: " + directory, LogKind.SYSTEM);
            } catch (IOException | RuntimeException failure) {
                appendLog("Unable to open download location: " + failure.getMessage(), LogKind.ERROR);
            }
        });
    }

    private void evaluateExtensionExit() {
        if (extensionLaunch && initialCommandsSubmitted.get() && updateComplete.get()
                && outstandingDownloads.get() == 0 && commandQueue.isEmpty()) {
            Platform.runLater(() -> shutdownApplication("Extension queue completed"));
        }
    }

    private void shutdownApplication(String reason) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        appendLog(reason + ". Shutting down.", LogKind.SYSTEM);
        Platform.exit();
        System.exit(0);
    }

    private void awaitWorkerGate() throws InterruptedException {
        pauseLock.lockInterruptibly();
        try {
            while (paused.get() && !shuttingDown.get()) {
                unpaused.await();
            }
        } finally {
            pauseLock.unlock();
        }
    }

    private void resumeWorkers() {
        paused.set(false);
        pauseLock.lock();
        try {
            unpaused.signalAll();
        } finally {
            pauseLock.unlock();
        }
    }

    private static void runOnFx(Runnable operation) {
        if (Platform.isFxApplicationThread()) {
            operation.run();
        } else {
            Platform.runLater(operation);
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> failure.printStackTrace(System.err));
            return thread;
        };
    }

    private static boolean containsIgnoreCase(List<String> arguments, String sought) {
        return arguments.stream().anyMatch(sought::equalsIgnoreCase);
    }

    private static Optional<String> matchedGroup(Pattern pattern, String input, String group) {
        Matcher matcher = pattern.matcher(input);
        return matcher.matches() ? Optional.of(matcher.group(group)) : Optional.empty();
    }

    private static String stripSurroundingQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String deriveDisplayName(List<String> arguments) {
        for (int index = arguments.size() - 1; index >= 0; index--) {
            String candidate = arguments.get(index);
            if (candidate.startsWith("-")) {
                continue;
            }
            try {
                URI uri = URI.create(candidate);
                String path = uri.getPath();
                if (path != null && !path.isBlank() && !path.equals("/")) {
                    String tail = path.substring(path.lastIndexOf('/') + 1);
                    if (!tail.isBlank()) {
                        return tail;
                    }
                }
                if (uri.getHost() != null) {
                    return uri.getHost();
                }
            } catch (IllegalArgumentException ignored) {
                // The argument can still be a valid yt-dlp search expression or local path.
            }
            return candidate;
        }
        return "yt-dlp request";
    }

    private static String printableCommand(List<String> arguments) {
        return arguments.stream()
                .map(argument -> argument.chars().anyMatch(Character::isWhitespace)
                        ? "\"" + argument.replace("\"", "\\\"") + "\"" : argument)
                .reduce((left, right) -> left + " " + right)
                .orElse("yt-dlp");
    }

    private AppSettings loadSettings() {
        AppSettings defaults = AppSettings.defaults();
        if (!Files.isRegularFile(settingsFile)) {
            return defaults;
        }
        Properties properties = new Properties();
        try (var input = Files.newInputStream(settingsFile)) {
            properties.load(input);
            List<String> audioFormats = configuredFormats(
                    properties.getProperty("audio.formats"), SUPPORTED_AUDIO_FORMATS, DEFAULT_AUDIO_FORMATS);
            List<String> videoFormats = configuredFormats(
                    properties.getProperty("video.formats"), SUPPORTED_VIDEO_FORMATS, DEFAULT_VIDEO_FORMATS);
            String favoriteAudio = configuredFavorite(
                    properties.getProperty("audio.favorite"), audioFormats, "mp3");
            String favoriteVideo = configuredFavorite(
                    properties.getProperty("video.favorite"), videoFormats, "mp4");
            Path audioDirectory = configuredPath(
                    properties.getProperty("audio.outputDirectory"), defaults.audioOutputDirectory());
            Path videoDirectory = configuredPath(
                    properties.getProperty("video.outputDirectory"), defaults.videoOutputDirectory());
            return new AppSettings(audioFormats, videoFormats, favoriteAudio, favoriteVideo,
                    audioDirectory, videoDirectory);
        } catch (IOException | RuntimeException failure) {
            System.err.println("Unable to read " + settingsFile + "; using defaults: " + failure);
            return defaults;
        }
    }

    private void persistSettings(AppSettings settings) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("audio.formats", String.join(",", settings.audioFormats()));
        properties.setProperty("video.formats", String.join(",", settings.videoFormats()));
        properties.setProperty("audio.favorite", settings.favoriteAudioFormat());
        properties.setProperty("video.favorite", settings.favoriteVideoFormat());
        properties.setProperty("audio.outputDirectory", settings.audioOutputDirectory().toString());
        properties.setProperty("video.outputDirectory", settings.videoOutputDirectory().toString());
        try (OutputStream output = Files.newOutputStream(settingsFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(output, "iNTAKE persistent configuration");
        }
    }

    private static List<String> configuredFormats(String rawValue, List<String> supported,
                                                   List<String> defaults) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaults;
        }
        List<String> configured = new ArrayList<>();
        for (String token : rawValue.split(",")) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (supported.contains(normalized) && !configured.contains(normalized)) {
                configured.add(normalized);
            }
        }
        return configured.isEmpty() ? defaults : List.copyOf(configured);
    }

    private static Path configuredPath(String rawValue, Path fallback) {
        return rawValue == null || rawValue.isBlank()
                ? fallback : Path.of(rawValue.trim()).toAbsolutePath().normalize();
    }

    private static String configuredFavorite(String rawValue, List<String> enabledFormats,
                                             String preferredDefault) {
        String normalized = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
        if (enabledFormats.contains(normalized)) {
            return normalized;
        }
        return enabledFormats.contains(preferredDefault) ? preferredDefault : enabledFormats.get(0);
    }

    private static String[] displayFormats(List<String> formats) {
        return formats.stream().map(Main::formatDisplayName).toArray(String[]::new);
    }

    private static String formatDisplayName(String format) {
        String normalized = format.toLowerCase(Locale.ROOT);
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.lang.reflect.InvocationTargetException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Label titleLabel(String title, String color) {
        Label label = neonLabel(title, color);
        label.setFont(Font.font("Monospaced", FontWeight.EXTRA_BOLD, 16));
        label.setMaxWidth(Double.MAX_VALUE);
        label.setPadding(new Insets(6, 9, 6, 9));
        label.setStyle("-fx-background-color: #101620; -fx-border-color: " + color
                + "; -fx-border-width: 0 0 2 5;");
        return label;
    }

    private static Label neonLabel(String text, String color) {
        Label label = new Label(text);
        label.setTextFill(Color.web(color));
        label.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
        return label;
    }

    private static String primaryButtonStyle() {
        return "-fx-background-color: " + YELLOW + "; -fx-text-fill: #080a0f;"
                + " -fx-border-color: " + CYAN + "; -fx-border-width: 0 3 3 0;"
                + " -fx-background-radius: 0; -fx-border-radius: 0; -fx-cursor: hand;";
    }

    private static String warningButtonStyle() {
        return "-fx-background-color: " + MAGENTA + "; -fx-text-fill: white;"
                + " -fx-border-color: " + YELLOW + "; -fx-border-width: 0 3 3 0;"
                + " -fx-background-radius: 0; -fx-border-radius: 0; -fx-cursor: hand;";
    }

    private static String playlistToggleStyle(boolean selectingItems) {
        String background = selectingItems ? CYAN : YELLOW;
        return "-fx-background-color: " + background + "; -fx-text-fill: #080a0f;"
                + " -fx-border-color: " + (selectingItems ? MAGENTA : CYAN) + ";"
                + " -fx-border-width: 0 3 3 0; -fx-background-radius: 0; -fx-border-radius: 0;"
                + " -fx-cursor: hand; -fx-padding: 7 12 7 12;";
    }

    private static String windowControlButtonStyle(String backgroundColor) {
        return "-fx-background-color: " + backgroundColor + "; -fx-text-fill: " + CYAN + ";"
                + " -fx-border-color: transparent; -fx-border-width: 0;"
                + " -fx-background-radius: 0; -fx-border-radius: 0; -fx-cursor: hand;"
                + " -fx-padding: 4 12 4 12;";
    }

    private enum DownloadMode {
        AUDIO("Audio", "Mp3"),
        VIDEO("Video", "Mp4");

        private final String label;
        private final String defaultFormat;

        DownloadMode(String label, String defaultFormat) {
            this.label = label;
            this.defaultFormat = defaultFormat;
        }

        String label() { return label; }
        String defaultFormat() { return defaultFormat; }
    }

    public enum DownloadStatus {
        QUEUED("Queued"),
        DOWNLOADING("Downloading"),
        PAUSED("Paused"),
        COMPLETED("Completed"),
        ERROR("Error");

        private final String label;

        DownloadStatus(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class DownloadItem {
        private final StringProperty name;
        private final ObjectProperty<DownloadStatus> status;
        private final StringProperty progress;
        private final StringProperty fileSize;
        private final StringProperty fileLocation;
        private volatile String nameSnapshot;
        private volatile DownloadStatus statusSnapshot;
        private volatile String progressSnapshot;
        private volatile String errorSnapshot = "";

        public DownloadItem(String name, DownloadStatus status, String fileSize, String fileLocation) {
            this.name = new SimpleStringProperty(this, "name", Objects.requireNonNull(name));
            this.status = new SimpleObjectProperty<>(this, "status", Objects.requireNonNull(status));
            this.progress = new SimpleStringProperty(this, "progress", "0%");
            this.fileSize = new SimpleStringProperty(this, "fileSize", Objects.requireNonNull(fileSize));
            this.fileLocation = new SimpleStringProperty(this, "fileLocation", Objects.requireNonNull(fileLocation));
            nameSnapshot = name;
            statusSnapshot = status;
            progressSnapshot = "0%";
        }

        public String getName() { return name.get(); }
        public void setName(String value) {
            nameSnapshot = Objects.requireNonNull(value);
            name.set(value);
        }
        public StringProperty nameProperty() { return name; }
        public DownloadStatus getStatus() { return status.get(); }
        public void setStatus(DownloadStatus value) {
            statusSnapshot = Objects.requireNonNull(value);
            status.set(value);
        }
        public ObjectProperty<DownloadStatus> statusProperty() { return status; }
        public String getProgress() { return progress.get(); }
        public void setProgress(String value) {
            progressSnapshot = Objects.requireNonNull(value);
            progress.set(value);
        }
        public StringProperty progressProperty() { return progress; }
        public String getDisplayStatus() {
            return getStatus() == DownloadStatus.DOWNLOADING
                    ? getStatus() + " " + getProgress()
                    : getStatus().toString();
        }
        public String getFileSize() { return fileSize.get(); }
        public void setFileSize(String value) { fileSize.set(value); }
        public StringProperty fileSizeProperty() { return fileSize; }
        public String getFileLocation() { return fileLocation.get(); }
        public void setFileLocation(String value) { fileLocation.set(value); }
        public StringProperty fileLocationProperty() { return fileLocation; }
        public String getNameSnapshot() { return nameSnapshot; }
        public DownloadStatus getStatusSnapshot() { return statusSnapshot; }
        public String getProgressSnapshot() { return progressSnapshot; }
        public String getErrorSnapshot() { return errorSnapshot; }
        public void setErrorSnapshot(String value) { errorSnapshot = Objects.requireNonNull(value); }
    }

    private record DownloadCommand(List<String> arguments, DownloadItem item) {
        private DownloadCommand {
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(item);
        }
    }

    private record ProcessAttempt(int exitCode, String output) {
        private ProcessAttempt {
            Objects.requireNonNull(output);
        }
    }

    private record AppSettings(List<String> audioFormats, List<String> videoFormats,
                               String favoriteAudioFormat, String favoriteVideoFormat,
                               Path audioOutputDirectory, Path videoOutputDirectory) {
        private AppSettings {
            audioFormats = List.copyOf(audioFormats);
            videoFormats = List.copyOf(videoFormats);
            Objects.requireNonNull(favoriteAudioFormat);
            Objects.requireNonNull(favoriteVideoFormat);
            Objects.requireNonNull(audioOutputDirectory);
            Objects.requireNonNull(videoOutputDirectory);
            if (audioFormats.isEmpty() || videoFormats.isEmpty()) {
                throw new IllegalArgumentException("Audio and video format lists cannot be empty");
            }
            if (!audioFormats.contains(favoriteAudioFormat)
                    || !videoFormats.contains(favoriteVideoFormat)) {
                throw new IllegalArgumentException("Favorite formats must be enabled");
            }
        }

        private static AppSettings defaults() {
            Path workingDirectory = isPackagedApplication()
                    ? Path.of(System.getProperty("user.home"), "Downloads", "iNTAKE")
                    : Path.of("").toAbsolutePath().normalize();
            return new AppSettings(DEFAULT_AUDIO_FORMATS, DEFAULT_VIDEO_FORMATS, "mp3", "mp4",
                    workingDirectory, workingDirectory);
        }
    }

    private record HistoryEntry(Instant completedAt, String name, String fileSize, String fileLocation) {
        private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
        private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

        private HistoryEntry {
            Objects.requireNonNull(completedAt);
            Objects.requireNonNull(name);
            Objects.requireNonNull(fileSize);
            Objects.requireNonNull(fileLocation);
        }

        private static HistoryEntry from(DownloadItem item) {
            return new HistoryEntry(Instant.now(), item.getName(), item.getFileSize(), item.getFileLocation());
        }

        private String encode() {
            return completedAt + "\t" + encodeField(name) + "\t" + encodeField(fileSize)
                    + "\t" + encodeField(fileLocation);
        }

        private static HistoryEntry decode(String line) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 4) {
                throw new IllegalArgumentException("Expected four tab-separated fields");
            }
            return new HistoryEntry(Instant.parse(fields[0]), decodeField(fields[1]),
                    decodeField(fields[2]), decodeField(fields[3]));
        }

        private static String encodeField(String value) {
            return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String decodeField(String value) {
            return new String(DECODER.decode(value), StandardCharsets.UTF_8);
        }
    }

    private enum LogKind {
        NORMAL(TEXT, false),
        SYSTEM(CYAN, false),
        SUCCESS(YELLOW, true),
        WARNING(MAGENTA, true),
        ERROR(MAGENTA, false);

        private final Color color;
        private final boolean bold;

        LogKind(String color, boolean bold) {
            this.color = Color.web(color);
            this.bold = bold;
        }

        Color color() { return color; }
        boolean bold() { return bold; }
    }

    private static final class NeonStringCell extends TableCell<DownloadItem, String> {
        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            setText(empty ? null : value);
            setTextFill(Color.web(TEXT));
            setFont(Font.font("Monospaced", 12));
            setStyle("-fx-background-color: transparent; -fx-padding: 8;");
        }
    }

    private final class FileLocationCell extends TableCell<DownloadItem, String> {
        private final Label pathLabel = new Label();
        private final Button openButton = new Button("OPEN");
        private final HBox content = new HBox(8);

        private FileLocationCell() {
            pathLabel.setTextFill(Color.web(TEXT));
            pathLabel.setFont(Font.font("Monospaced", 12));
            pathLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(pathLabel, Priority.ALWAYS);

            openButton.setFont(Font.font("Monospaced", FontWeight.BOLD, 10));
            openButton.setMinSize(46, 22);
            openButton.setPrefSize(46, 22);
            openButton.setMaxSize(46, 22);
            openButton.setOpacity(0.0);
            openButton.setMouseTransparent(true);
            openButton.setStyle("-fx-background-color: transparent; -fx-text-fill: " + CYAN + ";"
                    + " -fx-border-color: " + CYAN + "; -fx-border-width: 1;"
                    + " -fx-background-radius: 0; -fx-border-radius: 0; -fx-cursor: hand;"
                    + " -fx-padding: 2 6 2 6;");
            openButton.setOnMouseEntered(event -> openButton.setStyle(
                    "-fx-background-color: " + CYAN + "; -fx-text-fill: #080a0f;"
                            + " -fx-border-color: " + CYAN + "; -fx-border-width: 1;"
                            + " -fx-background-radius: 0; -fx-border-radius: 0; -fx-cursor: hand;"
                            + " -fx-padding: 2 6 2 6;"));
            openButton.setOnMouseExited(event -> openButton.setStyle(
                    "-fx-background-color: transparent; -fx-text-fill: " + CYAN + ";"
                            + " -fx-border-color: " + CYAN + "; -fx-border-width: 1;"
                            + " -fx-background-radius: 0; -fx-border-radius: 0; -fx-cursor: hand;"
                            + " -fx-padding: 2 6 2 6;"));
            openButton.setOnAction(event -> openDownloadLocation(getItem()));

            content.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().addAll(pathLabel, openButton);
            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            setStyle("-fx-background-color: transparent; -fx-padding: 4 8 4 8;");
            setOnMouseEntered(event -> setOpenActionVisible(!isEmpty()));
            setOnMouseExited(event -> setOpenActionVisible(false));
        }

        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            if (empty || value == null || value.isBlank()) {
                pathLabel.setText(null);
                setTooltip(null);
                setGraphic(null);
                setOpenActionVisible(false);
                return;
            }
            pathLabel.setText(value);
            setTooltip(new Tooltip(value + System.lineSeparator() + "Hover, then select OPEN to reveal the folder."));
            setGraphic(content);
            setOpenActionVisible(isHover());
        }

        private void setOpenActionVisible(boolean visible) {
            openButton.setOpacity(visible ? 1.0 : 0.0);
            openButton.setMouseTransparent(!visible);
        }
    }

    private static final class StatusTextCell extends TableCell<DownloadItem, String> {
        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            setText(empty ? null : value);
            if (!empty && value != null) {
                setTextFill(value.startsWith(DownloadStatus.COMPLETED.toString())
                        ? Color.web(YELLOW)
                        : value.startsWith(DownloadStatus.ERROR.toString())
                                || value.startsWith(DownloadStatus.PAUSED.toString())
                                ? Color.web(MAGENTA) : Color.web(CYAN));
            }
            setFont(Font.font("Monospaced", FontWeight.BOLD, 12));
            setStyle("-fx-background-color: transparent; -fx-padding: 8;");
        }
    }

    private static class ExternalProcessException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ExternalProcessException(String operation, int exitCode, String output) {
            super(operation + " exited with code " + exitCode + System.lineSeparator()
                    + "Process output:" + System.lineSeparator() + output);
        }
    }

    private static final class DownloadProcessException extends ExternalProcessException {
        private static final long serialVersionUID = 1L;

        DownloadProcessException(int exitCode, String output) {
            super("yt-dlp download", exitCode, output);
        }
    }

    private static final class UpdateProcessException extends ExternalProcessException {
        private static final long serialVersionUID = 1L;

        UpdateProcessException(int exitCode, String output) {
            super("yt-dlp nightly update", exitCode, output);
        }
    }

    private static final class ProcessStreamException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ProcessStreamException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class TrayInstallationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TrayInstallationException(Throwable cause) { super(cause); }
    }
}
