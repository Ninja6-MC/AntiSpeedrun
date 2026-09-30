package com.ninja6.antispeedrun.listeners;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One player's secondary-dragon boss bars (#21). Not thread-safe: every call comes from the
 * viewer's own region, which is the point of keeping bars per viewer (see
 * {@link SecondaryDragonRules}).
 *
 * @param <B> the bar type; Adventure's {@code BossBar} in the plugin, a stand-in in tests
 */
public final class ViewerBars<B> {

    /** How bars are made, shown, filled and hidden for this viewer. */
    public interface Display<B> {

        B create(float progress);

        void show(B bar);

        void fill(B bar, float progress);

        void hide(B bar);
    }

    private final Display<B> display;
    private final Map<UUID, B> bars = new LinkedHashMap<>();
    private final Map<UUID, Float> shown = new LinkedHashMap<>();

    public ViewerBars(Display<B> display) {
        this.display = Objects.requireNonNull(display, "display");
    }

    /**
     * Makes the viewer's bars match {@code visible}: a bar for a dragon no longer in it is hidden, a
     * new dragon gets a bar, and a changed fill is updated.
     *
     * @param visible each dragon the viewer should see, with its fill; empty to see none
     */
    public void sync(Map<UUID, Float> visible) {
        Iterator<Map.Entry<UUID, B>> it = bars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, B> entry = it.next();
            if (!visible.containsKey(entry.getKey())) {
                display.hide(entry.getValue());
                shown.remove(entry.getKey());
                it.remove();
            }
        }
        for (Map.Entry<UUID, Float> entry : visible.entrySet()) {
            float progress = entry.getValue();
            B bar = bars.get(entry.getKey());
            if (bar == null) {
                bar = display.create(progress);
                bars.put(entry.getKey(), bar);
                shown.put(entry.getKey(), progress);
                display.show(bar);
            } else if (Float.compare(shown.get(entry.getKey()), progress) != 0) {
                display.fill(bar, progress);
                shown.put(entry.getKey(), progress);
            }
        }
    }

    /** Hides every bar. */
    public void hideAll() {
        sync(Map.of());
    }

    /** How many bars the viewer is shown. */
    public int size() {
        return bars.size();
    }
}
