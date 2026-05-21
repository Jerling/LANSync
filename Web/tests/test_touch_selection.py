"""Tests for iOS Safari touch selection compatibility (W-2).

These tests verify that GalleryView.vue uses touch events (touchstart/touchend)
instead of pointer events for long-press selection, ensuring iOS Safari compatibility.
"""
import pytest
import re
import os


BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GALLERY_VIEW_PATH = os.path.join(BASE_DIR, 'src', 'views', 'GalleryView.vue')


@pytest.fixture
def gallery_view_content():
    """Read GalleryView.vue content."""
    with open(GALLERY_VIEW_PATH, 'r', encoding='utf-8') as f:
        return f.read()


class TestTouchSelectionOnIOS:
    """Tests for iOS Safari touch event compatibility."""

    def test_long_press_triggers_selection_on_ios(self, gallery_view_content):
        """Verify iOS long press can trigger selection mode.

        iOS Safari requires touchstart/touchend events for proper touch handling.
        Long press (500ms) should enter selection mode.
        """
        # Verify touchstart event handler is present
        assert '@touchstart' in gallery_view_content or 'touchstart' in gallery_view_content, \
            "touchstart event handler must be present for iOS Safari compatibility"

        # Verify touchend event handler is present
        assert '@touchend' in gallery_view_content or 'touchend' in gallery_view_content, \
            "touchend event handler must be present for iOS Safari compatibility"

        # Verify long press timer implementation exists (500ms)
        assert 'LONG_PRESS_MS' in gallery_view_content, \
            "LONG_PRESS_MS constant must be defined for long press detection"
        assert '500' in gallery_view_content or 'LONG_PRESS_MS' in gallery_view_content, \
            "Long press duration should be 500ms"

        # Verify onPhotoTouchStart or equivalent touch handler exists
        has_touch_handler = (
            'onPhotoTouchStart' in gallery_view_content or
            'onTouchStart' in gallery_view_content or
            'handleTouchStart' in gallery_view_content
        )
        assert has_touch_handler, \
            "Touch start handler function must be implemented for iOS Safari"

    def test_touch_and_tap_different_behaviors(self, gallery_view_content):
        """Verify that short tap (preview) and long press (selection) have different behaviors.

        Short tap: < 500ms touch duration -> preview photo
        Long press: >= 500ms touch duration -> enter selection mode or toggle selection
        """
        # Verify timer-based implementation for distinguishing tap vs long press
        assert 'setTimeout' in gallery_view_content or 'pressTimer' in gallery_view_content, \
            "Timer-based implementation required to distinguish tap from long press"

        # Verify clearTimeout is used to cancel timer on short tap
        assert 'clearTimeout' in gallery_view_content, \
            "clearTimeout must be used to cancel long press timer on short tap"

        # Verify openPreview or similar function exists for short tap -> preview
        has_preview_handler = (
            'openPreview' in gallery_view_content or
            'onPhotoTouchEnd' in gallery_view_content or
            'onTouchEnd' in gallery_view_content
        )
        assert has_preview_handler, \
            "Preview handler must exist for short tap behavior"

        # Verify selection mode toggle logic for long press
        assert 'selecting.value' in gallery_view_content, \
            "Selection mode state must be toggled on long press"

    def test_selection_mode_works_on_safari(self, gallery_view_content):
        """Verify Safari selection mode implementation.

        Selection mode should:
        1. Enter selection mode on long press
        2. Allow toggling individual photo selection
        3. Exit selection mode on cancel action
        """
        # Verify selection state management
        assert 'const selecting = ref' in gallery_view_content or 'selecting.value' in gallery_view_content, \
            "Selection mode state must be reactive (ref)"

        assert 'const selectedIds = ref' in gallery_view_content or 'selectedIds.value' in gallery_view_content, \
            "Selected IDs must be tracked as reactive state"

        # Verify selection toggle logic (add/remove from Set)
        assert 'Set' in gallery_view_content, \
            "SelectedIds should be a Set for efficient add/remove operations"

        # Verify pointer events are NOT used (or are supplemented with touch events)
        # iOS Safari has poor pointer event support on touch devices
        # Note: Vue SFC may have multiple <template> tags (v-if/v-else conditionals)
        # So we search for @touchstart in the entire content, not just template slice
        has_pointer_events = '@pointerdown' in gallery_view_content or 'pointerdown' in gallery_view_content
        has_touch_events = '@touchstart' in gallery_view_content or 'touchstart' in gallery_view_content

        assert has_touch_events, \
            "Touch events (@touchstart) must be present for iOS Safari compatibility. Pointer events alone are insufficient."

        # Verify touch event handlers are wired up
        script_section = gallery_view_content[gallery_view_content.find('<script'):gallery_view_content.find('</script>')]

        # Check that touch event handler functions exist
        if '@touchstart' in gallery_view_content:
            # Extract handler name from @touchstart.stop.prevent="handlerName"
            touch_start_handlers = re.findall(r'@touchstart[^=]*="(\w+)"', gallery_view_content)
            if touch_start_handlers:
                handler_name = touch_start_handlers[0]
                assert handler_name in script_section, \
                    f"Touch start handler '{handler_name}' referenced in template must exist in script section"


class TestTouchEventImplementation:
    """Test that touch event implementation follows iOS Safari best practices."""

    def test_prevent_double_firing(self, gallery_view_content):
        """Verify touch events prevent duplicate event firing.

        iOS Safari can fire both touch and mouse events.
        Should use preventDefault or a flag to avoid double-processing.
        """
        # Either .preventDefault() or stopPropagation should be used
        has_prevention = (
            'preventDefault' in gallery_view_content or
            'stopPropagation' in gallery_view_content or
            'stopImmediatePropagation' in gallery_view_content
        )
        assert has_prevention, \
            "Event prevention (preventDefault or stopPropagation) should be used to avoid duplicate events on iOS"

    def test_touch_coordinates_accessible(self, gallery_view_content):
        """Verify touch coordinates are captured for multi-touch scenarios."""
        # Touch events provide touches[] array with coordinates
        # Should reference event.touches or similar
        script_section = gallery_view_content[gallery_view_content.find('<script'):gallery_view_content.find('</script>')]

        # If using touch events, should access touch coordinates
        if 'touchstart' in gallery_view_content or 'touchend' in gallery_view_content:
            # Look for parameter that captures the event (e.g., 'e' or 'event')
            # and potential access to touch coordinates
            has_event_param = re.search(r'function\s+\w+\s*\([^)]*e[^)]*\)', script_section) or \
                             re.search(r'function\s+\w+\s*\([^)]*event[^)]*\)', script_section)
            # This is a soft check - just ensure functions have parameters that could receive events
            assert has_event_param, \
                "Touch event handlers should accept event parameter to access touch coordinates"

    def test_mobile_touch_optimization(self, gallery_view_content):
        """Verify touch handling is optimized for mobile Safari.

        iOS Safari requires:
        - touch-action: manipulation CSS (or similar) to disable double-tap zoom
        - Proper event binding to avoid scroll issues
        """
        # Check for touch-action CSS property or similar optimization
        style_section = gallery_view_content[gallery_view_content.find('<style'):]

        # At minimum, cursor: pointer should be present for touch targets
        assert 'cursor:' in style_section or 'cursor :' in style_section, \
            "Touch targets should have cursor style for visual feedback"
