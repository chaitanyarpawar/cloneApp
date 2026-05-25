import 'dart:convert';
import 'package:flutter/foundation.dart';
import '../models/launch_result.dart';

/// Centralized logging service for clone operations
/// Implements structured logging with log rotation and export capabilities
class CloneLogger {
  static final CloneLogger _instance = CloneLogger._internal();
  factory CloneLogger() => _instance;
  CloneLogger._internal();

  final List<CloneLogEntry> _logs = [];
  static const int _maxLogEntries = 500;
  static const int _maxExportEntries = 100;

  /// Log an informational message
  void log(
    String operation,
    String message, {
    String? cloneId,
    Map<String, dynamic>? data,
    LogLevel level = LogLevel.info,
  }) {
    final entry = CloneLogEntry(
      timestamp: DateTime.now(),
      operation: operation,
      message: message,
      cloneId: cloneId,
      data: data,
      level: level,
    );

    _addEntry(entry);
    _printLog(entry);
  }

  /// Log an error with optional stack trace
  void logError(
    String operation,
    String message, {
    Object? error,
    StackTrace? stackTrace,
    String? cloneId,
    Map<String, dynamic>? data,
  }) {
    final entry = CloneLogEntry(
      timestamp: DateTime.now(),
      operation: operation,
      message: message,
      cloneId: cloneId,
      data: data,
      error: error?.toString(),
      stackTrace: stackTrace?.toString(),
      level: LogLevel.error,
    );

    _addEntry(entry);
    _printLog(entry);
  }

  /// Log with warning level
  void logWarning(
    String operation,
    String message, {
    String? cloneId,
    Map<String, dynamic>? data,
  }) {
    log(
      operation,
      message,
      cloneId: cloneId,
      data: data,
      level: LogLevel.warning,
    );
  }

  /// Log with debug level
  void logDebug(
    String operation,
    String message, {
    String? cloneId,
    Map<String, dynamic>? data,
  }) {
    log(
      operation,
      message,
      cloneId: cloneId,
      data: data,
      level: LogLevel.debug,
    );
  }

  void _addEntry(CloneLogEntry entry) {
    _logs.add(entry);

    // Rotate logs if exceeding max entries
    if (_logs.length > _maxLogEntries) {
      _logs.removeRange(0, _logs.length - _maxLogEntries);
    }
  }

  void _printLog(CloneLogEntry entry) {
    final prefix =
        '[CloneApp][${entry.level.name.toUpperCase()}][${entry.operation}]';
    final clonePrefix = entry.cloneId != null ? '[${entry.cloneId}]' : '';

    debugPrint('$prefix$clonePrefix ${entry.message}');

    if (entry.data != null) {
      debugPrint('$prefix Data: ${json.encode(entry.data)}');
    }

    if (entry.error != null) {
      debugPrint('$prefix Error: ${entry.error}');
    }

    if (entry.stackTrace != null && entry.level == LogLevel.error) {
      debugPrint('$prefix StackTrace: ${entry.stackTrace}');
    }
  }

  /// Get recent logs for display
  List<Map<String, dynamic>> getRecentLogs({int count = 50}) {
    final startIndex = _logs.length > count ? _logs.length - count : 0;
    return _logs.sublist(startIndex).map((e) => e.toJson()).toList();
  }

  /// Get logs for a specific clone
  List<Map<String, dynamic>> getLogsForClone(String cloneId) {
    return _logs
        .where((e) => e.cloneId == cloneId)
        .map((e) => e.toJson())
        .toList();
  }

  /// Get error logs only
  List<Map<String, dynamic>> getErrorLogs() {
    return _logs
        .where((e) => e.level == LogLevel.error)
        .map((e) => e.toJson())
        .toList();
  }

  /// Export logs as JSON string for user feedback
  String exportLogsAsJson() {
    final exportLogs = _logs.length > _maxExportEntries
        ? _logs.sublist(_logs.length - _maxExportEntries)
        : _logs;

    return const JsonEncoder.withIndent(
      '  ',
    ).convert(exportLogs.map((e) => e.toJson()).toList());
  }

  /// Clear all logs
  void clearLogs() {
    _logs.clear();
  }

  /// Get log statistics
  Map<String, int> getLogStats() {
    return {
      'total': _logs.length,
      'errors': _logs.where((e) => e.level == LogLevel.error).length,
      'warnings': _logs.where((e) => e.level == LogLevel.warning).length,
      'info': _logs.where((e) => e.level == LogLevel.info).length,
      'debug': _logs.where((e) => e.level == LogLevel.debug).length,
    };
  }

  /// Log launch event lifecycle
  void logLaunchStart(String packageName, String cloneId) {
    log(
      'launchLifecycle',
      'Clone launch started',
      cloneId: cloneId,
      data: {
        'packageName': packageName,
        'startTime': DateTime.now().toIso8601String(),
      },
    );
  }

  void logLaunchComplete(
    String cloneId,
    bool success,
    String method,
    Duration duration,
  ) {
    log(
      'launchLifecycle',
      'Clone launch ${success ? 'succeeded' : 'failed'}',
      cloneId: cloneId,
      level: success ? LogLevel.info : LogLevel.error,
      data: {
        'success': success,
        'method': method,
        'durationMs': duration.inMilliseconds,
      },
    );
  }

  void logAdInitStart(String cloneId) {
    log('adInit', 'Ad SDK initialization started', cloneId: cloneId);
  }

  void logAdInitComplete(String cloneId, bool success, Duration duration) {
    log(
      'adInit',
      'Ad SDK initialization ${success ? 'completed' : 'failed/timeout'}',
      cloneId: cloneId,
      level: success ? LogLevel.info : LogLevel.warning,
      data: {'durationMs': duration.inMilliseconds},
    );
  }
}
