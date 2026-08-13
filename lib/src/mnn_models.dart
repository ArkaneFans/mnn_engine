typedef JsonMap = Map<String, Object?>;

enum MnnServerBindMode { loopback, allInterfaces }

Map<String, Object?> _map(Object? value) {
  return Map<String, Object?>.from(value! as Map);
}

class MnnEngineInfo {
  const MnnEngineInfo({
    required this.pluginVersion,
    required this.mnnVersion,
    required this.mnnCommit,
    required this.abi,
    required this.androidApiLevel,
    required this.ndkVersion,
    required this.nativeLibraryLoaded,
    required this.testRootPath,
  });

  factory MnnEngineInfo.fromMap(Object? value) {
    final map = _map(value);
    return MnnEngineInfo(
      pluginVersion: map['pluginVersion'] as String? ?? 'unknown',
      mnnVersion: map['mnnVersion'] as String? ?? 'unavailable',
      mnnCommit: map['mnnCommit'] as String? ?? 'unknown',
      abi: map['abi'] as String? ?? 'unknown',
      androidApiLevel: (map['androidApiLevel'] as num?)?.toInt() ?? 0,
      ndkVersion: map['ndkVersion'] as String? ?? 'unknown',
      nativeLibraryLoaded: map['nativeLibraryLoaded'] as bool? ?? false,
      testRootPath: map['testRootPath'] as String? ?? '',
    );
  }

  final String pluginVersion;
  final String mnnVersion;
  final String mnnCommit;
  final String abi;
  final int androidApiLevel;
  final String ndkVersion;
  final bool nativeLibraryLoaded;
  final String testRootPath;
}

class MnnModelInfo {
  const MnnModelInfo({
    required this.modelId,
    required this.modelKey,
    required this.displayName,
    required this.modelDirPath,
    required this.configPath,
    required this.sizeBytes,
    required this.importedAt,
    required this.isActive,
    this.supportsVision = false,
    this.supportsToolCalling = false,
    this.loadDurationMs,
    this.vendor,
    this.validationWarnings = const [],
  });

  factory MnnModelInfo.fromMap(Object? value) {
    final map = _map(value);
    return MnnModelInfo(
      modelId: map['modelId'] as String? ?? '',
      modelKey: map['modelKey'] as String? ?? '',
      displayName: map['displayName'] as String? ?? '',
      vendor: map['vendor'] as String?,
      modelDirPath: map['modelDirPath'] as String? ?? '',
      configPath: map['configPath'] as String? ?? '',
      sizeBytes: (map['sizeBytes'] as num?)?.toInt() ?? 0,
      importedAt: (map['importedAt'] as num?)?.toInt() ?? 0,
      isActive: map['isActive'] as bool? ?? false,
      supportsVision: map['supportsVision'] as bool? ?? false,
      supportsToolCalling: map['supportsToolCalling'] as bool? ?? false,
      loadDurationMs: (map['loadDurationMs'] as num?)?.toInt(),
      validationWarnings:
          (map['validationWarnings'] as List?)?.whereType<String>().toList(
            growable: false,
          ) ??
          const [],
    );
  }

  final String modelId;
  final String modelKey;
  final String displayName;
  final String? vendor;
  final String modelDirPath;
  final String configPath;
  final int sizeBytes;
  final int importedAt;
  final bool isActive;
  final bool supportsVision;
  final bool supportsToolCalling;
  final int? loadDurationMs;
  final List<String> validationWarnings;
}

class MnnModelImportResult {
  const MnnModelImportResult({
    required this.requestedModelName,
    required this.model,
  });

  factory MnnModelImportResult.fromMap(Object? value) {
    final map = _map(value);
    return MnnModelImportResult(
      requestedModelName: map['requestedModelName'] as String? ?? '',
      model: MnnModelInfo.fromMap(map['model']),
    );
  }

  final String requestedModelName;
  final MnnModelInfo model;

  bool get wasRenamed =>
      requestedModelName.toLowerCase() != model.modelKey.toLowerCase();
}

class MnnServerInfo {
  const MnnServerInfo({
    required this.running,
    required this.host,
    required this.bindMode,
    required this.bindAddress,
    required this.port,
    required this.baseUrl,
    required this.localBaseUrl,
    this.advertisedUrls = const [],
    this.requiresApiKey = false,
    this.startedAt,
    this.startDurationMs,
  });

