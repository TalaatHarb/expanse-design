package net.talaatharb.expanse.desktop;

import javafx.application.Application;

public final class DesktopLauncher {
    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(WorkbenchApplication.class, args);
    }
}
