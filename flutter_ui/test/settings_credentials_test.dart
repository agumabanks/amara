import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/settings/settings_screen.dart';

void main() {
  testWidgets(
    'Soko PIN uses the dedicated secure bridge, not ordinary settings',
    (tester) async {
      const channel = MethodChannel('com.sanaa.agent/core');
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            if (call.method == 'amaraSettings') return {'sokoPinStored': false};
            return true;
          });
      await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Soko Terminal PIN'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(find.byType(TextField)).obscureText,
        isTrue,
      );
      await tester.enterText(find.byType(TextField), '9876');
      await tester.tap(find.text('Save PIN'));
      await tester.pumpAndSettle();
      expect(calls.where((call) => call.method == 'storeSokoPin').length, 1);
      expect(calls.where((call) => call.method == 'setAmaraSetting'), isEmpty);
      await tester.pumpWidget(const SizedBox());
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    },
  );
}
