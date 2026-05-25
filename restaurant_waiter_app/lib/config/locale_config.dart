class LocaleConfig {
  static const supported = ['ms_my', 'en_us', 'zh_cn'];
  static const defaultLang = 'en_us';

  static String normalizeDeviceLanguageCode(String? languageCode) {
    final code = (languageCode ?? '').toLowerCase();
    if (code.startsWith('zh')) return 'zh_cn';
    if (code.startsWith('ms')) return 'ms_my';
    if (code.startsWith('en')) return 'en_us';
    return defaultLang;
  }
}
