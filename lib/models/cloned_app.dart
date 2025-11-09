class ClonedApp {
  final String id;
  final String appName;
  final String packageName;
  final String? appIcon;
  final DateTime createdAt;

  ClonedApp({
    required this.id,
    required this.appName,
    required this.packageName,
    this.appIcon,
    required this.createdAt,
  });

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'appName': appName,
      'packageName': packageName,
      'appIcon': appIcon,
      'createdAt': createdAt.toIso8601String(),
    };
  }

  factory ClonedApp.fromJson(Map<String, dynamic> json) {
    return ClonedApp(
      id: json['id'],
      appName: json['appName'],
      packageName: json['packageName'],
      appIcon: json['appIcon'],
      createdAt: DateTime.parse(json['createdAt']),
    );
  }
}
