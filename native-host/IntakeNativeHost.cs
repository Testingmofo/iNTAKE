using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

internal static class IntakeNativeHost
{
    private const int MaximumMessageBytes = 65536;
    private const string HealthEndpoint = "http://127.0.0.1:17845/health";
    private const string EnqueueEndpoint = "http://127.0.0.1:17845/enqueue";
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();

    private static int Main()
    {
        try
        {
            Dictionary<string, object> request = ReadMessage();
            Dictionary<string, object> response = HandleRequest(request);
            WriteMessage(response);
            return 0;
        }
        catch (Exception failure)
        {
            try
            {
                WriteMessage(new Dictionary<string, object>
                {
                    { "ok", false },
                    { "error", failure.Message }
                });
            }
            catch
            {
                // Chrome will surface a disconnected native host if stdout is unavailable.
            }
            return 1;
        }
    }

    private static Dictionary<string, object> HandleRequest(Dictionary<string, object> request)
    {
        string url = RequiredString(request, "url");
        string mode = RequiredString(request, "mode").ToLowerInvariant();
        string name = OptionalString(request, "name");
        Uri mediaUri;
        if (!Uri.TryCreate(url, UriKind.Absolute, out mediaUri)
            || (mediaUri.Scheme != Uri.UriSchemeHttp && mediaUri.Scheme != Uri.UriSchemeHttps))
        {
            throw new InvalidOperationException("The extension supplied an invalid media URL.");
        }
        if (mode != "audio" && mode != "video")
        {
            throw new InvalidOperationException("The extension supplied an invalid download mode.");
        }

        bool started = false;
        using (Mutex wakeMutex = new Mutex(false, "Local\\iNTAKE.NativeWake"))
        {
            bool ownsMutex = false;
            try
            {
                try
                {
                    ownsMutex = wakeMutex.WaitOne(TimeSpan.FromSeconds(120));
                }
                catch (AbandonedMutexException)
                {
                    ownsMutex = true;
                }
                if (!ownsMutex)
                {
                    throw new TimeoutException("Timed out waiting for another iNTAKE startup attempt.");
                }

                if (!IsBridgeOnline())
                {
                    StartIntakeHidden();
                    started = true;
                    WaitForBridge();
                }

                Dictionary<string, object> response = PostDownload(url, mode, name);
                response["started"] = started;
                return response;
            }
            finally
            {
                if (ownsMutex)
                {
                    wakeMutex.ReleaseMutex();
                }
            }
        }
    }

