import '../config/api_config.dart';

class AssetUtils {
  static String imageUrl(dynamic value) {
    final raw = value?.toString() ?? '';
    if (raw.isEmpty) return '';
    if (raw.startsWith('http://') ||
        raw.startsWith('https://') ||
        raw.startsWith('data:') ||
        raw.startsWith('blob:')) {
      return raw;
    }
    final origin = ApiConfig.baseUrl.replaceFirst(RegExp(r'/api/?$'), '');
    return '${origin.replaceFirst(RegExp(r'/$'), '')}/${raw.replaceFirst(RegExp(r'^/+'), '')}';
  }
}
