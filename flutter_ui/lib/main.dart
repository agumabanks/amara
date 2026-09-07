import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'screens/home/home_screen.dart';
import 'screens/work/work_screen.dart';
import 'screens/market/market_screen.dart';
import 'screens/settings/settings_screen.dart';
import 'screens/chat/chat_tab.dart';
import 'screens/onboarding/onboarding_screen.dart';
import 'bridge/agent_channel.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SanaaAgentApp());
}

class SanaaAgentApp extends StatelessWidget {
  const SanaaAgentApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Sanaa Agent',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(0xFF080A0A),
      useMaterial3: true,
      textTheme: GoogleFonts.outfitTextTheme(ThemeData.dark().textTheme),
      colorScheme: const ColorScheme.dark(
        primary: Color(0xFF50E3C2),
        secondary: Color(0xFFF97316),
        surface: Color(0xFF121616),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xFF181D1C),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide.none,
        ),
      ),
    ),
    home: const _RootScreen(),
  );
}

class _RootScreen extends StatelessWidget {
  const _RootScreen();

  @override
  Widget build(BuildContext context) => FutureBuilder<bool>(
    future: AgentChannel.setupComplete().onError((_, __) => false),
    builder: (_, snapshot) {
      if (!snapshot.hasData) {
        return const Scaffold(body: Center(child: SizedBox.shrink()));
      }
      return snapshot.data == true
          ? const AppShell()
          : const OnboardingScreen();
    },
  );
}

class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => AppShellState();
}

class AppShellState extends State<AppShell> {
  int _index = 0;

  void switchTab(int index) => setState(() => _index = index);

  static const _tabs = [
    HomeScreen(),
    WorkScreen(),
    MarketScreen(),
    ChatTab(),
    SettingsScreen(),
  ];

  @override
  Widget build(BuildContext context) => Scaffold(
    body: IndexedStack(index: _index, children: _tabs),
    bottomNavigationBar: Container(
      decoration: BoxDecoration(
        color: const Color(0xFF0D1111),
        border: Border(
          top: BorderSide(color: Colors.white.withValues(alpha: 0.06)),
        ),
      ),
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _navItem(0, Icons.home_outlined, Icons.home, 'Home'),
              _navItem(1, Icons.work_outline, Icons.work, 'Work'),
              _navItem(
                2,
                Icons.trending_up_outlined,
                Icons.trending_up,
                'Market',
              ),
              _navItem(3, Icons.chat_bubble_outline, Icons.chat_bubble, 'Chat'),
              _navItem(4, Icons.settings_outlined, Icons.settings, 'Settings'),
            ],
          ),
        ),
      ),
    ),
  );

  Widget _navItem(int i, IconData icon, IconData activeIcon, String label) {
    final active = _index == i;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => setState(() => _index = i),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: active
              ? const Color(0xFF50E3C2).withValues(alpha: 0.12)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              active ? activeIcon : icon,
              color: active ? const Color(0xFF50E3C2) : Colors.white38,
              size: 20,
            ),
            const SizedBox(height: 4),
            Text(
              label,
              style: TextStyle(
                fontSize: 10,
                fontWeight: active ? FontWeight.w700 : FontWeight.w500,
                color: active ? const Color(0xFF50E3C2) : Colors.white38,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Global accessor for tab switching from child screens
AppShellState? getAppShellState(BuildContext context) {
  AppShellState? state;
  context.visitAncestorElements((element) {
    if (element is StatefulElement && element.state is AppShellState) {
      state = element.state as AppShellState;
      return false;
    }
    return true;
  });
  return state;
}
