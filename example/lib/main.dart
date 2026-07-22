import 'package:flutter/material.dart';
import 'package:mnn_engine/mnn_engine.dart';

void main() => runApp(const MnnEngineExampleApp());

class MnnEngineExampleApp extends StatelessWidget {
  const MnnEngineExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(colorSchemeSeed: Colors.deepOrange),
      home: const MnnEngineExamplePage(),
    );
  }
}

class MnnEngineExamplePage extends StatefulWidget {
  const MnnEngineExamplePage({super.key});

  @override
  State<MnnEngineExamplePage> createState() => _MnnEngineExamplePageState();
}

class _MnnEngineExamplePageState extends State<MnnEngineExamplePage> {
  MnnEngineInfo? _info;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      final info = await MnnEngine.instance.initialize();
      if (mounted) setState(() => _info = info);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final info = _info;
    return Scaffold(
      appBar: AppBar(title: const Text('MNN Engine example')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: _error != null
            ? Text('Initialization failed: $_error')
            : info == null
            ? const Center(child: CircularProgressIndicator())
            : SelectableText(
                'MNN ${info.mnnVersion} (${info.mnnCommit})\n'
                'ABI: ${info.abi}\n'
                'Native loaded: ${info.nativeLibraryLoaded}\n'
                'Test root: ${info.testRootPath}',
              ),
      ),
    );
  }
}
