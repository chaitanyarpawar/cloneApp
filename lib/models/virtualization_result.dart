/// Result of virtualization operations
class VirtualizationResult {
  final bool success;
  final String cloneId;
  final String? error;
  final String stage;
  final Duration? launchDuration;
  final Map<String, dynamic>? diagnostics;

  VirtualizationResult({
    required this.success,
    required this.cloneId,
    this.error,
    required this.stage,
    this.launchDuration,
    this.diagnostics,
  });

  Map<String, dynamic> toJson() => {
    'success': success,
    'cloneId': cloneId,
    'error': error,
    'stage': stage,
    'launchDurationMs': launchDuration?.inMilliseconds,
    'diagnostics': diagnostics,
  };

  @override
  String toString() {
    return 'VirtualizationResult(success: $success, stage: $stage, error: $error)';
  }

  /// Human-readable error message
  String get userFriendlyError {
    if (success) return 'Launch successful';

    switch (stage) {
      case 'validation':
        if (error?.contains('not installed') == true) {
          return 'App is not installed on your device';
        }
        if (error?.contains('banking') == true ||
            error?.contains('SafetyNet') == true) {
          return 'This app cannot be cloned due to security restrictions';
        }
        if (error?.contains('storage') == true) {
          return 'Insufficient storage space available';
        }
        return error ?? 'Validation failed';

      case 'environment_init':
        return 'Failed to initialize clone environment. Please try again.';

      case 'storage_init':
        return 'Failed to create isolated storage. Check available space.';

      case 'launch':
        if (error?.contains('timeout') == true) {
          return 'Launch timed out. The app may be slow to start.';
        }
        return 'Failed to launch clone. Please try again.';

      default:
        return error ?? 'Unknown error occurred';
    }
  }

  /// Get retry recommendation
  bool get canRetry {
    return stage == 'launch' || stage == 'environment_init';
  }
}
