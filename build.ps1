$ErrorActionPreference = 'Stop'
$sdk = 'D:\Android\sdk'
$java = 'D:\Program\Java\bin'
$tools = Join-Path $sdk 'build-tools\36.0.0'
$platform = Join-Path $sdk 'platforms\android-34\android.jar'
$project = $PSScriptRoot
Set-Location $project
New-Item -ItemType Directory -Force build, build\gen, build\classes, build\dex | Out-Null
$manifest = [IO.File]::ReadAllText((Join-Path $project 'app\src\main\AndroidManifest.xml')).Replace('<manifest ', '<manifest package="com.cantonadong.emoji" ')
[IO.File]::WriteAllText((Join-Path $project 'build\AndroidManifest.xml'), $manifest)

function Run-Native($executable, $arguments) {
    & $executable @arguments
    if ($LASTEXITCODE -ne 0) { throw "$executable failed: $LASTEXITCODE" }
}

Run-Native "$tools\aapt2.exe" @('compile', '--dir', 'app\src\main\res', '-o', 'build\resources.zip')
Run-Native "$tools\aapt2.exe" @('link', '-o', 'build\resources.apk', '-I', $platform, '--manifest', 'build\AndroidManifest.xml', '--java', 'build\gen', '--min-sdk-version', '26', '--target-sdk-version', '34', '--version-code', '17', '--version-name', '1.0', '-A', 'app\src\main\assets', 'build\resources.zip')
$sources = @(Get-ChildItem app\src\main\java, build\gen -Recurse -Filter *.java | ForEach-Object FullName)
Run-Native "$java\javac.exe" (@('-encoding', 'UTF-8', '--release', '8', '-classpath', $platform, '-d', 'build\classes') + $sources)
$classes = @(Get-ChildItem build\classes -Recurse -Filter *.class | ForEach-Object FullName)
$env:JAVA_HOME = Split-Path $java
Run-Native "$tools\d8.bat" (@('--lib', $platform, '--min-api', '26', '--output', 'build\dex') + $classes)
Run-Native "$java\jar.exe" @('uf', 'build\resources.apk', '-C', 'build\dex', 'classes.dex')
Run-Native "$tools\zipalign.exe" @('-f', '4', 'build\resources.apk', 'build\aligned.apk')
if (-not (Test-Path debug.keystore)) {
    Run-Native "$java\keytool.exe" @('-genkeypair', '-keystore', 'debug.keystore', '-storepass', 'android', '-keypass', 'android', '-alias', 'emoji-debug', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Emoji Debug')
}
Run-Native "$tools\apksigner.bat" @('sign', '--ks', 'debug.keystore', '--ks-key-alias', 'emoji-debug', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', 'Emoji.apk', 'build\aligned.apk')
Write-Host "Built $project\Emoji.apk"
