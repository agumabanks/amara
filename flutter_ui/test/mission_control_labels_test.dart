import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/bridge/agent_channel.dart';
import 'package:sanaa_agent_ui/screens/work/mission_control_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'mission control renders catalog labels from production-shaped data',
    (tester) async {
      const channel = MethodChannel('com.sanaa.agent/core');
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        switch (call.method) {
          case 'missionControl':
            return {
              'approvals': [
                {
                  'id': 1,
                  'capability': 'apply_soko_edit',
                  'target': 'Thermal Mini Printer',
                  'description': 'Retitle',
                  'before': '{}',
                  'after': '{}',
                  'risk': 'LOW_IMPACT_CHANGE',
                  'expiresAt': 9999999999,
                },
              ],
              'findings': <Map<String, dynamic>>[],
              'schedules': <Map<String, dynamic>>[],
              'settings': <String, dynamic>{},
            };
          case 'capabilityCatalog':
            return [
              {
                'id': 'apply_soko_edit',
                'label': 'Apply approved Soko edit',
                'description': 'd',
                'risk': 'LOW_IMPACT_CHANGE',
                'externalSideEffect': true,
                'requiresFreshApproval': true,
                'verifier': 'FIELD_REOPEN_COMPARE',
                'uiExposed': true,
              },
            ];
          case 'activeCommitments':
            return [
              {
                'id': 'run-1',
                'phase': 'AWAITING_DECISION',
                'stepIndex': 2,
                'decisionQuestion': 'Confirm the saved value?',
              },
            ];
          default:
            return null;
        }
      });

      await tester.pumpWidget(const MaterialApp(home: MissionControlScreen()));
      await tester.pumpAndSettle();

      // Catalog-derived human label appears beside the approval target.
      expect(find.text('Apply approved Soko edit'), findsOneWidget);
      // Active commitment surfaces its phase and owner question.
      expect(find.textContaining('run-1'), findsOneWidget);
      expect(find.text('Confirm the saved value?'), findsOneWidget);

      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        channel,
        null,
      );
    },
  );

  testWidgets('capability catalog accessor filters ui-exposed entries', (
    tester,
  ) async {
    const channel = MethodChannel('com.sanaa.agent/core');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      if (call.method == 'capabilityCatalog') {
        return [
          {'id': 'visible_one', 'label': 'Visible', 'uiExposed': true},
          {'id': 'hidden_one', 'label': 'Hidden', 'uiExposed': false},
        ];
      }
      return null;
    });
    final exposed = await AgentChannel.exposedCapabilities();
    expect(exposed.map((c) => c.id), ['visible_one']);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      null,
    );
  });
}
