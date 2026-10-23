import 'package:attemption/main.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.gabriel.attemption/permissions');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  void mockPermissions({required bool usage, required bool overlay}) {
    messenger.setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'checkUsagePermission':
          return usage;
        case 'checkOverlayPermission':
          return overlay;
        default:
          return true;
      }
    });
  }

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  testWidgets('sin permisos muestra dos botones Permitir y no el de iniciar', (tester) async {
    mockPermissions(usage: false, overlay: false);
    await tester.pumpWidget(const AttemptionApp());
    await tester.pumpAndSettle();

    expect(find.text('Protege tu tiempo'), findsOneWidget);
    expect(find.text('Permitir'), findsNWidgets(2));
    expect(find.text('Iniciar Protección'), findsNothing);
  });

  testWidgets('con ambos permisos se puede iniciar la protección', (tester) async {
    mockPermissions(usage: true, overlay: true);
    await tester.pumpWidget(const AttemptionApp());
    await tester.pumpAndSettle();

    expect(find.text('Permitir'), findsNothing);
    await tester.tap(find.text('Iniciar Protección'));
    await tester.pumpAndSettle();
    expect(find.text('Servicio Activo'), findsOneWidget);
  });
}
