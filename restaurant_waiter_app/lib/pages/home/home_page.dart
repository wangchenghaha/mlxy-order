import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../api/auth_api.dart';
import '../../api/order_api.dart';
import '../../api/realtime_connection.dart';
import '../../api/realtime_api.dart';
import '../../api/table_api.dart';
import '../../utils/asset_utils.dart';
import '../../utils/hive_utils.dart';
import '../../utils/locale_utils.dart';
import '../../utils/network_utils.dart';
import '../../widgets/app_shell.dart';
import '../../widgets/table_item.dart';
import '../../widgets/top_toast.dart';
import '../mine/mine_page.dart';
import '../order/order_page.dart';
import '../order_history/order_history_detail_page.dart';
import '../table_detail/table_detail_page.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  List<dynamic> tables = [];
  List<dynamic> orders = [];
  int tab = 0;
  bool loading = false;
  bool offline = false;
  String tableStatusFilter = 'ALL';
  String orderDateFilter = 'TODAY';
  Map<String, dynamic> profile = HiveUtils.cachedProfile;
  late DateTime orderStartDate;
  late DateTime orderEndDate;
  DateTime? lastSyncedAt;
  RealtimeConnection? realtimeSubscription;
  Timer? realtimeReloadTimer;
  Timer? realtimeReconnectTimer;
  Timer? tableFallbackTimer;
  StreamSubscription<bool>? networkSubscription;
  int reconnectAttempt = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    LocaleUtils.rebuildNotifier.addListener(rebuildForLanguageChange);
    final today = _dateOnly(DateTime.now());
    orderStartDate = today;
    orderEndDate = today;
    restoreCachedData();
    watchNetwork();
    load();
    connectRealtime();
    tableFallbackTimer = Timer.periodic(const Duration(seconds: 20), (_) {
      if (mounted) refreshTables();
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => maybePromptUpdate());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    LocaleUtils.rebuildNotifier.removeListener(rebuildForLanguageChange);
    realtimeSubscription?.cancel();
    realtimeReloadTimer?.cancel();
    realtimeReconnectTimer?.cancel();
    tableFallbackTimer?.cancel();
    networkSubscription?.cancel();
    super.dispose();
  }

  void rebuildForLanguageChange() {
    if (mounted) setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      connectRealtime();
      refreshTables();
    }
  }

  Future<void> load() async {
    if (mounted) setState(() => loading = true);
    List<dynamic> nextTables = tables;
    try {
      nextTables = await TableApi.list();
      HiveUtils.cachedTables = nextTables;
      offline = false;
      lastSyncedAt = DateTime.now();
    } catch (error) {
      offline = !await NetworkUtils.hasConnection;
      debugPrint('load waiter tables failed: $error');
    }
    List<dynamic> nextOrders = orders;
    try {
      nextOrders = await OrderApi.historyOrders(
        startDate: _dateParam(orderStartDate),
        endDate: _dateParam(orderEndDate),
      );
      HiveUtils.cachedHistoryOrders = nextOrders;
      offline = false;
    } catch (error) {
      offline = !await NetworkUtils.hasConnection;
      debugPrint('load waiter history failed: $error');
    }
    tables = nextTables;
    orders = nextOrders;
    if (mounted) setState(() => loading = false);
  }

  Future<void> refreshTables() async {
    try {
      final nextTables = await TableApi.list();
      HiveUtils.cachedTables = nextTables;
      if (mounted) {
        setState(() {
          tables = nextTables;
          offline = false;
          lastSyncedAt = DateTime.now();
        });
      }
    } catch (error) {
      if (mounted) setState(() => offline = true);
      debugPrint('refresh waiter tables failed: $error');
    }
  }

  Future<void> refreshOrders() async {
    try {
      final nextOrders = await OrderApi.historyOrders(
        startDate: _dateParam(orderStartDate),
        endDate: _dateParam(orderEndDate),
      );
      HiveUtils.cachedHistoryOrders = nextOrders;
      if (mounted) {
        setState(() {
          orders = nextOrders;
          offline = false;
        });
      }
    } catch (error) {
      if (mounted) setState(() => offline = true);
      debugPrint('refresh waiter history failed: $error');
    }
  }

  Future<void> connectRealtime() async {
    realtimeSubscription?.cancel();
    realtimeSubscription = await RealtimeApi.subscribe(
      scheduleRealtimeLoad,
      onDisconnected: scheduleRealtimeReconnect,
    );
    if (realtimeSubscription != null) reconnectAttempt = 0;
  }

  void scheduleRealtimeReconnect() {
    realtimeSubscription?.cancel();
    realtimeSubscription = null;
    if (realtimeReconnectTimer != null || !mounted) return;
    reconnectAttempt = (reconnectAttempt + 1).clamp(1, 6);
    final delaySeconds = reconnectAttempt * 3;
    realtimeReconnectTimer = Timer(Duration(seconds: delaySeconds), () {
      realtimeReconnectTimer = null;
      if (mounted) connectRealtime();
    });
  }

  void scheduleRealtimeLoad() {
    if (realtimeReloadTimer != null) return;
    realtimeReloadTimer = Timer(const Duration(milliseconds: 350), () {
      realtimeReloadTimer = null;
      if (mounted) refreshTables();
    });
  }

  void restoreCachedData() {
    tables = HiveUtils.cachedTables;
    orders = HiveUtils.cachedHistoryOrders;
  }

  void watchNetwork() {
    networkSubscription = NetworkUtils.onConnectionChanged.listen((connected) {
      if (!mounted) return;
      setState(() => offline = !connected);
      if (connected) {
        reconnectAttempt = 0;
        connectRealtime();
        load();
      }
    });
  }

  Future<void> maybePromptUpdate() async {
    try {
      final nextProfile = await AuthApi.profile();
      if (mounted) setState(() => profile = nextProfile);
      final update = await AuthApi.checkUpdate();
      final hasUpdate = update['hasUpdate'] == true;
      final latestVersion = '${update['latestVersion'] ?? ''}';
      if (!hasUpdate ||
          latestVersion.isEmpty ||
          HiveUtils.hasPromptedUpdate(nextProfile['userId'], latestVersion)) {
        return;
      }
      HiveUtils.markPromptedUpdate(nextProfile['userId'], latestVersion);
      if (mounted) showUpdateDialog(update);
    } catch (error) {
      debugPrint('check waiter app update failed: $error');
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

  Future<void> openTable(dynamic table) async {
    if (table['status'] == 'EMPTY' || table['status'] == 'RESERVED') {
      final people = await showDialog<int>(
        context: context,
        builder: (_) => const _PeopleDialog(),
      );
      if (people == null) return;
      if (!mounted) return;
      final submitted = await Navigator.push<bool>(
        context,
        MaterialPageRoute(
          builder: (_) => OrderPage(
            tableId: table['id'],
            tableNo: table['tableNo'],
            people: people,
          ),
        ),
      );
      if (mounted && submitted == true) {
        showTopToast(context, LocaleUtils.get('common_success'));
      }
      await load();
      return;
    }
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => TableDetailPage(table: table, onRefresh: load),
      ),
    );
  }

  Future<void> openHistoryOrder(dynamic order) async {
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) =>
            OrderHistoryDetailPage(order: Map<String, dynamic>.from(order)),
      ),
    );
  }

  Future<void> changeOrderDateFilter(String value) async {
    final today = _dateOnly(DateTime.now());
    setState(() {
      orderDateFilter = value;
      if (value == 'TODAY') {
        orderStartDate = today;
        orderEndDate = today;
      } else if (value == 'WEEK') {
        orderStartDate = today.subtract(const Duration(days: 6));
        orderEndDate = today;
      } else if (value == 'MONTH') {
        orderStartDate = today.subtract(const Duration(days: 29));
        orderEndDate = today;
      }
    });
    await refreshOrders();
  }

  Future<void> openCustomDateRange() async {
    final result = await showModalBottomSheet<_DateRangeResult>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (_) => _DateRangeSheet(
        startDate: orderStartDate,
        endDate: orderEndDate,
      ),
    );
    if (result == null) return;
    setState(() {
      orderDateFilter = 'CUSTOM';
      orderStartDate = result.startDate;
      orderEndDate = result.endDate;
    });
    await refreshOrders();
  }

  @override
  Widget build(BuildContext context) {
    final filteredTables = tableStatusFilter == 'ALL'
        ? tables
        : tables
            .where((table) => table['status'] == tableStatusFilter)
            .toList();
    final historyCards = orders
        .map((order) => _HistoryOrderCard(
            order: order, onTap: () => openHistoryOrder(order)))
        .toList();

    final pages = [
      RefreshIndicator(
        onRefresh: load,
        child: ListView(
          padding: EdgeInsets.zero,
          children: [
            if (offline) ...[
              _OfflineBanner(onRetry: load),
              const SizedBox(height: 12),
            ],
            if (loading && tables.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 80),
                child: Center(child: CircularProgressIndicator()),
              )
            else ...[
              _StatusFilterBar(
                selected: tableStatusFilter,
                tables: tables,
                onChanged: (value) => setState(() => tableStatusFilter = value),
                onHelp: showTableStatusHelp,
              ),
              const SizedBox(height: 14),
              GridView.builder(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 2,
                  childAspectRatio: 0.92,
                  mainAxisSpacing: 12,
                  crossAxisSpacing: 12,
                ),
                itemCount: filteredTables.length,
                itemBuilder: (_, i) => TableItem(
                  table: filteredTables[i],
                  onTap: () => openTable(filteredTables[i]),
                ),
              ),
            ],
          ],
        ),
      ),
      RefreshIndicator(
        onRefresh: refreshOrders,
        child: ListView(
          padding: EdgeInsets.zero,
          children: [
            if (offline) ...[
              _OfflineBanner(onRetry: load),
              const SizedBox(height: 12),
            ],
            _OrderDateFilterBar(
              selected: orderDateFilter,
              startDate: orderStartDate,
              endDate: orderEndDate,
              onSelected: changeOrderDateFilter,
              onCustom: openCustomDateRange,
            ),
            const SizedBox(height: 14),
            if (historyCards.isEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 30),
                child: Center(
                  child: Text(
                    LocaleUtils.get('history_empty'),
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                ),
              )
            else
              ...historyCards,
          ],
        ),
      ),
      const MinePage(),
    ];

    return AppShell(
      title: _shellTitle,
      subtitle: _shellSubtitle,
      customHeader: _buildHeader(context),
      bottomBar: NavigationBar(
        height: 64,
        elevation: 0,
        backgroundColor: Colors.transparent,
        indicatorColor: const Color(0xffd8eee6),
        selectedIndex: tab,
        onDestinationSelected: (value) => setState(() => tab = value),
        destinations: [
          NavigationDestination(
              icon: const Icon(Icons.table_restaurant),
              label: LocaleUtils.get('home_tables')),
          NavigationDestination(
              icon: const Icon(Icons.receipt_long),
              label: LocaleUtils.get('history_orders')),
          NavigationDestination(
              icon: const Icon(Icons.person),
              label: LocaleUtils.get('home_mine')),
        ],
      ),
      child: Expanded(child: pages[tab]),
    );
  }

  String get _shellTitle {
    if (tab == 1) return LocaleUtils.get('history_orders');
    if (tab == 2) return LocaleUtils.get('home_mine');
    return LocaleUtils.get('home_workbench');
  }

  String get _shellSubtitle {
    if (tab == 1) return LocaleUtils.get('history_orders_desc');
    if (tab == 2) return LocaleUtils.get('mine_desc');
    return _headerSubtitle;
  }

  String get _headerSubtitle {
    final storeName = _currentStoreName();
    final syncStatus = offline
        ? LocaleUtils.get('home_offline_short')
        : '${_formatSyncTime(lastSyncedAt)} ${LocaleUtils.get('home_synced')}';
    return '$storeName · $syncStatus';
  }

  Widget _buildHeader(BuildContext context) {
    if (tab != 0) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _shellTitle,
            style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                  fontWeight: FontWeight.w800,
                ),
          ),
          const SizedBox(height: 6),
          Text(
            _shellSubtitle,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ],
      );
    }
    return _WorkbenchHeader(
      title: _greetingTitle(),
      subtitle: _headerSubtitle,
    );
  }

  String _greetingTitle() {
    final hour = DateTime.now().hour;
    final greeting = hour < 11
        ? LocaleUtils.get('home_greeting_morning')
        : hour < 13
            ? LocaleUtils.get('home_greeting_noon')
            : hour < 18
                ? LocaleUtils.get('home_greeting_afternoon')
                : LocaleUtils.get('home_greeting_evening');
    final name = _profileName();
    return name.isEmpty ? greeting : '$greeting，$name';
  }

  String _profileName() {
    final displayName = '${profile['displayName'] ?? ''}'.trim();
    if (displayName.isNotEmpty) return displayName;
    final username = '${profile['username'] ?? ''}'.trim();
    if (username.isNotEmpty) return username;
    return '';
  }

  String _currentStoreName() {
    final stores = tables
        .map((table) => '${table['storeName'] ?? table['area'] ?? ''}'.trim())
        .where((value) => value.isNotEmpty)
        .toSet();
    if (stores.length == 1) return stores.first;
    return LocaleUtils.get('home_dining_room');
  }

  String _formatSyncTime(DateTime? value) {
    final time = value ?? DateTime.now();
    final hour = time.hour.toString().padLeft(2, '0');
    final minute = time.minute.toString().padLeft(2, '0');
    return '$hour:$minute';
  }

  DateTime _dateOnly(DateTime value) {
    return DateTime(value.year, value.month, value.day);
  }

  String _dateParam(DateTime value) {
    final month = value.month.toString().padLeft(2, '0');
    final day = value.day.toString().padLeft(2, '0');
    return '${value.year}-$month-$day';
  }

  Future<void> showTableStatusHelp() async {
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (_) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 8, 22, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                LocaleUtils.get('home_status_help'),
                style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
              ),
              const SizedBox(height: 14),
              _StatusHelpLine(
                color: const Color(0xffd48a1d),
                title: LocaleUtils.get('table_dining'),
                body: LocaleUtils.get('home_status_help_dining'),
              ),
              const SizedBox(height: 12),
              _StatusHelpLine(
                color: const Color(0xffc65d3f),
                title: LocaleUtils.get('table_checkout'),
                body: LocaleUtils.get('home_status_help_checkout'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _OfflineBanner extends StatelessWidget {
  final Future<void> Function() onRetry;

  const _OfflineBanner({required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xfffff4e4),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xffffd59a)),
      ),
      child: Row(
        children: [
          const Icon(Icons.wifi_off_rounded,
              color: Color(0xff9a5b12), size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              LocaleUtils.get('network_offline'),
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: const Color(0xff74420a),
                    fontWeight: FontWeight.w700,
                  ),
            ),
          ),
          TextButton(
            onPressed: onRetry,
            child: Text(LocaleUtils.get('common_retry')),
          ),
        ],
      ),
    );
  }
}

