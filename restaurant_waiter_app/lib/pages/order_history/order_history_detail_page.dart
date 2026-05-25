import 'package:flutter/material.dart';

import '../../utils/asset_utils.dart';
import '../../utils/locale_utils.dart';
import '../../widgets/app_shell.dart';

class OrderHistoryDetailPage extends StatelessWidget {
  final Map<String, dynamic> order;

  const OrderHistoryDetailPage({super.key, required this.order});

  @override
  Widget build(BuildContext context) {
    final items = (order['items'] as List? ?? []).cast<Map<String, dynamic>>();
    final people = _peopleText(order['people']);
    final createdAt = _formatDate(order['createdAt']);
    final updatedAt = _formatDate(order['updatedAt']);

    return AppShell(
      title: '${LocaleUtils.get('history_order_detail')} #${order['id']}',
      subtitle:
          '${LocaleUtils.get('table_no')} ${order['tableNo']} • ${LocaleUtils.get('order_status_paid')}',
      child: Expanded(
        child: ListView(
          padding: EdgeInsets.zero,
          children: [
            _SummaryCard(
              order: order,
              people: people,
              createdAt: createdAt,
              updatedAt: updatedAt,
            ),
            const SizedBox(height: 16),
            _SectionCard(
              title: LocaleUtils.get('table_order_items'),
              child: items.isEmpty
                  ? Text(
                      LocaleUtils.get('table_no_order'),
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            color:
                                Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    )
                  : Column(
                      children: items
                          .map(
                            (item) => _HistoryOrderItem(item: item),
                          )
                          .toList(),
                    ),
            ),
            if ((order['remark'] ?? '').toString().trim().isNotEmpty) ...[
              const SizedBox(height: 16),
              _SectionCard(
                title: LocaleUtils.get('order_note'),
                child: Text(
                  order['remark'],
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ),
            ],
          ],
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

class _SummaryCard extends StatelessWidget {
  final Map<String, dynamic> order;
  final String people;
  final String createdAt;
  final String updatedAt;

  const _SummaryCard({
    required this.order,
    required this.people,
    required this.createdAt,
    required this.updatedAt,
  });

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
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            LocaleUtils.get('field_total_amount'),
            style: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(color: Colors.white70),
          ),
          const SizedBox(height: 8),
          Text(
            'RM ${order['totalAmount']}',
            style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                  color: Colors.white,
                  fontWeight: FontWeight.w900,
                ),
          ),
          const SizedBox(height: 18),
          _InfoRow(
              label: LocaleUtils.get('table_no'), value: '${order['tableNo']}'),
          _InfoRow(label: LocaleUtils.get('order_guest_count'), value: people),
          _InfoRow(
              label: LocaleUtils.get('field_created_at'), value: createdAt),
          _InfoRow(label: LocaleUtils.get('history_paid_at'), value: updatedAt),
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;

  const _InfoRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: Theme.of(context)
                  .textTheme
                  .bodyMedium
                  ?.copyWith(color: Colors.white70),
            ),
          ),
          Text(
            value,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Colors.white,
                  fontWeight: FontWeight.w700,
                ),
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
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }
}

class _HistoryOrderItem extends StatelessWidget {
  final Map<String, dynamic> item;

  const _HistoryOrderItem({required this.item});

  @override
  Widget build(BuildContext context) {
    final quantity = item['quantity'] ?? 0;
    final unitPrice = double.tryParse('${item['unitPrice']}') ?? 0;
    final subtotal = unitPrice * (quantity is num ? quantity : 0);
    final imageUrl = AssetUtils.imageUrl(item['imageUrl']);

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xfff7faf8),
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
                  '${item['dishNameZh'] ?? item['dishNameEn'] ?? item['dishNameMs']} x $quantity',
                  style: Theme.of(context)
                      .textTheme
                      .titleMedium
                      ?.copyWith(fontWeight: FontWeight.w800),
                ),
                if ((item['remark'] ?? '').toString().trim().isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    item['remark'],
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                  ),
                ],
              ],
            ),
          ),
          Text(
            'RM ${subtotal.toStringAsFixed(2)}',
            style: Theme.of(context)
                .textTheme
                .titleSmall
                ?.copyWith(fontWeight: FontWeight.w800),
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
