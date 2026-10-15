import 'dart:async';

import 'package:flutter/material.dart';

import 'application/monitor_controller.dart';
import 'platform/monitor_gateway.dart';
import 'ui/monitor_page.dart';
import 'ui/recordings_page.dart';
import 'ui/settings_page.dart';

class SleepMonitorApp extends StatefulWidget {
  const SleepMonitorApp({super.key, required this.gateway});
  final MonitorGateway gateway;

  @override
  State<SleepMonitorApp> createState() => _SleepMonitorAppState();
}

class _SleepMonitorAppState extends State<SleepMonitorApp>
    with WidgetsBindingObserver {
  late final MonitorController _controller;
  int _selectedPage = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _controller = MonitorController(widget.gateway);
    unawaited(_controller.initialize());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_controller.initializing) {
      unawaited(_controller.refreshRecordings());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '夜间记录',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xFF8ED9CD),
        brightness: Brightness.dark,
        surface: const Color(0xFF101722),
      ),
      scaffoldBackgroundColor: const Color(0xFF0B111B),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(),
      ),
      cardTheme: CardThemeData(
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      ),
    ),
    home: AnimatedBuilder(
      animation: _controller,
      builder: (context, _) => Scaffold(
        appBar: AppBar(
          title: const Row(
            children: [
              Icon(Icons.nights_stay_outlined, size: 23),
              SizedBox(width: 10),
              Text('夜间记录'),
            ],
          ),
          actions: const [
            Padding(
              padding: EdgeInsets.only(right: 20),
              child: Chip(
                avatar: Icon(Icons.phonelink_lock_outlined, size: 16),
                label: Text('本地保存'),
              ),
            ),
          ],
        ),
        body: SafeArea(
          child: Column(
            children: [
              if (_controller.error case final String error)
                MaterialBanner(
                  content: Text(error),
                  leading: const Icon(Icons.error_outline),
                  actions: [
                    if (!_controller.capabilities.timedRecording)
                      TextButton(
                        onPressed: _controller.initialize,
                        child: const Text('重试连接'),
                      ),
                    TextButton(
                      onPressed: _controller.dismissError,
                      child: const Text('关闭'),
                    ),
                  ],
                ),
              Expanded(
                child: _controller.initializing
                    ? const Center(child: CircularProgressIndicator())
                    : Center(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 720),
                          child: IndexedStack(
                            index: _selectedPage,
                            children: [
                              MonitorPage(
                                controller: _controller,
                                onSettings: () =>
                                    setState(() => _selectedPage = 2),
                              ),
                              RecordingsPage(controller: _controller),
                              SettingsPage(
                                settings: _controller.settings,
                                enabled:
                                    _controller.canEdit &&
                                    _controller.capabilities.timedRecording,
                                onSave: _controller.saveSettings,
                              ),
                            ],
                          ),
                        ),
                      ),
              ),
            ],
          ),
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _selectedPage,
          onDestinationSelected: (index) =>
              setState(() => _selectedPage = index),
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.nights_stay_outlined),
              selectedIcon: Icon(Icons.nights_stay),
              label: '监控',
            ),
            NavigationDestination(
              icon: Icon(Icons.video_library_outlined),
              selectedIcon: Icon(Icons.video_library),
              label: '片段',
            ),
            NavigationDestination(
              icon: Icon(Icons.tune_outlined),
              selectedIcon: Icon(Icons.tune),
              label: '设置',
            ),
          ],
        ),
      ),
    ),
  );
}
