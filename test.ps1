$pkg = "com.example.vpbankcontroller"
$activity = "$pkg/.MainActivity"
$deviceId = "QGIZHEOVSW7DW8R4"
$results = @()

for ($i = 1; $i -le 3; $i++) {
    Write-Host "Attempt $i..."
    adb -s $deviceId shell am force-stop $pkg
    adb -s $deviceId shell am start -n $activity
    Start-Sleep -Seconds 5

    adb -s $deviceId shell uiautomator dump /sdcard/view.xml
    adb -s $deviceId pull /sdcard/view.xml view.xml

    [xml]$xml = Get-Content view.xml
    $node = $xml.SelectSingleNode("//node[@resource-id='com.example.vpbankcontroller:id/btn_open_app']")
    
    if ($node -eq $null) {
        $results += "Attempt $i: FAIL (btn_open_app not found)"
        continue
    }

    $bounds = $node.bounds
    if ($bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x1, $y1, $x2, $y2 = [int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4]
        $cx = [int](($x1 + $x2) / 2)
        $cy = [int](($y1 + $y2) / 2)
        
        adb -s $deviceId shell input tap $cx $cy
        Start-Sleep -Seconds 5

        $topResumed = adb -s $deviceId shell dumpsys activity topResumedActivity
        $currentFocus = adb -s $deviceId shell dumpsys window mCurrentFocus
        
        if ($topResumed -match "com\.vnpay\.vpbankonline" -or $currentFocus -match "com\.vnpay\.vpbankonline") {
            $results += "Attempt $i: PASS"
        } else {
            $results += "Attempt $i: FAIL (Not in foreground)"
        }
    } else {
        $results += "Attempt $i: FAIL (Invalid bounds)"
    }
}
$results | Out-String | Set-Content results.txt
