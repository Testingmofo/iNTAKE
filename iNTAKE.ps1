[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Web

# Native Windows API declarations to force a true Dark Title Bar
$signature = @"
[DllImport("dwmapi.dll")]
public static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);
"@
$dwmapi = Add-Type -Namespace "Win32" -Name "Dwmapi" -MemberDefinition $signature -PassThru

$script:pauseQueue = $false
$script:lastProcessedUrl = ''
$script:currentDownloadJob = $null
$script:currentQueueIndex = 0

# Faint Premium Dark Palette
$backColor = [Drawing.Color]::FromArgb(32, 32, 32)        # Charcoal Main Background
$windowColor = [Drawing.Color]::FromArgb(40, 40, 43)      # Base background for list & inputs
$altRowColor = [Drawing.Color]::FromArgb(48, 48, 51)      # Faint increase in whiteness for alternating rows
$foreColor = [Drawing.Color]::White                       # White Text
$borderColor = [Drawing.Color]::FromArgb(65, 65, 70)     # Clean subtle border accents

$form = New-Object Windows.Forms.Form
$form.Text = 'iNTAKE'
$form.Size = New-Object Drawing.Size(700, 520)
$form.MinimumSize = New-Object Drawing.Size(650, 450)
$form.StartPosition = 'CenterScreen'
$form.BackColor = $backColor
$form.ForeColor = $foreColor
$form.FormBorderStyle = [Windows.Forms.FormBorderStyle]::Sizable
$form.ControlBox = $true

