/// Represents the result of a clone launch attempt
class LaunchResult {
  final bool success;
  final String? error;
  final String cloneId;
  final String launchMethod;
  final Map<String, dynamic>? diagnostics;
  final Duration? launchDuration;

  LaunchResult({
    required this.success,
    this.error,
    required this.cloneId,
    required this.launchMethod,
    this.diagnostics,
    this.launchDuration,
  });

  Map<String, dynamic> toJson() => {
    'success': success,
    'error': error,
    'cloneId': cloneId,
    'launchMethod': launchMethod,
    'diagnostics': diagnostics,
    'launchDurationMs': launchDuration?.inMilliseconds,
  };

  @override
  String toString() {
    return 'LaunchResult(success: $success, method: $launchMethod, error: $error)';
  }
}

/// Represents a log entry for clone operations
class CloneLogEntry {
  final DateTime timestamp;
  final String operation;
  final String message;
  final String? cloneId;
  final Map<String, dynamic>? data;
  final String? error;
  final String? stackTrace;
  final LogLevel level;

  CloneLogEntry({
    required this.timestamp,
    required this.operation,
    required this.message,
    this.cloneId,
    this.data,
    this.error,
    this.stackTrace,
    this.level = LogLevel.info,
  });

  Map<String, dynamic> toJson() => {
    'timestamp': timestamp.toIso8601String(),
    'operation': operation,
    'message': message,
    'cloneId': cloneId,
    'data': data,
    'error': error,
    'stackTrace': stackTrace,
    'level': level.name,
  };
}

enum LogLevel { debug, info, warning, error }
