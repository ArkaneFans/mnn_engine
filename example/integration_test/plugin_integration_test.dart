import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:mnn_engine/mnn_engine.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('initializes the Android MNN engine plugin', (tester) async {
    final info = await MnnEngine.instance.initialize();

    expect(info.abi, isNotEmpty);
    expect(info.testRootPath, contains('mnn_test'));
  });
}
