import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/home/home_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.sanaa.agent/core');
  testWidgets('idle summary cannot hide owner blocker and resolution refreshes', (tester) async {
    var blocked = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'autonomousDashboard') {
        return {'loop': {'running': true, 'ownerOn': true, 'state': 'WAITING',
          'lastSummary': 'No eligible work is due.',
          'blockers': blocked ? [{'task': 'Device battery', 'reason': 'Battery is 7%',
            'ownerAction': true, 'action': 'Connect a reliable charger.',
            'continuation': 'Autonomous work waits for charging.'}] : []}};
      }
      return <String, dynamic>{};
    });
    await tester.pumpWidget(const MaterialApp(home: HomeScreen()));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Battery is 7%'), findsOneWidget);
    expect(find.text('Review'), findsOneWidget);
    expect(find.text('Ready'), findsNothing);
    expect(find.text('Device battery · Owner action needed'), findsOneWidget);
    expect(find.text('Connect a reliable charger.'), findsOneWidget);
    expect(find.text('No eligible work is due.'), findsOneWidget);
    blocked = false;
    await tester.pump(const Duration(seconds: 5));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Battery is 7%'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
}
