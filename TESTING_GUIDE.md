# Multi-Instance Testing Guide

## What's New in This Version

1. **Fixed CloneActivity**: Now properly launches target apps instead of trying to be a Flutter activity
2. **Enhanced Launch Methods**: Added multiple approaches to launch apps with separate instances:
   - Direct multi-instance launch
   - Force restart method (kills existing instances first)
   - Virtual container approach
   - Backup method for stubborn apps

3. **Better Debugging**: Added extensive logging to help identify issues

## Testing Steps for Multiple Instances

### Method 1: Using the App Interface
1. Install the new APK: `build\app\outputs\flutter-apk\app-release.apk`
2. Open CloneApp
3. Find and select "Kite - Zerodha" (or any app you want to clone)
4. Tap "Add Clone" to create a clone entry
5. Tap the clone app icon in your cloned apps list
6. The app should try multiple launch methods

### Method 2: Direct Testing with ADB (For Debugging)

If you want to test the functionality directly, you can use these ADB commands:

```bash
# Install the APK
adb install -r build\app\outputs\flutter-apk\app-release.apk

# Launch first instance of Kite normally
adb shell am start -n com.zerodha.kite3/.startup.SplashActivity

# Launch second instance using multi-task flags (what our app does)
adb shell am start -n com.zerodha.kite3/.startup.SplashActivity --activity-new-task --activity-multiple-task --activity-new-document

# Or launch through our clone activity
adb shell am start -n com.cloneapp.multiaccount/.CloneActivity -e TARGET_PACKAGE com.zerodha.kite3 -e CLONE_ID test123
```

### Method 3: Check Logs for Debugging

To see what's happening behind the scenes:

```bash
# Watch logs from our app
adb logcat -s CloneApp

# Or watch all logs during launch
adb logcat | grep -E "(CloneApp|Kite|multiaccount)"
```

## What to Look For

### Success Indicators:
- Two separate Kite app instances appearing in recent apps
- Ability to login to different accounts in each instance
- Apps staying separate when switching between them

### Troubleshooting:

#### If Second Instance Doesn't Launch:
1. Check if the target app (like Kite) has restrictions in its manifest
2. Some apps detect multiple instances and prevent them
3. Try with other apps like WhatsApp, Instagram, or Telegram first

#### If Apps Keep Closing:
1. The app might be detecting cloning attempts
2. Try the "Force Restart" method which kills the first instance before launching the second

#### If Nothing Happens:
1. Check app permissions in Android settings
2. Make sure the target app is installed
3. Check logs using ADB

## Apps That Work Well for Testing:
- WhatsApp (`com.whatsapp`)
- Telegram (`org.telegram.messenger`) 
- Instagram (`com.instagram.android`)
- Facebook (`com.facebook.katana`)

## Apps That May Be Challenging:
- Banking apps (have security restrictions)
- Some games (detect multiple instances)
- Apps with special DRM protection

## Next Steps if Issues Persist:

1. **Check Android Version**: Some methods work better on different Android versions
2. **Try Different Apps**: Start with messaging apps that are known to support dual modes
3. **Device Permissions**: Make sure CloneApp has all required permissions
4. **Manufacturer Restrictions**: Some OEMs (Samsung, Xiaomi) have their own dual app features that may interfere

## Key Improvements Made:

1. **CloneActivity** now properly launches target apps instead of creating Flutter instances
2. **Multiple launch strategies** to handle different app types and restrictions
3. **Better error handling** and logging for debugging
4. **Force restart option** for apps that resist multiple instances
5. **Unique identifiers** added to each instance to help with separation

The app now tries 4 different methods in sequence to launch multiple instances, so it should work with more apps than before.