import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../api/api_service.dart';
import '../../api/auth_api.dart';
import '../../config/app_config.dart';
import '../../config/locale_config.dart';
import '../../utils/hive_utils.dart';
import '../../utils/locale_utils.dart';
import '../../widgets/common_button.dart';
import '../../widgets/top_toast.dart';
import '../login/login_page.dart';
import 'about_us_page.dart';
import 'change_password_page.dart';
import 'edit_profile_page.dart';

class MinePage extends StatefulWidget {
  const MinePage({super.key});

  @override
  State<MinePage> createState() => _MinePageState();
}

class _MinePageState extends State<MinePage> {
  Map<String, dynamic> profile = HiveUtils.cachedProfile;
  Map<String, dynamic>? updateInfo;
  bool loading = false;

  @override
  void initState() {
    super.initState();
    loadMineData();
  }

  bool get hasUpdate => updateInfo?['hasUpdate'] == true;

  Future<void> loadMineData() async {
    setState(() => loading = true);
    try {
      final nextProfile = await AuthApi.profile();
      final nextUpdate = await AuthApi.checkUpdate();
      if (mounted) {
        setState(() {
          profile = nextProfile;
          updateInfo = nextUpdate;
        });
      }
    } catch (error) {
      debugPrint('load waiter mine data failed: $error');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> changeLang(String lang) async {
    await LocaleUtils.changeLanguage(lang);
    if (mounted) setState(() {});
  }

  Future<void> logout() async {
    await HiveUtils.clearToken();
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginPage()),
      (_) => false,
    );
  }

  Future<void> checkUpdate() async {
    try {
      final update = await AuthApi.checkUpdate();
      if (mounted) setState(() => updateInfo = update);
      if (update['hasUpdate'] == true) {
        await showUpdateDialog(update);
      } else if (mounted) {
        showTopToast(context, LocaleUtils.get('mine_update_latest'),
            icon: Icons.verified_rounded);
      }
    } catch (error) {
      if (mounted) {
        showTopToast(context, ApiService.friendlyError(error),
            icon: Icons.error_rounded);
      }
    }
  }

