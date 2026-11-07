module com.example.testing01 {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.logging;
    requires java.net.http;
    requires java.sql;
    requires java.desktop;
    requires org.xerial.sqlitejdbc;

    opens com.example.testing01 to javafx.fxml;
    exports com.example.testing01;
}