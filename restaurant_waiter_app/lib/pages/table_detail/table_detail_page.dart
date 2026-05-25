import 'dart:async';

import 'package:flutter/material.dart';

import '../../api/order_api.dart';
import '../../api/realtime_connection.dart';
import '../../api/realtime_api.dart';
import '../../api/table_api.dart';
import '../../api/api_service.dart';
import '../../utils/asset_utils.dart';
import '../../utils/locale_utils.dart';
import '../../utils/network_utils.dart';
import '../../widgets/app_shell.dart';
import '../../widgets/common_button.dart';
import '../../widgets/top_toast.dart';
import '../order/order_page.dart';

class TableDetailPage extends StatefulWidget {
  final dynamic table;
  final Future<void> Function() onRefresh;

  const TableDetailPage(
      {super.key, required this.table, required this.onRefresh});

  @override
  State<TableDetailPage> createState() => _TableDetailPageState();
}

class _TableDetailPageState extends State<TableDetailPage> {
  late Map<String, dynamic> table;
  Map<String, dynamic>? currentOrder;
  bool loading = true;
  bool offline = false;
  bool actionLoading = false;
  RealtimeConnection? realtimeSubscription;
  Timer? realtimeReloadTimer;
  Timer? realtimeReconnectTimer;
  StreamSubscription<bool>? networkSubscription;
  int reconnectAttempt = 0;

  String statusLabelFor(String status) => switch (status) {
        'EMPTY' => LocaleUtils.get('table_available'),
        'RESERVED' => LocaleUtils.get('table_reserved'),
        'DINING' => LocaleUtils.get('table_dining'),
        'PENDING_CHECKOUT' => LocaleUtils.get('table_checkout'),
        'CLEANING' => LocaleUtils.get('table_cleaning'),
        _ => status,
      };

  @override
  void initState() {
    super.initState();
    table = Map<String, dynamic>.from(widget.table as Map);
    watchNetwork();
    load();
    connectRealtime();
  }

  @override
  void dispose() {
    realtimeSubscription?.cancel();
    realtimeReloadTimer?.cancel();
    realtimeReconnectTimer?.cancel();
    networkSubscription?.cancel();
    super.dispose();
  }