class _WorkbenchHeader extends StatelessWidget {
  final String title;
  final String subtitle;

  const _WorkbenchHeader({
    required this.title,
    required this.subtitle,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                fontWeight: FontWeight.w900,
              ),
        ),
        const SizedBox(height: 6),
        Text(
          subtitle,
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.w600,
              ),
        ),
        const SizedBox(height: 6),
        Text(
          LocaleUtils.get('home_workbench'),
          style: Theme.of(context).textTheme.labelLarge?.copyWith(
                color: const Color(0xff184f43),
                fontWeight: FontWeight.w900,
              ),
        ),
      ],
    );
  }
}

class _StatusFilterBar extends StatelessWidget {
  final String selected;
  final List<dynamic> tables;
  final ValueChanged<String> onChanged;
  final VoidCallback onHelp;

  const _StatusFilterBar({
    required this.selected,
    required this.tables,
    required this.onChanged,
    required this.onHelp,
  });

  @override
  Widget build(BuildContext context) {
    final options = [
      _StatusFilterOption('ALL', LocaleUtils.get('home_status_all')),
      _StatusFilterOption('EMPTY', LocaleUtils.get('table_available')),
      _StatusFilterOption('RESERVED', LocaleUtils.get('table_reserved')),
      _StatusFilterOption('DINING', LocaleUtils.get('table_dining')),
      _StatusFilterOption(
          'PENDING_CHECKOUT', LocaleUtils.get('table_checkout')),
      _StatusFilterOption('CLEANING', LocaleUtils.get('table_cleaning')),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          height: 38,
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ...options.map(
                  (option) => Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: _StatusFilterChip(
                      label: option.label,
                      count: _count(option.status),
                      selected: selected == option.status,
                      color: _statusColor(option.status),
                      onTap: () => onChanged(option.status),
                    ),
                  ),
                ),
                _StatusHelpChip(onTap: onHelp),
              ],
            ),
          ),
        ),
      ],
    );
  }

  int _count(String status) {
    if (status == 'ALL') return tables.length;
    return tables.where((table) => table['status'] == status).length;
  }

  Color _statusColor(String status) {
    return switch (status) {
      'EMPTY' => const Color(0xff1f8f67),
      'RESERVED' => const Color(0xffd48a1d),
      'DINING' => const Color(0xffd48a1d),
      'PENDING_CHECKOUT' => const Color(0xffc65d3f),
      'CLEANING' => const Color(0xff64748b),
      _ => const Color(0xff184f43),
    };
  }
}