  factory MnnServerInfo.fromMap(Object? value) {
    final map = _map(value);
    return MnnServerInfo(
      running: map['running'] as bool? ?? false,
      host: map['host'] as String? ?? '127.0.0.1',
      bindMode: MnnServerBindMode.values.byName(
        map['bindMode'] as String? ?? 'loopback',
      ),
      bindAddress: map['bindAddress'] as String? ?? '127.0.0.1',
      port: (map['port'] as num?)?.toInt() ?? 0,
      baseUrl: map['baseUrl'] as String? ?? '',
      localBaseUrl:
          map['localBaseUrl'] as String? ?? map['baseUrl'] as String? ?? '',
      advertisedUrls:
          (map['advertisedUrls'] as List?)?.whereType<String>().toList(
            growable: false,
          ) ??
          const [],
      requiresApiKey: map['requiresApiKey'] as bool? ?? false,
      startedAt: (map['startedAt'] as num?)?.toInt(),
      startDurationMs: (map['startDurationMs'] as num?)?.toInt(),
    );
  }

  final bool running;
  final String host;
  final MnnServerBindMode bindMode;
  final String bindAddress;
  final int port;
  final String baseUrl;
  final String localBaseUrl;
  final List<String> advertisedUrls;
  final bool requiresApiKey;
  final int? startedAt;
  final int? startDurationMs;
}

class MnnRuntimeSnapshot {
  const MnnRuntimeSnapshot({
    required this.revision,
    required this.engineState,
    required this.modelState,
    required this.serverState,
    required this.generationState,
    this.activeModel,
    this.server,
    this.lastError,
  });

  factory MnnRuntimeSnapshot.fromMap(Object? value) {
    final map = _map(value);
    return MnnRuntimeSnapshot(
      revision: (map['revision'] as num?)?.toInt() ?? 0,
      engineState: map['engineState'] as String? ?? 'uninitialized',
      modelState: map['modelState'] as String? ?? 'unloaded',
      serverState: map['serverState'] as String? ?? 'stopped',
      generationState: map['generationState'] as String? ?? 'idle',
      activeModel: map['activeModel'] == null
          ? null
          : MnnModelInfo.fromMap(map['activeModel']),
      server: map['server'] == null
          ? null
          : MnnServerInfo.fromMap(map['server']),
      lastError: map['lastError'] as String?,
    );
  }

  final int revision;
  final String engineState;
  final String modelState;
  final String serverState;
  final String generationState;
  final MnnModelInfo? activeModel;
  final MnnServerInfo? server;
  final String? lastError;
}

class MnnRuntimeEvent {
  const MnnRuntimeEvent({required this.type, required this.snapshot});

  factory MnnRuntimeEvent.fromMap(Object? value) {
    final map = _map(value);
    return MnnRuntimeEvent(
      type: map['type'] as String? ?? 'snapshot',
      snapshot: MnnRuntimeSnapshot.fromMap(map['snapshot']),
    );
  }

  final String type;
  final MnnRuntimeSnapshot snapshot;
}

class MnnLogEntry {
  const MnnLogEntry({
    required this.sequence,
    required this.timestamp,
    required this.level,
    required this.tag,
    required this.message,
  });

  factory MnnLogEntry.fromMap(Object? value) {
    final map = _map(value);
    return MnnLogEntry(
      sequence: (map['sequence'] as num?)?.toInt() ?? 0,
      timestamp: (map['timestamp'] as num?)?.toInt() ?? 0,
      level: map['level'] as String? ?? 'info',
      tag: map['tag'] as String? ?? 'MNN',
      message: map['message'] as String? ?? '',
    );
  }

  final int sequence;
  final int timestamp;
  final String level;
  final String tag;
  final String message;
}

class MnnPortCheckResult {
  const MnnPortCheckResult({
    required this.available,
    required this.ownedByMnn,
    this.message,
  });

  factory MnnPortCheckResult.fromMap(Object? value) {
    final map = _map(value);
    return MnnPortCheckResult(
      available: map['available'] as bool? ?? false,
      ownedByMnn: map['ownedByMnn'] as bool? ?? false,
      message: map['message'] as String?,
    );
  }

  final bool available;
  final bool ownedByMnn;
  final String? message;
}
