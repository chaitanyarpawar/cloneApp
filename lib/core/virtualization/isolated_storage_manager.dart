import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import '../../services/clone_logger.dart';

/// Manages isolated storage for each clone instance
/// Each clone gets unique:
/// - Storage directory
/// - Database files
/// - Cache
/// - SharedPreferences
/// - Temp files
///
/// Prevents shared static state between clones
class IsolatedStorageManager {
  final CloneLogger _logger = CloneLogger();

  /// Create isolated storage for a clone
  Future<StorageResult> createIsolatedStorage({
    required String cloneId,
    required String packageName,
  }) async {
    try {
      _logger.log(
        'StorageManager',
        'Creating isolated storage',
        cloneId: cloneId,
        data: {'packageName': packageName},
      );

      if (kIsWeb) {
        return StorageResult(
          success: true,
          storagePath: '/web/storage/$cloneId',
        );
      }

      // Get app's private directory
      final appDir = await getApplicationDocumentsDirectory();

      // Create clone-specific directory structure
      final cloneRoot = Directory('${appDir.path}/clones/$cloneId');
      final dataDir = Directory('${cloneRoot.path}/data');
      final cacheDir = Directory('${cloneRoot.path}/cache');
      final dbDir = Directory('${cloneRoot.path}/databases');
      final filesDir = Directory('${cloneRoot.path}/files');
      final prefsDir = Directory('${cloneRoot.path}/shared_prefs');

      // Create all directories
      await Future.wait([
        cloneRoot.create(recursive: true),
        dataDir.create(recursive: true),
        cacheDir.create(recursive: true),
        dbDir.create(recursive: true),
        filesDir.create(recursive: true),
        prefsDir.create(recursive: true),
      ]);

      // Create metadata file
      final metadataFile = File('${cloneRoot.path}/metadata.json');
      await metadataFile.writeAsString('''
{
  "cloneId": "$cloneId",
  "packageName": "$packageName",
  "createdAt": "${DateTime.now().toIso8601String()}",
  "version": 1,
  "isolated": true
}
''');

      _logger.log(
        'StorageManager',
        'Isolated storage created',
        cloneId: cloneId,
        data: {'path': cloneRoot.path},
      );

      return StorageResult(
        success: true,
        storagePath: cloneRoot.path,
        dataPath: dataDir.path,
        cachePath: cacheDir.path,
        dbPath: dbDir.path,
      );
    } catch (e) {
      _logger.logError(
        'StorageManager',
        'Failed to create isolated storage',
        error: e,
        cloneId: cloneId,
      );

      return StorageResult(
        success: false,
        error: 'Storage creation failed: ${e.toString()}',
      );
    }
  }

  /// Get storage path for a clone
  Future<String?> getStoragePath(String cloneId) async {
    try {
      if (kIsWeb) return '/web/storage/$cloneId';

      final appDir = await getApplicationDocumentsDirectory();
      final cloneRoot = Directory('${appDir.path}/clones/$cloneId');

      if (await cloneRoot.exists()) {
        return cloneRoot.path;
      }

      return null;
    } catch (e) {
      return null;
    }
  }

  /// Get storage size for a clone
  Future<int> getStorageSize(String cloneId) async {
    try {
      final storagePath = await getStoragePath(cloneId);
      if (storagePath == null) return 0;

      if (kIsWeb) return 0;

      final directory = Directory(storagePath);
      if (!await directory.exists()) return 0;

      int totalSize = 0;
      await for (final entity in directory.list(recursive: true)) {
        if (entity is File) {
          totalSize += await entity.length();
        }
      }

      return totalSize;
    } catch (e) {
      return 0;
    }
  }

