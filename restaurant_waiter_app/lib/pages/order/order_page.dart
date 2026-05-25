import 'package:flutter/material.dart';

import '../../api/api_service.dart';
import '../../api/order_api.dart';
import '../../utils/hive_utils.dart';
import '../../utils/locale_utils.dart';
import '../../widgets/app_shell.dart';
import '../../widgets/common_button.dart';
import '../../widgets/dish_item.dart';
import '../../widgets/top_toast.dart';

class OrderPage extends StatefulWidget {
  final int tableId;
  final String? tableNo;
  final int people;
  final Map<String, dynamic>? existingOrder;

  const OrderPage({
    super.key,
    required this.tableId,
    this.tableNo,
    required this.people,
    this.existingOrder,
  });

  @override
  State<OrderPage> createState() => _OrderPageState();
}

class _OrderPageState extends State<OrderPage> {
  List<dynamic> categories = [];
  List<dynamic> dishes = [];
  final Map<int, Map<String, dynamic>> cart = {};
  final remark = TextEditingController();
  String selectedCategoryId = 'ALL';
  bool loading = true;
  bool submitting = false;

  @override
  void initState() {
    super.initState();
    seedExistingOrder();
    load();
  }

  @override
  void dispose() {
    remark.dispose();
    super.dispose();
  }

  void seedExistingOrder() {
    if (widget.existingOrder == null) return;
    remark.text = widget.existingOrder?['remark'] ?? '';
    for (final item in (widget.existingOrder?['items'] as List? ?? [])) {
      cart[item['dishId']] = {
        'dishId': item['dishId'],
        'quantity': item['quantity'],
        'remark': item['remark'] ?? '',
      };
    }
  }

