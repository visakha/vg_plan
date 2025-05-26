module org.vgplan.vg_plan {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.kordamp.bootstrapfx.core;
    requires java.sql;
    requires com.zaxxer.hikari;

    opens org.vgplan.vg_plan to javafx.fxml;
    exports org.vgplan.vg_plan;
}