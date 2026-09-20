import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/settings/settings_screen.dart';

void main() {
  testWidgets('YouTube has independent controls and truthful activity counts', (
    tester,
  ) async {
    const channel = MethodChannel('com.sanaa.agent/core');
    final writes = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'amaraSettings')
            return {
              'youtubeEnabled': false,
              'youtubeChannel': '@sanaa',
              'youtubeIntervalMinutes': 240,
              'youtubeDailyCap': 3,
              'youtubeAudioCleared': false,
              'youtubeStatus': 'Waiting for export',
              'youtubeQueue': '1 pending',
              'moduleStats': {
                'YouTube': {
                  'attempts': 2,
                  'completed': 1,
                  'failed': 0,
                  'held': 1,
                  'verified': 0,
                  'uncertain': 1,
                  'recent': [],
                },
              },
            };
          if (call.method == 'setAmaraSetting') writes.add(call);
          return true;
        });
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('YouTube Shorts'));
    await tester.tap(find.text('YouTube Shorts'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Enable YouTube Shorts'));
    final toggleRow = find
        .ancestor(
          of: find.text('Enable YouTube Shorts'),
          matching: find.byType(Row),
        )
        .first;
    await tester.tap(
      find.descendant(of: toggleRow, matching: find.byType(Switch)),
    );
    await tester.pumpAndSettle();
    expect(writes.single.arguments, {'key': 'youtubeEnabled', 'value': true});
    await tester.ensureVisible(find.text('Shorts visibility'));
    await tester.tap(find.text('Public'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Private').last);
    await tester.pumpAndSettle();
    expect(writes.last.arguments, {
      'key': 'youtubeVisibility',
      'value': 'Private',
    });
    await tester.ensureVisible(find.text('Made for kids'));
    final audienceRow = find
        .ancestor(of: find.text('Made for kids'), matching: find.byType(Row))
        .first;
    await tester.tap(
      find.descendant(of: audienceRow, matching: find.byType(Switch)),
    );
    await tester.pumpAndSettle();
    expect(writes.last.arguments, {'key': 'youtubeMadeForKids', 'value': true});
    await tester.ensureVisible(find.text('YouTube activity').first);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('YouTube activity').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('YouTube activity').first);
    await tester.pumpAndSettle();
    expect(
      find.textContaining('0 verified external actions · 1 uncertain'),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
}
