import 'package:flutter/material.dart';

import '../utils/locale_utils.dart';

class TableItem extends StatelessWidget {
  final dynamic table;
  final VoidCallback? onTap;

  const TableItem({super.key, required this.table, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final status = table['status'] as String? ?? 'EMPTY';
    final accent = switch (status) {
      'EMPTY' => const Color(0xff1f8f67),
      'RESERVED' => const Color(0xffd48a1d),
      'DINING' => const Color(0xffd48a1d),
      'PENDING_CHECKOUT' => const Color(0xffd05b42),
      _ => const Color(0xff6b7280),
    };
    final background = switch (status) {
      'EMPTY' => const Color(0xffebf8f1),
      'RESERVED' => const Color(0xfffff6de),
      'DINING' => const Color(0xfffff6de),
      'PENDING_CHECKOUT' => const Color(0xffffebe4),
      _ => const Color(0xffeef2f7),
    };
    final statusText = switch (status) {
      'EMPTY' => LocaleUtils.get('table_available'),
      'RESERVED' => LocaleUtils.get('table_reserved'),
      'DINING' => LocaleUtils.get('table_dining'),
      'PENDING_CHECKOUT' => LocaleUtils.get('table_checkout'),
      'CLEANING' => LocaleUtils.get('table_cleaning'),
      _ => status,
    };

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(24),
        child: Ink(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(24),
            color: Colors.white.withValues(alpha: 0.95),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.05),
                blurRadius: 18,
                offset: const Offset(0, 8),
              ),
            ],
          ),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(
                        color: background,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        statusText,
                        style: Theme.of(context).textTheme.labelLarge?.copyWith(
                              color: accent,
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                    ),
                    const Spacer(),
                    Icon(Icons.chevron_right_rounded,
                        color: Colors.black.withValues(alpha: 0.35)),
                  ],
                ),
                const SizedBox(height: 16),
                Text(
                  table['tableNo'],
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w800,
                      ),
                ),
                const SizedBox(height: 6),
                Text(
                  table['area'],
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
                const Spacer(),
                Row(
                  children: [
                    Expanded(
                      child: _InfoChip(
                        icon: Icons.group_outlined,
                        label:
                            '${table['currentPeople']}/${table['maxPeople']}',
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _InfoChip(
                        icon: status == 'EMPTY' || status == 'RESERVED'
                            ? Icons.add_rounded
                            : Icons.receipt_long_outlined,
                        label: status == 'EMPTY' || status == 'RESERVED'
                            ? LocaleUtils.get('table_open')
                            : LocaleUtils.get('table_in_service'),
                        accent: accent,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _InfoChip extends StatelessWidget {
  final Color? accent;
  final IconData icon;
  final String label;

  const _InfoChip({
    required this.icon,
    required this.label,
    this.accent,
  });

  @override
  Widget build(BuildContext context) {
    final color = accent ?? Theme.of(context).colorScheme.primary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    color: color,
                    fontWeight: FontWeight.w700,
                  ),
            ),
          ),
        ],
      ),
    );
  }
}
