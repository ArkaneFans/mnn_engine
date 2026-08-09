import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mnn_engine_example/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.arkanefans.mnn_engine/methods');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
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
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows initialized engine information', (tester) async {
    await tester.pumpWidget(const MnnEngineExampleApp());
    await tester.pumpAndSettle();

    expect(find.textContaining('MNN 3.6.0'), findsOneWidget);
    expect(find.textContaining('Native loaded: true'), findsOneWidget);
  });
}
