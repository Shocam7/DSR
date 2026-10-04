// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// BackgroundEffectPanel — M3-T5 background color/blur effect UI.
// Displayed as a bottom sheet on RecordScreen when the user taps the "BG" button.

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// State for the background effect configuration.
class BackgroundEffectState {
  const BackgroundEffectState({
    this.enabled = false,
    this.useBlur = false,
    this.color = Colors.black,
    this.blurRadius = 3,
  });

  final bool enabled;
  final bool useBlur;
  final Color color;
  final int blurRadius;

  BackgroundEffectState copyWith({
    bool? enabled,
    bool? useBlur,
    Color? color,
    int? blurRadius,
  }) {
    return BackgroundEffectState(
      enabled: enabled ?? this.enabled,
      useBlur: useBlur ?? this.useBlur,
      color: color ?? this.color,
      blurRadius: blurRadius ?? this.blurRadius,
    );
  }
}

class BackgroundEffectNotifier
    extends StateNotifier<BackgroundEffectState> {
  BackgroundEffectNotifier() : super(const BackgroundEffectState());

  void setEnabled(bool v) => state = state.copyWith(enabled: v);
  void setUseBlur(bool v) => state = state.copyWith(useBlur: v);
  void setColor(Color c) => state = state.copyWith(color: c);
  void setBlurRadius(int r) => state = state.copyWith(blurRadius: r);
}

final backgroundEffectProvider =
    StateNotifierProvider<BackgroundEffectNotifier, BackgroundEffectState>(
  (ref) => BackgroundEffectNotifier(),
);

/// Bottom-sheet panel for background effect controls.
class BackgroundEffectPanel extends ConsumerWidget {
  const BackgroundEffectPanel({required this.onChanged, super.key});

  /// Called whenever any setting changes, so RecordController can relay to engine.
  final void Function(BackgroundEffectState state) onChanged;

  // Preset solid background colors
  static const _colorPresets = <Color>[
    Colors.black,
    Colors.white,
    Color(0xFF1A1A2E), // Deep navy
    Color(0xFF16213E), // Dark blue
    Color(0xFF0F3460), // Royal blue
    Color(0xFF533483), // Purple
    Color(0xFF00B4D8), // Cyan
    Color(0xFF90E0EF), // Light blue
    Color(0xFF023E8A), // Deep ocean
    Color(0xFF2D6A4F), // Forest green
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return _buildContent(context, ref);
  }

  Widget _buildContent(BuildContext context, WidgetRef ref) {
    final state = ref.watch(backgroundEffectProvider);
    final notifier = ref.read(backgroundEffectProvider.notifier);

    void update(BackgroundEffectState s) {
      onChanged(s);
    }

    return Container(
      decoration: const BoxDecoration(
        color: Colors.black87,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Handle bar
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.white38,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 16),

          // Enable toggle
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                'Background Effect',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                ),
              ),
              Switch(
                value: state.enabled,
                activeThumbColor: Colors.cyanAccent,
                onChanged: (v) {
                  notifier.setEnabled(v);
                  update(state.copyWith(enabled: v));
                },
              ),
            ],
          ),


          if (state.enabled) ...[
            const SizedBox(height: 12),

            // Blur / Color toggle
            Row(
              children: [
                _ModeChip(
                  label: 'Color',
                  selected: !state.useBlur,
                  onTap: () {
                    notifier.setUseBlur(false);
                    update(state.copyWith(useBlur: false));
                  },
                ),
                const SizedBox(width: 8),
                _ModeChip(
                  label: 'Blur',
                  selected: state.useBlur,
                  onTap: () {
                    notifier.setUseBlur(true);
                    update(state.copyWith(useBlur: true));
                  },
                ),
              ],
            ),
            const SizedBox(height: 16),

            if (!state.useBlur) ...[
              const Text(
                'Background Color',
                style: TextStyle(color: Colors.white70, fontSize: 13),
              ),
              const SizedBox(height: 8),
              SizedBox(
                height: 44,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: _colorPresets.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 8),
                  itemBuilder: (_, i) {
                    final c = _colorPresets[i];
                    final selected = state.color.toARGB32() == c.toARGB32();
                    return GestureDetector(
                      onTap: () {
                        notifier.setColor(c);
                        update(state.copyWith(color: c));
                      },
                      child: Container(
                        width: 44,
                        height: 44,
                        decoration: BoxDecoration(
                          color: c,
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: selected
                                ? Colors.cyanAccent
                                : Colors.white24,
                            width: selected ? 2.5 : 1,
                          ),
                        ),
                        child: selected
                            ? const Icon(Icons.check,
                                color: Colors.white, size: 20)
                            : null,
                      ),
                    );
                  },
                ),
              ),
            ] else ...[
              // Blur radius slider
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Blur Strength',
                    style: TextStyle(color: Colors.white70, fontSize: 13),
                  ),
                  Text(
                    '${state.blurRadius}',
                    style: const TextStyle(
                      color: Colors.cyanAccent,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ],
              ),
              Slider(
                value: state.blurRadius.toDouble(),
                min: 1,
                max: 8,
                divisions: 7,
                activeColor: Colors.cyanAccent,
                inactiveColor: Colors.white24,
                onChanged: (v) {
                  final r = v.round();
                  notifier.setBlurRadius(r);
                  update(state.copyWith(blurRadius: r));
                },
              ),
            ],
          ],
          const SizedBox(height: 8),
        ],
      ),
    );
  }
}

class _ModeChip extends StatelessWidget {
  const _ModeChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        decoration: BoxDecoration(
          color: selected ? Colors.cyanAccent : Colors.white12,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? Colors.black : Colors.white70,
            fontWeight:
                selected ? FontWeight.bold : FontWeight.normal,
            fontSize: 13,
          ),
        ),
      ),
    );
  }
}
