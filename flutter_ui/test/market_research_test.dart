import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/market/market_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.sanaa.agent/core');
  testWidgets('hidden and background market does not poll the catalogue', (
    tester,
  ) async {
    var reads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'marketDashboard') reads++;
          return {'totalListings': 0, 'sources': []};
        });
    await tester.pumpWidget(
      const MaterialApp(home: MarketScreen(active: false)),
    );
    await tester.pump(const Duration(seconds: 31));
    expect(reads, 0);
    await tester.pumpWidget(
      const MaterialApp(home: MarketScreen(active: true)),
    );
    await tester.pumpAndSettle();
    expect(reads, 1);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 31));
    expect(reads, 1);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(reads, 2);
    await tester.pumpWidget(
      const MaterialApp(home: MarketScreen(active: false)),
    );
    await tester.pump(const Duration(seconds: 31));
    expect(reads, 2);
    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  testWidgets('research queues work and displays unmet prerequisites', (
    tester,
  ) async {
    var requests = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'marketDashboard') {
            return {'totalListings': 0, 'sources': []};
          }
          if (call.method == 'requestMarketResearch') {
            requests++;
            expect((call.arguments as Map)['query'], 'receipt printer');
            return {
              'queued': ['Jiji'],
              'blockers': ['Jumia needs vision configuration'],
            };
          }
          return <String, dynamic>{};
        });
    await tester.pumpWidget(const MaterialApp(home: MarketScreen()));
    await tester.pumpAndSettle();
    expect(find.text('0 saved observations'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'receipt printer');
    await tester.ensureVisible(find.text('Research now'));
    await tester.tap(find.text('Research now'));
    await tester.pumpAndSettle();
    expect(requests, 1);
    expect(
      find.textContaining('Jumia needs vision configuration'),
      findsOneWidget,
    );
    expect(find.textContaining('Queued: Jiji'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
}