    private static void StartIntakeHidden()
    {
        string hostDirectory = AppDomain.CurrentDomain.BaseDirectory;
        string projectRoot = Path.GetFullPath(Path.Combine(hostDirectory, ".."));
        string launcher = Path.Combine(projectRoot, "Launch iNTAKE.cmd");
        if (File.Exists(launcher))
        {
            StartProcess(
                Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe",
                "/d /s /c \"\"" + launcher + "\" -Silent\"",
                projectRoot);
            return;
        }

        DirectoryInfo appDirectory = Directory.GetParent(hostDirectory.TrimEnd(
            Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        DirectoryInfo imageRoot = appDirectory == null ? null : appDirectory.Parent;
        string packagedLauncher = imageRoot == null ? null : Path.Combine(imageRoot.FullName, "iNTAKE.exe");
        if (String.IsNullOrWhiteSpace(packagedLauncher) || !File.Exists(packagedLauncher))
        {
            throw new FileNotFoundException(
                "Neither the development launcher nor packaged iNTAKE executable could be found.");
        }
        StartProcess(packagedLauncher, "-silent", imageRoot.FullName);
    }

    private static void StartProcess(string executable, string arguments, string workingDirectory)
    {
        ProcessStartInfo startInfo = new ProcessStartInfo
        {
            FileName = executable,
            Arguments = arguments,
            WorkingDirectory = workingDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden
        };
        Process process = Process.Start(startInfo);
        if (process == null)
        {
            throw new InvalidOperationException("Windows could not start the iNTAKE launcher.");
        }
        process.Dispose();
    }

    private static void WaitForBridge()
    {
        DateTime deadline = DateTime.UtcNow.AddSeconds(120);
        while (DateTime.UtcNow < deadline)
        {
            if (IsBridgeOnline())
            {
                return;
            }
            Thread.Sleep(500);
        }
        throw new TimeoutException(
            "iNTAKE did not start in time. Open the main GUI and check launcher-error.log.");
    }

    private static bool IsBridgeOnline()
    {
        try
        {
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(HealthEndpoint);
            request.Method = "GET";
            request.Timeout = 1000;
            request.ReadWriteTimeout = 1000;
            using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
            {
                return response.StatusCode == HttpStatusCode.OK;
            }
        }
        catch
        {
            return false;
        }
    }

    private static Dictionary<string, object> PostDownload(string url, string mode, string name)
    {
        StringBuilder form = new StringBuilder();
        form.Append("url=").Append(Uri.EscapeDataString(url));
        form.Append("&mode=").Append(Uri.EscapeDataString(mode));
        if (!String.IsNullOrWhiteSpace(name))
        {
            form.Append("&name=").Append(Uri.EscapeDataString(name));
        }
        byte[] body = Encoding.UTF8.GetBytes(form.ToString());

        HttpWebRequest request = (HttpWebRequest)WebRequest.Create(EnqueueEndpoint);
        request.Method = "POST";
        request.ContentType = "application/x-www-form-urlencoded;charset=UTF-8";
        request.Headers["X-iNTAKE-Extension"] = "1";
        request.ContentLength = body.Length;
        request.Timeout = 10000;
        request.ReadWriteTimeout = 10000;
        using (Stream stream = request.GetRequestStream())
        {
            stream.Write(body, 0, body.Length);
        }

        try
        {
            using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
            using (StreamReader reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
            {
                return DeserializeBridgeResponse(reader.ReadToEnd());
            }
        }
        catch (WebException failure)
        {
            HttpWebResponse response = failure.Response as HttpWebResponse;
            if (response == null)
            {
                throw;
            }
            using (response)
            using (StreamReader reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
            {
                return DeserializeBridgeResponse(reader.ReadToEnd());
            }
        }
    }

    private static Dictionary<string, object> DeserializeBridgeResponse(string json)
    {
        Dictionary<string, object> result = Json.Deserialize<Dictionary<string, object>>(json);
        if (result == null)
        {
            throw new InvalidDataException("iNTAKE returned an empty bridge response.");
        }
        return result;
    }

    private static Dictionary<string, object> ReadMessage()
    {
        Stream input = Console.OpenStandardInput();
        byte[] lengthBytes = ReadExactly(input, 4);
        int length = BitConverter.ToInt32(lengthBytes, 0);
        if (length <= 0 || length > MaximumMessageBytes)
        {
            throw new InvalidDataException("The native message length is invalid.");
        }
        string json = Encoding.UTF8.GetString(ReadExactly(input, length));
        Dictionary<string, object> message = Json.Deserialize<Dictionary<string, object>>(json);
        if (message == null)
        {
            throw new InvalidDataException("The native message is empty.");
        }
        return message;
    }

    private static void WriteMessage(Dictionary<string, object> message)
    {
        byte[] payload = Encoding.UTF8.GetBytes(Json.Serialize(message));
        byte[] length = BitConverter.GetBytes(payload.Length);
        Stream output = Console.OpenStandardOutput();
        output.Write(length, 0, length.Length);
        output.Write(payload, 0, payload.Length);
        output.Flush();
    }

    private static byte[] ReadExactly(Stream input, int length)
    {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length)
        {
            int read = input.Read(buffer, offset, length - offset);
            if (read <= 0)
            {
                throw new EndOfStreamException("The browser closed the native messaging pipe.");
            }
            offset += read;
        }
        return buffer;
    }

    private static string RequiredString(Dictionary<string, object> values, string key)
    {
        string value = OptionalString(values, key);
        if (String.IsNullOrWhiteSpace(value))
        {
            throw new InvalidDataException("The native request is missing " + key + ".");
        }
        return value;
    }

    private static string OptionalString(Dictionary<string, object> values, string key)
    {
        object raw;
        return values.TryGetValue(key, out raw) && raw != null ? Convert.ToString(raw) : null;
    }
}
