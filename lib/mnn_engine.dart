library;

export 'src/mnn_engine_exception.dart';
export 'src/mnn_models.dart';

import 'mnn_engine_platform_interface.dart';
import 'src/mnn_models.dart';

class MnnEngine {
  MnnEngine._();

  static final MnnEngine instance = MnnEngine._();

  Future<MnnEngineInfo> initialize() => MnnEnginePlatform.instance.initialize();

  Future<MnnRuntimeSnapshot> getSnapshot() =>
      MnnEnginePlatform.instance.getSnapshot();

  Future<String> getTestRootPath() =>
      MnnEnginePlatform.instance.getTestRootPath();

  Future<List<MnnModelInfo>> listImportedModels() =>
      MnnEnginePlatform.instance.listImportedModels();

  Future<MnnModelInfo> importModelDirectory({bool replaceExisting = true}) =>
      MnnEnginePlatform.instance.importModelDirectory(
        replaceExisting: replaceExisting,
      );

  Future<void> deleteImportedModel(String modelId) =>
      MnnEnginePlatform.instance.deleteImportedModel(modelId);

  Future<MnnModelInfo> loadModel(String modelId) =>
      MnnEnginePlatform.instance.loadModel(modelId);

  Future<void> unloadModel() => MnnEnginePlatform.instance.unloadModel();

  Future<MnnPortCheckResult> checkPort({
    String host = '127.0.0.1',
    required int port,
  }) => MnnEnginePlatform.instance.checkPort(host: host, port: port);

  Future<MnnServerInfo> startServer({
    String host = '127.0.0.1',
    int port = 8081,
    String? apiKey,
  }) => MnnEnginePlatform.instance.startServer(
    host: host,
    port: port,
    apiKey: apiKey,
  );

  Future<void> stopServer() => MnnEnginePlatform.instance.stopServer();

  Future<void> cancelGeneration() =>
      MnnEnginePlatform.instance.cancelGeneration();

  Future<List<MnnLogEntry>> getLogSnapshot() =>
      MnnEnginePlatform.instance.getLogSnapshot();

  Future<void> clearLogs() => MnnEnginePlatform.instance.clearLogs();

  Stream<MnnRuntimeEvent> get events => MnnEnginePlatform.instance.events;

  Stream<MnnLogEntry> get logs => MnnEnginePlatform.instance.logs;
}
