$p = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($p -notlike '*platform-tools*') {
    [Environment]::SetEnvironmentVariable('Path', $p + ';C:\Users\Lenovo\AppData\Local\Android\Sdk\platform-tools', 'User')
    Write-Output 'platform-tools added to user PATH'
} else {
    Write-Output 'already in PATH'
}