  /// Clear cache for a clone
  Future<bool> clearCache(String cloneId) async {
    try {
      final storagePath = await getStoragePath(cloneId);
      if (storagePath == null) return false;

      if (kIsWeb) return true;

      final cacheDir = Directory('$storagePath/cache');
      if (await cacheDir.exists()) {
        await cacheDir.delete(recursive: true);
        await cacheDir.create();
      }

      _logger.log('StorageManager', 'Cache cleared', cloneId: cloneId);

      return true;
    } catch (e) {
      _logger.logError(
        'StorageManager',
        'Failed to clear cache',
        error: e,
        cloneId: cloneId,
      );
      return false;
    }
  }

  /// Cleanup storage for a clone
  Future<bool> cleanupCloneStorage(String cloneId) async {
    try {
      _logger.log('StorageManager', 'Cleaning up storage', cloneId: cloneId);

      final storagePath = await getStoragePath(cloneId);
      if (storagePath == null) return false;

      if (kIsWeb) return true;

      final directory = Directory(storagePath);
      if (await directory.exists()) {
        await directory.delete(recursive: true);
      }

      _logger.log('StorageManager', 'Storage cleaned up', cloneId: cloneId);

      return true;
    } catch (e) {
      _logger.logError(
        'StorageManager',
        'Failed to cleanup storage',
        error: e,
        cloneId: cloneId,
      );
      return false;
    }
  }

  /// Get all clone storage directories
  Future<List<String>> getAllCloneStorages() async {
    try {
      if (kIsWeb) return [];

      final appDir = await getApplicationDocumentsDirectory();
      final clonesDir = Directory('${appDir.path}/clones');

      if (!await clonesDir.exists()) return [];

      final List<String> cloneIds = [];
      await for (final entity in clonesDir.list()) {
        if (entity is Directory) {
          final name = entity.path.split(Platform.pathSeparator).last;
          cloneIds.add(name);
        }
      }

      return cloneIds;
    } catch (e) {
      return [];
    }
  }

  /// Validate storage integrity for a clone
  Future<bool> validateStorageIntegrity(String cloneId) async {
    try {
      final storagePath = await getStoragePath(cloneId);
      if (storagePath == null) return false;

      if (kIsWeb) return true;

      // Check required directories exist
      final requiredDirs = [
        '$storagePath/data',
        '$storagePath/cache',
        '$storagePath/databases',
        '$storagePath/files',
        '$storagePath/shared_prefs',
      ];

      for (final dirPath in requiredDirs) {
        final dir = Directory(dirPath);
        if (!await dir.exists()) {
          _logger.logWarning(
            'StorageManager',
            'Missing directory: $dirPath',
            cloneId: cloneId,
          );
          return false;
        }
      }

      // Check metadata file
      final metadataFile = File('$storagePath/metadata.json');
      if (!await metadataFile.exists()) {
        _logger.logWarning(
          'StorageManager',
          'Missing metadata file',
          cloneId: cloneId,
        );
        return false;
      }

      return true;
    } catch (e) {
      return false;
    }
  }

  /// Repair corrupted storage
  Future<bool> repairStorage(String cloneId, String packageName) async {
    try {
      _logger.log(
        'StorageManager',
        'Attempting storage repair',
        cloneId: cloneId,
      );

      // Delete and recreate
      await cleanupCloneStorage(cloneId);
      final result = await createIsolatedStorage(
        cloneId: cloneId,
        packageName: packageName,
      );

      return result.success;
    } catch (e) {
      _logger.logError(
        'StorageManager',
        'Storage repair failed',
        error: e,
        cloneId: cloneId,
      );
      return false;
    }
  }
}

/// Result of storage operations
class StorageResult {
  final bool success;
  final String? error;
  final String? storagePath;
  final String? dataPath;
  final String? cachePath;
  final String? dbPath;

  StorageResult({
    required this.success,
    this.error,
    this.storagePath,
    this.dataPath,
    this.cachePath,
    this.dbPath,
  });

  Map<String, dynamic> toJson() => {
    'success': success,
    'error': error,
    'storagePath': storagePath,
    'dataPath': dataPath,
    'cachePath': cachePath,
    'dbPath': dbPath,
  };
}
