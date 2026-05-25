import 'package:flutter/material.dart';

import '../../api/api_service.dart';
import '../../api/auth_api.dart';
import '../../utils/locale_utils.dart';
import '../../widgets/common_button.dart';
import '../../widgets/platform_text_input.dart';
import '../home/home_page.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({super.key});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final username = TextEditingController(text: 'waiter');
  final password = TextEditingController(text: 'Waiter@123');
  final captchaCode = TextEditingController();
  final phone = TextEditingController(text: '+60000000002');
  final smsCode = TextEditingController();
  String mode = 'PASSWORD';
  String captchaId = '';
  String captchaQuestion = '...';
  String debugSmsCode = '';
  bool loading = false;
  bool obscurePassword = true;

  @override
  void dispose() {
    username.dispose();
    password.dispose();
    captchaCode.dispose();
    phone.dispose();
    smsCode.dispose();
    super.dispose();
  }

  Future<void> refreshCaptcha() async {
    try {
      final data = await AuthApi.captcha();
      if (!mounted) return;
      setState(() {
        captchaId = '${data['captchaId'] ?? ''}';
        captchaQuestion = '${data['question'] ?? '...'}';
        captchaCode.clear();
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => captchaQuestion = '...');
      showMessage(ApiService.friendlyError(error));
    }
  }

  Future<void> sendSmsCode() async {
    try {
      final data = await AuthApi.sendSmsCode(phone.text);
      if (!mounted) return;
      setState(() => debugSmsCode = data['debugCode'] ?? '');
      showMessage(
        debugSmsCode.isEmpty
            ? LocaleUtils.get('login_send')
            : '${LocaleUtils.get('login_test_code')}: $debugSmsCode',
      );
    } catch (error) {
      showMessage(ApiService.friendlyError(error));
    }
  }

  Future<void> submit() async {
    if (loading) return;
    setState(() => loading = true);
    try {
      if (mode == 'SMS') {
        await AuthApi.loginBySms(phone.text, smsCode.text);
      } else {
        await AuthApi.loginByPassword(
            username.text, password.text, captchaId, captchaCode.text);
      }
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const HomePage()),
      );
    } catch (error) {
      if (mode == 'PASSWORD') await refreshCaptcha();
      showMessage(ApiService.friendlyError(error));
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  void showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isPassword = mode == 'PASSWORD';
    final theme = Theme.of(context);
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            colors: [
              Color(0xff154b3f),
              Color(0xff2a7a66),
              Color(0xfff6f1e9),
            ],
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            stops: [0, 0.42, 1],
          ),
        ),
        child: SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              return SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
                child: ConstrainedBox(
                  constraints:
                      BoxConstraints(minHeight: constraints.maxHeight - 42),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Container(
                        padding: const EdgeInsets.all(22),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: 0.14),
                          borderRadius: BorderRadius.circular(28),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const CircleAvatar(
                              radius: 25,
                              backgroundColor: Colors.white24,
                              child: Icon(Icons.table_restaurant_rounded,
                                  color: Colors.white),
                            ),
                            const SizedBox(height: 16),
                            Text(
                              LocaleUtils.get('login_title'),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.headlineSmall?.copyWith(
                                color: Colors.white,
                                fontWeight: FontWeight.w900,
                                height: 1.08,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              LocaleUtils.get('login_subtitle'),
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: Colors.white.withValues(alpha: 0.84),
                                height: 1.42,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 16),
                      Container(
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: 0.98),
                          borderRadius: BorderRadius.circular(28),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withValues(alpha: 0.08),
                              blurRadius: 22,
                              offset: const Offset(0, 10),
                            ),
                          ],
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            _LoginModeSwitch(
                              mode: mode,
                              onChanged: (value) =>
                                  setState(() => mode = value),
                            ),
                            const SizedBox(height: 16),
                            if (isPassword) ...[
                              _LoginTextField(
                                controller: username,
                                label: LocaleUtils.get('login_account'),
                                icon: Icons.person_outline_rounded,
                              ),
                              const SizedBox(height: 12),
                              _LoginTextField(
                                controller: password,
                                label: LocaleUtils.get('login_password'),
                                icon: Icons.lock_outline_rounded,
                                obscureText: obscurePassword,
                                trailing: IconButton(
                                  tooltip: LocaleUtils.get('login_password'),
                                  icon: Icon(
                                    obscurePassword
                                        ? Icons.visibility_off_rounded
                                        : Icons.visibility_rounded,
                                    size: 20,
                                  ),
                                  onPressed: () => setState(
                                      () => obscurePassword = !obscurePassword),
                                ),
                              ),
                              const SizedBox(height: 12),
                              Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Expanded(
                                    child: _LoginTextField(
                                      controller: captchaCode,
                                      label: LocaleUtils.get('login_captcha'),
                                      icon: Icons.shield_outlined,
                                    ),
                                  ),
                                  const SizedBox(width: 10),
                                  SizedBox(
                                    height: 56,
                                    child: OutlinedButton(
                                      onPressed: refreshCaptcha,
                                      style: OutlinedButton.styleFrom(
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 12),
                                        shape: RoundedRectangleBorder(
                                            borderRadius:
                                                BorderRadius.circular(16)),
                                      ),
                                      child: ConstrainedBox(
                                        constraints:
                                            const BoxConstraints(maxWidth: 82),
                                        child: Text(
                                          captchaQuestion,
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ] else ...[
                              _LoginTextField(
                                controller: phone,
                                label: LocaleUtils.get('login_phone'),
                                icon: Icons.phone_iphone_rounded,
                                keyboardType: TextInputType.phone,
                              ),
                              const SizedBox(height: 12),
                              Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Expanded(
                                    child: _LoginTextField(
                                      controller: smsCode,
                                      label: LocaleUtils.get('login_sms_code'),
                                      icon: Icons.markunread_outlined,
                                      keyboardType: TextInputType.number,
                                    ),
                                  ),
                                  const SizedBox(width: 10),
                                  SizedBox(
                                    height: 56,
                                    child: OutlinedButton(
                                      onPressed: sendSmsCode,
                                      style: OutlinedButton.styleFrom(
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 14),
                                        shape: RoundedRectangleBorder(
                                            borderRadius:
                                                BorderRadius.circular(16)),
                                      ),
                                      child:
                                          Text(LocaleUtils.get('login_send')),
                                    ),
                                  ),
                                ],
                              ),
                              if (debugSmsCode.isNotEmpty) ...[
                                const SizedBox(height: 12),
                                Container(
                                  padding: const EdgeInsets.all(12),
                                  decoration: BoxDecoration(
                                    color: const Color(0xffeef7ff),
                                    borderRadius: BorderRadius.circular(16),
                                  ),
                                  child: Text(
                                    '${LocaleUtils.get('login_test_code')}: $debugSmsCode',
                                    style: theme.textTheme.bodyMedium?.copyWith(
                                      color: const Color(0xff245e86),
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                ),
                              ],
                            ],
                            const SizedBox(height: 20),
                            CommonButton(
                              text: loading
                                  ? '...'
                                  : LocaleUtils.get('common_confirm'),
                              icon: Icons.login_rounded,
                              onPressed: loading ? null : submit,
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class _LoginTextField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final IconData icon;
  final bool obscureText;
  final TextInputType? keyboardType;
  final Widget? trailing;

  const _LoginTextField({
    required this.controller,
    required this.label,
    required this.icon,
    this.obscureText = false,
    this.keyboardType,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 56,
      decoration: BoxDecoration(
        color: const Color(0xfff7faf8),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          const SizedBox(width: 14),
          Icon(icon, size: 20, color: const Color(0xff5f716c)),
          const SizedBox(width: 10),
          Expanded(
            child: PlatformTextInput(
              controller: controller,
              obscureText: obscureText,
              keyboardType: keyboardType,
              placeholder: label,
            ),
          ),
          if (trailing != null) trailing!,
          const SizedBox(width: 6),
        ],
      ),
    );
  }
}

class _LoginModeSwitch extends StatelessWidget {
  final String mode;
  final ValueChanged<String> onChanged;

  const _LoginModeSwitch({
    required this.mode,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: const Color(0xffedf4f1),
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          Expanded(
            child: _ModeButton(
              selected: mode == 'PASSWORD',
              label: LocaleUtils.get('login_method_password'),
              icon: Icons.lock_outline_rounded,
              onTap: () => onChanged('PASSWORD'),
            ),
          ),
          Expanded(
            child: _ModeButton(
              selected: mode == 'SMS',
              label: LocaleUtils.get('login_method_sms'),
              icon: Icons.phone_iphone_rounded,
              onTap: () => onChanged('SMS'),
            ),
          ),
        ],
      ),
    );
  }
}

class _ModeButton extends StatelessWidget {
  final bool selected;
  final String label;
  final IconData icon;
  final VoidCallback onTap;

  const _ModeButton({
    required this.selected,
    required this.label,
    required this.icon,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = selected ? const Color(0xff1f6f5a) : const Color(0xff53645f);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
        decoration: BoxDecoration(
          color: selected ? Colors.white : Colors.transparent,
          borderRadius: BorderRadius.circular(14),
          boxShadow: selected
              ? [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.06),
                    blurRadius: 10,
                    offset: const Offset(0, 4),
                  ),
                ]
              : null,
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 18, color: color),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: color,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