# Splash / Loading Overlay Panel to display while checking for updates
$loadingPanel = New-Object Windows.Forms.Panel
$loadingPanel.Size = New-Object Drawing.Size(660, 290)
$loadingPanel.Location = New-Object Drawing.Point(12, 12)
$loadingPanel.BackColor = $windowColor
$loadingPanel.Anchor = [Windows.Forms.AnchorStyles]::Top -bor [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Left -bor [Windows.Forms.AnchorStyles]::Right

$loadingLabel = New-Object Windows.Forms.Label
$loadingLabel.Text = 'Checking for yt-dlp & ffmpeg updates... Please wait.'
$loadingLabel.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$loadingLabel.Dock = [Windows.Forms.DockStyle]::Fill
$loadingLabel.Font = New-Object Drawing.Font('Segoe UI', 11, [Drawing.FontStyle]::Bold)
$loadingLabel.ForeColor = [Drawing.Color]::FromArgb(180, 180, 180)
$loadingPanel.Controls.Add($loadingLabel)
$form.Controls.Add($loadingPanel)

# Queue Track Item ListView Box
$listView = New-Object Windows.Forms.ListView 
$listView.Size = New-Object Drawing.Size(660, 290)
$listView.Location = New-Object Drawing.Point(12, 12)
$listView.View = 'Details'
$listView.FullRowSelect = $true
$listView.GridLines = $false 
$listView.BackColor = $windowColor
$listView.ForeColor = [Drawing.Color]::Gainsboro
$listView.Anchor = [Windows.Forms.AnchorStyles]::Top -bor [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Left -bor [Windows.Forms.AnchorStyles]::Right

# BROUGHT BACK & FIXED: URL column is back! It uses -2 width so it stretches to fill the edge perfectly, eliminating the ghost divider chunk.
$null = $listView.Columns.Add('Title', 260)
$null = $listView.Columns.Add('Format', 60)
$null = $listView.Columns.Add('Status', 100)
$null = $listView.Columns.Add('Progress', 80) 
$null = $listView.Columns.Add('URL', -2)
$form.Controls.Add($listView)

# Explicitly push loading screen to the front layer
$loadingPanel.BringToFront()

# Manual Entry Input Controls
$urlLabel = New-Object Windows.Forms.Label
$urlLabel.Text = 'YouTube URL:'
$urlLabel.Location = New-Object Drawing.Point(12, 332)
$urlLabel.Size = New-Object Drawing.Size(90, 20)
$urlLabel.ForeColor = $foreColor
$urlLabel.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Left
$form.Controls.Add($urlLabel)

$urlBox = New-Object Windows.Forms.TextBox
$urlBox.Size = New-Object Drawing.Size(300, 20)
$urlBox.Location = New-Object Drawing.Point(105, 330)
$urlBox.BackColor = $windowColor
$urlBox.ForeColor = $foreColor
$urlBox.BorderStyle = [Windows.Forms.BorderStyle]::FixedSingle
$urlBox.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Left -bor [Windows.Forms.AnchorStyles]::Right
$form.Controls.Add($urlBox)

$mp3Btn = New-Object Windows.Forms.Button
$mp3Btn.Text = 'Add MP3'
$mp3Btn.Size = New-Object Drawing.Size(75, 25)
$mp3Btn.Location = New-Object Drawing.Point(420, 328)
$mp3Btn.BackColor = $windowColor
$mp3Btn.ForeColor = $foreColor
$mp3Btn.FlatStyle = [Windows.Forms.FlatStyle]::Flat
$mp3Btn.FlatAppearance.BorderColor = $borderColor
$mp3Btn.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Right
$form.Controls.Add($mp3Btn)

$opusBtn = New-Object Windows.Forms.Button
$opusBtn.Text = 'Add OPUS'
$opusBtn.Size = New-Object Drawing.Size(75, 25)
$opusBtn.Location = New-Object Drawing.Point(500, 328)
$opusBtn.BackColor = $windowColor
$opusBtn.ForeColor = $foreColor
$opusBtn.FlatStyle = [Windows.Forms.FlatStyle]::Flat
$opusBtn.FlatAppearance.BorderColor = $borderColor
$opusBtn.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Right
$form.Controls.Add($opusBtn)

$mp4Btn = New-Object Windows.Forms.Button
$mp4Btn.Text = 'Add MP4'
$mp4Btn.Size = New-Object Drawing.Size(75, 25)
$mp4Btn.Location = New-Object Drawing.Point(580, 328)
$mp4Btn.BackColor = $windowColor
$mp4Btn.ForeColor = $foreColor
$mp4Btn.FlatStyle = [Windows.Forms.FlatStyle]::Flat
$mp4Btn.FlatAppearance.BorderColor = $borderColor
$mp4Btn.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Right
$form.Controls.Add($mp4Btn)

$useSubfolderCheck = New-Object Windows.Forms.CheckBox
$useSubfolderCheck.Text = 'Custom Subfolder'
$useSubfolderCheck.Location = New-Object Drawing.Point(12, 372)
$useSubfolderCheck.ForeColor = $foreColor
$useSubfolderCheck.Size = New-Object Drawing.Size(130, 20)
$useSubfolderCheck.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Left
$form.Controls.Add($useSubfolderCheck)

$subfolderBox = New-Object Windows.Forms.TextBox
$subfolderBox.Size = New-Object Drawing.Size(200, 20)
$subfolderBox.Location = New-Object Drawing.Point(145, 372)
$subfolderBox.BackColor = $windowColor
$subfolderBox.ForeColor = $foreColor
$subfolderBox.BorderStyle = [Windows.Forms.BorderStyle]::FixedSingle
$subfolderBox.Enabled = $false
$subfolderBox.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Left
$form.Controls.Add($subfolderBox)

$useSubfolderCheck.Add_CheckedChanged({ $subfolderBox.Enabled = $useSubfolderCheck.Checked })

$startBtn = New-Object Windows.Forms.Button
$startBtn.Text = 'Start Queue'
$startBtn.Size = New-Object Drawing.Size(120, 35)
$startBtn.Location = New-Object Drawing.Point(535, 420)
$startBtn.BackColor = $windowColor
$startBtn.ForeColor = $foreColor
$startBtn.FlatStyle = [Windows.Forms.FlatStyle]::Flat
$startBtn.FlatAppearance.BorderColor = $borderColor
$startBtn.Font = New-Object Drawing.Font($startBtn.Font, [Drawing.FontStyle]::Bold)
$startBtn.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Right
$form.Controls.Add($startBtn)

$pauseBtn = New-Object Windows.Forms.Button
$pauseBtn.Text = 'Pause'
$pauseBtn.Size = New-Object Drawing.Size(80, 35)
$pauseBtn.Location = New-Object Drawing.Point(445, 420)
$pauseBtn.BackColor = $windowColor
$pauseBtn.ForeColor = $foreColor
$pauseBtn.FlatStyle = [Windows.Forms.FlatStyle]::Flat
$pauseBtn.FlatAppearance.BorderColor = $borderColor
$pauseBtn.Anchor = [Windows.Forms.AnchorStyles]::Bottom -bor [Windows.Forms.AnchorStyles]::Right
$form.Controls.Add($pauseBtn)

$pauseBtn.Add_Click({
    $script:pauseQueue = -not $script:pauseQueue
    $pauseBtn.Text = if ($script:pauseQueue) { 'Resume' } else { 'Pause' }
})

function Update-RowStyles {
    for ($i = 0; $i -lt $listView.Items.Count; $i++) {
        if ($i % 2 -eq 0) {
            $listView.Items[$i].BackColor = $windowColor
        } else {
            $listView.Items[$i].BackColor = $altRowColor
        }
    }
    # Keep the final column (URL) matched up directly to the right bounds
    try { $listView.Columns[4].Width = -2 } catch {}
}

function Add-ToQueue($url, $format) {
    if ([string]::IsNullOrWhiteSpace($url)) { return }
    $resolvedTitle = 'YouTube Video Asset'

    try {
        $encodedUrl = [System.Web.HttpUtility]::UrlEncode($url)
        $apiQuery = "https://www.youtube.com/oembed?url=$encodedUrl&format=json"
        
        $webClient = New-Object System.Net.WebClient
        $webClient.Encoding = [System.Text.Encoding]::UTF8
        $jsonRaw = $webClient.DownloadString($apiQuery)
        
        if ($jsonRaw -match '"title"\s*:\s*"([^"]+)"') {
            $resolvedTitle = $Matches[1]
        }
    } catch {
        if ($url -match 'v=([^&]+)') { $resolvedTitle = "Video ($($Matches[1]))" }
    }

    $item = New-Object Windows.Forms.ListViewItem($resolvedTitle)
    $item.SubItems.Add($format)
    $item.SubItems.Add('Waiting')
    $item.SubItems.Add('0%')
    $item.SubItems.Add($url) # Adding back visually to the row
    $listView.Items.Add($item)
    Update-RowStyles
}

$mp3Btn.Add_Click({ Add-ToQueue $urlBox.Text 'mp3'; $urlBox.Clear() })
$opusBtn.Add_Click({ Add-ToQueue $urlBox.Text 'opus'; $urlBox.Clear() })
$mp4Btn.Add_Click({ Add-ToQueue $urlBox.Text 'mp4'; $urlBox.Clear() })

$clipboardTimer = New-Object Windows.Forms.Timer
$clipboardTimer.Interval = 300
$clipboardTimer.Add_Tick({
    try {
        if ([Windows.Forms.Clipboard]::ContainsText()) {
            $clipText = [Windows.Forms.Clipboard]::GetText()
            if ($clipText -and $clipText.StartsWith('YT_DOWNLOAD|')) {
                $parts = $clipText -split '\|'
                if ($parts.Count -eq 3) {
                    $url = $parts[1].Trim()
                    $format = $parts[2].Trim()
                    
                    if ($url -ne $script:lastProcessedUrl) {
                        $script:lastProcessedUrl = $url
                        Add-ToQueue $url $format
                        [Windows.Forms.Clipboard]::Clear()
                    }
                }
            }
        }
    } catch {}
})
$clipboardTimer.Start()

$queueTimer = New-Object Windows.Forms.Timer
$queueTimer.Interval = 150
$queueTimer.Add_Tick({
    if ($script:pauseQueue) { return }

    if ($script:currentDownloadJob -ne $null) {
        $job = $script:currentDownloadJob
        $item = $listView.Items[$script:currentQueueIndex]
        
        if ($job.HasExited) {
            if ($job.ExitCode -eq 0) {
                $item.SubItems[2].Text = 'Done'
                $item.SubItems[3].Text = '100%'
            } else {
                $item.SubItems[2].Text = 'Failed/Error'
            }
            $job.Dispose()
            $script:currentDownloadJob = $null
            $script:currentQueueIndex++
        } else {
            if (-not $job.StandardOutput.EndOfStream) {
                $line = $job.StandardOutput.ReadLine()
                if ($line -and $line -match '\s(\d+(\.\d+)?%)') {
                    $item.SubItems[3].Text = $Matches[1]
                }
            }
        }
        return
    }

    if ($script:currentQueueIndex -lt $listView.Items.Count) {
        $item = $listView.Items[$script:currentQueueIndex]
        
        if ($item.SubItems[2].Text -ne 'Waiting') {
            $script:currentQueueIndex++
            return
        }

        $url = $item.SubItems[4].Text # Reads the target straightforward out of subitem column 4 again
        $format = $item.SubItems[1].Text
        $item.SubItems[2].Text = 'Downloading...'

        $baseOutputDir = [System.IO.Path]::Combine($env:USERPROFILE, "Downloads")
        if ($useSubfolderCheck.Checked -and -not [string]::IsNullOrWhiteSpace($subfolderBox.Text)) {
            $folderName = [Regex]::Replace($subfolderBox.Text, '[\\/:*?"<>|]', '')
            $outputDir = Join-Path $baseOutputDir $folderName
            if (-not (Test-Path $outputDir)) { New-Item -ItemType Directory -Path $outputDir | Out-Null }
        } else { $outputDir = $baseOutputDir }

        $argsList = New-Object System.Collections.Generic.List[string]
        $argsList.Add($url)
        $argsList.Add('--no-playlist')
        $argsList.Add('-o')
        $argsList.Add("$outputDir\%(title)s.%(ext)s")

        if ($format -eq 'mp3') {
            $argsList.Add('--extract-audio')
            $argsList.Add('--audio-format')
            $argsList.Add('mp3')
            $argsList.Add('--audio-quality')
            $argsList.Add('0')
            $argsList.Add('--embed-thumbnail')
            $argsList.Add('--add-metadata')
        } elseif ($format -eq 'opus') {
            $argsList.Add('--extract-audio')
            $argsList.Add('--audio-format')
            $argsList.Add('opus')
            $argsList.Add('--audio-quality')
            $argsList.Add('0')
            $argsList.Add('--embed-thumbnail')
            $argsList.Add('--add-metadata')
        } else {
            $argsList.Add('-f')
            $argsList.Add('bestvideo[ext=mp4]+bestaudio[ext=m4a]/mp4')
        }

        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = 'yt-dlp'
        $psi.Arguments = [string]::Join(' ', ($argsList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }))
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true

        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $psi
        
        try {
            if ($process.Start()) {
                $script:currentDownloadJob = $process
            } else {
                $item.SubItems[2].Text = 'Launch Fail'
            }
        } catch {
            $item.SubItems[2].Text = 'Path Error'
        }
    }
})

$startBtn.Add_Click({
    $script:pauseQueue = $false
    $queueTimer.Start()
})

$form.Add_FormClosing({
    $clipboardTimer.Stop()
    $queueTimer.Stop()
    if ($script:currentDownloadJob -and -not $script:currentDownloadJob.HasExited) {
        try { $script:currentDownloadJob.Kill() } catch {}
    }
})

$form.Add_Load({
    $formHandle = $form.Handle
    $trueValue = 1
    [Win32.Dwmapi]::DwmSetWindowAttribute($formHandle, 20, [ref]$trueValue, 4)
    [Win32.Dwmapi]::DwmSetWindowAttribute($formHandle, 38, [ref]$trueValue, 4)
})

$form.Add_Shown({
    $form.Text = 'iNTAKE (Updating...)'
    [System.Windows.Forms.Application]::DoEvents()

    $loadingLabel.Text = 'Checking core engine for yt-dlp updates...'
    [System.Windows.Forms.Application]::DoEvents()

    $ytdlpPsi = New-Object System.Diagnostics.ProcessStartInfo -Property @{
        FileName = 'yt-dlp'
        Arguments = '-U'
        UseShellExecute = $false
        CreateNoWindow = $true
    }
    try {
        $procYtdlp = [System.Diagnostics.Process]::Start($ytdlpPsi)
        $procYtdlp.WaitForExit(10000)
    } catch {}

    $loadingLabel.Text = 'Synchronizing environment ffmpeg components...'
    [System.Windows.Forms.Application]::DoEvents()

    $ffmpegPsi = New-Object System.Diagnostics.ProcessStartInfo -Property @{
        FileName = 'yt-dlp'
        Arguments = '--update-to yt-dlp/ffmpeg-latest'
        UseShellExecute = $false
        CreateNoWindow = $true
    }
    try {
        $procFfmpeg = [System.Diagnostics.Process]::Start($ffmpegPsi)
        $procFfmpeg.WaitForExit(12000)
    } catch {}

    $form.Text = 'iNTAKE'
    $loadingPanel.Visible = $false
    $loadingPanel.Dispose()
    
    try { $listView.Columns[4].Width = -2 } catch {}
})

$form.Add_Resize({
    try { $listView.Columns[4].Width = -2 } catch {}
})

$form.Topmost = $false 
[Windows.Forms.Application]::Run($form)