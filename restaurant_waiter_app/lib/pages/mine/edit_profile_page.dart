import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../../api/api_service.dart';
import '../../api/auth_api.dart';
import '../../utils/locale_utils.dart';
import '../../widgets/common_button.dart';
import '../../widgets/top_toast.dart';

class EditProfilePage extends StatefulWidget {
  final Map<String, dynamic> profile;

  const EditProfilePage({super.key, required this.profile});

  @override
  State<EditProfilePage> createState() => _EditProfilePageState();
}

class _EditProfilePageState extends State<EditProfilePage> {
  final picker = ImagePicker();
  late final TextEditingController nameController;
  late final TextEditingController phoneController;
  String avatarUrl = '';
  bool saving = false;
  bool uploading = false;

  @override
  void initState() {
    super.initState();
    nameController =
        TextEditingController(text: '${widget.profile['displayName'] ?? ''}');
    phoneController =
        TextEditingController(text: '${widget.profile['phone'] ?? ''}');
    avatarUrl = '${widget.profile['avatarUrl'] ?? ''}';
  }

  @override
  void dispose() {
    nameController.dispose();
    phoneController.dispose();
    super.dispose();
  }

  Future<void> pickAvatar() async {
    try {
      final image = await picker.pickImage(
        source: ImageSource.gallery,
        imageQuality: 82,
        maxWidth: 1024,
      );
      if (image == null) return;
      setState(() => uploading = true);
      final nextAvatarUrl = await AuthApi.uploadAvatar(image.path);
      if (mounted) setState(() => avatarUrl = nextAvatarUrl);
    } catch (error) {
      if (mounted) {
        showTopToast(context, ApiService.friendlyError(error),
            icon: Icons.error_rounded);
      }
    } finally {
      if (mounted) setState(() => uploading = false);
    }
  }

  Future<void> save() async {
    setState(() => saving = true);
    try {
      final nextProfile = await AuthApi.updateProfile(
        phone: phoneController.text.trim(),
        displayName: nameController.text.trim(),
        avatarUrl: avatarUrl,
      );
      if (!mounted) return;
      showTopToast(context, LocaleUtils.get('mine_profile_updated'));
      Navigator.pop(context, nextProfile);
    } catch (error) {
      if (mounted) {
        showTopToast(context, ApiService.friendlyError(error),
            icon: Icons.error_rounded);
      }
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final canSave = !saving && !uploading;
    return Scaffold(
      appBar: AppBar(
        title: Text(LocaleUtils.get('mine_edit_profile')),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(18),
          children: [
            Center(
              child: Stack(
                alignment: Alignment.bottomRight,
                children: [
                  _AvatarPreview(avatarUrl: avatarUrl),
                  FilledButton(
                    onPressed: uploading ? null : pickAvatar,
                    style: FilledButton.styleFrom(
                      shape: const CircleBorder(),
                      padding: const EdgeInsets.all(12),
                    ),
                    child: uploading
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : const Icon(Icons.photo_library_rounded, size: 18),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
            Center(
              child: TextButton.icon(
                onPressed: uploading ? null : pickAvatar,
                icon: const Icon(Icons.image_rounded),
                label: Text(LocaleUtils.get('mine_pick_avatar')),
              ),
            ),
            const SizedBox(height: 18),
            TextField(
              controller: nameController,
              textInputAction: TextInputAction.next,
              decoration: InputDecoration(
                labelText: LocaleUtils.get('mine_display_name'),
                prefixIcon: const Icon(Icons.person_rounded),
              ),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: phoneController,
              keyboardType: TextInputType.phone,
              decoration: InputDecoration(
                labelText: LocaleUtils.get('mine_phone'),
                prefixIcon: const Icon(Icons.phone_rounded),
              ),
            ),
            const SizedBox(height: 24),
            CommonButton(
              text: LocaleUtils.get('mine_save_profile'),
              icon: Icons.check_rounded,
              onPressed: canSave ? save : null,
            ),
          ],
        ),
      ),
    );
  }
}

class _AvatarPreview extends StatelessWidget {
  final String avatarUrl;

  const _AvatarPreview({required this.avatarUrl});

  @override
  Widget build(BuildContext context) {
    final uri = Uri.tryParse(avatarUrl);
    final canLoad =
        uri != null && (uri.isScheme('http') || uri.isScheme('https'));
    return CircleAvatar(
      radius: 58,
      backgroundColor: const Color(0xffe9f5f1),
      backgroundImage: canLoad ? NetworkImage(avatarUrl) : null,
      child: canLoad
          ? null
          : const Icon(Icons.person_rounded,
              size: 48, color: Color(0xff184f43)),
    );
  }
}
