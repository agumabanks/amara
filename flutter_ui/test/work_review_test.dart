import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/work/work_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.sanaa.agent/core');
  testWidgets('review closure requires owner choice and targets one hold', (
    tester,
  ) async {
    final closed = <Map<dynamic, dynamic>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'missionControl') return <String, dynamic>{};
          if (call.method == 'autonomousDashboard') {
            return {
              'loop': {'running': true, 'ownerOn': false},
              'queue': {
                'needsReview': closed.isEmpty
                    ? [
                        {
                          'key': 'one-held-reply',
                          'kind': 'WA_REPLY_INBOUND',
                          'conversation': 'Customer A',
                          'message': 'How much?',
                          'reason': 'Delivery uncertain',
                          'reviewAt': 0,
                        },
                      ]
                    : [],
              },
            };
          }
          if (call.method == 'closeWorkReview') {
            closed.add(call.arguments as Map);
            return true;
          }
          return null;
        });
    await tester.pumpWidget(const MaterialApp(home: WorkScreen()));
    await tester.pumpAndSettle();
    expect(find.text('Amara is off'), findsOneWidget);
    await tester.tap(find.text('Needs review (1)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Customer A'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Resolve this hold'));
    await tester.tap(find.text('Resolve this hold'));
    await tester.pumpAndSettle();
    expect(closed, isEmpty);
    await tester.tap(find.text('Keep waiting'));
    await tester.pumpAndSettle();
    expect(closed, isEmpty);
    await tester.tap(find.text('Resolve this hold'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('I handled it'));
    await tester.pumpAndSettle();
    expect(closed.single, {
      'key': 'one-held-reply',
      'disposition': 'handled_elsewhere',
    });
    expect(find.text('Hold closed. No message was sent.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
}
