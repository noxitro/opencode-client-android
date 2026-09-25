# serve-launcher.ps1 — serve.bat のワンタッチ起動用ラッパー。
# 初回だけ OPENCODE_SERVER_PASSWORD を尋ねて User スコープに保存し(リポジトリには書かない)、
# 以降は入力なしで scripts/serve.ps1 を呼ぶ。

$ErrorActionPreference = 'Stop'

$existing = [Environment]::GetEnvironmentVariable('OPENCODE_SERVER_PASSWORD', 'User')
if ([string]::IsNullOrEmpty($existing)) {
  $existing = [Environment]::GetEnvironmentVariable('OPENCODE_SERVER_PASSWORD', 'Machine')
}
if ([string]::IsNullOrEmpty($existing)) {
  Write-Host 'OPENCODE_SERVER_PASSWORD が未設定です。初回のみ入力してください。'
  Write-Host '(User スコープに保存されます。このスクリプトやリポジトリには書き込まれません)'
  $secure = Read-Host -AsSecureString 'OPENCODE_SERVER_PASSWORD'
  $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
  if ([string]::IsNullOrEmpty($plain)) {
    Write-Warning '空のため保存しませんでした。'
    exit 2
  }
  [Environment]::SetEnvironmentVariable('OPENCODE_SERVER_PASSWORD', $plain, 'User')
  Write-Host 'User スコープに保存しました。次回から入力は不要です。'
}

& (Join-Path $PSScriptRoot 'serve.ps1')
$code = $LASTEXITCODE
Write-Host ''
Write-Host '(serve はバックグラウンドで動き続けます。このウィンドウは閉じてOK)'
exit $code
