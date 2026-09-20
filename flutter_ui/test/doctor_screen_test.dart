import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/doctor/doctor_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.sanaa.agent/core');
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('group repair refreshes the observed issue list', (tester) async {
    var repaired = false;
    var reads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'deepRepairWhatsAppGroups') {
        repaired = true;
        return {'summary': 'Repair attempted'};
      }
      if (call.method == 'doctorStatus') {
        reads++;
        return {
          'health': {'blockers': []},
          'issues': repaired ? [] : [{'task': 'Group', 'reason': 'Group unavailable'}],
          'resolved': [],
        };
      }
      return null;
    });
    await tester.pumpWidget(const MaterialApp(home: DoctorScreen()));
    await tester.pumpAndSettle();
    final button = find.text('Deep repair WhatsApp groups');
    await tester.ensureVisible(button);
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(reads, 2);
    expect(find.text('Deep repair WhatsApp groups'), findsNothing);
  });

  for (final confirm in [false, true]) {
    testWidgets(
      'media review ${confirm ? 'confirms selection and refreshes' : 'cancel does not delete'}',
      (tester) async {
        var statusCalls = 0;
        final cleanupCalls = <MethodCall>[];
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, (call) async {
              if (call.method == 'doctorStatus') {
                statusCalls++;
                return {
                  'health': {'blockers': []},
                  'issues': [],
                  'resolved': [],
                };
              }
              if (call.method == 'previewMediaCleanup') {
                return {
                  'stores': [
                    {
                      'name': 'tiktok-bound-media',
                      'usedBytes': 134217728,
                      'limitBytes': 134217728,
                    },
                  ],
                  'candidates': [
                    {
                      'id': 'obsolete-a',
                      'store': 'tiktok-bound-media',
                      'bytes': 1048576,
                    },
                    {
                      'id': 'obsolete-b',
                      'store': 'tiktok-bound-media',
                      'bytes': 2097152,
                    },
                  ],
                  'totalReclaimableBytes': 3145728,
                };
              }
              if (call.method == 'confirmMediaCleanup') {
                cleanupCalls.add(call);
                return {'removedCount': 1, 'reclaimedBytes': 1048576};
              }
              return null;
            });
        await tester.pumpWidget(const MaterialApp(home: DoctorScreen()));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Review obsolete media'));
        await tester.pumpAndSettle();
        expect(cleanupCalls, isEmpty);
        expect(
          tester
              .widget<FilledButton>(
                find.widgetWithText(FilledButton, 'Remove selected media'),
              )
              .onPressed,
          isNull,
        );
        await tester.tap(find.text('obsolete-a'));
        await tester.pumpAndSettle();
        await tester.tap(
          find.text(confirm ? 'Remove selected media' : 'Cancel'),
        );
        await tester.pumpAndSettle();
        if (confirm) {
          expect(cleanupCalls.single.arguments, {
            'candidateIds': ['obsolete-a'],
          });
          expect(statusCalls, 2);
          await tester.ensureVisible(find.text('Latest check details'));
          await tester.tap(find.text('Latest check details'));
          await tester.pumpAndSettle();
          expect(find.textContaining('reclaimed 1.0 MiB'), findsOneWidget);
        } else {
          expect(cleanupCalls, isEmpty);
          expect(statusCalls, 1);
        }
      },
    );
  }

  testWidgets('media review displays publication labels and states', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'previewMediaCleanup') {
        return {
          'candidates': [
            {
              'id': 'candidate-a',
              'store': 'tiktok-bound-media',
              'bytes': 1048576,
              'label': 'TikTok publication',
              'status': 'VERIFIED',
            },
          ],
        };
      }
      return {'health': {}, 'issues': [], 'resolved': []};
    });
    await tester.pumpWidget(const MaterialApp(home: DoctorScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Review obsolete media'));
    await tester.pumpAndSettle();
    expect(find.text('TikTok publication\nVERIFIED\ncandidate-a'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
  });

  testWidgets('protected media has no cleanup selection', (tester) async {
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          return call.method == 'previewMediaCleanup'
              ? {'stores': [], 'candidates': [], 'totalReclaimableBytes': 0}
              : {'health': {}, 'issues': [], 'resolved': []};
        });
    await tester.pumpWidget(const MaterialApp(home: DoctorScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Review obsolete media'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Nothing safely reclaimable'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(
            find.widgetWithText(FilledButton, 'Remove selected media'),
          )
          .onPressed,
      isNull,
    );
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(calls, isNot(contains('confirmMediaCleanup')));
  });
  testWidgets(
    'failed media confirmation retains error and allows another review',
    (tester) async {
      var previews = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'previewMediaCleanup') {
              previews++;
              return {
                'stores': [],
                'candidates': [
                  {
                    'id': 'obsolete-a',
                    'store': 'tiktok-bound-media',
                    'bytes': 1,
                  },
                ],
              };
            }
            if (call.method == 'confirmMediaCleanup') {
              throw PlatformException(code: 'CLEANUP_UNAVAILABLE');
            }
            return {'health': {}, 'issues': [], 'resolved': []};
          });
      await tester.pumpWidget(const MaterialApp(home: DoctorScreen()));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Review obsolete media'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('obsolete-a'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Remove selected media'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('Media review could not finish'),
        findsOneWidget,
      );
      await tester.tap(find.text('Review obsolete media'));
      await tester.pumpAndSettle();
      expect(previews, 2);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
    },
  );

  testWidgets('cleanup success survives a health refresh failure', (
    tester,
  ) async {
    var statusCalls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'doctorStatus' && ++statusCalls > 1) {
            throw PlatformException(code: 'HEALTH_UNAVAILABLE');
          }
          if (call.method == 'previewMediaCleanup') {
            return {
              'candidates': [
                {
                  'id': 'obsolete-a',
                  'store': 'tiktok-bound-media',
                  'bytes': 1048576,
                },
              ],
            };
          }
          if (call.method == 'confirmMediaCleanup') {
            return {'removedCount': 1, 'reclaimedBytes': 1048576};
          }
          return {'health': {}, 'issues': [], 'resolved': []};
        });
    await tester.pumpWidget(const MaterialApp(home: DoctorScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Review obsolete media'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('obsolete-a'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Remove selected media'));
    await tester.pumpAndSettle();
    expect(
      find.textContaining('Cleanup completed, but health status'),
      findsOneWidget,
    );
    await tester.ensureVisible(find.text('Latest check details'));
    await tester.tap(find.text('Latest check details'));
    await tester.pumpAndSettle();
    expect(find.textContaining('reclaimed 1.0 MiB'), findsOneWidget);
  });

  testWidgets('repair refreshes observed blockers and resolution evidence', (
    tester,
  ) async {
    var repaired = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'runOperationalHealth') {
            repaired = true;
            return {'summary': 'Service checked'};
          }
          if (call.method == 'doctorStatus') {
            return {
              'health': {
                'blockers': repaired ? [] : ['Phone control is unavailable'],
              },
              'issues': [],
              'resolved': repaired
                  ? [
                      {
                        'task': 'Group broadcast',
                        'evidence': 'Later verified delivery',
                      },
                    ]
                  : <Map<String, String>>[],
            };
          }
          return <String, dynamic>{};
        });
    await tester.pumpWidget(const MaterialApp(home: DoctorScreen()));
    await tester.pumpAndSettle();
    expect(find.text('Open phone access'), findsOneWidget);
    await tester.tap(find.text('Check & repair now'));
    await tester.pumpAndSettle();
    expect(find.text('Phone control is unavailable'), findsNothing);
    expect(find.text('No current blockers found'), findsOneWidget);
    expect(find.text('Latest check details'), findsOneWidget);
    expect(find.text('Service checked'), findsNothing);
    await tester.tap(find.text('Latest check details'));
    await tester.pumpAndSettle();
    expect(find.text('Service checked'), findsOneWidget);
    expect(find.text('Later verified delivery'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
}
