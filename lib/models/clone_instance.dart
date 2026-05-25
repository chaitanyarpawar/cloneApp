/// Represents an active virtualized clone instance
class CloneInstance {
  final String cloneId;
  final String packageName;
  final String appName;
  final DateTime startTime;
  final int? processId;
  final String status;
  final Map<String, dynamic>? metadata;

  CloneInstance({
    required this.cloneId,
    required this.packageName,
    required this.appName,
    required this.startTime,
    this.processId,
    this.status = 'running',
    this.metadata,
  });

  factory CloneInstance.fromJson(Map<String, dynamic> json) {
    return CloneInstance(
      cloneId: json['cloneId'] as String,
      packageName: json['packageName'] as String,
      appName: json['appName'] as String? ?? '',
      startTime: json['startTime'] != null
          ? DateTime.parse(json['startTime'] as String)
          : DateTime.now(),
      processId: json['processId'] as int?,
      status: json['status'] as String? ?? 'running',
      metadata: json['metadata'] as Map<String, dynamic>?,
    );
  }

  Map<String, dynamic> toJson() => {
    'cloneId': cloneId,
    'packageName': packageName,
    'appName': appName,
    'startTime': startTime.toIso8601String(),
    'processId': processId,
    'status': status,
    'metadata': metadata,
  };

  Duration get uptime => DateTime.now().difference(startTime);
}
