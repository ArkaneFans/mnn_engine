import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mnn_engine/mnn_engine.dart'
    show MnnEngineException, MnnServerBindMode;
import 'package:mnn_engine/mnn_engine_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final platform = MethodChannelMnnEngine();
  const channel = MethodChannel('com.arkanefans.mnn_engine/methods');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('initialize decodes native engine information', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'initialize');
          return <String, Object?>{
            'pluginVersion': '0.0.1',
            'mnnVersion': '3.6.0',
            'mnnCommit': 'cc20f672',
            'abi': 'arm64-v8a',
            'androidApiLevel': 35,
            'ndkVersion': '27.3.13750724',
            'nativeLibraryLoaded': true,
            'testRootPath': '/data/user/0/app/files/mnn_test',
          };
        });

    final info = await platform.initialize();

    expect(info.mnnVersion, '3.6.0');
    expect(info.mnnCommit, 'cc20f672');
    expect(info.nativeLibraryLoaded, isTrue);
  });

  test('checkPort sends host and port', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'checkPort');
          expect(call.arguments, <String, Object?>{
            'bindMode': 'loopback',
            'port': 8081,
          });
          return <String, Object?>{'available': true, 'ownedByMnn': false};
        });

    final result = await platform.checkPort(
      bindMode: MnnServerBindMode.loopback,
      port: 8081,
    );

    expect(result.available, isTrue);
    expect(result.ownedByMnn, isFalse);
  });

  test('allows allInterfaces start without an API key', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'startServer');
          expect(call.arguments, <String, Object?>{
            'bindMode': 'allInterfaces',
            'port': 8081,
            'apiKey': null,
          });
          return <String, Object?>{
            'running': true,
            'host': '0.0.0.0',
            'bindMode': 'allInterfaces',
            'bindAddress': '0.0.0.0',
            'port': 8081,
            'baseUrl': 'http://127.0.0.1:8081',
            'localBaseUrl': 'http://127.0.0.1:8081',
            'advertisedUrls': <String>['http://192.168.1.2:8081'],
            'requiresApiKey': false,
          };
        });

    final server = await platform.startServer(
      bindMode: MnnServerBindMode.allInterfaces,
      port: 8081,
    );

    expect(server.bindMode, MnnServerBindMode.allInterfaces);
    expect(server.bindAddress, '0.0.0.0');
    expect(server.requiresApiKey, isFalse);
  });

  test('loadModel decodes native load duration', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'loadModel');
          expect(call.arguments, <String, Object?>{'modelId': 'qwen'});
          return <String, Object?>{
            'modelId': 'qwen',
            'modelKey': 'qwen',
            'displayName': 'Qwen',
            'modelDirPath': '/models/qwen',
            'configPath': '/models/qwen/config.json',
            'sizeBytes': 100,
            'importedAt': 1,
            'isActive': true,
            'loadDurationMs': 1234,
          };
        });

    final model = await platform.loadModel('qwen');

    expect(model.loadDurationMs, 1234);
  });

  test('importModelFromPath forwards the private directory', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'importModelFromPath');
          expect(call.arguments, <String, Object?>{
            'directoryPath': '/data/user/0/app/files/downloads/model',
            'replaceExisting': false,
          });
          return <String, Object?>{
            'modelId': 'qwen',
            'modelKey': 'qwen',
            'displayName': 'Qwen',
            'modelDirPath': '/data/user/0/app/files/mnn/models/qwen',
            'configPath': '/data/user/0/app/files/mnn/models/qwen/config.json',
            'sizeBytes': 100,
            'importedAt': 1,
            'isActive': false,
          };
        });

    final model = await platform.importModelFromPath(
      '/data/user/0/app/files/downloads/model',
      replaceExisting: false,
    );

    expect(model.modelId, 'qwen');
    expect(model.modelDirPath, contains('/mnn/models/qwen'));
  });

  test('preserves stable native error codes and details', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(
            code: 'port_in_use',
            message: 'Port is unavailable.',
            details: <String, Object?>{'port': 8081},
          );
        });

    await expectLater(
      platform.startServer(bindMode: MnnServerBindMode.loopback, port: 8081),
      throwsA(
        isA<MnnEngineException>()
            .having((error) => error.code, 'code', 'port_in_use')
            .having((error) => error.details, 'details', <String, Object?>{
              'port': 8081,
            }),
      ),
    );
  });
}