  Future<void> showUpdateDialog(Map<String, dynamic> update) async {
    await showDialog<void>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(LocaleUtils.get('mine_update_available')),
        content: Text(
          '${LocaleUtils.get('mine_update_body')}\n\n${update['releaseNotes'] ?? ''}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(LocaleUtils.get('mine_update_cancel')),
          ),
          FilledButton(
            onPressed: () {
              Navigator.pop(context);
              openUpdateUrl(update);
            },
            child: Text(LocaleUtils.get('mine_update_now')),
          ),
        ],
      ),
    );
  }

  Future<void> openUpdateUrl(Map<String, dynamic> update) async {
    final uri = Uri.tryParse('${update['downloadUrl'] ?? ''}');
    if (uri == null || uri.toString().isEmpty) return;
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  }

  Future<void> editProfile() async {
    final nextProfile = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(builder: (_) => EditProfilePage(profile: profile)),
    );
    if (nextProfile != null && mounted) {
      setState(() => profile = nextProfile);
    }
  }

  Future<void> showAbout() async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute(builder: (_) => const AboutUsPage()),
    );
  }

  Future<void> changePassword() async {
    await Navigator.push<void>(
      context,
      MaterialPageRoute(builder: (_) => const ChangePasswordPage()),
    );
  }

  @override
  Widget build(BuildContext context) {
    final name = '${profile['displayName'] ?? ''}'.isBlank
        ? LocaleUtils.get('mine_profile')
        : '${profile['displayName']}';
    final account = '${profile['username'] ?? profile['phone'] ?? ''}';

    return RefreshIndicator(
      onRefresh: loadMineData,
      child: ListView(
        padding: EdgeInsets.zero,
        children: [
          _ProfileCard(
            name: name,
            account: account,
            avatarUrl: '${profile['avatarUrl'] ?? ''}',
            loading: loading,
            onEdit: editProfile,
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            title: LocaleUtils.get('mine_language'),
            subtitle: LocaleUtils.get('mine_language_hint'),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SegmentedButton<String>(
                  style: ButtonStyle(
                    padding: WidgetStateProperty.all(
                      const EdgeInsets.symmetric(vertical: 14),
                    ),
                  ),
                  segments: const [
                    ButtonSegment(value: 'ms_my', label: Text('BM')),
                    ButtonSegment(value: 'en_us', label: Text('EN')),
                    ButtonSegment(value: 'zh_cn', label: Text('中文')),
                  ],
                  selected: {HiveUtils.lang},
                  onSelectionChanged: (value) => changeLang(value.first),
                ),
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: const Color(0xfff3f7fb),
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.info_outline_rounded, size: 18),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          '${LocaleUtils.get('mine_supported')}: ${LocaleConfig.supported.join(', ')}',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: Column(
              children: [
                _ActionTile(
                  icon: Icons.system_update_alt_rounded,
                  title: LocaleUtils.get('mine_check_update'),
                  subtitle: hasUpdate
                      ? '${LocaleUtils.get('mine_update_available')} v${updateInfo?['latestVersion'] ?? ''}'
                      : 'v${AppConfig.version}+${AppConfig.buildNumber}',
                  showDot: hasUpdate,
                  onTap: checkUpdate,
                ),
                const Divider(height: 1),
                _ActionTile(
                  icon: Icons.lock_reset_rounded,
                  title: LocaleUtils.get('common_change_password'),
                  subtitle: LocaleUtils.get('mine_change_password_desc'),
                  onTap: changePassword,
                ),
                const Divider(height: 1),
                _ActionTile(
                  icon: Icons.info_rounded,
                  title: LocaleUtils.get('mine_about_us'),
                  subtitle: 'v${AppConfig.version}+${AppConfig.buildNumber}',
                  onTap: showAbout,
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _SettingsCard(
            child: CommonButton(
              text: LocaleUtils.get('common_logout'),
              icon: Icons.logout_rounded,
              onPressed: logout,
            ),
          ),
        ],
      ),
    );
  }
}

class _ProfileCard extends StatelessWidget {
  final String name;
  final String account;
  final String avatarUrl;
  final bool loading;
  final VoidCallback onEdit;

  const _ProfileCard({
    required this.name,
    required this.account,
    required this.avatarUrl,
    required this.loading,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(28),
        gradient: const LinearGradient(
          colors: [Color(0xff184f43), Color(0xff2f7a69)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.08),
            blurRadius: 20,
            offset: const Offset(0, 10),
          ),
        ],
      ),
      child: Row(
        children: [
          _Avatar(avatarUrl: avatarUrl, name: name),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style:
                            Theme.of(context).textTheme.headlineSmall?.copyWith(
                                  color: Colors.white,
                                  fontWeight: FontWeight.w800,
                                ),
                      ),
                    ),
                    if (loading)
                      const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  account.isBlank
                      ? LocaleUtils.get('mine_desc')
                      : '${LocaleUtils.get('mine_username')}: $account',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Colors.white.withValues(alpha: 0.82),
                      ),
                ),
                const SizedBox(height: 14),
                FilledButton.icon(
                  onPressed: onEdit,
                  icon: const Icon(Icons.edit_rounded, size: 18),
                  label: Text(LocaleUtils.get('mine_edit_profile')),
                  style: FilledButton.styleFrom(
                    backgroundColor: Colors.white,
                    foregroundColor: const Color(0xff184f43),
                    visualDensity: VisualDensity.compact,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  final String avatarUrl;
  final String name;

  const _Avatar({required this.avatarUrl, required this.name});

  @override
  Widget build(BuildContext context) {
    final uri = Uri.tryParse(avatarUrl);
    final canLoad =
        uri != null && (uri.isScheme('http') || uri.isScheme('https'));
    return CircleAvatar(
      radius: 34,
      backgroundColor: Colors.white.withValues(alpha: 0.22),
      backgroundImage: canLoad ? NetworkImage(avatarUrl) : null,
      child: canLoad
          ? null
          : Text(
              name.isBlank ? '-' : name.characters.first.toUpperCase(),
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                    color: Colors.white,
                    fontWeight: FontWeight.w900,
                  ),
            ),
    );
  }
}

class _SettingsCard extends StatelessWidget {
  final String? title;
  final String? subtitle;
  final Widget child;

  const _SettingsCard({this.title, this.subtitle, required this.child});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.96),
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
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (title != null) ...[
            Text(
              title!,
              style: Theme.of(context).textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
            ),
            if (subtitle != null) ...[
              const SizedBox(height: 6),
              Text(
                subtitle!,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ],
            const SizedBox(height: 16),
          ],
          child,
        ],
      ),
    );
  }
}

class _ActionTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final bool showDot;
  final VoidCallback onTap;

  const _ActionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.showDot = false,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        backgroundColor: const Color(0xffe9f5f1),
        foregroundColor: const Color(0xff184f43),
        child: Icon(icon),
      ),
      title: Row(
        children: [
          Flexible(
            child: Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          if (showDot) ...[
            const SizedBox(width: 8),
            Container(
              width: 8,
              height: 8,
              decoration: const BoxDecoration(
                color: Color(0xffe0473f),
                shape: BoxShape.circle,
              ),
            ),
          ],
        ],
      ),
      subtitle: Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: onTap,
    );
  }
}

extension on String {
  bool get isBlank => trim().isEmpty;
}