class _StatusFilterOption {
  final String status;
  final String label;

  const _StatusFilterOption(this.status, this.label);
}

class _StatusFilterChip extends StatelessWidget {
  final String label;
  final int count;
  final bool selected;
  final Color color;
  final VoidCallback onTap;

  const _StatusFilterChip({
    required this.label,
    required this.count,
    required this.selected,
    required this.color,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final textColor = selected ? Colors.white : color;
    return Semantics(
      button: true,
      selected: selected,
      label: '$label $count',
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(999),
          onTap: onTap,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 160),
            height: 36,
            constraints: const BoxConstraints(minWidth: 62),
            alignment: Alignment.center,
            padding: const EdgeInsets.symmetric(horizontal: 14),
            decoration: BoxDecoration(
              color: selected ? color : color.withValues(alpha: 0.10),
              borderRadius: BorderRadius.circular(999),
              border: Border.all(
                  color: color.withValues(alpha: selected ? 0 : 0.24)),
            ),
            child: Text(
              '$label $count',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              strutStyle: const StrutStyle(
                height: 1.1,
                forceStrutHeight: true,
              ),
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    color: textColor,
                    fontWeight: FontWeight.w800,
                    height: 1.1,
                  ),
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusHelpChip extends StatelessWidget {
  final VoidCallback onTap;

  const _StatusHelpChip({required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(999),
          onTap: onTap,
          child: Container(
            height: 36,
            alignment: Alignment.center,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.56),
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: Colors.black.withValues(alpha: 0.08)),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.help_outline_rounded, size: 18),
                const SizedBox(width: 7),
                Text(
                  LocaleUtils.get('home_status_help'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  strutStyle: const StrutStyle(
                    height: 1.1,
                    forceStrutHeight: true,
                  ),
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        height: 1.1,
                      ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusHelpLine extends StatelessWidget {
  final Color color;
  final String title;
  final String body;

  const _StatusHelpLine({
    required this.color,
    required this.title,
    required this.body,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 10,
          height: 10,
          margin: const EdgeInsets.only(top: 7),
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
              ),
              const SizedBox(height: 4),
              Text(
                body,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      height: 1.45,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _OrderDateFilterBar extends StatelessWidget {
  final String selected;
  final DateTime startDate;
  final DateTime endDate;
  final ValueChanged<String> onSelected;
  final VoidCallback onCustom;

  const _OrderDateFilterBar({
    required this.selected,
    required this.startDate,
    required this.endDate,
    required this.onSelected,
    required this.onCustom,
  });

  @override
  Widget build(BuildContext context) {
    final options = [
      _OrderDateOption('TODAY', LocaleUtils.get('order_filter_today')),
      _OrderDateOption('WEEK', LocaleUtils.get('order_filter_week')),
      _OrderDateOption('MONTH', LocaleUtils.get('order_filter_month')),
    ];
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          ...options.map(
            (option) => Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                selected: selected == option.value,
                showCheckmark: false,
                label: Text(option.label),
                onSelected: (_) => onSelected(option.value),
                selectedColor: const Color(0xff184f43),
                backgroundColor: const Color(0xff184f43).withValues(alpha: 0.1),
                labelStyle: Theme.of(context).textTheme.labelLarge?.copyWith(
                      color: selected == option.value
                          ? Colors.white
                          : const Color(0xff184f43),
                      fontWeight: FontWeight.w800,
                    ),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(999)),
              ),
            ),
          ),
          ActionChip(
            avatar: const Icon(Icons.date_range_rounded, size: 18),
            label: Text(selected == 'CUSTOM'
                ? '${_formatShortDate(startDate)} - ${_formatShortDate(endDate)}'
                : LocaleUtils.get('order_filter_custom')),
            onPressed: onCustom,
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(999)),
          ),
        ],
      ),
    );
  }
}

class _OrderDateOption {
  final String value;
  final String label;

  const _OrderDateOption(this.value, this.label);
}

class _DateRangeSheet extends StatefulWidget {
  final DateTime startDate;
  final DateTime endDate;

  const _DateRangeSheet({
    required this.startDate,
    required this.endDate,
  });

  @override
  State<_DateRangeSheet> createState() => _DateRangeSheetState();
}

class _DateRangeSheetState extends State<_DateRangeSheet> {
  static final DateTime _minimumDate = DateTime(2020, 1, 1);

  late DateTime startDate;
  late DateTime endDate;
  bool editingStart = true;
  FixedExtentScrollController? yearController;
  FixedExtentScrollController? monthController;
  FixedExtentScrollController? dayController;

  @override
  void initState() {
    super.initState();
    startDate = _dateOnly(widget.startDate);
    endDate = _dateOnly(widget.endDate);
    _resetPickerControllers();
  }

  @override
  void dispose() {
    yearController?.dispose();
    monthController?.dispose();
    dayController?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final today = _dateOnly(DateTime.now());
    final selectedDate = editingStart ? startDate : endDate;
    final selectedLabel = editingStart
        ? LocaleUtils.get('order_filter_start')
        : LocaleUtils.get('order_filter_end');
    const primary = Color(0xff184f43);
    const textColor = Color(0xff0f172a);
    const mutedColor = Color(0xff64748b);
    return SafeArea(
      top: false,
      child: FractionallySizedBox(
        heightFactor: MediaQuery.sizeOf(context).height < 720 ? 0.76 : 0.64,
        child: Container(
          decoration: const BoxDecoration(
            color: Color(0xfff8fafc),
            borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
            boxShadow: [
              BoxShadow(
                color: Color(0x26000000),
                blurRadius: 28,
                offset: Offset(0, -10),
              ),
            ],
          ),
          child: Column(
            children: [
              const SizedBox(height: 10),
              Container(
                width: 42,
                height: 4,
                decoration: BoxDecoration(
                  color: const Color(0xffcbd5e1),
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 12, 10),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            LocaleUtils.get('order_filter_custom_title'),
                            style: Theme.of(context)
                                .textTheme
                                .titleMedium
                                ?.copyWith(
                                  color: textColor,
                                  fontWeight: FontWeight.w900,
                                ),
                          ),
                          const SizedBox(height: 5),
                          Text(
                            '${_formatShortDate(startDate)} - ${_formatShortDate(endDate)}',
                            style:
                                Theme.of(context).textTheme.bodySmall?.copyWith(
                                      color: mutedColor,
                                      fontWeight: FontWeight.w700,
                                    ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.close_rounded),
                      color: mutedColor,
                      tooltip: LocaleUtils.get('common_cancel'),
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 14),
                child: Container(
                  padding: const EdgeInsets.all(4),
                  decoration: BoxDecoration(
                    color: const Color(0xffe8eef3),
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: _DateRangeEndpoint(
                          label: LocaleUtils.get('order_filter_start'),
                          date: startDate,
                          selected: editingStart,
                          onTap: () => _switchEditingDate(true),
                        ),
                      ),
                      Expanded(
                        child: _DateRangeEndpoint(
                          label: LocaleUtils.get('order_filter_end'),
                          date: endDate,
                          selected: !editingStart,
                          onTap: () => _switchEditingDate(false),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Container(
                  height: 274,
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: const Color(0xffe2e8f0)),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Column(
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(18, 16, 18, 8),
                        child: Row(
                          children: [
                            Container(
                              width: 34,
                              height: 34,
                              decoration: BoxDecoration(
                                color: primary.withValues(alpha: 0.1),
                                shape: BoxShape.circle,
                              ),
                              child: const Icon(
                                Icons.calendar_month_rounded,
                                size: 18,
                                color: primary,
                              ),
                            ),
                            const SizedBox(width: 10),
                            Text(
                              selectedLabel,
                              style: Theme.of(context)
                                  .textTheme
                                  .labelLarge
                                  ?.copyWith(
                                    color: textColor,
                                    fontWeight: FontWeight.w900,
                                  ),
                            ),
                            const Spacer(),
                            Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 10,
                                vertical: 6,
                              ),
                              decoration: BoxDecoration(
                                color: primary.withValues(alpha: 0.08),
                                borderRadius: BorderRadius.circular(999),
                              ),
                              child: Text(
                                _formatShortDate(selectedDate),
                                style: Theme.of(context)
                                    .textTheme
                                    .labelMedium
                                    ?.copyWith(
                                      color: primary,
                                      fontWeight: FontWeight.w900,
                                    ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const Divider(height: 1, color: Color(0xffedf2f7)),
                      Expanded(
                        child: _NumericDateWheelPicker(
                          key: ValueKey(editingStart
                              ? 'start-numeric-picker'
                              : 'end-numeric-picker'),
                          date: selectedDate.isAfter(today)
                              ? today
                              : selectedDate,
                          minDate: _minimumDate,
                          maxDate: today,
                          yearController: yearController!,
                          monthController: monthController!,
                          dayController: dayController!,
                          onChanged: _updateSelectedDate,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const Spacer(),
              Container(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
                decoration: const BoxDecoration(
                  color: Colors.white,
                  border: Border(
                    top: BorderSide(color: Color(0xffe2e8f0)),
                  ),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size.fromHeight(46),
                          foregroundColor: mutedColor,
                          side: const BorderSide(color: Color(0xffcbd5e1)),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(16),
                          ),
                        ),
                        onPressed: () => Navigator.pop(context),
                        child: Text(LocaleUtils.get('common_cancel')),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton(
                        style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(46),
                          backgroundColor: primary,
                          foregroundColor: Colors.white,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(16),
                          ),
                        ),
                        onPressed: () => Navigator.pop(
                          context,
                          _DateRangeResult(startDate, endDate),
                        ),
                        child: Text(LocaleUtils.get('common_confirm')),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  DateTime _dateOnly(DateTime value) {
    return DateTime(value.year, value.month, value.day);
  }

  void _switchEditingDate(bool nextEditingStart) {
    if (editingStart == nextEditingStart) return;
    setState(() {
      editingStart = nextEditingStart;
      _resetPickerControllers();
    });
  }

  void _resetPickerControllers() {
    final selected = editingStart ? startDate : endDate;
    final normalized = _clampDate(selected);
    final yearIndex = normalized.year - _minimumDate.year;
    final monthIndex = normalized.month - 1;
    final dayIndex = normalized.day - 1;
    yearController?.dispose();
    monthController?.dispose();
    dayController?.dispose();
    yearController = FixedExtentScrollController(initialItem: yearIndex);
    monthController = FixedExtentScrollController(initialItem: monthIndex);
    dayController = FixedExtentScrollController(initialItem: dayIndex);
  }

  DateTime _clampDate(DateTime value) {
    final today = _dateOnly(DateTime.now());
    final normalized = _dateOnly(value);
    if (normalized.isBefore(_minimumDate)) return _minimumDate;
    if (normalized.isAfter(today)) return today;
    return normalized;
  }

  void _updateSelectedDate(DateTime value) {
    final next = _clampDate(value);
    setState(() {
      if (editingStart) {
        startDate = next;
        if (startDate.isAfter(endDate)) {
          endDate = startDate;
        }
      } else {
        endDate = next;
        if (endDate.isBefore(startDate)) {
          startDate = endDate;
        }
      }
    });
  }
}

class _NumericDateWheelPicker extends StatefulWidget {
  final DateTime date;
  final DateTime minDate;
  final DateTime maxDate;
  final FixedExtentScrollController yearController;
  final FixedExtentScrollController monthController;
  final FixedExtentScrollController dayController;
  final ValueChanged<DateTime> onChanged;

  const _NumericDateWheelPicker({
    super.key,
    required this.date,
    required this.minDate,
    required this.maxDate,
    required this.yearController,
    required this.monthController,
    required this.dayController,
    required this.onChanged,
  });

  @override
  State<_NumericDateWheelPicker> createState() =>
      _NumericDateWheelPickerState();
}

class _NumericDateWheelPickerState extends State<_NumericDateWheelPicker> {
  static const double _itemExtent = 42;

  late int selectedYear;
  late int selectedMonth;
  late int selectedDay;

  int get yearCount => widget.maxDate.year - widget.minDate.year + 1;

  @override
  void initState() {
    super.initState();
    _syncFromWidget();
  }

  @override
  void didUpdateWidget(covariant _NumericDateWheelPicker oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.date != widget.date ||
        oldWidget.minDate != widget.minDate ||
        oldWidget.maxDate != widget.maxDate) {
      _syncFromWidget();
    }
  }

  @override
  Widget build(BuildContext context) {
    const primary = Color(0xff184f43);
    return Stack(
      children: [
        Positioned.fill(
          child: Center(
            child: Container(
              height: _itemExtent,
              margin: const EdgeInsets.symmetric(horizontal: 16),
              decoration: BoxDecoration(
                color: primary.withValues(alpha: 0.09),
                borderRadius: BorderRadius.circular(14),
                border: Border.symmetric(
                  horizontal:
                      BorderSide(color: primary.withValues(alpha: 0.12)),
                ),
              ),
            ),
          ),
        ),
        Row(
          children: [
            Expanded(
              flex: 4,
              child: _buildWheel(
                controller: widget.yearController,
                count: yearCount,
                isSelected: (index) =>
                    selectedYear == widget.minDate.year + index,
                labelBuilder: (index) => '${widget.minDate.year + index} 年',
                onSelected: (index) => _selectDate(
                  year: widget.minDate.year + index,
                ),
              ),
            ),
            Expanded(
              flex: 3,
              child: _buildWheel(
                controller: widget.monthController,
                count: 12,
                isSelected: (index) => selectedMonth == index + 1,
                labelBuilder: (index) =>
                    '${(index + 1).toString().padLeft(2, '0')} 月',
                onSelected: (index) => _selectDate(month: index + 1),
              ),
            ),
            Expanded(
              flex: 3,
              child: _buildWheel(
                controller: widget.dayController,
                count: _daysInMonth(selectedYear, selectedMonth),
                isSelected: (index) => selectedDay == index + 1,
                labelBuilder: (index) =>
                    '${(index + 1).toString().padLeft(2, '0')} 日',
                onSelected: (index) => _selectDate(day: index + 1),
              ),
            ),
          ],
        ),
        const IgnorePointer(
          child: Column(
            children: [
              _WheelFade(top: true),
              Spacer(),
              _WheelFade(top: false),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildWheel({
    required FixedExtentScrollController controller,
    required int count,
    required bool Function(int index) isSelected,
    required String Function(int index) labelBuilder,
    required ValueChanged<int> onSelected,
  }) {
    return CupertinoPicker.builder(
      scrollController: controller,
      itemExtent: _itemExtent,
      diameterRatio: 1.35,
      squeeze: 1.08,
      magnification: 1.02,
      useMagnifier: true,
      backgroundColor: Colors.transparent,
      selectionOverlay: const SizedBox.shrink(),
      changeReportingBehavior: ChangeReportingBehavior.onScrollEnd,
      childCount: count,
      onSelectedItemChanged: onSelected,
      itemBuilder: (context, index) {
        final text = labelBuilder(index);
        final active = isSelected(index);
        return Center(
          child: Text(
            text,
            maxLines: 1,
            style: TextStyle(
              color: active ? const Color(0xff0f172a) : const Color(0xff64748b),
              fontSize: active ? 19 : 17,
              fontWeight: active ? FontWeight.w900 : FontWeight.w600,
              letterSpacing: 0,
            ),
          ),
        );
      },
    );
  }

  void _syncFromWidget() {
    final date = _clampDate(widget.date);
    selectedYear = date.year;
    selectedMonth = date.month;
    selectedDay = date.day;
  }

  void _selectDate({int? year, int? month, int? day}) {
    final nextYear = year ?? selectedYear;
    final nextMonth = month ?? selectedMonth;
    final maxDay = _daysInMonth(nextYear, nextMonth);
    final nextDay = (day ?? selectedDay).clamp(1, maxDay);
    final next = _clampDate(DateTime(nextYear, nextMonth, nextDay));
    setState(() {
      selectedYear = next.year;
      selectedMonth = next.month;
      selectedDay = next.day;
    });
    _syncDayControllerIfNeeded(next.day);
    widget.onChanged(next);
  }

  DateTime _clampDate(DateTime value) {
    final normalized = DateTime(value.year, value.month, value.day);
    if (normalized.isBefore(widget.minDate)) return widget.minDate;
    if (normalized.isAfter(widget.maxDate)) return widget.maxDate;
    return normalized;
  }

  void _syncDayControllerIfNeeded(int day) {
    if (!widget.dayController.hasClients) return;
    final currentIndex = widget.dayController.selectedItem;
    final targetIndex = day - 1;
    if (currentIndex == targetIndex) return;
    widget.dayController.animateToItem(
      targetIndex,
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeOutCubic,
    );
  }

  int _daysInMonth(int year, int month) {
    return DateTime(year, month + 1, 0).day;
  }
}

class _WheelFade extends StatelessWidget {
  final bool top;

  const _WheelFade({required this.top});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 36,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: top ? Alignment.topCenter : Alignment.bottomCenter,
          end: top ? Alignment.bottomCenter : Alignment.topCenter,
          colors: const [
            Colors.white,
            Color(0x00ffffff),
          ],
        ),
      ),
    );
  }
}

class _DateRangeEndpoint extends StatelessWidget {
  final String label;
  final DateTime date;
  final bool selected;
  final VoidCallback onTap;

  const _DateRangeEndpoint({
    required this.label,
    required this.date,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = selected ? const Color(0xff184f43) : const Color(0xff64748b);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: selected ? color : Colors.transparent,
          borderRadius: BorderRadius.circular(14),
          boxShadow: selected
              ? [
                  BoxShadow(
                    color: color.withValues(alpha: 0.18),
                    blurRadius: 12,
                    offset: const Offset(0, 5),
                  ),
                ]
              : null,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    color: selected ? Colors.white : color,
                    fontWeight: FontWeight.w800,
                  ),
            ),
            const SizedBox(height: 4),
            Text(
              _formatShortDate(date),
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    color: selected ? Colors.white : const Color(0xff0f172a),
                    fontWeight: FontWeight.w900,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DateRangeResult {
  final DateTime startDate;
  final DateTime endDate;

  const _DateRangeResult(this.startDate, this.endDate);
}

String _formatShortDate(DateTime value) {
  final month = value.month.toString().padLeft(2, '0');
  final day = value.day.toString().padLeft(2, '0');
  return '${value.year}-$month-$day';
}

class _HistoryOrderCard extends StatelessWidget {
  final dynamic order;
  final VoidCallback onTap;

  const _HistoryOrderCard({
    required this.order,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final items = (order?['items'] as List? ?? []).cast<Map<String, dynamic>>();
    final firstItems = items.take(3).toList();
    final extraCount = items.length - firstItems.length;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(24),
        child: Container(
          margin: const EdgeInsets.only(bottom: 12),
          padding: const EdgeInsets.all(18),
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
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '${LocaleUtils.get('table_no')} ${order['tableNo']}',
                      style: Theme.of(context)
                          .textTheme
                          .titleLarge
                          ?.copyWith(fontWeight: FontWeight.w800),
                    ),
                  ),
                  const _StatusPill(status: 'PAID'),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                '${LocaleUtils.get('order_label')} ${order['id']} · ${_peopleText(order['people'])}',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
              const SizedBox(height: 12),
              Text(
                'RM ${order['totalAmount']}',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w900,
                      color: const Color(0xff184f43),
                    ),
              ),
              const SizedBox(height: 10),
              ...firstItems.map(
                (item) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(
                    children: [
                      _HistoryDishThumb(
                        imageUrl: AssetUtils.imageUrl(item['imageUrl']),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '${item['dishNameZh'] ?? item['dishNameEn'] ?? item['dishNameMs']} x ${item['quantity']}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              if (extraCount > 0)
                Text(
                  '+$extraCount ${LocaleUtils.get('history_more_items')}',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      _formatDate(order['updatedAt']),
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color:
                                Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    ),
                  ),
                  Icon(Icons.chevron_right_rounded,
                      color: Colors.black.withValues(alpha: 0.35)),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _formatDate(dynamic value) {
    final text = value?.toString() ?? '';
    if (text.isEmpty) return '-';
    return text.replaceFirst('T', ' ');
  }

  String _peopleText(dynamic value) {
    final count = value is num ? value.toInt() : int.tryParse('${value ?? ''}');
    if (count == null || count <= 0) return '-';
    return '$count ${LocaleUtils.get('order_guest')}';
  }
}

class _HistoryDishThumb extends StatelessWidget {
  final String imageUrl;

  const _HistoryDishThumb({required this.imageUrl});

  @override
  Widget build(BuildContext context) {
    return Container(
      clipBehavior: Clip.antiAlias,
      width: 28,
      height: 28,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(9),
        gradient: const LinearGradient(
          colors: [Color(0xfff1d7ab), Color(0xffd8896c)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: imageUrl.isEmpty
          ? const Icon(Icons.restaurant_menu_rounded,
              color: Colors.white, size: 16)
          : Image.network(
              imageUrl,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => const Icon(
                Icons.restaurant_menu_rounded,
                color: Colors.white,
                size: 16,
              ),
            ),
    );
  }
}

class _PeopleDialog extends StatefulWidget {
  const _PeopleDialog();

  @override
  State<_PeopleDialog> createState() => _PeopleDialogState();
}

class _PeopleDialogState extends State<_PeopleDialog> {
  int people = 2;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
      title: Text(LocaleUtils.get('order_guest_count')),
      content: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          IconButton(
            onPressed: () => setState(() => people = (people - 1).clamp(1, 99)),
            icon: const Icon(Icons.remove),
          ),
          Text('$people', style: Theme.of(context).textTheme.headlineMedium),
          IconButton(
            onPressed: () => setState(() => people++),
            icon: const Icon(Icons.add),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(LocaleUtils.get('common_cancel'))),
        FilledButton(
            onPressed: () => Navigator.pop(context, people),
            child: Text(LocaleUtils.get('common_confirm'))),
      ],
    );
  }
}

class _StatusPill extends StatelessWidget {
  final String status;

  const _StatusPill({required this.status});

  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      'EMPTY' => const Color(0xff1f8f67),
      'DINING' => const Color(0xffd48a1d),
      'PENDING_CHECKOUT' => const Color(0xffc65d3f),
      'CLEANING' => const Color(0xff64748b),
      'PAID' => const Color(0xff1f8f67),
      _ => const Color(0xff64748b),
    };
    final label = switch (status) {
      'EMPTY' => LocaleUtils.get('table_available'),
      'RESERVED' => LocaleUtils.get('table_reserved'),
      'DINING' => LocaleUtils.get('table_dining'),
      'PENDING_CHECKOUT' => LocaleUtils.get('table_checkout'),
      'CLEANING' => LocaleUtils.get('table_cleaning'),
      'PAID' => LocaleUtils.get('order_status_paid'),
      _ => status,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: color,
              fontWeight: FontWeight.w700,
            ),
      ),
    );
  }
}
