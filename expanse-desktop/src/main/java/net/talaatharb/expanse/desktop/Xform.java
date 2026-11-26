package net.talaatharb.expanse.desktop;

import javafx.scene.Group;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * Transformable group from Oracle's JavaFX 3D sample application ("Building a 3D Sample Application"): a translate,
 * pivot, Z/Y/X rotations and scale applied in a fixed order so a camera rig can be orbited, panned and zoomed.
 */
class Xform extends Group {
    final Translate t = new Translate();
    final Translate p = new Translate();
    final Translate ip = new Translate();
    final Rotate rx = new Rotate(0, Rotate.X_AXIS);
    final Rotate ry = new Rotate(0, Rotate.Y_AXIS);
    final Rotate rz = new Rotate(0, Rotate.Z_AXIS);
    final Scale s = new Scale();

    Xform() {
        getTransforms().addAll(t, p, rz, ry, rx, s, ip);
    }

    void setTranslate(double x, double y, double z) {
        t.setX(x);
        t.setY(y);
        t.setZ(z);
    }

    void setRotate(double x, double y, double z) {
        rx.setAngle(x);
        ry.setAngle(y);
        rz.setAngle(z);
    }

    void reset() {
        setTranslate(0, 0, 0);
        setRotate(0, 0, 0);
        s.setX(1);
        s.setY(1);
        s.setZ(1);
    }
}
