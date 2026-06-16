package com.taskqueue.gui;

import com.taskqueue.gui.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX application entry point for the Task Queue Dashboard.
 * <p>
 * Loads the main FXML layout, applies the dark-theme stylesheet,
 * and wires up the window lifecycle (including clean shutdown of
 * the background refresh scheduler).
 * </p>
 */
public class GuiMain extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/taskqueue/gui/main.fxml"));
        Parent root = loader.load();

        MainController controller = loader.getController();

        Scene scene = new Scene(root, 900, 600);
        scene.getStylesheets().add(getClass().getResource("/com/taskqueue/gui/styles.css").toExternalForm());

        primaryStage.setTitle("JavaTaskQueue Dashboard");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(700);
        primaryStage.setMinHeight(500);

        // Clean up background threads on window close
        primaryStage.setOnCloseRequest(event -> controller.shutdown());

        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
