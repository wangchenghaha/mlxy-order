class AppConfig {
  static const platform = 'waiter_app';
  static const version = String.fromEnvironment(
    'APP_VERSION',
    defaultValue: '1.0.0',
  );
  static const buildNumber = int.fromEnvironment(
    'APP_BUILD_NUMBER',
    defaultValue: 1,
  );
}
