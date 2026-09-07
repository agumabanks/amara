import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/services.dart';
import 'package:sanaa_agent_ui/screens/chat/chat_screen.dart';
import 'package:sanaa_agent_ui/screens/onboarding/onboarding_screen.dart';
import 'package:sanaa_agent_ui/screens/work/work_screen.dart';
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
    await tester.pumpWidget(const MaterialApp(home: OnboardingScreen()));
    await tester.pumpAndSettle();
    expect(find.text('Let’s get Amara ready.'), findsOneWidget);
    expect(find.text('Accessibility'), findsOneWidget);
    expect(find.text('Keep alive'), findsOneWidget);
    expect(find.text('Groq intelligence'), findsNothing);
  });

  testWidgets('backend sync failure does not block completed local setup', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'setupComplete') return false;
          if (call.method == 'syncConfig') {
            throw PlatformException(
              code: 'SYNC_ERROR',
              message: 'Backend returned HTTP 500',
            );
          }
          if (call.method == 'permissionStatus') {
            return <String, bool>{
              'accessibility': true,
              'battery': true,
              'overlay': true,
              'notificationAccess': true,
              'notifications': true,
            };
          }
          return false;
        });

    await tester.pumpWidget(const MaterialApp(home: OnboardingScreen()));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView), const Offset(0, -1400));
    await tester.pumpAndSettle();

    expect(find.text('Start Amara'), findsOneWidget);
    expect(find.text('Finish setup to continue'), findsNothing);
    expect(find.byType(TextField), findsNothing);
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

  testWidgets('work command exposes loop schedules and workflow templates', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'missionControl') {
            return <String, dynamic>{
              'approvals': <dynamic>[],
              'findings': <dynamic>[],
              'schedules': <dynamic>[],
              'systemSchedules': <dynamic>[
                <String, dynamic>{
                  'id': 'pulse',
                  'name': 'Autonomous work pulse',
                  'rule': 'Every 15 minutes',
                  'enabled': true,
                  'authority': 'Safety-governed',
                },
              ],
              'templates': <dynamic>[
                <String, dynamic>{
                  'id': 'listing',
                  'name': 'Create verified listing',
                  'inputs': <String>['product'],
                  'steps': <String>['OBSERVE', 'VERIFY', 'REPORT'],
                  'approvals': <String>['PUBLISH'],
                  'classification': 'BUSINESS',
                },
              ],
              'learnedRoutineSuggestions': <dynamic>[
                <String, dynamic>{
                  'id': 'learned:jiji_scrape:9',
                  'name': 'Jiji Scrape',
                  'rule': 'Observed on 4 days around 09:00–12:00',
                  'evidenceCount': 4,
                  'authority': 'Suggestion only — owner activation required',
                },
              ],
            };
          }
          if (call.method == 'autonomousDashboard') {
            return <String, dynamic>{
              'loop': <String, dynamic>{
                'running': true,
                'state': 'SLEEPING',
                'cycleCount': 3,
                'itemsExecuted': 2,
                'itemsFailed': 0,
                'lastWakeReason': 'scheduled_alarm',
                'lastSummary': '2 actions verified',
              },
              'queue': <String, dynamic>{'items': <dynamic>[]},
            };
          }
          return false;
        });

    await tester.pumpWidget(const MaterialApp(home: WorkScreen()));
    await tester.pumpAndSettle();
    expect(find.text('AUTONOMOUS LOOP ONLINE'), findsOneWidget);
    expect(find.text('SCHEDULED & RECURRING'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('WORKFLOW TEMPLATES'),
      250,
      scrollable: find.byType(Scrollable),
    );
    expect(find.text('Create verified listing'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('LEARNED ROUTINE SUGGESTIONS'),
      250,
      scrollable: find.byType(Scrollable),
    );
    expect(find.text('Jiji Scrape'), findsOneWidget);
    expect(find.textContaining('4 verified observations'), findsOneWidget);
  });

  testWidgets('work command refreshes itself while it remains mounted', (
    tester,
  ) async {
    var dashboardCalls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'missionControl') {
            return <String, dynamic>{
              'approvals': <dynamic>[],
              'findings': <dynamic>[],
              'schedules': <dynamic>[],
              'systemSchedules': <dynamic>[],
              'templates': <dynamic>[],
            };
          }
          if (call.method == 'autonomousDashboard') {
            dashboardCalls++;
            return <String, dynamic>{
              'loop': <String, dynamic>{'running': true},
              'queue': <String, dynamic>{'items': <dynamic>[]},
            };
          }
          return false;
        });

    await tester.pumpWidget(const MaterialApp(home: WorkScreen()));
    await tester.pump();
    expect(dashboardCalls, 1);

    await tester.pump(const Duration(seconds: 5));
    await tester.pump();
    expect(dashboardCalls, greaterThanOrEqualTo(2));

    await tester.pumpWidget(const SizedBox.shrink());
  });
}
