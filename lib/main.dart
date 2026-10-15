import 'package:flutter/widgets.dart';

import 'src/app.dart';
import 'src/platform/monitor_gateway.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(SleepMonitorApp(gateway: createMonitorGateway()));
}