  Future<void> load() async {
    try {
      final cachedDishes = HiveUtils.cachedDishes;
      final cachedCategories = HiveUtils.cachedCategories;
      if (cachedDishes.isNotEmpty && mounted) {
        setState(() {
          categories = cachedCategories;
          dishes = cachedDishes;
          loading = false;
        });
      }
      final results =
          await Future.wait([OrderApi.categories(), OrderApi.dishes()]);
      final nextCategories = results[0];
      final nextDishes = results[1];
      HiveUtils.cachedCategories = nextCategories;
      HiveUtils.cachedDishes = nextDishes;
      if (mounted) {
        setState(() {
          categories = nextCategories;
          dishes = nextDishes;
          loading = false;
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() => loading = false);
        showTopToast(context, ApiService.friendlyError(error));
      }
    }
  }

  void add(dynamic dish) {
    final id = dish['id'] as int;
    final current = cart[id] ?? {'dishId': id, 'quantity': 0, 'remark': ''};
    current['quantity'] = current['quantity'] + 1;
    cart[id] = current;
    setState(() {});
  }

  void remove(dynamic dish) {
    final id = dish['id'] as int;
    final current = cart[id];
    if (current == null) return;
    current['quantity'] = current['quantity'] - 1;
    if (current['quantity'] <= 0) {
      cart.remove(id);
    } else {
      cart[id] = current;
    }
    setState(() {});
  }

  Future<void> submit() async {
    if (cart.isEmpty || submitting) return;
    setState(() => submitting = true);
    final body = {
      'tableId': widget.tableId,
      'people': widget.people,
      'remark': remark.text,
      'items': cart.values.toList(),
    };
    final key = ApiService.nextIdempotencyKey(widget.existingOrder != null
        ? 'waiter-modify-order'
        : 'waiter-submit-order');
    try {
      if (widget.existingOrder != null) {
        await OrderApi.modify(widget.existingOrder!['id'], body,
            idempotencyKey: key);
      } else {
        await OrderApi.submit(body, idempotencyKey: key);
      }
      if (!mounted) return;
      Navigator.pop(context, true);
    } catch (error) {
      if (mounted) showTopToast(context, ApiService.friendlyError(error));
    } finally {
      if (mounted) setState(() => submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final visibleDishes = selectedCategoryId == 'ALL'
        ? dishes
        : dishes
            .where((dish) => '${dish['categoryId']}' == selectedCategoryId)
            .toList();
    final total = dishes.fold<double>(0, (sum, dish) {
      final line = cart[dish['id']];
      return sum +
          ((line?['quantity'] ?? 0) * double.parse('${dish['price']}'));
    });

    return AppShell(
      title:
          '${LocaleUtils.get('table_current_table')} ${widget.tableNo ?? widget.tableId}',
      subtitle:
          '${widget.people} ${LocaleUtils.get('order_guest')} • ${LocaleUtils.get('order_note')}',
      bottomBar: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  LocaleUtils.get('order_current_total'),
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
                const SizedBox(height: 4),
                Text(
                  'RM ${total.toStringAsFixed(2)}',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w900,
                      ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: CommonButton(
              text: submitting ? '...' : LocaleUtils.get('order_submit'),
              icon: Icons.send_rounded,
              onPressed: submitting || cart.isEmpty ? null : submit,
            ),
          ),
        ],
      ),
      child: Expanded(
        child: loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: EdgeInsets.zero,
                children: [
                  Container(
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
                          LocaleUtils.get('order_note'),
                          style:
                              Theme.of(context).textTheme.titleMedium?.copyWith(
                                    fontWeight: FontWeight.w800,
                                  ),
                        ),
                        const SizedBox(height: 10),
                        TextField(
                          controller: remark,
                          maxLines: 3,
                          decoration: InputDecoration(
                            labelText: LocaleUtils.get('order_kitchen_note'),
                            alignLabelWithHint: true,
                            prefixIcon: const Icon(Icons.edit_note_rounded),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  _CategorySelector(
                    categories: categories,
                    selectedId: selectedCategoryId,
                    onSelected: (value) =>
                        setState(() => selectedCategoryId = value),
                  ),
                  const SizedBox(height: 12),
                  ...visibleDishes.map(
                    (dish) => _EditableDishItem(
                      dish: dish,
                      quantity: cart[dish['id']]?['quantity'] ?? 0,
                      onAdd: submitting ? null : () => add(dish),
                      onRemove: submitting ? null : () => remove(dish),
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class _CategorySelector extends StatelessWidget {
  final List<dynamic> categories;
  final String selectedId;
  final ValueChanged<String> onSelected;

  const _CategorySelector({
    required this.categories,
    required this.selectedId,
    required this.onSelected,
  });

  String categoryName(dynamic category) {
    final lang = HiveUtils.lang;
    if (lang == 'zh_cn') {
      return category['nameZh'] ??
          category['nameEn'] ??
          category['nameMs'] ??
          '';
    }
    if (lang == 'ms_my') {
      return category['nameMs'] ??
          category['nameEn'] ??
          category['nameZh'] ??
          '';
    }
    return category['nameEn'] ?? category['nameZh'] ?? category['nameMs'] ?? '';
  }

  @override
  Widget build(BuildContext context) {
    final chips = [
      {'id': 'ALL', 'name': LocaleUtils.get('order_category_all')},
      ...categories.map((category) => {
            'id': '${category['id']}',
            'name': categoryName(category),
          }),
    ];
    return SizedBox(
      height: 42,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemBuilder: (context, index) {
          final chip = chips[index];
          final selected = chip['id'] == selectedId;
          return ChoiceChip(
            selected: selected,
            label: Text(chip['name'] ?? ''),
            onSelected: (_) => onSelected(chip['id'] ?? 'ALL'),
            labelStyle: TextStyle(
              color: selected
                  ? Theme.of(context).colorScheme.onPrimary
                  : Theme.of(context).colorScheme.onSurface,
              fontWeight: FontWeight.w800,
            ),
            selectedColor: Theme.of(context).colorScheme.primary,
            backgroundColor: Colors.white.withValues(alpha: 0.9),
            side: BorderSide(
              color: selected
                  ? Theme.of(context).colorScheme.primary
                  : Colors.black.withValues(alpha: 0.06),
            ),
            shape:
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          );
        },
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemCount: chips.length,
      ),
    );
  }
}

class _EditableDishItem extends StatelessWidget {
  final dynamic dish;
  final int quantity;
  final VoidCallback? onAdd;
  final VoidCallback? onRemove;

  const _EditableDishItem({
    required this.dish,
    required this.quantity,
    required this.onAdd,
    required this.onRemove,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(16),
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
      child: Row(
        children: [
          Expanded(
            child: DishItem(dish: dish, quantity: quantity, onAdd: onAdd),
          ),
          const SizedBox(width: 12),
          if (quantity > 0)
            FilledButton.tonal(
              onPressed: onRemove,
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.all(10),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16)),
              ),
              child: const Icon(Icons.remove_rounded, size: 20),
            ),
        ],
      ),
    );
  }
}
