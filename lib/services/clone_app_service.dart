import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/cloned_app.dart';

// Platform-specific imports
import 'package:installed_apps/app_info.dart';
import 'package:installed_apps/installed_apps.dart';
import 'package:external_app_launcher/external_app_launcher.dart';

class CloneAppService {
  static const String _clonedAppsKey = 'cloned_apps';

  // Get all installed apps
  Future<List<AppInfo>> getInstalledApps() async {
    if (kIsWeb) {
      // Return mock data for web platform
      return _getMockApps();
    }

    try {
      List<AppInfo> apps = await InstalledApps.getInstalledApps(
        false, // includeSystemApps
        true, // includeAppIcons
      );

      // Filter and sort apps
      apps = apps
          .where((app) => app.packageName.isNotEmpty && app.name.isNotEmpty)
          .toList();

      // Sort apps alphabetically
      apps.sort((a, b) => a.name.compareTo(b.name));
      return apps;
    } catch (e) {
      return kIsWeb ? _getMockApps() : [];
    }
  }

  // Mock apps for web platform
  List<AppInfo> _getMockApps() {
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    return [
      AppInfo(
        name: "WhatsApp",
        packageName: "com.whatsapp",
        icon: null,
        versionName: "2.23.24.12",
        versionCode: 1234567,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "Instagram",
        packageName: "com.instagram.android",
        icon: null,
        versionName: "304.0.0.34.76",
        versionCode: 2345678,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "Facebook",
        packageName: "com.facebook.katana",
        icon: null,
        versionName: "440.0.0.33.69",
        versionCode: 3456789,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "Twitter",
        packageName: "com.twitter.android",
        icon: null,
        versionName: "10.26.0",
        versionCode: 4567890,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "TikTok",
        packageName: "com.zhiliaoapp.musically",
        icon: null,
        versionName: "32.5.4",
        versionCode: 5678901,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "Telegram",
        packageName: "org.telegram.messenger",
        icon: null,
        versionName: "10.2.5",
        versionCode: 6789012,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "LinkedIn",
        packageName: "com.linkedin.android",
        icon: null,
        versionName: "4.1.703",
        versionCode: 7890123,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
      AppInfo(
        name: "Netflix",
        packageName: "com.netflix.mediaclient",
        icon: null,
        versionName: "8.86.0",
        versionCode: 8901234,
        builtWith: BuiltWith.flutter,
        installedTimestamp: timestamp,
      ),
    ];
  }

  // Get all cloned apps
  Future<List<ClonedApp>> getClonedApps() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final clonedAppsJson = prefs.getStringList(_clonedAppsKey) ?? [];

      return clonedAppsJson
          .map((jsonString) => ClonedApp.fromJson(json.decode(jsonString)))
          .toList();
    } catch (e) {
      return [];
    }
  }

  // Add a new cloned app
  Future<bool> addClonedApp(AppInfo app) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final existingApps = await getClonedApps();

      // Check if app is already cloned
      bool alreadyExists = existingApps.any(
        (clonedApp) => clonedApp.packageName == app.packageName,
      );

      if (alreadyExists) {
        return false; // App already cloned
      }

      String? iconBase64;
      if (app.icon != null) {
        try {
          iconBase64 = base64Encode(app.icon!);
        } catch (e) {
          iconBase64 = null;
        }
      }

      final clonedApp = ClonedApp(
        id: DateTime.now().millisecondsSinceEpoch.toString(),
        appName: app.name,
        packageName: app.packageName,
        appIcon: iconBase64,
        createdAt: DateTime.now(),
      );

      existingApps.add(clonedApp);

      final clonedAppsJson = existingApps
          .map((app) => json.encode(app.toJson()))
          .toList();

      await prefs.setStringList(_clonedAppsKey, clonedAppsJson);
      return true;
    } catch (e) {
      return false;
    }
  }

  // Launch a cloned app
  Future<bool> launchClonedApp(ClonedApp clonedApp) async {
    if (kIsWeb) {
      // Web demo mode - simulate app launch
      return true;
    }

    try {
      await LaunchApp.openApp(androidPackageName: clonedApp.packageName);
      return true;
    } catch (e) {
      return false;
    }
  }

  // Remove a cloned app
  Future<bool> removeClonedApp(String id) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final existingApps = await getClonedApps();

      existingApps.removeWhere((app) => app.id == id);

      final clonedAppsJson = existingApps
          .map((app) => json.encode(app.toJson()))
          .toList();

      await prefs.setStringList(_clonedAppsKey, clonedAppsJson);
      return true;
    } catch (e) {
      return false;
    }
  }
}
