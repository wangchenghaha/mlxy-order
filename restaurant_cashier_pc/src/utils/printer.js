export function formatReceipt(order, paymentMethod, referenceNo) {
  const lines = [
    'Receipt / Resit / 小票',
    `Order: ${order.id}  Table: ${order.tableNo}`,
    '--------------------------------',
    ...order.items.flatMap((item) => [
      `${item.dishNameEn} / ${item.dishNameMs}`,
      `${item.quantity} x RM ${item.unitPrice} = RM ${item.subtotal || (item.quantity * item.unitPrice).toFixed(2)}`,
    ]),
    '--------------------------------',
    `Total RM ${order.totalAmount}`,
    `Payment: ${paymentMethod}`,
    `Ref: ${referenceNo || '-'}`,
    'Thank you / Terima kasih / 谢谢',
  ]
  return lines.join('\n')
}
