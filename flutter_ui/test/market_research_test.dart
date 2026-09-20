import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sanaa_agent_ui/screens/market/market_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.sanaa.agent/core');
  testWidgets('research queues work and displays unmet prerequisites', (tester) async {
    var requests = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'marketDashboard') return {'totalListings': 0, 'sources': []};
      if (call.method == 'requestMarketResearch') {
        requests++;
        expect((call.arguments as Map)['query'], 'receipt printer');
        return {'queued': ['Jiji'], 'blockers': ['Jumia needs vision configuration']};
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
    expect(find.textContaining('Jumia needs vision configuration'), findsOneWidget);
    expect(find.textContaining('Queued: Jiji'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
}