  Future<void> load() async {
    if (mounted) setState(() => loading = true);
    try {
      final tables = await TableApi.list();
      final nextTable = tables.cast<Map>().firstWhere(
            (item) => item['id'] == table['id'],
            orElse: () => table,
          );
      final nextTableMap = Map<String, dynamic>.from(nextTable);
      final nextOrder = await OrderApi.currentOrder(nextTableMap['id']);
      if (mounted) {
        setState(() {
          table = nextTableMap;
          currentOrder =
              nextOrder == null ? null : Map<String, dynamic>.from(nextOrder);
          offline = false;
        });
      }
    } catch (error) {
      final connected = await NetworkUtils.hasConnection;
      if (mounted) {
        setState(() => offline = !connected);
        showTopToast(context, ApiService.friendlyError(error));
      }
    } finally {
      if (mounted) setState(() => loading = false);
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

  void scheduleRealtimeLoad() {
    if (realtimeReloadTimer != null) return;
    realtimeReloadTimer = Timer(const Duration(milliseconds: 350), () {
      realtimeReloadTimer = null;
      if (mounted) load();
    });
  }

  void scheduleRealtimeReconnect() {
    realtimeSubscription?.cancel();
    realtimeSubscription = null;
    if (realtimeReconnectTimer != null || !mounted) return;
    reconnectAttempt = (reconnectAttempt + 1).clamp(1, 6);
    realtimeReconnectTimer = Timer(Duration(seconds: reconnectAttempt * 3), () {
      realtimeReconnectTimer = null;
      if (mounted) connectRealtime();
    });
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

  Future<void> openOrder() async {
    final submitted = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => OrderPage(
          tableId: table['id'],
          tableNo: table['tableNo'],
          people: table['currentPeople'] ?? 2,
          existingOrder: currentOrder,
        ),
      ),
    );
    if (mounted && submitted == true) {
      showTopToast(context, LocaleUtils.get('common_success'));
    }
    await widget.onRefresh();
    await load();
  }

  Future<void> transferTable() async {
    if (actionLoading) return;
    final tables = await TableApi.list();
    if (!mounted) return;
    final result = await showDialog<Map<String, int>>(
      context: context,
      builder: (_) => _TransferDialog(
        currentTableId: table['id'],
        currentTableNo: table['tableNo'],
        emptyTables:
            tables.where((table) => table['status'] == 'EMPTY').toList(),
      ),
    );
    if (result == null) return;
    setState(() => actionLoading = true);
    try {
      await TableApi.transfer(result['fromTableId']!, result['toTableId']!);
      await widget.onRefresh();
      await load();
    } catch (error) {
      if (mounted) showTopToast(context, ApiService.friendlyError(error));
    } finally {
      if (mounted) setState(() => actionLoading = false);
    }
  }

  Future<void> cleanComplete() async {
    if (actionLoading) return;
    setState(() => actionLoading = true);
    try {
      await TableApi.cleanComplete(table['id']);
      await widget.onRefresh();
      if (mounted) Navigator.pop(context);
    } catch (error) {
      if (mounted) showTopToast(context, ApiService.friendlyError(error));
    } finally {
      if (mounted) setState(() => actionLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final status = table['status'] as String? ?? 'EMPTY';
    final isDining = status == 'DINING';
    final isCheckout = status == 'PENDING_CHECKOUT';
    final isCleaning = status == 'CLEANING';
    final showActions = isDining || isCleaning;
    final people = table['currentPeople'] ?? 0;
    final maxPeople = table['maxPeople'] ?? 0;
    final orderItems = isDining || isCheckout
        ? (currentOrder?['items'] as List? ?? []).cast<Map<String, dynamic>>()
        : <Map<String, dynamic>>[];

    return AppShell(
      title: table['tableNo'],
      subtitle:
          '${table['area']} • $people/$maxPeople ${LocaleUtils.get('order_guest')}',
      child: Expanded(
        child: loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: EdgeInsets.zero,
                children: [
                  if (offline) ...[
                    _OfflineBanner(onRetry: load),
                    const SizedBox(height: 12),
                  ],
                  Text(
                    LocaleUtils.get('table_current_table'),
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                  const SizedBox(height: 16),
                  _HeaderCard(
                    status: status,
                    people: people,
                    maxPeople: maxPeople,
                  ),
                  const SizedBox(height: 16),
                  _SectionCard(
                    title: LocaleUtils.get('table_current_order'),
                    child: currentOrder == null || orderItems.isEmpty
                        ? Padding(
                            padding: const EdgeInsets.symmetric(vertical: 18),
                            child: Text(
                              LocaleUtils.get('table_no_order'),
                              style: Theme.of(context)
                                  .textTheme
                                  .bodyMedium
                                  ?.copyWith(
                                    color: Theme.of(context)
                                        .colorScheme
                                        .onSurfaceVariant,
                                  ),
                            ),
                          )
                        : Column(
                            children: orderItems
                                .map(
                                  (item) => _OrderDishLine(item: item),
                                )
                                .toList(),
                          ),
                  ),
                  if (showActions) ...[
                    const SizedBox(height: 16),
                    _SectionCard(
                      title: isDining
                          ? LocaleUtils.get('table_add_dishes')
                          : LocaleUtils.get('table_cleaning'),
                      child: Column(
                        children: [
                          if (isDining) ...[
                            CommonButton(
                              text: actionLoading
                                  ? '...'
                                  : LocaleUtils.get('table_transfer_table'),
                              icon: Icons.swap_horiz_rounded,
                              onPressed: actionLoading ? null : transferTable,
                            ),
                            const SizedBox(height: 12),
                            CommonButton(
                                text: LocaleUtils.get('table_add_dishes'),
                                icon: Icons.restaurant_menu_rounded,
                                onPressed: actionLoading ? null : openOrder),
                          ],
                          if (isCleaning)
                            CommonButton(
                              text: actionLoading
                                  ? '...'
                                  : LocaleUtils.get('table_clean_complete'),
                              icon: Icons.check_circle_outline_rounded,
                              onPressed: actionLoading ? null : cleanComplete,
                            ),
                        ],
                      ),
                    ),
                  ],
                ],
              ),
      ),
    );
  }
}

class _OrderDishLine extends StatelessWidget {
  final Map<String, dynamic> item;

  const _OrderDishLine({required this.item});

  @override
  Widget build(BuildContext context) {
    final imageUrl = AssetUtils.imageUrl(item['imageUrl']);
    final quantity = item['quantity'] ?? 0;
    final unitPrice = item['unitPrice'] ?? 0;
    final subtotal = (unitPrice is num ? unitPrice : double.tryParse('$unitPrice') ?? 0) *
        (quantity is num ? quantity : int.tryParse('$quantity') ?? 0);
    final name = item['dishNameEn'] ?? item['dishNameZh'] ?? item['dishNameMs'] ?? '';

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: Colors.black.withValues(alpha: 0.05)),
      ),
      child: Row(
        children: [
          _DishThumb(imageUrl: imageUrl),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '$name × $quantity',
                  style: Theme.of(context)
                      .textTheme
                      .titleMedium
                      ?.copyWith(fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 4),
                Text(
                  'RM ${subtotal.toStringAsFixed(2)}',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
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

class _DishThumb extends StatelessWidget {
  final String imageUrl;

  const _DishThumb({required this.imageUrl});

  @override
  Widget build(BuildContext context) {
    return Container(
      clipBehavior: Clip.antiAlias,
      width: 52,
      height: 52,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        gradient: const LinearGradient(
          colors: [Color(0xfff1d7ab), Color(0xffd8896c)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: imageUrl.isEmpty
          ? const Icon(Icons.restaurant_menu_rounded, color: Colors.white)
          : Image.network(
              imageUrl,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => const Icon(
                Icons.restaurant_menu_rounded,
                color: Colors.white,
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

class _HeaderCard extends StatelessWidget {
  final String status;
  final int people;
  final int maxPeople;

  const _HeaderCard({
    required this.status,
    required this.people,
    required this.maxPeople,
  });

  String _labelOf(String status) => switch (status) {
        'EMPTY' => LocaleUtils.get('table_available'),
        'DINING' => LocaleUtils.get('table_dining'),
        'PENDING_CHECKOUT' => LocaleUtils.get('table_checkout'),
        'CLEANING' => LocaleUtils.get('table_cleaning'),
        _ => status,
      };

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(26),
        gradient: const LinearGradient(
          colors: [Color(0xff184f43), Color(0xff2f7a69)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  LocaleUtils.get('table_current_table'),
                  style: Theme.of(context)
                      .textTheme
                      .bodyMedium
                      ?.copyWith(color: Colors.white70),
                ),
                const SizedBox(height: 8),
                Text(
                  '$people / $maxPeople',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.w900,
                      ),
                ),
                const SizedBox(height: 6),
                Text(
                  _labelOf(status),
                  style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        color: Colors.white.withValues(alpha: 0.88),
                      ),
                ),
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.16),
              borderRadius: BorderRadius.circular(20),
            ),
            child: const Icon(Icons.table_restaurant_rounded,
                color: Colors.white, size: 34),
          ),
        ],
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  final String title;
  final Widget child;

  const _SectionCard({required this.title, required this.child});

  @override
  Widget build(BuildContext context) {
    return Container(
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
          Text(
            title,
            style: Theme.of(context)
                .textTheme
                .titleMedium
                ?.copyWith(fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 14),
          child,
        ],
      ),
    );
  }
}

class _TransferDialog extends StatefulWidget {
  final int currentTableId;
  final String currentTableNo;
  final List<dynamic> emptyTables;

  const _TransferDialog({
    required this.currentTableId,
    required this.currentTableNo,
    required this.emptyTables,
  });

  @override
  State<_TransferDialog> createState() => _TransferDialogState();
}

class _TransferDialogState extends State<_TransferDialog> {
  int? toTableId;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
      title: Text(LocaleUtils.get('table_transfer_dialog_title')),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextFormField(
            initialValue: widget.currentTableNo,
            enabled: false,
            decoration:
                InputDecoration(labelText: LocaleUtils.get('table_from_table')),
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<int>(
            initialValue: toTableId,
            decoration:
                InputDecoration(labelText: LocaleUtils.get('table_to_table')),
            items: widget.emptyTables
                .map((table) => DropdownMenuItem<int>(
                      value: table['id'] as int,
                      child: Text(table['tableNo'] as String),
                    ))
                .toList(),
            onChanged: (v) => setState(() => toTableId = v),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(LocaleUtils.get('common_cancel'))),
        FilledButton(
          onPressed: toTableId == null
              ? null
              : () => Navigator.pop(
                    context,
                    {
                      'fromTableId': widget.currentTableId,
                      'toTableId': toTableId!,
                    },
                  ),
          child: Text(LocaleUtils.get('table_transfer_confirm')),
        ),
      ],
    );
  }
}
