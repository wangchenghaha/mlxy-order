# restaurant_waiter_app

Flutter 3.22+ waiter APP project. It only handles waiter ordering workflows and never connects to printers directly.

Implemented screens:

- Login
- Table list and open table
- Dish ordering cart
- My orders and checkout request
- Table transfer
- Language switch with backend i18n cache

Run with a Flutter 3.22+ environment:

```bash
flutter pub get
flutter run --dart-define=API_BASE_URL=http://localhost:8080/api
```
