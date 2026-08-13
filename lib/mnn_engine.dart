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

  Future<MnnModelImportResult> importModelDirectoryWithResult({
    bool replaceExisting = false,
    bool autoRename = true,
    List<String> unavailableNames = const <String>[],
  }) => MnnEnginePlatform.instance.importModelDirectoryWithResult(
    replaceExisting: replaceExisting,
    autoRename: autoRename,
    unavailableNames: unavailableNames,
  );

  /// Imports a model directory the app already owns (e.g. a finished
  /// download in private storage) without going through the SAF picker.
  Future<MnnModelInfo> importModelFromPath(
    String directoryPath, {
    bool replaceExisting = true,
  }) => MnnEnginePlatform.instance.importModelFromPath(
    directoryPath,
    replaceExisting: replaceExisting,
  );

  Future<MnnModelImportResult> importModelFromPathWithResult(
    String directoryPath, {
    bool replaceExisting = false,
    bool autoRename = true,
    List<String> unavailableNames = const <String>[],
  }) => MnnEnginePlatform.instance.importModelFromPathWithResult(
    directoryPath,
    replaceExisting: replaceExisting,
    autoRename: autoRename,
    unavailableNames: unavailableNames,
  );

  Future<void> deleteImportedModel(String modelId) =>
      MnnEnginePlatform.instance.deleteImportedModel(modelId);

  /// Renames the model directory and therefore changes its runtime model ID.
  /// The model and API server must be stopped before this operation.
  Future<MnnModelInfo> renameImportedModel(String modelId, String newName) =>
      MnnEnginePlatform.instance.renameImportedModel(modelId, newName);

  Future<MnnModelInfo> loadModel(String modelId) =>
      MnnEnginePlatform.instance.loadModel(modelId);

  Future<void> unloadModel() => MnnEnginePlatform.instance.unloadModel();

  /// Performs an advisory port probe. The authoritative result is the bind
  /// performed by [startServer], whose `port_in_use` error should be handled
  /// by callers.
  Future<MnnPortCheckResult> checkPort({
    MnnServerBindMode bindMode = MnnServerBindMode.loopback,
    required int port,
  }) => MnnEnginePlatform.instance.checkPort(bindMode: bindMode, port: port);

  Future<MnnServerInfo> startServer({
    MnnServerBindMode bindMode = MnnServerBindMode.loopback,
    int port = 8081,
    String? apiKey,
  }) => MnnEnginePlatform.instance.startServer(
    bindMode: bindMode,
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
