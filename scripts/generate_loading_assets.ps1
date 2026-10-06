# Regenerate the static loading-screen glyph atlas and antialiased round brush.
# Windows build-time utility only: clients do not need System.Drawing, AWT or installed fonts.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$target = Join-Path $PSScriptRoot '../platform/resources/src/main/resources/assets/dreamdisplayx/textures/gui'
$target = [System.IO.Path]::GetFullPath($target)
$font = [System.Drawing.Font]::new('Segoe UI Semibold', 72, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
if ($font.Name -ne 'Segoe UI Semibold') { throw 'Install Segoe UI Semibold before regenerating the atlas.' }
$format = [System.Drawing.StringFormat]::GenericTypographic.Clone()
$format.FormatFlags = $format.FormatFlags -bor [System.Drawing.StringFormatFlags]::MeasureTrailingSpaces
$measure = [System.Drawing.Bitmap]::new(1, 1)
$graphics = [System.Drawing.Graphics]::FromImage($measure)
$chars = 'Dream DisplaysX'.ToCharArray()
$widths = @($chars | ForEach-Object {
    4 + [int][Math]::Ceiling($graphics.MeasureString("$_", $font, [System.Drawing.PointF]::new(0, 0), $format).Width)
})
$graphics.Dispose()
$measure.Dispose()
$width = [int](($widths | Measure-Object -Sum).Sum)
$atlas = [System.Drawing.Bitmap]::new($width, 96, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($atlas)
$graphics.Clear([System.Drawing.Color]::Transparent)
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$x = 0
for ($i = 0; $i -lt $chars.Length; $i++) {
    # Two transparent pixels on both sides isolate each independently animated character.
    $graphics.DrawString("$($chars[$i])", $font, [System.Drawing.Brushes]::White, [System.Drawing.PointF]::new($x + 2, 0), $format)
    $x += $widths[$i]
}
$atlas.Save((Join-Path $target 'loading_wordmark.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$atlas.Dispose()
$font.Dispose()
$format.Dispose()
$dot = [System.Drawing.Bitmap]::new(32, 32, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($dot)
$graphics.Clear([System.Drawing.Color]::Transparent)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.FillEllipse([System.Drawing.Brushes]::White, 1, 1, 30, 30)
$dot.Save((Join-Path $target 'loading_dot.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$dot.Dispose()
Write-Output "Atlas: ${width}x96; update LoadingScreenAnimation.GLYPH_WIDTHS if these change: $($widths -join ', ')"
