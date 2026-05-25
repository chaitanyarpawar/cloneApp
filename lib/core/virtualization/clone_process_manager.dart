import '../../services/clone_logger.dart';

/// Manages clone processes and instances
/// Tracks active clones, handles lifecycle, prevents conflicts
class CloneProcessManager {
  final CloneLogger _logger = CloneLogger();
  final Map<String, ProcessInfo> _activeProcesses = {};

  /// Register a new clone process
  void registerProcess(String cloneId, ProcessInfo info) {
    _activeProcesses[cloneId] = info;
    _logger.log(
      'ProcessManager',
      'Process registered',
      cloneId: cloneId,
      data: {'pid': info.processId, 'package': info.packageName},
    );
  }

  /// Unregister a clone process
  void unregisterProcess(String cloneId) {
    _activeProcesses.remove(cloneId);
    _logger.log('ProcessManager', 'Process unregistered', cloneId: cloneId);
  }

  /// Get active process info
  ProcessInfo? getProcessInfo(String cloneId) {
    return _activeProcesses[cloneId];
  }

  /// Get all active processes
  List<ProcessInfo> getActiveProcesses() {
    return _activeProcesses.values.toList();
  }

  /// Check if clone is running
  bool isCloneRunning(String cloneId) {
    return _activeProcesses.containsKey(cloneId);
  }

  /// Get number of active clones for a package
  int getActiveCloneCount(String packageName) {
    return _activeProcesses.values
        .where((p) => p.packageName == packageName)
        .length;
  }

  /// Clear all processes (cleanup)
  void clearAllProcesses() {
    _activeProcesses.clear();
  }
}

/// Information about a clone process
class ProcessInfo {
  final String cloneId;
  final String packageName;
  final int? processId;
  final DateTime startTime;
  final String storagePath;

  ProcessInfo({
    required this.cloneId,
    required this.packageName,
    this.processId,
    required this.startTime,
    required this.storagePath,
  });

  Map<String, dynamic> toJson() => {
    'cloneId': cloneId,
    'packageName': packageName,
    'processId': processId,
    'startTime': startTime.toIso8601String(),
    'storagePath': storagePath,
  };
}
