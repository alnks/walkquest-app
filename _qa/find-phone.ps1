Get-PnpDevice -PresentOnly | Where-Object {
    $_.FriendlyName -match 'ADB' -or $_.Class -match 'USB|WPD|Modem' -and $_.FriendlyName -match 'SAMSUNG'
} | Select-Object Status, Class, FriendlyName, InstanceId | Format-List | Out-String -Width 200
