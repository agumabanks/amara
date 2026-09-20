import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/screens/settings/settings_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.sanaa.agent/core');
  testWidgets('pairing identifies the device and submits only on Connect', (tester) async {
    final codes = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'amaraSettings') return <String,dynamic>{};
      if (call.method == 'pairingDeviceId') return 'device-for-this-shop';
      if (call.method == 'pairDevice') { codes.add(call.arguments['code'] as String); return true; }
      return null;
    });
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Connect to Cards admin'));
    await tester.pumpAndSettle();
    expect(find.text('Device ID: device-for-this-shop'), findsOneWidget);
    expect(codes, isEmpty);
    await tester.enterText(find.byType(TextField), '1234567890abcdef12345678');
    await tester.tap(find.text('Connect'));
    await tester.pumpAndSettle();
    expect(codes, ['1234567890abcdef12345678']);
    expect(find.text('Connected to Cards. Device settings synced.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
}
