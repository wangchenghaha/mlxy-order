class RealtimeConnection {
  final void Function() _cancel;

  RealtimeConnection(this._cancel);

  void cancel() => _cancel();
}
