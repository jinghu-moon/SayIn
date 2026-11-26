# Run with Windows PowerShell in STA. The probe creates private RichEdit controls
# and OLE data objects. No system clipboard, focus change, user input or files.
param([ValidateRange(1, 30)][int]$Iterations = 5)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type -ReferencedAssemblies System.Windows.Forms -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

[ComImport, Guid("00020D00-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IRichEditOleProbe {
    // These members reserve the documented IRichEditOle slots before ImportDataObject.
    void GetClientSite(); void GetObjectCount(); void GetLinkCount(); void GetObject();
    void InsertObject(); void ConvertObject(); void ActivateAs(); void SetHostNames();
    void SetLinkAvailable(); void SetDvaspect(); void HandsOffStorage(); void SaveCompleted();
    void InPlaceDeactivate(); void ContextSensitiveHelp(); void GetClipboardData();
    [PreserveSig] int ImportDataObject(System.Runtime.InteropServices.ComTypes.IDataObject data, ushort format, IntPtr meta);
}
public sealed class BatchRichEdit : RichTextBox {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr LoadLibrary(string name);
    static readonly IntPtr library = LoadLibrary("Msftedit.dll");
    protected override CreateParams CreateParams {
        get {
            if (library == IntPtr.Zero) throw new InvalidOperationException("Msftedit unavailable");
            var parameters = base.CreateParams;
            parameters.ClassName = "RICHEDIT50W";
            return parameters;
        }
    }
}
public static class NativeBatchProbe {
    [DllImport("user32.dll", ExactSpelling = true)]
    static extern IntPtr SendMessageW(IntPtr hwnd, uint msg, IntPtr param, out IntPtr result);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int GetClassName(IntPtr hwnd, StringBuilder name, int capacity);

    public static string ClassName(RichTextBox box) {
        var name = new StringBuilder(128);
        if (GetClassName(box.Handle, name, name.Capacity) == 0)
            throw new InvalidOperationException("Cannot identify RichEdit class.");
        return name.ToString();
    }

    public static void Paste(RichTextBox box, DataObject data, string format) {
        IntPtr ole;
        if (SendMessageW(box.Handle, 1084, IntPtr.Zero, out ole) == IntPtr.Zero || ole == IntPtr.Zero)
            throw new InvalidOperationException("EM_GETOLEINTERFACE failed.");
        var target = (IRichEditOleProbe)Marshal.GetTypedObjectForIUnknown(ole, typeof(IRichEditOleProbe));
        try {
            var hr = target.ImportDataObject((System.Runtime.InteropServices.ComTypes.IDataObject)data,
                (ushort)DataFormats.GetFormat(format).Id, IntPtr.Zero);
            if (hr != 0) throw new InvalidOperationException("ImportDataObject hr=" + hr.ToString("X"));
        } finally {
            Marshal.ReleaseComObject(target);
            Marshal.Release(ole);
        }
    }
}
"@
function New-Picture([int]$Width, [int]$Height, [Drawing.Color]$Color) {
    $image = [Drawing.Bitmap]::new($Width, $Height)
    $stream = [IO.MemoryStream]::new()
    $graphics = [Drawing.Graphics]::FromImage($image)
    try {
        $graphics.Clear($Color)
        $image.Save($stream, [Drawing.Imaging.ImageFormat]::Png)
        $hex = [BitConverter]::ToString($stream.ToArray()).Replace('-', '')
        return '{\pict\pngblip\picw' + $Width + '\pich' + $Height + '\picwgoal' + ($Width * 15) + '\pichgoal' + ($Height * 15) + ' ' + $hex + '}'
    } finally {
        $graphics.Dispose()
        $stream.Dispose()
        $image.Dispose()
    }
}
function Invoke-Imports([string[]]$Parts) {
    $target = [BatchRichEdit]::new()
    try {
        $elapsed = 0.0
        foreach ($part in $Parts) {
            $target.SelectionStart = $target.TextLength
            $object = [Windows.Forms.DataObject]::new()
            $payload = '{\rtf1\ansi ' + $part + '}'
            $encoded = [Text.Encoding]::ASCII.GetBytes($payload + [char]0)
            $object.SetData([Windows.Forms.DataFormats]::Rtf, $false, [IO.MemoryStream]::new($encoded))
            $watch = [Diagnostics.Stopwatch]::StartNew()
            [NativeBatchProbe]::Paste($target, $object, [Windows.Forms.DataFormats]::Rtf)
            $elapsed += $watch.Elapsed.TotalMilliseconds
        }
        $pictureWidths = @([regex]::Matches($target.Rtf, '\\picwgoal(\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
        return @{ window_class=[NativeBatchProbe]::ClassName($target); calls=$Parts.Count; widths=$pictureWidths; elapsed_ms=[Math]::Round($elapsed, 2); object_chars=$target.TextLength }
    } finally {
        $target.Dispose()
    }
}
$images = @(
    (New-Picture 7 11 ([Drawing.Color]::Red)),
    (New-Picture 13 5 ([Drawing.Color]::Blue)),
    (New-Picture 11 8 ([Drawing.Color]::Green))
)
$expectedWidths = '105,195,165'
$samples = @(for ($index = 0; $index -lt $Iterations; $index++) {
    $batch = Invoke-Imports @(-join $images)
    $individual = Invoke-Imports $images
    if (($batch.widths -join ',') -ne $expectedWidths -or ($individual.widths -join ',') -ne $expectedWidths) {
        throw 'RichEdit picture count or order differs.'
    }
    @{ batch=$batch; individual=$individual }
})
$truncated = $images[1] -replace ' [0-9A-F]+\}$', ' 89504E470D0A1A0A}'
$partial = Invoke-Imports @($images[0] + $truncated + $images[2])
if (($partial.widths -join ',') -ne '105,165') {
    throw 'The second item omission was not reproduced.'
}

$legacy = [Windows.Forms.RichTextBox]::new()
try {
    $data = [Windows.Forms.DataObject]::new()
    $payload = '{\rtf1\ansi ' + (-join $images) + '}'
    $encoded = [Text.Encoding]::ASCII.GetBytes($payload + [char]0)
    $data.SetData([Windows.Forms.DataFormats]::Rtf, $false, [IO.MemoryStream]::new($encoded))
    [NativeBatchProbe]::Paste($legacy, $data, [Windows.Forms.DataFormats]::Rtf)
    $batchCount = [regex]::Matches($legacy.Rtf, '\\pict\b').Count
    $sizes = @(@(7,11), @(13,5), @(11,8))
    foreach ($size in $sizes) {
        $image = [Drawing.Bitmap]::new($size[0], $size[1])
        try {
            $item = [Windows.Forms.DataObject]::new()
            $item.SetData([Windows.Forms.DataFormats]::Bitmap, $false, $image)
            $legacy.SelectionStart = $legacy.TextLength
            [NativeBatchProbe]::Paste($legacy, $item, [Windows.Forms.DataFormats]::Bitmap)
        } finally { $image.Dispose() }
    }
    $individualCount = [regex]::Matches($legacy.Rtf, '\\pict\b').Count
    if ($batchCount -ne 0 -or $individualCount -ne 3) {
        throw 'The legacy control did not reproduce the batch/individual result.'
    }
    $legacyResult = @{
        window_class=[NativeBatchProbe]::ClassName($legacy)
        batch_objects=$batchCount
        individual_bitmap_calls=$sizes.Count
        individual_objects=$individualCount
    }
} finally { $legacy.Dispose() }

@{
    samples=$samples
    invalid_second=$partial
    legacy=$legacyResult
    expected_widths_twips=@(105,195,165)
} | ConvertTo-Json -Depth 6
