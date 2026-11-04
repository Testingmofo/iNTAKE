/**
 * Neutral JVM entry point used by jpackage. Keeping the launcher separate from
 * the JavaFX Application subclass avoids the Java launcher's modular JavaFX
 * detection path when the release uses a bundled class path.
 */
public final class IntakeLauncher {
    private IntakeLauncher() {
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
