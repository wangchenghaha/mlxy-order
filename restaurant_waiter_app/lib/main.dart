import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hive_flutter/hive_flutter.dart';

import 'config/locale_config.dart';
import 'pages/home/home_page.dart';
import 'pages/login/login_page.dart';
import 'utils/hive_utils.dart';
import 'utils/auth_redirect.dart';
import 'utils/locale_utils.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  runApp(const WaiterApp());
}

class WaiterApp extends StatefulWidget {
  const WaiterApp({super.key});

  @override
  State<WaiterApp> createState() => _WaiterAppState();
}

class _WaiterAppState extends State<WaiterApp> {
  bool bootstrapped = false;
  bool loggedIn = false;

  @override
  void initState() {
    super.initState();
    AuthRedirect.unauthorizedNotifier.addListener(handleUnauthorized);
    bootstrap();
  }

  @override
  void dispose() {
    AuthRedirect.unauthorizedNotifier.removeListener(handleUnauthorized);
    super.dispose();
  }

  void handleUnauthorized() {
    if (!mounted) return;
    setState(() => loggedIn = false);
    AuthRedirect.navigatorKey.currentState?.pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginPage()),
      (_) => false,
    );
  }

  Future<void> bootstrap() async {
    try {
      await Hive.initFlutter();
      await HiveUtils.init();
    } catch (error) {
      debugPrint('waiter local storage init failed: $error');
    }
    if (HiveUtils.lang.isEmpty) {
      final deviceLocale = WidgetsBinding.instance.platformDispatcher.locale;
      HiveUtils.lang =
          LocaleConfig.normalizeDeviceLanguageCode(deviceLocale.languageCode);
    }
    await LocaleUtils.load();
    if (mounted) {
      setState(() {
        bootstrapped = true;
        loggedIn = HiveUtils.token.isNotEmpty;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<int>(
      valueListenable: LocaleUtils.rebuildNotifier,
      builder: (context, _, __) {
        return MaterialApp(
          navigatorKey: AuthRedirect.navigatorKey,
          debugShowCheckedModeBanner: false,
          title: LocaleUtils.get('login_title'),
          theme: ThemeData(
            useMaterial3: true,
            colorScheme:
                ColorScheme.fromSeed(seedColor: const Color(0xff1f6f5a)),
            inputDecorationTheme: InputDecorationTheme(
              filled: true,
              fillColor: const Color(0xfff7faf8),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(16),
                borderSide: BorderSide.none,
              ),
            ),
          ),
          home: !bootstrapped
              ? const _BootstrapPage()
              : loggedIn
                  ? const HomePage()
                  : const LoginPage(),
        );
      },
    );
  }
}

class _BootstrapPage extends StatelessWidget {
  const _BootstrapPage();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            colors: [Color(0xff184f43), Color(0xff2f7a69)],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        child: const SafeArea(
          child: Center(
            child: SizedBox(
              width: 30,
              height: 30,
              child: CircularProgressIndicator(
                  color: Colors.white, strokeWidth: 3),
            ),
          ),
        ),
      ),
    );
  }
}
