import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Starts the registered Windows Installer removal flow for an installed copy
 * of iNTAKE. The release builder exposes this class as a separate Start Menu
 * launcher; it never performs file deletion itself.
 */
public final class UninstallLauncher {
    private static final String UNINSTALL_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $registryPaths = @(
                'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
                'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',
                'HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'
            )
            $entry = Get-ItemProperty -Path $registryPaths -ErrorAction SilentlyContinue |
                Where-Object { $_.DisplayName -eq 'iNTAKE' -and $_.Publisher -eq 'iNSRT' } |
                Select-Object -First 1
            if (-not $entry) { exit 2 }
            $uninstallCommand = [string]$entry.UninstallString
            if ([string]::IsNullOrWhiteSpace($uninstallCommand)) { exit 3 }
            Get-Process -Name 'iNTAKE' -ErrorAction SilentlyContinue | Stop-Process -Force
            Start-Sleep -Milliseconds 900
            if ($uninstallCommand -match '(?i)msiexec(?:\\.exe)?\\s+/[ix]\\s*({[0-9a-f-]+})') {
                Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', $Matches[1])
            } else {
                Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/s', '/c', $uninstallCommand)
            }
            """;

    private UninstallLauncher() {
    }

    public static void main(String[] args) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            showMessage("The iNTAKE installer removal wizard is available only on Windows.",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("A graphical Windows session is required to uninstall iNTAKE.");
            return;
        }
        int choice = JOptionPane.showConfirmDialog(null,
                "Remove the installed copy of iNTAKE?\n\n"
                        + "The running tray application will close. Downloaded media and files in "
                        + "%LOCALAPPDATA%\\iNTAKE are not deleted.",
                "iNTAKE // Uninstall", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            String encoded = Base64.getEncoder().encodeToString(
                    UNINSTALL_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
                    "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .inheritIO()
                    .start();
            int exitCode = process.waitFor();
            if (exitCode == 2) {
                showMessage("No installed iNTAKE entry was found.\n\n"
                                + "If this is the portable edition, close iNTAKE and delete its "
                                + "extracted folder.", JOptionPane.INFORMATION_MESSAGE);
            } else if (exitCode != 0) {
                showMessage("Windows could not start the iNTAKE removal wizard (exit code "
                        + exitCode + "). Use Settings > Apps > Installed apps instead.",
                        JOptionPane.ERROR_MESSAGE);
            }
        } catch (IOException failure) {
            showMessage("Unable to start Windows Installer: " + failure.getMessage(),
                    JOptionPane.ERROR_MESSAGE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void showMessage(String message, int type) {
        Runnable display = () -> JOptionPane.showMessageDialog(
                null, message, "iNTAKE // Uninstall", type);
        if (SwingUtilities.isEventDispatchThread()) {
            display.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(display);
            } catch (Exception unavailable) {
                System.err.println(message);
            }
        }
    }
}
