import 'package:flutter/material.dart';

import '../../api/api_service.dart';
import '../../api/auth_api.dart';
import '../../utils/locale_utils.dart';
import '../../widgets/common_button.dart';
import '../../widgets/top_toast.dart';

class ChangePasswordPage extends StatefulWidget {
  const ChangePasswordPage({super.key});

  @override
  State<ChangePasswordPage> createState() => _ChangePasswordPageState();
}

class _ChangePasswordPageState extends State<ChangePasswordPage> {
  final oldPasswordController = TextEditingController();
  final newPasswordController = TextEditingController();
  final confirmPasswordController = TextEditingController();
  bool submitting = false;
  bool showOldPassword = false;
  bool showNewPassword = false;
  bool showConfirmPassword = false;

  @override
  void dispose() {
    oldPasswordController.dispose();
    newPasswordController.dispose();
    confirmPasswordController.dispose();
    super.dispose();
  }

  Future<void> submit() async {
    final oldPassword = oldPasswordController.text.trim();
    final newPassword = newPasswordController.text.trim();
    final confirmPassword = confirmPasswordController.text.trim();
    if (oldPassword.isEmpty || newPassword.isEmpty || confirmPassword.isEmpty) {
      showTopToast(context, LocaleUtils.get('password_required'),
          icon: Icons.error_rounded);
      return;
    }
    if (newPassword.length < 6 || newPassword.length > 20) {
      showTopToast(context, LocaleUtils.get('password_length_tip'),
          icon: Icons.error_rounded);
      return;
    }
    if (newPassword != confirmPassword) {
      showTopToast(context, LocaleUtils.get('password_mismatch'),
          icon: Icons.error_rounded);
      return;
    }
    setState(() => submitting = true);
    try {
      await AuthApi.changePassword(
        oldPassword: oldPassword,
        newPassword: newPassword,
        confirmPassword: confirmPassword,
      );
      if (!mounted) return;
      showTopToast(context, LocaleUtils.get('password_changed'),
          icon: Icons.verified_rounded);
      Navigator.pop(context);
    } catch (error) {
      if (mounted) {
        showTopToast(context, ApiService.friendlyError(error),
            icon: Icons.error_rounded);
      }
    } finally {
      if (mounted) setState(() => submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(LocaleUtils.get('common_change_password')),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(18),
          children: [
            Container(
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(24),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.05),
                    blurRadius: 18,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: Column(
                children: [
                  _PasswordField(
                    controller: oldPasswordController,
                    label: LocaleUtils.get('password_old'),
                    visible: showOldPassword,
                    onToggle: () =>
                        setState(() => showOldPassword = !showOldPassword),
                    textInputAction: TextInputAction.next,
                  ),
                  const SizedBox(height: 14),
                  _PasswordField(
                    controller: newPasswordController,
                    label: LocaleUtils.get('password_new'),
                    visible: showNewPassword,
                    onToggle: () =>
                        setState(() => showNewPassword = !showNewPassword),
                    textInputAction: TextInputAction.next,
                  ),
                  const SizedBox(height: 14),
                  _PasswordField(
                    controller: confirmPasswordController,
                    label: LocaleUtils.get('password_confirm'),
                    visible: showConfirmPassword,
                    onToggle: () => setState(
                        () => showConfirmPassword = !showConfirmPassword),
                    onSubmitted: (_) {
                      if (!submitting) submit();
                    },
                  ),
                  const SizedBox(height: 24),
                  CommonButton(
                    text: LocaleUtils.get('common_confirm'),
                    icon: Icons.lock_reset_rounded,
                    onPressed: submitting ? null : submit,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PasswordField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final bool visible;
  final VoidCallback onToggle;
  final TextInputAction? textInputAction;
  final ValueChanged<String>? onSubmitted;

  const _PasswordField({
    required this.controller,
    required this.label,
    required this.visible,
    required this.onToggle,
    this.textInputAction,
    this.onSubmitted,
  });

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      obscureText: !visible,
      textInputAction: textInputAction ?? TextInputAction.done,
      onSubmitted: onSubmitted,
      decoration: InputDecoration(
        labelText: label,
        prefixIcon: const Icon(Icons.lock_rounded),
        suffixIcon: IconButton(
          tooltip: visible
              ? LocaleUtils.get('password_hide')
              : LocaleUtils.get('password_show'),
          onPressed: onToggle,
          icon: Icon(
            visible ? Icons.visibility_off_rounded : Icons.visibility_rounded,
          ),
        ),
      ),
    );
  }
}
