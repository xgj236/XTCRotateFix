Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$src = 'C:\Users\1\Downloads\7ddbe23d6c85172c331a67608347196f.png'
$out = 'C:\xgj_236\AllToolBox\rotate_fix\lspmod\res'

$img = [System.Drawing.Image]::FromFile($src)
Write-Host ("source: {0}x{1}" -f $img.Width, $img.Height)

# 补成正方形（用背景同色 #212121 填充），避免启动器把非正方形图标拉伸/加黑边
$side = [Math]::Max($img.Width, $img.Height)
$sq = New-Object System.Drawing.Bitmap -ArgumentList ([int]$side), ([int]$side)
$g = [System.Drawing.Graphics]::FromImage($sq)
$g.Clear([System.Drawing.Color]::White)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($img, [int](($side - $img.Width) / 2), [int](($side - $img.Height) / 2), $img.Width, $img.Height)
$g.Dispose()

$sizes = [ordered]@{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($k in $sizes.Keys) {
    $s = $sizes[$k]
    $dir = Join-Path $out "mipmap-$k"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList ([int]$s), ([int]$s)
    $g2 = [System.Drawing.Graphics]::FromImage($bmp)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g2.DrawImage($sq, 0, 0, $s, $s)
    $g2.Dispose()
    $p = Join-Path $dir 'ic_launcher.png'
    $bmp.Save($p, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host ("  written {0}  ({1}x{1})" -f $p, $s)
}
$sq.Dispose()
$img.Dispose()
Write-Host 'DONE'
