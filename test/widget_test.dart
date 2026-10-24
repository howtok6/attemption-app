import 'package:attemption/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _minute = 60000;

Map<String, Object> _status({
  bool running = false,
  bool enabled = false,
  String state = 'inactive',
  int dailyUsedMs = 0,
  int sessionUsedMs = 0,
  int restLeftMs = 0,
}) => {
  'running': running,
  'protectionEnabled': enabled,
  'state': state,
  'dailyUsedMs': dailyUsedMs,
  'dailyLimitMs': 60 * _minute,
  'sessionUsedMs': sessionUsedMs,
  'sessionLimitMs': 5 * _minute,
  'restLeftMs': restLeftMs,
  'restLimitMs': 10 * _minute,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const methods = MethodChannel('com.gabriel.attemption/permissions');
  const statusChannel = EventChannel('com.gabriel.attemption/status');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<String> calls;

  void mockNative({required bool usage, required bool overlay, Map<String, Object>? status}) {
    final current = status ?? _status();
    calls = [];
    messenger.setMockMethodCallHandler(methods, (call) async {
      calls.add(call.method);
      switch (call.method) {
        case 'checkUsagePermission':
          return usage;
        case 'checkOverlayPermission':
          return overlay;
        case 'getStatus':
          return current;
        case 'getManufacturer':
          return 'xiaomi';
        default:
          return true;
      }
    });
    messenger.setMockStreamHandler(
      statusChannel,
      MockStreamHandler.inline(onListen: (_, events) => events.success(current)),
    );
  }

  Future<void> pumpApp(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(const AttemptionApp());
    await tester.pumpAndSettle();
  }

  Future<void> tapText(WidgetTester tester, String text) async {
    await tester.ensureVisible(find.text(text));
    await tester.pumpAndSettle();
    await tester.tap(find.text(text));
    await tester.pumpAndSettle();
  }

  tearDown(() {
    messenger.setMockMethodCallHandler(methods, null);
    messenger.setMockStreamHandler(statusChannel, null);
  });

  testWidgets('sin permisos muestra dos botones Permitir y no el de iniciar', (tester) async {
    mockNative(usage: false, overlay: false);
    await pumpApp(tester);

    expect(find.text('Protege tu tiempo'), findsOneWidget);
    expect(find.text('Permitir'), findsNWidgets(2));
    expect(find.text('Iniciar Protección'), findsNothing);
    expect(find.text('Guía para teléfonos Xiaomi'), findsOneWidget);
  });

  testWidgets('con ambos permisos se puede iniciar la protección', (tester) async {
    mockNative(usage: true, overlay: true);
    await pumpApp(tester);

    expect(find.text('Permitir'), findsNothing);
    await tapText(tester, 'Iniciar Protección');
    expect(calls, contains('startMonitorService'));
    expect(find.text('Protección activa'), findsOneWidget);
    expect(find.text('Desactivar protección'), findsOneWidget);
  });

  testWidgets('muestra el uso del día y de la sesión', (tester) async {
    mockNative(
      usage: true,
      overlay: true,
      status: _status(
        running: true,
        enabled: true,
        state: 'allowed',
        dailyUsedMs: 23 * _minute,
        sessionUsedMs: 3 * _minute,
      ),
    );
    await pumpApp(tester);

    expect(find.text('Hoy: 23 de 60 min'), findsOneWidget);
    expect(find.text('Sesión actual: 3 de 5 min'), findsOneWidget);
  });

  testWidgets('durante el descanso no se puede desactivar', (tester) async {
    mockNative(
      usage: true,
      overlay: true,
      status: _status(running: true, enabled: true, state: 'resting', restLeftMs: 7 * _minute),
    );
    await pumpApp(tester);

    expect(find.text('Descanso: faltan 7 min'), findsOneWidget);
    await tapText(tester, 'Desactivar protección');
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.textContaining('Estás en un descanso'), findsOneWidget);
    expect(calls, isNot(contains('stopMonitorService')));
  });

  testWidgets('desactivar exige esperar 30 segundos', (tester) async {
    mockNative(usage: true, overlay: true, status: _status(running: true, enabled: true, state: 'allowed'));
    await pumpApp(tester);

    await tapText(tester, 'Desactivar protección');
    expect(find.byType(AlertDialog), findsOneWidget);
    final waiting = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(waiting.onPressed, isNull);

    await tester.pump(const Duration(seconds: 30));
    final ready = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(ready.onPressed, isNotNull);

    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();
    expect(calls, contains('stopMonitorService'));
    expect(find.text('Iniciar Protección'), findsOneWidget);
  });

  testWidgets('reanuda la protección si estaba activada y el servicio no corre', (tester) async {
    mockNative(usage: true, overlay: true, status: _status(running: false, enabled: true));
    await pumpApp(tester);

    expect(calls, contains('startMonitorService'));
    expect(find.text('Protección activa'), findsOneWidget);
  });
}
