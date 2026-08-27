import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:sanaa_agent_ui/main.dart';
import 'package:sanaa_agent_ui/screens/chat/chat_screen.dart';
import 'package:flutter/material.dart';

void main() {
  const channel = MethodChannel('com.sanaa.agent/core');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'permissionStatus') {
            return <String, bool>{
              'accessibility': false,
              'battery': false,
              'overlay': false,
              'notificationAccess': false,
              'notifications': false,
            };
          }
          if (call.method == 'syncConfig') return true;
          if (call.method == 'chatHistory') return <dynamic>[];
          return false;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows the Oppo onboarding flow', (tester) async {
    await tester.pumpWidget(const SanaaAgentApp());
    await tester.pumpAndSettle();
    expect(find.text('Let’s get Amara ready.'), findsOneWidget);
    expect(find.text('Accessibility'), findsOneWidget);
    expect(find.text('Keep alive'), findsOneWidget);
  });

  testWidgets('shows Amara as a conversation, not a task form', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: ChatScreen()));
    await tester.pumpAndSettle();
    expect(find.text('Amara'), findsOneWidget);
    expect(find.text('Active'), findsOneWidget);
    expect(
      find.text('Morning — what can I take off your plate?'),
      findsOneWidget,
    );
    expect(find.text('Message Amara'), findsOneWidget);
    expect(find.text('Send Task'), findsNothing);
  });

  testWidgets('shows the Observe Analyze Act Report flow and receipt', (
    tester,
  ) async {
    final result = Completer<Map<String, dynamic>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'chatHistory') return <dynamic>[];
          if (call.method == 'autonomyStatus') {
            return <String, String>{
              'phase': 'analyze',
              'detail': 'Choosing the safest useful action',
            };
          }
          if (call.method == 'submitTask') return result.future;
          if (call.method == 'stopCurrentTask') return true;
          return false;
        });

    await tester.pumpWidget(const MaterialApp(home: ChatScreen()));
    await tester.enterText(
      find.byType(TextField).last,
      'Check my WhatsApp groups',
    );
    await tester.tap(find.byTooltip('Send'));
    await tester.pump(const Duration(milliseconds: 700));

    expect(find.text('Observe'), findsOneWidget);
    expect(find.text('Analyze'), findsOneWidget);
    expect(find.text('Act'), findsOneWidget);
    expect(find.text('Report'), findsOneWidget);
    expect(find.text('Stop'), findsOneWidget);

    result.complete({
      'success': true,
      'status': 'completed',
      'message': 'I found two WhatsApp groups.',
      'observation': 'WhatsApp was available.',
      'analysis': 'Reading the group list was enough.',
      'steps': [
        {
          'action': 'list_whatsapp_groups',
          'reason': 'Read groups',
          'success': true,
          'result': 'Found two groups.',
        },
      ],
    });
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();

    expect(find.text('I found two WhatsApp groups.'), findsOneWidget);
    expect(find.text('How I handled it'), findsOneWidget);
    expect(find.text('1 verified step'), findsOneWidget);
  });
}
