import 'package:flutter/material.dart';

import '../utils/asset_utils.dart';

class DishItem extends StatelessWidget {
  final dynamic dish;
  final int quantity;
  final VoidCallback? onAdd;

  const DishItem(
      {super.key,
      required this.dish,
      required this.quantity,
      required this.onAdd});

  @override
  Widget build(BuildContext context) {
    final title = dish['nameEn'] ?? dish['nameMs'] ?? dish['nameZh'] ?? 'Dish';
    final imageUrl = AssetUtils.imageUrl(dish['imageUrl']);
    final subtitle = [dish['nameZh'], dish['nameMs']]
        .where((item) =>
            item != null && item.toString().isNotEmpty && item != title)
        .join(' · ');

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.96),
        borderRadius: BorderRadius.circular(22),
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
        child: Row(
          children: [
            Container(
              clipBehavior: Clip.antiAlias,
              width: 58,
              height: 58,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(18),
                gradient: const LinearGradient(
                  colors: [Color(0xfff1d7ab), Color(0xffd8896c)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
              ),
              child: imageUrl.isEmpty
                  ? const Icon(Icons.restaurant_menu_rounded,
                      color: Colors.white)
                  : Image.network(
                      imageUrl,
                      fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const Icon(
                        Icons.restaurant_menu_rounded,
                        color: Colors.white,
                      ),
                    ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w800,
                        ),
                  ),
                  if (subtitle.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      subtitle,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color:
                                Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    ),
                  ],
                  const SizedBox(height: 10),
                  Text(
                    'RM ${dish['price']}',
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          color: Theme.of(context).colorScheme.primary,
                          fontWeight: FontWeight.w800,
                        ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Column(
              children: [
                Text(
                  '$quantity',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w800,
                      ),
                ),
                const SizedBox(height: 8),
                FilledButton.tonal(
                  onPressed: onAdd,
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.all(10),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16)),
                  ),
                  child: const Icon(Icons.add_rounded, size: 20),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
