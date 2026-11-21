import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

final class ApplicationUpdaterTest {
    @Test
    void detachedUpdaterScriptHasValidPowerShellSyntax() throws Exception {
        Process process = new ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                "$text=[Console]::In.ReadToEnd(); $tokens=$null; $errors=$null; "
                        + "[Management.Automation.Language.Parser]::ParseInput($text, "
                        + "[ref]$tokens, [ref]$errors) > $null; "
                        + "if($errors.Count){$errors | ForEach-Object {$_.Message}; exit 1}")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(
                ApplicationUpdater.updaterScript().getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), output);
    }

    @Test
    void updaterSelectsWinnerBeforeRemovingOlderInstallations() {
        String script = ApplicationUpdater.updaterScript();

        assertTrue(script.indexOf("$winner =")
                < script.lastIndexOf("Remove-SafeOlderInstallations ($winner[0])"));
        assertTrue(script.contains("update-history.log"));
        assertTrue(script.contains("iNTAKEv*"));
    }
}
