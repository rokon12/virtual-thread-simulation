package vtmachine.view;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import vtmachine.model.Sim;

/** Optional second-screen confidence monitor for conference presentations. */
public final class SpeakerNotesWindow {
    private final Stage stage = new Stage();
    private final Label chapter = new Label();
    private final Label body = new Label();
    private final Label next = new Label();
    private final Label metrics = new Label();
    private final Label timer = new Label();
    private final ProgressBar progress = new ProgressBar();
    private Sim sim;

    public SpeakerNotesWindow(Window owner, Sim sim, String stylesheet) {
        this.sim = sim;
        stage.initOwner(owner);
        stage.setTitle("Virtual Thread Machine · Speaker Notes");
        chapter.getStyleClass().add("notes-chapter");
        body.getStyleClass().add("notes-body");
        body.setWrapText(true);
        next.getStyleClass().add("notes-next");
        next.setWrapText(true);
        metrics.getStyleClass().add("notes-metrics");
        timer.getStyleClass().add("notes-timer");
        progress.setMaxWidth(Double.MAX_VALUE);
        VBox root = new VBox(16, timer, chapter, body, progress, next, metrics);
        root.getStyleClass().add("speaker-notes");
        root.setPadding(new Insets(24));
        Scene scene = new Scene(root, 560, 520, Color.web("#070b12"));
        scene.getStylesheets().add(stylesheet);
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.N || event.getCode() == KeyCode.ESCAPE) stage.hide();
        });
        stage.setScene(scene);
    }

    public void toggle() {
        if (stage.isShowing()) {
            stage.hide();
        } else {
            if (Screen.getScreens().size() > 1) {
                var bounds = Screen.getScreens().get(1).getVisualBounds();
                stage.setX(bounds.getMinX() + 40);
                stage.setY(bounds.getMinY() + 40);
            }
            stage.show();
        }
    }

    public void sync(double elapsedSeconds, double fps, boolean autoplay) {
        if (!stage.isShowing()) return;
        int selected = sim.chapter();
        Sim.Stats stats = sim.stats();
        Sim.ProfileStats mix = sim.profileStats();
        timer.setText("%02d:%02d  ·  %s  ·  %.0f FPS".formatted(
                (int) elapsedSeconds / 60, (int) elapsedSeconds % 60,
                autoplay ? "AUTO-PLAY" : "MANUAL", fps));
        chapter.setText((selected + 1) + "/" + Sim.CHAPTER_COUNT + "  " + Hud.chapterTitle(selected));
        body.setText(Hud.chapterText(selected));
        next.setText("NEXT → " + Hud.chapterTitle(selected + 1) + "\n"
                + Hud.chapterText(selected + 1));
        metrics.setText("feed %s  ·  live %d  ·  runnable %d  ·  mounted %d/%d  ·  parked %d  ·  completed %d  ·  avg I/O %.1fs\n"
                .formatted(sim.liveMode() ? "LIVE JDK" : "SYNTHETIC", sim.vts().size(),
                        stats.runnable(), stats.mounted(), sim.carriers().size(), stats.parked(),
                        stats.completed(), sim.averageIoSeconds())
                + "task mix: " + mix.fast() + " fast · " + mix.compute() + " compute · "
                + mix.ioBound() + " I/O-bound\n"
                + "Keys: ←/→ chapter · Space pause · P presenter · A auto · 0 overview · Q quality · H contrast");
        progress.setProgress((selected + 1) / (double) Sim.CHAPTER_COUNT);
    }

    public void setSim(Sim sim) { this.sim = sim; }
    public void close() { stage.close(); }
}
