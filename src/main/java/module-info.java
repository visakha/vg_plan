module org.vgplan.plan {
    requires javafx.controls;
    requires javafx.fxml;
    requires transitive javafx.graphics;
    requires org.kordamp.bootstrapfx.core;
    requires java.sql;
    requires com.zaxxer.hikari;

    opens org.vgplan.plan to javafx.fxml;

    exports org.vgplan.plan;
}
